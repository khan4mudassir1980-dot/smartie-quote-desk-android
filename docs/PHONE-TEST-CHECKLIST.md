# Phone test checklist

**Everything a phone still has to prove.** Cumulative: every batch adds its
rows here and nothing leaves until it has been run on a device and passed.

Since N4.4 there is **no phone testing between batches**. Every row below is
run once, at the end, against one staging APK and one staging rules
deployment. That is why this file exists: nothing else carries the memory of
what is owed.

Last updated for **N4.4**.

## How to use it

- A row is passed only when it has been **run on a physical phone** against
  staging. An automated test passing is not a pass here.
- Record the run number of the APK a row passed on. A row whose surface has
  since changed goes back to owed, and says why.
- Role mapping on screen: Owner = `owner`, Administrator = `admin`,
  **Manager** = stored `staff`, **Staff** = stored `worker`.

---

## Owed now

### N5.7 — the product editor

| Row | Check |
|---|---|
| T-E1 | **Go through the product list and set the unit on every item that is priced per square foot.** Trust the catalogue, not this list — the Owner knows which products are sold that way far better than a text search does. The known candidates are the PVC curtains (PVC-A to PVC-D, group `hsdbuild`) and the toughened-glass items (GD-GLASS-60, GD-GLASS-100, GD-FILM, group `glass`), but check the rest of the catalogue too: the extractor gave the wrong unit to any item whose unit differed from its group's, not only the sq-ft ones |
| T-E2 | Type the unit **exactly** as `per sq ft`. A product left reading `sqft` or `sq ft` is priced per piece, deliberately — the unit shows on its list row, so it is readable there |
| T-E3 | A unit that is not `each` reads darker on the list row than `each` does, so a wrong one is findable without opening every product |
| T-E4 | After correcting a product, open it in the **PWA** and confirm both the unit and every rate read correctly there. This is the check that the write landed on the document the PWA reads |
| T-E5 | **Change a rate by hand in the PWA, then change only the unit in the app, and confirm the rate is unchanged.** The editor must write the rate it read from Firestore and never a seed value |
| T-E6 | A product whose contractor rate is set shows it in the editor as a fact with no box, and the rate is still there after saving a change to something else |
| T-E7 | A product with no contractor rate shows **Price not set**, not `₹0`, and still has none after a save |
| T-E8 | Editing a `per m` or `per pc` product's rate leaves its unit exactly as it was |
| T-E9 | A Manager opening a product sees the stored details, the sentence saying why, and **no Save control** |
| T-E10 | A refusal — a blank name, a GST of 29 — lands on the field and the sheet **stays open** |
| T-E11 | An existing **stock** row for a product whose unit was corrected still shows the old unit. This is expected and recorded under Deferred in `PROJECT-STATUS.md`; it is not a defect found on the pass |
| T-E12 | A Manager's Team screen still renders, with names and no role badges. N5.6c closed `/teamSettings/access` to them and the denial is swallowed, so this is the check that nothing broke |
| T-E13 | **Negative check, because the error is swallowed and never reaches the log:** an Owner's Team screen still shows the Primary and Additional Owner badges and the Appoint and Revoke controls. If they are missing, `observeAccess` failed and the app said nothing — see Deferred in `PROJECT-STATUS.md` |

### N4.4 — the fixes and changes from the run #113 pass

| Row | Check |
|---|---|
| P-B1a | On an open card, **every** action the role is offered is visible and tappable — nothing clipped, nothing behind another control. Owner on their own untouched row sees Remove; a Staff account on their own untouched row sees Remove |
| P-B1b | The Owner's partly received row offers **Close with N received**, and the confirmation names both figures |
| P-B1c | No card has dead space under its buttons |
| P-B1d | **Stock, Products and Team** cards render their full footer at 360dp with nothing clipped and no dead space — the same shared card was changed |
| P-B2a | Adding a requirement while the connection drops, then tapping Add again, produces **one** requirement, and the second tap says it was already added |
| P-B2b | The **Open** header's number equals the number of open cards on screen. Count them |
| P-B2c | Add cannot be tapped twice: it is disabled the moment the first tap is taken |
| P-C1a | The Purchase screen ends in **one collapsed History**, closed on open, that expands on tap |
| P-C1b | Inside History: Received, and **Removed** as its own collapsed sub-section |
| P-C1c | A **Staff** account sees only the rows it raised inside History on the Purchase screen — no other person's received row anywhere on that screen |
| P-C1d | Owner, Administrator and Manager see everyone's rows in that History |
| P-C1e | **More → Purchase history** shows the same rows as the Purchase screen's History, for the same account |
| P-C2 | Cards are visibly tighter than the run #113 build; no wasted space |
| P-C3 | The quantity line is bold and larger. `10 required · 4 received · 6 remaining` is fully readable at 360dp — not cut off, not ellipsized |
| P-C4 | A note stands out in its own tinted box |
| P-C5a | Row actions are one tidy row, the same shape for every role, with an overflow `⋯` where there are more |
| P-C5b | Every action in the overflow opens the right sheet, and each is a real 48dp target |
| P-C6a | On an **Owner or Administrator** phone, a person's role shows beside their name — "Added by", "Received by", "Removed by" |
| P-C6b | On a **Manager or Staff** phone the same lines show the **name alone**, and nothing is broken or blank by its absence |
| P-C7 | A removed requirement that had received something still shows what arrived (e.g. "2 in") |

