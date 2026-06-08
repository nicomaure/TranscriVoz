package ar.com.nicomaure.transcrivoz

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

private val Bg = Color(0xFF0B1020)
private val SurfaceDark = Color(0xFF121A2E)
private val SurfaceSoft = Color(0xFF182238)
private val Border = Color(0xFF293650)
private val Accent = Color(0xFF50E6FF)
private val AccentStrong = Color(0xFF00B8D4)
private val TextMain = Color(0xFFE6EDF7)
private val TextMuted = Color(0xFF93A4BA)
private val Danger = Color(0xFFFF6B6B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TranscriVozApp()
        }
    }
}

enum class Provider(
    val id: String,
    val title: String,
    val keyPrefix: String,
    val keyUrl: String,
    val endpoint: String,
    val models: List<Pair<String, String>>,
) {
    Groq(
        id = "groq",
        title = "Groq (gratis con limite)",
        keyPrefix = "gsk_",
        keyUrl = "https://console.groq.com/keys",
        endpoint = "https://api.groq.com/openai/v1/audio/transcriptions",
        models = listOf(
            "whisper-large-v3-turbo" to "Whisper v3 Turbo (rapido)",
            "whisper-large-v3" to "Whisper v3 (preciso)",
        ),
    ),
    OpenAI(
        id = "openai",
        title = "OpenAI (pago, sin limite)",
        keyPrefix = "sk-",
        keyUrl = "https://platform.openai.com/api-keys",
        endpoint = "https://api.openai.com/v1/audio/transcriptions",
        models = listOf(
            "whisper-1" to "Whisper v3",
        ),
    );

    companion object {
        fun fromId(id: String): Provider = entries.firstOrNull { it.id == id } ?: Groq
    }
}

private data class AppState(
    val provider: Provider = Provider.Groq,
    val model: String = Provider.Groq.models.first().first,
    val apiKey: String = "",
    val selectedUri: Uri? = null,
    val selectedName: String = "",
    val busy: Boolean = false,
    val progress: Float = 0f,
    val status: String = "Elegi un archivo de audio o video para empezar.",
    val transcript: String = "",
    val error: String = "",
)

