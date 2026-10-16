# UltimateCamera — visión y decisiones

## Visión
Cámara de vídeo Android con todas las opciones posibles: perfiles de color del dispositivo, Log con LUTs aplicados en la grabación, multicámara con monitor remoto y, sobre todo, elección bajo demanda de la fuente de audio (USB y Bluetooth antes que el micrófono interno). Interfaz cuidada, rendimiento alto, APK firmados y CI desde el principio. Octava app aproximada de la familia Ultimate.

Caso de uso clave: se coloca un móvil grabando en el sitio y, cuando el plano está bien, el monitor (otro móvil) lanza la grabación.

## Decisiones de la entrevista (2026-10-16)
| Área | Decisión |
|---|---|
| Nombre / paquete | UltimateCamera — `com.qtekfun.ultimatecamera` |
| Alcance | Solo vídeo en la v1 (foto en fase posterior) |
| API de cámara | Camera2 directo |
| Android | minSdk 33 |
| Color nativo | Usar lo que exponga cada móvil: perfiles 10-bit del sistema, etiquetas de fabricante si existen, tonemap de Camera2; catálogo por dispositivo |
| Log | Curva Log propia aplicada por GPU desde 10-bit |
| LUTs | Pipeline GPU con .cube (y .3dl); quemado en el vídeo, solo monitor o doble archivo; biblioteca con previsualización |
| Formatos | HEVC 10-bit, H.264 8-bit, AV1 si hay hardware, alta velocidad, 4K60, bitrate manual |
| Rendimiento | Objetivo base 4K60 10-bit con LUT sin frames perdidos |
| Controles | ISO, obturador, balance de blancos, enfoque manual; peaking, zebras, histograma, waveform, vectorscopio, falso color, guías y desqueeze |
| Lentes | Selección de lente física, zoom continuo, OIS/EIS seleccionable, bloqueo AE/AF/AWB |
| Audio | Selector siempre visible antes de grabar, con orden USB > Bluetooth > interno preseleccionado; si cambia la fuente durante la toma, sigue con la actual y avisa; AAC en el contenedor, pista WAV/PCM aparte, ganancia manual, limitador y vúmetro, selección mono/estéreo/canales; Bluetooth SCO permitido con aviso de calidad |
| Multicámara | Cámaras concurrentes en un teléfono; monitor remoto; control remoto con REC sincronizado; subida de ficheros/proxys al maestro |
| Emparejado | QR + descubrimiento en red local (prioritario); Wi‑Fi Direct para exteriores sin router |
| Roles | Maestro, cámara esclava y monitor; el monitor controla la esclava y lanza la grabación |
| Sincronía | REC simultáneo + timecode común (NTP local) |
| Transporte | WebRTC en red local |
| Monitor | Imagen con LUT, medidores y estado, control remoto de exposición y enfoque, audio del esclavo |
| Esclavo | Pantalla atenuada con bloqueo táctil, servicio en primer plano con wake lock, avisos de batería/temperatura/espacio, reconexión automática (sigue grabando en local) |
| Perfiles | Preajustes completos de un toque (resolución, fps, códec, curva/LUT, audio, lente) |
| Almacenamiento | Carpeta elegida por SAF, incluido SSD por USB, con aviso de velocidad insuficiente |
| Backup | Archivo .zip por SAF con cifrado opcional; incluye ajustes y preajustes, biblioteca de LUTs y emparejamientos multicámara |
| Interfaz | Un único modo; funciones que se activan u ocultan en Ajustes según el uso |
| Distribución | GitHub Releases + F-Droid, APK firmado en CI |
| CI | Build, lint y tests en cada PR; APK firmado por tag; tests de shaders/LUT con imágenes de referencia; metadatos F-Droid y builds reproducibles |
| Riesgo | Spike inicial (fase 0) en OPPO, vivo, Xiaomi, Honor y Pixel antes del plan completo |

## LUTs de fabricantes (añadido 2026-10-16)
Z quiere reunir los LUTs de los principales fabricantes, incluidos o al menos como catálogo en el repo. Decisión provisional: catálogo (`luts/catalog.json`) con descarga bajo demanda desde la fuente oficial; empaquetar solo lo que tenga licencia de redistribución confirmada. OPPO publica O-Log y O-Log2 en .cube; falta investigar los demás fabricantes.

## Hoja de ruta posterior a la v1 (confirmada por Z)
- Foto: captura con LUT y, donde el dispositivo lo permita, RAW/DNG.
- Desenfoque de fondo estilo Google Camera (retrato / Lens Blur): segmentación + mapa de profundidad, intensidad ajustable antes y después de la captura.
- Otras funciones de cámara computacional al estilo Google Camera: a concretar en una entrevista propia cuando se acerque esta fase.
- Posibilidad de llevar el desenfoque también al vídeo, a evaluar según rendimiento.

## Fuera de alcance de la v1
Foto (ver hoja de ruta), salida RTSP/SRT, sincronía de fotograma, edición de vídeo, Play Store.
