#!/usr/bin/env bash
# Compares two APKs built from the same source, ignoring only the signature (reproducible builds, F-Droid).
#
#   scripts/release/compare-apks.sh first.apk second.apk
#
# The APK Signing Block (v2/v3) lives outside the zip entries, so unzipping drops it; the v1 (JAR) signature files in
# META-INF (MANIFEST.MF, *.SF, *.RSA, *.DSA, *.EC) are removed before comparing. Everything else must be byte-identical:
# the entry list and its order, and every entry's content. Exit status 0 when they match, 1 when they differ.
set -euo pipefail
export LC_ALL=C

if [[ $# -ne 2 ]]; then
    echo "usage: $0 first.apk second.apk" >&2
    exit 2
fi
first=$1
second=$2
for apk in "$first" "$second"; do
    [[ -f "$apk" ]] || { echo "not a file: $apk" >&2; exit 2; }
done

work=$(mktemp -d "${TMPDIR:-/tmp}/compare-apks.XXXXXX")
trap 'rm -rf "$work"' EXIT

is_signature_entry() {
    [[ "$1" =~ ^META-INF/([^/]+\.(SF|RSA|DSA|EC)|MANIFEST\.MF)$ ]]
}

entries() {
    local name
    unzip -Z1 "$1" | while IFS= read -r name; do
        is_signature_entry "$name" || printf '%s\n' "$name"
    done
}

entries "$first" > "$work/first.list"
entries "$second" > "$work/second.list"

status=0
if ! diff -u "$work/first.list" "$work/second.list" > "$work/list.diff"; then
    echo "::error::The APKs have different entries (or entry order):"
    cat "$work/list.diff"
    status=1
fi

# Per-entry zip metadata (permissions, sizes, compression method, timestamp): F-Droid copies the upstream signature
# onto its own build, which only verifies when the zip structure matches too, not just the extracted bytes.
zip_metadata() {
    zipinfo -l "$1" | sed -e '1,2d' -e '$d' | while IFS= read -r line; do
        is_signature_entry "${line##* }" || printf '%s\n' "$line"
    done
}
zip_metadata "$first" > "$work/first.meta"
zip_metadata "$second" > "$work/second.meta"
if ! diff -u "$work/first.meta" "$work/second.meta" > "$work/meta.diff"; then
    echo "::error::The APKs have different zip metadata (sizes, compression or timestamps):"
    head -n 100 "$work/meta.diff"
    status=1
fi

mkdir "$work/a" "$work/b"
unzip -q "$first" -d "$work/a"
unzip -q "$second" -d "$work/b"
for dir in "$work/a" "$work/b"; do
    find "$dir/META-INF" -maxdepth 1 -type f \( -name '*.SF' -o -name '*.RSA' -o -name '*.DSA' -o -name '*.EC' \
        -o -name 'MANIFEST.MF' \) -delete 2>/dev/null || true
done

if ! diff -r -q "$work/a" "$work/b" > "$work/content.diff"; then
    echo "::error::The APKs differ in content (signature excluded):"
    sed -e "s|$work/a/||g" -e "s|$work/b/||g" "$work/content.diff"
    status=1
fi

if [[ $status -eq 0 ]]; then
    echo "Reproducible: $(wc -l < "$work/first.list") entries identical (signature excluded)."
fi
exit "$status"