**Known, not a defect to report:** the two existing "yysh" rows are **two real
documents**. The N4.4 fix stops new twins but cannot merge these two — both
will still show, and the Owner removes one by hand.

### N4.3 — not yet run

| Row | Check |
|---|---|
| T-D3 | A requirement with no recorded creator sorts with everyone else's |
| T-D4 | Staff receives 4 of 10 on their own requirement; it stays Open and reads `10 required · 4 received · 6 remaining` |
| T-D5 | Staff receives the remaining 6; it closes and appears in History |
| T-D6 | Staff is offered **Close with 4 received** on their own partly received row, and the confirmation names both figures |
| T-D7 | Staff is offered no Receive and no Close-short on somebody else's row |
| T-D8 | After a receipt, Staff's Edit, Urgency and Remove are gone but Receive remains |
| T-D9 (Staff half) | A **Staff** account opens Purchase History and sees only its own received rows. *The Owner half passed on run #113* |
| T-D11 | Staff sees only their own removed rows; a PWA-removed row with no creator is not among them |

### N4.2 — not yet run, as reconciled by N4.3

| Row | Check |
|---|---|
| T-C2 | A Staff account soft-removes their own untouched requirement; it leaves both lists and does not come back |
| T-C3 | **Reworded by N4.3.** Staff is offered **no** row action at all on a requirement somebody else raised — no Edit, no Urgency, no Remove, **and no Received and no Close-with** |
| T-C4 | **Superseded by N4.3.** Staff **is** offered Received and Close-with on **their own** requirement, and neither on anyone else's or on one with no recorded creator. Reopen is absent everywhere |
| T-C5 | **Reworded by N4.3.** Staff sees the Purchase History entry, and inside it only the rows they raised; still no stock controls and no products or prices |
| T-C6 | **Two phones.** A Manager receives 4 of 10 against a Staff requirement; the Staff member's Edit, Urgency and Remove disappear **without restarting the app** — but Received remains |
| T-C7 | After that receipt the Manager is offered Received and **Close with 4 received**, and no Edit, Urgency or Remove |
| T-C8 | The shortfall confirmation names both figures, and afterwards `qty` reads 4, the row is closed, and the card still shows 4 in |
| T-C9 | An Owner or Administrator can still edit, change urgency and remove that partly received requirement |
| T-C10 | A Manager raises a requirement and removes it themselves while untouched; a Manager is still refused removal of somebody else's |
| T-C11 | **Reworded by N4.3.** An Administrator reopens a received requirement and its original creator is offered Edit, Urgency, Remove **and Received** again |
| T-C12 | A PWA requirement with no `byUid` offers a Staff account nothing, and behaves normally for Owner, Administrator and Manager |
| T-C13 | Two accounts with the **same display name** and different uids: neither is offered the other's controls |
| T-C14 | A Manager is refused Reopen — and after the rules deployment the refusal holds against a hand-edited client too |

**T-C3, T-C4 and T-C5 are owed** although they passed on run #106: N4.3
reworded or superseded all three afterwards, so that pass no longer covers
them.

**T-C6 needs a second phone. T-C13 needs a duplicate-name account.**

### Re-check in the final regression

| Row | Why |
|---|---|
| T-C1 | Passed on run #106 — a Staff account adds a requirement, then edits its name, note and quantity and changes its urgency from their own card. **Re-check: the card actions moved in N4.4 (C5)** |
| T-C15 | Passed on run #106 — every role badge reads Owner, Administrator, Manager or Staff, and every existing account keeps the role it had. **Re-check: the card actions moved in N4.4 (C5)** |

### Older, still open

| Row | Check |
|---|---|
| T-S25 | N3's purchase row — a real requirement, its urgency colour and its note, exercised by hand |
| T-S5 | **Two phones**, one stock row written from both at once |
| T-S24, T-S27 | The second-device halves |
| T-S2c, T-S2d, T-S4, T-S9, T-X4 | Never reached by either N3 pass |
| T-P13, T-P14, T-P15 | Stock photo rows never run |
| T-P12 | Passed in part on 20 September; four clauses unreported |
| T-P7 | **Blocked** on the N6 Products & Categories screen, which does not exist |
| T-R16, T-R17 | N4 role rows the Batch C pass did not reach |

---

## Before the rules deploy

Not this batch's work. Recorded here so it cannot be forgotten, because on
deploy day the whole committed ruleset meets the live PWA for the first time,
for every collection at once.

| Row | Check |
|---|---|
| T-E14 | **Verify every document shape the PWA writes is accepted by the committed ruleset** — quotations, customers, products, purchase, stock, teamSettings — each pinned by an emulator test rather than checked by hand. `firestore/tests/catalogue.test.js` is the pattern: it pins what `fbPushProduct` writes, so a mistake in that reading fails in CI instead of stopping the business on the day |

---

## Passed

| Row | Passed on |
|---|---|
| T-D1, T-D2 | run #113 |
| T-D9 (Owner half) | run #113 |
| T-D10, T-D12 | run #113 |
| T-D13, T-D14, T-D15, T-D16 | run #113 |

*T-C1 and T-C15 passed on run #106 but are listed above for re-check.*

---

## Maintaining this file

1. **Add every new batch's rows here**, in their own section, when the batch
   lands — not when it is planned.
2. **A row only moves to Passed with the run number it passed on.** If the
   surface it covers changes afterwards, move it back and say what changed.
3. **Never delete an owed row to make the list shorter.** A row that is
   blocked says what it is blocked on and stays.
