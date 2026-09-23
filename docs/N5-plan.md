# N5 — Quotation

*The plan of record for N5. `docs/PROJECT-STATUS.md` remains the single
statement of what is true now; this file says what this phase set out to do,
what the Owner decided, and why each decision was taken.*

## Why

Quotations are the last thing keeping the PWA in daily use. Before N5 the
native app could **read** a quotation and nothing else: there was no write path
for `/quotations`, `/customers` or `/teamSettings/numbering`, and the
Quotations tab told people to keep using the PWA.

Two things make this harder than N4 (Purchase):

1. **One shared counter.** `/teamSettings/numbering` holds a single `next`, and
   the rules enforce `next == resource.data.next + 1` exactly. Every quotation
   in the business serialises through that one document, and during cutover the
   PWA and the native app both move it.
2. **N5 is not a port.** Area (per sq ft) pricing, installation and discount do
   not exist in V8C4 at all. They are new behaviour added *without* stopping
   V8C4 reading what the native app writes.

Testing policy: no phone testing between batches, one staging pass at the end,
CI green on every push.

---

## Who may do what

The Owner's matrix. Wire values never change: `owner` / `admin` /
`staff` = **Manager** / `worker` = **Staff**.

| | Owner | Administrator | Manager | Staff |
|---|---|---|---|---|
| Quotation tab and history | ✅ | ✅ | ✅ | ❌ no access at all |
| Create a quotation | ✅ | ✅ | ✅ | ❌ |
| See whose quotations | everyone's | everyone's | **only their own** | — |
| Edit / cancel a finalised quotation | anyone's | anyone's | **their own only** | ❌ |
| Parties: add, correct details | ✅ | ✅ | ✅ | ❌ |
| Parties: rename, archive | ✅ | ✅ | ❌ | ❌ |
| Products and prices | ✅ | ✅ | ❌ | ❌ |
| Settings (numbering, discount cap) | ✅ **only** | ❌ | ❌ | ❌ |
| Discount | uncapped | uncapped | **capped by the Owner's limit** | — |
| Rate tiers offered | Dealer, Client | Dealer, Client | Dealer, Client | — |

**Parties are shared.** A Manager sees and uses every party, so they can quote
against a customer somebody else entered.

**History filtering is app-level**, as `PurchaseHistory` is: Firestore
evaluates a list query against its *constraints* rather than document by
document, so a read rule mentioning `resource.data` refuses the unconstrained
listener outright, and a `where('byUid','==',mine())` query would refuse the
PWA's own listener and drop every legacy row with no author. The rules-level
version is deferred until the PWA is retired. Nothing in this repository may
describe the app filter as though the rules enforced it.

**A blank `byUid` belongs to nobody.** `"" == ""` would hand every authorless
PWA row to whoever happened to be signed in. Such a row appears only for an
Owner or an Administrator.

---

## The contractor tier is not offered, and never deleted

New quotations offer **Dealer and Client only**. The Contractor option is not
shown.

**Reading stays complete.** Quotations already issued at the contractor tier
must display in full, and the `contractor` rate column in product data is
preserved. N5.7 does this **without a rule change**: the editor does not offer
the contractor rate as a box at all, and its complete write carries the stored
value through untouched — including a stored `null`, which is V8C4's
deliberate "Price not set" rather than an absence to tidy away. An earlier
draft of this plan proposed a `contractorKept()` rule guard; it was dropped,
because a rule cannot distinguish an edit that drops a field from one that
never had it, and the editor writing every field makes the guard unnecessary.
A screen test opens the finalised contractor-tier fixture and asserts its tier
and totals still read.

---

## One discount, and what it may not touch

- **One per quotation**, typed as a percentage or as a rupee amount.
- It applies to **products plus installation**, and **never to transport**.
  Carriage is what it costs to get the goods to site; discounting it means
  quoting a delivery below what it is about to cost.
- A **Manager is capped** by a limit the Owner sets, enforced in the rules as
  well as in the app. Owner and Administrator are uncapped.
- The refusal **names the figure the person may have** — "The most you can
  discount is 10% (₹9,483)" — rather than silently clamping what they typed. A
  quotation that went out at a discount nobody chose is worse than one that
  would not save.

**Hand-typed line rates are outside the cap by design.** The Owner accepted
this. A Manager may still quote a line below its catalogue rate, so the cap is
a **guardrail, not a proof**, and the KDoc on `QuoteMath.discountRefusal` says
so in those words.

