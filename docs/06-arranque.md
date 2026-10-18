# Arranque: keystore, prompt inicial y versiones firmadas

## 1. Generar el keystore (una sola vez, fuera del repo)
```bash
mkdir -p ~/keys/ultimatecamera && chmod 700 ~/keys ~/keys/ultimatecamera

keytool -genkeypair -v \
  -keystore ~/keys/ultimatecamera/ultimatecamera.jks \
  -storetype PKCS12 \
  -alias ultimatecamera \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Qtekfun, OU=Qtekfun, O=Qtekfun, L=Madrid, ST=España, C=ES"
# keytool pedirá la contraseña del almacén; usa una fuerte y guárdala en tu gestor.

cat > ~/keys/ultimatecamera/keystore.properties <<'EOF'
storeFile=/home/TU_USUARIO/keys/ultimatecamera/ultimatecamera.jks
storePassword=CAMBIA_ESTO
keyAlias=ultimatecamera
keyPassword=CAMBIA_ESTO
EOF
chmod 600 ~/keys/ultimatecamera/keystore.properties
```
Haz copia de seguridad del `.jks` y de las contraseñas: si se pierde, no se podrán actualizar las instalaciones firmadas con él. Nunca entra en el repo.

Para el CI (secretos de GitHub): `base64 -w0 ~/keys/ultimatecamera/ultimatecamera.jks` → `KEYSTORE_B64`, más `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## 1b. Subir la firma al repo de GitHub como secretos (para el CI)
```bash
gh auth status || gh auth login
REPO=qtekfun/ultimatecamera          # ajusta propietario/nombre
P=~/keys/ultimatecamera/keystore.properties

base64 -w0 ~/keys/ultimatecamera/ultimatecamera.jks | gh secret set KEYSTORE_B64 -R "$REPO"
grep '^storePassword=' "$P" | cut -d= -f2- | tr -d '\n' | gh secret set KEYSTORE_PASSWORD -R "$REPO"
grep '^keyAlias='      "$P" | cut -d= -f2- | tr -d '\n' | gh secret set KEY_ALIAS -R "$REPO"
grep '^keyPassword='   "$P" | cut -d= -f2- | tr -d '\n' | gh secret set KEY_PASSWORD -R "$REPO"
gh secret list -R "$REPO"
```
El repo debe existir antes (`gh repo create qtekfun/ultimatecamera --private --source=. --remote=origin`). Los secretos no se pueden leer de vuelta, solo reemplazar.

En `release.yml`, el paso de firma decodifica el keystore a un fichero temporal y exporta las variables que lee Gradle:
```yaml
- name: Decode keystore
  run: echo "${{ secrets.KEYSTORE_B64 }}" | base64 -d > "$RUNNER_TEMP/release.jks"
- name: Build signed release
  env:
    KEYSTORE_PATH: ${{ runner.temp }}/release.jks
    KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
    KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
    KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
  run: ./gradlew assembleRelease
```
Gradle usa `keystore.properties` en local y estas variables de entorno en el CI.

## 2. Reglas de firma para el proyecto
- `app/build.gradle.kts` lee la firma de `~/keys/ultimatecamera/keystore.properties` o, si no existe, de variables de entorno del CI. Si no hay ninguna, el build `release` falla con un mensaje claro (no firma con la clave de debug).
- Mismo `applicationId` y misma clave en todas las versiones de prueba, para poder actualizar con `adb install -r` sin desinstalar.
- `versionCode` incremental automático; `versionName` en semver `MAJOR.MINOR.PATCH` (D76; antes `0.<fase>.<n>-faseN`, abandonado por decisión del propietario).

## 3. Prompt inicial para Claude Code
Ver el prompt completo en la respuesta de la conversación; está copiado aquí también.

```
Eres el desarrollador de UltimateCamera (com.qtekfun.ultimatecamera). Lee CLAUDE.md y todo docs/ antes de empezar; son la fuente de verdad (spec-driven).

Trabajo:
1. Crea el repositorio Android (Kotlin, Compose, Material 3 Expressive, minSdk 33, multimódulo según CLAUDE.md) con git y commits convencionales.
2. Ejecuta las fases de docs/03-fases.md en orden, empezando por la Fase 0 (spike). No pases a la siguiente fase hasta cumplir los criterios de aceptación de la actual y dejar el estado en docs/PROGRESS.md (hecho, pendiente, decisiones, riesgos).
3. Firma: configura Gradle para firmar release con ~/keys/ultimatecamera/keystore.properties (docs/06-arranque.md). Nunca imprimas, copies ni commitees contraseñas ni el .jks.
4. Dispositivos: al empezar cada fase y antes de cada entrega ejecuta `adb devices`. Si hay un móvil autorizado, instala (`adb install -r`), ejecuta las pruebas instrumentadas y los escenarios manuales que se puedan automatizar, y anota resultados por dispositivo. Si no hay móvil, no te detengas: termina lo que se pueda verificar sin hardware (tests unitarios, shaders con imágenes de referencia, build) y deja en docs/TESTING.md una lista de pruebas manuales pendientes.
5. Flujo de git: nunca trabajes en master. Una rama por tarea o hito (feat/…, fix/…, ci/…), push y PR con `gh pr create` (qué cambia, requisitos REQ-xxx cubiertos y cómo se probó). Espera al CI con `gh pr checks --watch`. Si falla, lee los logs (`gh run view --log-failed`), corrige en la misma rama y repite. Solo con todo en verde, haz merge con `gh pr merge --squash --delete-branch`. Nunca mergees con CI en rojo, nunca uses --admin ni force push, nunca desactives ni relajes un check para que pase.
6. Versiones: al cerrar cada fase (y en hitos útiles), tras el merge a master y con master en verde, crea el tag semver `vMAJOR.MINOR.PATCH` (pre-release mientras sea 0.x; la fase va en las notas) y haz push del tag. El workflow release.yml compila, firma con los secretos del repo y publica la GitHub Release con el APK y su .sha256. Comprueba con `gh run watch` que termina bien, descarga el APK de la release, verifica la firma con apksigner verify --print-certs y deja en las notas de la release qué probar. Para pruebas rápidas locales puedes además generar dist/ultimatecamera-<versionName>.apk con `./gradlew assembleRelease`.
7. Fase 0: construye la app de spike, mide en el dispositivo conectado y escribe docs/spike-report.md por modelo. Sin dispositivo, publica igualmente el APK de spike firmado por el mismo flujo y deja las instrucciones para que Z lo pruebe en cada teléfono y te devuelva los resultados.
8. Si una decisión no está en docs/, elige la opción más conservadora, anótala en PROGRESS.md y continúa; solo para y pregunta si es irreversible, cambia el alcance o exigiría saltarse el CI, la firma o las protecciones del repo.

Empieza ahora: crea el proyecto, el CI y la Fase 0, y dime qué dispositivos ves con adb devices.
```

## 4. Comandos útiles
```bash
adb devices                                  # móviles conectados
adb install -r dist/ultimatecamera-<versión>.apk
apksigner verify --print-certs dist/ultimatecamera-<versión>.apk
./gradlew assembleRelease
./gradlew connectedAndroidTest               # con móvil conectado
```
