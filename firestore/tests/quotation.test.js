const test = require('node:test');
const assert = require('node:assert/strict');
const { assertFails, assertSucceeds } = require('@firebase/rules-unit-testing');
const { createTestEnvironment, seed, as, UIDS } = require('./helpers');

/**
 * Quotations, parties, and the one counter the whole business queues behind.
 *
 * **Nothing here changes a rule.** This file is written first, against the
 * rules exactly as deployed, so that what they already do is written down
 * before N5 builds a writer on top of them. Three tests in `data.test.js`
 * cover the plain role matrix for these collections; what is here is the part
 * that only shows up under contention, or against the shapes V8C4 writes.
 *
 * The counter is the reason this file exists. `/teamSettings/numbering` holds
 * a single `next`, and the rule is `next == resource.data.next + 1` —
 * **exactly**, with no skipping. Every quotation in the business serialises
 * through that one document, and during cutover the PWA and the native app
 * both move it. Two properties matter and both are asserted below: a client
 * working from a stale read cannot take a number that is already gone, and a
 * retry of a quotation that was already written cannot advance the counter a
 * second time.
 *
 * Titles, for reading this file: stored `staff` is displayed **Manager**,
 * stored `worker` is displayed **Staff**.
 */

let testEnv;

test.before(async () => { testEnv = await createTestEnvironment(); });
test.after(async () => { await testEnv?.cleanup(); });
test.beforeEach(async () => { await seed(testEnv); });

const quotations = (db) => db.collection('quotations');
const customers = (db) => db.collection('customers');
const numbering = (db) => db.collection('teamSettings').doc('numbering');

/** The counter as V8C4 seeds it, planted with the rules disabled. */
async function givenCounter(fields = {}) {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('teamSettings').doc('numbering').set({
      prefix: 'SIE/QD', fy: '2025-26', next: 9, pad: 3, ...fields,
    });
  });
}

/** The Owner's discount limit, planted with the rules disabled. */
async function givenCap(managerDiscountPct) {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('teamSettings').doc('quoting')
      .set({ managerDiscountPct });
  });
}

/** What the counter actually holds right now, rules bypassed. */
async function counter() {
  let stored;
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const snapshot = await context.firestore().collection('teamSettings').doc('numbering').get();
    stored = snapshot.data();
  });
  return stored;
}

/** A finalised quotation in the PWA's own shape. */
const quotation = (id, uid, extra = {}) => ({
  id,
  no: 'SIE/QD/2025-26/009',
  at: Date.now(),
  by: 'Manager Person',
  byUid: uid,
  tier: 'client',
  tierName: 'Client',
  partyId: 'c_1',
  party: { name: 'Sunrise Constructions', city: 'Mumbai' },
  lines: [{ t: 'Sliding gate motor', u: 'each', qty: 2, rate: 22200, k: 'gateMotors|SIE1000', amt: 44400 }],
  gst: true,
  gstPct: 18,
  subtotal: 44400,
  total: 52392,
  status: 'Finalised',
  snap: { gstPct: 18, validityDays: 15 },
  ...extra,
});

/** One issued number: the quotation and the counter bump, as one commit. */
function issue(db, uid, { id, next, no }) {
  const batch = db.batch();
  batch.set(quotations(db).doc(id), quotation(id, uid, { no }));
  batch.update(numbering(db), {
    next,
    lastIssued: { no, at: Date.now(), by: 'Manager Person', uid },
  });
  return batch.commit();
}

// --- creating a quotation ---------------------------------------------------

test('a quotation must carry the document id it is stored under', async () => {
  const db = as(testEnv, UIDS.staff);
  await assertFails(quotations(db).doc('q_1').set(quotation('q_somewhere_else', UIDS.staff)));
  await assertSucceeds(quotations(db).doc('q_1').set(quotation('q_1', UIDS.staff)));
});

test('and cannot be attributed to somebody else', async () => {
  // The author is the one fact a later edit is decided against, so it is
  // pinned to the caller at the moment it is written and never taken on trust.
  const db = as(testEnv, UIDS.staff);
  await assertFails(quotations(db).doc('q_1').set(quotation('q_1', UIDS.admin)));
  await assertFails(quotations(db).doc('q_1').set(quotation('q_1', '')));
});