---

## Manual lines, and where transport lives

Manual lines already exist in V8C4: a title, a description, a quantity, an
amount, unit `no`, and `manual: true` with no product key.

**Transport is a manual line, not a field.** V8C4 pushes it into `lines[]` as
"Transportation" and counts it in the subtotal, so that is where it appears —
tagged as typed by hand. The read-only detail built in N5.4 shows it exactly
that way.

---

## Area pricing, which is new

W × H in **mm or ft, mm the default**; 1 ft = 304.8 mm; plus a door count.

1. The measured area is rounded **up to the next half square foot**.
2. **Then** the product's minimum chargeable area applies, if it sets one.

**The order is the whole of it**, and it is invisible until the minimum is not
itself a multiple of a half: 9.0 sq ft against a 10.3 minimum charges 10.3 this
way and 10.5 the other way. There is a test that can tell the two apart, which
the obvious cases cannot.

**An exact measurement must not move.** A 10 ft × 8 ft shutter is 80 sq ft, and
so is the same opening entered as 3048 × 2438.4 mm — but the millimetre
arithmetic lands a hair either side of 80 and a bare `ceil` would sell half a
square foot that was never measured. The rounding goes through `BigDecimal`
quantised to six decimals: far finer than any site measurement, far coarser
than the noise.

**A line stores `qty` as the total chargeable area, not the door count.** V8C4
knows nothing about `nos`: it prints `qty` against `rate` and falls back to
`qty × rate` when an amount is missing, so the two must agree or a natively
built line reads wrong in the PWA. The door count travels separately, and the
working — `3000 × 3500 mm = 113.5 sq ft × ₹450 × 2 nos` — goes into the line's
`s` (spec) field, which V8C4 already prints.

---

## Installation, in four modes

Optional, one per quotation, the rate typed by hand:

| Mode | Rate applied to | Default basis |
|---|---|---|
| `fixed` | — | the rate *is* the amount |
| `door` | the door count | quantity of the area lines |
| `sqft` | chargeable square feet | total chargeable sq ft |
| `pct` | a percentage | the products subtotal **before** discount |

The basis is defaulted from the lines and then **editable**, which is why it is
stored rather than re-derived: a quotation reopened a month later must show the
figure the price was actually struck on.

**When installation is absent the PDF prints "Installation extra".**

---

## The totals order, and whole rupees

Fixed, and not the obvious order:

1. products + installation
2. − discount
3. + transport
4. + GST on that whole amount

So **GST falls on transport** as well, which is V8C4's behaviour and is kept
deliberately — the finalised fixture carries a manual Transportation line
inside its subtotal with 18% charged on the lot. Discount, by contrast, stops
before transport.

**Every stored figure is whole rupees, HALF_UP, rounded as it is computed**,
and each later figure is built from the already-rounded earlier ones. That is
not cosmetic: it keeps the printed page adding up, keeps both apps showing the
same numbers, and makes the bound the security rules check —
`discBase ≤ subtotal + discount`, because transport is never negative — exact
integer arithmetic rather than something that drifts by a rupee.

---

## Editing a finalised quotation

- **The creator, and an Owner or Administrator on anyone's.**
- An edit **overwrites the same number**; there is no revision copy.
- `id`, `no`, `at` and `byUid` stay pinned.
- Every edit carries a stamp, and the PDF prints **"Last edited by &lt;name&gt;,
  &lt;date time&gt;"**.
- **`snap{}` is never re-frozen.** It records what the terms, validity and bank
  block were *at issue*, and the number is not reissued. An edit moves `lines`,
  `install`, `disc`, `discBase`, `subtotal`, `total` and the stamp, and nothing
  else.

**Drafts are device-local.** V8C4 lists `Draft` as a status, but the deployed
create rule requires `no is string` and `total is number`, so a numberless
draft would be refused. `QuoteDraft` / `QuoteDraftCodec` already persist a
draft on the device; the id is minted when the draft opens, so the idempotent
retry still works, and no half-built quotation appears to anybody else.

---

## The printed order

Screen, PDF and the WhatsApp text all follow this sequence. V8C4's order with
the new money lines inserted:

1. Quotation no
2. Date
3. **Last edited by &lt;name&gt;, &lt;date time&gt;** — only when an edit stamp exists
4. Client, contact
5. Item table
6. **Products subtotal**
7. **Installation** — or the words **"Installation extra"** when absent
8. **Discount**
9. Transportation
10. **Subtotal**
11. GST %
12. **Grand total**
13. Validity days, payment terms, warranty, notes, terms, bank details, footer

