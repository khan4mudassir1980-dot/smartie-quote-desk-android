# N4 Purchase Requirements — the plan

Purchase is the last major screen still read-only. `PurchaseScreen` renders requirements
behind an `InDevelopmentBanner(phase = "N4")`, and nothing in the app creates, edits,
completes or removes one. That gap also holds N3's **T-S25** open: the row cannot be
exercised while no requirement can be created.

The useful fact about this phase is how much is already done. `PurchaseRecord` maps the
full V8C4 wire schema, and the v9 rules already define the whole write contract. **N4 is
writers, UI and tests — not schema design.**

## What was decided, and by whom

Settled by the Owner before any code was written:

| Decision | Choice |
|---|---|
| Purchase History | A **read-only** screen at `more/purchase-history`, newest first. No editing. |
| Reopen | **Owner and Administrator only.** Clears `received`, `rcvQty`, `rcvBy`, `rcvUid` and `rcvAt`; status returns to `Needed`. |
| Clear history | **None.** `allow delete: if false` stays untouched. |
| Green urgency label | **"Needed, but not now"**. The wire value stays `normal`. |
| Badge | The Purchase tab carries the count of active requirements. |
| Cancel | **Not in N4.** A `Cancelled` status still *displays* correctly, because the reader already handles it; nothing in the app writes one. |
| Writes | Online-only, as everywhere else in this app. Reads stay cache-backed. |

Two things follow from "no Cancel", and they are deliberate: there is **no generic
`setStatus` operation** anywhere in N4, and `PurchaseWrite` exposes exactly six mutations —
`create`, `edit`, `setUrgency`, `markReceived`, `reopen`, `softDelete`. A status is only ever
moved by the operation that owns it.

## The data contract

**No schema change.** The document id **is** the `id` field: `Keys.generateId("pr_")`.

| Wire field | Type | Create | Update |
|---|---|---|---|
| `id` | string | required, `== docId` | **re-asserted every time** |
| `name` | string | required (app-enforced) | on edit |
| `qty` | **number > 0** | required | **always rewritten numeric** |
| `urgency` | `critical` \| `urgent` \| `normal` | required | on urgency change |
| `status` | string | must be `'Needed'` | `Received` on receive, `Needed` on reopen |
| `note` | string | optional | on edit |
| `by`, `byUid` | string | `byUid == auth.uid` | never rewritten |
| `t` | number (ms) | creation time | never rewritten |
| `updated` | number (ms) | required | required |
| `rev` | number | `1` | **`stored.rev + 1`** |
| `received`, `rcvQty`, `rcvBy`, `rcvUid`, `rcvAt` | bool / number / string / string / number | absent | set on receive, **removed** on reopen |
| `upBy`, `upUid` | string | — | every update |
| `del`, `deletedBy` | bool / string | absent or `false` | **Administrator only** |

### Five traps the rules set, all of them verifiable in the fixtures

Every one of these is a way to write something the rules silently refuse. The first four were
found by reading the rules against `app/src/test/resources/fixtures/purchase.json`. The fifth
was found the honest way, by an emulator test that failed, and its first wording here was
wrong — see below.

1. **A legacy string `qty` makes a row unupdatable.** On update `request.resource.data` is
   the *merged post-state*, so `qty is number && qty > 0` is applied to the **stored** value.
   `pr_received_legacy` holds `"qty": "10"`. Every update must therefore rewrite `qty` as a
   number, which also heals the document for good.
2. **`id` must be re-asserted on every update**, for the same reason:
   `request.resource.data.id == id` sees the merged state, and `toPurchaseRecord()` defaulting
   `id` to the document id is the evidence that rows without an `id` field exist.
3. **No update payload may carry a `del` key.** `touched()` is
   `diff(resource.data).affectedKeys()`, which reports keys **added**. On a row with no `del`
   field, writing `del: false` *adds* the key and trips `!touched().hasAny(['del'])`, refusing
   an ordinary Manager receive. `del` appears only in the create payload and in the
   Administrator-only soft delete.
4. **`updated` is epoch milliseconds, never a server timestamp.** The rules require
   `updated is number`, and a `FieldValue.serverTimestamp()` is not one. `serverAt` stays as
   the separate audit field.