test('a total that is not a number is refused outright', async () => {
  // `q_string_totals` in the Kotlin fixtures is a real V8C4 row holding
  // `"total": "12390"`. The reader copes with it; the rules will not accept a
  // new one, because a string total cannot be compared by any rule after it.
  const db = as(testEnv, UIDS.staff);
  await assertFails(quotations(db).doc('q_1').set(quotation('q_1', UIDS.staff, { total: '52392' })));
  await assertFails(quotations(db).doc('q_1').set(quotation('q_1', UIDS.staff, { at: '1712000000000' })));
  await assertFails(quotations(db).doc('q_1').set(quotation('q_1', UIDS.staff, { no: 9 })));
});

test('a Staff account writes no quotation at all', async () => {
  // Stored `worker`, displayed Staff. No Quotation tab, and no way round it.
  const db = as(testEnv, UIDS.worker);
  await assertFails(quotations(db).doc('q_1').set(quotation('q_1', UIDS.worker)));
  await assertFails(customers(db).doc('c_new').set({ id: 'c_new', name: 'New Party' }));
});

// --- parties ----------------------------------------------------------------

test('a party is created under its own id, with a name that is not empty', async () => {
  const db = as(testEnv, UIDS.staff);
  await assertFails(customers(db).doc('c_new').set({ id: 'c_elsewhere', name: 'New Party' }));
  await assertFails(customers(db).doc('c_new').set({ id: 'c_new', name: '' }));
  await assertFails(customers(db).doc('c_new').set({ id: 'c_new' }));
  await assertFails(customers(db).doc('c_new').set({ id: 'c_new', name: 42 }));
  await assertSucceeds(customers(db).doc('c_new').set({ id: 'c_new', name: 'New Party' }));
});

// --- the counter: a stale client cannot take a number twice -----------------

test('the counter moves by exactly one, never further', async () => {
  await givenCounter();
  const db = as(testEnv, UIDS.staff);
  const lastIssued = { no: 'SIE/QD/2025-26/009', at: Date.now(), by: 'Manager Person', uid: UIDS.staff };

  // Skipping is how two devices end up believing they own different numbers
  // while the counter says only one of them did.
  await assertFails(numbering(db).update({ next: 11, lastIssued }));
  await assertFails(numbering(db).update({ next: 9, lastIssued }));
  await assertSucceeds(numbering(db).update({ next: 10, lastIssued }));
});

test('a Manager working from a stale read is refused the number that is gone', async () => {
  // The two-client race, written as the rule sees it. Both Managers read
  // `next: 9`. The first commits 10. The second still believes 9 is free and
  // asks for 10 again — and is refused, rather than overwriting the first.
  await givenCounter();
  const first = as(testEnv, UIDS.staff);
  const second = as(testEnv, UIDS.otherStaff);
  const at = Date.now();

  await assertSucceeds(numbering(first).update({
    next: 10, lastIssued: { no: 'SIE/QD/2025-26/009', at, by: 'Manager Person', uid: UIDS.staff },
  }));

  await assertFails(numbering(second).update({
    next: 10, lastIssued: { no: 'SIE/QD/2025-26/009', at, by: 'Second Manager', uid: UIDS.otherStaff },
  }));

  // The loser's way forward is to re-read and take the next one.
  await assertSucceeds(numbering(second).update({
    next: 11, lastIssued: { no: 'SIE/QD/2025-26/010', at, by: 'Second Manager', uid: UIDS.otherStaff },
  }));

  assert.equal((await counter()).next, 11);
  assert.equal((await counter()).lastIssued.no, 'SIE/QD/2025-26/010');
});

