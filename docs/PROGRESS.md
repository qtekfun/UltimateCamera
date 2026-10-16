# Progress

Living status of the project (prompt rule 2). Updated at the start and end of every phase and milestone.

## Current phase: 0 — spike on real devices (in progress)

### Done
- Repository bootstrap: Gradle 9.8.1 (wrapper checksum pinned), AGP 9.4.1, Kotlin 2.4.21, Compose BOM 2026.09.00,
  Material 3 1.5.0-beta01 (Expressive), minSdk 33, JDK 21 toolchain / Java 17 bytecode.
- Release signing: `~/keys/ultimatecamera/keystore.properties` locally, `KEYSTORE_PATH` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` /
  `KEY_PASSWORD` in CI; with neither, release tasks fail with a clear message (verified locally in three cases: none,
  incomplete env, throwaway key via env). Never falls back to the debug key.
- CI (`ci.yml`): ktlint, detekt, unit tests, Android lint, debug APK, and a check that an unsigned release build is refused.
- Release (`release.yml`): tag `v<appVersion>` → tests → signed APK → signature check (rejects debug certificates) →
  GitHub pre-release with the APK and `.sha256`.

### Pending
- Phase 0 spike app and measurements (see docs/03-fases.md).

### Decisions (not covered by docs/, conservative choice taken)
| # | Date | Decision | Reason |
|---|---|---|---|
| D1 | 2026-10-16 | The only direct commit to `main` is the initial spec commit; everything else goes through PRs. | The GitHub repo was empty and a PR needs a base branch. |
| D2 | 2026-10-16 | Kept the existing GitHub repo `qtekfun/UltimateCamera`, which is public (docs/06 suggested `--private`). | Changing visibility is the owner's call; F-Droid needs public sources anyway. No secrets are in the repo. |
| D3 | 2026-10-16 | `compileSdk 37`, `targetSdk 36`. | Same as the sibling apps; target 36 is the behaviour already validated on the OPPO. |
| D4 | 2026-10-16 | Version lives in `gradle.properties` (`appVersion`); the release tag must equal `v<appVersion>` or the release fails. `versionCode = MAJOR*1_000_000 + MINOR*1_000 + PATCH`. | "Version derived from the tag" (docs/04) while keeping the source reproducible for F-Droid (the version must be in the source). The code always grows with phase and patch. |
| D5 | 2026-10-16 | Debug builds use `applicationIdSuffix .debug`. | Debug (debug key) and signed release can coexist on a test phone; `adb install -r` of releases keeps working. |
| D6 | 2026-10-16 | Dark dynamic-colour theme only, for now. | Field-monitor theme (docs/01); a light UI around the viewfinder distorts exposure judgement. Revisit in Phase 1 settings. |
| D7 | 2026-10-16 | PR CI never sees signing secrets; it checks that an unsigned release is refused. Only `release.yml` (tags) signs. | Least exposure of the signing key. |
| D8 | 2026-10-16 | Device work uses `flock /tmp/pixel-device.lock` and explicit `adb -s <serial>`. | The OPPO is shared with other projects' agents (same convention as ultimatevideoeditor). |

### Risks and open issues
- `~/keys/ultimatecamera/keystore.properties` does not exist on the dev machine, so a local `./gradlew assembleRelease`
  fails (by design). Signed APKs come from `release.yml` until the owner creates it (docs/06-arranque.md §1).
- `~/keys/ultimatecamera/ultimatecamera.jks` is world-readable (mode 644); recommended `chmod 600`.
- Pixel 8 (`39031FDJH000W1`) is connected but the owner asked not to use it for now (2026-10-16).

## Devices seen
| Date | Serial | Model | SoC | Android (SDK) | Use |
|---|---|---|---|---|---|
| 2026-10-16 | 39031FDJH000W1 | Google Pixel 8 | Tensor G3 | 37 | Not used (busy, owner's request) |
| 2026-10-16 | adb-3B163R00JYF00000-… (wireless) | OPPO CPH2841 | SM8850 | 36 | Spike measurements |
