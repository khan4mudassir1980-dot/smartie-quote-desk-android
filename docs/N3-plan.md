# N3 Our Stock — implementation plan

**Direction approved 2026-09-17. Nothing in this plan is implemented.** It is
written against the roadmap's §12 line for N3 and the agreed behaviour below,
and it is deliberately specific about the writes, because N3 is the first phase
in which the native app writes a document the PWA also writes.

Two things settled at approval are already folded in: stock writing is
**online-only** (see "Stock writing is online-only"), and the canonical
document-id mismatch is fixed **before** N3 rather than inside it.

Phase N3 turns the read-only Our Stock screen into the working one. The screen
currently carries an `InDevelopmentBanner` promising exactly this: *"Adding and
subtracting stock returns with the Our Stock phase, with the pending +/- flow,
Done, an optional note and full history."*

## The agreed behaviour, and where each piece lands

| # | Agreed behaviour | Where | Proven by |
|---|---|---|---|
| 1 | Show current stock per product | `StockScreen` rows, from `observeStock()` | `StockScreenTest` |
| 2 | Add or reduce stock with plus/minus | `CompactStepper` on the row → pending delta → **Done** | `StockBoardTest`, `StockScreenTest` |
| 3 | Manual quantity editing only inside Edit | No typeable number on the row; `EditStockDialog` only | `StockScreenTest`, rules test |
| 4 | Select a catalogue product **or** enter a manual item | `AddStockSheet`, two modes | `StockEntryTest`, `StockScreenTest` |
| 5 | Optional note for every stock change | One note field on the +/- commit, on Edit, on Add | `StockWriteTest` |
| 6 | Out of stock only at quantity 0 | `StockRecord.isOut` — already `quantity <= 0.0` | `StockBoardTest` |
| 7 | Low stock at or below the reorder level | `StockRecord.isLow` — already `quantity <= reorderLevel` | `StockBoardTest` |
| 8 | Tapping Low/Out shows the affected products | `SummaryTile(onClick)` → `StockFilter` | `StockScreenTest` |
| 9 | Pin frequently used stock items | `pinned` / `pinOrder` on the stock document | `StockPinsTest`, rules test |
| 10 | Worker views stock, changes nothing | `Permissions.canAdjustStock` + rules | `PermissionsTest`, `StockScreenTest`, rules test |
| 11 | Safe multi-device updates with audit history | One Firestore transaction per change, `stockMoves` | `StockWriteTest`, emulator, manual T-S9 |

Rows 6, 7, 10 and most of 11 are already specified in code — see below. N3
mostly adds the write path and the screen, not a new model.

## What already exists, and is not to be rebuilt

- `StockRecord` and `StockMove` in `data/model/Records.kt`, with `isOut` and
  `isLow` already carrying rules 6 and 7. `isLow` is guarded by
  `reorderLevel > 0.0`, so an item with no reorder level is never both.
- `DocData.toStockRecord()` / `toStockMove()` in `mapping/CatalogueReaders.kt`,
  already tolerant of the PWA's string numbers, `0`/`1` booleans, the `off`
  archive flag, and a move that carries an absolute `qty` instead of a signed
  `delta`.
- `CatalogueReadRepository.observeStock()` and `observeRecentMovements(limit)`.
- The whole **stock block of `Permissions`**: `canViewStock`, `canAdjustStock`,
  `canSetExactQuantity`, `requiresCorrectionReason`, `canStopTrackingStock`,
  `canPinStock`, `canViewStockHistory`, plus `REQUIRES_STOCK_NOTE = false` and
  `DEFAULT_STOCK_NOTE`. N3 consumes these; it does not restate them.
- The **v9 rules for `/stock` and `/stockMoves`**, which already encode the
  role split, the `q >= 0` floor and the Staff/Administrator action split.
- `firestore.indexes.json` already carries the `stockMoves` composite index
  (`key` ASC, `at` DESC) that per-item history needs.
- `SummaryTile` already takes `selected` and `onClick`, so behaviour 8 needs no
  new component.
- `Connectivity.online`, which exists for "refusing actions that must not be
  attempted offline".

## What N3 adds

