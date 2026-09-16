# Staging catalogue import

Closes **T-P1** (count parity and 20 models × 3 tiers) and as much of **T-P4**
(pin order) as two separate Firebase projects allow. Covers audit **M0**
(read-only export), **M1 step 2** and **M2 steps 1–3**.

Nothing here ever writes to production, and nothing here ever runs by itself.

## The four scripts

| Script | Reads | Writes | Credential |
|---|---|---|---|
| `extract-v8c4.mjs` | the approved V8C4 `index.html` | `out/seed-catalogue.json` | none |
| `export-production.mjs` | production `products`, `teamSettings/categories`, `teamSettings/productPins` | `out/m0-export.json` | production, **viewer only** |
| `import-staging.mjs` | the seed, the export, the device backup | staging, after backing it up | staging |
| `verify-staging.mjs` | staging | nothing | staging |

`export-production.mjs` contains no `set`, `update`, `delete`, `add`, `commit`
or `batch` call. The account it runs under cannot write either, which is the
protection that matters.

## Two dedicated service accounts

Create both for this task, use them for nothing else, and delete them
afterwards. Keep both key files **outside** this repository — the scripts refuse
to start if they find one inside it.

| | Project | Role | Used by |
|---|---|---|---|
| Production | `smartie-quote-desk` | `roles/datastore.viewer` — **not** Editor, Owner or Firebase Admin | `export-production.mjs` |
| Staging | `smartie-quote-desk-staging` | `roles/datastore.user` | `import-staging.mjs`, `verify-staging.mjs` |

Each script reads only the `project_id` field of the credential, asserts it is
the project it expects, and stops before its first read if it is not. No other
field of a key file is ever read, printed or logged.

## Before anything is generated

Every script checks, with `git check-ignore`, that each file it is about to
create is ignored, and refuses to start if any is not. `out/` holds the seed,
the export, the staging backups and the reports; all of it is gitignored and
none of it is ever committed.

## Order of operations

```bash
npm install                                    # firebase-admin, once

node extract-v8c4.mjs --index <V8C4 index.html>

GOOGLE_APPLICATION_CREDENTIALS=<production viewer key> \
  node export-production.mjs --project smartie-quote-desk

GOOGLE_APPLICATION_CREDENTIALS=<staging key> \
  node import-staging.mjs --seed out/seed-catalogue.json \
    --export out/m0-export.json --device out/device-backup.json

#   ... read the report, then:
GOOGLE_APPLICATION_CREDENTIALS=<staging key> \
  node import-staging.mjs --seed out/seed-catalogue.json \
    --export out/m0-export.json --device out/device-backup.json --apply

GOOGLE_APPLICATION_CREDENTIALS=<staging key> \
  node verify-staging.mjs --seed out/seed-catalogue.json --export out/m0-export.json
```

`--apply` is required to write. A staging project that already holds products
also needs `--accept-existing`, so a populated project cannot be overwritten on
a first attempt.

## The Owner device backup is required

A price someone edited in the PWA on a device that never pushed to Firestore
exists **only** on that device. Without `--device`, the import cannot see those
prices, and the twenty-model comparison against the PWA may differ for reasons
that are not the import's fault.

So: `--device` is required for a run that claims price parity. The importer says
so plainly when it is missing, the report records `deviceBackup: NOT SUPPLIED`,
and in that case **T-P1 price parity must not be reported as closed** — only
count parity and seed prices.

Device overrides carry no timestamp, so the audit puts them on a review list
rather than applying them silently. The import lists every one; re-run with
`--apply-device-overrides` to take them once you have read the list.

## Tests

```bash
npm test
```

`lib/plan.mjs` decides what the import would write — the merge, the pins, the
shelves and the migration report — with no Firebase in it, and `test/` drives
that path end to end. It exists because a dry run once died on
`Cannot access 'countShelves' before initialization`: the helpers the report
built itself from were `const` arrows below the top-level dispatch, still in the
temporal dead zone. `node --check` parses without running, so it saw nothing.
CI runs both.

## Rollback

```bash
node import-staging.mjs --restore out/staging-backup-<stamp>.json            # dry run
node import-staging.mjs --restore out/staging-backup-<stamp>.json --apply
```

Restoring deletes every product document the import created, writes the
previous ones back with `set()` **without merge** so added fields are removed,
and restores `teamSettings/categories` and `teamSettings/productPins` to their
prior content — **deleting** them if the backup records they did not exist.
Restoring an empty staging project therefore leaves no product documents and no
categories or pins document at all. Afterwards the script re-reads staging and
fails loudly if it does not match the backup.

Rehearse the restore on the empty project before the real import, so the path
is known to work rather than assumed.

## What T-P4 can and cannot prove

The production PWA reads the production project; the staging APK reads staging.
A pin reordered in staging **cannot** appear in the production PWA, and nothing
here will write to production to make it.

- `verify-staging.mjs` checks the imported order equals the exported production
  order, ≤ 15, every key resolving to a product. Compare it against the
  production PWA by looking, not by changing anything there.
- `verify-staging.mjs --pins`, after reordering in the staging APK, prints what
  staging Firestore holds so it can be compared with the app, and again after a
  force-stop and reopen.
- The audit's "order identical in PWA" *after a reorder* stays **blocked** until
  a staging PWA exists. It is recorded as blocked rather than claimed.
