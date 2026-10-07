# N5 cutover — when the native app replaces the PWA

Written in N5.12 (2026-10-07). **Nothing here has happened yet.** Production
Firebase is never written by this branch; the production cutover is **N8**,
and this document is what N8 starts from. The single current status is
`docs/PROJECT-STATUS.md`; where this document and it disagree, the status
file wins and this one is corrected.

## 1. What cutover is

From cutover day the native app is the tool for **Quotation** and
**Purchase** on the production project (`smartie-quote-desk`), and the PWA
(V8C4) stops writing Purchase. Until then the native app writes only to
staging (`smartie-quote-desk-staging`), and the PWA keeps running against
production unchanged.

## 2. Preconditions — every one is hard

No production cutover until all of these hold:

1. **N6 has landed.** A native finalise writes no `snap` until N6 builds
   company settings, and V8C4 re-prints a quotation from `snap` alone — a
   native quotation re-printed from the PWA would come out on a blank
   letterhead ("BLOCKER for the N8 cutover", recorded 2026-09-26).
2. **Production `teamSettings/company` carries every field the PDF
   prints** — `name, tagline, address, phone, email, web, gstin, pan,
   bankName, bankBranch, bankAcc, bankIfsc, upi, validityDays, payTerms,
   warranty, pdfFooter, logo, qr, terms, notes`, and N6's `signature` — read
   through a viewer-only credential. A missing one prints as missing on
   every quotation from cutover day.
3. **Counter contention is settled.** In the emulator, with no self-retry —
   V8C4's behaviour — two of three simultaneous finalises were refused with
   `permission-denied` (N5.9a commit 6). Whether production's current
   ruleset already does the same is the first thing N8 checks.
4. **Every document shape the PWA still writes is accepted** by the
   committed ruleset — quotations, customers, products, stock,
   `teamSettings` — each pinned by an emulator test (T-E14 in
   `PHONE-TEST-CHECKLIST.md`). **Not purchase**: the hard-block refuses the
   PWA's purchase writes by design (§3).
5. **Every staging phone row has passed** — the first phone pass after
   N5.12, then N6's.
6. **The Owner deploys the production rules** from the ruleset anchor named
   in `PROJECT-STATUS.md` — which carries the Purchase hard-block (§3).
7. **The production Android API key is restricted** (§5, item 11), and
   **the production APK is not built or uploaded by the public
   repository's CI** (§5, item 10).

## 3. Purchase at cutover

**The PWA's Purchase writing stops at cutover** (the Owner's decision QZ,
2026-09-29, corrected 2026-10-05). Everyone uses the native app for
Purchase from cutover day. **The control is staff stopping PWA Purchase
use** — announced, and done on the day.

**The hard-block behind it — the Owner's option (b), 2026-10-07, built in
N5.12 commit 4b.** The rules require `rev` on every `/purchase` create and
update: a create is revision 1, an update the stored revision plus one (1
on a row V8C4 wrote). The native app always sends it (`PurchaseWrite`); the
PWA never does. **From the production rules deploy at N8, every PWA
purchase write is refused.** Until N5.12 these V8C4 writes were still
accepted on a row the native app never wrote — **19** in the differential
replay (`firestore/tools/diffrules.js` over `v8cases.js`), by category:

1. a **create** — by an Administrator, a Manager or Staff (3);
2. a **top-up**, the quantity raised (5);
3. an **edit** of name, quantity, urgency and note (5);
4. an **Administrator's restore** of a cancelled requirement (2);
5. an **Administrator's delete** (`del: 1`) (3);
6. an **Administrator taking a V8C4 Ordered row back to Needed** (1);

and, outside the replay, an **Administrator's cancel where `received` is
already the number 0**. Since N5.12 every one is refused, each pinned in
`firestore/tests/v8c4-purchase.test.js` beside a witness that the same write
carrying the native `rev` is accepted. The Owner's and an Administrator's
native writes on old V8C4 rows stay accepted (`firestore/tools/
admincheck.js`, 24 of 24).

**What a PWA user sees after the deploy:** V8C4's own failure for a refused
write; it has no handling of its own for it. That is why the announcement
matters.

**Why the two apps must never both write Purchase** — either fact alone is
enough: the PWA **overwrites** `rcvQty` with one delivery's quantity where
the native app keeps a cumulative total, and the PWA does not follow the
`rev` contract.

**The hard-block options that were weighed** (2026-10-06):

