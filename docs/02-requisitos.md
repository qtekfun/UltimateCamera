# Requisitos

## Cámara y vídeo
- REQ-CAM-001 Camera2 con selección de lente física y zoom continuo.
- REQ-CAM-002 Controles manuales: ISO, obturador, balance de blancos, enfoque; bloqueo AE/AF/AWB.
- REQ-CAM-003 Estabilización OIS/EIS seleccionable según lo que exponga el dispositivo.
- REQ-CAM-004 HEVC 10-bit, H.264 8-bit, AV1 por hardware si existe; 4K60 y alta velocidad; bitrate manual.
- REQ-CAM-005 Perfiles de grabación de un toque con todos los parámetros.

## Color, Log y LUT
- REQ-COL-001 Usar perfiles 10-bit y etiquetas de color nativos de cada móvil cuando estén disponibles.
- REQ-COL-002 Curva Log propia por GPU desde 10-bit.
- REQ-COL-003 Importar .cube y .3dl; biblioteca con carpetas y vista previa en vivo.
- REQ-COL-004 LUT quemado, solo monitor o doble archivo, por toma.
- REQ-COL-005 Sin bandas visibles en degradados con HEVC 10-bit (verificación con imagen de referencia).

- REQ-COL-006 Catálogo de LUTs de fabricantes (`luts/catalog.json`): por cada LUT, fabricante, dispositivos, URL oficial, checksum, formato y estado de licencia (`redistributable`, `link-only`, `unknown`).
- REQ-COL-007 Los LUTs `link-only` o `unknown` no se incluyen en el repo ni en el APK; la app los descarga bajo demanda desde la fuente oficial (o el usuario los importa) con confirmación y checksum.
- REQ-COL-008 Solo se incluyen en el repo/APK LUTs propios o con licencia que permita redistribuirlos, con su aviso de licencia (compatibilidad F-Droid).
- REQ-COL-009 Sembrar el catálogo con OPPO O-Log y O-Log2 (.cube, página oficial de descargas técnicas de OPPO) y ampliar a Xiaomi, vivo, Honor y otros fabricantes tras investigar sus fuentes en la Fase 4.

## Monitor y ayudas
- REQ-MON-001 Peaking, zebras, histograma, waveform, vectorscopio, falso color, guías y desqueeze.
- REQ-MON-002 Medidores calculados en GPU sin bajar del objetivo de fps.
- REQ-MON-003 Opción de brillo máximo de pantalla (para exteriores); por defecto brillo normal del sistema. (Añadido por Z el 2026-10-16.)

## Audio
- REQ-AUD-001 Selector de fuente visible antes de cada grabación con USB > Bluetooth > interno preseleccionado.
- REQ-AUD-002 Si el dispositivo cambia durante la toma, continuar con la fuente actual y avisar.
- REQ-AUD-003 AAC en el contenedor y pista WAV/PCM aparte sincronizada.
- REQ-AUD-004 Ganancia manual, limitador, vúmetro y selección de canales.
- REQ-AUD-005 Bluetooth SCO permitido con aviso de calidad real.

## Multicámara y red
- REQ-NET-001 Cámaras concurrentes en un móvil donde el hardware lo permita.
- REQ-NET-002 Emparejado por QR + descubrimiento local; Wi‑Fi Direct como alternativa.
- REQ-NET-003 Roles maestro/cámara/monitor; el monitor lanza y detiene la grabación.
- REQ-NET-004 Monitor remoto con LUT, medidores, estado, control de exposición/enfoque y audio.
- REQ-NET-005 REC simultáneo y timecode común.
- REQ-NET-006 Subida de ficheros/proxys al maestro tras la grabación.
- REQ-NET-007 Reconexión automática; la cámara sigue grabando en local.
- REQ-NET-008 Cámara esclava con pantalla atenuada y bloqueo táctil, servicio en primer plano, wake lock, avisos de batería/temperatura/espacio.

## Almacenamiento y backup
- REQ-STO-001 Carpeta elegida por SAF, con soporte de SSD USB y aviso de velocidad insuficiente.
- REQ-BAK-001 Exportar/importar backup .zip por SAF con cifrado opcional.
- REQ-BAK-002 El backup incluye ajustes y preajustes, biblioteca de LUTs y emparejamientos.

## Posteriores a la v1 (hoja de ruta)
- REQ-PHO-001 Captura de foto con LUT aplicable y perfiles propios.
- REQ-PHO-002 RAW/DNG en dispositivos que lo expongan.
- REQ-PHO-003 Desenfoque de fondo estilo Google Camera (retrato/Lens Blur), con intensidad editable también tras la captura.
- REQ-PHO-004 Funciones adicionales de cámara computacional: pendientes de definir.
- REQ-PHO-005 Evaluar el desenfoque de fondo en vídeo según rendimiento.

## No funcionales
- REQ-NFR-001 Objetivo base: 4K60 10-bit con LUT sin frames perdidos; degradación guiada ante calentamiento.
- REQ-NFR-002 minSdk 33; interfaz es/en; código en inglés.
- REQ-NFR-003 Sin telemetría; compatible con F-Droid.
- REQ-NFR-004 Accesibilidad: todos los controles con nombre, rol y estado para TalkBack (REC anuncia «Grabando, 00:01:12»; código de tiempo como región viva educada y limitada a un cambio por minuto; alertas críticas asertivas), objetivos táctiles de 48 dp, texto al 200 % sin recortes ni solapes (los textos se reparten en líneas; solo los números tabulares se limitan al 150 %), contraste ≥ 4,5:1 de los colores de texto sobre los scrims con cualquier imagen detrás, y sin animaciones cuando el sistema las quita. Verificación: `ContrastTest`, `AccessibilityHelpersTest` (JVM) y tests de Compose de `feature-camera` y `feature-settings` (androidTest, en el Pixel); recorrido manual con TalkBack (D67).