---

## What N5.1 found, and which batch answers it

N5.1 changed no rule. It wrote emulator tests against the rules exactly as
deployed, and two of them found something.

### 1. A contended issue is refused as `permission-denied`, and the SDK will not retry it

A Firestore transaction retries itself when the **server** aborts it for
contention. Here the security rule is doing the concurrency check, so a
transaction whose read of `next` went stale is rejected by the rules *before*
the server's own optimistic-concurrency check ever aborts it — and
`permission-denied` is not a retryable status, so the SDK gives up.

Measured over twenty contended pairs on the emulator: two separate client apps
both got through half the time and lost one the other half; two concurrent
transactions inside one app lost one **every** time.

**Answered by N5.9**, which retries the whole transaction itself, bounded, on
`permission-denied` against the counter. The approved plan assumed the SDK's
own retry covered this. It does not.

### 2. An Administrator can re-take a number that is already gone

The counter's second update branch exists for configuration and asks only for
`next >= resource.data.next`. An Administrator issuing from a stale read writes
`next: 10` when the stored value is already 10, `10 >= 10` holds, the write is
accepted — and two people hold the same quotation number while `lastIssued`
names the loser. A Manager cannot do this, because a Manager never reaches that
branch, which is why the contention tests use two Managers.

**Closed in N5.6** (`f61eebc` and `74ff81e`), where the characterisation test
flipped from `assertSucceeds` to `assertFails`. What actually closes it is not
what this plan predicted — see the N5.6 entry below.

---

## The rules diff, batch by batch

Every change is additive, a widening, or — for the two narrowings — confirmed
harmless because **no Administrator account exists in staging or production**.

### Done: N5.0b — `/users`, an Administrator acts on Manager and Staff only

The update branch and the delete branch both narrow from
`resource.data.role in ['admin','staff','worker']` to `['staff','worker']`.

**V8C4 verdict — safe.** A narrowing, and no Administrator account exists in
either project, so it interrupts nobody. That window closes the moment one is
created, which is why it was taken first.

### Done: N5.6 — `/teamSettings/numbering` and `/teamSettings/quoting`

Numbering **configuration** moves from `admin()` to `owner()`. The **issue**
branch (`next + 1` plus `lastIssued`) stays open to every quoting role, because
V8C4 needs it.

**What shipped is not what this section originally said, and the difference
matters.** The plan proposed two guards on the configuration branch: a
strictly greater `next` unless the financial year changes, *and* a requirement
to be touching something other than `next` and `lastIssued`. The first shipped
alone in `f61eebc` and was wrong; both together would also have been wrong.

```
&& !touched().hasAny(['lastIssued'])
&& (request.resource.data.fy != resource.data.fy
    || !touched().hasAny(['next'])
    || request.resource.data.next > resource.data.next)
```

- **Strictly greater on every configuration write made a prefix or padding
  correction impossible.** Leaving `next` where it is was refused along with
  moving it backwards, so renaming `SIE/QD` would have cost a real quotation
  number. Found by the Settings screen test, which does exactly that edit.
- **`!hasOnly(['next','lastIssued'])`, the plan's other half, would have
  refused a `next`-only correction** — an Owner moving the counter forward and
  changing nothing else.

**N5.6b settled which guard does what, by ablation rather than by argument.**
An earlier version of this section claimed strictly-greater "left a gap
regardless" and credited the `lastIssued` guard with closing the original
finding. That was wrong, and the ablation proved it:

| Rule variant | `{next: 10, lastIssued, updated}` against a stored `next: 10` |
|---|---|
| Strictly-greater alone, as first shipped | **refused** |
| Current rule minus `!touched().hasAny(['lastIssued'])` | **allowed** |
| Current rule, both guards | **refused** |

So:

- **Strictly greater closes N5.1's second finding**, on its own. Removing it
  alone fails `an Owner rolls the financial year, and never rewinds inside one`
  and `a forward correction is allowed and rewinding is not`.
- **`!touched().hasAny(['lastIssued'])` closes the gap that the
  `!touched(['next'])` prefix-correction escape hatch opens.** That hatch is
  what allows a prefix to be corrected without burning a number; with it
  present and this guard gone, a stale re-stamp that leaves `next` alone walks
  straight through. Removing it alone fails `a number that is already spent
  cannot be re-taken, by anybody` and `but configuration may never stamp
  lastIssued, whatever else it does`.