test('the refusal is permission-denied, which Firestore will not retry for us', async () => {
  // **The finding that decides how finalise is written.**
  //
  // A Firestore transaction retries itself when the server aborts it for
  // contention — status ABORTED. It does *not* retry PERMISSION_DENIED. Here
  // the security rule is doing the concurrency check, so a transaction whose
  // read of `next` went stale is rejected by the rules before the server's own
  // optimistic-concurrency check ever aborts it, and the SDK gives up.
  //
  // Measured on this emulator over twenty contended pairs: two separate client
  // apps both succeeded half the time and lost one to permission-denied the
  // other half; two concurrent transactions inside one app lost one every
  // single time. So roughly half of genuinely simultaneous issues surface as a
  // permission error unless the caller retries them itself.
  //
  // N5.9 therefore retries the whole transaction on permission-denied against
  // the counter, bounded, rather than trusting the SDK to do it.
  await givenCounter();
  const first = as(testEnv, UIDS.staff);
  const second = as(testEnv, UIDS.otherStaff);
  const at = Date.now();

  await numbering(first).update({
    next: 10, lastIssued: { no: 'SIE/QD/2025-26/009', at, by: 'Manager Person', uid: UIDS.staff },
  });

  const refusal = await numbering(second).update({
    next: 10, lastIssued: { no: 'SIE/QD/2025-26/009', at, by: 'Second Manager', uid: UIDS.otherStaff },
  }).then(() => null, (error) => error);

  assert.ok(refusal, 'the stale bump must not be accepted');
  assert.equal(refusal.code, 'permission-denied');
});

test('however a contended pair resolves, no two quotations share a number', async () => {
  // The same property run concurrently rather than reasoned about. The
  // outcome is deliberately not pinned to "both succeed", because the test
  // above shows that is only true about half the time. What must hold every
  // time is the invariant underneath: the counter advances by exactly the
  // number of clients that got through, and no number is handed out twice.
  await givenCounter();
  const first = as(testEnv, UIDS.staff);
  const second = as(testEnv, UIDS.otherStaff);

  const take = (db, uid, by) => db.runTransaction(async (transaction) => {
    const snapshot = await transaction.get(numbering(db));
    const next = snapshot.data().next;
    const no = `SIE/QD/2025-26/${String(next).padStart(3, '0')}`;
    transaction.update(numbering(db), { next: next + 1, lastIssued: { no, at: Date.now(), by, uid } });
    return no;
  });

  const outcomes = await Promise.allSettled([
    take(first, UIDS.staff, 'Manager Person'),
    take(second, UIDS.otherStaff, 'Second Manager'),
  ]);
  const issued = outcomes.filter((it) => it.status === 'fulfilled').map((it) => it.value);

  assert.ok(issued.length >= 1, 'at least one client must get a number');
  assert.equal(new Set(issued).size, issued.length, `a number was issued twice: ${issued}`);
  assert.equal((await counter()).next, 9 + issued.length);
  for (const outcome of outcomes.filter((it) => it.status === 'rejected')) {
    assert.equal(outcome.reason.code, 'permission-denied');
  }
});

test('a number that is already spent cannot be re-taken, by anybody', async () => {
  // **N5.1's second finding, and this test used to assert the opposite.**
  //
  // Until N5.6 it read `assertSucceeds` with the defect named beside it: the
  // configuration branch asked only for `next >= resource.data.next`, so an
  // Administrator issuing from a stale read wrote `next: 10` when the stored
  // value was already 10, `10 >= 10` held, the write was accepted — and two
  // people walked away holding number 009 while `lastIssued` named the loser.
  //
  // Two separate changes close it, and each is asserted on its own so a later
  // edit cannot quietly remove one and still leave this file green.
  await givenCounter();
  const manager = as(testEnv, UIDS.staff);
  const administrator = as(testEnv, UIDS.admin);
  const ownerDb = as(testEnv, UIDS.primaryOwner);
  const at = Date.now();

  await assertSucceeds(numbering(manager).update({
    next: 10, lastIssued: { no: 'SIE/QD/2025-26/009', at, by: 'Manager Person', uid: UIDS.staff },
  }));

  // 1. An Administrator no longer reaches the configuration branch at all.
  await assertFails(numbering(administrator).update({
    next: 10, lastIssued: { no: 'SIE/QD/2025-26/009', at, by: 'Administrator', uid: UIDS.admin },
  }));

  // 2. And an Owner, who does reach it, is refused because **configuration
  //    may never stamp `lastIssued`**. That is the guard that matters here:
  //    a stale issue carries one by definition, so it cannot be dressed up
  //    as a configuration write however `next` is set. `settings.test.js`
  //    covers it directly.
  await assertFails(numbering(ownerDb).update({
    next: 10, lastIssued: { no: 'SIE/QD/2025-26/009', at, by: 'Primary Owner', uid: UIDS.primaryOwner },
  }));

  // The counter still holds what the Manager left, and 009 has one owner.
  assert.equal((await counter()).next, 10);
  assert.equal((await counter()).lastIssued.uid, UIDS.staff);
});

