#!/usr/bin/env bash
# Places the Firebase configuration for each product flavour.
#
# Real configuration always comes from a GitHub secret. When a secret is
# missing the committed placeholder is used instead, so the project still
# configures and builds — but sign-in will not complete on that flavour.
# Production credentials are never used for the staging flavour.
set -euo pipefail

mkdir -p app/src/production app/src/staging

if [ -n "${FIREBASE_GOOGLE_SERVICES_JSON:-}" ]; then
  printf '%s' "$FIREBASE_GOOGLE_SERVICES_JSON" > app/src/production/google-services.json
  echo "production: using protected Firebase configuration."
else
  cp app/google-services.placeholder.json app/src/production/google-services.json
  echo "production: FIREBASE_GOOGLE_SERVICES_JSON is not set; building with the placeholder."
fi

if [ -n "${FIREBASE_GOOGLE_SERVICES_JSON_STAGING:-}" ]; then
  printf '%s' "$FIREBASE_GOOGLE_SERVICES_JSON_STAGING" > app/src/staging/google-services.json
  echo "staging: using protected staging Firebase configuration."
else
  cp app/google-services.staging.placeholder.json app/src/staging/google-services.json
  echo "staging: FIREBASE_GOOGLE_SERVICES_JSON_STAGING is not set; building with the placeholder."
  echo "staging: sign-in will not work on this build until the staging project exists."
fi