### `domain/StockBoard.kt` — new, pure, unit-tested

The arrangement rules, in the shape `Catalogue` uses for Products, so nothing
is decided in a composable:

- `StockFilter { ALL, LOW, OUT, PINNED }` and the counts the three tiles show.
- `StockBoard.build(stock, query, filter, pendingByKey)` returning a
  `StockView`: pinned first by `pinOrder` ascending, then the rest by display
  name; search over name, model, key and note.
- `displayName(record)` — `name`, else `model`, else the key's tail. One
  function, because an imported PWA stock row may carry no denormalised `name`
  and a Worker must still see something.
- `StockStatus.of(quantity, reorderLevel)` returning `OUT` / `LOW` / `IN`,
  so the row, the tile and the filter cannot drift apart.

`pinOrder` ascending means a new pin is **appended**, matching the convention
N2 settled for product pins (audit P4). `observeStock()` currently sorts
`pinned` then `name` and ignores `pinOrder`; N3 fixes that.

### `domain/StockEntry.kt` — new, pure, unit-tested

What a new stock row is, for behaviour 4:

- `StockEntry.fromProduct(product)` → key `group|seedModel`, `unit`,
  `categoryId`, `model`, and `name` denormalised off the product.
- `StockEntry.manual(model, name, categoryId, unit)` → `manual = true`, key
  `manualstock|<slug>`, with the slug derived from the model or the name.
- `validate()` returning the PWA's own refusals: a blank item, a negative
  starting quantity, a negative reorder level, a key that already exists.

### `data/repository/StockWriteRepository.kt` — new

The only new writer. Three operations, each a single Firestore transaction, so
two phones counting the same shelf cannot overwrite each other:

**1. `adjust(member, record, delta, note)`** — behaviours 2, 5, 11.

```
transaction:
  stored   = get(stock/<docId>)
  previous = stored.q                       // re-read, never the screen's value
  next     = max(0, previous + delta)
  set(stock/<docId>, merge) { key, q: next, min, t, by, byUid,
                              lastAction: delta > 0 ? "in" : "out",
                              stockNote, name, model, group, unit, categoryId }
  set(stockMoves/<mv_id>)    { id, key, action, prev: previous, next,
                               delta: next - previous,     // signed, PWA shape
                               qty: abs(next - previous),  // absolute, beta shape
                               min, note, at, by, byUid, group, model, name }
```

The delta is applied to the **re-read** quantity, not to the quantity the
screen was showing, which is what makes two devices safe. When the clamp at
zero actually bites — someone else took the last of it while this person was
counting — the move records the true `prev` and `next`, and the person is told
what happened rather than being shown a silent success.

`t` and `at` are `System.currentTimeMillis()`. They cannot be
`FieldValue.serverTimestamp()`: the rules require `t is number`, and a sentinel
is not a number, so a server timestamp would be rejected.

Both `delta` and `qty` are written. `toStockMove()` prefers `delta` and falls
back to `next - prev` and then `qty`, but the PWA is still live and reads these
documents, so N3 writes the shape the PWA expects as well as the canonical one.
**Confirm against the V8C4 source which of the two it reads before merging** —
see the open questions.

**2. `setExact(member, record, quantity, reorderLevel, reason)`** — behaviour 3.

Same transaction shape, `lastAction: "set"` when the quantity moved and
`"min"` when only the reorder level did. Refused in the repository for anyone
`Permissions.canSetExactQuantity` rejects, so a doomed write never leaves the
device — the rules refuse it too, and both refusals are tested. The reason is
required here (`Permissions.requiresCorrectionReason`), unlike the note on a
+/- commit, which stays optional.

**3. `create(member, entry, quantity, reorderLevel, note)`** — behaviour 4.

`lastAction: "add"`, a matching `add` move, and a transaction that refuses a
key that already exists rather than silently merging onto someone else's row.

**Pinning is not one of these.** `togglePin` is a plain merge write of
`pinned`, `pinOrder`, `lastAction: "pin"`, `t`, `by`, `byUid` — and it writes
**no movement**, because the rules do not list `pin` among the permitted
`stockMoves` actions. A pin is not a stock change and must not appear in the
audit trail as one.

