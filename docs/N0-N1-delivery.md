# N0 Foundation and N1 Auth & Team — delivery notes

Branch: `claude/sweet-fermi-ejyrg2`.
Nothing here touches the PWA repository, production Firebase, production
rules or production data.

## What is in this delivery

**N0 Foundation**

- `production` and `staging` build flavours. Production keeps the exact
  package id `in.smartie.quotedesk`; staging installs alongside it as
  `in.smartie.quotedesk.staging` with its own Firebase configuration, placed
  by `scripts/configure-firebase.sh` from a GitHub secret and falling back to
  a committed placeholder.
- The approved PWA design tokens, Inter bundled at five weights, and the
  shared compact components (top bar with the page title, tags, status chips,
  summary tiles, stepper, single-line segmented control, list rows, confirm
  dialogs, connectivity banner). The system font scale is honoured up to 1.3x
  instead of being capped at 1.0x.
- A legacy-tolerant data layer: documents are read as plain field maps, so
  numeric strings, `0`/`1` booleans and Long/Double/Date timestamps all read
  correctly, a missing price stays "not set" rather than becoming zero, and a
  model containing a slash no longer builds an invalid document reference.
- `Permissions`: one object holding the audit's role matrix, with a test per
  row.
- One error path with the PWA's own wording, snapshot listeners that cannot
  crash the app, and a connectivity signal.
- The four beta screens are read-only: their write paths are removed until
  each is rebuilt, so no build from this branch can erase a party's fields,
  hard-delete a purchase requirement or write a product the PWA cannot read.
- `firestore/firestore.rules` — the v9 draft for staging, with an emulator
  suite covering the matrix. **Not deployed by this branch.**

**N1 Auth & Team**

- Google sign-in, email and password sign-in, password reset, and safe
  linking when one email holds both. No self-registration: a new person
  enters as an active Worker.
- A profile deleted while someone is signed in is re-created once, so they
  return as a Worker instead of sitting on Loading.
- Role and switch-off changes reach the running app within seconds, and
  signing out tears listeners down first.
- The full role-filtered More menu in the PWA's order. Team and About & legal
  are built; the rest name the phase that replaces them.
- People: live, de-duplicated by email, the signed-in person excluded by uid
  and by email, active first, grouped, searchable and filterable.
- Owner protection, Administrator managing Administrator/Staff/Worker,
  appoint, demote and emergency revoke as transactions, typed-name
  confirmations, and a team activity list of the latest 40 audit entries.

## Human actions still required

1. **Create the staging Firebase project.** `smartie-quote-desk-staging`,
   with Google and Email/Password sign-in enabled. Register two Android apps,
   `in.smartie.quotedesk.staging` and `in.smartie.quotedesk.staging.debug`,
   each with your debug signing SHA-1. Download `google-services.json` and
   add its contents as the GitHub secret
   `FIREBASE_GOOGLE_SERVICES_JSON_STAGING`.
   Until this exists, staging builds use the placeholder and sign-in cannot
   complete; everything else still builds and tests.
2. **Seed the owner identity in staging.** Write
   `teamSettings/access.primaryOwnerUid` with the Owner's uid, or set
   `smartie.stagingPrimaryOwnerEmail` in `gradle.properties` so the email
   fallback resolves. The app never writes `primaryOwnerUid` itself.
3. **Deploy the v9 rules to staging** when you are ready
   (`firebase deploy --only firestore:rules` from `firestore/`, against the
   staging project). Not run from here.
4. Optional: a read-only export of production Firestore, so the mapper suite
   runs against real legacy documents as well as the synthesised ones.

## Building and installing the staging APK

CI builds `app-staging-debug.apk` on every push to this branch; download it
from the run's `smartie-native-apks` artifact. Locally:

```bash
./gradlew assembleStagingDebug
```

It installs next to the production app, so testers keep both.

## Manual acceptance checklist

Run against **staging** only. No real business data.

| Test | What to do | Pass |
|---|---|---|
| T-A1 | Fresh install, no session | Only the sign-in screen; no data before sign-in |
| T-A2 | Google sign-in with a new account | Enters as an active Worker |
| T-A3 | Email and password sign-in for an existing account | Same uid and profile as the PWA |
| T-A4 | Password reset | Reset email arrives; the Google password is untouched |
| T-A5 | Switch an account off while it is signed in | The Blocked screen appears within seconds |
| T-A6 | Delete a profile while that person is signed in | They return as a Worker without restarting |
| T-A7 | Open Team as Owner or Administrator | You never appear in your own People list |
| T-A8 | Look at the Primary Owner row | `Owner / Administrator` and `Primary`; no role, switch or remove control |
| T-A9 | As an Administrator, open an Owner row | No controls; the rules refuse a direct write |
| T-A10 | As Primary Owner, appoint a second Owner | Allowed once; a second attempt is refused |
| T-A11 | As Primary Owner, emergency revoke | The person becomes a Worker with the account switched off, and an audit row appears |
| T-A12 | Change someone's role while they use the app | Their permissions change within seconds, no crash |
| T-X4 | 360x640 and 412x915, font scale 1.0 and 1.3 | No clipped text; nothing hidden behind the bar or keyboard |
| Stability | 50 sign-in and sign-out cycles | No crash |

## Not in this delivery

Phases N2-N8: the full product catalogue, stock movement, purchase
requirements, quotations and the PDF, settings, the calculators, and the
migration. Every one of those screens says which phase it is waiting for.

## Rollback

Nothing has been deployed or migrated, so rollback is `git revert` of the
commits on this branch, or simply not merging it. Production Firebase and the
PWA are untouched.