@Composable
private fun TranscriVozApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("transcrivoz", Context.MODE_PRIVATE) }
    val apiKeyStore = remember { ApiKeyStore(context) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(AppState()) }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val provider = Provider.fromId(prefs.getString("provider", Provider.Groq.id) ?: Provider.Groq.id)
        val model = prefs.getString("model", provider.models.first().first) ?: provider.models.first().first
        state = state.copy(
            provider = provider,
            model = model.takeIf { saved -> provider.models.any { it.first == saved } } ?: provider.models.first().first,
            apiKey = apiKeyStore.get(provider.id),
        )
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            state = state.copy(
                selectedUri = uri,
                selectedName = getDisplayName(context, uri),
                transcript = "",
                error = "",
                status = "Archivo listo para transcribir.",
                progress = 0f,
            )
        }
    }

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(state.transcript.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(context, "Transcripcion guardada", Toast.LENGTH_SHORT).show()
        }
    }

    MaterialTheme {
        Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 18.dp, top = 22.dp, end = 18.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Header(onSettings = { showSettings = true })
                FilePanel(
                    state = state,
                    onPick = { pickFile.launch("audio/*") },
                    onPickAny = { pickFile.launch("*/*") },
                    onTranscribe = {
                        val uri = state.selectedUri
                        if (uri == null) {
                            state = state.copy(error = "Primero selecciona un archivo.")
                            return@FilePanel
                        }
                        if (!state.apiKey.startsWith(state.provider.keyPrefix)) {
                            state = state.copy(error = "Configura una API key valida en Ajustes.")
                            showSettings = true
                            return@FilePanel
                        }

                        scope.launch {
                            state = state.copy(
                                busy = true,
                                error = "",
                                transcript = "",
                                progress = 0.12f,
                                status = "Preparando archivo...",
                            )
                            try {
                                val transcript = transcribeAudio(
                                    context = context,
                                    provider = state.provider,
                                    apiKey = state.apiKey,
                                    model = state.model,
                                    uri = uri,
                                    fileName = state.selectedName.ifBlank { "audio.mp3" },
                                ) { progress, status ->
                                    state = state.copy(progress = progress, status = status)
                                }
                                state = state.copy(
                                    busy = false,
                                    progress = 1f,
                                    status = "Transcripcion completa.",
                                    transcript = transcript.trim(),
                                )
                            } catch (e: Exception) {
                                state = state.copy(
                                    busy = false,
                                    error = e.message ?: "No se pudo transcribir el archivo.",
                                    status = "Error en la transcripcion.",
                                )
                            }
                        }
                    },
                )
                ProgressPanel(state)
                ResultPanel(
                    transcript = state.transcript,
                    onCopy = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("TranscriVoz", state.transcript))
                        Toast.makeText(context, "Texto copiado", Toast.LENGTH_SHORT).show()
                    },
                    onSave = {
                        val baseName = state.selectedName.substringBeforeLast('.', "transcripcion")
                        saveFile.launch("${baseName}_transcripcion.txt")
                    },
                    onNew = {
                        state = state.copy(
                            selectedUri = null,
                            selectedName = "",
                            busy = false,
                            progress = 0f,
                            status = "Elegi un archivo de audio o video para empezar.",
                            transcript = "",
                            error = "",
                        )
                    },
                )
                Footer()
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            state = state,
            savedApiKeys = Provider.entries.associateWith { apiKeyStore.get(it.id) },
            onDismiss = { showSettings = false },
            onSave = { provider, model, apiKey ->
                if (apiKey.isBlank()) {
                    apiKeyStore.delete(provider.id)
                } else {
                    apiKeyStore.save(provider.id, apiKey)
                }
                prefs.edit()
                    .putString("provider", provider.id)
                    .putString("model", model)
                    .apply()
                state = state.copy(
                    provider = provider,
                    model = model,
                    apiKey = apiKeyStore.get(provider.id),
                    error = "",
                )
                showSettings = false
            },
            onClear = { provider ->
                apiKeyStore.delete(provider.id)
                if (state.provider == provider) {
                    state = state.copy(apiKey = "", error = "")
                }
            },
        )
    }
}

@Composable
private fun Header(onSettings: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, Border),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "TranscriVoz",
                    color = Accent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Audio y video a texto",
                    color = TextMuted,
                    fontSize = 13.sp,
                )
            }
            OutlinedButton(
                onClick = onSettings,
                border = BorderStroke(1.dp, Border),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMain),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Ajustes")
            }
        }
    }
}

@Composable
private fun Footer() {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Desarrollado por", color = TextMuted, fontSize = 12.sp)
        TextButton(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://nicomaure.com.ar"))
                context.startActivity(intent)
            },
        ) {
            Text("nicomaure", color = Accent, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun FilePanel(
    state: AppState,
    onPick: () -> Unit,
    onPickAny: () -> Unit,
    onTranscribe: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, Border),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Archivo", color = Accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceSoft, RoundedCornerShape(8.dp))
                    .padding(18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.selectedName.ifBlank { "Selecciona un audio o video" },
                        color = if (state.selectedName.isBlank()) TextMuted else TextMain,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onPick,
                            colors = ButtonDefaults.buttonColors(containerColor = AccentStrong, contentColor = Color.White),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text("Elegir audio")
                        }
                        OutlinedButton(
                            onClick = onPickAny,
                            border = BorderStroke(1.dp, Border),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMain),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text("Video")
                        }
                    }
                }
            }
            Button(
                onClick = onTranscribe,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentStrong, contentColor = Color.White),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(if (state.busy) "Transcribiendo..." else "Transcribir")
            }
            Text(
                text = "Proveedor: ${state.provider.title} | Modelo: ${state.model}",
                color = TextMuted,
                fontSize = 12.sp,
            )
            if (state.error.isNotBlank()) {
                Text(state.error, color = Danger, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ProgressPanel(state: AppState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, Border),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.busy) {
                    CircularProgressIndicator(
                        color = Accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(state.status, color = TextMain, fontSize = 14.sp)
            }
            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = Accent,
                trackColor = SurfaceSoft,
            )
        }
    }
}

