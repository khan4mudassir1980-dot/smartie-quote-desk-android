# N3 Our Stock — implementation plan

**Approved 2026-09-17. Batch A, A.1 and batch B are implemented. Nothing has
been deployed and no Firebase project has been touched.** It is
written against the roadmap's §12 line for N3 and the agreed behaviour below,
and it is deliberately specific about the writes, because N3 is the first phase
in which the native app writes a document the PWA also writes.

Two things settled at approval are folded in: stock writing is
**online-only** (see "Stock writing is online-only"), and the canonical
document-id mismatch was fixed **before** N3 rather than inside it.

**The V8C4 inspection has been run and every schema question is resolved** —
see "Resolved against the V8C4 source". Nothing below is a guess about PWA
behaviour. Implementation awaits approval of this resolved plan.

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
  archive flag, and a beta movement carrying an absolute `qty` instead of a
  signed `delta`. That tolerance is for **reading** old documents; N3 writes
  `delta` only, as V8C4 does.
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

### `domain/StockBoard.kt` — **built** (batch A), pure, unit-tested

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

### `domain/StockEntry.kt` — **built** (batch A), pure, unit-tested

What a new stock row is, for behaviour 4:

- `StockEntry.fromProduct(product)` → key `ProductRecord.stockKey`, plus
  `unit`, `categoryId`, `model` and `name` denormalised off the product.
- `StockEntry.manual(model, name, categoryId, unit)` → `manual = true`, key
  `manualstock|<slug>`, with the slug derived from the model or the name.
- `validate()` returning the PWA's own refusals: a blank item, a negative
  starting quantity, a negative reorder level, a key that already exists.

### `data/repository/StockWriteRepository.kt` — **built** (batch A)

The only new writer. Every quantity or reorder-level change is a single
Firestore transaction, so two phones counting the same shelf cannot overwrite
each other.

#### Document identity — two schemes, never interchangeable

Confirmed against the V8C4 source:

| | Rule | Example |
|---|---|---|
| `/stock/{id}` | the logical key `group\|model`, **only `/` replaced** | `gate\|SIE2.5MSMALL` |
| `/products/{id}` | `group__model`, `/ . # $ [ ]` replaced | `gate__SIE2_5MSMALL` |
| `/stockMoves/{id}` | the movement's own `id` field | `mv_m1a2b3xyzab` |

`Keys.stockDocId(key)` and `Keys.stockMoveDocId(id)` exist for this, and
`fixtures/stock_doc_ids.json` asserts on every row that the two schemes produce
**different** ids. **`productDocId` must never address a stock document** — the
dot survives in a stock id and does not in a product id, so one would silently
read and write the wrong row.

#### Product identity is immutable

Confirmed against the V8C4 source: the `m` that `skey(gid, m)` takes is the
**seed** catalogue model, while `L.m` is a display model an Administrator may
edit. Renaming what a product is called must never open a second stock row,
orphan its movement history, or move its document id.

`ProductRecord.stockKey` is the single place that decides it, in order:

1. the product's own stored `key` — already `group|seedModel`;
2. `group|seedModel`, when the document carries a seed model;
3. `group|model`, **only** for a legacy document that has neither.

`name` and `model` are display fields and may change freely on a later write
without touching the key. Every stock-to-product join uses `stockKey` — the
Products screen's stock chip included — so none of them can drift onto a
display model.

`linkedKey` stays **empty** for ordinary catalogue stock: its link to a product
is the key itself. The field is reserved for an explicit manual-to-catalogue
linking feature that does not exist yet, and no meaning has been established
for it against the V8C4 source, so none is invented here.

#### The transaction contract

Binding on every one of the three writes below:

1. **Generate the movement id once, before entering the transaction**, with
   `Keys.generateId("mv_")`. Capture `at` (epoch milliseconds) at the same
   moment.
2. **Reuse both if Firestore replays the transaction body.** Firestore may run
   the body several times under contention; generating inside it risks a second
   movement document for one action, and would drift `at` on every retry. `at`
   is the moment the person pressed Done, which is what the history should say.