test('a Manager may not bump the counter in somebody else s name', async () => {
  await givenCounter();
  const db = as(testEnv, UIDS.staff);
  await assertFails(numbering(db).update({
    next: 10,
    lastIssued: { no: 'SIE/QD/2025-26/009', at: Date.now(), by: 'Administrator', uid: UIDS.admin },
  }));
  await assertFails(numbering(db).update({ next: 10, lastIssued: { no: 'SIE/QD/2025-26/009' } }));
});

test('the prefix, year and padding are frozen while a number is being issued', async () => {
  // A quoting role reaches only the issue branch, and that branch changes
  // nothing about the shape of a number. Touching one of these keys drops the
  // write through to the configuration branch, which a Manager cannot reach.
  await givenCounter();
  const db = as(testEnv, UIDS.staff);
  const lastIssued = { no: 'SIE/QD/2025-26/009', at: Date.now(), by: 'Manager Person', uid: UIDS.staff };

  await assertFails(numbering(db).update({ next: 10, lastIssued, prefix: 'SIE/X' }));
  await assertFails(numbering(db).update({ next: 10, lastIssued, fy: '2026-27' }));
  await assertFails(numbering(db).update({ next: 10, lastIssued, pad: 4 }));
  await assertSucceeds(numbering(db).update({ next: 10, lastIssued }));
});

test('an Owner rolls the financial year, and never rewinds inside one', async () => {
  // Configuration became the Owner's in N5.6; this test named an
  // Administrator until then. The year roll is the one case where `next` may
  // go backwards, because a new year restarts the sequence.
  await givenCounter();
  const db = as(testEnv, UIDS.primaryOwner);

  // Inside the same year the counter only goes forward. Leaving `next` where
  // it is changes nothing and is allowed — that is what lets a prefix be
  // corrected without burning a number, and `settings.test.js` covers it.
  await assertFails(numbering(db).update({ prefix: 'SIE/QD', fy: '2025-26', next: 2 }));
  await assertSucceeds(numbering(db).update({ prefix: 'SIE/QD', fy: '2025-26', next: 12 }));

  // A new year starts wherever the Owner says, including at 1.
  await assertSucceeds(numbering(db).update({ prefix: 'SIE/QD', fy: '2026-27', next: 1 }));
  assert.equal((await counter()).next, 1);

  // Zero is not a number anybody issues.
  await assertFails(numbering(db).update({ prefix: 'SIE/QD', fy: '2027-28', next: 0 }));
});

// --- the retry that must not issue twice ------------------------------------

test('re-writing a quotation that already exists is refused, so a retry cannot double-issue', async () => {
  // V8C4's `fbFinaliseAtomic` is idempotent per draft: if the quotation
  // document is already there it returns the stored number and leaves the
  // counter alone. The rules make that the only thing a retry *can* do — a
  // second `set` of the same id is evaluated as an update, and an update may
  // touch nothing but the three cancellation keys.
  await givenCounter();
  const db = as(testEnv, UIDS.staff);

  await assertSucceeds(issue(db, UIDS.staff, {
    id: 'q_draft_7', next: 10, no: 'SIE/QD/2025-26/009',
  }));
  assert.equal((await counter()).next, 10);

  // The same commit again, byte for byte. Refused on the quotation half.
  await assertFails(issue(db, UIDS.staff, {
    id: 'q_draft_7', next: 11, no: 'SIE/QD/2025-26/009',
  }));

  // And because the two halves are one batch, the counter did not move.
  assert.equal((await counter()).next, 10);
  assert.equal((await counter()).lastIssued.no, 'SIE/QD/2025-26/009');
});

// --- N5.9a commit 1: what the deployed rule accepts that nothing has asked it
// --- about yet. No rule text changes in this commit.