5. **`rev` is mandatory the moment a row has one**, and the rule reads as though it were
   optional. `revOk()` is
   `!request.resource.data.keys().hasAny(['rev']) || request.resource.data.rev == resource.data.get('rev', 0) + 1`,
   and `request.resource.data` is the merged post-state again — so a row already holding
   `rev: 1` still holds it after an update that never mentioned `rev`, the first clause is
   false, and `1 == 1 + 1` refuses the write. **The tolerance covers exactly one case: a row
   that has never carried a `rev` at all.** Every update this app writes therefore sends
   `rev = stored.rev + 1`, read inside a transaction, which is what the rule demands and what
   makes the optimistic guard real. Both halves are pinned in
   `firestore/tests/purchase.test.js` — *once a row carries a rev, omitting it is refused, not
   tolerated* and *a V8C4 row that never had a rev is the one case the tolerance is for*.

   ⚠️ **The consequence for the PWA, recorded rather than fixed.** No fixture carries `rev`, so
   V8C4 never writes one. The moment this app updates a requirement it stamps a `rev`, and from
   then on a PWA update to that row — which omits `rev` — is refused. That costs nothing today,
   because the native app writes only to `smartie-quote-desk-staging` while the PWA runs against
   production. It would matter the day both clients point at one project. It is **not** fixed
   here: N4 changes no rules, and the fix is a rules decision for the Owner (either `rev` is
   dropped from the app's updates, losing the optimistic guard, or `revOk()` grows a
   `rev == old.rev` tolerance, weakening it). Raised now so the cutover plan cannot be surprised
   by it.

### Clearing a field needs its own marker

`resolve()` in the Firestore store **drops nulls rather than writing them**, so reopen cannot
clear the `rcv*` fields by writing null — and writing zeros is wrong, because
`optionalDouble("rcvQty")` would then read `0.0` and the card would show "0 in".

`DeleteField` is the mirror of `ServerTimestamp`: a marker the pure planner can emit and a
test can assert, swapped for `FieldValue.delete()` inside the store.

## Partial receipt, and what the PWA actually does

A requirement is very often delivered in pieces: five of ten arrive on Tuesday and the rest
on Friday. Until Batch C the first of those closed the requirement, because `markReceived`
wrote `received: true` whatever quantity it was given, and the five still outstanding
disappeared off the shop floor's list.

Fixing that means writing a **cumulative** `rcvQty`, which is a shared field, so what the
V8C4 PWA does with it had to be established rather than assumed.

### What the inspector found

`tools/catalogue-import/inspect-v8c4.mjs --purchase` was run by the Owner, read-only, against
the approved V8C4 `index.html` on the Owner's own machine. That file is deliberately not in
this repository, so these are the reported verdicts, recorded here as the answers of record:

| Question | Verdict | What it means here |
|---|---|---|
| Is a partial `rcvQty` shown while a requirement is still open? | `USED_ONLY_WHEN_RECEIVED` | The PWA only ever renders the figure inside a received branch. A partly received row shows no quantity there — it does not show a wrong one. |
| Where does the PWA decide a requirement is finished? | `CLOSURE_FROM_RECEIVED_OR_STATUS` | From `received` / `status`, **never** from `rcvQty > 0`. A row carrying `rcvQty: 5`, `received: false`, `status: "Needed"` therefore reads as **open** in the PWA, which is exactly what a partial receipt has to be. |
| How does the PWA's own receive update the field? | `OVERWRITES` | It writes the figure it was given, discarding whatever total was there. |

The first two make cumulative `rcvQty` **safe to write**: the shape a partial receipt
produces is read correctly by both apps. The third is a one-way incompatibility, and it is
the reason for the restriction below.

### The contract

`qty` stays **the total required**, and is never reduced by a receipt.

| | |
|---|---|
| `rcvQty` | The **cumulative** quantity received, across every receipt. Absent means none. |
| `received` / `status` | `false` / `Needed` until the cumulative total reaches `qty`; `true` / `Received` at that point and not before. |
| Receiving | The panel asks for the quantity that arrived **in this delivery**. The transaction re-reads the stored `qty` and `rcvQty` and writes `stored + now`. |
| Refusals | Zero or less; and more than is still outstanding. Both are sentences decided against the **stored** document, inside the transaction. |
| Editing the required total | Refused below the cumulative received total. Set **equal** to it, and the requirement finalises as fully received in the same write. |
| Reopen | Unchanged: all four `rcv*` fields are removed, so the cumulative total returns to zero and the whole requirement comes back. |
| `ABORTED` | Still never retried automatically. A conflict on a receipt is two people receiving the same delivery, and guessing which is right is the one thing the revision counter exists to stop. |

**A legacy row stays closed.** `isClosed` is `received || status == "Received" || status ==
"Cancelled"` and that does not change, so a V8C4 row carrying `received: true` with
`rcvQty` below `qty` — which the PWA's overwrite makes ordinary — remains closed and is not
reopened by arithmetic. Quantities keep their string coercion: `"10"` reads as `10.0` on both
fields, through the same reader as before.

**No rules change and no index change.** The update rule constrains `id`, `qty`, `updated`,
`rev` and `del`, and says nothing about `rcvQty`, `received` or `status`. A partial payload
and a completing payload are both ordinary updates, and `firestore/tests/purchase.test.js`
proves it against the rules as deployed.

