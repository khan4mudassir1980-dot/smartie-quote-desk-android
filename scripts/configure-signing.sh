#!/usr/bin/env bash
# Places the signing keys for a CI build.
#
# Two independent keys:
#   release  - production only, built from main. Unchanged by this script's
#              existence; it behaves exactly as the inline step it replaced.
#   staging  - signs the staging APK testers install. It must be stable, because
#              its SHA-1 is registered with the staging Firebase project.
#
# Keys only ever come from repository secrets. Nothing is echoed: the script
# prints whether a key was placed, never the key or its password.
set -euo pipefail

placed_release="false"
placed_staging="false"

if [ -n "${ANDROID_KEYSTORE_BASE64:-}" ] && [ -n "${ANDROID_SIGNING_PASSWORD:-}" ]; then
  printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 --decode > smartie-quote-desk-release.p12
  {
    echo "storeFile=smartie-quote-desk-release.p12"
    echo "storePassword=$ANDROID_SIGNING_PASSWORD"
    echo "keyAlias=smartie-quote-desk"
    echo "keyPassword=$ANDROID_SIGNING_PASSWORD"
    echo "storeType=PKCS12"
  } > keystore.properties
  placed_release="true"
  echo "release: signing key placed."
else
  echo "release: no signing secrets; a release build would fall back to unsigned."
fi

if [ -n "${ANDROID_STAGING_KEYSTORE_BASE64:-}" ] && [ -n "${ANDROID_STAGING_KEYSTORE_PASSWORD:-}" ]; then
  printf '%s' "$ANDROID_STAGING_KEYSTORE_BASE64" | base64 --decode > smartie-quote-desk-staging.p12
  {
    echo "storeFile=smartie-quote-desk-staging.p12"
    echo "storePassword=$ANDROID_STAGING_KEYSTORE_PASSWORD"
    echo "keyAlias=${ANDROID_STAGING_KEY_ALIAS:-smartie-quote-desk-staging}"
    echo "keyPassword=$ANDROID_STAGING_KEYSTORE_PASSWORD"
    echo "storeType=PKCS12"
  } > staging-keystore.properties
  placed_staging="true"
  echo "staging: signing key placed; the APK's certificate will be stable."
else
  echo "staging: ANDROID_STAGING_KEYSTORE_BASE64 is not set."
  echo "staging: the APK will carry a throwaway debug certificate that changes"
  echo "staging: on every run, so its SHA-1 cannot be registered with Firebase"
  echo "staging: and Google sign-in will NOT work on it."
fi

# Consumed by the workflow to decide whether to build a signed release.
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "release_available=$placed_release" >> "$GITHUB_OUTPUT"
  echo "staging_available=$placed_staging" >> "$GITHUB_OUTPUT"
fi