/**
 * **Characterisation, written before anything is designed against it.**
 *
 * N5.7's first commit did this for `/products` and found the rule had zero
 * positive-path coverage — `priceOk` was never evaluated, because every write
 * in the suite ran under `withSecurityRulesDisabled`. So nothing here assumes
 * what the rule does; each test sends a shape N5.9 intends to send and records
 * the answer.
 *
 * Two of these three exist to be **flipped** in the next commit, the way N5.6
 * flipped N5.1's `assertSucceeds` to `assertFails`. They are green now because
 * the rule permits something it should not, and that is the finding.
 */

test('an area line carries its geometry, and the rule does not mind', async () => {
  // The N5 plan's line table specifies w/h/dim/sqft/nos. The create rule
  // names five keys and says nothing whatever about `lines` — no `hasOnly`,
  // no helper — so extra keys inside a line map are accepted as deployed.
  // This is the evidence for "the geometry needs no rule change", rather
  // than an argument from reading the rule.
  await givenCounter();
  const db = as(testEnv, UIDS.staff);

  await assertSucceeds(quotations(db).doc('q_area').set(quotation('q_area', UIDS.staff, {
    lines: [{
      t: 'Rolling shutter', s: '3000 × 3500 mm = 113.5 sq ft × 2 nos',
      u: 'per sq ft', qty: 227, rate: 450, amt: 102150,
      w: 3000, h: 3500, dim: 'mm', sqft: 113.5, nos: 2,
    }],
  })));
});

test('a Manager may not discount past the Owner\'s limit', async () => {
  // **Flipped from commit 1's `TODAY a Manager may write any discount at
  // all`, which passed.** The Owner's decision always read "enforced in the
  // rules"; until this commit it was not, and a Manager writing straight to
  // Firestore was bounded by nothing.
  await givenCounter();
  await givenCap(5);
  const db = as(testEnv, UIDS.staff);

  // 90% off, against a cap of 5.
  await assertFails(quotations(db).doc('q_disc').set(quotation('q_disc', UIDS.staff, {
    disc: { kind: 'pct', value: 90, amt: 39960 },
    discBase: 44400, subtotal: 4440, total: 5239,
  })));
});

test('but may discount exactly up to it', async () => {
  await givenCounter();
  await givenCap(5);
  const db = as(testEnv, UIDS.staff);

  // 5% of 44,400 is 2,220, leaving 42,180.
  await assertSucceeds(quotations(db).doc('q_ok').set(quotation('q_ok', UIDS.staff, {
    disc: { kind: 'pct', value: 5, amt: 2220 },
    discBase: 44400, subtotal: 42180, total: 49772,
  })));
});

/**
 * Where the app and the rule must agree on the cap, figure by figure.
 *
 * **The same five vectors are in `QuoteDiscountTest.kt`**, which pins the
 * app's side: `allowed` is what `QuoteMath.discountRefusal` lets a Manager
 * have — `base × cap ÷ 100` rounded HALF_UP to whole rupees. Change one list
 * and change the other.
 *
 * Every base here is chosen so that `base × cap ÷ 100` is **not** a whole
 * rupee, and two caps are fractional, because the whole-rupee case
 * (44,400 at 5% = 2,220) is the one where the two sides cannot disagree and
 * it was the only case commit 2 tested.
 */
const CAP_BOUNDARY = [
  { base: 44410, cap: 5, allowed: 2221 },    // 2,220.50  rounds up
  { base: 44410, cap: 7.5, allowed: 3331 },  // 3,330.75  rounds up
  { base: 44403, cap: 7.5, allowed: 3330 },  // 3,330.225 rounds down
  { base: 44404, cap: 12.5, allowed: 5551 }, // 5,550.50  rounds up
  { base: 44401, cap: 12.5, allowed: 5550 }, // 5,550.125 rounds down
];

/** A Manager's quotation discounting [amt] off [base], nothing else on it. */
const discounted = (id, base, amt) => quotation(id, UIDS.staff, {
  lines: [{ t: 'Sliding gate motor', u: 'each', qty: 1, rate: base, amt: base }],
  disc: { kind: 'amt', value: amt, amt },
  discBase: base, subtotal: base - amt, total: Math.round((base - amt) * 1.18),
});

