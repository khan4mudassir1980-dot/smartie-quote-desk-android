# Project status

**The single current-status record. Read this before planning or changing
anything.** Last updated 2026-09-20.

## Where the work is

| | |
|---|---|
| **Active development branch** | `claude/trusting-hamilton-z12eer` |
| **Last CI-verified head** | `2328932` — [run #108](https://github.com/khan4mudassir1980-dot/smartie-quote-desk-android/actions/runs/35536237079), fully green (unit tests, lint, Firestore rules emulator, APK build). **This is the commit to deploy the rules from.** |
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
| N4 Purchase | **In progress.** The plan of record is `docs/N4-plan.md`. Batches 0 to 4 are done, and so are the four defect batches A, B, C and D. A staging phone pass has since confirmed **all four defect fixes on a device**, plus three partial-receipt behaviours **in part** — listed line by line under "The Batch C staging phone pass". **No role-specific row and no whole T-R row is passed yet**, and N3's **T-S25 stays pending**. **N4.2, creator self-service, is code complete and CI-verified**, and is waiting on an Owner-run staging rules deployment paired with the APK rollout; the history screen is Batch 5 and the tab badge is Batch 6 |
| N5–N8 | Not started |

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

## Current next action

**Deploy the N4.2 rules to staging, and put the new APK on every phone in the
same sitting.**

Both halves, together, because the rules get **stricter for a Manager** as
well as looser for Staff. A Manager still running the Batch C build would be
offered Edit on a requirement a delivery has reached and would be refused by
the server. Staff's new abilities are the safe direction — an old build
simply does not offer them — so it is the Manager restriction that must not
run ahead of the APK.

The Owner runs the deployment; this branch never deploys, and production
rules are not part of it. The build is the `smartie-native-apks` artifact of
[run #106](https://github.com/khan4mudassir1980-dot/smartie-quote-desk-android/actions/runs/35523545235),
and it must show **Staging**.

Then the phone rows: **T-C1 to T-C15** in `docs/N4.2-plan.md`, plus the N4
rows the Batch C pass did not reach — every role-specific row, T-R16, T-R17,
and N3's **T-S25**. Batch 5, the read-only Purchase History screen, comes
after, and carries a binding requirement: **a Staff account sees only the
rows it raised**.

**No N4 or N4.2 acceptance row is passed until it has been run.** The Batch C
pass confirmed eight things and they are listed above; nothing else.

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
