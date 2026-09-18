# Project status

**The single current-status record. Read this before planning or changing
anything.** Last updated 2026-09-18.

## Where the work is

| | |
|---|---|
| **Active development branch** | `claude/trusting-hamilton-z12eer` |
| **Last CI-verified head** | `d11b5da` — [run #55](https://github.com/khan4mudassir1980-dot/smartie-quote-desk-android/actions/runs/35224973508), fully green (unit tests, lint, Firestore rules emulator, APK build) |
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
| N3 Our Stock | **Open.** Built and green, but **the first staging manual pass found blocking UI defects**. Those are fixed; N3 stays open until a corrected APK passes a second manual pass |
| N4–N8 | Not started |

- Staging holds **403 products and 12 categories**, imported and verified
  field by field. T-P1 and the §12 "403-item parity" exit criterion are closed.
- Still blocked, and recorded as blocked rather than claimed: the audit's
  "order identical in PWA" check **after** a reorder, which needs a PWA build
  pointed at the staging project. None exists.

### The first N3 staging manual pass

The APK installed and Our Stock opened without its development banner, so the
build and the wiring were sound. The pass then found **seven blocking UI
defects**, all of them in the interface rather than in the transaction or the
rules: Add stock showed the catalogue search, its results and the manual-item
fields at once with the buttons below a list; the search box behind the dialog
kept the focus, so the keyboard stayed up, and back or a tap outside threw
away what had been typed; the stock card's controls sat in a second card that
read as an unrelated thing, with nothing saying its middle figure was a
pending change and no History action; saved notes were never displayed, on
Our Stock or on Purchase; Purchase urgency was invisible without opening Edit;
the pinned shelf reordered through `↑`/`↓` buttons; and the header carried a
saffron/white/green line.

All seven are fixed, with regression coverage for each. Nothing in the settled
N3 domain, transaction or rules changed. **A second manual pass on a corrected
APK is what closes N3.**

## Current next action

**Deploy the v9 rules to staging, then run the T-S manual pass again on the
corrected APK.** Nothing has been deployed and no Firebase project has been
touched by any session. Until that pass runs, N3 is not delivered.

The steps, in order, are in `docs/N3-plan.md` under "Staging deployment". The
`stockMoves` composite index is not needed for History as it is built — the
read orders by `at` alone and filters by key in memory — so it deploys with
the first feature that queries a single key's movements directly.

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