`stopTracking` (Administrator only) sets `off: true` with an `archive` move;
`observeStock()` already filters archived rows out.

### `ui/stock/StockViewModel.kt` — new

Follows `ProductsViewModel`: the query, the filter, and the **pending deltas**
that have not been committed yet. Pending deltas are held per key and persisted
to `DevicePreferences` under a new `stock_pending` key, so a long shelf count
survives the app being killed — the same reasoning that puts the quote draft on
the device (audit C8).

A pending delta is **a number this person has typed in, nothing more**. It is
never a queued write: it does not commit itself when signal returns, it does
not commit itself on restart, and nothing else in the app may read it as though
it had been written. It is cleared only when its own transaction returns
success, and dropped when its row disappears. When a transaction is rejected
the pending delta stays exactly as it was, so the person sees what they still
have to commit rather than a count that quietly succeeded somewhere.

### `ui/stock/StockScreen.kt` — rewritten in place

The `InDevelopmentBanner` goes. The screen becomes stateless over a
`StockActions` record, as `ProductsScreen` is, so Robolectric can drive it
without Firebase.

- Three tiles: **Tracked**, **Low**, **Out**. Low and Out are toggles that set
  the filter, and the selected one is visibly selected — behaviour 8.
- Search field.
- Rows: name, model, the status tag, `Reorder at n`, the note, and the quantity
  with its unit. **The quantity shown is the stored quantity**, always. A
  pending delta appears beside it as its own figure — `12 each · +5 pending,
  17 after Done` — and never replaces the live number, because until Done
  returns, 12 is what the shelf and the other phones say.
- **The Out and Low tags are computed from the stored quantity only.** A
  pending −5 must not make a row read `Out of stock`: nothing has been taken
  yet. The beta got this wrong by deriving its status from
  `quantity + delta`.
- **No typeable quantity on the row** — behaviour 3. The stepper's `−` and `+`
  only.
- A **Done** button appears on a row with a pending delta, opening a small
  sheet with an optional note. Blank stores `Permissions.DEFAULT_STOCK_NOTE`
  rather than blocking the save.
- **Edit** — Owner and Administrator only — exact quantity, reorder level and a
  required reason. Staff get the same dialog **without** the quantity field,
  which is precisely what the rules allow them (`min`, not `set`).
- **Pin** on the row for anyone `canPinStock` allows (Owner, Administrator,
  Staff).
- **Add stock** — a sheet offering a catalogue product picker or a manual item,
  behaviour 4. The picker is hidden from a Worker, and the picker itself reads
  `data.products`, which is already empty for a Worker.
- **History** — a per-item sheet (`stockMoves` where `key == …`, newest first)
  and an all-stock list, both hidden from a Worker by
  `Permissions.canViewStockHistory`.
- A Worker sees the list, the tiles, the search and the tags, and **no
  control**: no stepper, no Done, no Edit, no pin, no Add, no history.

### Wiring

`AppContainer` gains `stockWriteRepository`. `SmartieApp` passes a
`StockViewModel` to `StockScreen` the way it already passes `ProductsViewModel`
to `ProductsScreen`.

## Role matrix for this screen

| | View | +/- | Note | Exact quantity | Reorder level | Add | Pin | Stop tracking | History |
|---|---|---|---|---|---|---|---|---|---|
| Owner / Administrator | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Staff | ✓ | ✓ | ✓ | — | ✓ | ✓ | ✓ | — | ✓ |
| Worker | ✓ | — | — | — | — | — | — | — | — |

This is `Permissions` and the v9 rules as they already stand, written out. The
one gap is the reorder-level column: the rules let Staff write `lastAction:
"min"`, but `Permissions` has no function saying so, so every screen would have
to re-derive it. N3 adds `Permissions.canSetReorderLevel(member) =
canAdjustStock(member)` with its row in `PermissionsTest`.

## Decisions taken, and why

**A transaction, not `FieldValue.increment`.** Increment would work offline and
is atomic, but it cannot clamp at zero and cannot know `prev` or `next`, so
every audit row would carry a guess. An audit trail that is sometimes wrong is
worse than one that sometimes has to wait. What follows from that is set out in
its own section below, because it is the rule the whole screen is built to.