@Composable
private fun ResultPanel(
    transcript: String,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onNew: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, Border),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Resultado", color = Accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onNew,
                    enabled = transcript.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, Border),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMain),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Nuevo", fontSize = 12.sp, maxLines = 1)
                }
                OutlinedButton(
                    onClick = onCopy,
                    enabled = transcript.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, Border),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMain),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Copiar", fontSize = 12.sp, maxLines = 1)
                }
                Button(
                    onClick = onSave,
                    enabled = transcript.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentStrong, contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Guardar", fontSize = 12.sp, maxLines = 1)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(Bg, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                Text(
                    text = transcript.ifBlank { "La transcripcion aparecera aca." },
                    color = if (transcript.isBlank()) TextMuted else TextMain,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDialog(
    state: AppState,
    savedApiKeys: Map<Provider, String>,
    onDismiss: () -> Unit,
    onSave: (Provider, String, String) -> Unit,
    onClear: (Provider) -> Unit,
) {
    var provider by remember { mutableStateOf(state.provider) }
    var model by remember { mutableStateOf(state.model) }
    var apiKey by remember { mutableStateOf(savedApiKeys[state.provider].orEmpty()) }
    var providerExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        titleContentColor = Accent,
        textContentColor = TextMain,
        title = {
            Text("Configuracion", fontFamily = FontFamily.Monospace)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExposedDropdownMenuBox(
                    expanded = providerExpanded,
                    onExpandedChange = { providerExpanded = !providerExpanded },
                ) {
                    OutlinedTextField(
                        value = provider.title,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Proveedor") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(providerExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        colors = darkTextFieldColors(),
                    )
                    ExposedDropdownMenu(
                        expanded = providerExpanded,
                        onDismissRequest = { providerExpanded = false },
                    ) {
                        Provider.entries.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.title) },
                                onClick = {
                                    provider = item
                                    model = item.models.first().first
                                    apiKey = savedApiKeys[item].orEmpty()
                                    providerExpanded = false
                                },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it.trim() },
                    label = { Text("API Key") },
                    placeholder = { Text(provider.keyPrefix + "...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = darkTextFieldColors(),
                    visualTransformation = PasswordVisualTransformation(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (apiKey.isBlank()) "Sin API key guardada" else "Key guardada: ${maskApiKey(apiKey)}",
                        color = TextMuted,
                        fontSize = 12.sp,
                    )
                    TextButton(
                        onClick = {
                            apiKey = ""
                            onClear(provider)
                        },
                        enabled = apiKey.isNotBlank(),
                    ) {
                        Text("Borrar", color = if (apiKey.isBlank()) TextMuted else Danger)
                    }
                }
                ApiKeyLinkButton(provider = provider)

                ExposedDropdownMenuBox(
                    expanded = modelExpanded,
                    onExpandedChange = { modelExpanded = !modelExpanded },
                ) {
                    val modelLabel = provider.models.firstOrNull { it.first == model }?.second ?: model
                    OutlinedTextField(
                        value = modelLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Modelo") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        colors = darkTextFieldColors(),
                    )
                    ExposedDropdownMenu(
                        expanded = modelExpanded,
                        onDismissRequest = { modelExpanded = false },
                    ) {
                        provider.models.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.second) },
                                onClick = {
                                    model = item.first
                                    modelExpanded = false
                                },
                            )
                        }
                    }
                }

                Text(
                    text = "La key se guarda cifrada en este telefono. Para audios grandes se intenta dividir automaticamente antes de enviar.",
                    color = TextMuted,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(provider, model, apiKey) },
                colors = ButtonDefaults.buttonColors(containerColor = AccentStrong, contentColor = Color.White),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = TextMuted)
            }
        },
    )
}

@Composable
private fun ApiKeyLinkButton(
    provider: Provider,
) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.keyUrl)))
        },
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, Border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text("Crear key de ${provider.displayName()}", fontSize = 12.sp, maxLines = 1)
    }
}

private fun Provider.displayName(): String = when (this) {
    Provider.Groq -> "Groq"
    Provider.OpenAI -> "OpenAI"
}

private fun maskApiKey(apiKey: String): String {
    if (apiKey.length <= 10) return "configurada"
    return "${apiKey.take(4)}...${apiKey.takeLast(4)}"
}

