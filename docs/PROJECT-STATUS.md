# Project status

**The single current-status record. Read this before planning or changing
anything.** Last updated 2026-09-18.

## Where the work is

| | |
|---|---|
| **Active development branch** | `claude/trusting-hamilton-z12eer` |
| **Last CI-verified head** | `cd41791` — [run #60](https://github.com/khan4mudassir1980-dot/smartie-quote-desk-android/actions/runs/35346221054), fully green (unit tests, lint, Firestore rules emulator, APK build) |
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
| N3 Our Stock | **Single-device staging verification passed; physical two-device concurrency verification pending.** Not fully closed: T-S5 needs two phones |
| N3.1 Stock Photo | **Planned for Spark, not started.** `docs/N3.1-plan.md` revised: photos live in a separate Firestore document, not Cloud Storage. Awaiting approval. Nothing implemented, no dependency added, no Firebase project touched |
| N4–N8 | Not started |

- Staging holds **403 products and 12 categories**, imported and verified
  field by field. T-P1 and the §12 "403-item parity" exit criterion are closed.
- Still blocked, and recorded as blocked rather than claimed: the audit's
  "order identical in PWA" check **after** a reorder, which needs a PWA build
  pointed at the staging project. None exists.

### The two N3 staging manual passes

**The first pass** found seven blocking UI defects — all in the interface, none
in the transaction or the rules. All seven were fixed, with regression
coverage for each: 359 Kotlin tests across 35 classes, up from 310 across 26,
plus the unchanged 43 emulator tests and 26 importer tests.

**The second pass**, on the corrected run #58 APK, ran on **one physical
Android phone** and passed. Seventeen plan rows passed whole and seven in
part; the seven defects are confirmed fixed on the device, along with the
status thresholds, the refusal below zero, the Done transaction and what
History recorded, restart persistence, the offline and reconnection sequence,
the Worker/Staff/Owner separation, Archive, and the drag reorder.

**What that pass did not cover, and is not claimed:**

- **T-S5, the two-phone concurrency check** — not run, because a second
  Android phone was not available. `StockWriteTest` and the emulator suite
  cover the transaction and the rules in code; that is real coverage and it
  stands, but it is **not** the physical two-device check and does not
  substitute for it.
- **Six rows this pass did not reach** — T-S2c, T-S2d, T-S4, T-S9, T-S20 and
  T-X4. Outstanding, not failed.
- **The second-device half** of T-S24 and T-S27, for the same reason as T-S5.
- **Purchase (T-S25)** — Purchase is view-only until N4, so a real requirement,
  its urgency colour and its note cannot be exercised by hand. Those manual
  checks belong to N4. The automated rendering tests are recorded as
  **automated evidence only**.

**The rules and indexes were deployed to staging**, from an extracted
`d11b5da` tree, before the run #55 APK was installed — Owner-confirmed. Both
files are byte-identical between `d11b5da` and now, so what went up is what
this repository holds. That is **deployment history, not a fresh read of the
live ruleset**: nobody has read the deployed rules back, and no session holds
a credential to do it.

Row by row, with the evidence for each, in `docs/N3-verification.md`.

## Current next action

**Review the revised `docs/N3.1-plan.md` and approve or amend it.** The Blaze
question is closed — SMARTIE stays on Spark — so the plan has been rewritten
around a separate Firestore photo document. It is judged suitable, with four
limitations stated in it.

The one answer that could still invalidate it is **open decision 3: is reading
small printed text off a photo actually required?** At 80 KB and 800×800 it is
not reliable, and if that is the real requirement the feature needs rethinking
rather than retuning.

Nothing is to be built until the plan is approved. When it is, **batch A's
index exemption must be deployed to staging before any photo-writing build
reaches a device** — an unexempted 80 KB `bytes` field breaks the 7.5 KiB
index-entry limit and every write fails with `InvalidArgument`.

N3's outstanding device work is **tracked, not closed**, and does not become
this action: T-S5 on two phones, the six rows the second pass did not reach,
and the second-device halves of T-S24 and T-S27. They are listed with their
evidence in `docs/N3-verification.md`, and they come back as soon as a second
phone is available. N3.1 must not be the reason they slip.

## Decisions that bind future work

**SMARTIE stays on the Firebase Spark plan.** No billing account, no Blaze
upgrade, no Cloud Storage, no Cloud Functions, no paid service. Anything that
needs one of those is not an option to weigh — it is out. Cloud Storage for
Firebase requires Blaze even for a default bucket, so image and file storage
must be solved inside Firestore or not at all, and the daily Spark quotas are
shared by every feature. Nothing in this repository may promise free operation
without limits.

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

**`name` is written to `/stock` as an approved additive extension**, so a
Worker sees a descriptive name without gaining `/products`. It carries the
catalogue product name, or the manual item's name, and nothing else from the
catalogue. Because `/stock` is the one Worker-readable place a price could leak
to, the rules refuse any write carrying `dealer`, `contractor`, `client` or
`gst`, and require `name` to be a string.

**A note-only edit writes no movement.** The stock document's `stockNote` and
its audit metadata are saved with `lastAction: "note"`; nothing moved, so
nothing is logged, and it is never disguised as `min`. `note` is deliberately
not a `/stockMoves` action.

**Below zero is rejected, never clamped**, and the movement id and `at` are
generated once before the transaction and reused if Firestore replays it.

**A sheet holding typed values is not dismissible by accident.** Add stock
and Edit pass `DialogProperties(dismissOnBackPress = false,
dismissOnClickOutside = false)`; Cancel, or a save that succeeds, is the only
way out. Opening either one clears the focus behind it first, and choosing a
catalogue product clears it again, so the keyboard is never left over the
dialog's own fields. History holds nothing typed, so back and a tap outside
still close it.

**Compose dialog bodies are extracted as panels.** A Compose `Dialog` opens
its own window with its own recomposer, which the Robolectric test clock does
not drive, so any test that opens one spins until Espresso times out. Each
dialog body — fields and actions together — is an internal panel the tests
drive directly; the `AlertDialog` is a wrapper holding no logic. Keep it that
way for any dialog added later.

**Robolectric test classes stay small.** Its native-object registry is a fixed
16,777,216-entry array per JVM and a Compose composition consumes many entries,
so the suite runs `forkEvery(1)` and the stock screen tests are four small
classes rather than one large one.

**A pending `+`/`−` count is never a queue.** It persists on the device so a
long shelf count survives the app being killed, and it commits only when the
person presses Done — never on reconnect, never on restart. It is cleared only
when its own transaction succeeds; a failure keeps it for a retry, and a mixed
save says what actually happened rather than "Saved".

**The pinned shelf reorders by dragging, never by arrows.** A six-dot handle
on a pinned card, long-press to drag, one write when the finger lifts. The
drop arithmetic is `PinDrag.targetIndex` and the list change is
`ProductPins.reorderTo`, both pure and unit-tested; the handle carries named
"Move up" and "Move down" accessibility actions so the moves stay reachable
without the buttons coming back. The cap is still fifteen, and a reorder can
never breach it because it neither adds nor drops a key.

**Product identity for stock is immutable.** `ProductRecord.stockKey` decides
it — the stored `key`, else `group|seedModel`, else `group|model` for a legacy
document only. A display-model rename never changes a stock key, a stock
document id, or which movements belong to a row. Every stock-to-product join
uses it. `linkedKey` stays empty and is reserved for a future explicit
manual-to-catalogue link; do not give it a meaning.

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
| `docs/N3-verification.md` | The second manual pass, row by row: what passed, what is pending, what is N4's |
| `docs/N3.1-plan.md` | The N3.1 Stock Photo plan: flow, data model, rules, Android integration, batches, costs and open decisions |
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
