# Testing

## Automated
| What | Where | Command |
|---|---|---|
| Unit tests | every module, CI | `./gradlew testDebugUnitTest` |
| Style and static analysis | CI | `./gradlew ktlintCheck detekt lintDebug` |
| Unsigned release is refused | CI | `./gradlew :app:assembleRelease` must fail with "Release signing is not configured" |
| Release signature | `release.yml` | `apksigner verify --print-certs` (fails on a debug certificate) |

## Manual tests pending
Tests that need hardware and could not be run yet. Each line names the device(s) still missing.

| Test | Pending on |
|---|---|
| Install the signed release over itself with `adb install -r` (same key, same applicationId) | OPPO, vivo, Xiaomi, Honor, Pixel |