### ⚠️ The production cutover restriction

**The PWA and this app must not both write Purchase requirements in the same Firebase
project.** Two independent reasons, either of which is enough:

1. **The PWA overwrites `rcvQty`.** A PWA receive against a partly received requirement
   replaces the running total with the quantity of that one delivery, so five already
   received are silently lost and the requirement can never close by arithmetic.
2. **The PWA does not follow the `rev` contract** — recorded separately in
   `docs/PROJECT-STATUS.md`. It never writes `rev`, and `revOk()` reads the merged
   post-state, so once this app has stamped a requirement the PWA's next update to that same
   document is refused outright.

This costs nothing today: the native app writes only to `smartie-quote-desk-staging` and the
PWA runs against production. Before a production cutover, **one** of these must happen:

1. update the PWA so that it accumulates `rcvQty` and carries `rev`; or
2. retire the PWA, or make it read-only, and move **all** Purchase writers to the native app
   together.

Splitting the writers across both apps is not a third option, and no partial migration of the
Purchase tab is safe.

## Role matrix

| Action | Owner | Admin | stored `staff` (*Manager*) | stored `worker` (*Staff*) | Enforced by |
|---|---|---|---|---|---|
| Read active | ✅ | ✅ | ✅ | ✅ | Rules |
| Create | ✅ | ✅ | ✅ | ✅ | Rules |
| Edit | ✅ | ✅ | ✅ | ❌ | Rules |
| Change urgency | ✅ | ✅ | ✅ | ❌ | Rules |
| Mark received | ✅ | ✅ | ✅ | ❌ | Rules |
| **Reopen** | ✅ | ✅ | ❌ | ❌ | **Rules, since N4.2** |
| Soft delete | ✅ | ✅ | ❌ | ❌ | Rules |
| Hard delete | ❌ | ❌ | ❌ | ❌ | Rules (`allow delete: if false`) |

**Reopen used to be the one restriction the rules could not express**, and this document said
so from Batch 2 until N4.2. It is expressed now, and not by a special case: a reopen removes
`rcvQty`, and no branch below an Administrator may let a received total fall. The app still
refuses it first, by name, so nobody meets a bare permission error — but the rules are the
enforcement now rather than the documentation of a gap. See `docs/N4.2-plan.md`.

The wider matrix above is the pre-N4.2 one for the operations N4.2 did not touch. **The
current matrix is in `docs/N4.2-plan.md`**, which adds the creator's own window and the
received-record lock.

## Two hardening candidates, both proved unsafe

Neither is adopted. They are written down so nobody re-proposes them.

- **Requiring `rev` on create** would break the PWA. V8C4 never writes `rev`; the tolerance in
  `revOk()` exists for exactly that reason — and, per trap 5, it covers only rows that have
  never had one.
- **Requiring `byUid` to be preserved on update** would break the PWA too, and the proof is in
  this repository: `pr_received_legacy` and `pr_soft_deleted` carry **no `byUid` at all**, so
  `resource.data.byUid` is undefined and the rule would deny every update to them.

## Reads stay one listener

`observeRequirements()` reads the whole `/purchase` collection with one snapshot listener and
filters and sorts client-side. That stays, and the badge and the history screen read the same
flow, so both cost **zero** extra reads.

Every server-side narrowing is unsafe against V8C4 data, and the fixtures show why:
`orderBy("t")` drops rows with no `t` (`pr_critical_no_created` is one); `whereEqualTo("received", false)`
drops rows where the field is absent; `whereIn("status", …)` drops rows with no `status` —
and `toPurchaseRecord()` defaulting `status` to `"Needed"` is the evidence that such rows
exist. **An open requirement that silently vanishes from the shop floor is worse than the
read cost.**

The existing `purchase (status ASC, updated DESC)` index stays unused. It is exactly the index
the split will need on the day it is warranted — at roughly **1,500 documents**, where a cold
start costs ~1,500 reads per device. The fix then is a `closed: true` boolean written by this
app, whose absence we control, plus a one-off backfill. That is N4.x, not N4.

## Batches

| Batch | What | State |
|---|---|---|
| **0** | Record the 20 September staging results; write this plan | Done |
| **1a** | The green urgency label, display only | Done |
| **1b** | `PurchaseWrite` — the pure mutation planner, and its tests | Done |
| **2** | `PurchaseStore` / `PurchaseTransaction` seam, `PurchaseWriteRepository`, `Permissions.canReopenPurchase`, emulator tests | Done |
| **3** | `PurchaseViewModel` and the panels | Done |
| **4** | The Purchase tab rebuilt; **unblocks T-S25** | Done |
| **A** | Card layout — a card's footer no longer overlaps its own text | Done |
| **B** | A listener that dies comes back; a new requirement shows at once | Done |
| **C** | **Partial receipt** — cumulative `rcvQty`, and the edit guards around it | Done |
| **D** | Open requirements ordered by urgency, newest within a colour | Done |
| **4.2** | **Creator self-service** — a Manager or Staff account corrects their own untouched requirement; the record locks on the first receipt. Its own plan: `docs/N4.2-plan.md` | Done, **pending a staging rules deployment** |
| 5 | The read-only Purchase History screen. **Binding: a Staff account sees only rows where `byUid` is their own uid**, and legacy rows with no `byUid` are not in it — see `docs/N4.2-plan.md` | Not started |
| 6 | The bottom-navigation badge, and close-out | Not started |

