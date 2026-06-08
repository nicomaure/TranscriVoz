# TranscriVoz

Transcribi clases o audios a texto usando la API gratuita de Groq (Whisper) o la API de OpenAI.

Subi un video o audio desde el navegador, o procesa enlaces compatibles sobre contenido propio o autorizado. La app convierte, divide y transcribe automaticamente. El resultado se puede copiar o descargar como `.txt`.

## Descargar

**No necesitas instalar nada.** Descarga, ejecuta y listo.

### Windows

1. [**Descargar TranscriVoz para Windows**](https://github.com/nicomaure/TranscriVoz/releases/download/v1.0.0-windows/TranscriVoz-Windows-x86_64.zip)
2. Extraer el zip en cualquier carpeta
3. Ejecutar `TranscriVoz.exe`
4. Se abre en tu navegador. Ir a Ajustes y pegar tu API key de [Groq](https://console.groq.com/keys) (gratis) o [OpenAI](https://platform.openai.com/api-keys)

### Linux

1. [**Descargar TranscriVoz AppImage**](https://github.com/nicomaure/TranscriVoz/releases/download/v1.0.0-desktop/TranscriVoz-x86_64.AppImage)
2. Darle permisos: `chmod +x TranscriVoz-x86_64.AppImage`
3. Ejecutar (doble click o desde terminal: `./TranscriVoz-x86_64.AppImage`)
4. Se abre en tu navegador. Ir a Ajustes y pegar tu API key de [Groq](https://console.groq.com/keys) (gratis) o [OpenAI](https://platform.openai.com/api-keys)

> Todo viene incluido: Python, ffmpeg y yt-dlp. No necesita instalar dependencias.

### Android

La version Android esta en desarrollo y todavia no esta publicada en Play Store.

1. [**Descargar TranscriVoz para Android**](https://github.com/nicomaure/TranscriVoz/actions/runs/27110059473)
2. En la seccion **Artifacts**, descargar `TranscriVoz-Android-debug`
3. Descomprimir el `.zip`
4. Instalar `app-debug.apk` en el telefono
5. Android puede mostrar una advertencia porque la app no viene de Play Store. Elegi permitir la instalacion desde esa fuente e instalar de todas formas si confias en este proyecto.

La version Android no incluye claves API. Al abrirla, anda a Ajustes y carga tu propia API key de [Groq](https://console.groq.com/keys) o [OpenAI](https://platform.openai.com/api-keys).

---

## Instalacion desde el codigo (alternativa)

Si preferis instalar desde el codigo fuente en vez del AppImage:

### Requisitos

- Python 3.8+
- ffmpeg
- Una API key de [Groq](https://console.groq.com) (gratis) o de [OpenAI](https://platform.openai.com) (pago)

### Instalacion rapida

```bash
git clone https://github.com/nicomaure/TranscriVoz.git
cd TranscriVoz
bash setup.sh
```

El script te pide la API key y una contraseña, configura todo automaticamente.

Para iniciar despues:

```bash
source venv/bin/activate
python3 app.py
# Abrir http://localhost:5050
```

### Instalacion manual

```bash
git clone https://github.com/nicomaure/TranscriVoz.git
cd TranscriVoz
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt

# Crear archivo .env
cp .env.example .env

# Generar hash de contraseña:
python3 generar_hash.py

# Generar clave secreta:
python3 -c "import os; print(os.urandom(32).hex())"

# Editar .env con los datos generados
nano .env

# Iniciar
python3 app.py
# Abrir http://localhost:5050
```

## Uso

1. Ingresa la contraseña
2. Subi un archivo de audio o video (MP4, MP3, WAV, M4A, OGG, WEBM, FLAC, AAC, WMA - max 1 GB) o pega un enlace compatible sobre contenido propio o autorizado
3. La app convierte y divide el audio automaticamente
4. Selecciona que partes queres transcribir
5. Espera el resultado - si llegas al limite de la API, la app espera y reintenta sola
6. Copia o descarga la transcripcion como `.txt`

## Funciones

- **Selector de partes**: Elegi que partes transcribir. Util para dividir el trabajo entre varias API keys o hacerlo en etapas.
- **Countdown en tiempo real**: Ve cuanto falta para que la API se desbloquee.
- **Resultado parcial**: Si falla una parte, se guarda lo que ya se transcribio.
- **Reintentar desde donde quedo**: No gasta cupo en partes ya hechas.
- **Procesamiento de enlaces compatibles**: Pega un enlace de contenido propio, publico o autorizado y la app prepara el audio para transcribirlo.
- **Progreso de subida**: Barra de porcentaje en tiempo real al subir archivos.
- **Multiples proveedores**: Groq (gratis) y OpenAI (pago), seleccionables desde la interfaz.
- **Cambiar API key y modelo desde la UI**: Sin tocar archivos de configuracion.
- **Proteccion con contraseña**: Acceso protegido con hash PBKDF2.
- **Proteccion contra fuerza bruta**: 5 intentos, bloqueo de 5 minutos.

## Limites del plan gratuito de Groq

| Limite | Valor |
|---|---|
| Audio por ciclo | ~7200 seg (~2 horas) |
| Reset | ~40-60 minutos |

Si necesitas mas, podes:
- Crear otra cuenta de Groq con otro correo y usar otra API key
- Esperar a que se reinicie el cupo
- Pagar el plan de Groq ($0.04/hora de audio)
- Usar OpenAI como proveedor alternativo

## Deploy en servidor (Ubuntu + Nginx)

### 1. Instalar la app

```bash
cd /home/tu-usuario/proyectos
git clone https://github.com/nicomaure/TranscriVoz.git
cd TranscriVoz
bash setup.sh
```

### 2. Configurar servicio systemd

```bash
sudo cp transcriptor.service /etc/systemd/system/
sudo nano /etc/systemd/system/transcriptor.service
```

Editar `User`, `Group`, `WorkingDirectory` y las rutas del `venv` segun tu servidor.

```bash
sudo systemctl enable --now transcriptor
sudo systemctl status transcriptor   # verificar que este activo
```

### 3. Configurar Nginx

Editar el archivo de Nginx que **realmente usa tu servidor** (verificar si `sites-enabled/default` es un symlink o un archivo separado):

```bash
# Ver si es symlink
ls -la /etc/nginx/sites-enabled/default

# Si es un archivo separado (no symlink), editar ese directamente:
sudo nano /etc/nginx/sites-enabled/default
```

Agregar la configuracion siguiendo `nginx_transcriptor.conf` como referencia:

1. El bloque `upstream` va **fuera** del `server {}`
2. El bloque `location` va **dentro** del `server {}` que maneja HTTPS (puerto 443)

```bash
sudo nginx -t && sudo systemctl reload nginx
```

La app queda en `https://tu-dominio/transcriptor/`

## Mantenimiento

Algunos proveedores de enlaces cambian sus sistemas cada pocas semanas, lo que puede romper el procesamiento automatico. Cuando veas un error como *"yt-dlp no pudo procesar el video (posiblemente desactualizado)"*, hay que actualizar `yt-dlp`.

### AppImage y Windows

Desde la carpeta del proyecto en tu máquina de desarrollo:

```bash
cd ~/Documentos/proyectos/TranscriVoz
git push origin main:desktop && git push origin main:windows
```

Esto dispara las builds en GitHub Actions. En ~10 minutos los releases se actualizan automáticamente con la última versión de `yt-dlp`. No hace falta cambiar ningún código.

### Servidor (Ubuntu + Nginx)

```bash
source venv/bin/activate
pip install -U yt-dlp
sudo systemctl restart transcriptor
```

---

## Estructura

```
TranscriVoz/
├── app.py                  ← Aplicacion Flask
├── wsgi.py                 ← Entry point para Gunicorn
├── setup.sh                ← Instalacion automatica
├── generar_hash.py         ← Genera hash de contraseña
├── requirements.txt        ← Dependencias Python
├── .env.example            ← Plantilla de configuracion
├── .env                    ← Tu configuracion (no se sube a git)
├── nginx_transcriptor.conf ← Config de Nginx de referencia
├── transcriptor.service    ← Servicio systemd para produccion
└── templates/
    └── index.html          ← Frontend
```

## Tecnologias

- **Backend**: Flask + Gunicorn + gevent
- **Transcripcion**: Groq (Whisper Large v3 / Turbo) y OpenAI (Whisper)
- **Procesamiento**: ffmpeg
- **Procesamiento de enlaces**: yt-dlp + gdown para contenido propio o autorizado
- **Frontend**: HTML/CSS/JS (sin frameworks)

---

Desarrollado por [nicomaure](https://nicomaure.com.ar) · [MIT License](LICENSE)