Neither ablation breaks nothing, so both guards are tested.

```
function numberingSrcOk() {
  let src = request.resource.data.get('lastIssued', {}).get('src', '');
  return src is string && src.size() <= 16;
}
```

`src` is **read but never required**, so V8C4's `fbFinaliseAtomic` — which
writes `lastIssued` without it — still passes.

New document `/teamSettings/quoting`, holding `managerDiscountPct`, written by
`owner()` only.

**V8C4 verdict — issue path compatible, configuration path a deliberate
narrowing.** `fbSaveNumbering` writes `{prefix, fy, next, pad, updated, by}`
through a merge transaction: `touched()` includes `prefix`/`pad`/`updated`/`by`
so it falls through to the configuration branch, every type check holds, and
the extra keys are permitted because that branch carries no `hasOnly`. **An
Owner's `fbSaveNumbering` write passes**, and an emulator test pins exactly
that shape. The narrowing itself is safe for the reason above.

### Done: N5.6b — `pad` and `fy` bounded to V8C4's own limits

The configuration branch type-checked `next`, `prefix` and `fy` but not `pad`,
and bounded none of them by shape. Added, **on the configuration branch only**
— issuing a number must never fail because a stored configuration value is
stale:

```
&& request.resource.data.fy.matches('^[0-9]{4}-[0-9]{2}$')
&& request.resource.data.pad is number
&& request.resource.data.pad >= 1 && request.resource.data.pad <= 6
```

`pad` is 1–6 because that is what V8C4 clamps to on both of its save paths
(`Math.min(6, Math.max(1, pd||3))`) and what its inputs allow. A pad of 7 set
natively would have appeared in that `max="6"` input and been silently
rewritten to 6 on the PWA's next settings save, changing the printed number
format with nobody asking.

**The prefix pattern was held for one batch, and shipped in N5.6c.** The
first pattern offered — `^[A-Za-z0-9][A-Za-z0-9-]{0,11}$` — rejects `SIE/QD`,
which is the prefix **the hand-built test fixtures hold** (committed 15–17
September; `app/src/test/resources/fixtures/README.md` records what they are
and are not). An earlier draft of this section called them an export of
production data. They are not, and **no production export exists in this
repository**. The Owner then confirmed from the live file why `SIE/QD` is
stored at all: only one of V8C4's two save paths applies a pattern, and the
numbering dialog validates "not blank" alone.

N5.6c ships `^[A-Za-z0-9][A-Za-z0-9/-]{0,15}$`, which admits `/` because a
quotation number is `{prefix}/{fy}/{n}` and the prefix is itself two
segments.

**`allow create` is still unbounded.** Seeding a fresh counter checks
`pad is number` but not its range, and does not check the `fy` shape. Narrow,
Owner-only, and reachable only on an unseeded project — recorded rather than
silently extended, because the instruction was the configuration branch only.

### N5.7 — `/products`, the minimal edit

**No rule change at all.** The deployed rule validates the *merged post-state*
rather than the keys an update touches, so a document an older PWA version
left with `gst` as a string refuses even a correction that does not go near
it — there is no partial fix, and weakening the rule to allow one would be the
wrong trade. Instead the editor writes a **complete, correctly typed**
document every save, exactly as `fbPushProduct` does, and the legacy defects
repair themselves on the way through. Both halves of that are pinned in
`firestore/tests/catalogue.test.js`.

Pricing type is the **existing `unit` field**, a free text box, with the value
`"per sq ft"` — V8C4's own spelling. `minSqft` is additive and the rule does
not name it.

Two things that block a rewrite rather than being guessed at: a stored price
that is neither blank, nor the `∅` marker, nor a number is refused rather than
written back as "not set"; and a seed model readable only from a sanitised
document id whose model half contains a `_` is refused, because a `/` and a
`.` both occur in live models and a wrong seed model makes V8C4 materialise a
new custom item.

**V8C4 verdict — compatible, established rather than assumed.**
`fbPushProduct` writes `contractor: rate(gid, m, "contractor").v` on every
product save with `{merge: true}`, so the incoming document always carries a
number. An earlier draft froze the value and would have refused a legitimate
PWA rate change; that is corrected above.

### N5.9 — `/quotations` create, and the discount cap

