#!/usr/bin/env bash
# Places the Firebase configuration for each product flavour.
#
# Real configuration always comes from a GitHub secret. When a secret is
# missing the committed placeholder is used instead, so the project still
# configures and builds — but sign-in will not complete on that flavour.
# Production credentials are never used for the staging flavour.
#
# When the staging secret is set, its contents are checked and described, so a
# build cannot quietly claim to use the staging project while carrying the
# placeholder. Only the project id and the registered package names are
# printed; both of those ship inside every installed APK. No API key, OAuth
# client id or other credential is ever echoed.
set -euo pipefail

# The variant CI builds and hands to testers: applicationId, plus the staging
# flavour suffix, plus the debug build-type suffix.
STAGING_DEBUG_PACKAGE="in.smartie.quotedesk.staging.debug"

check_staging_config() {
  local file="app/src/staging/google-services.json"

  if ! command -v jq >/dev/null 2>&1; then
    echo "staging: jq is not available, so the configuration was not checked."
    return 0
  fi

  if ! jq empty "$file" >/dev/null 2>&1; then
    echo "staging: the secret is not valid JSON." >&2
    echo "staging: copy google-services.json from the staging Firebase project verbatim." >&2
    return 1
  fi

  local project_id project_number packages
  project_id=$(jq -r '.project_info.project_id // ""' "$file")
  project_number=$(jq -r '.project_info.project_number // ""' "$file")
  packages=$(jq -r '[.client[]?.client_info.android_client_info.package_name] | join(", ")' "$file")

  echo "staging: project '${project_id:-<missing>}', registered packages: ${packages:-<none>}."

  if [ "$project_number" = "000000000000" ] || grep -q 'REPLACE_WITH' "$file"; then
    echo "staging: this is the placeholder, not a real Firebase configuration." >&2
    echo "staging: set FIREBASE_GOOGLE_SERVICES_JSON_STAGING to the google-services.json" >&2
    echo "staging: downloaded from the staging Firebase project." >&2
    return 1
  fi

  if ! jq -e --arg p "$STAGING_DEBUG_PACKAGE" \
      'any(.client[]?; .client_info.android_client_info.package_name == $p)' \
      "$file" >/dev/null; then
    echo "staging: no client for $STAGING_DEBUG_PACKAGE, which is the variant CI builds." >&2
    echo "staging: add an Android app with that exact package name to the staging" >&2
    echo "staging: Firebase project, then download google-services.json again." >&2
    return 1
  fi

  echo "staging: real configuration, with a client for $STAGING_DEBUG_PACKAGE."
}

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
  check_staging_config
else
  cp app/google-services.staging.placeholder.json app/src/staging/google-services.json
  echo "staging: FIREBASE_GOOGLE_SERVICES_JSON_STAGING is not set; building with the placeholder."
  echo "staging: sign-in will not work on this build until the staging project exists."
fi
