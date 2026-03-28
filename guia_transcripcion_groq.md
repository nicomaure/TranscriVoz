# 🎙️ Guía: Transcribir y resumir clases con Groq + Whisper

## ¿Qué hace esto?
Convierte el audio de tus clases grabadas (MP4/MP3) en texto con timestamps usando Groq (gratis, rapidísimo), y luego genera un resumen completo listo para estudiar aunque no hayas estado presente en la clase.

---

## Requisitos previos
- Linux (probado en MX Linux 25)
- Python 3 instalado
- `ffmpeg` instalado
- Conexión a internet
- Cuenta gratuita en [console.groq.com](https://console.groq.com)

---

## PASO 1 — Instalar ffmpeg

```bash
sudo apt install ffmpeg
```

---

## PASO 2 — Obtener API Key de Groq

1. Entrá a [console.groq.com](https://console.groq.com)
2. Login / Crear cuenta (gratis)
3. Ir a **API Keys** → **Create API Key**
4. Copiar la key (empieza con `gsk_...`)

Configurarla en tu sistema:

```bash
echo 'export GROQ_API_KEY="gsk_TU_KEY_ACA"' >> ~/.bashrc
source ~/.bashrc
```

> ⚠️ Nunca compartas tu API key públicamente.

---

## PASO 3 — Crear el entorno de trabajo

```bash
mkdir ~/transcriptor-clases
cd ~/transcriptor-clases
python3 -m venv venv
source venv/bin/activate
pip install groq
```

> ⚠️ Cada vez que abras una terminal nueva, activá el entorno:
> ```bash
> cd ~/transcriptor-clases && source venv/bin/activate
> ```

---

## PASO 4 — Crear el script de transcripción

```bash
nano ~/transcriptor-clases/transcribir.py
```

Pegás este código:

```python
import os
import sys
from groq import Groq

client = Groq()

def transcribir(archivo):
    print(f"Transcribiendo {archivo}...")
    with open(archivo, "rb") as f:
        resultado = client.audio.transcriptions.create(
            file=(archivo, f.read()),
            model="whisper-large-v3-turbo",
            language="es",
            temperature=0,
            response_format="verbose_json"
        )
    salida = archivo.rsplit(".", 1)[0] + ".txt"
    with open(salida, "w") as f:
        for seg in resultado.segments:
            mins = int(seg["start"] // 60)
            segs = int(seg["start"] % 60)
            f.write(f"[{mins:02d}:{segs:02d}] {seg['text'].strip()}\n")
    print(f"✓ Guardado en {salida}")

transcribir(sys.argv[1])
```

Guardás: **Ctrl+O → Enter → Ctrl+X**

---

## PASO 5 — Preparar el audio

Groq acepta archivos de **máximo 19.5 MB**. Una clase de 2hs pesa ~120MB, así que hay que comprimirla y partirla.

### Convertir MP4 a MP3 comprimido (optimizado para voz):

```bash
cd ~/Descargas
ffmpeg -i clase.mp4 -ar 16000 -ac 1 -b:a 32k clase_voz.mp3
```

### Partir en 2 mitades (para clase de 2hs):

```bash
ffmpeg -i clase_voz.mp3 -t 3600 -acodec copy parte1.mp3
ffmpeg -i clase_voz.mp3 -ss 3600 -acodec copy parte2.mp3
```

### Verificar que pesen menos de 19MB:

```bash
du -sh parte1.mp3 parte2.mp3
```

> Si pesan más de 19MB, bajá más el bitrate: cambiá `32k` por `24k` y repetí.

---

## PASO 6 — Transcribir

```bash
cd ~/transcriptor-clases
source venv/bin/activate

python transcribir.py ~/Descargas/parte1.mp3
# Esperar que termine, luego:
python transcribir.py ~/Descargas/parte2.mp3
```

El resultado se guarda automáticamente como `.txt` en la misma carpeta del audio, con timestamps así:

```
[00:00] Buenas tardes, profesor.
[00:05] Buenas tardes, ¿cómo andan?
[01:23] Vamos a esperar unos minutitos...
```

---

## PASO 7 — Generar resumen con IA

Una vez que tenés los `.txt` de la clase, pegás el contenido en Claude con este prompt:

---

### 📋 PROMPT PARA GENERAR RESUMEN DE CLASE

```
Sos un compañero de estudio que estuvo presente en esta clase virtual 
y ahora me la tenés que contar completa porque yo no pude asistir.

Tu objetivo es que yo, leyendo tu resumen, entienda TODO lo que pasó 
en la clase sin necesidad de ver el video. No omitás nada importante.

ESTILO DE ESCRITURA:
- Escribí en prosa narrativa, como si me estuvieras contando la clase
- Cuando el profesor explica algo, explicalo vos también con tus palabras
- Si el profesor da un ejemplo, reproducí ese ejemplo
- Si hay una discusión o pregunta de un alumno que aclara algo importante, incluila
- Si algo quedó confuso o incompleto en la clase, marcalo con ⚠️
- Usá negrita para resaltar conceptos clave, fechas y consignas

ESTRUCTURA QUE DEBE TENER EL RESUMEN:

## ⚠️ ALERTAS DE LA CLASE
(esta sección va PRIMERO y es lo más importante — listá aquí todo lo 
que tiene fecha límite o requiere acción obligatoria:
- Trabajos prácticos: nombre, consigna resumida, fecha de entrega
- Evaluaciones o parciales: fecha, tema, modalidad
- Entregas pendientes: qué, dónde y cuándo
- Cualquier plazo mencionado aunque sea de pasada
Si no hay nada urgente, escribí "Sin alertas para esta clase")

## Datos de la clase
(institución si se menciona, materia, número de clase, fecha, modalidad)

## Contexto general
(de qué trató la clase, en qué momento de la cursada estamos)

## Desarrollo de la clase
(esta es la sección más importante y más larga — contá todo lo que 
pasó en orden cronológico, sección por sección, tema por tema)

## Consignas y entregas
(todo lo que hay que hacer, exactamente cómo, dónde y cuándo — 
si el profesor fue específico, sé específico vos también)

## Fechas y plazos importantes
(todo lo que tenga fecha, incluso las que se mencionaron de pasada)

## Preguntas frecuentes de la clase
(las dudas que surgieron que probablemente también las tenga yo)

## Cuestiones administrativas
(correos, trámites, problemas técnicos, accesos)

## Lo que tengo que hacer antes de la próxima clase
(lista accionable y concreta)

IMPORTANTE:
- No resumas de más — si el profesor explicó algo en detalle, explicalo en detalle
- No ignores las preguntas de los alumnos si aclaran algo del tema
- Ignorá solo: saludos, silencios largos, problemas de audio sin contenido
- Si no entendés algo de la transcripción, marcalo con ⚠️ en lugar de inventar

Transcripción parte 1:
[PEGAR CONTENIDO DE parte1_groq.txt]

Transcripción parte 2:
[PEGAR CONTENIDO DE parte2_groq.txt]
```

---

## Límites del plan gratuito de Groq

| Límite | Valor |
|---|---|
| Audio por hora | 7.200 seg = **2hs** |
| Reset | Cada 1 hora |

Si aparece el error `RateLimitError 429`, el mensaje te dice exactamente cuánto esperar:
```
Please try again in Xm Xs
```

### Estrategia para varias clases:
- Procesá **parte 1** → esperá 1hs → procesá **parte 2**
- O dejá corriendo una clase de noche y al otro día tenés todo

### Costo si necesitás más:
- Whisper Large v3 Turbo: **$0.04 por hora de audio**
- Clase de 2hs completa: **$0.08 dólares** (menos de 10 centavos)
- Un semestre entero (~20 clases): **~$1.60 dólares**

---

## Resumen rápido — uso diario

```bash
# 1. Comprimir y partir el video
ffmpeg -i clase.mp4 -ar 16000 -ac 1 -b:a 32k clase_voz.mp3
ffmpeg -i clase_voz.mp3 -t 3600 -acodec copy parte1.mp3
ffmpeg -i clase_voz.mp3 -ss 3600 -acodec copy parte2.mp3

# 2. Transcribir
cd ~/transcriptor-clases && source venv/bin/activate
python transcribir.py ~/Descargas/parte1.mp3
# (esperar si hay rate limit)
python transcribir.py ~/Descargas/parte2.mp3

# 3. Pegar los .txt en Claude con el prompt de arriba
# 4. Guardar el resumen como PDF para estudiar
```

---

## Flujo completo visual

```
clase.mp4
    ↓ ffmpeg (comprimir + partir)
parte1.mp3 + parte2.mp3  (~16MB cada una)
    ↓ python transcribir.py
parte1.txt + parte2.txt  (con timestamps)
    ↓ Claude + prompt de resumen
resumen_clase.pdf  (listo para estudiar)
```
