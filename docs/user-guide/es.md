# UltimateCamera — guía de uso

Guía breve y orientada a tareas. Los nombres entre comillas son los de la app en español; *Ajustes › X* significa el
botón de engranaje de la cámara y luego la sección X. English: [en.md](en.md).

## 1. Primer arranque y autoconfiguración
1. Concede cámara y micrófono. Sin ellos la app solo muestra el aviso de permisos.
2. Se ofrece la **autoconfiguración** (puedes saltarla). Prueba unos segundos cada modo de grabación con la cámara,
   la LUT y el codificador a la vez, y se queda con los que el móvil sostiene sin perder fotogramas. Tarda unos
   minutos; se para sola si el móvil se calienta.
3. Al acabar pulsa «Aplicar». El selector de formato muestra solo los modos que han aguantado. Puedes repetirla en
   *Ajustes › Autoconfiguración* (tras una actualización del sistema, por ejemplo).

## 2. Grabar
- **REC** (botón rojo grande) empieza y para. Con *Ajustes › Controles › Teclas de volumen* también graban las teclas
  de volumen.
- Arriba, la tira de estado: punto rojo y código de tiempo, formato, codec y 10-bit; a la derecha almacenamiento
  restante, batería y temperatura. Debajo, fotogramas perdidos y bitrate durante la toma.
- El código de tiempo es el tiempo de la toma o la hora del día (*Ajustes › Cámara › Código de tiempo*).
- Nada se pierde si la app se cierra de golpe: la toma se recupera al volver a abrirla.

## 3. Modos y codecs
- **Formato** (chip «4K30», «1080p60»…): resolución y fotogramas por segundo. Si el móvil rechaza un modo, se
  explica y se queda el anterior.
- **Codec**: HEVC (por defecto, 10-bit), H.264 (máxima compatibilidad, 8-bit) o AV1 si el móvil tiene codificador.
- **10-bit HLG** se graba como HDR real; los móviles con pantalla HDR lo muestran en HDR (*Ajustes › Pantalla › Visor
  HDR*).
- **Estabilización**: chip con los modos electrónicos que ofrece la cámara; óptica activada donde existe. Cambiarla
  reabre la cámara, no durante una toma.

## 4. Lentes
- Las píldoras sobre los controles («0.5 · 1× · 2») cambian de lente; también puedes pellizcar la imagen para el zoom.
- Durante una toma solo cambia el zoom; abrir otra cámara (frontal o lentes separadas) se hace sin grabar.

## 5. Controles manuales
Toca una lectura (ISO, OBTURADOR, BALANCE, ENFOQUE, EV) para abrir su control. «Auto» vuelve al automático. AE-L,
AF-L y AWB-L bloquean exposición, enfoque y balance (ámbar = bloqueado). Toca la imagen para enfocar en ese punto.

## 6. LUT y UC-Log
- Chip **LUT**: aspecto grabado en el archivo, o «Solo monitor» (cambia lo que ves, el archivo no se toca).
- **UC-Log** es un perfil plano de 10 bits para etalonar después. Con «Auto» el monitor muestra UC-Log → Rec.709 para
  juzgar la exposición mientras grabas en Log.
- *Ajustes › LUT* importa archivos `.cube` (17 o 33 puntos) y muestra su vista previa.

## 7. Herramientas de monitor
Botón **Monitor**: cebras (nivel configurable, y cebras de piel 65–75 %), falso color, peaking (color a elegir),
histograma, forma de onda y vectorscopio, líneas de encuadre, guías y desanamorfizado. Solo afectan a la pantalla.
*Ajustes › Pantalla › Brillo máximo* sube el brillo al máximo mientras la cámara está abierta (opcional).

## 8. Almacenamiento y USB
- Toca la lectura de almacenamiento para elegir dónde se graba: memoria interna, tarjeta SD o un pendrive/disco USB.
- Un destino nuevo pasa una prueba de velocidad; si es lento para el modo actual se avisa antes de grabar.
- Si el destino desaparece (pendrive desconectado), la app sigue en la memoria interna y lo dice en ámbar.
- En FAT32 las tomas largas se parten en archivos de menos de 4 GB.

