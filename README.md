# SMARTIE Quote Desk — Native Android

Native Android replacement for the existing SMARTIE Quote Desk TWA.

## Technology

- Kotlin and Jetpack Compose
- Firebase Authentication with Android Credential Manager
- Cloud Firestore with offline cache
- Material 3 UI
- Package ID: `in.smartie.quotedesk` (production) and `in.smartie.quotedesk.staging` (staging flavour)
- Version code: `5` today; a production release must stay above the installed TWA's

## Build flavours

| Flavour | Application id | Firebase project | Use |
|---|---|---|---|
| `production` | `in.smartie.quotedesk` | `smartie-quote-desk` | The app people actually use. Not built from feature branches. |
| `staging` | `in.smartie.quotedesk.staging` | `smartie-quote-desk-staging` | All parity development and acceptance testing. Installs side by side with production. |

Each flavour reads its own ignored configuration file, `app/src/production/google-services.json`
and `app/src/staging/google-services.json`. `scripts/configure-firebase.sh` places them from the
GitHub secrets, falling back to the committed placeholders so a clean checkout still builds.

## Role access

| Role | Products and prices | Stock | Purchase requirements | Quotations | Team controls |
|---|---|---|---|---|---|
| Owner / Administrator | Manage | Manage | Manage | Manage | Full |
| Administrator | Manage | Manage | Manage | Manage | Staff and Workers |
| Staff | View | Add / remove | Manage | Create and view | None |
| Worker | Hidden | View only | View and add | Hidden | None |

Owners are labelled `Owner / Administrator` with a `Primary` or `Additional` sub-label. The Primary
Owner cannot be demoted, switched off or removed by anyone, including themselves. Only the Primary
Owner appoints, demotes or emergency-revokes the Additional Owner, and at most two people hold an
Owner position. An Administrator manages Administrator, Staff and Worker accounts but never an Owner.
Adding or removing stock never requires a note. Setting an exact quantity is an Owner or
Administrator action and asks for a reason.

The signed-in member is hidden from the People list (by uid and by email), active members appear
first, and deleting a profile lets that person return as a Worker on their next sign-in. The single
source of these rules is `domain/Permissions.kt`, with a test per row in `PermissionsTest`.

## Required Firebase setup

The project contains `app/google-services.placeholder.json` for source checks only. Before a real build, replace the ignored `app/google-services.json` file:

1. Open Firebase Console → Project settings → Your apps → Add app → Android.
2. Enter package name `in.smartie.quotedesk`.
3. Add the existing release certificate fingerprints:
   - SHA-1: `28:3E:02:7D:67:1B:EF:4A:1E:16:B3:C0:D5:5F:97:70:3A:F4:5C:60`
   - SHA-256: `E5:B6:00:0A:BC:01:5F:44:B2:F5:12:37:08:77:54:49:4A:C9:68:7B:36:F6:F2:65:C7:7C:5E:E1:6F:29:54:16`
4. Download the real `google-services.json` and replace the placeholder.
5. Keep Google Authentication enabled. Existing Firestore collections and rules are reused.

## Local build

Use Android Studio with JDK 17 and Android SDK 36. For a debug build:

```bash
./gradlew assembleDebug
```

For a release build, create an untracked `keystore.properties` file:

```properties
storeFile=/absolute/path/to/smartie-quote-desk-release.p12
storePassword=YOUR_PASSWORD
keyAlias=smartie-quote-desk
keyPassword=YOUR_PASSWORD
storeType=PKCS12
```

Then run:

```bash
./gradlew assembleRelease
```

Never commit the signing key or passwords.

## Protected GitHub build

The Actions workflow produces a functional signed release APK when these repository secrets exist:

- `FIREBASE_GOOGLE_SERVICES_JSON`: complete Android Firebase configuration file contents
- `ANDROID_KEYSTORE_BASE64`: base64 text of the private PKCS12 signing key
- `ANDROID_SIGNING_PASSWORD`: password for the existing SMARTIE signing key
- `FIREBASE_GOOGLE_SERVICES_JSON_STAGING`: the staging project's Android configuration file
- `ANDROID_STAGING_KEYSTORE_BASE64`: base64 text of the staging PKCS12 signing key
- `ANDROID_STAGING_KEYSTORE_PASSWORD`: password for the staging signing key

The workflow runs unit tests and lint, runs the Firestore rules suite against the emulator, and
builds a staging debug APK on every branch. A signed production release is built from `main` only.

Without Firebase configuration the workflow intentionally builds only against a placeholder. Without signing secrets it falls back to a debug APK. Private signing files are never committed.

## Migration safety

The current TWA remains usable while this project is developed. The first production native build must be signed with the same release key and have a version code above `1`. Firestore-synced products, stock, requirements, people and quotations remain available; browser-only unfinished drafts do not automatically migrate.

## Firestore rules

`firestore/firestore.rules` is the v9 draft for staging. It is never deployed from this repository.
Run its test suite locally with the Firebase emulator:

```bash
cd firestore
npm ci
npm run test:emulator
```

Production keeps the V8C4 rules until the cutover described in the parity audit.

## Staging signing

Debug builds are signed, by default, with a keystore the Android plugin
generates on the spot. On a throwaway CI runner that keystore is regenerated on
every run, so its SHA-1 changes every time and cannot be registered with
Firebase: Google sign-in would work on one APK and then fail with
`DEVELOPER_ERROR` on the next.

The staging flavour therefore uses its own signing key — separate from the
release key, so a staging build can never carry the identity that installs over
the real app.

**Create the key once:**

```bash
keytool -genkeypair -v \
  -keystore smartie-quote-desk-staging.p12 -storetype PKCS12 \
  -alias smartie-quote-desk-staging \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=SMARTIE Quote Desk Staging, O=Smart India Enterprises, C=IN"
```

Keep the file and its password safe and out of the repository, then add them as
the two repository secrets listed above:

```bash
base64 -w0 smartie-quote-desk-staging.p12    # the value for ANDROID_STAGING_KEYSTORE_BASE64
```

**Register it with Firebase.** The next CI run prints the APK's certificate in
the job summary, in the form the Firebase console expects. Add the **SHA-1** to
the Android app `in.smartie.quotedesk.staging.debug` in the staging project —
that is the package CI builds, because the debug build type appends `.debug`.
SHA-256 is not needed for sign-in but is required later for Play Integrity and
App Links. Re-download `google-services.json` afterwards and update
`FIREBASE_GOOGLE_SERVICES_JSON_STAGING`.

Until those secrets exist the build still succeeds; the job summary says plainly
that the certificate is not stable and must not be registered.

For a local build, put the same values in an untracked `staging-keystore.properties`:

```properties
storeFile=/absolute/path/to/smartie-quote-desk-staging.p12
storePassword=YOUR_PASSWORD
keyAlias=smartie-quote-desk-staging
keyPassword=YOUR_PASSWORD
storeType=PKCS12
```

Without that file, local debug builds keep using your own machine's debug key,
exactly as before. Production signing is untouched: same key, same secrets,
release builds from `main` only.