```
function qnDiscountAmount() {
  return request.resource.data.get('disc', {}).get('amt', 0);
}
function qnDiscountCap() {
  return exists(/databases/$(database)/documents/teamSettings/quoting)
    ? get(/databases/$(database)/documents/teamSettings/quoting)
        .data.get('managerDiscountPct', 0)
    : 0;
}
// `discBase` is client-supplied, so on its own it would let a Manager clear
// the cap by inflating the base. It is bounded against the figures stored
// beside it: transport is never negative, so the base can never exceed
// subtotal + discount. Every stored figure is a whole rupee, so the
// arithmetic is exact; the single rupee of slack absorbs a rounding order
// that differs by one.
function qnDiscBaseSane() {
  return request.resource.data.get('discBase', 0) is number
    && request.resource.data.discBase >= 0
    && request.resource.data.subtotal is number
    && request.resource.data.discBase
       <= request.resource.data.subtotal + qnDiscountAmount() + 1;
}
function qnDiscountWithinCap() {
  return qnDiscountAmount() is number
    && (qnDiscountAmount() == 0
        || admin()
        || (qnDiscountAmount() > 0
            && qnDiscBaseSane()
            && qnDiscountAmount()
               <= request.resource.data.discBase * qnDiscountCap() / 100));
}
```

**V8C4 verdict — compatible.** The predicate is **gated on the discount being
present**, and V8C4 writes no `disc` key, so a PWA quotation short-circuits
before any `get()` and before `subtotal` is type-checked — which matters,
because the `q_string_totals` fixture stores `subtotal` as the string
`"10500"`. A missing `/teamSettings/quoting` therefore cannot refuse a PWA
write. A Manager writing a discount with the document missing gets cap `0` and
is refused: safe by default.

### N5.10 — `/quotations` update, edit and cancel

```
function qnCreator() {
  return resource.data.get('byUid', '') != '' && resource.data.byUid == mine();
}
function qnIdentityPinned() {
  return request.resource.data.id == resource.data.id
    && request.resource.data.no == resource.data.no
    && request.resource.data.at == resource.data.at
    && request.resource.data.byUid == resource.data.byUid;
}
function qnEditStamped() {
  return request.resource.data.get('editedByUid', '') == mine()
    && request.resource.data.editedAt is number;
}
function qnCancelKeys() {
  return touched().hasOnly(['status','cancelledBy','cancelledAt'])
    && request.resource.data.status == 'Cancelled';
}
```

```
allow update: if member() && !worker()
              && (admin() || qnCreator())
              && (
                qnCancelKeys()
                || (qnIdentityPinned() && qnEditStamped()
                    && request.resource.data.total is number
                    && qnDiscountWithinCap())
              );
```

**V8C4 verdict — compatible.** Every clause is a *widening*: today the rule is
`admin() && hasOnly([cancel keys])`, and `admin() && qnCancelKeys()` is the
same set, so a PWA cancel passes through untouched.

### `/customers` — no rules change at all

The party writer N5.5 built is already permitted by the deployed rule. A
Manager may correct details, may not rename, and may neither archive nor
unarchive — and cannot touch an archived party at all, because the `staff()`
branch requires it was not archived to begin with.

### Indexes — none are added

`quotations [partyId ASC, at DESC]` already exists. The Manager history filter
is app-level, so the listener stays unconstrained and needs no index.

### Anything not V8C4-compatible?

**No.**

---

## The data shape

All additive. V8C4 ignores unknown keys, so it keeps reading native records;
the native readers already tolerate absent fields, string-vs-number variants
and the contractor tier, so they keep reading V8C4 records.

### `/customers/{id}` — V8C4's fields exactly, and no new ones

`id`, `name`, `type`, **`city`** (never `site`), `gstin`, `contact`, `phone`,
`email`, `address`, `notes`, `archived`, `t`, `by`, `byUid`, `updated`,
`upBy`, `upUid`. `type` is `dealer` / `contractor` / `client`, defaulting to
`client`. A test pins the whole key set so a parallel field cannot appear.

### `/products/{id}`

| Key | Type | Meaning |
|---|---|---|
| `unit` | string — **existing field**, value `"per sq ft"` | Pricing type. No new field, and a free text box, not a menu. |
| `minSqft` | number, optional | Minimum chargeable area per door |

**The spelling is V8C4's, not ours.** `per sq ft` is what the price book
already uses for the products this exists to serve. Matching folds case on the
already-trimmed value and admits nothing else, so `Per sq ft` is area-priced
and `sqft` is not — such a product is priced per piece, which shows on its
list row and is one edit away from being right. A guess would be the silent
option; this one is visible.

