# N2 Products — verification record

The staging catalogue import has been run. This is the evidence that closes
the items `docs/N2-delivery.md` recorded as blocked, and the record of what is
still blocked and why.

Run date: 2026-09-17. Run by the Owner, against
`smartie-quote-desk-staging`, following `tools/catalogue-import/README.md`.
Nothing in this session touched either Firebase project: this container holds
no credential for either, by design. The one part that needs none —
`test/plan.test.mjs` in `tools/catalogue-import` — was re-run here and passed 22 of 22.

## What the run produced

| Evidence | Produced by | Result |
|---|---|---|
| 403 products and 12 categories in staging | `import-staging.mjs --apply` | Imported |
| Every product matches the book and the production export, field by field | `verify-staging.mjs` | Passed |
| Pinned order in staging equals the exported production order | `verify-staging.mjs` | Matched |
| A second import writes nothing | `import-staging.mjs` re-run | `writes.total: 0` |
| The import plan behaves as specified | `test/plan.test.mjs` | 22 / 22 passed |
| Search, shelves, the load filter, prices, the stepper and the quote bar | Staging APK, by hand | Passed |
| Pin, reorder, and both surviving a force stop and reopen | Staging APK, by hand | Passed |
| Production data | — | Never modified |

The 12 categories are the PWA's twelve defaults that `ProductCategories`
merges (`index.html:7346-7359`); the 403 products are the full count the §12
exit criterion names.

## What this closes

- **T-P1 — count and price parity.** Closed. `verify-staging.mjs` compares
  every one of the 403 products against the seed book and, where production
  held a value, against the M0 export — `group`, `seedModel`, `model`, `name`,
  `unit`, `spec`, `gst`, `categoryId`, `kg`, all three tiers and `active`. That
  is a field-by-field comparison of all 403 against the data the production PWA
  itself reads, which subsumes the audit's twenty-model × three-tier spot
  check rather than merely sampling it. Count parity, distinct-key parity,
  `schemaVersion: 2` on every document, the canonical `group__seedModel`
  document id on every document, and the 13 ids needing a character replaced
  all passed in the same run. What the price half of it rests on is set out
  under "What price parity rests on" below.
- **T-P4 — pin order, the half that two projects allow.** Closed. Staging's
  pinned order equals the exported production order, is within the cap of 15,
  and every key resolves to a product. Reorder and unpin in the staging APK
  behave, and the order survives a force stop and reopen. The refusal half —
  the sixteenth pin — was already covered by `ProductPinsTest` and the
  emulator suite.
- **§12 exit criterion, "403-item parity".** Met.
- **The import is idempotent in the sense that matters.** The second run
  reported `writes.total: 0`: it sent nothing, rather than sending 403
  documents and reporting no change. That is the regression
  `tools/catalogue-import/README.md` describes, and it stays closed by
  `test/plan.test.mjs`.

## What stays blocked

**The staging-PWA comparison.** The audit's "order identical in PWA" *after a
reorder* cannot be shown, because no staging PWA exists. The production PWA
reads the production project; the staging APK reads staging; a pin reordered in
staging cannot appear in the production PWA, and nothing here will write to
production to make it. It is recorded as blocked, not claimed. Closing it needs
a PWA build pointed at the staging project.

## What price parity rests on

Three things, and it is worth being exact about the third.

1. The **V8C4 seed** extracted from the approved `index.html`.
2. The **read-only production export**, which wins over the seed wherever
   production holds a value.
3. An explicit **Owner attestation that no price was edited on any PWA device**.

The import reports `deviceBackup: SUPPLIED` for this run, and that is accurate
in the sense the script means it, but it must not be read as more than it is:
the file passed to `--device` was **not an extracted device export**. It was an
attestation JSON, written after the Owner confirmed that no prices had been
edited on any PWA device, carrying an empty override map (`ov: {}`).

So the guard in `tools/catalogue-import/README.md` — that a run without a
device backup must not claim price parity — is satisfied by attestation rather
than by extraction. The override review list is empty because the Owner states
there is nothing in it, not because nothing was looked for. That is a sound
basis for closing T-P1 given who made the statement, and it is recorded here
plainly so nobody later mistakes it for a device dump.

**This does not reopen N2.** T-P1 stays closed. If a device-only price is ever
found, it is a single-product correction through the normal edit path, not a
re-import.

## Credentials

Both service accounts were created for this task and used for nothing else.

- Production: `roles/datastore.viewer`, used only by `export-production.mjs`,
  which contains no write call of any kind.
- Staging: `roles/datastore.user`.

Both keys have been **revoked**, and both local key JSON files **deleted**.
Neither was ever inside this repository — the scripts refuse to start if they
find one there.

## Production safety

Production was read once, by a viewer-only account, through a script with no
`set`, `update`, `delete`, `add`, `commit` or `batch` call in it. It was never
written. Rollback of the staging import remains
`import-staging.mjs --restore out/staging-backup-<stamp>.json --apply`.
