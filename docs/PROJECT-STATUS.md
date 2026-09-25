# Project status

**The single current-status record. Read this before planning or changing
anything.** Last updated 2026-09-24.

## Where the work is

| | |
|---|---|
| **Active development branch** | `claude/trusting-hamilton-z12eer` |
| **Last CI-verified head** | `64e2abf` — [run #177](https://github.com/khan4mudassir1980-dot/smartie-quote-desk-android/actions/runs/36119346286), fully green (unit tests, lint, Firestore rules emulator, APK build). Later commits may sit above it. |
| **APK to install** | The `smartie-native-apks` artifact **from the run that verified the head you intend to install** — never from whichever run this table happens to name. A build contains the commit it ran on and nothing above it, so a head hash and an APK go out of step the moment anything lands. Each run's job summary reports its head and the signing certificate; the app must show **Staging**. |
| **Ruleset anchor** | `ead0a52` — the **last commit that changed `firestore.rules`** (`git log -1 --format=%h -- firestore/firestore.rules`). This moves only when a rule changes, which is why it is recorded separately from the head. |
| **Live on staging today** | Deployed from `a6c5839`, whose ruleset is identical to `88f343f`'s. It is the **N4.3-era** ruleset and it is **seven rules commits behind**: `ba2db27` (N5.0b), `f61eebc`, `74ff81e`, `677e751`, `a794d64` (N5.6, N5.6b, N5.6c), `ccc08c4`, `ead0a52` (N5.9a — the Manager's discount cap); count with `git log --oneline a6c5839..HEAD -- firestore/firestore.rules`. |
| **To deploy next** | The **latest CI-verified head**, not a hash copied into this file. Check it before deploying: `git diff --quiet <head> ead0a52 -- firestore/firestore.rules` — silence means that head carries the current ruleset. No index deploy: `firestore.indexes.json` is unchanged since `69d0fce`. |

