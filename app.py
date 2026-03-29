import os
import re
import uuid
import shutil
import hashlib
import subprocess
import json
import atexit
from pathlib import Path
from functools import wraps

from flask import (
    Flask, request, session, redirect, url_for,
    render_template, Response, jsonify, send_file
)
from dotenv import load_dotenv
from werkzeug.middleware.proxy_fix import ProxyFix
from groq import Groq
from openai import OpenAI

load_dotenv()

DESKTOP_MODE = os.environ.get("TRANSCRIVOZ_DESKTOP") == "1"

app = Flask(__name__)
if not DESKTOP_MODE:
    app.wsgi_app = ProxyFix(app.wsgi_app, x_prefix=1)
app.secret_key = os.environ.get("SECRET_KEY", os.urandom(32).hex())
app.config["MAX_CONTENT_LENGTH"] = 1 * 1024 * 1024 * 1024  # 1GB
# Werkzeug: guardar a disco archivos > 1MB (no en RAM)
app.config["MAX_FORM_MEMORY_SIZE"] = 1 * 1024 * 1024

PASSWORD_HASH = os.environ.get("PASSWORD_HASH", "")
MAX_CHUNK_SIZE = 19 * 1024 * 1024  # 19MB

PROVIDERS = {
    "groq": {
        "name": "Groq (gratis con limite)",
        "models": {
            "whisper-large-v3-turbo": "Whisper v3 Turbo (rapido)",
            "whisper-large-v3": "Whisper v3 (preciso)",
        },
        "key_prefix": "gsk_",
        "key_env": "GROQ_API_KEY",
    },
    "openai": {
        "name": "OpenAI (pago, sin limite)",
        "models": {
            "whisper-1": "Whisper v3",
        },
        "key_prefix": "sk-",
        "key_env": "OPENAI_API_KEY",
    },
}

# Runtime config (can be changed from UI)
config = {
    "provider": "groq",
    "api_keys": {
        "groq": os.environ.get("GROQ_API_KEY", ""),
        "openai": os.environ.get("OPENAI_API_KEY", ""),
    },
    "model": "whisper-large-v3-turbo",
}


def get_client():
    provider = config["provider"]
    api_key = config["api_keys"].get(provider, "")
    if provider == "groq":
        return Groq(api_key=api_key)
    elif provider == "openai":
        return OpenAI(api_key=api_key)
    raise RuntimeError(f"Proveedor desconocido: {provider}")


def get_current_model():
    provider = config["provider"]
    available = PROVIDERS[provider]["models"]
    if config["model"] in available:
        return config["model"]
    # Return first available model for this provider
    return next(iter(available))


# Job storage: job_id -> {status, messages[], result, error, chunks, offsets, ...}
jobs = {}

ALLOWED_EXTENSIONS = {
    "mp3", "mp4", "m4a", "wav", "ogg", "webm", "flac", "aac", "wma", "mpeg", "mpga"
}


def check_password(password):
    h = hashlib.pbkdf2_hmac(
        "sha256", password.encode(), b"transcriptor-groq", 100000
    ).hex()
    return h == PASSWORD_HASH


# Brute force protection
login_attempts = {}  # ip -> {"count": n, "blocked_until": timestamp}
MAX_LOGIN_ATTEMPTS = 5
BLOCK_DURATION = 300  # 5 minutes


def check_rate_limit(ip):
    import time
    now = time.time()
    if ip in login_attempts:
        info = login_attempts[ip]
        if info.get("blocked_until") and now < info["blocked_until"]:
            remaining = int(info["blocked_until"] - now)
            return False, remaining
        if info.get("blocked_until") and now >= info["blocked_until"]:
            login_attempts[ip] = {"count": 0}
    return True, 0


def record_failed_login(ip):
    import time
    if ip not in login_attempts:
        login_attempts[ip] = {"count": 0}
    login_attempts[ip]["count"] += 1
    if login_attempts[ip]["count"] >= MAX_LOGIN_ATTEMPTS:
        login_attempts[ip]["blocked_until"] = time.time() + BLOCK_DURATION


def reset_login_attempts(ip):
    if ip in login_attempts:
        del login_attempts[ip]


def require_auth(f):
    @wraps(f)
    def decorated(*args, **kwargs):
        if DESKTOP_MODE or session.get("authenticated"):
            return f(*args, **kwargs)
        return jsonify({"error": "No autorizado"}), 401
    return decorated