3. **Read the stored `q` inside the transaction.** Never the figure the screen
   was showing, never a cached one.
4. **Derive `prev`, signed `delta` and `next` from that stored value** —
   `prev = stored.q`, `next = prev + delta` for an adjustment, `delta = next -
   prev` for an exact set. `delta` is always `next - prev`.
5. **Reject anything that would take stock below zero.** The transaction fails
   and says so — *"Only 3 left; someone else took some while you were
   counting"*. It does **not** clamp to zero: a clamp turns a wrong instruction
   into a plausible-looking success, and the rules refuse `q < 0` anyway.
6. **Write the stock document and the movement atomically**, in that one
   transaction. There is no path where a quantity moves without its audit row.

`t` and `at` are epoch milliseconds, because the rules require `t is number`
and a sentinel is not one. `serverAt` is `FieldValue.serverTimestamp()` and is
added alongside, on both documents, for PWA compatibility and for ordering.

#### The documents written

**Stock** (`/stock/{stockDocId}`), merge — exactly the V8C4 shape:

```
key, group, model, name,
q, min, off,
t, lastAction,
pinned, pinOrder,
manual, manualName, manualModel,
categoryId, unit, linkedKey, stockNote,
by, byUid, serverAt
```

`name` is the one approved addition to the V8C4 shape. `q` and `min` are
written as numbers on **every** write, even when unchanged: an imported PWA row
can hold them as strings and the rules require `q is number`, so re-asserting
them is what keeps a legacy row writable at all.

**Movement** (`/stockMoves/{id}`) — exactly the V8C4 shape, plus `serverAt`
which the transaction adds:

```
id, key, group, model, name,
action, prev, delta, next,
min, note, by, byUid, at, serverAt
```

**There is no `qty` field.** An earlier draft of this plan proposed writing
both `delta` and `qty`; that was a guess and it is withdrawn. The movement
carries the signed `delta` only. `toStockMove()` already prefers `delta` and
keeps its `qty` fallback for documents the native beta wrote, which is reading
tolerance, not a licence to write one.

#### The three writes

**1. `adjust(member, record, delta, note)`** — behaviours 2, 5, 11.
`action` and `lastAction` are `"in"` for a positive delta and `"out"` for a
negative one. The note is optional; blank stores
`Permissions.DEFAULT_STOCK_NOTE`.

**2. `setExact(member, record, quantity, reorderLevel, reason)`** — behaviour 3.

- Quantity changed → `action: "set"`.
- Quantity unchanged, reorder level changed → `action: "min"`, with
  `prev == next` and `delta: 0`.
- Quantity and reorder level unchanged, a note typed → the note and the audit
  metadata are saved with `lastAction: "note"` and **no movement is written**.
- **Nothing changed and no note → no write at all.** No stock document, no
  movement. A no-op must cost nothing and must not appear in the history.
  A blank note never clears a stored one.

Refused in the repository for anyone `Permissions.canSetExactQuantity` rejects,
so a doomed write never leaves the device; the rules refuse it too, and both
refusals are tested. A reason is required here
(`Permissions.requiresCorrectionReason`), unlike the note on a +/- commit.

**3. `create(member, entry, quantity, reorderLevel, note)`** — behaviour 4.
`action: "add"`, and a transaction that refuses a key that already exists
rather than merging onto someone else's row.

**Pinning is not one of these.** `togglePin` is a plain merge write of
`pinned`, `pinOrder`, `lastAction: "pin"`, `t`, `by`, `byUid` — and it writes
**no movement**, because the rules do not list `pin` among the permitted
`stockMoves` actions. A pin is not a stock change and must not appear in the
audit trail as one.

`stopTracking` (Administrator only) sets `off: true` with an `archive` move;
`observeStock()` already filters archived rows out.

### `ui/stock/StockViewModel.kt` — **built** (batch B)

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

### `ui/stock/StockScreen.kt` — **built** (batch B)

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

### Wiring — **built** (batch B)

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

**The V8C4 stock shape is written exactly, plus one approved addition.**
That addition is `name`, so a Worker reading `/stock` sees a descriptive name
without gaining `/products`. Nothing else is added, and the rules refuse any
write carrying a price. See "Two decisions taken on top of the findings".

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