| Option | What it is | Cost |
|---|---|---|
| a | Process only | Nothing to build; relies on people; a stray PWA receive on a V8C4-created row silently wipes the cumulative `rcvQty` |
| **b — chosen** | **`rev` required on every `/purchase` create and update** | One rules commit; the V8C4 tests turned, the 19 replayed writes refused; headroom re-measured (§6); a PWA user sees V8C4's generic failure; no PWA fallback for Purchase. Reaches production with the N8 rules deploy, which happens anyway |
| c | As (b), switched on by an Owner-only flag the rule reads | No deploy to switch; a `get()` on every purchase write — a billed read, latency, expressions |
| d | Firebase App Check enforced on Firestore | Blocks the PWA everywhere, Quotation included — only at full retirement; Play Integrity set-up |
| e | Change or retire V8C4, or take its hosting down | Changing V8C4 is excluded by the Owner's rule; taking it down removes every fallback |

**Decided not to fix, and stays so (QZ):** V8C4's add-to-stock keys (D1)
and a Manager's restore (D4) stay refused; reopen stays Owner and
Administrator.

## 4. Quotation at cutover

- The Quotation tab's "Keep using the PWA to issue quotations" banner was
  removed in N5.12 — on staging builds; there is no production build until
  N8.
- A native quotation re-printed from the PWA needs `snap` — N6 first (§2.1).
- Counter contention (§2.3) is a Quotation risk on the day: the PWA
  retries nothing.

## 5. The N8 checklist — every item recorded so far

Each is recorded in `PROJECT-STATUS.md` under its own heading; this is the
single list.

1. **N6 first** — native finalise writes `snap` (§2.1).
2. **Production company settings complete** (§2.2), read through a
   viewer-only credential.
3. **The stale receipt fields.** Remove `rcvQty`, `rcvBy`, `rcvUid` and
   `rcvAt` where `received` is the number 0 and status is Needed or
   Cancelled — what V8C4's restore and cancel leave behind. The N8 batch
   tests exactly that on the emulator, against a V8C4-restored row after the
   cleanup: a Manager's and the Staff creator's edit, a Manager's cancel and
   an Administrator's cancel accepted — each refused on the same row
   before. First read with `tools/catalogue-import/inspect-v8c4.mjs`
   whether V8C4 can mark a restored row Ordered; if it can, the shape
   includes Ordered.
4. **Counter contention in the live PWA** (§2.3).
5. **Normalise the products no edit ever reaches** — a `gst` stored as a
   string, `active` as `1`, no `seedModel` — and remove the legacy
   `group|model` documents once, deliberately, with both apps stopped.
6. **The importer is never pointed at production.** `import-staging.mjs`
   carries the seed rates in every payload and would overwrite live
   pricing.
7. **T-E14** before the rules deploy (§2.4).
8. **The production rules deploy carries the hard-block** (§3): from that
   moment the PWA cannot write Purchase. Deploy it on the day the staff
   move, not before.
9. **Deferred until the PWA is retired:** a rules-level restriction on
   reading other people's Purchase history (together with a one-time
   normalisation of `del` to a boolean), and the same for quotation
   history. Both are app-level filters until then.
10. **Where the production APK is built.** `.github/workflows/android.yml`
    builds a signed production APK on `main` only, and uploads it with the
    staging APK. The repository is public, so such an artifact can be
    downloaded by anyone signed in to GitHub for as long as it is kept — 30
    days since N5.12 commit 5b. Before anything reaches `main`, the Owner
    decides: take that step out of the public workflow, or build the
    production APK privately. The old `main` artifacts expire on their
    own; nothing is deleted. **Also a candidate for N5.12c** (the Owner,
    2026-10-07): decide before launch whether CI stops building or
    uploading it.