## 9. Audio y WAV
- *Ajustes › Audio*: micrófono (interno, USB, Bluetooth), ganancia y vúmetro. Si el micrófono elegido falla se usa el
  interno y se avisa.
- **WAV aparte** guarda además un WAV sin comprimir junto a cada vídeo, sincronizado con la imagen y con el código de
  tiempo en sus metadatos.

## 10. Foto y HDR
- El botón de modo (junto a Ajustes) cambia entre vídeo y foto.
- **HDR** en «Auto» usa el HDR del fabricante si la cámara lo ofrece, si no Ultra HDR, si no la fusión propia de tres
  exposiciones (unos segundos de «Procesando…»). *Ajustes › Foto* cambia el valor por defecto.

## 11. Multicámara
- *Botón multicámara › Rol*: **Cámara** (graba y se deja controlar), **Monitor** (ve y controla una cámara) o
  **Maestro** (ve y controla varias, «REC en todas»).
- **Emparejar**: en la cámara «Mostrar código»; en el monitor escanea el QR o escribe el código corto. Los dos móviles
  en la misma Wi-Fi o uno compartiendo zona Wi-Fi con el otro. Se emparejan una vez; después se encuentran solos.
- El monitor muestra la imagen en directo, la tira de estado y las alertas de la cámara, y controla REC, exposición,
  enfoque, balance, LUT y formato. Las tomas empiezan sincronizadas con un reloj común.
- **Proxies**: con *Ajustes › Multicámara › Proxy* la cámara graba además una copia ligera; el monitor la descarga
  desde «Proxies» para revisar o montar en el momento.

## 12. Copia de seguridad
*Ajustes › Copia de seguridad › Exportar copia* guarda ajustes, biblioteca de LUT y, si quieres, las capacidades medidas de
este móvil en un `.zip`, cifrado con contraseña (AES-256) si pones una; sin ella no se puede abrir ni recuperar.
«Importar copia» lo restaura aquí o en otro móvil (las capacidades solo se usan en el mismo móvil y versión del sistema).
Los emparejamientos multicámara no se incluyen: se vuelven a emparejar con el QR.

## 13. Accesibilidad
- Todos los controles tienen nombre y estado para TalkBack; REC dice «Detener grabación, Grabando, 00:01:12», la tira
  de estado anuncia el tiempo una vez por minuto y las alertas críticas interrumpen.
- Texto hasta el 200 %: los textos se reparten en varias líneas; los números (código de tiempo, valores) crecen hasta
  el 150 % para no tapar la imagen.
- Con *Quitar animaciones* (Accesibilidad del sistema) se quitan los fundidos y el parpadeo de REC.

## 14. Solución de problemas
- **Calor**: un aviso predice minutos hasta el sobrecalentamiento y propone un modo más ligero (4K30 o 1080p). Si el
  sistema llega a crítico, la toma se para y se guarda. Quita la funda, evita el sol, baja a 4K30 o 1080p.
- **Almacenamiento**: el aviso dice los minutos que quedan al bitrate actual. Al llenarse, la toma se para y se guarda.
  Un pendrive lento provoca fotogramas perdidos: usa uno USB 3 o graba en interna.
- **Modo rechazado**: el móvil no admite esa combinación (resolución, fps, 10-bit, codec o segunda cámara). Elige otro
  o repite la autoconfiguración; los modos que no aguantaron aparecen apagados con el motivo.
- **Batería**: el aviso dice los minutos que quedan; por debajo del mínimo la toma se para y se guarda.
- **Multicámara no conecta**: misma red, sin aislamiento de clientes en el router; prueba la zona Wi-Fi de uno de
  los móviles.
- Para informar de un fallo, *Diagnóstico* (botón i) exporta un informe del móvil sin datos personales.