## Resolved against the V8C4 source

The inspection was run on the authoritative `index.html` on 2026-09-17. **No
open V8C4 schema question remains.** What it settled:

**Q1 — stock document identity.** The stock key is `${group}|${model}`. The
`/stock` document id is that key with **only `/` replaced by `_`**. A
`/stockMoves` document id is the record's own `id`. Stock and product ids are
**different schemes**, and `productDocId` must never be used for a stock
document. Implemented as `Keys.stockDocId` / `Keys.stockMoveDocId`, with
`fixtures/stock_doc_ids.json` asserting on every row that the two schemes
disagree.

**Q2 — movement schema.** `id, key, group, model, name, action, prev, delta,
next, min, note, by, byUid, at`, with the transaction adding `serverAt`.
`delta` is signed and equals `next - prev`. **There is no `qty` field** — the
earlier plan's "write both" was a guess and is withdrawn. `at` stays epoch
milliseconds for PWA compatibility; `serverAt` is the Firestore server
timestamp. The stock document shape is recorded above, verbatim.

**Q3 — product document identity.** The replacement set is exactly
`/ . # $ [ ]`. The canonicalisation fix already committed in `7370677` is
correct, and the fixture now carries **all thirteen real affected models**
rather than one plus synthetic stand-ins.

**Q4 — reorder-level history.** A reorder-level-only change uses action
`min`, and creates a movement **when the minimum actually changes**. A no-op —
minimum unchanged, no note — **creates no write and no movement**.

### Two decisions taken on top of the findings

**1. `name` is written to `/stock` as an approved additive extension.**
Approved 2026-09-17. The V8C4 stock document has no `name` field — it carries
`model`, `manualName` and `manualModel`, and the descriptive name lives only in
`/products`, which a Worker may not read. Without an addition a Worker's list
would read `SIE1000` rather than `Sliding gate motor`.

So the native app writes `name`: the catalogue product's name for a catalogue
row, the manual item's name for a hand-typed one, and **nothing else from the
catalogue**. `StockEntry.fromProduct` copies the name, model, group, unit and
category and no price. The PWA ignores fields it does not know, so this is safe
in the same way the importer's additive `kg` is safe.

Because `/stock` is now the one Worker-readable place a price could leak to,
the rules refuse one outright: `noPriceData()` rejects any write carrying
`dealer`, `contractor`, `client` or `gst`, and `safeName()` requires `name` to
be a string. The emulator suite proves a Worker reads the name, cannot read
`/products`, and cannot write stock at all.

An earlier draft of this plan said the opposite — that no `name` would be
written. That was correct before this decision and is superseded by it.

**2. A note-only edit writes no movement.** Approved 2026-09-17. When neither
the quantity nor the reorder level changed but a note was typed, the stock
document's `stockNote` is saved with its audit metadata (`by`, `byUid`, `t`,
`serverAt`) and **no `/stockMoves` document is created** — nothing moved, so
nothing is logged.

It is **not** disguised as `min`, which would claim a minimum changed when none
did. It carries `lastAction: "note"`, a native additive value permitted for
Staff by the rules and deliberately absent from the `/stockMoves` action list,
so a movement with that action is refused. Quantity movements and genuine
minimum changes keep carrying their own optional note as before.

An earlier draft proposed a `min` movement with `prev == next` for this case;
that is superseded.

## Test plan

**Unit (JVM, no Android):** `StockBoardTest` — the status thresholds at 0, at
the reorder level, one above and one below, with no reorder level set; the
three filters and their counts; pinned ordering by `pinOrder`; search over a
row with no name. `StockEntryTest` — catalogue and manual keys, the slug, and
every refusal. `StockPinsTest` — appending, and that a pin writes no movement.
`PermissionsTest` — a row for `canSetReorderLevel`, plus the Worker row for
every stock capability.

**Document identity:** `KeysTest` drives both fixtures — 21 product cases
including all thirteen real affected models, and 12 stock cases each asserting
that `stockDocId` and `productDocId` produce **different** ids for the same
product. `tools/catalogue-import/test/doc-ids.test.mjs` drives the product
fixture from the importer side, so Kotlin cannot drift from the ids already in
staging.

