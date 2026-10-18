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
storeFile=<ruta absoluta al .jks>
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

## 3. Comandos útiles
```bash
adb devices                                  # móviles conectados
adb install -r dist/ultimatecamera-<versión>.apk
apksigner verify --print-certs dist/ultimatecamera-<versión>.apk
./gradlew assembleRelease
./gradlew connectedAndroidTest               # con móvil conectado
```
