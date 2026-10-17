# Fases

## Fase 0 — Spike en dispositivos reales
Objetivo: validar los puntos de mayor riesgo antes de comprometer la arquitectura. App de pruebas mínima, instalada en OPPO, vivo, Xiaomi, Honor y Pixel.
- Camera2 con perfil 10-bit → GL → MediaCodec HEVC con un LUT .cube; medir fps, frames perdidos y temperatura a 4K30 y 4K60.
- Cámaras concurrentes: qué combinaciones permite cada móvil.
- Audio: enumeración y grabación con micrófono USB y Bluetooth (SCO y LE Audio si hay); `setPreferredDevice` en cada fabricante.
- WebRTC: latencia de monitor en Wi‑Fi y hotspot.
- Salida: informe por dispositivo (`docs/spike-report.md`) y alimentación del catálogo de capacidades.
Aceptación: informe completo y decisión documentada sobre 4K60 como objetivo base y sobre qué dispositivos lo cumplen.

## Fase 1 — Base y CI
Proyecto multimódulo, tema Material 3 Expressive, ajustes con grupos de funciones activables, CI (build, lint, tests), firma de APK por tag, metadatos F-Droid.

## Fase 2 — Núcleo de grabación
Camera2, perfiles de grabación, controles manuales, lentes y estabilización, códecs, almacenamiento SAF con SSD USB.

## Fase 3 — Audio
Selector previo, prioridad USB > BT > interno, ganancia, limitador, vúmetro, WAV aparte, avisos de cambio.

## Fase 4 — Color, Log y LUT
Curva Log, pipeline LUT, biblioteca con previsualización, modos de LUT por toma, tests de shaders con imágenes de referencia.

Incluye el catálogo de LUTs de fabricantes: esquema `luts/catalog.json`, descarga bajo demanda con checksum, entrada inicial de OPPO O-Log/O-Log2, y una tarea de investigación de fuentes y licencias de Xiaomi, vivo, Honor y otros (cada entrada documenta su estado de licencia). Antes de incluir cualquier fichero en el repo hay que confirmar los términos de uso.

## Fase 5 — Monitor y medidores
Peaking, zebras, histograma, waveform, vectorscopio, falso color, guías y desqueeze.

## Fase 6 — Multicámara
Cámaras concurrentes locales, emparejado QR/mDNS/Wi‑Fi Direct, roles, WebRTC, control remoto, REC simultáneo, timecode, modo esclavo, reconexión, subida de proxys.

Flujo (D80): cualquier pareja de móviles se empareja primero (uno muestra el código, el otro lo escanea o lo teclea), sin elegir rol; después, en cualquiera de los dos, se elige qué hace ese móvil (Cámara o Monitor) y el otro se adapta solo; con dos o más cámaras el que controla pasa a maestro. Los roles se intercambian o se termina la sesión sin volver a emparejar.

## Fase 7 — Backup y pulido
Backup .zip cifrable, rendimiento y térmica, accesibilidad, documentación y primera release (v1, solo vídeo).

## Fase 8 — Foto (posterior a la v1)
Captura con Camera2 reutilizando el pipeline de LUT, perfiles de foto, RAW/DNG. Se prepara en la arquitectura desde el inicio (la sesión de cámara y el render GPU no deben asumir solo vídeo).

## Fase 9 — Desenfoque de fondo y cámara computacional
Retrato estilo Google Camera: segmentación de sujeto, mapa de profundidad (sensor ToF o estimación por modelo) y desenfoque con bokeh configurable, editable después de la captura. Evaluar el mismo efecto en vídeo. El resto de funciones se define en una entrevista propia antes de empezar.
