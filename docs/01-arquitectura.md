# Arquitectura

## Pipeline de vídeo
```
Camera2 (sesión, 10-bit si el perfil lo permite)
   → SurfaceTexture / EGL (GL ES 3.x)
      → shader: curva Log opcional → LUT 3D (.cube) → medidores (peaking, zebras, histograma, waveform, vectorscopio, falso color)
         ├─ Superficie de preview (monitor con o sin LUT)
         ├─ Superficie MediaCodec (HEVC/H.264/AV1) → MediaMuxer (MP4/MOV)
         └─ Superficie de proxy → codificador WebRTC (monitor remoto)
```
Modos de LUT por toma: quemado, solo monitor, doble archivo (Log plano + con LUT).

## Capacidades por dispositivo
`DeviceCapabilityCatalog`: lentes físicas, perfiles de rango dinámico, fps y resoluciones, códecs por hardware, cámaras concurrentes, estabilización, límites de audio USB/BT. Se rellena al primer arranque y se cachea; es regenerable, no entra en el backup.

Implementado en `core-capabilities` (D35): lo anunciado por Camera2/MediaCodec y lo medido se guardan por separado, porque las capacidades se **sondean** con sesiones reales cortas (autoconfiguración estilo OBS) en vez de fiarse del `StreamConfigurationMap`: el OPPO CPH2841 anuncia 4K a 30 fps y sostiene 4K60 10-bit; el Pixel 8 anuncia 4K60 y rechaza HLG10 por encima de 30 fps. Se vuelve a sondear si cambia `Build.FINGERPRINT` o el SDK. Lo propio de cada fabricante (parámetros de sesión, vendor tags) entra por la interfaz `VendorExtension`.

## Audio
- `AudioSourceManager` lista dispositivos (`AudioManager.getDevices`) y aplica `setPreferredDevice` a `AudioRecord`.
- Selector previo a cada grabación con orden USB > Bluetooth > interno.
- Cambios de dispositivo en caliente: no se conmuta solo; se avisa y se ofrece cambiar.
- Salida: AAC en el contenedor y pista WAV/PCM sincronizada aparte; ganancia manual, limitador, vúmetro; mono/estéreo/canales según el dispositivo.
- Bluetooth SCO permitido con indicador de calidad real (frecuencia y canales).

## Multicámara y red
- Roles: Master, Camera, Monitor. El monitor controla la cámara y dispara REC; la cámara graba en local siempre.
- Emparejado por QR y descubrimiento mDNS en red local o hotspot; Wi‑Fi Direct como alternativa.
- Transporte WebRTC para vídeo de monitor, audio y canal de datos de control; canal fiable para ficheros/proxys posteriores.
- Reloj común por NTP local; timecode en metadatos de cada clip; REC simultáneo con compensación de latencia medida.
- Reconexión automática; el estado vuelve al monitor al recuperar la red.

## Almacenamiento y backup
- SAF con carpeta elegida (SSD USB incluido); prueba de velocidad y aviso.
- Backup `.zip` por SAF con cifrado opcional (contraseña, AES‑GCM): ajustes, preajustes, biblioteca de LUTs, emparejamientos. Versionado de esquema.

## Interfaz
Compose, Material 3 Expressive, color dinámico, tema oscuro de monitor de campo. Un único modo; cada grupo de funciones (medidores, multicámara, audio avanzado, etc.) se activa u oculta desde Ajustes.