for (const { base, cap, allowed } of CAP_BOUNDARY) {
  test(`at ${cap}% of ${base}, the ${allowed} the app allows is accepted`, async () => {
    // A refusal here is invisible to the Manager — the app said yes and the
    // server said no — so the rule must never be the stricter of the two.
    await givenCounter();
    await givenCap(cap);
    const db = as(testEnv, UIDS.staff);

    await assertSucceeds(quotations(db).doc('q_edge').set(discounted('q_edge', base, allowed)));
  });

  test(`at ${cap}% of ${base}, ${allowed + 2} is refused`, async () => {
    // The rule's margin is one rupee, so two past the app's figure is past
    // the rule's in every case: this is what keeps the margin from being a
    // hole.
    await givenCounter();
    await givenCap(cap);
    const db = as(testEnv, UIDS.staff);

    await assertFails(quotations(db).doc('q_over').set(discounted('q_over', base, allowed + 2)));
  });
}

test('an inflated discBase cannot buy a bigger discount', async () => {
  // **Flipped from commit 1's `TODAY an inflated discBase is accepted too`.**
  // A cap checked against a base the writer chooses is not a cap. The bound
  // is `discBase <= subtotal + disc.amt`, which holds for every honest
  // quotation because transport is never negative — and needs only stored
  // fields, since transport is a line inside the subtotal and no rule can see
  // it on its own.
  await givenCounter();
  await givenCap(5);
  const db = as(testEnv, UIDS.staff);

  await assertFails(quotations(db).doc('q_base').set(quotation('q_base', UIDS.staff, {
    disc: { kind: 'amt', value: 40000, amt: 40000 },
    // The lines come to 44,400. This claims four million.
    discBase: 4000000, subtotal: 4440, total: 5239,
  })));
});

test('transport inside the subtotal does not break the base bound', async () => {
  // The base bound is exact when transport is zero and slack by the
  // transport when it is not — see the KNOWN BOUND test — so a real
  // quotation carrying carriage must still pass.
  await givenCounter();
  await givenCap(10);
  const db = as(testEnv, UIDS.staff);

  // 44,400 products, 2,220 off, 2,500 transport -> subtotal 44,680.
  await assertSucceeds(quotations(db).doc('q_tr').set(quotation('q_tr', UIDS.staff, {
    disc: { kind: 'pct', value: 5, amt: 2220 },
    discBase: 44400, subtotal: 44680, total: 52722,
  })));
});

test('KNOWN BOUND: transport buys transport × cap ÷ 100 past the cap', async () => {
  // **Green because the rule permits what it should not, and that is the
  // finding** — the same shape as commit 1's two TODAY tests. The base bound
  // is `discBase <= subtotal + disc.amt`, and transport is a line inside the
  // subtotal, so a writer may claim the transport as part of the base.
  //
  // The Owner's example: a 10% cap, ₹1,00,000 of products, ₹50,000 of
  // transport. The cap means ₹10,000; the rule accepts ₹15,000. Lines,
  // subtotal and total are all honest — only `discBase` lies. Recorded in
  // `docs/PROJECT-STATUS.md`; the day the bound is closed, this flips to
  // `assertFails`.
  await givenCounter();
  await givenCap(10);
  const db = as(testEnv, UIDS.staff);

  await assertSucceeds(quotations(db).doc('q_slack').set(quotation('q_slack', UIDS.staff, {
    lines: [
      { t: 'Sliding gate motor', u: 'each', qty: 1, rate: 100000, amt: 100000 },
      { t: 'Transport', u: 'lot', qty: 1, rate: 50000, amt: 50000 },
    ],
    disc: { kind: 'amt', value: 15000, amt: 15000 },
    discBase: 150000, subtotal: 135000, total: 159300,
  })));
});

test('an Owner and an Administrator are uncapped', async () => {
  await givenCounter();
  await givenCap(5);

  for (const uid of [UIDS.primaryOwner, UIDS.admin]) {
    await assertSucceeds(quotations(as(testEnv, uid)).doc(`q_unc_${uid}`)
      .set(quotation(`q_unc_${uid}`, uid, {
        disc: { kind: 'pct', value: 90, amt: 39960 },
        discBase: 44400, subtotal: 4440, total: 5239,
      })));
  }
});

