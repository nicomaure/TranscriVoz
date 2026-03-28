# TranscriVoz

Transcribi clases o audios a texto con timestamps usando la API gratuita de Groq (Whisper).

Subi un video o audio desde el navegador, la app lo convierte, divide y transcribe automaticamente. El resultado se puede copiar o descargar como `.txt`.

## Requisitos

- Python 3.8+
- ffmpeg
- Una cuenta gratuita en [Groq](https://console.groq.com) (API key)

## Instalacion rapida

```bash
git clone https://github.com/nicomaure/transcriptor-groq.git
cd transcriptor-groq
bash setup.sh
```

El script te pide la API key y una contraseña, configura todo automaticamente.

## Instalacion manual

```bash
# 1. Clonar e instalar
git clone https://github.com/nicomaure/transcriptor-groq.git
cd transcriptor-groq
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt

# 2. Crear archivo .env
cp .env.example .env

# 3. Editar .env con tus datos:
#    - GROQ_API_KEY: tu key de https://console.groq.com -> API Keys
#    - PASSWORD_HASH: generar con el comando de abajo
#    - SECRET_KEY: generar con el comando de abajo

# Generar PASSWORD_HASH:
python3 generar_hash.py

# Generar SECRET_KEY:
python3 -c "import os; print(os.urandom(32).hex())"

# 4. Iniciar
python3 app.py
# Abrir http://localhost:5050
```

## Uso

1. Ingresa la contraseña
2. Subi un archivo de audio o video (MP4, MP3, WAV, M4A, OGG, WEBM, FLAC, AAC, WMA - max 1 GB)
3. La app convierte y divide el audio automaticamente
4. Selecciona que partes queres transcribir
5. Espera el resultado - si llegas al limite de la API, la app espera y reintenta sola
6. Copia o descarga la transcripcion como `.txt`

## Funciones

- **Selector de partes**: Elegi que partes transcribir. Util para dividir el trabajo entre varias API keys o hacerlo en etapas.
- **Countdown en tiempo real**: Ve cuanto falta para que la API se desbloquee.
- **Resultado parcial**: Si falla una parte, se guarda lo que ya se transcribio.
- **Reintentar desde donde quedo**: No gasta cupo en partes ya hechas.
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

## Deploy en servidor (Ubuntu + Nginx)

```bash
# En el servidor
cd /home/tu-usuario/proyectos
git clone https://github.com/nicomaure/transcriptor-groq.git
cd transcriptor-groq
bash setup.sh

# Copiar servicio de systemd
sudo cp transcriptor.service /etc/systemd/system/
# Editar el archivo para ajustar usuario y rutas:
sudo nano /etc/systemd/system/transcriptor.service

# Activar e iniciar
sudo systemctl enable --now transcriptor

# Agregar a Nginx (dentro del bloque server {}):
# Ver archivo nginx_transcriptor.conf como referencia
sudo nano /etc/nginx/sites-available/default
sudo nginx -t && sudo systemctl reload nginx
```

## Estructura

```
transcriptor-groq/
├── app.py                ← Aplicacion Flask
├── wsgi.py               ← Entry point para Gunicorn
├── setup.sh              ← Instalacion automatica
├── generar_hash.py       ← Genera hash de contraseña
├── requirements.txt      ← Dependencias Python
├── .env.example          ← Plantilla de configuracion
├── .env                  ← Tu configuracion (no se sube a git)
├── nginx_transcriptor.conf ← Config de Nginx de referencia
├── transcriptor.service  ← Servicio systemd para produccion
└── templates/
    └── index.html        ← Frontend
```

## Tecnologias

- **Backend**: Flask + Gunicorn + gevent
- **Transcripcion**: API de Groq (Whisper Large v3 / Turbo)
- **Procesamiento**: ffmpeg
- **Frontend**: HTML/CSS/JS (sin frameworks)

---

Desarrollado por [nicomaure](https://nicomaure.com.ar)