11. **The production Android API key — restrict it, do not rotate the
    wrong one** (the Owner's decision of 2026-10-07: no action until N8).
    Restricting or rotating the key the PWA uses would break the live PWA.
    In this order:
    1. Google Cloud console, project `smartie-quote-desk` → **APIs &
       Services → Credentials**: list the API keys.
    2. **Find the Android key**: the one whose value is the `current_key`
       in the production `google-services.json` (the
       `FIREBASE_GOOGLE_SERVICES_JSON` secret — never the committed
       placeholder, which N5.12 zeroed).
    3. **Find the browser key the PWA uses**: the `apiKey` in V8C4's own
       Firebase configuration. **Leave it untouched.**
    4. If the two are the same key, **stop** — restricting it to Android
       would break the PWA; ask the Owner.
    5. Otherwise restrict **only the Android key**: Application
       restrictions → Android apps → package `in.smartie.quotedesk` with
       the **release** signing certificate's SHA-1. API restrictions → only
       the APIs the key's own metrics show it calling.
    6. Check sign-in and a quotation on a production build before calling
       it done.

## 6. What the hard-block costs in expressions

Measured for N5.12 commit 4b (`fde9ad3`) against the commit before it
(`d68cf3a`), on the local emulator, from `firestore/tools`:
`node purch.js` (`scenarios.js`), `SCEN=./scenarios-ordered.js node
purch.js` and `SCEN=./scenarios-create.js node purch.js`, every rule padded
(`headroom.js`: cost ≈ (163 − N) × 1000 / 163, about ±6). The same runs with
`PAD_MATCH='/purchase/{id}'` — the per-document figure — give the same
number, write for write: a purchase write evaluates only its own match
block. And the native app writes one purchase document per transaction
(`PurchaseStore`'s single `transaction.set`), so per document and per
request are one figure here. **The stop line for a valid write is 900.**

| Write | uid set: before → after | transition: before → after |
|---|---|---|
| Administrator edits a Manager's requirement | 411 → 405 | 387 → 374 |
| Administrator reopens a received requirement | 479 → 472 | 448 → 442 |
| Primary Owner edits | 393 → 387 | 362 → 356 |
| Manager edits somebody's untouched requirement | 534 → 528 | 503 → 497 |
| Manager changes only the urgency | 534 → 528 | 503 → 497 |
| Manager records a part delivery on somebody's | 601 → 595 | 571 → 564 |
| Manager records a second part delivery | 583 → 577 | 552 → 546 |
| Manager records the whole delivery | 638 → 632 | 613 → 607 |
| Manager writes off a shortfall | 669 → 656 | 638 → 632 |
| Manager removes their own untouched requirement | 571 → 564 | 540 → 534 |
| Manager edits their own untouched requirement | 497 → 485 | 466 → 460 |
| Staff edits their own untouched requirement | 497 → 485 | 466 → 460 |
| Staff changes only the urgency of their own | 497 → 485 | 466 → 460 |
| Staff removes their own untouched requirement | 571 → 564 | 540 → 534 |
| Staff records a part delivery on their own | 583 → 571 | 552 → 546 |
| Staff records a second part delivery on their own | 583 → 571 | 552 → 546 |
| Staff records the whole delivery on their own | 620 → 613 | 589 → 583 |
| Staff writes off a shortfall on their own | 644 → 638 | 613 → 607 |
| Manager delivers on a V8C4 row with no rev or creator | 601 → 595 | 577 → 571 |
| Staff removes their own V8C4-shaped row | 589 → 583 | 558 → 552 |
| Administrator orders | 558 → 552 | 528 → 521 |
| Administrator orders a part-received one | 558 → 552 | 528 → 521 |
| Administrator takes the order back | 472 → 460 | 442 → 436 |
| Manager cancels somebody's | 755 → 748 | 724 → 718 |
| Manager cancels their own | 730 → 724 | 699 → 693 |
| Administrator cancels an Ordered one | 589 → 577 | 558 → 552 |
| Administrator reopens a cancelled one, every stamp removed | 479 → 472 | 448 → 442 |
| Manager records a part delivery on an Ordered one | 601 → 595 | 571 → 564 |
| Manager records the whole delivery on an Ordered one | 638 → 632 | 613 → 607 |
| Manager writes off on an Ordered one | 669 → 656 | 638 → 632 |
| Staff creator writes off on their own Ordered one | 644 → 638 | 613 → 607 |
| Staff creator records the whole delivery on their own Ordered one | 620 → 613 | 589 → 583 |
| Manager changes the note on an Ordered one | 540 → 528 | 509 → 503 |
| Staff creator changes the urgency on their own Ordered one | 497 → 491 | 466 → 460 |
| Administrator changes what and how many on an Ordered one | 411 → 405 | 387 → 374 |
| Administrator removes an Ordered one | 411 → 405 | 387 → 374 |
| Administrator raises a requirement (create) | 190 → 202 | 190 → 202 |
| Manager Person raises a requirement (create) | 190 → 202 | 190 → 202 |
| Staff Person raises a requirement (create) | 190 → 202 | 190 → 202 |

**Every update is 6 to 13 cheaper** — `revOk()` is now one comparison where
it was a `keys()` test and a comparison — and **every create 12 dearer**,
the new `rev == 1` clause. The dearest valid purchase write is still a
Manager cancelling somebody's requirement: **748**, 152 under the stop line.
"uid set" is an access document carrying `primaryOwnerUid`; "transition"
is one without it, where the Owner is found by the email fallback.

## 7. The order on the day

N8 plans the day; this is the order the items above imply:

1. Every precondition in §2 holds.
2. Announce to the staff: Purchase moves to the app; the PWA's Purchase
   stops working.
3. The Owner deploys the production rules — the hard-block goes live.
4. The production APK, signed with the release key and not taken from the
   public CI, goes to the staff.
5. The migration steps (§5.3, §5.5) run with both apps stopped where the
   step says so.
6. The phone checks on production, starting with sign-in, Purchase and a
   quotation.
