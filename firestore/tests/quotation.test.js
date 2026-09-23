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

  // 2. And an Owner, who does reach it, is refused by the strictly-greater
  //    rule — because standing still is not a configuration change, it is a
  //    re-take of a number that is already spent.
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
