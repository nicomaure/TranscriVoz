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
- Guardar API key localmente en preferencias privadas de la app.
- Enviar el archivo al endpoint compatible con Whisper.
- Mostrar progreso basico.
- Copiar o guardar la transcripcion como `.txt`.

## Pendiente

- Division automatica de audios grandes.
- Conversion local con ffmpeg.
- Reintentos avanzados cuando Groq llega al limite.
- Descarga desde YouTube o Google Drive.
- Icono final, firma y pipeline de release.

## Como abrir

Abrir la carpeta `android/` con Android Studio y ejecutar el modulo `app`.

Este workspace no incluye Java/Gradle instalados, por eso la compilacion se valida desde Android Studio.