**Caveat recorded rather than discovered later:**
`tools/catalogue-import/verify-staging.mjs` compares `unit` field-for-field,
so a unit corrected in the native app reads as drift against the seed until a
seed is regenerated with the fixed extractor. A reporting nuisance, not data
loss — and **no import may be run to resolve it**: the importer carries seed
rates in every payload and would revert any rate edited since the last run.
See the blocking warning in `docs/PROJECT-STATUS.md`.

### `/quotations/{id}`

Existing keys unchanged. New:

| Key | Type | Meaning |
|---|---|---|
| `install` | map, optional | `{mode, rate, amt, basis}` — mode ∈ `fixed`/`door`/`sqft`/`pct` |
| `disc` | map, optional | `{kind, value, amt}` — kind ∈ `pct`/`amt` |
| `discBase` | number, optional | Products + installation, the figure `disc.amt` was taken against. **Exists so the rules can check the cap**, and is bounded there |
| `editedBy` / `editedByUid` / `editedAt` | string / string / number | The "Last edited by …" stamp |
| `lastIssued.src` | string, ≤16 chars | Inside the counter document: `"android"` or `"pwa"` |

### A quotation line

| Key | Type | Meaning |
|---|---|---|
| `w` / `h` | number | Opening width and height, as typed |
| `dim` | string | `"mm"` (default) or `"ft"` |
| `sqft` | number | Chargeable area **per door**, after rounding and minimum |
| `nos` | number | Door count |

with `qty` = total chargeable sq ft, `rate` = the per-sq-ft rate, `amt` =
`qty × rate`, `u` = `"per sq ft"` — the product's own unit, copied.

### Where V8C4 will still look different

Not fixable by field naming, and stated plainly:

1. A **discounted** quotation opened in V8C4 shows the correct stored `total`
   but no discount line; its arithmetic will not visibly reconcile.
2. **Installation** is the same: correct total, no line explaining it.
3. **"Last edited by"** does not appear in V8C4's PDF.

All three are arguments for a short cutover, not a different data shape.

---

## Worked examples

Every stored figure is HALF_UP to whole rupees as computed, and `subtotal` is
derived from the already-rounded figures.

### A — % discount, installation per door, transport, GST, with an area line

| Step | Figure |
|---|---|
| Sliding gate motor 1000 kg — 2 × ₹22,200 | ₹44,400 |
| Rolling shutter — 3000 × 3500 mm = **113.5 sq ft**/door × 2 nos = 227 sq ft × ₹450 | ₹1,02,150 |
| **Products** | **₹1,46,550** |
| Installation — per door, ₹1,500 × 2 | ₹3,000 |
| **Products + installation** (`discBase`) | **₹1,49,550** |
| − Discount 10% | − ₹14,955 |
| | ₹1,34,595 |
| + Transport | + ₹2,500 |
| **Subtotal (GST base)** | **₹1,37,095** |
| + GST 18% | + ₹24,677 |
| **Grand total** | **₹1,61,772** |

3000 × 3500 mm = 113.0211 sq ft → up to the next 0.5 → **113.5**.

### B — ₹ discount, installation as a percentage of products

| Step | Figure |
|---|---|
| Toughened glass 12 mm — 120 sq ft × ₹145 | ₹17,400 |
| Manual line "Site measurement visit" — 1 × ₹2,000 | ₹2,000 |
| **Products** | **₹19,400** |
| Installation — 8% of products | ₹1,552 |
| **Products + installation** | **₹20,952** |
| − Discount ₹2,000 (flat) | − ₹2,000 |
| | ₹18,952 |
| + Transport | + ₹800 |
| **Subtotal** | **₹19,752** |
| + GST 18% | + ₹3,555 |
| **Grand total** | **₹23,307** |

### C — installation per sq ft, and a Manager held to the cap

| Step | Figure |
|---|---|
| Shutter — 2400 × 2100 mm = **54.5 sq ft**/door × 3 nos = 163.5 sq ft × ₹520 | ₹85,020 |
| **Products** | **₹85,020** |
| Installation — ₹60/sq ft × 163.5 | ₹9,810 |
| **Products + installation** | **₹94,830** |
| Manager types 15%; the Owner's cap is 10% → **refused**, cap named | − ₹9,483 |
| | ₹85,347 |
| Transport off | ₹0 |
| + GST 18% | + ₹15,362 |
| **Grand total** | **₹1,00,709** |

