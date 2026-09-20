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

Every one of these is a way to write something the rules silently refuse. They are listed
here because each was found by reading the rules against `app/src/test/resources/fixtures/purchase.json`,
not by a failing test.

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
5. **`rev` is opt-in in the rules** (`!keys().hasAny(['rev']) || rev == old.rev + 1`), so the
   optimistic guard only works if the app always sends `rev = stored.rev + 1`, read inside a
   transaction.

### Clearing a field needs its own marker

`resolve()` in the Firestore store **drops nulls rather than writing them**, so reopen cannot
clear the `rcv*` fields by writing null — and writing zeros is wrong, because
`optionalDouble("rcvQty")` would then read `0.0` and the card would show "0 in".

`DeleteField` is the mirror of `ServerTimestamp`: a marker the pure planner can emit and a
test can assert, swapped for `FieldValue.delete()` inside the store.

## Role matrix

| Action | Owner | Admin | stored `staff` (*Manager*) | stored `worker` (*Staff*) | Enforced by |
|---|---|---|---|---|---|
| Read active | ✅ | ✅ | ✅ | ✅ | Rules |
| Create | ✅ | ✅ | ✅ | ✅ | Rules |
| Edit | ✅ | ✅ | ✅ | ❌ | Rules |
| Change urgency | ✅ | ✅ | ✅ | ❌ | Rules |
| Mark received | ✅ | ✅ | ✅ | ❌ | Rules |
| **Reopen** | ✅ | ✅ | ❌ | ❌ | ⚠️ **The app only** |
| Soft delete | ✅ | ✅ | ❌ | ❌ | Rules |
| Hard delete | ❌ | ❌ | ❌ | ❌ | Rules (`allow delete: if false`) |

**Reopen is the one restriction the rules cannot express**, and this document says so rather
than implying otherwise. A reopen is an ordinary update, and `staff()` may update. Closing it
in the rules would mean refusing any write that turns `received` from true to false for a
non-Administrator — which is only safe once somebody has confirmed that the V8C4 PWA never
offers a Manager an un-receive. That question needs `tools/catalogue-import/inspect-v8c4.mjs`
and the Owner's machine; until it is answered, the restriction lives in `Permissions` alone.

## Two hardening candidates, both proved unsafe

Neither is adopted. They are written down so nobody re-proposes them.

- **Requiring `rev` on create** would break the PWA. V8C4 never writes `rev`; the opt-in shape
  of `revOk()` exists for exactly that reason.
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
| 2 | `PurchaseStore` / `PurchaseTransaction` seam, `PurchaseWriteRepository`, `Permissions.canReopenPurchase`, emulator tests | Not started |
| 3 | `PurchaseViewModel` and the panels | Not started |
| 4 | The Purchase tab rebuilt; **closes T-S25** | Not started |
| 5 | The read-only Purchase History screen | Not started |
| 6 | The bottom-navigation badge, and close-out | Not started |

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
