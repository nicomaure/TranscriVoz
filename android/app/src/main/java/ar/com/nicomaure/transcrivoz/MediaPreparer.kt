package ar.com.nicomaure.transcrivoz

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max

const val MAX_API_FILE_BYTES = 19L * 1024L * 1024L

data class PreparedAudioPart(
    val file: File,
    val fileName: String,
)

suspend fun prepareAudioParts(
    context: Context,
    uri: Uri,
    fileName: String,
    onProgress: (Float, String) -> Unit,
): List<PreparedAudioPart> {
    val size = getContentSize(context, uri)
    if (size in 1..MAX_API_FILE_BYTES) {
        return listOf(copyUriToCache(context, uri, fileName, "original"))
    }

    onProgress(0.18f, "Archivo grande: preparando partes...")
    val source = copyUriToCache(context, uri, fileName, "source")
    return try {
        splitMediaFile(context, source.file, fileName, size, onProgress)
    } catch (e: Exception) {
        source.file.delete()
        throw RuntimeException(
            "El archivo supera 19 MB y no se pudo dividir automaticamente en Android. " +
                "Proba con M4A/MP4/AAC, o comprimilo antes de subirlo. Detalle: ${e.message ?: "formato no compatible"}"
        )
    }
}

private fun copyUriToCache(
    context: Context,
    uri: Uri,
    fileName: String,
    prefix: String,
): PreparedAudioPart {
    val extension = fileName.substringAfterLast('.', "audio").lowercase()
    val safeExtension = extension.takeIf { it.length in 2..5 } ?: "audio"
    val file = File.createTempFile("transcrivoz-$prefix-", ".$safeExtension", context.cacheDir)
    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(file).use { output -> input.copyTo(output) }
    } ?: throw RuntimeException("No se pudo abrir el archivo seleccionado.")
    return PreparedAudioPart(file = file, fileName = fileName)
}

private fun splitMediaFile(
    context: Context,
    source: File,
    fileName: String,
    sourceSize: Long,
    onProgress: (Float, String) -> Unit,
): List<PreparedAudioPart> {
    val durationUs = getDurationUs(source)
    if (durationUs <= 0) {
        throw RuntimeException("duracion invalida")
    }

    val partCount = max(2, ceil(sourceSize.toDouble() / MAX_API_FILE_BYTES.toDouble()).toInt())
    val partDurationUs = durationUs / partCount
    val baseName = fileName.substringBeforeLast('.', "audio")
    val parts = mutableListOf<PreparedAudioPart>()

    for (index in 0 until partCount) {
        val startUs = index * partDurationUs
        val endUs = if (index == partCount - 1) durationUs else (index + 1) * partDurationUs
        onProgress(
            0.2f + (0.18f * index / partCount.toFloat()),
            "Dividiendo parte ${index + 1} de $partCount..."
        )
        val output = File(context.cacheDir, "transcrivoz-part-${System.nanoTime()}-$index.m4a")
        muxTimeRange(source, output, startUs, endUs)
        if (!output.exists() || output.length() == 0L) {
            output.delete()
            throw RuntimeException("parte vacia")
        }
        if (output.length() > MAX_API_FILE_BYTES) {
            output.delete()
            throw RuntimeException("una parte sigue superando 19 MB")
        }
        parts.add(PreparedAudioPart(output, "${baseName}_parte_${index + 1}.m4a"))
    }

    source.delete()
    return parts
}

private fun getDurationUs(file: File): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val durationMs = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        durationMs * 1000L
    } finally {
        retriever.release()
    }
}

private fun muxTimeRange(
    source: File,
    output: File,
    startUs: Long,
    endUs: Long,
) {
    val extractor = MediaExtractor()
    var muxer: MediaMuxer? = null
    try {
        extractor.setDataSource(source.absolutePath)
        val selectedTrack = selectBestAudioTrack(extractor)
        if (selectedTrack < 0) {
            throw RuntimeException("no se encontro una pista de audio compatible")
        }
        extractor.selectTrack(selectedTrack)
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        val format = extractor.getTrackFormat(selectedTrack)
        muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val outputTrack = muxer.addTrack(format)
        muxer.start()

        val maxInputSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else {
            1024 * 1024
        }
        val buffer = ByteBuffer.allocateDirect(maxInputSize.coerceAtLeast(256 * 1024))
        val info = MediaCodec.BufferInfo()

        while (true) {
            val sampleTime = extractor.sampleTime
            if (sampleTime < 0 || sampleTime >= endUs) break

            info.offset = 0
            info.size = extractor.readSampleData(buffer, 0)
            if (info.size < 0) break

            info.presentationTimeUs = sampleTime - startUs
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(outputTrack, buffer, info)
            extractor.advance()
        }
    } finally {
        try {
            muxer?.stop()
        } catch (_: Exception) {
        }
        muxer?.release()
        extractor.release()
    }
}

private fun selectBestAudioTrack(extractor: MediaExtractor): Int {
    for (index in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(index)
        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
        if (mime.startsWith("audio/")) return index
    }
    return -1
}

private fun getContentSize(context: Context, uri: Uri): Long {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (sizeIndex >= 0 && cursor.moveToFirst()) {
            val size = cursor.getLong(sizeIndex)
            if (size > 0) return size
        }
    }
    return context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
        descriptor.length
    } ?: -1L
}