test('with no quoting document at all, a Manager gets no discount', async () => {
  // An unseeded project is not an uncapped one. `/teamSettings/quoting` is
  // absent here, and the refusal is the safe direction.
  await givenCounter();
  const db = as(testEnv, UIDS.staff);

  await assertFails(quotations(db).doc('q_nocap').set(quotation('q_nocap', UIDS.staff, {
    disc: { kind: 'pct', value: 5, amt: 2220 },
    discBase: 44400, subtotal: 42180, total: 49772,
  })));

  // And a quotation with no discount is untouched by any of this.
  await assertSucceeds(quotations(db).doc('q_plain')
    .set(quotation('q_plain', UIDS.staff)));
});

test('installation rides along unremarked, as the shape N5.9 will send', async () => {
  await givenCounter();
  const db = as(testEnv, UIDS.staff);

  await assertSucceeds(quotations(db).doc('q_inst').set(quotation('q_inst', UIDS.staff, {
    install: { mode: 'door', rate: 500, amt: 2000, basis: 4 },
    subtotal: 46400,
    total: 54752,
  })));
});

test('a quotation with no saved customer is accepted, as V8C4 writes one', async () => {
  // The person typed the client inline and never saved them. V8C4 stores
  // `partyId: null` with a typed party object, so this is a real shape and
  // not an edge case — and the rule requires no `partyId` at all.
  await givenCounter();
  const db = as(testEnv, UIDS.staff);

  await assertSucceeds(quotations(db).doc('q_inline').set(quotation('q_inline', UIDS.staff, {
    partyId: null,
    party: { name: 'Walk-in customer', city: 'Thane' },
  })));
});

test('a quotation with no snap at all is accepted - what N5.9b writes until N6', async () => {
  // `QuotationWrite.plan` writes no `snap` key when its caller passes null,
  // and 9b's caller does until N6 builds company settings. The create rule
  // checks five fields and `snap` is not one of them; this pins that, so a
  // rule that started requiring it would fail here rather than on a phone.
  await givenCounter();
  const db = as(testEnv, UIDS.staff);
  const { snap, ...unfrozen } = quotation('q_nosnap', UIDS.staff);
  assert.ok(snap, 'the base shape carries a snap, so removing it is a real difference');

  await assertSucceeds(quotations(db).doc('q_nosnap').set(unfrozen));
});

test('a quotation is never edited or deleted by the person who wrote it', async () => {
  // The N5 position is different — the creator will be allowed to correct
  // their own — but this is what is deployed today, and the batch that changes
  // it changes this test with it.
  const db = as(testEnv, UIDS.staff);
  await assertSucceeds(quotations(db).doc('q_1').set(quotation('q_1', UIDS.staff)));

  await assertFails(quotations(db).doc('q_1').update({ total: 1 }));
  await assertFails(quotations(db).doc('q_1').update({ lines: [] }));
  await assertFails(quotations(db).doc('q_1').delete());

  // Even an Administrator may only cancel, and cancelling never moves `at`.
  const adminDb = as(testEnv, UIDS.admin);
  await assertFails(quotations(adminDb).doc('q_1').update({
    status: 'Cancelled', cancelledBy: 'Administrator', cancelledAt: Date.now(), at: Date.now(),
  }));
  await assertSucceeds(quotations(adminDb).doc('q_1').update({
    status: 'Cancelled', cancelledBy: 'Administrator', cancelledAt: Date.now(),
  }));
});

// --- writing a party ----------------------------------------------------------

/**
 * A stored party, planted with the rules disabled.
 *
 * Seeded per test rather than in `beforeEach`, and **not optional**: an
 * `update` on a document that is not there fails whatever the rules say, so a
 * "this role is refused" test against a missing party passes for the wrong
 * reason and proves nothing. Every refusal below is asserted against a party
 * that really exists.
 */
async function givenParty(id = 'c_1', fields = {}) {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('customers').doc(id).set({
      id, name: 'Sunrise Constructions', type: 'contractor', city: 'Mumbai',
      gstin: '27AAACS1234F1Z5', contact: 'Mr Deshmukh', phone: '9876543210',
      email: 'accounts@sunrise.invalid', address: 'Plot 14, Andheri East',
      notes: '', archived: false, t: 1700000000000, by: 'Mudassir Khan',
      byUid: UIDS.primaryOwner, updated: 1705000000000,
      ...fields,
    });
  });
}