**Write path:** `StockWriteTest` over the transaction body with a faked
document, asserting for each write:

- the movement id and `at` are generated **once, before** the transaction, and
  a replayed body reuses both — drive the fake to replay and assert one
  movement document with one id and one `at`;
- `prev` comes from the stored `q`, not from the caller's view of it;
- `delta == next - prev`, signed;
- a delta taking stock below zero is **rejected**, with nothing written —
  neither document;
- the exact V8C4 field map on both documents, with **no `qty` field** and no
  `name` field on the stock document;
- `t` and `at` are numbers; `serverAt` is a server timestamp;
- a blank note becomes `DEFAULT_STOCK_NOTE`;
- an edit with quantity and minimum unchanged and no note writes **nothing**;
- a minimum-only change writes `action: "min"` with `prev == next`,
  `delta: 0`.

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
action refused on `stockMoves`; a `stockMoves` update and delete refused; and a
movement whose document id differs from its `id` field refused.

**Manual, on staging:**

| ID | Test | Pass |
|---|---|---|
| T-S1 | Press `+` five times, then Done with no note | One audit row, `+5`, the neutral note |
| T-S2 | Take a row to exactly 0 | `Out of stock`, and only at 0 |
| T-S2b | Try to take a row below 0 | Refused and explained; nothing written, no movement |
| T-S2c | Edit, change nothing, no note, Save | No write, no movement, no history row |
| T-S2d | Edit the reorder level alone | One `min` movement, `prev == next` |
| T-S3 | Reorder level 5, quantity 5, then 4, then 6 | Low, Low, In stock |
| T-S4 | Tap Low, then Out, then Out again | The list filters, then filters, then clears |
| T-S5 | Two phones, same row, +3 and −1 without refreshing | Final quantity is correct; two audit rows. **PENDING — no second phone yet; nothing substitutes for it** |
| T-S6 | Edit as Staff | No exact-quantity field; the rules refuse a forced `set` |
| T-S7 | Edit as Administrator with a reason | `set` row in history with the reason |
| T-S8 | Sign in as a Worker | Rows visible by descriptive `name` where one has been written, and by model where the row is still the PWA's; no price anywhere; no stepper, Edit, pin, Add, Archive or History |
| T-S9 | Pending delta, force stop, reopen | The pending delta is still there, uncommitted |
| T-S10 | Add a manual item, then add it again | The second is refused, not merged |
| T-S11 | Pin three rows | They lead the list in the order pinned; no history rows |
| T-S12 | Aeroplane mode | The list, tags and filters still work from cache |
| T-S13 | Aeroplane mode with a pending delta | Done disabled, "Internet required to change stock"; the row still shows the stored number |
| T-S14 | Reconnect after T-S13, without touching Done | Nothing has been written; the delta is still pending |
| T-S15 | Then press Done | It commits, once, with the right `prev` and `next` |
| T-X4 | 360×640 and 412×915, font scale 1.0 and 1.3 | Nothing clipped; the stepper and Done reachable |

### The second pass: what the first one sent back

The first pass installed and opened Our Stock, then found seven blocking UI
defects. They are fixed, and these rows are what the second pass adds. Every
row above still has to be re-run on the corrected APK.

