# TranscriVoz Android

Primera version Android nativa de TranscriVoz.

Esta rama mantiene la app Android aislada de la version Flask/Desktop:

```text
android/
├── app/
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## Alcance actual

- Seleccionar audio o video local desde Android.
- Configurar proveedor Groq u OpenAI.
- Guardar API key localmente cifrada con Android Keystore.
- Mostrar estado de API key sin exponerla completa y permitir borrarla.
- Links directos para crear API keys de Groq y OpenAI.
- Division automatica experimental para archivos grandes usando APIs nativas de Android.
- Formato de salida configurable: con timestamps o solo texto.
- Enviar el archivo al endpoint compatible con Whisper.
- Mostrar progreso basico.
- Copiar o guardar la transcripcion como `.txt`.

## Pendiente

- Conversion local/comprimida tipo desktop con ffmpeg.
- Reintentos avanzados cuando Groq llega al limite.
- Descarga desde YouTube o Google Drive.
- Icono final, firma y pipeline de release.

## API keys

La app no incluye claves en el codigo ni requiere un backend propio. Cada usuario carga su API key desde Ajustes y la app la guarda cifrada en el telefono usando una clave administrada por Android Keystore.

## Archivos grandes

La app intenta dividir archivos mayores a 19 MB antes de enviarlos a Groq/OpenAI. Esta primera implementacion usa `MediaExtractor` y `MediaMuxer`, por lo que funciona mejor con contenedores compatibles con Android como M4A/MP4/AAC. La conversion y compresion equivalente a la version desktop queda pendiente para una etapa posterior.

## Timestamps

Desde Ajustes se puede elegir entre transcripcion con tiempos (`[MM:SS] texto`) o solo texto. Cuando el archivo se divide en partes, la app suma el offset de cada parte para que los tiempos sigan la duracion total del audio.

## Como abrir

Abrir la carpeta `android/` con Android Studio y ejecutar el modulo `app`.

Este workspace no incluye Java/Gradle instalados, por eso la compilacion se valida desde Android Studio.
