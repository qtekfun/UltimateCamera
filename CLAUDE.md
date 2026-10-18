# UltimateCamera — instrucciones para Claude Code

App Android de cámara de vídeo profesional de la familia Ultimate (`com.qtekfun.ultimatecamera`).
Metodología: spec-driven (SDD). La fuente de verdad está en `docs/`. Antes de escribir código lee `docs/00-vision-y-decisiones.md` y la fase activa en `docs/03-fases.md`.

## Reglas
- Código, commits, nombres de clases y comentarios en inglés. Interfaz en español e inglés (strings en `res/values` y `res/values-es`).
- Kotlin + Jetpack Compose, Material 3 Expressive con color dinámico. minSdk 33.
- Cámara: Camera2 directo (sin CameraX). Pipeline GPU propio: Camera2 → OpenGL ES → MediaCodec.
- No avances de fase sin que se cumplan los criterios de aceptación de la anterior.
- Cada requisito (`REQ-xxx`) debe tener test o verificación manual descrita.
- Todo lo dependiente del fabricante va detrás de una interfaz y se registra en el catálogo de capacidades del dispositivo.
- Sin telemetría ni librerías propietarias en la rama principal (F-Droid).
- Commits convencionales; sin release-please. Versionado por tag `vX.Y.Z`.

## Flujo de git y versiones
Nunca en master: rama → PR → CI verde → merge squash → tag de versión → release firmada por CI. Nunca mergear con CI en rojo ni saltarse checks. Detalle en `docs/04-ci-y-release.md` y `docs/06-arranque.md`.

## Preparar el futuro
La v1 es solo vídeo, pero foto y desenfoque de fondo estilo Google Camera están confirmados para versiones posteriores (fases 8 y 9). Diseña `core-camera` y `core-gpu` sin asumir que solo hay vídeo.

## Estructura de módulos sugerida
`app`, `core-camera` (Camera2, capacidades), `core-gpu` (render, LUT, medidores), `core-codec` (MediaCodec, muxer), `core-audio` (fuentes, ganancia, WAV), `core-net` (WebRTC, emparejado, control), `core-storage` (SAF, backup), `feature-*` (UI).
