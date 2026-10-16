# CI y release

## Workflows de GitHub Actions
- `ci.yml` (PR y push): Gradle build, ktlint/detekt, tests unitarios, tests de shaders/LUT comparando con imágenes de referencia.
- `release.yml` (tag `v*`): compila `assembleRelease`, firma el APK con keystore desde secretos (`KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`), publica en GitHub Releases con checksum.
- Metadatos F-Droid en `metadata/` con builds reproducibles (versión de Gradle, JDK y dependencias fijadas).

## Flujo de trabajo (PR → CI verde → merge → versión)
1. Rama por tarea, push y PR (`gh pr create`).
2. CI obligatorio: `gh pr checks --watch`; si falla se corrige en la rama y se repite.
3. Con todo verde, merge squash y borrado de rama (`gh pr merge --squash --delete-branch`).
4. Con main en verde, tag `v<versión>` (pre-release para las fases previas a la v1, p. ej. `v0.2.1-fase2`) y push del tag; `release.yml` firma y publica la GitHub Release.
5. Se verifica la release: el workflow termina bien, el APK se descarga y `apksigner verify --print-certs` muestra el certificado esperado.
Reglas: nunca mergear con CI en rojo, nunca `--admin` ni force push, nunca desactivar o relajar checks. Recomendado: protección de la rama main con CI obligatorio (`gh api` o ajustes del repo).

## Convenciones
Commits convencionales; sin release-please; versión derivada del tag.