**The pending delta is client-side and explicit.** Every `−` and `+` is not a
write. One Done is one transaction and one audit row, which is what makes the
history readable and what keeps a shelf count from costing forty writes.

**Names are denormalised onto the stock document on every write.** The rules
let a Worker read `/stock` but never `/products`, so a Worker's stock list can
only show a name if the name is on the stock row. Imported PWA rows may not
carry one; N3 writes `name`, `model` and `group` on every touch, which
backfills the rows in use, and `StockBoard.displayName` covers the rest.

**Pin state lives on the stock document, not in `teamSettings`.** Unlike
product pins, that is where the PWA's schema already puts it (`pinned`,
`pinOrder`), and the rules already permit it.

## Stock writing is online-only

Approved at review, and binding on the implementation. A Firestore transaction
needs a round trip, so a stock change cannot be made offline. Rather than work
around that, the screen states it.

- **The transaction re-reads the stored quantity and applies the delta to it.**
  Never to the figure the screen was showing, and never to a figure cached on
  the device.
- **No offline mutation queue.** Nothing is stored to be sent later, and
  nothing sends itself when signal returns. The person presses Done again.
- **No optimistic quantity change.** The number on the row is the stored
  number until a transaction returns success. A pending delta is shown as its
  own separate figure, and the Out and Low tags ignore it entirely.
- **No fake pending-write count for a rejected transaction.** A rejected or
  failed write leaves the pending delta untouched and says the write did not
  happen. Nothing anywhere counts it as written, not in the row, not in the
  tiles, not in history.
- **Cached stock stays readable offline.** The list, the quantities, the tags,
  the search and the filters all work from the Firestore cache, because reading
  a stale count is useful and changing one is not.
- **Add, Reduce, Done, Edit and Add stock say why they are unavailable.** The
  wording is *"Internet required to change stock"*, on the disabled control and
  in the message shown if one is somehow reached, alongside the existing
  connectivity banner.
- **The person retries once connectivity returns.** That is the whole recovery
  path; there is no other.

What this costs is honest: a shelf counted in a basement cannot be committed
there. What it buys is that every number in `stockMoves` is a number that was
true on the server at the moment it was written, which is the point of keeping
an audit trail at all.

## Open questions — to settle before implementing

1. **The stock document id for a key containing `/`.** Stock is keyed by the
   logical product key (`hwWheel|SIEBAL58H/V` is a real one), and a `/` cannot
   go in a document id — audit C6. `Keys.sanitiseDocId` handles it, but the
   native app and the PWA must agree on the *same* id or a second document
   appears. **Read the PWA's stock write path in the V8C4 `index.html` and
   pin the answer with a test before any write ships.**

2. **The thirteen models that need the broader canonicalisation.**
   `Keys.productDocId` has been corrected to the PWA's exact rule in its own
   commit, ahead of this phase, and a shared fixture proves Kotlin and
   `tools/catalogue-import/lib/keys.mjs` agree. The fixture carries one real
   affected model (`hwWheel|SIEBAL58H/V`) and synthetic cases for `.`, `#`,
   `$`, `[` and `]`. The other twelve real models should be added to it once
   the extraction lists them — the command below prints them.

3. **`delta` versus `qty` on a movement.** Confirm from the V8C4 source which
   field the PWA's history reads, so the native write is compatible while the
   PWA is still live. The plan writes both; that is a safe default, not a
   verified one.

4. **Does the PWA write a movement when a reorder level changes alone?** The
   rules permit the `min` action, which suggests yes. Worth confirming so the
   two histories match.

All four need the approved **V8C4 `index.html`**, which is deliberately not in
this repository. `tools/catalogue-import/inspect-v8c4.mjs` answers all four
read-only, from the file where it already lives on the Owner's machine — one
command, printed to the console, nothing written and nothing committed:

```powershell
node tools\catalogue-import\inspect-v8c4.mjs --index "C:\Users\dell\Documents\SMARTIE-Development\V8C4-source\index.html"
```

