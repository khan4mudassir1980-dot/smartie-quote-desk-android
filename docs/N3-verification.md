# N3 Our Stock — verification record

The second staging manual pass has been run. This is the evidence for it, row
by row against `docs/N3-plan.md`, and the record of what is **not** covered.

Reported to this session on 2026-09-18. Run by the Owner against
`smartie-quote-desk-staging`, on **one physical Android phone**, using
`app-staging-debug.apk` from
[run #58](https://github.com/khan4mudassir1980-dot/smartie-quote-desk-android/actions/runs/35313430384)
— the corrected build carrying the fixes for the seven UI defects the first
pass sent back. Nothing in this session touched either Firebase project: this
container holds no credential for either, by design.

**State: single-device staging verification passed; physical two-device
concurrency verification pending.**

## What the pass confirmed

| Area | Result |
|---|---|
| The seven defects the first pass sent back | All fixed on the device |
| Adding stock, in both modes | Passed |
| Keyboard, focus and accidental dismissal | Passed |
| Stock card layout, pending delta and History | Passed |
| Status thresholds, and the refusal below zero | Passed |
| The Done transaction, and what History recorded | Passed |
| Persistence across a restart | Passed, on the one phone |
| Offline behaviour, and reconnection | Passed |
| Worker, Staff and Owner separation | Passed, from the device |
| Pinned-product drag reorder | Passed |
| Two-device concurrency | **Not run — no second phone** |

## Row by row

Statuses mean exactly what they say. **Passed** is a row the evidence covers
whole. **Passed in part** is a row whose clauses were not all exercised, and
the evidence column says which half was. **Not run** is a row this pass did
not reach — not a failure, and not a pass either.

| Row | Status | Evidence, as reported |
|---|---|---|
| T-S1 | Passed | A successful Done transaction, and History showing the correct previous value, delta and next value |
| T-S2 | Passed | Out status only at zero |
| T-S2b | Passed | Quantity cannot go below zero |
| T-S2c | Not run | A no-op edit writing nothing was not exercised |
| T-S2d | Not run | A reorder-level-only edit producing one `min` movement was not exercised on its own |
| T-S3 | Passed in part | Low when the quantity equals the reorder level. The one-below and one-above steps were not separately reported |
| T-S4 | Not run | Tapping the Low and Out tiles to filter and to clear was not reported |
| T-S5 | **Pending** | Two phones, same row. **A second Android phone was not available.** See below |
| T-S6 | Passed in part | Staff can adjust stock and change the reorder level, and get neither the exact-quantity field nor Archive. The rules refusing a forced `set` stays emulator-covered, not device-shown |
| T-S7 | Passed | Owner edit of exact quantity, reorder level and note, with History showing previous, delta and next |
| T-S8 | Passed | A Worker sees permitted stock data and gets no mutation control, no History, no Products and no prices |
| T-S9 | Not run | A pending delta surviving a force stop and reopen was not separately reported |
| T-S10 | Passed in part | Manual stock creation with quantity, reorder level and note. The duplicate half — adding the same manual item twice and being refused — was not reported |
| T-S11 | Passed in part | A stock pin survives a restart. Three pins leading the list in pin order, and writing no history rows, were not separately reported |
| T-S12 | Passed | Offline, cached stock stays readable |
| T-S13 | Passed | Offline, Done is refused with "Internet required to change stock", and the pending change stays safe |
| T-S14 | Passed | Reconnection does not auto-commit |
| T-S15 | Passed | A manual Done after reconnection succeeds |
| T-X4 | Not run | The 360×640 and 412×915 screen sizes and the 1.0 and 1.3 font scales were not reported |
| T-S16 | Passed | Add Stock shows only From Products and Manual Item to begin with |
| T-S17 | Passed | Catalogue search and product selection; the modes' forms are separate |
| T-S18 | Passed | Choosing a product, then creating catalogue stock with quantity, reorder level and note |
| T-S19 | Passed | Manual stock creation with its own fields; catalogue and manual fields no longer appear together |
| T-S20 | Not run | Clearing the Stock search focus when Add Stock opens was not separately reported |
| T-S21 | Passed | With the keyboard up, Back closes the keyboard without dismissing the sheet |
| T-S22 | Passed in part | An outside tap does not dismiss the Add or Edit sheet. A second Back press with the keyboard already down was not separately reported; it is the same dialog property |
| T-S23 | Passed | The stored quantity stays authoritative before Done, and the pending delta is clearly shown |
| T-S24 | Passed in part | A saved note is visible and survives a restart **on the one phone**. The second-device half was not run |
| T-S25 | **Unblocked, pending a device run** | No longer deferred: N4 Batch 4 makes a requirement creatable, so the row can now be performed. **It has not been.** See below |
| T-S26 | Passed | The pinned-product arrows are gone, and six-dot long-press drag reorder works |
| T-S27 | Passed in part | Pin order survives a restart **on the one phone**. The second-device half was not run |
| T-S28 | Passed | A solid full-width purple header line |

Seventeen rows passed whole, seven in part, six were not run, one is pending a
second device and one — T-S25 — is unblocked but not yet run.

### T-S25, unblocked by N4 Batch 4

The row is *"Look at an open Purchase requirement with a note — the note is on
the card, and its urgency is red, yellow or green without opening Edit."* It
could not be performed at all while the Purchase tab was read-only, because
nothing in the app could create a requirement to look at. That is no longer
true: the tab writes, and a Worker can add one.

**The automated half is done and green.** `PurchaseBoardScreenTest` renders a
requirement carrying a note at its urgency and asserts both are on the card
without any sheet being opened; `PurchaseRowTest` holds the same for each of
the three urgencies, by name.

**The manual half has not been run, and this row is not passed.** It needs the
staging APK on a phone. It is listed in the Batch 4 manual checks in
`docs/N4-plan.md`.

## Verified, with no numbered row in the plan

**Owner Archive / Stop tracking works, and survives a restart.** The plan
names Archive only from the other side — T-S8, that a Worker is offered none —
so there was no row for an Owner using it. The pass exercised it anyway and it
behaved, which is recorded here rather than dropped for want of a row. It is
not backfilled into the table as a numbered pass.

## What is pending

**T-S5 — two phones, the same row, `+3` and `−1` without refreshing.** Not
run, because a second Android phone was not available. This is the row that
shows the Firestore transaction doing its job against a real second writer:
the final quantity correct, and two audit rows rather than one.

It is **not** substituted by anything. `StockWriteTest` drives the transaction
body with a faked document, including a replayed body, and the emulator suite
covers what the rules accept and refuse. That coverage is real and it stands,
but it is not the physical two-device check and must not be recorded as it.
Until T-S5 is run on two phones, N3's concurrency behaviour is verified in
code and unverified on devices.

## What was not part of this pass

Six rows above were not reached: T-S2c, T-S2d, T-S4, T-S9, T-S20 and T-X4.
They are not failures and they are not passes; they are outstanding, and they
are tracked here alongside T-S5 rather than being quietly dropped.

**The rules and indexes were deployed — as history, not as a fresh check.**
The Owner has since confirmed that before installing the run #55 APK, from the
`firestore` directory of an extracted **`d11b5da`** tree, both of these ran and
completed:

```
firebase.cmd deploy --only firestore:indexes --project smartie-quote-desk-staging
firebase.cmd deploy --only firestore:rules   --project smartie-quote-desk-staging
```

Two things follow, and they are not the same thing:

- **What was deployed is known.** `firestore.rules` and
  `firestore.indexes.json` are byte-identical between `d11b5da` and the current
  head — the rules file has not been touched since `a339c0f`, which predates
  `d11b5da`. So the v9 rules the repository holds now are the ones that
  command uploaded. That is a repository fact, checkable with `git show`.
- **What is live now is not verified.** Nobody has read back the deployed
  ruleset from the project. A later deploy, a console edit or a rollback from
  any source would not show up here, and no session holds a credential to
  look. This is **deployment history the Owner confirmed**, not a fresh
  verification of the currently deployed rules or their exact contents.

Treat it as: the correct rules were sent to staging at a known point, and the
live state has been taken on trust since.

## Purchase, and why its rows are N4's

Purchase is deliberately view-only until N4: the screen reads requirements and
shows them, and nothing in the app creates, edits or closes one. So the manual
checks that need a real requirement — creating one, its urgency colour
persisting, its note persisting — **could not be performed and are not
recorded as passed**. They move to N4, where the writing exists to exercise
them.

What does exist is automated rendering evidence, and it is recorded as
automated evidence only:

| Automated | What it shows |
|---|---|
| `PurchaseRowTest` | A saved note renders on the card, and no empty note line appears when there is none |
| `PurchaseRowTest` | Each urgency renders with the Owner's wording on the card |
| `AppearanceTest` | Very urgent is red, can-wait is yellow, needed-but-not-now is green, and the three are three distinct colours |

Rendering a record is not the same as a requirement created on a device and
still correct after a restart. The first is what the tests show; the second is
N4's to verify.

## Staging data after the pass

The temporary manual and catalogue stock rows created during verification were
**archived** afterwards, so staging carries no leftover test stock on the
board.

**That paragraph was true and the behaviour behind it was wrong.** Archiving
wrote `off: true` and left every one of those documents in `/stock`, hidden
but present — which is precisely the defect recorded under *The archive
finding* below. Those rows are the legacy rows the compatibility path
converts; they are not a clean-up that already happened.

## Automated coverage behind this

359 Kotlin tests across 35 classes, 43 Firestore rules tests against the
emulator, and 26 catalogue-import tests — green on
[run #58](https://github.com/khan4mudassir1980-dot/smartie-quote-desk-android/actions/runs/35313430384)
for the head this APK was built from. That coverage is what makes a single
manual pass sufficient for the rows it did reach. It is not what makes T-S5
unnecessary.

## The N3.1 photo pass, and the deployment behind it

**Owner-confirmed deployment history.** The N3.1 Firestore rules and the index
exemption were deployed to `smartie-quote-desk-staging` by the Owner and both
deployments reported success. As with the `d11b5da` deployment recorded above,
this is **deployment history, not a fresh read of the live ruleset**: nobody
has read the deployed rules back, and no session holds a credential to do it.
Production was untouched.

**The first physical photo pass** ran on the run #77 APK (`02b3edf`), on one
phone, and passed: the Photo tile visible and unclipped, camera and gallery
selection, preview with a cancel that wrote nothing, add, thumbnail and larger
view, persistence across a restart, offline viewing of a cached photo with
every mutation control disabled, replace, remove that stayed removed across a
restart, quantity, reorder level and note all preserved, no photo entry in
Stock History, and a stored `worker` — the person the app now calls **Staff**
— able to view the photo with no Photo, Replace or Remove control.

That was seven of the fifteen photo rows. **A second physical pass has since
closed three more, and changed what one of them means.** The detail is below
and in `docs/N3.1-plan.md`. **Neither N3 nor N3.1 is closed.**

**Role titles in this document** name the stored roles — `owner`, `admin`,
`staff`, `worker` — because that is what the rules and the documents use.
Stored `staff` is displayed **Manager** and stored `worker` is displayed
**Staff**; nothing stored changed with those titles.

## The role-title pass, on the run #81 APK

**Owner-confirmed, on one physical phone**, against the staging build at
`ea5a417`. The two renamed titles were checked on a real device, both for what
they read and for what the accounts behind them could still do.

**What the titles read.**

| Stored role | Displayed | Confirmed |
|---|---|---|
| `worker` | **Staff** | Yes |
| `staff` | **Manager** | Yes |
| `admin` | Administrator | Unchanged |
| `owner` | Owner / Administrator | Unchanged |

No old user-visible **Worker** title appeared anywhere in the Team UI that was
checked. The role selector showed Administrator, Manager and Staff, each once,
with no overlap and no ambiguous option — which is the on-device counterpart
of the overlap defect `ea5a417` fixed in the tests.

**What the accounts could still do.** This is the half that matters, because
the whole claim of the change is that it moved words and nothing else.

- The account now titled **Staff** (stored `worker`) kept exactly the old
  Worker permissions: Products and prices unavailable; stock and saved photos
  viewable; **no** Photo, Replace, Remove, quantity-changing or Edit control.
- The account now titled **Manager** (stored `staff`) kept exactly the old
  Staff permissions: Products and prices available; stock quantity and Edit
  controls available; Photo, Replace and Remove controls available. No
  unexpected gain or loss was observed.

**So the change is confirmed display-only — in the cases physically tested.**
Two things that phrase deliberately does not cover:

- **The selector's write path was not exercised on the device.** Nobody
  changed a teammate's role through the menu and then read the stored value
  back, so "choosing Manager writes `staff`" rests on
  `TeamRoleSelectorScreenTest`, which asserts the `wireValue` each selection
  carries. That is real coverage and it is not a device check.
- **Owner and Administrator permissions were not re-exercised** on a device in
  this pass; only their titles were confirmed unchanged.

Neither gap is a reason to doubt the change — the stored values, the rules and
every permission predicate are untouched, and the emulator's permission matrix
is green — but neither has been physically performed, and this record does not
say otherwise.

## The second physical pass, and the second phone

**Owner-confirmed, on physical Android phones.** Three photo rows closed, one
is blocked by a screen that does not exist yet, one had its acceptance
contract withdrawn and replaced, and a second phone found a real defect
without running the row it might be mistaken for.

| Row | Status | Evidence, as reported |
|---|---|---|
| T-P4 | **Passed** | Real stock photographed off the shelves. The compressed photo kept the printed label and the model readable — the clause the acceptance check exists for. The 80 KiB ceiling serves this business |
| T-P6 | **Passed** | A photo on a manual item survived an app restart |
| T-P11 | **Passed** | End to end as a stored `staff` — displayed **Manager**. Add, Replace and Remove all worked, and the removed photo did not come back after a restart. Run #81 had confirmed the controls were *present*; this is the write half, so the row is now whole |
| T-P7 | **Not run — blocked** | A display-model rename needs the Products & Categories editing screen. That screen is **N6** and still in development, so there is nothing to rename with. Not a failure; it cannot be attempted until N6 exists |
| T-P12 | **Withdrawn and replaced** | The archive/un-archive expectation was withdrawn by Owner decision. See below. Not run against the replacement contract |
| T-P13 | **Pending** | Two phones replacing one row's photo **at the same time**. Not performed — see the note on the second phone |
| T-P14 | **Pending** | The console usage measurement. Not measured |
| T-P15 | **Pending** | The Administrator cascade delete. Not exercised on a device |

### The archive finding — a one-way hidden duplicate

**Reported from the run #81 build.** `SIE-EXTRECEIVER` had been stopped, so it
was gone from the board — and it could not be added back. Add stock still
offered the catalogue product, the form offered zero defaults because it knew
nothing of the hidden row, and saving was refused with "This item is already
in stock".

The cause, traced in the code rather than guessed: stopping tracking wrote
`off: true` and left the stock document in place. `observeStock` filters `off`
rows out, so the board never showed it; `StockWriteRepository.create` reads the
document and finds it, so `StockWrite.create` refused. Nothing in the app read
or unset `off`, so there was no route back. Every temporary row "archived"
after the second N3 pass is in the same state.

**The Owner withdrew the archive/un-archive expectation.** Removal is now
permanent, and T-P12's acceptance contract is replaced by the one in
`docs/N3.1-plan.md`: the row, its photo document and its cached bytes deleted;
a read-only stopped-item history entry left behind; the catalogue product
untouched; the same identity addable again, fresh; and **no** quantity
movement written on removal. Legacy `off: true` rows convert themselves to
history entries and stop blocking their own re-add.

The entry recorded above under *Verified, with no numbered row in the plan* —
"Owner Archive / Stop tracking works, and survives a restart" — stands as a
record of what was observed, and is now **superseded**: what it observed
working is the behaviour this finding removed.

### The second phone — what it did, and what it did not

The Owner installed the build on a second physical Android phone. The app
worked there: the screens rendered and the functions behaved. That is a
functional pass on a second device and it is recorded as one.

**It found a defect.** The bottom navigation overlapped the system
navigation — traced to `NavigationBar` applying the navigation-bar inset
inside a height the app had pinned, so a three-button phone's ~48dp inset came
out of the items rather than sitting under them. Fixed, with the three-button
case now a test rather than a second phone.

**It did not run T-P13, and it did not run T-S5.** Neither row is about owning
two phones; both are about two devices writing the **same** row at the same
moment. No simultaneous photo replacement and no simultaneous quantity change
was performed. Both rows stay pending, and nothing in this pass may be read as
covering them.

## 20 September: the rules deployed, and the first removal on a device

**Owner-confirmed, one physical phone.** Recorded as an observation, not a read-back: no
session holds a credential for either Firebase project.

**The deployment.** `firestore/firestore.rules` from `ed4c80d` was deployed with
`firebase deploy --only firestore:rules --project smartie-quote-desk-staging`. The CLI
confirmed the staging target and the staging Rules tab showed a new publication at about
1:47 AM. **Production Rules were checked separately and their latest publication remained
11 September 2026, 6:43 PM — production was not changed.**

**The build.** The `smartie-native-apks` artifact from run #88 was installed and the app
showed the **Staging** label.

| Step | Status | Evidence, as reported |
|---|---|---|
| Add a temporary manual stock product | **Passed** | The item appeared on the board |
| Add a stock photo | **Passed** | |
| Change the quantity | **Passed** | |
| Remove the item permanently | **Passed** | |
| The removed item appears in Stopped History | **Passed** | |

### T-P12 is passed in part, not passed

The pass exercised the removal and the history entry — the heart of the replacement contract
recorded in `docs/N3.1-plan.md`. **Four clauses of that contract were not reported**, and a
clause nobody exercised is not a clause that passed:

- the catalogue product survived untouched;
- the same product could be **added to stock again, fresh and empty**;
- **no `/stockMoves` entry** was written by the removal;
- the item left the **Tracked, Low and Out** counts.

Those four, and **T-P13**, **T-P14** and **T-P15**, remain open. **T-P7 stays blocked** on the
N6 Products & Categories screen.

## What is still open after all of this

- **N3:** T-S5 (two phones, one row), the second-device halves of T-S24 and
  T-S27, and the six rows the second pass never reached — T-S2c, T-S2d, T-S4,
  T-S9, T-S20 and T-X4. **T-S25 is no longer blocked** — N4 Batch 4 makes it
  performable, and it is waiting on a phone like the rest.
- **N3.1:** T-P7 (blocked on N6), the four unreported T-P12 clauses above,
  T-P13, T-P14 and T-P15.

**Neither phase is closed**, and neither may be described as verified.

## Production safety

Production Firebase was not read or written during this pass or this session.
Staging remains the only write target.
