# SMARTIE Quote Desk — Native Android

Native Android replacement for the existing SMARTIE Quote Desk TWA.

## Technology

- Kotlin and Jetpack Compose
- Firebase Authentication with Android Credential Manager
- Cloud Firestore with offline cache
- Material 3 UI
- Package ID: `in.smartie.quotedesk`
- Version code: `2` (updates the current version-code-1 TWA)

## Role access

| Role | Products and prices | Stock | Purchase requirements | Quotations | Team controls |
|---|---|---|---|---|---|
| Owner / Administrator | Manage | Manage | Manage | Manage | Full |
| Administrator | Manage | Manage | Manage | Manage | Staff and Workers |
| Staff | View | Add / remove | Manage | Create and view | None |
| Worker | Hidden | View only | Add and edit own open item | Hidden | None |

The original Owner is protected. One second Owner may be appointed; only the original Owner can emergency-revoke that position. The signed-in member is hidden from the People list, active members appear first, and deleting a profile lets that person return as a Worker on their next Google sign-in.

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

Without Firebase configuration the workflow intentionally builds only against a placeholder. Without signing secrets it falls back to a debug APK. Private signing files are never committed.

## Migration safety

The current TWA remains usable while this project is developed. The first production native build must be signed with the same release key and have a version code above `1`. Firestore-synced products, stock, requirements, people and quotations remain available; browser-only unfinished drafts do not automatically migrate.
