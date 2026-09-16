#!/usr/bin/env bash
# Prints the certificate fingerprints of a built APK, in the colon-separated
# uppercase form the Firebase console expects.
#
# It reads the signature out of the APK, so it reports what actually signed the
# artifact rather than what the build intended to use. Fingerprints are public
# information: no key material and no password is read or printed here.
#
# Set STAGING_KEY_CONFIGURED=true when a stable staging key signed the build, so
# the report cannot be mistaken for a fingerprint worth registering.
#
# Usage: scripts/print-apk-certificate.sh <path-to-apk>
set -euo pipefail

apk="${1:?usage: print-apk-certificate.sh <apk>}"

if [ ! -f "$apk" ]; then
  echo "No APK at $apk" >&2
  exit 1
fi

format_hex() {
  # e5b6000a… -> E5:B6:00:0A:…
  tr -d ' :' | tr 'a-f' 'A-F' | sed 's/../&:/g; s/:$//'
}

report=""

# apksigner understands v1, v2 and v3 signatures; keytool only reads v1, so it
# is the fallback for when the Android SDK is not on the machine.
apksigner="$(ls "${ANDROID_HOME:-/nonexistent}"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"

if [ -n "$apksigner" ]; then
  certs="$("$apksigner" verify --print-certs "$apk")"
  sha1="$(printf '%s\n' "$certs" | sed -n 's/.*SHA-1 digest: *//p' | head -1 | format_hex)"
  sha256="$(printf '%s\n' "$certs" | sed -n 's/.*SHA-256 digest: *//p' | head -1 | format_hex)"
  report="read with apksigner (v1, v2 and v3 signatures)"
else
  certs="$(keytool -printcert -jarfile "$apk")"
  sha1="$(printf '%s\n' "$certs" | sed -n 's/.*SHA1: *//p' | head -1 | format_hex)"
  sha256="$(printf '%s\n' "$certs" | sed -n 's/.*SHA256: *//p' | head -1 | format_hex)"
  report="read with keytool (v1 signature only)"
fi

if [ -z "$sha1" ]; then
  echo "Could not read a certificate from $apk ($report)." >&2
  exit 1
fi

if [ "${STAGING_KEY_CONFIGURED:-false}" = "true" ]; then
  verdict=$(cat <<'EOF'
**Stable.** Signed with the staging key, so these fingerprints hold for every
future build. Register the SHA-1 against **in.smartie.quotedesk.staging.debug**
in the staging Firebase project. SHA-1 is what Google sign-in needs; SHA-256 is
needed later for Play Integrity and App Links.
EOF
)
else
  verdict=$(cat <<'EOF'
**Not stable — do not register these values.** No staging signing key is
configured, so the Android plugin generated a throwaway debug certificate during
this run and the next run will produce different fingerprints. Add the
`ANDROID_STAGING_KEYSTORE_BASE64` and `ANDROID_STAGING_KEYSTORE_PASSWORD`
secrets first; see the staging signing section of the README.
EOF
)
fi

summary=$(cat <<EOF
### Staging APK certificate

\`$(basename "$apk")\`, $report.

| | |
|---|---|
| SHA-1 | \`$sha1\` |
| SHA-256 | \`$sha256\` |

$verdict
EOF
)

printf '%s\n' "$summary"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  printf '%s\n' "$summary" >> "$GITHUB_STEP_SUMMARY"
fi