| ID | Test | Pass |
|---|---|---|
| T-S16 | Open Add stock | Two options only — From Products and Manual Item — and no fields belonging to either |
| T-S17 | From Products | A search, a bounded scrolling list of results each showing its model or code **and** its product name, and Add item nowhere until a product is chosen |
| T-S18 | Choose a product | It is shown as chosen, with Change product beside it, and the starting quantity, reorder level, note and Add item appear |
| T-S19 | Manual Item | Model or code, item name, unit, quantity, reorder level, note and Add item — and no catalogue results anywhere |
| T-S20 | Type in the Stock search, then open Add stock | The keyboard goes away with the focus; it does not come back over the dialog |
| T-S21 | Half-fill Add stock, press back once with the keyboard up | Only the keyboard closes; the dialog and every typed value are still there |
| T-S22 | Press back again, then tap outside the dialog | Neither closes it; Cancel does |
| T-S23 | Look at a tracked row | One card: the stored quantity captioned "in stock", the stepper captioned "Pending change", and Pin, Edit and History under the same rule |
| T-S24 | Save a note on a stock row, then restart the app and open it on a second device | The note is on the card in both places |
| T-S25 | Look at an open Purchase requirement with a note | The note is on the card, and its urgency is red, yellow or green without opening Edit. **MOVED TO N4** — Purchase is view-only until then, so no requirement can be created to check |
| T-S26 | Pin three products, long-press a handle and drag one | It lifts, and drops into the new position; nothing is added to the quotation and nothing is unpinned |
| T-S27 | Restart, and open Products on a second device | The dragged order is the order everywhere |
| T-S28 | Look at any screen header | One solid purple line, full width, with no orange or green anywhere |

## Staging deployment, and what is left

No session has accessed either Firebase project. Steps 3 and 4 have been done
by the Owner — the run #58 APK was installed on one physical phone and the
pass was run; `docs/N3-verification.md` is the record. Whether step 2 was done
first is **not stated in that evidence**, so it stays open below until someone
confirms it.

1. **The composite index is not needed yet.** `firestore.indexes.json`
   declares `stockMoves` by `key` ordered by `at`, for a per-item history
   query. History as built does not use it: it reads the recent movements
   ordered by `at` alone — a single-field index every project has — and picks
   one key's rows out in memory. So there is nothing to deploy here until a
   feature queries one key's movements directly, and no manual pass is
   blocked on it.
2. **Deploy the v9 rules to staging — unconfirmed.** They carry the
   `noPriceData()` and `safeName()` guards and the `note` action, and staging
   was last known to be running the version deployed before those existed. The
   second pass's writes were accepted by whatever rules staging is running,
   which does not say which. From `firestore/`, against **staging only**:
   `firebase deploy --only firestore:rules`. Production keeps the V8C4 rules;
   nothing here goes near it.
3. **Build and install the staging APK — done.** `app-staging-debug.apk` from
   run #58's `smartie-native-apks` artifact.
4. **Run the T-S manual pass above — done on one phone.** As Owner, as Staff
   and as a Worker. What it reached, and what it did not, is in
   `docs/N3-verification.md`.

## Manual acceptance still to run

Most of it has been run. `docs/N3-verification.md` maps every row to what the
second pass actually did: seventeen passed whole, seven in part, six were not
reached, one is pending a second phone and one moved to N4.

What is left on a device:

1. **T-S5, on two phones.** The concurrency check. `StockWriteTest` and the
   emulator suite cover the transaction and the rules in code; that coverage
   is real and it stands, but it is not this check and does not replace it.
2. **The second-device half of T-S24 and T-S27** — a note, and a pin order,
   seen from a second phone.
3. **T-S2c, T-S2d, T-S4, T-S9, T-S20 and T-X4** — the rows the second pass did
   not reach. Outstanding, not failed.

Until 1 is done, N3 is "single-device staging verification passed; physical
two-device concurrency verification pending", not closed.

## Out of scope for N3

Purchase requirements, including raising one from an out-of-stock row, are N4.
So are the **manual** Purchase checks: creating a requirement, and its urgency
colour and its note surviving a restart and appearing on another device. They
cannot be performed while Purchase is view-only, so T-S25 moves to N4 rather
than being recorded as passed. The automated rendering tests — `PurchaseRowTest`
and `AppearanceTest` — stand as automated evidence only.
Quotation creation and numbering are N5. Product and category administration is
N6. The calculators are N7. The production migration and cutover are N8. No
production read or write happens in N3, and the v9 rules stay staging-only.

## Rollback

N3 adds one repository and one screen; the read path is unchanged. Reverting
the commits restores the read-only screen. Stock documents written by N3 keep
the PWA's own shape, so nothing written needs undoing — and `stockMoves` is
append-only by rule, so the history of anything done during the phase survives
a revert.