2400 × 2100 mm = 54.2501 sq ft → **54.5**. With transport zero the rules' bound
is tight: 94,830 ≤ 85,347 + 9,483 + 1 = 94,831.

### D — the product minimum biting, with a fixed installation charge

| Step | Figure |
|---|---|
| Vision panel — 600 × 900 mm = 5.8125 sq ft → up to **6.0** → product minimum **10** → 10 sq ft × 1 no × ₹700 | ₹7,000 |
| Installation — fixed | ₹1,200 |
| **Products + installation** | **₹8,200** |
| No discount, no transport | — |
| + GST 18% | + ₹1,476 |
| **Grand total** | **₹9,676** |

Rounding **then** minimum — 6.0 → 10, not 5.8125 → 10.

**Feet:** 10 ft × 8 ft = 80.0 sq ft exactly and stays 80.0; an exact multiple
of 0.5 must not be lifted to 80.5.

---

## The batches

| # | Batch | Rules? | State |
|---|---|---|---|
| **N5.0** | Dead code out — `QuotationPdf.kt`, `Models.kt` | No | **Done**, `ccb4a25`, CI #126 |
| **N5.0b** | Administrator acts on Manager and Staff only | Yes | **Done**, `ba2db27`, CI #126 |
| **N5.1** | Rules tests only, no rule text change | No | **Done**, `9d3ab3a`, CI #126 |
| **N5.2** | Area, installation and discount arithmetic | No | **Done**, `15d6ce3`, CI #127 |
| **N5.3** | Parties, read side | No | **Done**, `978a495` + fix `d6bf5d5`, CI #130 |
| **N5.4** | Quotation history and detail, read-only | No | **Done**, `4fe76dd` + fix `38d32a6`, CI #130 |
| **N5.5** | Parties, write side | No | **Done**, `f2bcd3f` — CI pending at the time of writing |
| **N5.6** | Settings, Owner-only: numbering and the discount cap | **Yes** | **Done**, `f61eebc` + `74ff81e` + `a849650`, CI #137 |
| **N5.7** | Minimal product edit — `unit`, dealer/client rate, `minSqft` | **No** | Done |
| **N5.8** | The quotation builder, draft only | No | To do |
| **N5.9** | Finalise — one transaction, idempotent retry, `src`, `snap`, party snapshot | **Yes** | To do |
| **N5.10** | Edit and cancel, with the last-edited stamp | **Yes** | To do |
| **N5.11** | PDF, WhatsApp and Print in the printed order above | No | To do |
| **N5.12** | `docs/N5-cutover.md`, and **only here** does the banner come out | No | To do |

Rules deploy **once**, at the final staging pass, with one APK — not per batch.

### What N5.8 owes N5.5

The quotation side's "Save this customer" uses `PartyWrite.mergeInto`, which is
already written and tested: **fill gaps, take genuine changes, never blank a
detail already held.** That is the opposite of the Parties editor, which stores
exactly what is on screen so a wrong GSTIN can be removed. Both live in
`PartyWrite` so the difference is a tested function rather than a sentence
somebody has to remember.

---

## The PWA

The approved V8C4 `index.html` is deliberately not in this repository. Every
V8C4 fact above was read from the Owner's own file by the advisor — structure
only, never rates or company data — or is evidenced by the exported fixtures
and the deployed rules. `tools/catalogue-import/inspect-v8c4.mjs` has **no
quotation capability**: its four questions are stock, movement shape,
canonicalisation and reorder level. Anything further about the PWA's quotation
behaviour needs new patterns written before it can be established first-hand.

V8C4 keeps issuing quotations until N5.12. Nothing in N5 is deployed to
production, and no batch writes to it.

---

## Deployment

One staging pass at the end, one APK and one rules deploy, run by the Owner —
never from this branch, and never to production:

```powershell
cd firestore
firebase.cmd deploy --only firestore:rules --project smartie-quote-desk-staging
```

Deploy from the **last CI-verified head** named in `docs/PROJECT-STATUS.md`,
not from a hash remembered from an earlier batch: N5.0b changed `/users`, so
the ruleset has already moved once since N4.4.

**No acceptance row is passed until it has been run on a phone.** An automated
test passing is not a pass in `docs/PHONE-TEST-CHECKLIST.md`.