test('a Manager creates a party, in the shape V8C4 reads', async () => {
  // Every field V8C4 keeps, and no parallel one. `city`, never `site`.
  const db = as(testEnv, UIDS.staff);
  await assertSucceeds(customers(db).doc('c_new').set({
    id: 'c_new',
    name: 'Metro Glass',
    type: 'contractor',
    city: 'Mumbai',
    gstin: '27AAACM1234F1Z5',
    contact: 'Mr Rane',
    phone: '9820011223',
    email: 'accounts@metro.invalid',
    address: 'Plot 9, Bhandup',
    notes: 'Pays on delivery',
    archived: false,
    t: Date.now(),
    by: 'Manager Person',
    byUid: UIDS.staff,
    updated: Date.now(),
    upBy: 'Manager Person',
    upUid: UIDS.staff,
  }));
});

test('and corrects a detail on one, without touching its name', async () => {
  await givenParty();
  const db = as(testEnv, UIDS.staff);
  await assertSucceeds(customers(db).doc('c_1').update({
    id: 'c_1', name: 'Sunrise Constructions',
    contact: 'Mrs Deshmukh', phone: '9876500000', gstin: '27AAACS1234F1Z5',
    city: 'Mumbai', email: 'a@b.invalid', address: 'Plot 14', notes: '', type: 'contractor',
    updated: Date.now(), upBy: 'Manager Person', upUid: UIDS.staff,
  }));
});

test('but a Manager never renames a party', async () => {
  await givenParty();
  const db = as(testEnv, UIDS.staff);
  await assertFails(customers(db).doc('c_1').update({ id: 'c_1', name: 'Renamed Ltd' }));
  // Not even by emptying it.
  await assertFails(customers(db).doc('c_1').update({ id: 'c_1', name: '' }));
});

test('and never archives one, nor brings one back', async () => {
  await givenParty();
  const db = as(testEnv, UIDS.staff);
  await assertFails(customers(db).doc('c_1').update({ id: 'c_1', archived: true }));

  await givenParty('c_old', { name: 'Old Client Pvt Ltd', archived: true });
  // Unarchiving is refused, and so is every other change to an archived
  // party: the staff branch requires it was not archived to begin with.
  await assertFails(customers(db).doc('c_old').update({ id: 'c_old', archived: false }));
  await assertFails(customers(db).doc('c_old').update({ id: 'c_old', city: 'Mumbai' }));
});

test('an Owner and an Administrator rename and archive', async () => {
  await givenParty();
  const adminDb = as(testEnv, UIDS.admin);
  await assertSucceeds(customers(adminDb).doc('c_1').update({ id: 'c_1', name: 'Sunrise Infra' }));
  await assertSucceeds(customers(adminDb).doc('c_1').update({ id: 'c_1', archived: true }));
  // And back again, which a Manager cannot do.
  await assertSucceeds(customers(adminDb).doc('c_1').update({ id: 'c_1', archived: false }));

  const ownerDb = as(testEnv, UIDS.primaryOwner);
  await assertSucceeds(customers(ownerDb).doc('c_1').update({ id: 'c_1', name: 'Sunrise Group' }));
});

test('a Staff account writes no party at all', async () => {
  // Stored `worker`, displayed Staff. No Parties screen, and no way round it.
  await givenParty();
  const db = as(testEnv, UIDS.worker);
  await assertFails(customers(db).doc('c_new').set({ id: 'c_new', name: 'Metro Glass' }));
  await assertFails(customers(db).doc('c_1').update({ id: 'c_1', city: 'Mumbai' }));
  await assertFails(customers(db).doc('c_1').delete());
  await assertFails(customers(db).doc('c_1').get());
});

test('an edit must still carry the id of the document it is in', async () => {
  // The rule checks `data.id == id` on an update as well as a create, and on
  // a merged update the post-state carries whatever was stored — so a
  // document that never had an `id` cannot be edited until one is written.
  await givenParty();
  const db = as(testEnv, UIDS.staff);
  await assertFails(customers(db).doc('c_1').update({ id: 'c_elsewhere', city: 'Pune' }));
});