Implementation of the write path waits on its output. Nothing in it is guessed
at in the meantime.

## Test plan

**Unit (JVM, no Android):** `StockBoardTest` — the status thresholds at 0, at
the reorder level, one above and one below, with no reorder level set; the
three filters and their counts; pinned ordering by `pinOrder`; search over a
row with no name. `StockEntryTest` — catalogue and manual keys, the slug, and
every refusal. `StockPinsTest` — appending, and that a pin writes no movement.
`PermissionsTest` — a row for `canSetReorderLevel`, plus the Worker row for
every stock capability.

**Write path:** `StockWriteTest` over the transaction body with a faked
document, asserting the exact field map for each of the four writes: the
re-read delta, the clamp at zero recording true `prev`/`next`, a blank note
becoming `DEFAULT_STOCK_NOTE`, `t` and `at` being numbers, and both `delta` and
`qty` present on a move.

**Compose (Robolectric):** `StockScreenTest` — the Worker sees rows and tags
and no control at all; tapping Low filters and tapping again clears; the row
has no editable quantity field; Done appears only with a pending delta; Staff
see Edit without the quantity field; a pending delta shows beside the stored
quantity and never replaces it; a pending −5 on a quantity of 5 still reads
`In stock`; offline, the controls are disabled and say "Internet required to
change stock" while the list stays readable.

**Rules (emulator):** extend `firestore/tests/data.test.js` — Staff refused a
`set` move and allowed `in`, `out`, `min` and `add`; a Worker refused every
stock write and refused `stockMoves` reads; a negative `q` refused; a `pin`
action refused on `stockMoves`; a `stockMoves` update and delete refused.

**Manual, on staging:**

| ID | Test | Pass |
|---|---|---|
| T-S1 | Press `+` five times, then Done with no note | One audit row, `+5`, the neutral note |
| T-S2 | Take a row to exactly 0 | `Out of stock`, and only at 0 |
| T-S3 | Reorder level 5, quantity 5, then 4, then 6 | Low, Low, In stock |
| T-S4 | Tap Low, then Out, then Out again | The list filters, then filters, then clears |
| T-S5 | Two phones, same row, +3 and −1 without refreshing | Final quantity is correct; two audit rows |
| T-S6 | Edit as Staff | No exact-quantity field; the rules refuse a forced `set` |
| T-S7 | Edit as Administrator with a reason | `set` row in history with the reason |
| T-S8 | Sign in as a Worker | Rows and names visible; no stepper, Edit, pin, Add or History |
| T-S9 | Pending delta, force stop, reopen | The pending delta is still there, uncommitted |
| T-S10 | Add a manual item, then add it again | The second is refused, not merged |
| T-S11 | Pin three rows | They lead the list in the order pinned; no history rows |
| T-S12 | Aeroplane mode | The list, tags and filters still work from cache |
| T-S13 | Aeroplane mode with a pending delta | Done disabled, "Internet required to change stock"; the row still shows the stored number |
| T-S14 | Reconnect after T-S13, without touching Done | Nothing has been written; the delta is still pending |
| T-S15 | Then press Done | It commits, once, with the right `prev` and `next` |
| T-X4 | 360×640 and 412×915, font scale 1.0 and 1.3 | Nothing clipped; the stepper and Done reachable |

## Human actions

**Deploy the composite index.** Per-item history queries `stockMoves` by `key`
ordered by `at`. `firestore.indexes.json` already declares it, but it has not
been deployed: `firebase deploy --only firestore:indexes` against staging,
before T-S7. Without it the per-item history fails at runtime with a
create-index link.

## Out of scope for N3

Purchase requirements, including raising one from an out-of-stock row, are N4.
Quotation creation and numbering are N5. Product and category administration is
N6. The calculators are N7. The production migration and cutover are N8. No
production read or write happens in N3, and the v9 rules stay staging-only.

## Rollback

N3 adds one repository and one screen; the read path is unchanged. Reverting
the commits restores the read-only screen. Stock documents written by N3 keep
the PWA's own shape, so nothing written needs undoing — and `stockMoves` is
append-only by rule, so the history of anything done during the phase survives
a revert.