Batches A to D are the four defects the Batch 4 manual pass found on a phone. They were
audited read-only before a line was changed, and each is a separate commit against a separate
root cause rather than one sweep over the tab.

## Deferred to N4.1

**Raising a requirement from an out-of-stock stock row**, which `docs/N3-plan.md` lists as N4
scope. It couples two screens and two collections onto a phase that already adds a write path,
a rebuilt tab, a history screen and a nav badge, and it opens a question the Owner has not
settled: whether raising twice for the same stock key should merge, and what the requirement is
called when the stock row carries only a model code.

It stays cheap to add **provided `PurchaseDraft` carries a `key` field from day one**, empty
for a typed item. It does. N4.1 is then a `PurchaseDraft.fromStock(record)` factory and one
button — not a schema change.

## Acceptance rows

To be run on a staging build once Batch 6 lands. None has been run.

| Row | Check |
|---|---|
| T-R1 | A Worker adds a requirement; it appears at the top of the active list |
| T-R2 | Its urgency colour and its note are on the card **without opening Edit** — this is N3's **T-S25** |
| T-R3 | A Worker sees Add and no row action at all |
| T-R4 | A Manager edits, changes urgency and marks received; a Manager is offered **no** Reopen and **no** Remove |
| T-R5 | An Administrator reopens a received requirement; it returns to the active list with no received quantity |
| T-R6 | An Administrator removes a requirement; it leaves both lists and never reappears |
| T-R7 | Received items appear in Purchase History, newest first, with no way to edit or clear |
| T-R8 | A Worker is not offered the Purchase history entry |
| T-R9 | The tab badge counts active requirements, and clears when the last one is received |
| T-R10 | The badge does not push the tab label into the system navigation on a three-button phone |
| T-R11 | Offline, every write control is disabled and says why; nothing auto-commits on reconnect |
| T-R12 | **Two phones**, same requirement, both mark received: exactly one succeeds and the other is refused by name, not by a bare permission error. **Pending a second phone**, like T-S5 and T-P13 |
| T-R13 | A requirement created by the PWA is editable and receivable in the native app — the legacy `qty` rescue, on real data |
| T-R14 | Ten are needed and five arrive: the card reads **10 required · 5 received · 5 remaining**, stays in Open, and stays inside its own urgency colour |
| T-R15 | The remaining five arrive: the requirement closes, and the closed card shows **10 in** — not 5 |
| T-R16 | Receiving more than is still outstanding is refused by name, with the outstanding figure in the sentence |
| T-R17 | The required total cannot be edited below what has already arrived; setting it **equal** to what has arrived closes the requirement in the same save |
| T-R18 | Three smaller deliveries against one requirement accumulate rather than replace, and the third closes it |

### What a staging phone pass has actually confirmed

A pass on a physical phone against the run #104 build confirmed **eight
things**, listed line by line in `docs/PROJECT-STATUS.md` under "The Batch C
staging phone pass". In summary: all four defect fixes (card overlap, a new
requirement appearing without a restart, and the red/yellow/green ordering with
newest first inside a colour), and — **in part** — T-R14, T-R15 and T-R18, the
three partial-receipt behaviours. The build showed **Staging**.

**No role-specific row is passed**, T-R16 and T-R17 were not reported, and
**N3's T-S25 remains pending**. A row is passed here only when it has been run
whole and reported.

### What can be run on a phone after Batch 4

The tab writes, so most of the rows above are now performable. **None has been
run.** The rest wait on a later batch or on a second phone:

| Row | Ready after Batch 4? |
|---|---|
| T-R1, T-R2 (**N3's T-S25**), T-R3, T-R4, T-R5, T-R6, T-R11, T-R13 | **Yes** |
| T-R14 to T-R18 | **Yes, after Batch C** — they are the partial-receipt rows and did not exist before it |
| T-R7, T-R8 | No — the Purchase History screen is Batch 5 |
| T-R9, T-R10 | No — the tab badge is Batch 6 |
| T-R12 | No — needs a **second phone**, like T-S5 and T-P13 |

The build to use is the `smartie-native-apks` artifact from the CI run that
verified Batch 4, and it must show **Staging**.
