#!/usr/bin/env bash
# Uploads the release signing key to the GitHub secrets that release.yml reads (docs/06-arranque.md §1b), safely:
# refuses to run if ~/keys/ultimatecamera/keystore.properties is missing or has an empty value, and checks that the
# alias and both passwords really open the keystore before uploading anything. Values are never printed.
#
#   scripts/release/set-signing-secrets.sh [owner/repo]     # default qtekfun/UltimateCamera
set -euo pipefail

repo="${1:-qtekfun/UltimateCamera}"
props="$HOME/keys/ultimatecamera/keystore.properties"
[ -f "$props" ] || { echo "missing $props: create it first (docs/06-arranque.md §1)" >&2; exit 1; }

get() { grep -E "^$1=" "$props" | head -n1 | cut -d= -f2- | tr -d '\r\n'; }
for key in storeFile storePassword keyAlias keyPassword; do
  [ -n "$(get "$key")" ] || { echo "$key is empty or missing in $props" >&2; exit 1; }
done
store="$(get storeFile)"
store="${store/#\~/$HOME}"
[ -f "$store" ] || { echo "storeFile does not exist: $store" >&2; exit 1; }

# Prove the values work by signing a throwaway jar (kept on disk under ~/.cache, removed afterwards).
work="$(mktemp -d "$HOME/.cache/uc-signcheck.XXXXXX")"
trap 'rm -rf "$work"' EXIT
echo probe > "$work/probe.txt"
(cd "$work" && jar cf probe.jar probe.txt)
STOREPASS="$(get storePassword)" KEYPASS="$(get keyPassword)" \
  jarsigner -keystore "$store" -storepass:env STOREPASS -keypass:env KEYPASS "$work/probe.jar" "$(get keyAlias)" > /dev/null \
  || { echo "storePassword, keyPassword or keyAlias do not open $store" >&2; exit 1; }
echo "keystore, alias and passwords verified"

base64 -w0 "$store" | gh secret set KEYSTORE_B64 -R "$repo"
printf '%s' "$(get storePassword)" | gh secret set KEYSTORE_PASSWORD -R "$repo"
printf '%s' "$(get keyAlias)" | gh secret set KEY_ALIAS -R "$repo"
printf '%s' "$(get keyPassword)" | gh secret set KEY_PASSWORD -R "$repo"
gh secret list -R "$repo"