@Composable
private fun darkTextFieldColors() = TextFieldDefaults.colors(
    focusedTextColor = TextMain,
    unfocusedTextColor = TextMain,
    focusedContainerColor = Bg,
    unfocusedContainerColor = Bg,
    focusedIndicatorColor = Accent,
    unfocusedIndicatorColor = Border,
    focusedLabelColor = Accent,
    unfocusedLabelColor = TextMuted,
    cursorColor = Accent,
)

private fun getDisplayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
            return cursor.getString(nameIndex)
        }
    }
    return uri.lastPathSegment ?: "audio"
}

private suspend fun transcribeAudio(
    context: Context,
    provider: Provider,
    apiKey: String,
    model: String,
    uri: Uri,
    fileName: String,
    onProgress: (Float, String) -> Unit,
): String = withContext(Dispatchers.IO) {
    val parts = prepareAudioParts(context, uri, fileName, onProgress)
    try {
        if (parts.size == 1) {
            onProgress(0.35f, "Subiendo archivo a ${provider.title}...")
            return@withContext transcribePreparedPart(provider, apiKey, model, parts.first())
        }

        val results = mutableListOf<String>()
        parts.forEachIndexed { index, part ->
            val baseProgress = 0.4f + (0.5f * index / parts.size.toFloat())
            onProgress(baseProgress, "Transcribiendo parte ${index + 1} de ${parts.size}...")
            results.add(transcribePreparedPart(provider, apiKey, model, part).trim())
        }
        onProgress(0.95f, "Uniendo transcripcion...")
        results.filter { it.isNotBlank() }.joinToString("\n\n")
    } finally {
        parts.forEach { it.file.delete() }
    }
}

private fun transcribePreparedPart(
    provider: Provider,
    apiKey: String,
    model: String,
    part: PreparedAudioPart,
): String {
    val boundary = "TranscriVoz-${UUID.randomUUID()}"
    val connection = (URL(provider.endpoint).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 30_000
        readTimeout = 10 * 60_000
        doInput = true
        doOutput = true
        setRequestProperty("Authorization", "Bearer $apiKey")
        setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
    }

    BufferedOutputStream(connection.outputStream).use { output ->
        writeTextPart(output, boundary, "model", model)
        writeTextPart(output, boundary, "response_format", "text")
        writeFilePart(output, boundary, "file", part.fileName, part.file)
        output.write("--$boundary--\r\n".toByteArray())
        output.flush()
    }

    val code = connection.responseCode
    val body = if (code in 200..299) {
        connection.inputStream.bufferedReader().use { it.readText() }
    } else {
        connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }
    connection.disconnect()

    if (code !in 200..299) {
        throw RuntimeException(parseApiError(code, body))
    }

    return body
}

private fun writeTextPart(
    output: BufferedOutputStream,
    boundary: String,
    name: String,
    value: String,
) {
    output.write("--$boundary\r\n".toByteArray())
    output.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
    output.write(value.toByteArray(Charsets.UTF_8))
    output.write("\r\n".toByteArray())
}

private fun writeFilePart(
    output: BufferedOutputStream,
    boundary: String,
    name: String,
    fileName: String,
    file: java.io.File,
) {
    val mimeType = when (file.extension.lowercase()) {
        "m4a", "mp4" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "webm" -> "audio/webm"
        else -> "application/octet-stream"
    }
    output.write("--$boundary\r\n".toByteArray())
    output.write("Content-Disposition: form-data; name=\"$name\"; filename=\"$fileName\"\r\n".toByteArray())
    output.write("Content-Type: $mimeType\r\n\r\n".toByteArray())
    file.inputStream().use { input ->
        BufferedInputStream(input).copyTo(output)
    }
    output.write("\r\n".toByteArray())
}

private fun parseApiError(code: Int, body: String): String {
    val clean = body.replace('\n', ' ').take(280)
    return when (code) {
        401 -> "API key invalida o sin permisos."
        413 -> "El archivo es demasiado grande para enviarlo en una sola parte."
        429 -> "Limite de la API alcanzado. Espera unos minutos y volve a intentar."
        else -> "Error de transcripcion ($code): $clean"
    }
}
