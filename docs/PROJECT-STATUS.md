# Project status

**The single current-status record. Read this before planning or changing
anything.** Last updated 2026-09-19.

## Where the work is

| | |
|---|---|
| **Active development branch** | `claude/trusting-hamilton-z12eer` |
| **Last CI-verified head** | `ea5a417` — [run #81](https://github.com/khan4mudassir1980-dot/smartie-quote-desk-android/actions/runs/35439461751), fully green (unit tests, lint, Firestore rules emulator, APK build) |
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
| N3 Our Stock | **Single-device staging verification passed; physical two-device concurrency verification pending.** A second phone has since been used, and it did **not** run T-S5 — nobody wrote the same row from both at once. Not fully closed |
| N3.1 Stock Photo | **Ten of fifteen photo rows have passed on physical phones.** T-P4, T-P6 and T-P11 closed in the second pass; the run #73 clipping defect is confirmed fixed on a device. **Five rows remain open** — T-P7 (**blocked** on the N6 Products & Categories screen), T-P12 (contract withdrawn and replaced), T-P13, T-P14, T-P15 — so N3.1 is **not closed**. Rules and the index exemption are deployed to staging (Owner-confirmed history, not a fresh read); **the `/stoppedStock` rules are new and not yet deployed** |
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
`d11b5da` tree, before the run #55 APK was installed — Owner-confirmed. That
is **deployment history, not a fresh read of the live ruleset**: nobody has
read the deployed rules back, and no session holds a credential to do it.

**The N3.1 rules and index exemption have since been deployed to staging as
well** — Owner-confirmed, both deployments reported successful. Same
distinction: it is deployment history, not a fresh read. No session has read
the live ruleset back, and none holds a credential to.

Row by row, with the evidence for each, in `docs/N3-verification.md`.

### What N3.1 built

CI-green at `3fd420f`, [run #72](https://github.com/khan4mudassir1980-dot/smartie-quote-desk-android/actions/runs/35433020684).

| Batch | Commit | What landed |
|---|---|---|
| A | `69d0fce` | `/stockPhotos` rules, the cross-document consistency guards, `'photo'` as a `/stock` `lastAction`, the `bytes` index exemption |
| B | `dcba479` | Pure `StockPhoto` (sizing, EXIF, the quality ladder, refusals), `Permissions`, `StockRecord.hasPhoto`/`photoRev`, the `StockWrite` photo planner |
| C | `67234ae` | The two-document transaction, `StockPhotoCache`, `StockPhotoRepository` |
| D | `58f7e38` | Capture, gallery pick, EXIF rotation, WebP encoding, the FileProvider entry, the source and preview sheets |
| — | `35d136c` | The preview sheet's confirm button keyed to the prepared bytes rather than the drawn bitmap |
| — | `214dd0b` | `StockPhotoWriteTest` asserts the photo write **merges**; the fake store had been discarding that flag |
| D.5 | `e617249` | The **persistent** photo cache: `StockPhotoDisk` naming, `DiskStockPhotoFiles`, a three-layer repository |
| E | `9a1b4b0` | Thumbnails on cards, the larger view, the pure `StockPhotoFlow`, the camera and picker wiring |
| E | `3fd420f` | `FirestoreFailures`: an exhausted daily quota is named, rather than reported as "Quota exceeded" |

**553 Kotlin test methods across 51 classes**, up from 359 across 37 before
N3.1; **60 Firestore emulator tests**, up from 43; the 26 importer tests
unchanged.

**What is deliberately absent.** Nothing has been deployed and no photo
manual row (T-P1…T-P15) has been run. The feature works in tests and has
never been exercised on a real phone against staging.

**Nothing was deployed and no Firebase project was accessed.** The rules and
the index exemption sit in the repository only.

**A numbering collision to keep straight:** N2's parity row `T-P1` and the
N3.1 photo rows `T-P1`…`T-P15` are different series. A bare `T-P` number in
this file means the photo series unless it is next to the 403-item parity
criterion.

### The run #73 layout defect, and the pass that followed it

The run #73 artifact was downloaded, renamed `SMARTIE-N3-PHOTO-RUN73.apk`,
installed over the staging app, and confirmed to be the staging build. On Our
Stock, **Pin, Edit and History appeared and no Photo control did** — no
button, no camera affordance, no thumbnail placeholder anywhere.

**It was not a stale APK.** Run #73 built `1deb44f`, which carries batch E
(`9a1b4b0`) in its ancestry, and the tree at that commit contains
`PHOTO_BUTTON`, `photoManage` and `StockPhotoThumbnail`. `app/src/` has only
`main` and `test` source sets, so no flavour could have replaced the screen.
The APK contained the feature.

**It was not a permission.** `canManageStockPhoto` *is* `canAdjustStock`, the
same predicate behind `canPinStock`. Pin being visible proves `photoManage`
was true.

**It was the layout.** The card's controls were a `Row`, which does not wrap.
Once the children exceed the available width Compose measures the remainder
against zero and the card's `clip` hides them, while they stay in the
semantics tree. Card content width is the screen less 24dp of list padding,
30dp of card padding and the 3dp accent bar:

| Screen | Card content | Controls need | Photo |
|---|---|---|---|
| 360dp | 303dp | 429dp | clipped |
| 393dp | 336dp | 433dp | clipped |
| 412dp | 355dp | 433dp | clipped |

Photo was the last child, so it was the one that disappeared — at every
width, not only narrow ones. The same arithmetic shows History needing
354–358dp, so **History was already being clipped before photos existed**, a
latent defect the new button only made visible.

**Why the tests passed.** `assertExists()` and `performClick()` read the
semantics tree, and a zero-width node is still in it. Every photo assertion
was about presence; none was about layout.

**Fixed across three commits**, because the first attempt cost something it
should not have:

- `51712c5` made the controls a `FlowRow` so they wrap instead of clipping.
  That made the button visible — and pushed every manager's card onto a
  second line of controls. CI caught the cost: three board tests failed
  because taller cards meant fewer rows fitted the viewport, which is a real
  regression on a screen built to be scanned down a shelf.
- `4b98185` moved the photo action into the card's **picture slot** instead:
  a bordered 56dp tile where the thumbnail would be. It is more obvious than
  a fourth button, competes with nothing for the controls line, and costs no
  height, because the name column beside it is already taller than 56dp. The
  controls stay a `FlowRow` — with the photo action gone they hold what they
  held before N3.1, so the card height is unchanged, and the latent History
  overflow at 360dp is fixed anyway.
- `02b3edf` drew the tile with `Icons.Filled.AddAPhoto` rather than a text
  "+", which was indistinguishable from the stepper's increment both to the
  tests and to a person on a board where "+" already means "one more".

**Confirmed fixed on a device.** The run #77 APK was installed on a physical
phone and the Photo tile appeared, unclipped. Seven photo rows passed in that
same pass; eight did not run. The detail is in `docs/N3.1-plan.md` and
`docs/N3-verification.md`.

**The coverage.** `StockPhotoWiringScreenTest` asserts layout at 360×640 —
displayed, at least 48dp wide — for Owner, Administrator and Staff, absent
for a Worker, and unclipped for Pin, Edit, History and the stepper below it.
The role-to-capability mapping moved into `StockCapabilities.forMember`, and
the screen test fixtures derive from it rather than restating it, so every
existing stock screen test now runs against the mapping the app uses.

**597 Kotlin test methods across 54 classes**; 60 emulator tests; 26
importer tests.

### The role-title pass, on run #81

**Owner-confirmed, one physical phone, staging build `ea5a417`.** A stored
`worker` reads **Staff** and a stored `staff` reads **Manager**; Owner and
Administrator are unchanged; no old **Worker** title appeared in the Team UI
that was checked; and the role selector showed Administrator, Manager and
Staff exactly once each, with no overlap or ambiguous option.

The permissions behind the titles were checked on the device and had not
moved: the account titled **Staff** still has no Products, no Photo, Replace,
Remove, quantity or Edit control, and can still view stock and saved photos;
the account titled **Manager** still has Products, quantity, Edit, Photo,
Replace and Remove. No unexpected gain or loss.

**The change is display-only in the cases physically tested.** Two things
that sentence does not cover, both recorded in `docs/N3-verification.md`: no
role was actually *changed* through the selector on the device, so "choosing
Manager writes `staff`" rests on `TeamRoleSelectorScreenTest` asserting the
`wireValue` each selection carries; and Owner and Administrator permissions
were not re-exercised, only their titles confirmed unchanged.

### The N3/N3.1 stabilization work, and what the second phone found

**Three photo rows closed on a device, one blocked, one contract replaced.**
T-P4 passed — a compressed photo kept a printed label and model readable, so
the 80 KiB ceiling serves this business rather than merely fitting under it.
T-P6 passed: a manual item's photo survived a restart. T-P11 passed **whole**,
end to end as a stored `staff` (displayed **Manager**), and the removed photo
did not come back after a restart. T-P7 is **blocked**, not failed: a
display-model rename needs the Products & Categories editing screen, which is
N6 and in development.

**A second phone was used, and it did not run T-P13 or T-S5.** The app worked
on it — a functional pass on a second device, recorded as one. But neither of
those rows is about owning two phones; both are about two devices writing the
**same** row at the same moment, and nobody performed a simultaneous photo
replacement or quantity change. Both stay pending.

**What the second phone did find** was the bottom navigation overlapping the
system navigation, traced and fixed — see *A window inset is padding, never
height* below.

**The archive finding, and the end of "Stop tracking".** Stopping tracking
wrote `off: true` and left the stock document in place. The board filters
`off` rows out, so the row vanished; `create` still read the document and
refused with "This item is already in stock"; and nothing in the app read or
unset `off`, so there was no route back. `SIE-EXTRECEIVER` is the reported
case. The Owner **withdrew** the archive/un-archive expectation, and T-P12's
acceptance contract is replaced by permanent removal, a read-only stopped-item
history, and a fresh re-add. The contract is in `docs/N3.1-plan.md`; none of
it has been run on a device yet.

| Commit | What landed |
|---|---|
| `0a5d3f5` | The removal data layer: `StockRemoval`, `/stoppedStock` rules and 23 emulator tests, the transaction, the legacy-conversion plan |
| `15a35d0` | The screen: Remove from stock on the Edit sheet, the stopped-item history at the foot of the board, Clear history, the legacy sweep, `StoppedStockRepository`. `StockWrite.stopTracking` deleted |
| `de040f5` | The bottom-navigation inset fix, the Products back-to-top control, and the regression coverage for both |

## Current next action

**Deploy the new `/stoppedStock` rules to staging, then run the five
outstanding photo rows and the replaced T-P12 contract on a device.**

The rules must go first: nothing in the removal path works against staging
until `/stoppedStock` is deployed, and the replacement APK is useless without
them. The exact command is in `docs/N3.1-plan.md` and is the Owner's to run —
no session holds a credential for either project.

Then, on a device:

| Row | What it checks |
|---|---|
| **T-P12 (replaced)** | Remove a photographed row permanently: the row, its photo document and its cached bytes gone; one read-only history entry left; the catalogue product untouched; the same product addable again, fresh; **no** quantity movement written. And `SIE-EXTRECEIVER` freed |
| **T-P14** | Scroll the whole board, leave, return, scroll again, against the Firebase console's usage tab. **The assumption every usage figure rests on**, measured rather than trusted |
| T-P15 | Remove a photographed row as an Administrator; the photo document must go with it |
| T-P13 | Two phones replacing the same row's photo **at the same moment** — not two phones, the same moment |
| T-P7 | **Blocked until N6.** A display-model rename needs the Products & Categories editing screen, which does not exist yet. Do not attempt it, and do not record it as failed |

Also on the device, because a second phone found it: the bottom navigation
clear of the system navigation in both three-button and gesture navigation,
and the Products back-to-top control.

**N3.1 is not closed** and must not be described as verified until these have
run. N3's outstanding device work is **tracked, not closed**, and does not
become this action: T-S5 on two phones, the six rows the second pass did not
reach, and the second-device halves of T-S24 and T-S27. They are listed with
their evidence in `docs/N3-verification.md`. N3.1 must not be the reason they
slip.

## Decisions that bind future work

**Spark limits come in three kinds and are not interchangeable.** Daily
operation quotas (50,000 reads, 20,000 writes, 20,000 deletes) reset daily;
monthly outbound transfer (10 GiB) resets monthly; stored capacity (1 GiB) is
a ceiling that **never resets** and is freed only by deleting data. No
document in this repository may promise that an exhausted quota recovers at a
particular time of day.

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

**A row of controls wraps; it never clips.** A Compose `Row` does not wrap —
children past the available width are measured at zero and clipped, and they
stay in the semantics tree while being invisible on the device. That is how
the Photo button shipped in run #73 with every test green. Card controls are
a `FlowRow`, and any control added to one must wrap rather than disappear.

**A job title is not a role.** Stored `staff` is displayed **Manager** and
stored `worker` is displayed **Staff**. The wire values, the enum, the
security rules, the PWA and every permission predicate keep the original
vocabulary, because that is what is in the documents and a rename would need
a migration to a live team. `RoleTitles` is the only place a role becomes
words — not `Member.roleLabel`, not a `when` block in a screen, which is how
three copies of the mapping once existed. When reading this codebase, take
`Role.STAFF` to mean Manager and `Role.WORKER` to mean Staff.

**Technical documents name stored roles.** The plan, the verification record
and the rules say `staff` and `worker` because that is what they are about.
Each carries the display-title table beside it rather than pretending the
stored roles changed.

**A stock card's height is a feature.** Our Stock is scanned down a shelf, so
anything that makes every card taller thins the board. A new per-row
affordance belongs in space the card already reserves — the picture slot, a
tag row — rather than as another control on the line below.

**A UI test that asserts a control exists has asserted nothing about whether
anyone can see it.** `assertExists()` and `performClick()` read the semantics
tree, which a zero-size node is still in. A control that must be reachable is
asserted with `assertIsDisplayed()` and a minimum width, at **360×640** — the
narrowest phone this app supports and the width at which a card's controls
overflow first.

**One mapping from a role to a set of controls.** `StockCapabilities.forMember`
is it; `StockViewModel.capabilities()` reaches it and the screen takes the
result. Test fixtures derive from that function rather than restating it, so a
screen test cannot pass against a second copy of the rules.

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

**A photo is a separate document, and the two are written together.**
`/stockPhotos/{stockDocId}` holds the bytes; `/stock` holds only `hasPhoto` and
a monotonic `photoRev`. Image bytes never enter a stock document — the mobile
SDKs have no `select()`, so a photo on the row would be downloaded by every
device on every board read. Both documents move in **one** transaction and the
rules refuse a commit that leaves them disagreeing: metadata claiming a photo
that is not there, a photo the row does not claim, mismatched revisions, a
`photoRev` going backwards, or a stock row deleted out from under its photo. A
photo change writes `lastAction: "photo"` and **never** a `/stockMoves`
document; `q` and `min` are written back from the value read inside the
transaction, never from the screen.

**A cached photo is keyed by stock identity *and* revision, in memory and on
disk.** A matching `rev` is served without a read; a mismatched one is never
served; `hasPhoto: false` discards the bytes without spending a read. A
fetched document whose `rev` is *behind* the row is discarded too. That is
what keeps unchanged photos from being downloaded repeatedly, and it is the
assumption the whole usage calculation rests on.

**Firestore's own persistence is not a substitute for our cache, because it
is not free.** A `get()` its local cache answers is still a billed document
read. Persistence buys *availability* offline, not cost, and no document or
comment in this repository may treat the two as the same thing. Photo bytes
therefore live in an app-private, no-backup directory under
`noBackupFilesDir`, one file per row named by hashed document id and
revision, written through a `.part` rename so a killed process cannot leave
a torn file, checked on the way back out so a cleared or corrupted file is a
miss rather than a wrong picture, and held under 40 MiB by least-recently-used
eviction. A revision that cannot be named exactly is not cached on disk at
all, because rounding two revisions to one name is how a replaced photograph
would come back.

**Removing stock is permanent, and there is no archive.** "Stop tracking"
wrote `off: true` and left the document in place: the row vanished from the
board, `create` still found it and refused with "already exists", and nothing
in the app could read or unset the flag. A hidden document that blocks its own
replacement is worse than no document. Removal now deletes the stock row and
its photo document, and writes one immutable `/stoppedStock` record, all in
**one** transaction. **No `/stockMoves` entry is written** — nothing moved.
There is no Restore and no un-archive, by the Owner's decision; the catalogue
product is untouched, so the same identity is added again as a **fresh** row.
`StockWrite.stopTracking` is deleted rather than deprecated, so no new row can
be left in that state; `ACTION_ARCHIVE` survives as a read-only constant
because the PWA writes it and older rows carry it.

**A history entry outlives the promise that the item's data was deleted.** So
`/stoppedStock` carries the name, the model, Manual or Catalogue and the last
quantity, and nothing else. `at`, `serverAt` and `byUid` are internal —
ordering needs the timestamps, the rules need the uid — and **none of them is
rendered**: no date, no time, no "stopped by", no photo, no price, no note.
The rules refuse a create carrying any of them, and the domain asserts the
same list. The rules also refuse history for an item still on the board
(`!existsAfter(/stock/$(stockDoc))` in the same commit) and refuse any edit to
an entry, ever. The reverse direction — that deleting a row *must* write
history — is **not** enforceable, because a fresh event id is random and a
rule cannot name it; the transaction is what guarantees that half, and no
document here may claim the rules do.

**A legacy `off: true` row converts under a deterministic id.** The event id
for a conversion is derived from the stock document id, and the history
document is written only when it is not already there, so a retry after a
partial failure finishes the job instead of doubling the record. A fresh
removal uses a random id, because two genuine add-then-remove cycles for one
key are two distinct events. A row that is not marked is never touched, and a
failure leaves it exactly where it was.

**A window inset is padding, never height taken out of a bar.** Material's
`NavigationBar` applies `WindowInsets.navigationBars` *inside* its own height,
so pinning it to a fixed height lays the items out in `height − inset`. At
80dp that is about 56dp on a gesture phone, which looks right, and about 32dp
on a three-button phone, which is the overlap the second phone found — and the
`+ 24.dp` that had been added to the height was the device-specific fudge
hiding it. The bar declares no insets of its own; the Box around it takes the
horizontal and bottom **safe-drawing** insets, which covers three-button,
gesture and a landscape cutout alike. The compact PWA height is a **minimum**
and never a cap, because an icon, its indicator and a label need more than
56dp, and it rises with the effective font scale as the label does. Nothing is
measured per device, and `SmartieBottomBar` takes its insets as a parameter so
the three-button case is a test rather than a second phone.

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
