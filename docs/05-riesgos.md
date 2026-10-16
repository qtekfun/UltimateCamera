# Riesgos

| Riesgo | Mitigación |
|---|---|
| 4K60 10-bit con LUT puede no sostenerse en todos los móviles | Fase 0 mide por dispositivo; degradación guiada y catálogo de capacidades |
| Perfiles de color nativos poco expuestos por los fabricantes | Usar lo que exponga cada móvil; registrar qué ofrece cada uno en el spike |
| Cámaras concurrentes limitadas por hardware | Detectar combinaciones soportadas y ofrecer solo esas |
| Audio USB/Bluetooth distinto según fabricante | Pruebas en Fase 0; SCO con aviso de calidad |
| Calentamiento en grabaciones largas | Avisos, bajada guiada de calidad, parada segura configurable |
| Latencia y pérdida de red en exteriores | Wi‑Fi Direct, grabación local siempre, reconexión automática |
| Desenfoque tipo Google Camera exige modelo de segmentación/profundidad (peso, licencia, F-Droid) | Elegir modelo abierto y ejecutable en local; decidirlo en la entrevista de la Fase 9 |
| Los LUTs de fabricantes están publicados para descarga, pero su página de OPPO no indica licencia de redistribución; incluirlos en el repo o el APK podría infringir sus términos y chocar con F-Droid | Catálogo con enlaces, checksum y descarga bajo demanda; solo se empaquetan los de licencia confirmada |
| Builds reproducibles con librerías nativas (WebRTC) | Fijar versiones y revisar compatibilidad con F-Droid en Fase 1 |