def emit(job_id, event_type, message="", percent=0, text="", **extra):
    if job_id not in jobs:
        return
    event = {"type": event_type, "message": message, "percent": percent, "text": text}
    event.update(extra)
    jobs[job_id]["messages"].append(event)


def get_duration(filepath):
    result = subprocess.run(
        ["ffprobe", "-v", "quiet", "-show_entries", "format=duration",
         "-of", "csv=p=0", filepath],
        capture_output=True, text=True, timeout=30
    )
    return float(result.stdout.strip())


def format_duration(seconds):
    mins = int(seconds // 60)
    secs = int(seconds % 60)
    return f"{mins}m {secs:02d}s"


def convert_to_mp3(input_path, output_path, job_id):
    emit(job_id, "progress", "Convirtiendo audio...", 5)
    subprocess.run(
        ["ffmpeg", "-y", "-i", input_path,
         "-ar", "16000", "-ac", "1", "-b:a", "32k", output_path],
        capture_output=True, timeout=600
    )
    if not os.path.exists(output_path):
        raise RuntimeError("Error al convertir el archivo con ffmpeg")


def split_audio(mp3_path, job_dir, job_id):
    file_size = os.path.getsize(mp3_path)

    if file_size <= MAX_CHUNK_SIZE:
        return [mp3_path]

    duration = get_duration(mp3_path)
    chunk_duration = int(duration * (MAX_CHUNK_SIZE / file_size) * 0.90)
    num_chunks = int(duration / chunk_duration) + 1

    emit(job_id, "progress", f"Dividiendo en {num_chunks} partes...", 10)

    chunks = []
    for i in range(num_chunks):
        start = i * chunk_duration
        chunk_path = os.path.join(job_dir, f"chunk_{i:03d}.mp3")
        subprocess.run(
            ["ffmpeg", "-y", "-i", mp3_path,
             "-ss", str(start), "-t", str(chunk_duration),
             "-acodec", "copy", chunk_path],
            capture_output=True, timeout=300
        )
        if os.path.exists(chunk_path) and os.path.getsize(chunk_path) > 0:
            chunks.append(chunk_path)

    return chunks


def parse_rate_limit_wait(error_msg):
    match = re.search(r"(\d+)m\s*(\d+(?:\.\d+)?)s", str(error_msg))
    if match:
        return int(match.group(1)) * 60 + float(match.group(2))
    match = re.search(r"(\d+(?:\.\d+)?)s", str(error_msg))
    if match:
        return float(match.group(1))
    return 60


def transcribe_chunk(chunk_path, offset_seconds, job_id, chunk_num, total_chunks):
    import time
    max_retries = 5

    for attempt in range(max_retries):
        try:
            with open(chunk_path, "rb") as f:
                model = get_current_model()
                params = {
                    "file": (os.path.basename(chunk_path), f.read()),
                    "model": model,
                    "language": "es",
                    "response_format": "verbose_json",
                }
                # temperature only supported by Groq
                if config["provider"] == "groq":
                    params["temperature"] = 0
                result = get_client().audio.transcriptions.create(**params)

            segments = []
            if result.segments:
                for seg in result.segments:
                    segments.append({
                        "start": seg["start"] + offset_seconds,
                        "text": seg["text"].strip()
                    })
            return segments

        except Exception as e:
            error_str = str(e)
            if "429" in error_str or "rate_limit" in error_str.lower():
                import time as _time
                wait_time = parse_rate_limit_wait(error_str)
                wait_time = min(wait_time + 2, 3600)
                # Save rate limit info so UI can show it
                if job_id in jobs:
                    jobs[job_id]["rate_limit_until"] = _time.time() + wait_time
                remaining = int(wait_time)
                pct = 10 + (80 * chunk_num / total_chunks)
                while remaining > 0:
                    if jobs.get(job_id, {}).get("cancelled"):
                        raise RuntimeError("Cancelado por el usuario")
                    mins = remaining // 60
                    secs = remaining % 60
                    emit(
                        job_id, "progress",
                        f"Limite de API. Reintentando en {mins}m {secs:02d}s...",
                        pct
                    )
                    step = min(10, remaining)
                    time.sleep(step)
                    remaining -= step
                emit(job_id, "progress", "Reintentando...", pct)
            else:
                if attempt < max_retries - 1:
                    time.sleep(5)
                else:
                    raise

    raise RuntimeError(f"No se pudo transcribir chunk {chunk_num} despues de {max_retries} intentos")


def prepare_job(job_id):
    """Convert and split only. Stops before transcription so user can pick parts."""
    job = jobs[job_id]
    job_dir = job["job_dir"]
    original_path = job["original_path"]

    try:
        # Step 1: Convert to MP3
        mp3_path = os.path.join(job_dir, "audio.mp3")
        convert_to_mp3(original_path, mp3_path, job_id)

        # Remove original to save disk
        if os.path.exists(original_path) and original_path != mp3_path:
            os.remove(original_path)

        # Step 2: Split
        chunks = split_audio(mp3_path, job_dir, job_id)

        # Get chunk offsets and durations
        offsets = []
        durations = []
        cumulative = 0
        for chunk_path in chunks:
            offsets.append(cumulative)
            try:
                dur = get_duration(chunk_path)
                durations.append(dur)
                cumulative += dur
            except Exception:
                durations.append(0)

        # Build chunk info for frontend
        chunk_info = []
        for i, chunk_path in enumerate(chunks):
            size_mb = os.path.getsize(chunk_path) / (1024 * 1024)
            chunk_info.append({
                "index": i,
                "label": f"Parte {i+1}",
                "start": format_duration(offsets[i]),
                "duration": format_duration(durations[i]),
                "size": f"{size_mb:.1f} MB"
            })

        # Save to job
        job["chunks"] = chunks
        job["offsets"] = offsets
        job["durations"] = durations
        job["chunk_info"] = chunk_info
        job["prepared"] = True
        job["transcribed_parts"] = {}  # index -> segments

        emit(job_id, "progress", "Audio listo!", 12)
        emit(job_id, "chunks_ready", chunks=chunk_info)

    except Exception as e:
        emit(job_id, "error", message=str(e))


def transcribe_selected(job_id, selected_indices):
    """Transcribe only the selected chunk indices."""
    import time
    job = jobs[job_id]
    chunks = job["chunks"]
    offsets = job["offsets"]
    total = len(chunks)
    selected = sorted(selected_indices)
    num_selected = len(selected)

    try:
        all_segments = []
        completed = []
        failed_at = None

        for step, i in enumerate(selected):
            if job.get("cancelled"):
                failed_at = i + 1
                emit(job_id, "progress", "Cancelado por el usuario", int(15 + (75 * step / num_selected)))
                break

            # Skip if already transcribed
            if i in job["transcribed_parts"]:
                emit(job_id, "progress",
                     f"Parte {i+1}/{total} ya transcripta, saltando...",
                     15 + (75 * step / num_selected))
                all_segments.extend(job["transcribed_parts"][i])
                completed.append(i)
                continue

            pct = 15 + (75 * step / num_selected)
            emit(job_id, "progress", f"Transcribiendo parte {i+1}/{total}...", int(pct))

            try:
                segments = transcribe_chunk(chunks[i], offsets[i], job_id, i, total)
                job["transcribed_parts"][i] = segments
                all_segments.extend(segments)
                completed.append(i)
            except Exception as e:
                failed_at = i + 1
                emit(
                    job_id, "progress",
                    f"Error en parte {i+1}/{total}: {str(e)[:100]}",
                    int(pct)
                )
                break

        # Sort all segments by start time
        all_segments.sort(key=lambda s: s["start"])

        # Format result
        lines = []
        for seg in all_segments:
            mins = int(seg["start"] // 60)
            secs = int(seg["start"] % 60)
            lines.append(f"[{mins:02d}:{secs:02d}] {seg['text']}")

        result_text = "\n".join(lines)

        # Determine remaining parts
        remaining = [i for i in selected if i not in completed]

        if failed_at and lines:
            result_text = f"[TRANSCRIPCION PARCIAL - Fallo en parte {failed_at}/{total}]\n\n" + result_text
            job["result"] = result_text
            job["failed"] = True
            emit(job_id, "progress",
                 f"Parcial: {len(completed)}/{num_selected} partes completadas", 90)
            emit(job_id, "partial_result", text=result_text, remaining=remaining,
                 chunks=job["chunk_info"])
        elif failed_at and not lines:
            job["failed"] = True
            emit(job_id, "partial_result", text="",
                 message="No se pudo transcribir nada", remaining=remaining,
                 chunks=job["chunk_info"])
        else:
            job["result"] = result_text
            job["failed"] = False
            emit(job_id, "progress", "Completado!", 100)
            emit(job_id, "result", text=result_text)

    except Exception as e:
        job["failed"] = True
        # Save whatever we have
        all_existing = []
        for idx in sorted(job["transcribed_parts"].keys()):
            all_existing.extend(job["transcribed_parts"][idx])
        if all_existing:
            all_existing.sort(key=lambda s: s["start"])
            lines = []
            for seg in all_existing:
                mins = int(seg["start"] // 60)
                secs = int(seg["start"] % 60)
                lines.append(f"[{mins:02d}:{secs:02d}] {seg['text']}")
            result_text = f"[TRANSCRIPCION PARCIAL - Error: {str(e)[:100]}]\n\n" + "\n".join(lines)
            job["result"] = result_text
            emit(job_id, "partial_result", text=result_text,
                 remaining=[i for i in selected if i not in job["transcribed_parts"]],
                 chunks=job["chunk_info"])
        else:
            emit(job_id, "error", message=str(e))


# ---- Routes ----

@app.route("/")
def index():
    authenticated = DESKTOP_MODE or session.get("authenticated", False)
    current_provider = config["provider"]
    has_api_key = bool(config["api_keys"].get(current_provider, ""))
    return render_template("index.html",
                           authenticated=authenticated,
                           has_api_key=has_api_key,
                           current_provider=current_provider,
                           current_model=config["model"],
                           providers=PROVIDERS)


@app.route("/login", methods=["POST"])
def login():
    ip = request.remote_addr
    allowed, wait = check_rate_limit(ip)
    if not allowed:
        return render_template("index.html", authenticated=False,
                               login_error=f"Demasiados intentos. Espera {wait}s")

    password = request.form.get("password", "")
    if check_password(password):
        session["authenticated"] = True
        session.permanent = True
        reset_login_attempts(ip)
        return redirect(url_for("index"))

    record_failed_login(ip)
    attempts_left = MAX_LOGIN_ATTEMPTS - login_attempts.get(ip, {}).get("count", 0)
    if attempts_left > 0:
        error = f"Contraseña incorrecta ({attempts_left} intentos restantes)"
    else:
        error = f"Bloqueado por {BLOCK_DURATION // 60} minutos"
    return render_template("index.html", authenticated=False, login_error=error)


@app.route("/logout", methods=["POST"])
def logout():
    session.clear()
    return redirect(url_for("index"))


@app.route("/settings", methods=["GET"])
@require_auth
def get_settings():
    provider = config["provider"]
    key = config["api_keys"].get(provider, "")
    if len(key) > 12:
        masked = key[:8] + "*" * (len(key) - 12) + key[-4:]
    elif key:
        masked = "****"
    else:
        masked = ""
    return jsonify({
        "api_key_masked": masked,
        "has_api_key": bool(key),
        "provider": provider,
        "model": get_current_model(),
        "providers": {k: v["name"] for k, v in PROVIDERS.items()},
        "models": PROVIDERS[provider]["models"],
    })


@app.route("/settings", methods=["POST"])
@require_auth
def update_settings():
    data = request.get_json()

    # Change provider
    if "provider" in data:
        if data["provider"] in PROVIDERS:
            config["provider"] = data["provider"]
        else:
            return jsonify({"error": "Proveedor no valido"}), 400

    provider = config["provider"]

    # Require password to change API key
    if "api_key" in data and data["api_key"].strip():
        password = data.get("password", "")
        if not check_password(password):
            return jsonify({"error": "Contraseña incorrecta para cambiar la API key"}), 403
        new_key = data["api_key"].strip()
        expected_prefix = PROVIDERS[provider]["key_prefix"]
        if not new_key.startswith(expected_prefix):
            return jsonify({"error": f"La API key de {PROVIDERS[provider]['name'].split(' (')[0]} debe empezar con {expected_prefix}"}), 400
        config["api_keys"][provider] = new_key

    if "model" in data:
        available = PROVIDERS[provider]["models"]
        if data["model"] in available:
            config["model"] = data["model"]
        else:
            return jsonify({"error": "Modelo no valido para este proveedor"}), 400

    return jsonify({
        "message": "Configuracion guardada",
        "provider": provider,
        "model": get_current_model(),
        "models": PROVIDERS[provider]["models"],
    })


@app.route("/rate-limit/<job_id>")
@require_auth
def rate_limit_status(job_id):
    import time
    if job_id not in jobs:
        return jsonify({"waiting": False})
    until = jobs[job_id].get("rate_limit_until", 0)
    remaining = max(0, int(until - time.time()))
    return jsonify({"waiting": remaining > 0, "remaining": remaining})


@app.route("/cancel/<job_id>", methods=["POST"])
@require_auth
def cancel(job_id):
    if job_id not in jobs:
        return jsonify({"error": "Job no encontrado"}), 404
    jobs[job_id]["cancelled"] = True
    return jsonify({"message": "Cancelado"})


@app.route("/upload", methods=["POST"])
@require_auth
def upload():
    if "file" not in request.files:
        return jsonify({"error": "No se envio archivo"}), 400

    file = request.files["file"]
    if not file.filename:
        return jsonify({"error": "Archivo sin nombre"}), 400

    ext = file.filename.rsplit(".", 1)[-1].lower() if "." in file.filename else ""
    if ext not in ALLOWED_EXTENSIONS:
        return jsonify({"error": f"Formato .{ext} no soportado"}), 400

    job_id = uuid.uuid4().hex[:12]
    job_dir = f"/tmp/transcriptor-{job_id}"
    os.makedirs(job_dir, exist_ok=True)

    original_path = os.path.join(job_dir, f"original.{ext}")
    file.save(original_path)

    jobs[job_id] = {
        "messages": [],
        "result": None,
        "original_path": original_path,
        "job_dir": job_dir,
        "filename": file.filename,
        "prepared": False,
        "failed": False,
        "transcribed_parts": {},
    }

    # Only convert and split, don't transcribe yet
    import threading
    t = threading.Thread(target=prepare_job, args=(job_id,), daemon=True)
    t.start()

    return jsonify({"job_id": job_id})


def download_from_url(url, job_dir, job_id):
    """Download audio/video from YouTube or Google Drive URL."""
    import yt_dlp
    import gdown

    emit(job_id, "progress", "Detectando tipo de link...", 2)

    # YouTube
    if "youtube.com" in url or "youtu.be" in url:
        emit(job_id, "progress", "Descargando audio de YouTube...", 3)
        output_path = os.path.join(job_dir, "original.mp3")
        ydl_opts = {
            "format": "bestaudio/best",
            "outtmpl": os.path.join(job_dir, "original.%(ext)s"),
            "postprocessors": [{
                "key": "FFmpegExtractAudio",
                "preferredcodec": "mp3",
                "preferredquality": "32",
            }],
            "quiet": True,
            "no_warnings": True,
            "socket_timeout": 30,
        }
        try:
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                info = ydl.extract_info(url, download=True)
                title = info.get("title", "youtube_audio")
        except Exception as e:
            error_str = str(e)
            if "Sign in" in error_str or "bot" in error_str or "cookies" in error_str.lower():
                raise RuntimeError(
                    "YouTube bloqueo la descarga desde este servidor. "
                    "Descarga el video desde tu navegador y subilo desde la pestaña Archivo."
                )
            raise RuntimeError(f"Error al descargar de YouTube: {error_str[:200]}")

        if not os.path.exists(output_path):
            # yt-dlp may have saved with different extension
            for f in os.listdir(job_dir):
                if f.startswith("original."):
                    output_path = os.path.join(job_dir, f)
                    break

        return output_path, f"{title}.mp3"

    # Google Drive
    if "drive.google.com" in url:
        emit(job_id, "progress", "Descargando de Google Drive...", 3)
        try:
            # Let gdown use the original filename from Drive
            result_path = gdown.download(url, output=job_dir + "/", quiet=True, fuzzy=True)
            if not result_path or not os.path.exists(result_path):
                raise RuntimeError("No se pudo descargar el archivo de Drive. Verifica que el link sea publico.")
            filename = os.path.basename(result_path)
            ext = filename.rsplit(".", 1)[-1].lower() if "." in filename else ""
            if ext not in ALLOWED_EXTENSIONS:
                raise RuntimeError(f"El archivo descargado (.{ext}) no es un formato de audio/video soportado")
            final_path = os.path.join(job_dir, f"original.{ext}")
            os.rename(result_path, final_path)
            return final_path, filename
        except RuntimeError:
            raise
        except Exception as e:
            raise RuntimeError(f"Error al descargar de Drive: {str(e)[:200]}")

    raise RuntimeError("Link no soportado. Usa links de YouTube o Google Drive.")


def prepare_job_from_url(job_id, url):
    """Download from URL then prepare (convert + split).
    Runs download in a real OS thread to avoid blocking gevent."""
    import concurrent.futures
    job = jobs[job_id]
    job_dir = job["job_dir"]

    try:
        # Run download in a real thread so it doesn't block gevent worker
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(download_from_url, url, job_dir, job_id)
            file_path, filename = future.result(timeout=300)
        job["original_path"] = file_path
        job["filename"] = filename
        emit(job_id, "progress", f"Descargado: {filename}", 5)
        prepare_job(job_id)
    except concurrent.futures.TimeoutError:
        emit(job_id, "error", message="La descarga tardo demasiado. Intenta subir el archivo manualmente.")
    except Exception as e:
        emit(job_id, "error", message=str(e))


@app.route("/upload-url", methods=["POST"])
@require_auth
def upload_url():
    data = request.get_json()
    url = data.get("url", "").strip()

    if not url:
        return jsonify({"error": "No se envio URL"}), 400

    if "youtube.com" not in url and "youtu.be" not in url and "drive.google.com" not in url:
        return jsonify({"error": "Solo se aceptan links de YouTube o Google Drive"}), 400

    job_id = uuid.uuid4().hex[:12]
    job_dir = f"/tmp/transcriptor-{job_id}"
    os.makedirs(job_dir, exist_ok=True)

    jobs[job_id] = {
        "messages": [],
        "result": None,
        "original_path": "",
        "job_dir": job_dir,
        "filename": "descarga",
        "prepared": False,
        "failed": False,
        "transcribed_parts": {},
    }

    import threading
    t = threading.Thread(target=prepare_job_from_url, args=(job_id, url), daemon=True)
    t.start()

    return jsonify({"job_id": job_id})


@app.route("/transcribe/<job_id>", methods=["POST"])
@require_auth
def transcribe(job_id):
    if job_id not in jobs:
        return jsonify({"error": "Job no encontrado"}), 404

    job = jobs[job_id]
    if not job.get("prepared"):
        return jsonify({"error": "El audio aun no esta preparado"}), 400

    data = request.get_json()
    selected = data.get("parts", [])

    if not selected:
        return jsonify({"error": "Selecciona al menos una parte"}), 400

    # Validate indices
    total = len(job["chunks"])
    selected = [i for i in selected if 0 <= i < total]

    if not selected:
        return jsonify({"error": "Indices de partes invalidos"}), 400

    # Reset messages for new stream
    job["messages"] = []
    job["result"] = None
    job["failed"] = False
    job["cancelled"] = False

    import threading
    t = threading.Thread(target=transcribe_selected, args=(job_id, selected), daemon=True)
    t.start()

    return jsonify({"job_id": job_id})


@app.route("/stream/<job_id>")
@require_auth
def stream(job_id):
    import time

    if job_id not in jobs:
        return jsonify({"error": "Job no encontrado"}), 404

    def generate():
        job = jobs[job_id]
        idx = 0
        while True:
            messages = job["messages"]
            while idx < len(messages):
                event = messages[idx]
                yield f"data: {json.dumps(event)}\n\n"
                idx += 1
                if event["type"] in ("result", "error", "chunks_ready", "partial_result"):
                    return
            time.sleep(0.5)

    return Response(
        generate(),
        mimetype="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
        }
    )


@app.route("/download/<job_id>")
@require_auth
def download(job_id):
    if job_id not in jobs or not jobs[job_id].get("result"):
        return jsonify({"error": "Transcripcion no disponible"}), 404

    job = jobs[job_id]
    filename = job.get("filename", "audio")
    base_name = filename.rsplit(".", 1)[0] if "." in filename else filename
    download_name = f"{base_name}_transcripcion.txt"

    return Response(
        job["result"],
        mimetype="text/plain",
        headers={
            "Content-Disposition": f'attachment; filename="{download_name}"'
        }
    )


@app.route("/cleanup", methods=["POST"])
@require_auth
def cleanup():
    import glob
    count = 0
    total_size = 0
    for d in glob.glob("/tmp/transcriptor-*"):
        try:
            for root, dirs, files in os.walk(d):
                for f in files:
                    total_size += os.path.getsize(os.path.join(root, f))
            shutil.rmtree(d, ignore_errors=True)
            count += 1
        except Exception:
            pass
    jobs.clear()
    size_mb = total_size / (1024 * 1024)
    return jsonify({"message": f"Limpiado: {count} jobs, {size_mb:.1f} MB liberados"})


def cleanup_old_jobs():
    """Remove temp directories for any leftover jobs"""
    import glob
    for d in glob.glob("/tmp/transcriptor-*"):
        try:
            shutil.rmtree(d, ignore_errors=True)
        except Exception:
            pass


atexit.register(cleanup_old_jobs)


if __name__ == "__main__":
    app.run(debug=True, port=5050)