> **Three fields, three meanings — they were one field until 2026-09-24 and it
> had gone wrong.** The table said `a849650` was "the commit to deploy the rules
> from"; line ~1327 said `2127d48`; the Owner's ledger said `a6c5839`. All three
> were about different questions, and two were stale.
>
> **`a849650` was the dangerous one.** `git merge-base` puts it *before*
> `677e751` and `a794d64`, so deploying from it would have shipped a ruleset
> missing N5.6b and N5.6c — among them "a closed catch-all", a narrowing that
> would have stayed open. It was correct when written, on the day the head and
> the last rules change were the same commit, and became wrong a few hours later
> when two more rules commits landed on top. That is the failure mode: a head
> hash used as a ruleset hash goes stale silently, because nothing about the
> head changing tells you the rules did not.
>
> So: **"last CI-verified head" answers what CI has proven. "Ruleset anchor"
> answers which rules text is current. "Live on staging" answers what is
> actually deployed. "To deploy next" is derived from the first two and is never
> written down as a hash.**

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
| N3.1 Stock Photo | **Ten of fifteen photo rows have passed on physical phones.** T-P4, T-P6 and T-P11 closed in the second pass; the run #73 clipping defect is confirmed fixed on a device. **Five rows remain open** — T-P7 (**blocked** on the N6 Products & Categories screen), T-P12 (**passed in part** on 20 September against its replacement contract), T-P13, T-P14, T-P15 — so N3.1 is **not closed**. All rules, including `/stoppedStock`, are deployed to staging (Owner-confirmed observation, not a fresh read) |
| N4 Purchase | **In progress.** The plan of record is `docs/N4-plan.md`. Batches 0 to 4 are done, and so are the four defect batches A, B, C and D. A staging phone pass has since confirmed **all four defect fixes on a device**, plus three partial-receipt behaviours **in part** — listed line by line under "The Batch C staging phone pass". **No role-specific row and no whole T-R row is passed yet**, and N3's **T-S25 stays pending**. **N4.2, N4.3 and N4.4 are all code complete and CI-verified**, and both are waiting on the same Owner-run staging rules deployment paired with the APK rollout — they were never deployed separately and must not be. Purchase History is built and open to every role, so what was Batch 5 is done; the tab badge is Batch 6 |
| N5 Quotation | **In progress.** The plan of record is `docs/N5-plan.md`. **N5.0 through N5.7 are complete and CI-verified.** **N5.8a and N5.8b are complete and CI-verified at `c60705d` ([run #160](https://github.com/khan4mudassir1980-dot/smartie-quote-desk-android/actions/runs/36027462955)).** The quotation builder now exists: it replaces the Products tab's catalogue when the quote bar is tapped, and carries the rate type, the customer with a picker and "Save this customer", catalogue, hand-typed and area-priced lines, installation, the discount with the Manager cap, transport and GST, with live totals in the printed page's order. **Nothing is written to `/quotations` and no number is allocated** — the single Firestore write in the whole of N5.8 is "Save this customer", through `PartyWrite.mergeInto` under rules that already allow it. N5.8 changed **no rule**: `git diff 8784f90..HEAD` touches nothing under `firestore/` or `tools/`. Quotations themselves are still read-only and the Quotation tab keeps its "Keep using the PWA to issue quotations" banner until the cutover batch. Next is N5.9, the finalise transaction that takes a number from the counter |
| N6 Products & Categories | Not started. The Products & Categories editing screen, which T-P7 is blocked on. **Also owed here: read the company GST from `teamSettings/company.defaultGst`.** V8C4's `stSave` writes it there and the native app is already permitted to read that document. N5.8a resolves a quotation's GST from the rate its catalogue lines agree on, which is an honest stopgap and not the final answer — a quotation whose lines disagree, or which has only hand-typed lines, has nothing to agree on and currently refuses to finalise until somebody sets the rate |
| N7 Calculators | Not started. Port the four V8C4 calculators — rolling shutter, high-speed door, garage door, glass door — whose output becomes ordinary quotation lines carrying the opening size in the line's spec text |
| N8 Migration & cutover | Not started. **The production migration and cutover.** `docs/N2-delivery.md:40` calls N8 "the catalogue migration"; that line is the stale one and `docs/N3-plan.md:585` is right. **Read the blocking warning about `import-staging.mjs` under "Decisions that bind future work" before planning any part of this** — the importer carries seed rates in every payload and would destroy live pricing if pointed at production |

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
| `f5a4961` | The four failures run #86 found, all in the source: a `semantics` node placed below a padding reports the padded *inside*, and a `clickable` placed below a size only takes pointers over what is below it |

**697 Kotlin test methods across 65 classes**, up from 597 across 52 before
this work; **86 Firestore emulator tests**, up from 60; the 26 importer tests
unchanged. Both counts are measured from the tree — test methods by `@Test`,
classes by the files that hold one.

**The staging APK for the next physical pass** is `smartie-native-apks` from
[run #87](https://github.com/khan4mudassir1980-dot/smartie-quote-desk-android/actions/runs/35458715257)
at `f5a4961`. It carries the removal path, the stopped-item history, the
bottom-navigation fix and the back-to-top control. The `/stoppedStock` rules
**have since been deployed** (20 September), and a removal has been performed
on a device against them.

### 20 September: the rules reached staging, and the first removal ran

**Owner-confirmed, and recorded as an observation rather than a read-back** — no session holds
a credential for either project, so nothing below was verified from this container.

**The deployment.** `firestore/firestore.rules` from `ed4c80d` was deployed with

```
firebase deploy --only firestore:rules --project smartie-quote-desk-staging
```

The Firebase CLI confirmed the target was `smartie-quote-desk-staging`, and the staging Rules
tab showed a new publication at about 1:47 AM. **Production was checked separately and its
latest publication remained 11 September 2026, 6:43 PM — production was not changed.** That
closes the deployment half of the previous next action.

**The APK.** The `smartie-native-apks` artifact from run #88 was installed and the app showed
the **Staging** label.

**What passed on the device**, in one sitting, on one phone:

| Step | Result |
|---|---|
| Add a temporary **manual** stock product | Passed |
| Add a stock **photo** to it | Passed |
| Change its **quantity** | Passed |
| **Permanently remove** it | Passed |
| The removed product appears in **Stopped History** | Passed |

**T-P12 is recorded as PASSED IN PART, not passed.** The run exercised the removal and the
history entry, which is the heart of the replacement contract. Four clauses of that contract
were not reported and are therefore not claimed: that the catalogue product survived untouched,
that the same product could be **added again fresh**, that **no `/stockMoves` entry** was
written by the removal, and that the item left the **Tracked / Low / Out** counts.

**Nothing else moved.** These stay `pending` — not passed, not skipped, and not failed:

- **N3:** T-S5 (two phones, one row), the second-device halves of T-S24 and T-S27, and the six
  rows the second pass never reached — T-S2c, T-S2d, T-S4, T-S9, T-S20, T-X4. T-S25 is N4's.
- **N3.1:** T-P13, T-P14, T-P15, and the four unreported T-P12 clauses above.
- **T-P7 remains BLOCKED** on the N6 Products & Categories screen, which does not exist.

**Neither N3 nor N3.1 is closed**, and neither may be described as verified.

### N4 batches 0 to 2, the data-shape work

Verified at `c681f13`,
[run #90](https://github.com/khan4mudassir1980-dot/smartie-quote-desk-android/actions/runs/35497587944),
all three jobs green.

| Commit | What |
|---|---|
| `541a4e8` | Batch 0: the 20 September record above, and `docs/N4-plan.md` as the plan of record |
| `dffe125` | Batch 1a: the green urgency reads **"Needed, but not now"**. Display only — `UrgencyV2.NORMAL.wireValue` is still `normal` |
| `71ce629` | Batch 1b: `PurchaseWrite`, the pure mutation planner. Six mutations and no more |
| `cd0fcb9` | Trap 5 corrected: `rev` is **not** opt-in once a row has one. The documentation was wrong and the emulator said so |
| `8ce113f` | `Permissions.canReopenPurchase` — Owner and Administrator only |
| `a5204df` | Batch 2: the `PurchaseStore` seam, `FirestorePurchaseStore` and `PurchaseWriteRepository` |
| `c681f13` | Batch 2: `firestore/tests/purchase.test.js`, 21 tests against the rules as deployed |

**742 Kotlin test methods across 67 classes**, up from 697 across 65 before N4; **107 Firestore
emulator tests**, up from 86; the 26 importer tests unchanged; still **0 instrumentation tests**.
Both counts measured from the tree, not carried forward.

**Nothing was deployed, and no rule or index changed.** `firestore/firestore.rules` and
`firestore/firestore.indexes.json` are byte-for-byte what was deployed to staging on
20 September; the emulator suite runs against them unmodified. Production was not read and not
written. Nothing was merged to `main` and no pull request exists.

**No N4 row may be called verified.** Purchase is still read-only behind its in-development
banner, so **T-S25 stays blocked** on the batch that makes a requirement creatable from a
screen.

### N4.4, the run #113 phone pass: two defects and seven changes

Verified at `505b8d9`,
[run #123](https://github.com/khan4mudassir1980-dot/smartie-quote-desk-android/actions/runs/35750625322),
all three jobs green. The plan of record is `docs/N4.4-plan.md`, and what a
phone still owes is `docs/PHONE-TEST-CHECKLIST.md`.

**Phone testing happens once now, at the end of a batch**, against one
staging APK and one staging rules deploy. CI green stays mandatory on every
push, which is what makes CI the only thing between a defect and that single
pass — and this batch is the argument for taking that seriously.

| Commit | What |
|---|---|
| `b5bb3ed` | `docs/N4.4-plan.md` and the new cumulative `docs/PHONE-TEST-CHECKLIST.md` |
| `116e164` | The clipping-aware test helper, and B1 reproduced before it was fixed |
| `a1d25ab` | B1: the accent bar is painted rather than laid out |
| `efe8090` | B2: one requirement, one document, however many times it is tried |
| `d0ec4b7`, `c3d3b91`, `950d118` | Three rounds of correcting the new tests, below |
| `162ce5b` | C1 and C7: History folded away, and Staff stops seeing other people's deliveries |
| `db8ee33` | Two assertions that still asked for the renamed flow |
| `97d9294` | C2 to C5: the card compacted, the quantity line emphasised, the note boxed |
| `505b8d9` | C6: a person's role beside their name, where the rules allow it |

**B1 was one cause with two faces, and the test came first.** `SmartieCard`
fixed its height with `height(IntrinsicSize.Min)` for one reason — so a 3dp
accent bar could `fillMaxHeight()` — and an intrinsic measurement asks a
`FlowRow` how tall it would be at a width it will not finally get. Where the
two disagree the card's height is wrong in one direction or the other: too
short and the rounded clip erases whatever wrapped past it, which is how
Remove and Close-short went missing on every card and for every role; too
tall and the surplus is the "large blank area under the buttons" the same
report described. The reproduction measured the second at **67px** before
anything was changed. The bar is painted with `drawBehind` now and the card
wraps its content, so neither face has a mechanism.

**Why the suite could not see it.** `assertIsDisplayed()` asks a node about
its own bounds and never asks whether an ancestor painted it, so a clipped
control passes. `PurchaseSmallPhoneScreenTest` compounded it: the comment
said "Four controls on one card" over a loop of three.
`assertPaintedInsideCard` compares what a node would occupy against what
survived its ancestors, and runs at 360dp against the purchase card, the
stock card — same `FlowRow` pattern, same latent defect — and the shared
`ListRow` and `SmartieCard` that the Products and Team cards are built from.

**B2 was an idempotency gap, not a double tap.** `create()` minted a document
id on every call, and the replay guarantee the repository documents covers
Firestore re-running one transaction body, not two calls. The Add sheet stays
open on a failure with the typing intact, and a write that failed *after* the
server committed it is indistinguishable from one that never landed — so the
retry the app invites wrote a twin. The id belongs to the open sheet now and
a retry lands on the same document. `PurchaseRecord.id` is the Firestore
document id rather than a stored `id` field, checked first as the Owner asked:
nothing references a purchase row by that field, no fixture carries one that
differs from its document id, and the only outward identifier on a
requirement is `key`, which points at a stock row.

**The count is still unexplained and is not being explained away.** All nine
cards sat above the closed heading, inside Open, and the header is
`active.size` over the same list `items(...)` iterates. It may have read 9 at
that resolution. It has a test rather than an argument now: the Open header's
number against the cards the board drew, including two documents that look
alike, which are two requirements and are counted twice. **The two existing
"yysh" rows are two real documents** — the fix stops new ones and cannot
merge these, so both still show and the Owner removes one by hand.

**Staff was being shown other people's deliveries on the main list.** The
board asked `PurchaseBoard.closed`, which knows the state of a requirement
and nothing about who raised it, while the History screen asked
`PurchaseHistory`. One of them had to be wrong. Both ask `PurchaseHistory`
now, so the Owner's standing decision holds wherever a received row appears.

**C6 shows a role on an Owner's and an Administrator's phone and nowhere
else**, and that is a rules fact rather than a preference: another person's
`/users` document is readable by `admin()` only, so a Manager or a Staff
account cannot look anyone up. The members listener is gated the way
`quotingOnly` already gates products and quotations, so those accounts never
attach one the server would refuse, and every line falls back to the name
alone — which is also what a PWA-written row gets.

**The `⋯` overflow was built and taken out again**, which is a deviation from
the approved plan and is recorded as one. A `DropdownMenu` renders in a
`Popup` a Robolectric test cannot dismiss, so every question about what a card
offers would have left a menu open behind it; and an overflow puts Remove
behind a tap, which is what the phone pass reported missing. The buttons are
compact instead — narrower padding, smaller face, **the same 44dp height and
48dp target** — and four share one row at 360dp where three did.

**1065 Kotlin test methods across 98 classes**, up from 1027 across 89 after
N4.3; **148 emulator tests** and 48 importer and tool tests unchanged, because
`firestore/` was not touched. Still **0 instrumentation tests**.

**Nothing in this batch changed a rule, an index, or a document.** No
deployment, production neither read nor written, the PWA not modified, no role
value moved and no account renamed. Nothing merged to `main`, no pull request,
nothing amended or force-pushed.

**Seven CI runs to get here, and five of them were mine.** Gradle cannot
resolve the Android plugins in this container, so a Kotlin compile is a push
away and two rounds went to compile errors. Three more went to tests that
asked for things the app never promised — a 48dp target from a button the
theme makes 44dp, a click action from a container whose children click, a
44dp stepper the theme deliberately shrinks to 40 at 360dp. Each was corrected
to what the app actually says rather than by loosening the check.

### N4.3, the phone pass changes: own rows first, History for everyone, and the creator finishes what they raised

Verified at `3f1c988`,
[run #112](https://github.com/khan4mudassir1980-dot/smartie-quote-desk-android/actions/runs/35720565293),
all three jobs green. The plan of record is `docs/N4.3-plan.md`.

| Commit | What |
|---|---|
| `0bd838c` | `docs/N4.3-plan.md` — the Owner's decisions, the deferred rules-level history item, the role matrix |
| `d9ae2a3` | The open list puts the viewer's own requirements first |
| `4aff19c` | The Add and Edit sheets keep the Note field and the buttons out from behind the keyboard |
| `c5c7f66` | The creator may record what arrives against their own requirement — app side |
| `88f343f` | The same permission in the rules, and the emulator tests that prove it |
| `73c0205` | Purchase History for every role, with removals folded away at the bottom |
| `c0fe76f` | A test helper call that did not compile, and the assertion it was hiding |
| `a0bf1f1` | The N4.2 acceptance rows reconciled with the creator's new powers |
| `3f1c988` | The form sheets use the window, and four tests that still pinned the old truth |

**Receiving writes no stock movement and changes no Our Stock quantity.** The
Owner asked this be established before anything was planned, and it was:
`PurchaseWriteRepository` is built with `FirestorePurchaseStore(firestore)`
alone (`AppContainer.kt:42`), which touches only the `purchase` collection;
`stocked` and `stockedQty` are read (`OperationsReaders.kt:34-35`) and written
nowhere. The rules agree — `stockWriter()` is `admin() || staff()`, so a
`worker` cannot write `/stock` or `/stockMoves` at all. Giving a Staff account
Receive therefore gives it nothing over stock.

**A requirement you raised is one you can finish.** N4.2 let the creator
correct or remove an untouched requirement; N4.3 adds Receive — partial and
full — and Close with N received. The post-receipt lock is unchanged and
deliberate: once something has arrived, Edit, Urgency and Remove go, because
the row is now a record of what arrived. Reopen stays Owner and Administrator
only. A Manager's powers over other people's requirements are untouched.

**The rules carry it, not the hidden buttons.** `prDelivery()` is one
definition shared by the displayed Manager's branch and the creator's, so the
two cannot drift apart, and every existing invariant still binds it —
`prIdentityPinned()` and `revOk()` sit above the disjunction,
`prRcvNotReduced()`, `prShortfallKeys()` and the shortfall's
`request.qty == resource.rcvQty` are reused verbatim. No creator branch can
reach a reopen: `prOpen()` is false on a closed row and `prShortfall()`
requires `received != true`.

**History is filtered in the app, by the Owner's decision, and the reason is
recorded so it is not re-proposed.** Firestore evaluates a list query against
its *constraints*, not document by document, so the moment a read rule
mentions `resource.data` an unconstrained listener is refused — and the
filtered query that would replace it (`where('del','==',false)`) silently
drops every legacy row carrying `del: 1` or no `del` at all, and would refuse
the PWA's own listener in the same project. Owner, Administrator and Manager
see everyone's received and removed rows; a Staff account sees only the rows
it raised, and a row the PWA wrote without recording an author belongs to
nobody and is in no Staff account's history. **This hides rows from a screen;
it does not stop a determined client reading them.**

**`observeRequirements()` stops dropping removed rows**, because History needs
them and a second query would double the tab's cost against a shared daily
quota to fetch rows the first one already holds. All three consumers were
audited: `AppDataViewModel` and `SmartieApp` pass the flow through, and
`PurchaseViewModel` filters through `PurchaseBoard`, whose own filter is
load-bearing again rather than redundant — `PurchaseViewModelTest` guards it.
There is no badge and no count to regress; `openRequirementCount` does not
exist.

**Removals record who and when.** `delBy` and `delAt` join the remove key
list, constrained in the rules — `delBy` a string of 80 characters or fewer,
`delAt` a number — and `deletedBy` is now pinned to the caller's uid, which it
was not before: the old rule let any permitted remover write any uid there.
There is deliberately **no `request.time` comparison**, because an offline
write carries the device clock and a server-time bound would refuse a removal
made in a basement and synced later. A legacy removal that stamped neither
still renders, and the screen invents neither a name nor a date for it.

**Five existing emulator tests changed, all by design and all reported.** Four
asserted the limited role could not deliver, against rows whose `byUid` *is*
that account. The fifth, in `data.test.js`, ended on a refusal of the worker's
own receipt. A sixth was rewritten although it did not fail: its refusals
would have survived on `prQtyKept()` rather than on the receipt gate — passing
for the wrong reason and no longer testing what it named.

**1027 Kotlin test methods across 89 classes**, up from 976
across 86 after N4.2; **148 emulator tests**, up from 137, of which 62 are the
purchase rules; 48 importer and tool tests unchanged. Still **0 instrumentation
tests**.

**The sheet fix was wrong the first time, and a test said so.** The compact
urgency chips freed room inside a box that was still capped at half the
window — `sheetBodyHeight()`, 320dp on a 640dp phone — so the Note field was
still below the fold and five tests failed on run #111. The cap is gone
from the purchase **form** sheets: the window is the only bound a form needs,
and the weighted scroll child gives way to the keyboard. The stock sheets
keep the cap, because their bodies are lists.

**Nine tests failed before this went green, and every one of them was real.**
Five were the layout defect above. Three asserted what N4.3 deliberately
changed — the built More entries, and the creator's controls before and after
a delivery. One asked for a node by a word the screen now uses twice, as both
the section heading and a row's status tag. None was made green by weakening
it.

**`firestore.indexes.json` is unchanged** and no new query was introduced, so
no index deploy is needed.

**The rules change cannot break the PWA.** It only widens who may write — one
new alternative inside the existing creator branch, plus two field names in
the remove list. No read rule changed, no query changed, no field became
newly required, and a receipt a Staff account records is an ordinary receipt.
The standing cutover restriction is unaffected: the PWA overwrites `rcvQty`
and carries no `rev`, so the two apps still must not both write Purchase
requirements in one project.

**⚠️ The rules are changed and NOT deployed.** Staging only, the Owner's to
run, and in the same sitting as the APK — the two halves are one feature.
Production rules are untouched, production was neither read nor written, the
PWA was not modified, no role value moved and no account changed. Nothing
merged to `main`, no pull request, nothing amended or force-pushed.

### N4.2, creator self-service

Verified at `924786c`,
[run #106](https://github.com/khan4mudassir1980-dot/smartie-quote-desk-android/actions/runs/35523545235),
all three jobs green on the first attempt. The plan of record is
`docs/N4.2-plan.md`.

| Commit | What |
|---|---|
| `25aa549` | The eight things the Batch C phone pass actually confirmed, and the N4.2 plan |
| `de678b8` | `PurchaseAccess` — the per-record matrix — and `PurchaseWrite.closeShortfall` |
| `a4fc509` | The repository asks the **stored** document who may change it |
| `4ac2b71` | Capabilities per card; the Close-short control and its confirmation |
| `924786c` | The rules, and the emulator suite that proves them |
| `2328932` | Two gaps the pre-deployment verification found, closed |

**What changed, in one paragraph.** The person who raised a requirement may
correct it — name, note, quantity, urgency — or take it off the list, for as
long as nothing has been delivered against it. That is new for a Staff
account, who could previously add a requirement and not even fix a quantity
they had just mistyped, and new for a Manager, who could not remove anything.
A delivery then closes the record: Staff is read-only for that requirement, a
Manager keeps receiving the outstanding quantity and may write off a
shortfall, and a correction becomes an Owner's or an Administrator's.

**Ownership is `member.uid == record.byUid` and nothing else.** Never a
display name, never an email. A row whose `byUid` is blank — which is most of
what the PWA wrote — belongs to nobody, because `"" == ""` would hand every
legacy requirement to whoever happened to be signed in.

**A reopened requirement is its creator's again**, by the Owner's decision:
reopen removes the receipt outright and means as good as new. No
`everReceived` marker was added; the rules can only see the stored document,
so it would have needed a schema change.

**`closeShortfall` is the seventh named operation** and takes no quantity —
the new required total is the stored `rcvQty`, read inside the transaction,
and the receipt fields are preserved. Without it the lock would have left a
Manager able to see a finished requirement and unable to clear it.

**Writing the rules tests found three real holes in the first draft**, each
now its own guard: a Manager could un-receive a requirement while leaving the
receipt in place (a reopen in all but name); could change `qty` to anything
while "receiving", because every update re-asserts it; and could mark a
requirement fully received while the receipt fell short of the total.

**Two existing tests asserted the old rules and were inverted, not deleted.**
A Manager's reopen is refused by the rules now — `docs/N4-plan.md` had
recorded that since Batch 2 as the one restriction the rules could not
express — and the limited role may correct the requirement it raised.

**976 Kotlin test methods across 86 classes**, up from 905 across 83 after
Batch C; **137 emulator tests**, up from 113; 48 importer and tool tests
unchanged. Still **0 instrumentation tests**.

**Two coverage gaps were found before the deployment and closed** (`2328932`,
[run #108](https://github.com/khan4mudassir1980-dot/smartie-quote-desk-android/actions/runs/35536237079),
green). A read-only check against the ten-point security list found all ten
enforced, but two places where the suite proved less than it appeared to.

The shortfall test ended at `assertSucceeds`, which says a write is permitted
and nothing about what it did — and a shortfall rewrites a stored quantity and
closes a requirement. It now reads the document back and asserts the whole
result: `qty` is the receipt, `received` and `status` are set, all four `rcv*`
fields survive untouched, `rev` advanced by one, and `byUid` and `t` are where
they were. Those assertions existed only on the Kotlin side, against the
payload rather than against Firestore.

And `prIdentityPinned()` binds the Administrator branch by sitting outside the
disjunction, which is exactly why nothing would notice if it were moved
inside a branch one day. An Administrator is now refused a `byUid`, `by` or
`t` rewrite by name, with the identical write **without** them accepted, so
the refusals mean the pin rather than something else in the rule.

Test-only: no rule, no source and no document changed in that commit.

**⚠️ The rules are changed and NOT deployed.** This is the first rules change
since 20 September, and it makes the rules **stricter for a Manager** as well
as looser for Staff. The deployment is the Owner's to run, staging only, and
it must happen in the same sitting as the APK rollout — a Manager on the
Batch C build would be offered Edit on a received requirement and be refused
by the server. Production rules are untouched, production was neither read
nor written, the PWA was not modified, no role value moved and no account
changed. Nothing merged to `main`, no pull request, nothing amended or
force-pushed.

### The Batch C staging phone pass

Run by the Owner on a physical Android phone, against the
`smartie-native-apks` artifact of
[run #104](https://github.com/khan4mudassir1980-dot/smartie-quote-desk-android/actions/runs/35519935449).
**Eight things were confirmed, and they are listed here exactly as reported:**

| Confirmed on the phone | What it closes |
|---|---|
| Purchase card information and buttons no longer overlap | **Batch A's defect, fixed on a device** |
| A new requirement appears without restarting the app | **Batch B's defect, fixed on a device** |
| Open requirements are ordered red, then yellow, then green | **Batch D, confirmed** |
| Newest first inside the same urgency | **Batch D, confirmed** |
| A partial receipt leaves the requirement Open | T-R14, **in part** |
| The received quantity accumulates across deliveries | T-R18, **in part** |
| The requirement closes when the cumulative received reaches the required quantity | T-R15, **in part** |
| The build shows **Staging** | The build-identity check |

**What this pass does not claim, and must not be read as claiming.** Only the
eight lines above were reported. Everything else stays outstanding:

- **No role-specific row is passed.** T-R3, T-R4, T-R5 and T-R6 name what a
  Staff account, a Manager and an Administrator are each offered, and no role
  separation was reported. They stay pending.
- **T-R14, T-R15 and T-R18 passed in part, not whole.** The card's exact
  wording (`10 required · 4 received · 6 remaining`), the closed card's
  `10 in`, and the partly received row keeping its place inside its urgency
  group were not separately reported.
- **T-R16 and T-R17 were not reported at all** — receiving more than is
  outstanding, and the two edit guards around the received total.
- **T-R1, T-R2, T-R11 and T-R13 were not reported**, so **N3's T-S25 stays
  pending**: the row is about the note and the urgency being legible on the
  card *without opening Edit*, and that is not one of the eight.
- **T-R7 to T-R10 and T-R12** wait on Batch 5, Batch 6 and a second phone, as
  they always did.

### N4 batch C, partial receipt

Verified at `8030933`,
[run #104](https://github.com/khan4mudassir1980-dot/smartie-quote-desk-android/actions/runs/35519935449),
all three jobs green on the first attempt.

| Commit | What |
|---|---|
| `d9bdc81` | The three V8C4 verdicts and the production cutover restriction they force |
| `7c0d928` | `rcvQty` is a cumulative total; the planner closes only when it reaches `qty`, and guards the edit path both ways |
| `ce57f14` | The repository hands back a `PurchaseReceipt`, so what the tab says is decided inside the transaction |
| `dc0a107` | The card reads `10 required · 4 received · 6 remaining`; the panel shows all three and asks for this delivery |
| `8030933` | Six emulator tests proving the deployed rules take a partial and a completing payload |

**The defect.** `markReceived` wrote `received: true` whatever quantity it
was handed, so the first delivery closed the requirement and whatever was
still outstanding left the shop floor's list.

**The contract, written out in `docs/N4-plan.md`.** `qty` stays the total
required and no delivery reduces it. `rcvQty` is the cumulative received
total; a missing field means none. A requirement keeps `status: "Needed"` and
`received: false` until that total reaches `qty`, and closes at that point and
not before. Receiving more than is outstanding is refused with the
outstanding figure in the sentence. Editing the required total below what has
arrived is refused; setting it **equal** to what has arrived finishes the
requirement in the same save. Reopen is unchanged — the four `rcv*` fields are
removed, which is what returns the cumulative total to zero.

**`isClosed` was deliberately not touched**, so a V8C4 row carrying
`received: true` with `rcvQty` below `qty` — which the PWA's overwrite makes
ordinary — stays closed. Arithmetic must never reopen what a person marked
finished.

**No rules change and no index change, and that was proved rather than
assumed.** The update rule constrains `id`, `qty`, `updated`, `rev` and `del`
and says nothing about `rcvQty`, `received` or `status`, so both payloads are
ordinary updates. `git diff 9eef41a..8030933 -- firestore/firestore.rules
firestore/firestore.indexes.json` is empty.

**905 Kotlin test methods across 83 classes**, up from 866 across 81 after
Batch B; **113 emulator tests**, up from 107; 48 importer and tool tests
unchanged. Still **0 instrumentation tests**.

**Nothing deployed, production neither read nor written, the PWA not
modified**, nothing merged to `main`, no pull request, nothing amended or
force-pushed.

### N4 batches A, B and D, the first three defects from the phone

Verified at `9eef41a`,
[run #103](https://github.com/khan4mudassir1980-dot/smartie-quote-desk-android/actions/runs/35518126583),
all three jobs green.

The Batch 4 manual pass found four real defects. They were audited read-only
before anything changed, and each has its own root cause and its own commit.

| Commit | What |
|---|---|
| `8e8a8d2` | **Batch D** — open requirements are read most urgent first, newest within a colour |
| `e86ade2` | A test imported `assertExists` as though it were an extension; it is a member |
| `3d3f483` | The V8C4 inspector gained the three `rcvQty` questions, read-only and redacted |
| `e93b835` | **Batch B** — a listener that dies comes back, and a new requirement shows at once |
| `7ee2278` | The retry tests stopped draining the virtual clock for ever |
| `9eef41a` | Two `Double`s compared with a delta, not the deprecated primitive overload |

**Batch A, the overlapping card, is in `8e8a8d2`'s parent work** —
`ListRow` put its information row and its footer in a `Box`, which stacks its
children, so every card's control row sat on top of the card's own text on a
real phone. They are in a `Column` now, and `PurchaseCardLayoutScreenTest`
compares bounds against bounds: `assertIsDisplayed()` does **not** detect
occlusion, which is why every test passed while the defect shipped.

**Batch B was two faults in one symptom.** A snapshot listener used to
*complete* on a benign refusal, and a `stateIn` whose upstream has completed is
never collected again — `SharingStarted` decides when to start a flow, not when
to restart one that finished. On top of that, a Firestore transaction is
applied on the server and is **not** latency-compensated, so a created
requirement does not reach the local cache at all until the round trip
finishes. An error is an error now, `retryingListener` survives it with a
capped doubling wait and reports every failure, and a just-created requirement
is held by document id until the snapshot carries it.

**866 Kotlin test methods across 81 classes**, up from 805 across 73 after
Batch 3; 107 emulator tests and 48 importer and tool tests — the importer
suite grew with `purchase-receipt.test.mjs`, which proves the inspector's
classifier against synthetic sources, including the two answers it must refuse
to give. Still **0 instrumentation tests**.

**Nothing deployed, no rules or index change, production neither read nor
written**, nothing merged to `main`, no pull request. The inspector is
read-only and the PWA was not modified.

### N4 batch 4, the tab

Verified at `9e05bd3`,
[run #97](https://github.com/khan4mudassir1980-dot/smartie-quote-desk-android/actions/runs/35511915950),
all three jobs green.

| Commit | What |
|---|---|
| `1831c07` | A `ListRow` footer slot, and how a purchase sheet may be closed |
| `d8e9619` | The Purchase tab writes; the filtering defect fixed on the screen it was on |
| `9e05bd3` | Offline, a control still says which control it is |

**830 Kotlin test methods across 78 classes**, up from 805 across 73 after
Batch 3; the 107 emulator tests and 26 importer tests unchanged — `firestore/`
was not touched; still **0 instrumentation tests**.

**The in-development banner is gone.** Everything on the tab goes through
`PurchaseViewModel` and the writer behind it: add, edit, urgency, receive,
reopen, remove, and nothing else. Active is newest first, sorted client-side.

**The soft-delete defect is closed.** Both lists come from `PurchaseBoard`;
the screen's `filterNot { isOpen }` is gone, so a removed requirement leaves
both sections instead of reappearing as history.

**A second defect, found by a test and fixed.** A disabled control used to
replace its own description with the reason it was disabled, so offline every
control on every card announced the same sentence and none could be told
apart by ear. The name comes first now and the reason follows it.

**Back closes a purchase sheet** rather than leaving the tab, on all six —
this is the one place the purchase sheets and the stock sheets deliberately
differ, and `PurchaseSheetRulesTest` pins it. A tap outside still cannot
discard what somebody has typed.

**T-S25 is unblocked, not passed.** Its automated half is green; the manual
half needs a phone and is recorded as pending in `docs/N3-verification.md`.

**No rules or index change, nothing deployed, production neither read nor
written**, nothing merged to `main`, no pull request.

### N4 batch 3, the panels and the view model

Verified at `fc371da`,
[run #94](https://github.com/khan4mudassir1980-dot/smartie-quote-desk-android/actions/runs/35510294933),
all three jobs green.

| Commit | What |
|---|---|
| `67beb3e` | `PurchaseBoard` — active and closed, newest first, sorted client-side |
| `a73d762` | `FirestoreFailures` tells contention apart from a refusal |
| `3cea2fe` | `PurchasePanels` — the six surfaces and the role-to-control mapping |
| `1b96f1d` | `PurchaseViewModel` over `PurchaseWriteRepository` |
| `fc371da` | Sheet height shared with the stock sheets; the two panel tests scroll |

**805 Kotlin test methods across 73 classes**, up from 742 across 67 after Batch 2; the 107
emulator tests and 26 importer tests unchanged — `firestore/` was not touched; still **0
instrumentation tests**.

**A defect found and fixed on the way.** `PurchaseRecord.isOpen` is `!deleted && !isClosed`,
so "not open" is not the same as "closed": a removed requirement that was never received is
neither. The tab's current split is `filter { isOpen }` / `filterNot { isOpen }`, which puts a
soft-deleted row into "Received and closed". `PurchaseBoard.closed()` filters on
`!deleted && isClosed` instead. Nothing can reach it yet — no screen is wired to a writer —
and `PurchaseScreen` is rebuilt in Batch 4, so it was not widened here.

**Reopen is enforced by the app alone**, and three places say so rather than implying
otherwise: `Permissions.canReopenPurchase`, `PurchaseCapabilities`, and
`PurchaseViewModel.open`, which refuses to open a panel this person may not act on.

**No rules or index change, nothing deployed, production neither read nor written**, nothing
merged to `main`, no pull request.

## N5 Quotation — the batches that are done

All of these are CI-verified, the last of them at `2127d48` ([run #133](https://github.com/khan4mudassir1980-dot/smartie-quote-desk-android/actions/runs/35834219582)).

| Commit | Batch | Green at |
|---|---|---|
| `ccb4a25` | **N5.0** — remove the beta quotation code nothing reaches | run #126 |
| `ba2db27` | **N5.0b** — an Administrator acts on Manager and Staff only | run #126 |
| `9d3ab3a` | **N5.1** — write down what the quotation rules already do, before building on them | run #126 |
| `15d6ce3` | **N5.2** — the area, installation and discount arithmetic, pure Kotlin | run #127 |
| `978a495` + `d6bf5d5` | **N5.3** — Parties, read side | run #130 |
| `4fe76dd` + `38d32a6` | **N5.4** — quotation history and detail, read-only | run #130 |
| `f2bcd3f` + `2127d48` | **N5.5** — Parties, write side | run #133 |
| `f61eebc` + `74ff81e` + `a849650` | **N5.6** — Settings, Owner-only: numbering and the discount cap | run #137 |

`7bc11dd` sits between N5.5 and its fix and is `docs/N5-plan.md` alone — no code, and
therefore red on CI only because it inherited `f2bcd3f`'s failures.

**Test counts at `a849650`: 1236 Kotlin tests across 111 classes, and 191 emulator
tests.** Superseded — see the N5.7 block for the counts at the branch head. A figure
here is the count at the commit it names and nothing else; quoting an older batch's
number as the current baseline is how a quietly deleted test hides.
N5.2 to N5.5 changed no rule text; **N5.6 is the second and last rules change N5 has
made so far**, after N5.0b.

**N5.0** deleted `util/QuotationPdf.kt` (188 lines) and `data/model/Models.kt` (51 lines),
neither of which had a single caller. Deleting the PDF writer also removed a trap for the area
pricing N5 builds: its private `formatNumber` used `Double.toInt()` — the truncation audit C12
recorded and that `Money` exists to replace — so a computed area of 76.125 sq ft would have
printed 76.13 on the PDF and 76.125 on the card. `AppDataViewModel.parties` was kept and
documented rather than deleted: it is uncollected today, but N5.3 replaces the Parties
placeholder and reads exactly that flow, and `WhileSubscribed(5_000)` means an uncollected
flow attaches no listener.

**N5.0b** is a rules change and the only one N5 has deployed so far. An Administrator may no
longer demote, disable or delete another Administrator, and `roleOptionsFor` offers
Administrator only to an Owner — the second half matters as much as the first, because
without it an Administrator could create the peer they are then not allowed to manage. Taken
now because there is **no Administrator account in staging or production**, so the narrowing
interrupts nobody; that window closes the moment one is created.

### The two N5.1 findings, and where each is answered

N5.1 changed no rule. It wrote fifteen emulator tests against the rules exactly as deployed,
and two of them found something. Both are carried forward rather than fixed in place:

1. **A contended issue is refused as `permission-denied`, and the SDK does not retry that.**
   A Firestore transaction retries itself when the *server* aborts it for contention. Here the
   security rule is doing the concurrency check — `next == resource.data.next + 1`, exactly —
   so a transaction whose read of `next` went stale is rejected by the rules before the
   server's own optimistic-concurrency check ever aborts it, and the SDK gives up. Measured
   over twenty contended pairs on the emulator: two separate client apps both got through half
   the time and lost one to `permission-denied` the other half; two concurrent transactions
   inside one app lost one **every** time. **N5.9's finalise must therefore retry the whole
   transaction itself, bounded, on `permission-denied` against the counter.** The approved plan
   assumed the SDK's own retry covered this; it does not.

2. **An Administrator can re-take a number that is already gone.** The counter's second update
   branch exists for configuration and asks only for `next >= resource.data.next`. An
   Administrator issuing from a stale read writes `next: 10` when the stored value is already
   10, `10 >= 10` holds, and the write is accepted — so two people hold the same quotation
   number and `lastIssued` ends up naming the loser. A Manager cannot do this, because a
   Manager never reaches that branch, which is why the contention tests use two Managers to
   prove the real property. It was pinned as a **characterisation test asserting
   `assertSucceeds`**, with the defect named in full beside it. **Closed in N5.6**, where that
   assertion flipped to `assertFails` — but *not* by the predicate the plan named. See
   "What N5.6 cost" below.

**Emulator tests: 165**, up from 148. The contended pair was run five times over to confirm it
is not flaky; it asserts the invariant that holds every time — the counter advances by exactly
the number of clients that got through, and no number is issued twice — rather than an outcome
that only holds about half the time. `helpers.js` gained a second Manager account so two
ordinary quoting accounts can contend without either reaching an admin-only branch and proving
something other than what the test claims.

### What N5.6 cost, and the rule that had to be corrected after it shipped

N5.6 made the counter's configuration the Owner's and added the Manager
discount cap. The plan's predicate for closing finding 2 — **a strictly
greater `next` unless the financial year changes** — shipped in `f61eebc`
and was **wrong twice over**, which `74ff81e` corrects.

1. **It made a prefix or padding correction impossible.** Requiring
   `next > stored` on *every* configuration write refused leaving `next`
   alone as firmly as moving it backwards, so renaming `SIE/QD` would have
   cost a real quotation number. Found by the Settings screen test, because
   that screen is the thing which does exactly that edit.
2. It needed a companion clause, but **not for the reason first recorded
   here.** An earlier version of this section claimed strictly-greater "did
   not close finding 2 on its own". **N5.6b's ablation proved that wrong.**

**What the ablation established.** Under the strictly-greater rule as first
shipped, the write `{next: 10, lastIssued, updated}` against a stored `next: 10`
was **refused**. It becomes allowed only once the `!touched(['next'])` escape
hatch is added — the hatch that exists so a prefix can be corrected without
burning a number. So:

- **Strictly greater closes N5.1's second finding**, on its own. Remove it
  alone and two tests fail: `an Owner rolls the financial year, and never
  rewinds inside one`, `a forward correction is allowed and rewinding is not`.
- **`!touched().hasAny(['lastIssued'])` closes the gap the escape hatch
  opens.** Remove it alone and two different tests fail: `a number that is
  already spent cannot be re-taken, by anybody`, `but configuration may never
  stamp lastIssued, whatever else it does`.

Neither ablation breaks nothing, so both guards are tested. The plan's other
proposed half — `!hasOnly(['next','lastIssued'])` — would have refused a
`next`-only correction and is deliberately not there.

**Two lessons worth keeping.** The screen was what proved the rule wrong: a
rules change reviewed only against its own emulator tests is reviewed against
the cases its author already thought of, and the first real caller finds the
case they did not. And an explanation of *which* guard does the work is worth
nothing until it is ablated — the first account written here was confident,
plausible and backwards.

A near-miss also worth recording: the first probe that "confirmed" the
breakage had actually failed on module resolution, not on the rules. It was
re-run from `firestore/tests/` against both the old and the new rule text
before anything was concluded from it.

### What N5.2 to N5.5 settled, and the two traps they cost

**N5.2** put every worked example in `docs/N5-plan.md` into `QuoteArea` and
`QuoteMath` before any screen existed to get them wrong. Two decisions there
are load-bearing and are not obvious from the code alone: the area rounding
goes through `BigDecimal` quantised to six decimals rather than `ceil`, so an
exact 10 ft × 8 ft opening entered in millimetres stays 80 sq ft instead of
being sold half a square foot nobody measured; and a line stores `qty` as the
**total chargeable area**, not the door count, so `qty × rate == amt` and a
natively built area line still prints correctly in V8C4.

**N5.3 and N5.4** are read-only. Both landed with a fix commit, and the two
failures are worth keeping because each is a class of mistake rather than a
typo:

1. **A phone number pasted with its country code found nobody** (`d6bf5d5`).
   Partial search and country-code search pull in opposite directions — one
   needs the stored number to contain what was typed, the other the reverse —
   and only the obvious direction had been implemented. **The same bug reached
   CI a second time in N5.5's duplicate guard** (`2127d48`), because that file
   tested the *order* of the three matchers and left the comparison itself to
   an `==` between digit strings. The lesson recorded: a predicate two callers
   need for the same reason still needs testing at each of them.

2. **A `LazyColumn` never composes an off-screen item** (`38d32a6`), so an
   un-scrolled assertion is about the emulated screen size rather than the
   screen. **This too recurred in N5.5** (`2127d48`), where nine of eleven
   failures were the Save button sitting below the fold. Worse than the
   failures, two *absence* checks were passing vacuously: an un-scrolled
   "a Manager is offered no archive control" would have passed whether or not
   the control was there. Absence assertions in this repository must now prove
   their own reach by also finding a neighbouring control.

**N5.5** added the party writer. Two save semantics live in `PartyWrite` so
the difference is a tested function rather than a remembered sentence:
`edit` is a **replace**, so emptying a box takes that detail off the customer,
which is the only way a GSTIN entered against the wrong firm comes off it;
`mergeInto` is a **fill** for N5.8's "Save this customer", which never blanks
a detail already held. A third finding came out of the gap between them:
**`PartyDraft.type` must not default to `client`**, because that makes "nobody
chose a type" and "somebody chose Client" the same value, and `mergeInto`
would then have demoted every contractor in the book on its first save. The
fallback belongs to whichever writer has the context — `create` takes V8C4's
`client`, `edit` and `mergeInto` keep what is stored.

### Owed in N5.12: scrub the fixture rates

**N5.12 — replace every rate in `app/src/test/resources/fixtures/` with
obviously-fake values and update the expected totals.**

Treat all fixture rates as potentially real: at least one dealer figure
matches a rate in V8C4's own embedded seed catalogue, and the other tiers are
simple multiples of it. `products.json` holds the tier rates and
`quotations.json` holds totals derived from them, so the expected values in
`LegacyDocumentMappingTest` and the quotation screen tests move with them.

Everything else in those fixtures is synthetic and stays — the evidence for
that is in `app/src/test/resources/fixtures/README.md`. The Owner's own email
in `users.json` also stays: it is in this repository by design at
`firestore/firestore.rules:42` as `ownerEmailFallback()`, because the PWA
hard-codes it. **Do not remove or obfuscate it.**

Repository visibility is being checked; assume private until told otherwise.

**Also in the N5.12 pass — the Transportation line in `quotations.json`.**
`q_pwa_finalised` stores it with `u: "lot"` and `manual: true`. V8C4 stores
`u: ""`, `manual: false`, `k: null` and an `origRate` (answered 2026-09-25,
see "N5.9a questions for the Owner"). The Owner's ruling is that the fixture
moves with this pass rather than before it, together with any test that
reads the line as typed by hand.

### N5.6b, and the two decisions it left with the Owner

`pad` is now bounded 1–6 on the configuration branch and `fy` must read like
`2026-27`, both matching what V8C4 itself accepts, so a value set natively
cannot be silently rewritten by the PWA's clamp on its next settings save.
The issue branch is untouched, and a test proves a counter holding `pad: 9`
or `fy: 'FY25'` still issues numbers.

Two things are recorded rather than fixed, both awaiting the Owner:

1. **The prefix pattern is not shipped.** *(Closed in N5.6c.)*
   `^[A-Za-z0-9][A-Za-z0-9-]{0,11}$` rejects `SIE/QD`, which is the prefix the
   **hand-built test fixtures** hold — not, as this file first said, an export
   of production data. **No production export exists in this repository**; see
   `app/src/test/resources/fixtures/README.md`. N5.6c ships a pattern that
   admits `/`.
2. **A Manager can read `/teamSettings/access`.** The named rule says
   `admin()`, but the `/teamSettings/{other}` catch-all ORs in
   `member() && !worker()` and a narrower named rule cannot take anything away.
   Pinned as a characterisation test asserting today's behaviour, in the same
   shape as the N5.1 counter finding.

### N5.7 — the product editor, and no rule change

The first write path to `/products` the native app has ever had. Owner and
Administrator only, reached from a control on the catalogue card.

**The rule is untouched, and that was the finding rather than the plan.** The
deployed `/products` rule validates the *merged post-state*, not the keys an
update touches, so a document an older PWA version left with `gst` as a string
refuses even a correction that does not go near it. Rather than weaken the
rule, the editor writes a complete, correctly typed document every save —
exactly as `fbPushProduct` does — so the legacy defects repair themselves on
the way through. `firestore/tests/catalogue.test.js` pins both halves:
`a one-field edit on a legacy document is refused`, and
`and the same edit as a complete, correctly typed write is accepted`.

**`/products` had no positive-path coverage at all before this batch.**
`priceOk` appeared nowhere in the emulator suite, every product write in it
ran with the rules disabled, and the one negative case was refused at
`admin()` before a single field predicate evaluated. The whole
`seedModel`/`gst`/`priceOk`/`active` chain had never been shown to accept
anything, or to refuse anything.

**An absent key is not read as `null`, and the emulator said so in its own
words.** The rule reads nine possibly-absent keys bare, where about thirty
other sites in the same file guard with `.get()` or `hasAny()`. The probe
returns `Property seedModel is undefined on object.`, reported as
`evaluation error at L167:32`; an allow whose condition errors does not grant.
That is why the editor writes `contractor: null` where the stored key is
missing rather than leaving it out.

**Three things the editor must never do**, each with a test that names it:
write a seed value (every unchanged field comes from a fresh read inside the
transaction); drop a contractor price (a stored `null` is V8C4's deliberate
"Price not set"); or rewrite a unit nobody touched.

**The unit is a free text box, not a toggle.** V8C4's own `#fU` is a text
input, and the price book uses `per m`, `per pc`, `per kg`, `per rft`,
`per ft` and `per rm` besides `per sq ft` — far more products carry one of
those than carry the area unit. Because the save writes the whole document, a
toggle meaning "off equals `each`" would have converted every one of them on
its first rate correction. The chip beside the box can only *set* it to
`per sq ft` and disappears once it already says so.

**The canonical spelling is `per sq ft`**, V8C4's own. Matching folds case on
the already-trimmed value and admits nothing else: `Per sq ft` is area-priced,
`sqft` is not. A product left reading `sqft` is priced per piece — and because
a unit that is not `each` now reads in Ink on its list row, that refusal is
visible rather than silent.

**The write lands on `group__model`, never on the document it was read from.**
`docId` (`index.html:5723`) is the only product document id V8C4 computes;
`pid` (`:5682`) is the pipe-separated value it puts in the `id` and `key`
*fields*. A product still sitting at a legacy pipe id is read from there and
written to the canonical document, carrying its shelf and spec across.

**The seed model is derived stored → the `id`/`key` field → the document id →
`model`, and that order is load-bearing.** The `id`/`key` field is
`group|model` and loses nothing; the document id is `group__model` with six
characters replaced, and is lossy. `model` must come last, because V8C4 writes
it from `o.md || it.m` and it is therefore the display override where one is
set — deriving from it would compute a different document id and write to a
second document. Where only a sanitised document id is left and its model half
contains a `_`, the save is refused rather than guessed: both `/` and `.`
occur in live models.

**The extractor defect that started this.**
`tools/catalogue-import/extract-v8c4.mjs:57` read `unit: group.unit ?? 'each'`
and discarded an item's own `u`, so any item whose unit differed from its
group's was given the group's. Fixed to read the item first, with `||`
semantics rather than `??` — V8C4 chains with `||`, so a blank item unit must
fall through to the group, and `??` would have let a blank shadow it. The
derivation moved into `lib/catalogue.mjs` so it could be tested at all, and
the extraction report now tallies units so the next run shows what it derived.

**No import was run and no credential was requested.** The stored units are
corrected by hand in the editor at the final phone pass — see the blocking
warning about the importer under *Decisions that bind future work*, which is
the reason.

**Test counts at `042d995`: 1306 Kotlin test methods across 115 classes, 223
emulator tests, 58 catalogue-tool tests.** N5.7 added 58 Kotlin tests
(1248 before), 22 emulator tests (201 before) and 11 tool tests (47 before).
The emulator and tool figures are the runner's own, taken locally; the Kotlin
figure counts `@Test` methods, which is what the earlier entries in this file
counted.

The Owner's figure for how many items carry their own unit is recorded as what
it is: Owner-supplied from the private V8C4 file, 23 Sept 2026; **not
verifiable in this repository**, because V8C4 is deliberately absent and
`out/` is gitignored. Approximately 57 items carry their own `u`, of which
about 7 are `per sq ft`; the named candidates are PVC-A to PVC-D (`hsdbuild`)
and GD-GLASS-60, GD-GLASS-100, GD-FILM (`glass`). **A lead, not a
specification** — nothing built depends on those names, the extractor fix is
correct whatever the count, and the phone pass trusts the catalogue rather
than the list.

### N5.8a — the quotation draft model (complete, CI-green)

N5.8 was split into **8a (model)** and **8b (screen)**. The split point is that
8a leaves the Products tab's quote bar and draft sheet working unchanged.

| # | Commit | CI |
|---|---|---|
| 1 | `9d034c6` N5.8a model: a line is identified by its own id | **#148 green** |
| 2 | `62af89d` N5.8a model: the draft carries the whole quotation | **#149 green** |
| — | `8b5f2c9` docs: handover | **#150 green** |
| 3 | `948db4a` N5.8a storage: a draft belongs to an account | **#151 green** |
| 4 | `60e033c` N5.8a: only the store may mint a draft id | **#153 green** |

**Test counts at `60e033c`: 1353 Kotlin test methods across 117 classes, 223
emulator tests, 58 catalogue-tool tests.** N5.8a added 47 Kotlin tests
(1306 before). The emulator and tool figures are unchanged: N5.8a touches no
rule and no import tooling, and those suites were re-run green on every commit
regardless.

### N5.8b, the builder — 13 code commits

`git log --oneline --no-decorate 8784f90..a13af08 -- app/src | wc -l` → 13. The documentation commits are `663ba2e`, `dbea7d6` and this one, and are not counted here.

| | Commit | CI |
|---|---|---|
| 1 | `9a3d011` both `resume` faults, and the transport note | **#155 green** |
| 2 | `03b986e` the builder panel, in place of the draft sheet | *(covered by #156)* |
| 3 | `fe02a32` two rates on the builder, and two on the catalogue | **#156 green** |
| 4 | `d341100` who the quotation is for | *(covered by #157)* |
| 5 | `1100751` Save this customer, the one write this phase makes | *(covered by #157)* |
| 6 | `25fd6c5` three kinds of line, and Remove on all of them | **#157 red — 3 test faults** |
| 7 | `c6993d7` a line by hand, and an opening priced by area | *(covered by #158)* |
| 8 | `4221ed1` GST, transport and what it all comes to | **#158 red — a compile error** |
| 9 | `f3cd3ea` installation and the discount, and the three faults in 8b-1 | **#159 green** |
| 10 | `c60705d` clear the rate box before typing a negative into it | **#160 green** |
| 11 | `e97e846` prove `alignLinesToTier` leaves a hand-typed catalogue rate alone | **#161 green** |
| 12 | `aa58bc7` four findings from the reachability sweep | *(covered by #162)* |
| 13 | `a13af08` delete `canEditSettings`; maintenance rules 5, 6 and 7 | **#165 green** |

**Two runs went red and both were faults in what the batch itself wrote**, not
in the app: two top-level constants colliding with `ProductEditor.kt`
(`UNIT_LABEL`, `GST_LABEL` — a compile error), a shell heredoc's `${'$'}` escape
written into a Python one so a test's ids were the source text, and an absence
check whose witness sat at the opposite end of the `LazyColumn` from where it
scrolled. The last is trap 2, got wrong in the file whose own KDoc explains
trap 2.

**Counts at `a13af08`, each with the command that produced it** (rule 5):

| Count | Command | |
|---|---|---|
| Kotlin test methods | `grep -rho "@Test" app/src/test \| wc -l` | **1459** |
| Kotlin test classes | `grep -rl "@Test" app/src/test \| wc -l` | **123** |
| Code commits in N5.8b | `git log --oneline 8784f90..a13af08 -- app/src \| wc -l` | **13** |
| Files changed | `git diff --stat 8784f90..a13af08 \| tail -1` | **25 files, +4459 −129** |
| Rules and tooling touched | `git diff --stat 8784f90..a13af08 -- firestore tools \| wc -l` | **0** |

**The grep is calibrated, not assumed.** Gradle prints a test total only when
something fails, so a green run's console carries no number. Run #157 failed at
`25fd6c5` and printed `1399 tests completed, 3 failed`; `grep -rho "@Test"` at
that same commit returns **1399** exactly. The proxy and the runner agree on
the one commit where both are known, which is what makes the 1459 usable.

Emulator (223) and catalogue-tool (58) figures are unchanged, and that is
checked rather than asserted: the diff touches nothing under `firestore/` or
`tools/`.

**No rule changed, nothing was written to `/quotations`, and no number was
allocated.** The single Firestore write in the whole of N5.8 is "Save this
customer", through `PartyWrite.mergeInto` under rules that already allow it.

**Every N5.8a commit is CI-green**: #148, #149, #151 and #153, with the two
documentation commits green at #150 and #152. The batch is closed.

#### The three amendments — all applied, none outstanding

1. **The non-negative invariant had two holes** — applied in **`62af89d`**.
   A negative transport made the subtotal negative even with a valid discount,
   and a negative installation made `discountBase` negative, at which point
   `discountRefusal`'s `amount > base` stopped meaning what it thinks.
   `QuoteDraft.refusal` now bounds both (`QuoteDraft.kt`, `NEGATIVE_TRANSPORT`
   and `NEGATIVE_INSTALLATION`, the latter on the installation's rate **and**
   basis). The invariant is stated at `QuoteTotals.kt:150` naming all three
   gates — discount, transport, installation — and saying that `totals` itself
   has no floor. Tests in `QuoteDraftRefusalTest` assert the subtotal stays at
   or above zero **and** that the arithmetic would have accepted the bad
   figure, which is what makes the gate's absence visible rather than
   theoretical.
2. **The silent money-changing fallback** — applied in **`62af89d`**.
   `RateTierV2.from` falls back to `DEALER` (`Records.kt:81`), the
   lower-priced tier and so the underquote direction;
   `InstallationMode.from` falls back to `FIXED`, which bills a `pct` charge
   of 8 as 8 rupees. Both are right for a document V8C4 wrote and wrong for
   our own draft. `QuoteDraftCodec` now parses both strictly and records a
   `DraftFault` (`QuoteDraft.kt:99`): a damaged tier recovers to **Client**,
   the higher of the two offered and never the enum's first, and says so; a
   damaged installation is dropped and says so. Both block finalising. A third
   fault was added that the brief did not name — a discount whose *kind*
   cannot be read, where a stored 2000 taken as a percentage rather than
   rupees gives the whole quotation away.
3. **Where a new draft's GST comes from** — applied in **`62af89d`**.
   `QuoteDraft.gstPercent` is nullable (`:175`) and null means "not resolved
   yet", not "no GST". Such a draft charges nothing and **cannot be
   finalised** (`:432`, `GST_NOT_SET`), rather than silently going out at 0%.
   `gstSuggestion` (`:404`) resolves it from the rate the catalogue lines
   agree on and answers null when they disagree — there is **no company-level
   GST setting in this repository**, so the products are the only honest
   source. Deliberately switching GST off stays distinct from never setting
   one.

#### The B2 shape appeared three times in one batch, and is now structural

Three occurrences in N5.8a, all the same cause — an identity available at a
moment where creating one is wrong:

1. a line identified by its **product key**, so two openings of one product
   merged into a single wrong figure (fixed in `9d034c6`);
2. a draft id minted **inside a DataStore transform**, which `edit` may re-run
   (fixed in `948db4a` before it shipped);
3. a draft id minted **per view model**, which is per *process*, so a process
   death turned one quotation into two drafts.

Three is not coincidence, and a third point fix would have invited a fourth
occurrence in N5.8b or N5.9. So the third was fixed **structurally**:
`AccountPreferences.currentDraftId()` is now the only place a draft id comes
into existence, and `ProductsViewModel` cannot mint at all — it has no
reference to `Keys`. A caller that cannot mint cannot mint at the wrong
moment. The one remaining hazard, minting before a transform rather than
inside it, now lives in exactly one function where it is stated and tested
once, instead of in every caller.

The two failure paths are fixed and each has a test:

- **The emptied draft.** `persist` runs when the last line is removed, so an
  empty draft *with an id* is stored. Skipping the assignment because it was
  empty left the screen holding no id, and the next add minted a second one.
  `QuoteDrafts.resume` now adopts the stored draft **even when empty**.
- **The race after process death.** Reading the store is asynchronous; a tap
  on Add before it answered was overwritten by a straight assignment, losing
  the person's line *and* leaving an orphan. `resume` merges instead, so
  neither set of lines is discarded. This was silent data loss and was the
  more serious of the two.

The decision lives in `QuoteDrafts.resume` rather than in the view model
because **`ProductsViewModel` has no test and cannot have one** — it takes an
`AppContainer`. Putting it in pure code is what made both paths testable.

#### What commit 3 did do

Keyed the draft and `stock_pending` by account uid. The old global keys leaked
a draft — and so its customer and its rates — to the next account signing in on
the same phone, which is a permission failure rather than housekeeping, because
a Staff account may not see rates anywhere in this app.

The leak is closed by the keying alone: nothing reads the old keys any more.
The one-time adoption (`AccountPreferences.adoptOwnerlessValues`) exists only
to rescue work in progress, never overwrites a value the account already has,
and cannot run unless somebody is signed in, because `AccountPreferences` is
only reachable through `forAccount(uid)` — which makes "never delete data with
no owner" structural rather than a rule to remember. The adopted id is minted
**before** the DataStore transform, because `edit` may re-run it.

The store holds a collection keyed by draft id from the first commit that
stores one, so adding a drafts list later is screen-only. `QuoteDrafts.MAX`
refuses a runaway rather than pruning one.

#### Also settled earlier in N5.8a, for the record

- **The area rounding order is proven, not assumed.** `QuoteAreaTest.kt:98-107`,
  `and the minimum applies after the rounding, never before`: 3 ft x 3 ft =
  9.0 sq ft against `minimumSqft = 10.3`, asserting 10.3. Round-then-minimum
  gives 10.3; minimum-then-round gives 10.5. `minSqft` is **not** constrained
  to halves, which is why that case is the one that tells them apart.
- **Three defects in the draft model, found before building** (commit 1): two
  hand-typed lines could not coexist, two openings of the same product silently
  merged into one wrong figure, and a hand-typed line did not survive a restart
  at all. All three came from identifying a line by its product key.
- **A codec version bump would have erased every draft on every phone.**
  `decode` answers an empty draft for an unknown version, so fields are
  appended and every version ever written stays readable. A test built from a
  hand-written `v1` string keeps that true.
- **`assertUnclipped` does not exist.** The N4.4 helpers are
  `assertPaintedInsideCard`, `assertFooterPaintedInsideCard` and
  `assertNoDeadSpaceBelow`, on `SemanticsNode.unclippedBounds()`, in
  `app/src/test/java/in/smartie/quotedesk/ui/CardClipping.kt`. Use those in 8b.
- **The stored area spec carries no rate.** `QuoteArea.describeGeometry` goes
  into the line's `s` field; `QuoteArea.describe` keeps the rate and is what
  the card and the PDF show. A rate baked into the stored sentence would
  contradict the rate column the first time N5.10 let somebody correct a
  finalised line.

## Current next action

**Build N5.9 — finalise: write the quotation and take its number.**

N5.8b is complete and CI-verified, so the builder holds everything a
quotation needs and nothing issues one. N5.9 is the transaction that writes
`/quotations` and takes the next number from `/teamSettings/numbering` — the
counter's *issue* branch, which N5.6 deliberately left untouched while it
narrowed the *configure* branch to the Owner.

Three things are already built for it and are waiting:

- `QuoteDraft.toRecord()` turns a draft line into a `QuotationLineRecord`. It
  has no caller today; N5.9 is the caller it was written for.
- `Numbering` holds the formatting and the refusals from N5.6.
- Transport becomes an **ordinary** line titled `Transportation` (not a
  manual one — corrected 2026-09-25, see the Owner's answers under N5.9a) whose `s` is
  `QuoteDraft.transportNote`, which is why the note exists.

**Carry the N5.8b lesson into it:** when a plan prescribes a mechanism, check
the mechanism actually fires. `withTier(resolved.tier, priceOf)` was the plan's
fix for the tier half of the `resume` defect and it is a **no-op**, because it
returns early when the tier is not changing — which is exactly the state
`resume` leaves. A test now pins the no-op.

### N5.9a so far — every commit CI-verified

From `git log --oneline e9690a1..64e2abf`, each with the run that verified it:

| Commit | What | Run |
|---|---|---|
| `f22a39c` | 1 — characterise what `/quotations` accepts; no rule change | #167 green |
| `ccc08c4` | 2 — the Manager's discount cap, in the rules | #168 green |
| `4cfcb43` | docs — the rulings that existed only in chat | #169 green |
| `612514c` | docs — the Owner's two cap questions, recorded before answering | #170 green |
| `ead0a52` | 2b — the cap check was one rupee strict; now one rupee generous | #171 green |
| `3db056b` | 3 — `QuotationWrite`, the pure plan for finalise | **#172 red**: one test |
| `ca33f8c` | fix — `QuoteGstTest` relied on the old party gate | #173 green |
| `7e19a26` | docs — verified head and ruleset anchor moved | #174 green |
| `7964aa6` | docs — the Owner's answers on `snap{}` and the Transportation line | #175 green |
| `f14d0df` | 3b — the Transportation line exactly as V8C4 stores it; nine keys on every line | #176 green |
| `64e2abf` | 4 — `QuotationStore` + `QuotationWriteRepository`: read first, then the counter | #177 green |

Kotlin tests: **1500** at `64e2abf` (`git grep -h -o '@Test' 64e2abf --
app/src/test | wc -l`); the proxy was last calibrated on #172, whose runner
printed `1488 tests completed, 1 failed` against a grep of 1488. Local JVM
sweep at `64e2abf`: 674 tests across 45 classes, all passing. Emulator: **243** at `ead0a52`
(`npx firebase emulators:exec --only firestore "node --test
--test-concurrency=1 tests/*.test.js"`, run from `firestore/`).

Still to build: 5 (the bounded retry), 6 (the contended emulator test,
reporting attempts per contender), 7 (the N5-plan line table, and the N5.9
rule sketch that now differs from what shipped).

### N5.9a decisions, taken in chat and recorded here because chat is not memory

**1. Finalise requires a party NAME, not a saved customer.**
`partyId` is re-resolved from `/customers` **only when it is present**;
otherwise the typed snapshot is written with `partyId` absent.
*(The name requirement stands. The "re-resolved from `/customers`" half is
**superseded** by the Owner's re-read of 2026-09-25 — the form's snapshot is
kept and only the link is re-derived from it; see "V8C4's fbFinaliseAtomic,
re-read 2026-09-25" below. Built in N5.9a commit 3c.)*
`QuoteDraft.refusal`'s `NO_PARTY` moves from "no `partyId`" to "no party
name" accordingly.

The reason is the Owner's business, not symmetry with the PWA: a walk-in or
a first enquiry gets quoted without being filed as a customer, and forcing
every quotation through Parties first would make the native app harder to
use than the one it replaces. The builder already offers "Save this
customer" for when they do want it. V8C4 writes `partyId: null` with a typed
party object, and the emulator test `a quotation with no saved customer is
accepted, as V8C4 writes one` (N5.9a commit 1) proves the deployed rule
takes that shape. **N5.10 must never try to re-resolve an absent `partyId`.**

**2. The retry cannot tell which write was refused, and will not pretend to.**
A Firestore transaction surfaces one exception; `PERMISSION_DENIED` names no
document. The emulator shows the shape — the server reports *rule lines*
(`false for 'update' @ L802`), and the SDK does not pass that through as
structured data.

So the design is: **refuse locally everything that can be refused locally**
— role, cap, GST unset, no lines, no party name, through `QuoteDraft.refusal`
and `QuoteDiscount.refusal` — so the transaction carries only the contention
case. A `permission-denied` from inside the transaction is then,
overwhelmingly, the counter. **That is an inference, not a discrimination**,
and the repository's KDoc must say so in those words rather than claim a
precision the code does not have. A Manager over the cap therefore never
reaches the retry, and neither does a Staff account.

**3. Three retries is a starting point, not a finding.** N5.1 measured that
two concurrent transactions inside one app lost one *every* time, so one
retry is plainly too few; three is not yet evidence. **N5.9a commit 6 reports
attempts-per-contender from the contended emulator test and moves the number
if a meaningful share exhausts the bound** — before the Owner meets it on a
Friday afternoon.

**4. The rules sweep: only the discount cap was absent.** Every other Owner
decision claiming server-side enforcement is enforced —
Staff locked out of quotations (`:611`, `:612`), parties add/correct
(`:596`), parties rename/archive Owner-Admin only (`:600-606`), products
`admin()` (`:167`), numbering and the cap Owner-only (`:722` and the
configuration branch), and the counter advancing by exactly one (`:651`).
**The Manager-sees-only-their-own-quotations restriction is app-level BY
DESIGN** — `docs/N5-plan.md:56` explains why a rules-level version would
refuse the PWA's own listener, and forbids describing it as rules-enforced.
Recorded so nobody re-runs this sweep.

### N5.9a questions for the Owner — V8C4 facts, ANSWERED 2026-09-25

Asked in N5.9a commit 3 and recorded before they were answered (rule 8). The
Owner answered both from the V8C4 file. **Advisor-read evidence, not
authority**: it is checked against the repository below wherever the
repository can check it, and nothing here is a V8C4 figure or company datum.

**1. What `fbFinaliseAtomic` freezes into `snap{}` — the target for N6.**
V8C4 line 6230, under its own comment "what the quotation said on the day.
Text only — the logo and QR are not copied into every record, the current
images are used":

```
snap: {
  name, tag, addr, phones, email, web, gstin, pan,
  bank: { name, branch, acc, ifsc, upi },
  terms: termsList(), notes: notesList(),
  validityDays, gstPct, payTerms, warranty, pdfFooter
}
```

Three groups: **company identity** (`name` … `pan`), the **bank block**, then
**terms and notes plus five quote settings** (`validityDays`, `gstPct`,
`payTerms`, `warranty`, `pdfFooter`). **Text only, by deliberate design — no
logo, no QR.** None of this data exists in the native app yet (the Settings
screen says so), so 9a's `QuotationWrite` writing "whatever map it is given"
is right, and **N6 fills it** when company settings are built. This shape is
N6's target, not a guess. Checked against the repository: the only `snap` in
it is `fixtures/quotations.json`'s `{gstPct: 18, validityDays: 15}`, two keys
of the fifteen, invented by this project — consistent with, and much smaller
than, the real shape.

**2. The Transportation line V8C4 stores — and two corrections to the plan.**
`quoteLines()` pushes
`normLine({ t:"Transportation", s: t.note||"", amt: t.amt, q:"1 no", transport:true })`,
and `normLine` resolves it: `qty` 1 (`+"1 no"` is NaN, so the `q` string is
not a number), `rate = amt / 1`, `u = l.u || ""` with `l.u` undefined, and
`manual = !!l.manual` with nothing passed. The stored mapping at 6223-6224 is
`{ t, s, u, qty, rate, origRate, k, manual, amt }`, so V8C4 stores exactly:

```
t: "Transportation", s: <the note>, u: "", qty: 1,
rate: <amount>, origRate: <amount>, k: null, manual: false, amt: <amount>
```

- **Correction 1 — `u` is `""`,** not `"no"` and not `"lot"`. The plan's
  "manual lines use unit `no`" is V8C4's **display** fallback — `qLabel`
  reads `l.u || "no"` only when rendering — not the stored value.
  `QuotationWrite.TRANSPORT_UNIT` was `"no"` in `3db056b`, taken from that
  line of the plan. The invented fixture's `"lot"` is wrong too and **moves
  with the N5.12 fixture pass**, not before.
- **Correction 2 — `manual` is `false`.** "Transport becomes a manual line at
  finalise" is wrong as written: it becomes an **ordinary** line with
  `k: null` and `manual: false`. Writing `manual: true`, as `3db056b` does,
  would make V8C4 and this app's own detail screen both tag it "typed by
  hand", which it is not. The fixture's `manual: true` moves with N5.12 as
  well.
- **`amt` and `origRate` are both written.** Absence would be safe — V8C4's
  `amtOf` falls back to `qty × rate` and `normLine` defaults `origRate` to
  `rate` — but writing them matches the PWA byte for byte and costs nothing.

Corrected in the commit after this one.

### Running the pure Kotlin tests locally — found in N5.9a, and its limits

Gradle cannot build the app here, but the Kotlin compiler **inside the Gradle
distribution** can compile and run anything that imports no Android, AndroidX
or Firebase class — `domain/`, `data/model/`, most of `data/mapping/`, and
the plain JUnit tests over them.

**Run every pure test class, never only the ones a change touched.** N5.9a
commit 3 (`3db056b`) ran just its four touched classes locally — 69 tests,
all green — and CI run #172 then failed a fifth: `QuoteGstTest > switched
off, a quotation finalises with no GST at all`, whose draft named a saved
customer with no party name and had relied on the old `partyId` gate. The
full local sweep, below, reproduces that failure against `3db056b`'s test
and passes with the fix: **662 tests across 44 classes**.

```
L=/opt/gradle-8.14.3/lib; OUT=<scratch dir>
KC="$L/kotlin-compiler-embeddable-2.0.21.jar:$L/kotlin-stdlib-2.0.21.jar:$L/kotlin-reflect-2.0.21.jar:$L/kotlin-script-runtime-2.0.21.jar:$L/kotlin-daemon-embeddable-2.0.21.jar:$L/trove4j-1.0.20200330.jar:$L/annotations-24.0.1.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar"
RT="$L/kotlin-stdlib-2.0.21.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar:$L/gson-2.10.jar"
SRC=$(grep -L -e '^import android' -e '^import androidx' -e '^import com.google' app/src/main/java/in/smartie/quotedesk/{domain,data/model,data/mapping}/*.kt)
TESTS=$(grep -L -e '^import android' -e '^import androidx' -e '^import com.google.firebase' -e '^import org.robolectric' -e '^import io.mockk' -e '^import kotlinx.coroutines.test' -e '^import app.cash' -e 'quotedesk.ui\.' -e 'quotedesk.data.repository' -e 'quotedesk.core' app/src/test/java/in/smartie/quotedesk/{domain,data/mapping}/*.kt)
java -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$RT" -d $OUT/main $SRC
java -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$RT:$L/junit-4.13.2.jar:$OUT/main" -d $OUT/test $TESTS
CLASSES=$(cd $OUT/test && find . -name '*Test.class' | grep -v '\$' | sed 's|^\./||; s|\.class$||; s|/|.|g')
java -ea -cp "$RT:$L/junit-4.13.2.jar:$L/hamcrest-core-1.3.jar:$OUT/main:$OUT/test:app/src/test/resources" org.junit.runner.JUnitCore $CLASSES
```

**`-ea` is not optional.** Kotlin's `assert(...)` does nothing unless the JVM
enables assertions; Gradle's test task does by default, a bare `java` does
not. Without it `QuoteDiscountTest`'s one `assert` passed locally whatever it
checked — found and fixed in N5.9a commit 3c.

**What it is not.** It is not the build: the compiler version, flags and
dependency versions are the distribution's, not the app's, and every file
touching Android, the UI, a repository or `core` is left out — the sweep
covers 44 of the suite's 124 test classes (`grep -rl "@Test" app/src/test |
wc -l`). A pass here can still be red on CI. It is for catching a type error
or a wrong figure **before** a push costs a cycle.
The `verify` job remains the only evidence a commit is green.

### V8C4's `fbFinaliseAtomic`, re-read 2026-09-25 — what finalise must match

The Owner re-read V8C4 and sent the whole of `fbFinaliseAtomic` because this
phase rebuilds it. **Advisor-read evidence, not authority**; checked against
the repository wherever the repository can check it. Recorded before any code
acts on it (rule 8).

**1. `k: null` on a product-less line is read, not inferred — twice.**
`normLine` (2200) resolves `k: l.k||null`, `s: l.s||""`, `u: l.u||""`,
`qty`/`rate` floored at zero, and `origRate: l.origRate!=null ? +l.origRate :
Math.max(0,rate||0)` (2201) — which `QuotationWrite.lineData`'s fallback
matches. The store mapping at 6224 applies `k` again. `qlabel` and
`needsRate` exist in memory and are **not** in the stored mapping — dropped
at save, as this app already has it. The KDoc calling `k: null` "an
inference" is to be downgraded to read.

**2. The transaction, 5111-5157**, as the Owner quoted it:

```
const qRef = doc(f.db, "quotations", draft.id);   // the draft keeps this id across retries
runTransaction(f.db, async tx => {
  const qSnap   = await tx.get(qRef);             // both reads before any write
  const numSnap = await tx.get(numRef);
  if(qSnap.exists()){                             // retry after a dropped commit
    const prev = qSnap.data();
    return {no: prev.no, doc: prev, counter: null, lastIssued: null, reused: true};
  }
  ...
  if(cur) tx.update(numRef, {next: n+1, lastIssued});
  else    tx.set(numRef, {prefix, fy, pad, next: n+1, lastIssued, serverAt: serverTimestamp()});
  tx.set(qRef, Object.assign({}, draft, {no, serverAt: serverTimestamp()}));
})
```

- **a. The read-first is V8C4's own design.** It returns the **whole prior
  record** with `reused: true`, and the caller does
  `if(out.reused) Object.assign(draft, out.doc)`. Ours must hand back the
  record, not only the number.
- **b. The document id is the draft id**, minted at 6209
  (`if(!state.draftId) state.draftId = newDraftId()` — "survives a failed
  attempt") and cleared **only on success** at 6262 ("this record is
  closed"), never in the catch. That is the whole mechanism the read-first
  depends on: an id re-minted on the second press finds nothing and mints a
  second number.
- **c. `if(cur) update else set`** — "never a set followed by an update on a
  document that did not exist when we read it."
- **d. Two guards with their own messages:**
  - no counter and not admin: "Numbering has not been set up yet. An
    administrator must open Settings and press Save shared settings once."
  - financial-year mismatch: `The team is on financial year ${cur.fy}; this
    device is on ${N.fy}. Reload before finalising.`
- **e. V8C4 has no self-retry loop.** None. It relies entirely on the
  Firestore JS SDK's own transaction retry.

**3. DEFECT, in a design not yet built: the saved-customer refusal must not
exist.** `QuotationWrite` (`3db056b`) refuses finalise with `CUSTOMER_GONE`
when the saved customer's record cannot be found. V8C4 does the opposite,
deliberately. `resolvePartyId` (6379) returns null and never throws:

```
const onForm = partyFromForm();
if(!onForm.name && !onForm.gstin && !onForm.phone) return null;
const held = state.partyId ? state.customers.find(c=>c.id===state.partyId) : null;
if(held && sameParty(onForm, held)) return held.id;
const found = findCustomer(onForm);
return found ? found.id : null;
```

and at 6270: "No party is created here. A party joins the Parties list only
when the user presses 'Save this customer', or picks one that is already
saved... Either way the quotation keeps its own snapshot of the details in
draft.party, so editing the party later never rewrites it."

- **The party id is optional metadata.** The quotation is self-contained —
  name, GSTIN, phone and address are copied into it — so a missing record
  removes a cross-reference and loses no quotation data. **When the record
  cannot be found, set the link to null and finalise. Do not refuse, do not
  prompt, do not ask the person to re-pick.** A refusal there is a
  native-only failure the PWA does not have, at the one moment the person
  most needs the number.
- **The link is re-derived from the form, not merely re-read from the
  record.** 6211: "a party that was picked and then typed over cannot be
  carried into the record" — `if(held && sameParty(onForm, held))`. A held
  id survives only while it still matches what is on the form now. Checking
  only that the record exists would file a quotation for Party B's details
  under Party A.
- **The Owner's earlier note — "the message must offer to re-pick" — is
  withdrawn.** It was written believing the refusal was correct. There is no
  message to write.

**4. 9b's messaging — V8C4's own text, to be used:**

- Offline, checked **before** anything is built (6203): "Finalising needs an
  internet connection — the number is shared with the team"
- Counter returns nothing: "The shared counter did not respond"
- Any failure (the catch at 6274): `"Not finalised — " +
  friendlyAuthError(e) + " Your quotation is untouched."` — and in that same
  catch `state.quoteNo = null`, the draft is **not** cleared, the draft id is
  **not** cleared: "nothing was consumed and nothing is marked finalised."
- Success, in this order (6259-6264): `draft.no = no` → `state.draftId =
  null` → `upsertQuote(draft)` → `clearDraft()` → toast `Finalised as
  ${no}`. The draft is cleared **after** the number is in hand, and
  `upsertQuote` carries "the listener may have beaten us to it" — ours needs
  the same tolerance.

**5. Commit 6 — the Owner's decision, and the question it must answer
first.** Do (1), the Node emulator test of the protocol under the real
rules, and (2), the Kotlin retry against a fake. **Do not build the
Android-SDK-against-emulator CI job now** — out of N5.9a's scope, large, and
the binding gets a real exercise at the single phone pass; a candidate to
revisit only if that pass shows trouble (see "N5.9a's recorded bounds").

Because V8C4 has been live with **no** self-retry and no reported duplicate
or failed numbers, the first question is not "how many tries" but:
**under the shipped rules, what error code does counter contention actually
produce?** ABORTED means the SDK already retries and our loop may be largely
redundant; `permission-denied` is terminal, the SDK will not retry it, and
the self-retry is the only thing between a busy minute and a hard failure.
Commit 6 **measures** it — never assumes it — names it before any number,
and gives the command (rule 5). Then:

- a realistic level (2-3 contenders, a small team) **and** a pessimistic one
  (8-10); the bound comes from the pessimistic worst case with headroom;
  both reported;
- the commit **states that the emulator is single-process and its contention
  is an indication, not a production measurement** — that number is never
  to be quoted later as measured against real Firestore;
- the Node test mirrors the Kotlin transaction step for step, and **each
  file names the other in a comment**, so a change to one not made to the
  other is visible in review. That correspondence is the only thing joining
  (1) to (2).

### After the re-read: what N5.9a commit 3c did, and what it left open

**Done in 3c.** `CUSTOMER_GONE` is gone: a saved customer that cannot be
found leaves the link empty and the quotation is issued. The form's snapshot
is written and never rewritten from a record. The link is derived from the
form by `QuoteParty.linkFor` — the picked customer survives only while the
form still matches it, else a saved customer the form matches (N5.5's port
of `findCustomer`, `PartyDuplicates.find`), else nothing — against the
customer list the screen holds, as V8C4 uses `state.customers`. The finalise
transaction no longer reads `/customers`; like V8C4's it reads the quotation
and the counter, plus the discount limit V8C4 has no need of.

**QUESTION FOR THE OWNER — V8C4's `sameParty` text.** It is not in this
repository, and nothing here ports it. `QuoteParty.sameParty` is a
**stand-in**, deliberately strict — the name must match, and a GSTIN or phone
present on both sides must agree — because too strict costs a cross-reference
that `findCustomer` may restore, while too loose files one firm's quotation
under another. Its KDoc says it is a stand-in. The real text lets it become
a port. Blocks nothing in 9a; wanted before 9b ships finalise.

**FINDING, not fixed — "Save this customer" has the same typed-over shape.**
`ProductsViewModel.saveCustomer` passes the draft's **held** `partyId` with
the **form's** details to `PartyWriteRepository.saveFromQuotation`, which
merges them into that record. Pick Sunrise, type Metro Glass's details over
the form, press "Save this customer", and Metro Glass's phone and GSTIN are
merged into Sunrise's record. N5.8b code, outside this batch; recorded for
the Owner rather than fixed, per "do not fix anything beyond" the batch.

**FOR 9b — remove the finalised draft; never clear it.** The document id is
the draft's id, and it is cleared in this app only by removing the draft
(`QuoteDrafts.remove`), after which `currentDraftId()` mints a fresh one.
The builder's existing `clearDraft()` empties the lines and **keeps the id**.
Used after a successful finalise, the next quotation would carry the issued
one's id, the read-first would find it, and the new quotation would be
answered with the **previous number** and never issued. V8C4 clears
`state.draftId` only on success (6262) and never in the catch; 9b does the
same with `remove`, and only on `Issued` or `AlreadyIssued`.

### N5.10 is coupled to the finalise retry — read this before widening the rule

The Owner's ruling of 2026-09-25, and the guard it costs:

- **Edit** a finalised quotation: the creator, and Owner/Administrator on
  anyone's. **New capability** — V8C4 cannot edit a finalised quotation at
  all — so N5.10 writes a **new update branch**.
- **Cancel**: Owner and Administrator only, as V8C4 has it
  (`if(!admin) return toast("Only an administrator can cancel a quotation")`)
  and as the deployed rule already says. A Manager cannot cancel, including
  their own. The rule was stricter than the old plan row, in the safe
  direction; no deploy-day break.

**Widening for edit removes the second defence against a duplicate number.**
Today a retry that blindly re-writes a quotation is evaluated as an *update*,
fails `hasOnly(['status','cancelledBy','cancelledAt'])`, and takes the whole
transaction with it, so the counter never advances. The moment an edit branch
accepts a full document that stops holding, and the finalise transaction's
**read-first is the only thing left** between a lost response and a customer
holding two quotations for one job. Keep cancel at `admin()`, bound the edit
branch to what an edit may actually change, and never let it accept the
document whole.

**N5.10 must also never re-resolve an absent `partyId`.** A quotation may
legitimately carry no saved customer — a walk-in or a first enquiry, quoted
without being filed — so the party snapshot is what it has and there is
nothing to look up.

### N5.9a questions on the discount cap rule — asked by the Owner, recorded before they were answered

Both are about commit 2's `discountOk()` (`ccc08c4`). Recorded here, with the
Owner's worked numbers intact, **before** either is answered, because a
question that exists only in chat does not survive a compaction. Each is
closed by editing this section to say what was found and which commit
settled it — not by deleting it.

**Q1. The transport slack.** Cap 10%, products and installation ₹1,00,000,
transport ₹50,000. The intended discount is ₹10,000; the rule permits up to
₹15,000, because its base bound is `discBase <= subtotal + disc.amt` and
transport sits inside the subtotal as a line. **Owed:** whether the rule can
see transport at all — for instance by storing the transport amount as a
top-level field — and what making it exact would cost; if it cannot be made
exact, why not, and the residual recorded here as a **known bound** rather
than left implied by the word "tight".

**Q2. The boundary.** The app computes the cap in whole rupees, HALF_UP
(`QuoteMath.discountRefusal`: `rupees(base × cap ÷ 100)`); the rule computes
`base × cap ÷ 100` in doubles. Fractional caps like 7.5% and 12.5%, and
bases where that figure is not a whole rupee. **Owed:** whether commit 2's
tests cover those cases (add them if not), and whether the two can disagree
at the boundary. **The Owner's ruling if they can: the rule must be one rupee
generous, never one rupee strict** — a refusal there is invisible to the
person, and a rupee is not.

**Q2 — ANSWERED AND FIXED in N5.9a commit 2b.** They could disagree, and
commit 2 was **one rupee strict**. 5% of 44,410 is ₹2,220.50; the app rounds
that to ₹2,221 and lets a Manager have it, and the rule refused ₹2,221
because `2221 > 2220.5`. Commit 2's only boundary test used 44,400 × 5%
= ₹2,220 exactly — the one case where the two cannot differ, so it was green
over the fault (rule 7). Reproduced before the fix: of five non-whole
vectors, the three that round **up** (44,410 @ 5%, 44,410 @ 7.5%,
44,404 @ 12.5%) were refused; the two that round down passed. The rule now
reads `amount <= base × cap ÷ 100 + 1`: it accepts at most one rupee the app
refuses (whenever the exact figure's fraction is under half a rupee) and
never refuses one the app accepts. The five vectors are `CAP_BOUNDARY` in
`firestore/tests/quotation.test.js` and `capBoundary` in
`QuoteDiscountTest.kt`; each side asserts the same `allowed` figures. Rule 7,
measured: removing the `+ 1` fails the three round-up acceptances; widening
it to `+ 2` fails the two round-down refusals.

**Q1 — ANSWERED; the residual is a known bound, and whether to add a field
is the Owner's call.**

- **The arithmetic is confirmed.** The rule accepts any `discBase` up to
  `subtotal + disc.amt`, which for an honest quotation is the true base plus
  the transport. Claiming the transport as base buys **`transport × cap ÷
  100`** past the cap — ₹5,000 in the Owner's example, ₹15,000 permitted
  against ₹10,000 intended — plus the one-rupee margin from Q2. Every figure
  the customer sees stays honest; only `discBase` is inflated. Pinned as a
  passing test, `KNOWN BOUND: transport buys transport × cap ÷ 100 past the
  cap`, which flips to `assertFails` the day the bound is closed.
- **No rule can make it exact, because every field that could tell it the
  transport is written by the same client.** Transport is a line in `lines`,
  and the rules language cannot sum or search a list. A top-level
  `transport` field would let the rule check `discBase <= subtotal +
  disc.amt - transport` exactly **against the declared figure** — but a
  client writing straight to Firestore declares `transport: 0` beside a
  ₹50,000 transport line and gets the same ₹5,000. The field moves the lie
  from one number to another; it does not remove it. Unrolling a sum over a
  fixed number of line slots is the only way a rule reads lines, and it
  would make any quotation longer than the unroll unwritable.
- **The cost of the field anyway:** one additive top-level key that V8C4
  never writes and cannot strip (V8C4 cannot edit a finalised quotation, and
  cancel touches three keys only), and one more rule change for the Owner to
  deploy. What it would buy is a consistency check against **our own** bugs
  — an app that computed `discBase` wrongly would be refused — not against a
  hostile writer.
- **How much it matters.** Reaching the slack at all needs a client that
  bypasses the app. A Manager already has a larger route inside the app:
  hand-typed line rates are **outside the cap by design, and the Owner
  accepted this** (`docs/N5-plan.md:120-123`). The cap is a guardrail, not a
  proof, and the slack does not change that.
- **Recommendation: record the bound, do not add the field.** It closes
  nothing against the case the cap exists for.
- **DECIDED 2026-09-25 — the Owner accepted the recommendation.** It stays a
  recorded limit. A top-level `transport` field moves the false figure
  rather than closing the hole, because whoever writes straight to Firestore
  controls that field too; and hand-typed line rates are already outside the
  cap by the Owner's accepted design, a larger route. Closing the smaller gap
  while the larger stands by agreement buys nothing. **Keep the pinning test**
  (`KNOWN BOUND: ...`) so it flips the day the gap closes.

### Owed, and recorded rather than done

- **The 50-draft cap is not enforced.** `QuoteDrafts.refusalToAdd()` is called
  by nothing, because nothing can create a second draft. It becomes live in the
  first batch that ships a **drafts list**; that batch is not scheduled.
- **`Permissions.canEditSettings` contradicts what shipped** — it permits an
  Administrator to edit settings, while N5.6's Settings screen is Owner-only
  with a view-only notice for everyone else. Left in place with a comment; it
  is the Owner's call whether to delete it or change the policy.
- **The stale `quotations.json` fixture** still carries `"unit": "sqft"`, the
  spelling N5.7 corrected to `per sq ft`. Checked and safe to defer — neither
  test class that reads it touches area pricing — and it moves with the N5.12
  fixture pass.

The rest of this section describes N5.8 as a whole and is kept for the record.

**Build N5.8 — the quotation builder, draft only.**

Catalogue, area and manual lines; the Dealer and Client tiers; installation;
the discount with the Manager cap N5.6 configured; transport; GST. **Nothing
is written to Firestore** — all the content and none of the danger. N5.9
builds the finalise transaction that takes a number from the counter.

Area pricing reads the product's `unit`, matching `per sq ft` with case folded
and nothing else, and applies `minSqft` per door after rounding up to the next
half square foot. `QuoteMath` already holds that arithmetic from N5.2 and its
four worked examples; N5.8 is the screen over it.

**Carry the N5.7 lesson into it:** the rule was never the problem. What made
the batch was reading what the deployed rule actually does before designing
against it — and finding it had never been proven to accept anything.

### The staging pass is deferred, not skipped

Phone testing happens once, at the end, so the deployment and the whole of
`docs/PHONE-TEST-CHECKLIST.md` now run **after N5**, in one sitting, covering
N4 and N5 together. That file stays the cumulative record of what is owed and
nothing else is: N4.2's reconciled rows, N4.3's unrun ones, N4.4's new ones,
the older N3 and N3.1 rows neither pass reached, and N5's when they are
written.

When that sitting comes, the Owner runs the deployment; this branch never
deploys, and production rules are not part of it:

```powershell
cd firestore
firebase.cmd deploy --only firestore:rules --project smartie-quote-desk-staging
```

Deploy from the **latest CI-verified head**, and check it first with
`git diff --quiet <head> a794d64 -- firestore/firestore.rules` — see "Where the
work is" at the top of this file, which is the only place these hashes are
maintained. **Do not copy a hash into this paragraph**: it read `2127d48` until
2026-09-24, which was the verified head at the time of writing and by then sat
*five* rules commits behind. No index deploy: `firestore.indexes.json` has not
changed since `69d0fce`.

**Three things to know before the pass, so none of them reads as a defect.**
The two existing "yysh" rows are two real documents — N4.4 stops new ones and
cannot merge these, so both will still show and one is removed by hand. A role
appears beside a name only on an Owner's or an Administrator's phone; a
Manager's and a Staff account's show the name alone, because the rules do not
let those accounts read `/users`. And an Administrator can no longer change
another Administrator, which is N5.0b and intended.

**No acceptance row is passed until it has been run on a phone.** An
automated test passing is not a pass in that file.

## Decisions that bind future work

**Finalise retries itself; the Firestore SDK will not do it.** A transaction
retries automatically when the *server* aborts it for contention. The
quotation counter's rule does the concurrency check first — `next ==
resource.data.next + 1`, exactly — so a stale transaction is refused as
`permission-denied`, which is not a retryable status, and the SDK gives up.
Measured in N5.1 over twenty contended pairs: roughly half of genuinely
simultaneous issues surface as a permission error. **N5.9 must catch
`permission-denied` on the counter and re-run the whole transaction itself,
bounded.** Any later batch that touches finalise inherits this; it is not an
implementation detail of one commit.

**Purchase History is filtered in the app, not in the rules, and that is
deliberate.** The Owner decided it after seeing why: Firestore evaluates a
list query against its *constraints* rather than document by document, so the
moment the read rule mentions `resource.data` the app's one unconstrained
`/purchase` listener is refused. The query that would replace it,
`where('del','==',false)`, silently drops every legacy row carrying `del: 1`
or no `del` at all, and refuses the PWA's own listener in the same project.
So the screen hides rows it should not show; it does not stop a determined
client reading them, and no document in this repository may say otherwise.

**Deferred, not dropped: a write-time role snapshot.** `byRole`, `rcvRole`
and `delRole` on the requirement itself, validated in the rules against the
writer's actual role, so that **every** role sees who did what rather than
only an Owner or an Administrator. Today a person's role can be shown only
where `/users` is readable, which is `admin()` alone, and N4.4's C6 falls back
to the name for everybody else. This needs a rules change and is scheduled
with a later rules batch — the Owner's decision, recorded so the fallback is
not mistaken for the intended end state.

**Deferred, not dropped: a rules-level restriction on reading other people's
Purchase history.** Only after the PWA is retired, and only together with a
one-time normalisation of `del` to a boolean across every existing
requirement. Until both are true, proposing it again is re-deciding something
already decided.

**A requirement you raised is one you can finish, and not one you can
rewrite.** The creator may correct or remove a requirement while nothing has
arrived, and may record deliveries and write off a shortfall for as long as
it is open. The moment a delivery lands, Edit, Urgency and Remove go and
Receive stays: the row has become a record of what arrived. Reopen is Owner
and Administrator only, and a reopen hands the row back to its creator whole.


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

**Writing a requirement stamps a `rev`, and that shuts the PWA out of that row.**
`revOk()` in the purchase rules reads `request.resource.data.keys()`, which on an
update is the **merged post-state** — so a document that already holds a `rev`
still holds it after an update that never mentioned one, and `1 == 1 + 1`
refuses the write. The tolerance covers only a document that has **never**
carried a `rev`. No V8C4 fixture carries one, so the PWA never writes one:
the moment this app updates a requirement, a PWA update to that same document
is refused.

That costs nothing today — the native app writes only to
`smartie-quote-desk-staging` and the PWA runs against production — and it is
recorded here because it is the cutover's problem, not N4's. The fix is a rules
decision for the Owner, and the two options are not equal: dropping `rev` from
the app's updates gives up the guard against two devices completing the same
requirement, while relaxing `revOk()` to accept an unchanged `rev` weakens it
for everybody. **Neither was taken. N4 changes no rules.** Proved by name in
`firestore/tests/purchase.test.js` and written out in `docs/N4-plan.md`, trap 5.

**Purchase history is filtered in the app, not in the rules, and that is
deliberate.** Firestore evaluates a list query against its *constraints*, not
document by document, so the moment a read rule mentions `resource.data` an
unconstrained listener is refused. The Purchase tab would then have to query
`where('del','==',false)`, and legacy rows carry `del: 1` as a number or no
`del` at all — both shapes are in `fixtures/purchase.json` — so every one of
them would silently vanish from the shop floor's list. It would refuse the
PWA's own listener in the same project too.

**Deferred: a rules-level restriction on reading other people's Purchase
history — only after the PWA is retired, and only together with a one-time
`del` normalisation.** Until then a Staff account's own-rows-only history is an
app-level filter, which `docs/N4.3-plan.md` states plainly rather than
implying otherwise.

**The PWA and the native app must never both write Purchase requirements in
the same Firebase project.** This is the production cutover restriction, and it
follows from two independent facts, either of which is enough on its own:

1. **The PWA overwrites `rcvQty`.** The Owner ran
   `tools/catalogue-import/inspect-v8c4.mjs --purchase` read-only against the
   approved V8C4 `index.html`, and it reported `OVERWRITES`: a PWA receive
   replaces the stored figure with the quantity of that one delivery. The
   native app writes `rcvQty` as a **cumulative** total, so one PWA receive
   against a partly received requirement silently discards everything received
   before it, and the requirement can then never close by arithmetic.
2. **The PWA does not follow the `rev` contract** — the note above. It never
   writes `rev`, and once this app has stamped a requirement, a PWA update to
   that same document is refused outright.

Before a native production cutover, **one** of these must happen:

1. update the PWA so that it accumulates `rcvQty` and carries `rev`; or
2. retire the PWA, or make it read-only, and move **all** Purchase writers to
   the native app together.

Running both as writers, or migrating the Purchase tab in part, is not a third
option. It costs nothing today — the native app writes only to
`smartie-quote-desk-staging`, and the PWA runs against production — and it is
recorded now so the cutover plan cannot be surprised by it.

**The inspector's other two verdicts are what make cumulative `rcvQty` safe**,
and they are recorded so nobody re-derives them: the PWA renders a received
quantity **only inside a received branch** (`USED_ONLY_WHEN_RECEIVED`), and it
decides a requirement is finished from `received` / `status` and **never** from
a positive `rcvQty` (`CLOSURE_FROM_RECEIVED_OR_STATUS`). A row carrying
`rcvQty: 5`, `received: false`, `status: "Needed"` therefore reads as open in
both apps, which is exactly what a partial receipt must be. The verdicts are
the Owner's run of the tool, not a reading of the PWA from this machine — the
approved `index.html` is deliberately not in this repository.

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

**NEVER RUN `import-staging.mjs` AGAINST PRODUCTION.** The catalogue importer
carries **seed rates in every payload**: `tools/catalogue-import/lib/plan.mjs:69-92`
names `dealer`, `contractor`, `client`, `gst` and `active` on every product,
and `merge: true` does **not** protect a field that is present in the payload.
`reconcileProducts` (`:213-218`) skips only documents that are already
byte-identical, so any product the import touches **for any reason at all** —
including a one-character change to its `unit` — has its stored rates replaced
by the seed's. On production, where the PWA edits rates daily, this would
destroy live pricing with no warning and no record of what it overwrote.

Never run it against production without either a rate-preserving mode or an
explicit per-product diff that a person has read first. Supplying a fresh
`--export` makes production's values win per field (`lib/plan.mjs:94-111`),
which is a mitigation and not a licence: it still rewrites every document it
touches.

This is why the N5.7 unit correction is done **by hand in the editor** and no
import is run.

## Deferred — known defects, recorded and not fixed

These are findings from N5.6c, N5.7 and N5.9a that were deliberately not fixed in the
batch that found them. A plan document gets superseded; this list does not.

### N5.9a's recorded bounds — two, in one place

The Owner's instruction of 2026-09-25: both honest limits of N5.9a live
here, not scattered through commit messages.

1. **The transport slack.** The cap rule bounds `discBase` by
   `subtotal + disc.amt`, so a client writing straight to Firestore can buy
   `transport × cap ÷ 100` past the cap. **Accepted by the Owner**; pinned by
   the emulator test `KNOWN BOUND: transport buys transport × cap ÷ 100 past
   the cap`, which flips the day the gap closes. Detail in the entry below
   and under "N5.9a questions on the discount cap rule".
2. **The Kotlin-to-Firestore binding is proven only by CI's compile.**
   `FirestoreQuotationStore` — the code that turns a finalise into real
   Firestore reads and writes — is exercised by **no test anywhere**: the
   repository's tests run against a fake, and the emulator tests are Node.
   **The only exercise it ever gets is the single final phone pass**, as
   `PHONE-TEST-CHECKLIST.md` row **T-Q1**: a real Finalise against staging
   that writes a quotation and takes a number, both checked in the console.
   If that row is not run, this bound is never closed. An
   Android-SDK-against-emulator CI job would close it earlier; the Owner
   ruled it out of N5.9a and it is a candidate **only if the phone pass
   shows trouble**.

### The discount cap rule is slack by `transport × cap ÷ 100` — a known bound

`discountOk()` bounds `discBase` by `subtotal + disc.amt`, and transport is a
line inside the subtotal, so a client writing straight to Firestore can claim
the transport as discount base: at a 10% cap, ₹1,00,000 of products and
installation and ₹50,000 of transport, ₹15,000 is accepted against ₹10,000
intended. Plus the rule's deliberate one-rupee margin at the cap boundary.
**Not closable by any stored field**, since the client writes them all; the
reasoning, the cost of a top-level `transport` field and the recommendation
are under "N5.9a questions on the discount cap rule" above. Pinned by the
emulator test `KNOWN BOUND: transport buys transport × cap ÷ 100 past the cap`.

### `priceOk` admits infinity

`firestore/firestore.rules:89` reads
`return v == null || (v is number && v >= 0);`. `Infinity` is a number and is
greater than zero, so a price of infinity is accepted. The native editor
cannot produce one — `ProductWrite.priceIsSayable` requires a finite value —
so this is reachable only by a hand-crafted write from an Owner or
Administrator account. **Not N8 work**: it is a one-clause rule change that
belongs to whichever batch next has a reason to touch `/products`, so it is
not deployed on its own.

### A product's unit is copied into `/stock` and never re-synced

`StockEntry.kt:81` takes `product.unit` when a stock row is created and
`StockWrite.kt:328` writes it, so a stock row keeps whatever unit the product
had on the day it was added. Correcting a product's unit in the N5.7 editor
does **not** update existing `/stock` rows, which will still read `each`.
There is a phone-checklist row so this is not discovered as a surprise. A fix
means either re-syncing on a product save, or reading the unit through the
product rather than storing it — the second is the better shape and is a
change to how stock rows are read, not a rules change.

### A transient denial freezes an Owner's team access for the whole session

**Found while answering the N5.6c follow-up about the Manager's Team screen,
and unrelated to that change.** `AuthRepository.kt:190-193`:

```kotlin
fun observeAccess(): Flow<TeamAccess> = accessDocument.docDataFlow()
    .map { it?.toTeamAccess() ?: TeamAccess() }
    .onStart { emit(TeamAccess()) }
    .catch { emit(TeamAccess()) }
```

`observeAccess` is the **one** listener that opts out of `retryingListener`,
and `.catch` terminates the flow. So a *transient* failure — not a permission
decision, a dropped connection — leaves `TeamAccess` empty for the rest of
the session, stripping an Owner's or Administrator's Primary/Additional
badges and their Appoint and Revoke controls until the app is restarted.

**The trap that makes this more than a one-line fix.** A naive retry would
spam, because for a Manager and a Staff account the denial is *permanent and
correct* — the rules do not let them read `/teamSettings/access`, and N5.6c
closed the catch-all that used to let them. Any fix must therefore tell a
permanent permission denial from a transient failure and retry only the
second. The swallow also means a genuine breakage never reaches the log,
which is why the phone pass carries a *negative* check for it.

### Owed before the N8 cutover: counter contention in the live PWA

Recorded 2026-09-25 at the Owner's instruction; **act at N8, not before.**
If N5.9a commit 6 finds that counter contention surfaces as
`permission-denied` under this repository's rules, then at production cutover
the **live PWA** meets it too — and V8C4 has no retry loop, so where today it
retries silently inside the SDK and succeeds, it would show "Not finalised".
**Do not change V8C4 and do not touch production.** Before cutover, settle
what the PWA does under the new rules — commit 6's measured error code is the
starting evidence, with its single-process caveat.

### Owed in N8: normalise the products no edit ever reaches

**N8 — normalise every product document that neither app has rewritten.**

The N5.7 editor writes a complete, correctly typed document, so any product
somebody edits repairs itself. Products nobody edits keep whatever shape an
older PWA version left them in — `gst` as a string, `active` as `1`, no
`seedModel` — and the deployed rule refuses a write to those until something
rewrites them whole. Whether any such document still exists in production is
**not established**: the parity audit recorded those variants somewhere, and
no export exists in this repository to check against.

The same step clears what N5.7 leaves behind. A product read from a legacy
`group|model` document is written to the canonical `group__model` one, and
the legacy document stays where it is. Nothing reads it — V8C4 computes only
`docId` (`group__model`), and `canonicalProduct` prefers the newer canonical
document — but it is stale data and should be removed once, deliberately,
with both apps stopped.

### Before the importer is ever pointed at production

See the blocking warning under **Decisions that bind future work**. It is the
single most dangerous operation in this repository.

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
| `docs/N4-plan.md` | The N4 Purchase plan: the decisions, the write contract, the rules traps, the batches and the acceptance rows |
| `docs/N5-plan.md` | The N5 Quotation plan: the Owner's decisions, the printed order, the rules diff per batch with a V8C4 verdict for each, the data shape, the worked examples and the batch list |
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
5. **Every count names the command that produced it.** Commits from `git log`
   with its range, tests from the runner's own output, files from
   `git diff --stat`. A count written from what somebody intended is not
   checkable; a count carrying its command is, by anyone, in one paste.

   This rule exists because N5.8b produced **four** miscounts in one phase, all
   the same shape — a number written from the plan rather than read from the
   repository. Correcting each total fixed that total and left the shape
   untouched. So the rule is not "count carefully": it is that an unchecked
   count may not be written down. A drifting count is how a lost or duplicated
   commit, or a quietly deleted test, hides in plain sight.

   Same move as the structural draft-id fix in N5.8a — **make the mistake
   unavailable rather than remembered.**
6. **No field may be right by coincidence.** If a value is only correct because
   two things happen to agree today — a head hash that is also the current
   ruleset, a run number that is also the installed build — it will go stale in
   silence, because nothing about the first changing tells you the second did
   not. Record what each field actually answers, and derive anything that
   depends on two of them, with the command that settles it.

   This is the deploy-hash lesson. `a849650` was recorded as "the commit to
   deploy the rules from" and was correct the day it was written, when the
   verified head and the last rules change were the same commit. Two rules
   commits landed hours later and it became wrong with no signal.
7. **A green test is evidence that the test passed — not that the behaviour
   exists, and not that it is right.** When a test is the only evidence for a
   behaviour, say what change would make it fail. If nothing plausible would,
   the test is decoration.

   That matters more here than in most projects: **CI is this repository's only
   verification of the app as a whole.** Gradle cannot resolve the Android
   plugins in the agent's container, so nothing touching Android, Compose,
   Firebase or Robolectric runs anywhere else, and a green tick is the whole of
   what anyone sees of it. *(Until N5.9a commit 3 this read "no Kotlin runs
   anywhere else". Pure JVM Kotlin does — see "Running the pure Kotlin tests
   locally" under N5.9a. CI stays the authority.)*

   One day in N5.8b produced the same finding three ways:

   - `PartyWrite.mergeInto` — built and tested in N5.5, called by **nothing**
     until N5.8b. Its tests were green over a feature no user could reach.
   - `alignLinesToTier` — its test used `addManual` lines, which set `manual`
     **and** `rateEdited`, so it could not distinguish the predicate it claimed
     to test. It would have stayed green while a hand-typed rate was silently
     overwritten.
   - `canEditSettings` — its test pinned `isAdmin`, contradicting the `isOwner`
     policy the Settings screen shipped and the deployed rule enforces. Green
     over a trap.

   Same spirit as Rule 5: an unchecked claim may not be written down.

8. **Anything the Owner sends that is not yet in the repository is at risk.**
   A ruling, a decision or a question that arrives in chat is written into this
   file (or the plan it belongs to), committed and pushed **before it is acted
   on** whenever the session's context is near compaction — without waiting to
   be asked. Chat is not memory; a compaction keeps a summary, and a summary
   loses the worked numbers.

   The Owner's standing instruction of 2026-09-25, given with the two N5.9a
   open questions above, which were recorded here before either was answered.
