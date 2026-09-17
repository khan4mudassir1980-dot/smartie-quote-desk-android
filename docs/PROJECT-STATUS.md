# Project status

**The single current-status record. Read this before planning or changing
anything.** Last updated 2026-09-17.

## Where the work is

| | |
|---|---|
| **Active development branch** | `claude/trusting-hamilton-z12eer` |
| **Last CI-verified head** | `353eddb` — [run #41](https://github.com/khan4mudassir1980-dot/smartie-quote-desk-android/actions/runs/35194386675), fully green (unit tests, lint, Firestore rules emulator, APK build) |
| **Historical branch** | `claude/sweet-fermi-ejyrg2` — carries N0 through N2 and **must not receive new work** |
| **`main`** | The old native beta. Not the port. Do not branch new work from it. |

`claude/trusting-hamilton-z12eer` was based on `claude/sweet-fermi-ejyrg2`, so
it contains the whole N0–N2 history. Later commits may sit above the hash
above; it is the last head CI has verified, not necessarily the tip.

## Phase state

| Phase | State |
|---|---|
| N0 Foundation | **Complete** |
| N1 Auth & Team | **Complete** |
| N2 Products | **Complete and verified** |
| N3 Our Stock | **Planned and resolved against V8C4, not implemented** |
| N4–N8 | Not started |

- Staging holds **403 products and 12 categories**, imported and verified
  field by field. T-P1 and the §12 "403-item parity" exit criterion are closed.
- Still blocked, and recorded as blocked rather than claimed: the audit's
  "order identical in PWA" check **after** a reorder, which needs a PWA build
  pointed at the staging project. None exists.

## Current next action

**Get approval to implement N3.** The plan in `docs/N3-plan.md` is complete:
the V8C4 inspection has been run against the authoritative `index.html` and
**no open schema question remains**. Document identity, the movement and stock
shapes, and the reorder-level rule are all resolved and recorded there, and the
transaction contract is specified.

Nothing of N3 — the repository, the ViewModel or the screen — is to be written
until that approval is given.

## Decisions that bind future work

**Stock writing is online-only.** A Firestore transaction that re-reads the
stored quantity and applies the delta to it. No offline mutation queue, no
optimistic quantity change, and a rejected transaction is never counted as
written. Cached stock stays readable offline; the controls say "Internet
required to change stock". Full rationale in `docs/N3-plan.md`.

**Out and Low are computed from the stored quantity only**, never from a
pending delta.

**Two document-id schemes, never interchangeable.** A product document is
`group__model` with `/ . # $ [ ]` replaced; a **stock** document is the logical
key `group|model` with **only `/`** replaced; a movement document is the
movement's own `id`. `productDocId` must never address a stock document. Both
schemes are proven by fixtures — the product one shared with the importer's own
test, and all thirteen real affected catalogue models are in it.

**Movements carry a signed `delta` and no `qty` field**, `at` in epoch
milliseconds and `serverAt` as the server timestamp.

**The V8C4 stock document has no `name` field**, so a Worker sees the model
rather than the catalogue name. Adding one would be a deliberate additive
divergence and is the Owner's call; it is not planned.

## Data and credentials

- **Production Firebase must never be written.** It has been read exactly once,
  through a viewer-only account, by a script containing no write call.
- **Staging (`smartie-quote-desk-staging`) is the only write target.**
- **No service-account credentials are available.** Both temporary keys —
  production viewer and staging — were **revoked**, and their local JSON files
  **deleted**. A session must not assume any credential exists; if one is
  needed, ask the Owner. Key files must never enter this repository; the
  scripts refuse to start if they find one.
- The approved V8C4 `index.html` lives only on the Owner's machine and is not
  committed.

## Where the detail lives

| Document | What it holds |
|---|---|
| `docs/N0-N1-delivery.md` | N0 and N1 scope, human actions (all done), acceptance checklist |
| `docs/N2-delivery.md` | N2 scope, what is deliberately not in it, acceptance |
| `docs/N2-verification.md` | The import evidence, what price parity rests on, what stays blocked |
| `docs/N3-plan.md` | The N3 plan: resolved V8C4 facts, the transaction contract, decisions and test plan |
| `tools/catalogue-import/README.md` | How the import runs, its guards, and rollback |
| `firestore/firestore.rules` | The v9 rules — staging only; production keeps V8C4 |

## Maintaining this file

1. **Update it only after work is verified and committed** — never in advance
   of it, and never to describe an intention. A hash here means CI has been
   green on it; if CI has not run, say so rather than implying it has.
2. **Keep "Current next action" to exactly one step.** If two things are
   outstanding, name the one that must happen first and put the other in the
   phase or plan document where it belongs.
3. **A session handed a different designated branch bases it on the active
   verified head first** (`git fetch origin <active-branch>` then
   `git checkout -B <designated-branch> origin/<active-branch>`), then records
   the new branch here once its first commit is pushed. **Never force-push and
   never discard commits** to make a branch fit; if that would lose history,
   stop and ask.
4. If this file and the repository disagree, the repository is right. Correct
   the file in the same commit that discovers the difference.
