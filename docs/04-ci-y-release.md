# CI y release

## Workflows de GitHub Actions
- `ci.yml` (PR y push): Gradle build, ktlint/detekt, tests unitarios, tests de shaders/LUT comparando con imágenes de referencia.
- `release.yml` (tag `v*`): compila `assembleRelease`, firma el APK con keystore desde secretos (`KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`), publica en GitHub Releases con checksum.
- Metadatos F-Droid en `metadata/` con builds reproducibles (versión de Gradle, JDK y dependencias fijadas).

## Flujo de trabajo (PR → CI verde → merge → versión)
1. Rama por tarea, push y PR (`gh pr create`).
2. CI obligatorio: `gh pr checks --watch`; si falla se corrige en la rama y se repite.
3. Con todo verde, merge squash y borrado de rama (`gh pr merge --squash --delete-branch`).
4. Con master en verde, tag `v<versión>` en **semver** `vMAJOR.MINOR.PATCH` (D76): MINOR para funcionalidades, PATCH para arreglos, 0.x hasta la v1; las releases 0.x se publican como pre-release y la fase va en las notas, no en el número; push del tag; `release.yml` firma y publica la GitHub Release.
5. Se verifica la release: el workflow termina bien, el APK se descarga y `apksigner verify --print-certs` muestra el certificado esperado.
Reglas: nunca mergear con CI en rojo, nunca `--admin` ni force push, nunca desactivar o relajar checks. Recomendado: protección de la rama master con CI obligatorio (`gh api` o ajustes del repo).

## Cobertura de código (D72)
- Kover (`org.jetbrains.kotlinx.kover`) se aplica a todos los módulos desde el `build.gradle.kts` raíz. La variante de
  informe `unit` cubre los tests unitarios JVM de la variante debug: `./gradlew koverHtmlReportUnit` deja el informe
  agregado de todos los módulos en `build/reports/kover/htmlUnit/` y cada módulo el suyo en
  `<módulo>/build/reports/kover/`. Solo se excluye código generado (`R`, `BuildConfig`, `ComposableSingletons`,
  `*_Factory`).
- CI (job *Build, lint and tests*): genera los informes, escribe en el resumen del job una tabla por módulo (líneas y
  ramas, con la línea base), sube el HTML como artefacto `coverage-report` y ejecuta el trinquete.
- Trinquete: `config/coverage-baseline.json` guarda el % de líneas y ramas de cada módulo. `./gradlew
  checkCoverageBaseline` falla si algún módulo baja más de 0,5 puntos de su línea base, si un módulo nuevo no tiene línea
  base o si falta el informe de uno que la tiene. Cuando la cobertura sube, `./gradlew updateCoverageBaseline` reescribe
  la línea base (los valores solo suben) y se commitea en el mismo PR. Bajarla a propósito (p. ej. código movido a otro
  módulo) exige `python3 -I scripts/coverage/coverage.py update --allow-decrease` y justificarlo en el PR. El script
  (`scripts/coverage/coverage.py`, solo biblioteca estándar) tiene sus tests en CI.
- Cobertura en dispositivo (opcional, documentada en `docs/TESTING.md`): `-PandroidTestCoverage`.

## Convenciones
Commits convencionales; sin release-please; versión derivada del tag.
