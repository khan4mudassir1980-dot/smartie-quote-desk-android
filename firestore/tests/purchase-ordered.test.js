const test = require('node:test');
const assert = require('node:assert/strict');
const { assertSucceeds } = require('@firebase/rules-unit-testing');
const firebase = require('firebase/compat/app');
require('firebase/compat/firestore');
const { createTestEnvironment, seed, as, refused, UIDS, PEOPLE } = require('./helpers');

/**
 * "Ordered", and a cancel for the Manager — N5.10b commit 9, the Owner's
 * design approved on 2026-10-05.
 *
 * - **Order / Not ordered** is the Owner's and the Administrator's alone, on
 *   an open requirement (part-received counts), with an Ordered stamp.
 * - **Ordered is open**: deliveries go on as before; a part receipt keeps it
 *   Ordered, the whole of it or a write-off closes it as Received.
 * - **The locks**: once Ordered, only an Owner or Administrator changes the
 *   name or the quantity, or takes it off the list.
 * - **Cancel** only when nothing has been received — an Administrator
 *   included — with V8C4's stamp, `cancelledBy` / `cancelledUid` /
 *   `cancelledAt`. A Manager cancels anybody's that is not Ordered; an Owner
 *   or Administrator any. Staff have no cancel.
 *
 * Every payload is the one the native app sends (`PurchaseWrite`).
 */

let testEnv;

test.before(async () => { testEnv = await createTestEnvironment(); });
test.after(async () => { await testEnv?.cleanup(); });
test.beforeEach(async () => { await seed(testEnv); });

const ID = 'pr_ord';
const del = () => firebase.firestore.FieldValue.delete();
const name = (uid) => PEOPLE[uid].name;
const doc = (uid) => as(testEnv, uid).collection('purchase').doc(ID);

async function given(fields) {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('purchase').doc(ID).set(fields);
  });
}

async function stored() {
  let data;
  await testEnv.withSecurityRulesDisabled(async (context) => {
    data = (await context.firestore().collection('purchase').doc(ID).get()).data();
  });
  return data;
}

/** Raised by Staff, nothing arrived. Stored `worker` is displayed **Staff**. */
const NEEDED = {
  id: ID, name: 'Rack', qty: 10, urgency: 'normal', status: 'Needed', note: '',
  by: 'Staff Person', byUid: UIDS.worker, t: 1712000000000, updated: 1712000000000,
  rev: 1, received: false, del: false,
};
const ORDERED_STAMP = { orderedBy: 'Administrator', orderedUid: UIDS.admin, orderedAt: 1712500000000 };
const ORDERED = { ...NEEDED, status: 'Ordered', ...ORDERED_STAMP };
const RECEIPT = { rcvQty: 4, rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: 1712600000000 };

/** `PurchaseWrite.base()`: stamped with the caller. */
const base = (uid, qty = 10, rev = 1) => ({
  id: ID, qty, updated: Date.now(), rev: rev + 1, upBy: name(uid), upUid: uid,
  serverAt: firebase.firestore.FieldValue.serverTimestamp(),
});
const order = (uid, extra = {}) => ({ ...base(uid), status: 'Ordered',
  orderedBy: name(uid), orderedUid: uid, orderedAt: Date.now(), ...extra });
const unorder = (uid) => ({ ...base(uid), status: 'Needed', orderedBy: del(), orderedUid: del(), orderedAt: del() });
const cancel = (uid, extra = {}) => ({ ...base(uid), status: 'Cancelled',
  cancelledBy: name(uid), cancelledUid: uid, cancelledAt: Date.now(), ...extra });
const edit = (uid, fields) => ({ ...base(uid, fields.qty ?? 10), ...fields });
const remove = (uid) => ({ ...base(uid), del: true, deletedBy: uid, delBy: name(uid), delAt: Date.now() });
const part = (uid, status, total = 4) => ({ ...base(uid), status, received: false,
  rcvQty: total, rcvBy: name(uid), rcvUid: uid, rcvAt: Date.now() });
const whole = (uid) => ({ ...base(uid), status: 'Received', received: true,
  rcvQty: 10, rcvBy: name(uid), rcvUid: uid, rcvAt: Date.now() });
const writeOff = (uid) => ({ ...base(uid, 4), status: 'Received', received: true });
const reopen = (uid) => ({ ...base(uid), status: 'Needed', received: false,
  rcvQty: del(), rcvBy: del(), rcvUid: del(), rcvAt: del(),
  cancelledBy: del(), cancelledUid: del(), cancelledAt: del(),
  orderedBy: del(), orderedUid: del(), orderedAt: del() });

// --- order, and back to Needed ------------------------------------------------------

test('an Owner or Administrator orders an open requirement and takes it back — part-received included', async () => {
  for (const uid of [UIDS.admin, UIDS.primaryOwner]) {
    await given(NEEDED);
    await assertSucceeds(doc(uid).update(order(uid)));
    const ordered = await stored();
    assert.equal(ordered.status, 'Ordered');
    assert.equal(ordered.orderedUid, uid);
    await assertSucceeds(doc(uid).update({ ...unorder(uid), rev: 3 }));
    assert.equal((await stored()).status, 'Needed');

    await given({ ...NEEDED, ...RECEIPT });
    await assertSucceeds(doc(uid).update(order(uid)));
  }
});

test('a Manager or Staff may neither order nor take it back', async () => {
  await given(NEEDED);
  await refused(doc(UIDS.staff).update(order(UIDS.staff)));
  await refused(doc(UIDS.worker).update(order(UIDS.worker)));

  await given(ORDERED);
  await refused(doc(UIDS.staff).update(unorder(UIDS.staff)));
  await refused(doc(UIDS.worker).update(unorder(UIDS.worker)));
});

test('ordering needs its stamp, written now — V8C4\'s bare "Ordered" included', async () => {
  await given(NEEDED);
  const admin = doc(UIDS.admin);
  await refused(admin.update({ ...base(UIDS.admin), status: 'Ordered' }));
  await refused(admin.update(order(UIDS.admin, { orderedBy: del() })));
  await refused(admin.update(order(UIDS.admin, { orderedAt: '2026-10-05' })));
  await refused(admin.update(order(UIDS.admin, { orderedBy: 'x'.repeat(81) })));

  // Left over from an order taken back by hand: the same time again is not
  // a stamp written now.
  await given({ ...NEEDED, orderedBy: 'Administrator', orderedUid: UIDS.admin, orderedAt: 1712500000000 });
  await refused(admin.update({ ...base(UIDS.admin), status: 'Ordered' }));
  await assertSucceeds(admin.update(order(UIDS.admin)));
});

test('the Ordered uid is the caller\'s — set now, or left over from somebody else', async () => {
  await given(NEEDED);
  await refused(doc(UIDS.admin).update(order(UIDS.admin, { orderedUid: UIDS.otherAdmin })));

  await given({ ...NEEDED, orderedBy: 'Other Administrator', orderedUid: UIDS.otherAdmin, orderedAt: 1712500000000 });
  await refused(doc(UIDS.admin).update(order(UIDS.admin, { orderedBy: 'Administrator', orderedUid: del() })));
  await refused(doc(UIDS.admin).update({ ...base(UIDS.admin), status: 'Ordered', orderedAt: Date.now() }));

  // Any write that sets it, not only the order.
  await given(ORDERED);
  await refused(doc(UIDS.admin).update({ ...base(UIDS.admin), orderedUid: UIDS.otherAdmin }));
});

test('only an open requirement is ordered', async () => {
  const admin = doc(UIDS.admin);
  await given({ ...NEEDED, ...RECEIPT, rcvQty: 10, status: 'Received', received: true });
  await refused(admin.update({ ...order(UIDS.admin), received: false }));
  await given({ ...NEEDED, status: 'Cancelled', cancelledBy: 'Administrator', cancelledUid: UIDS.admin, cancelledAt: 1712500000000 });
  await refused(admin.update(order(UIDS.admin)));
  await given({ ...NEEDED, del: true });
  await refused(admin.update(order(UIDS.admin)));
});

// --- Ordered is open: deliveries, for a Manager and for the Staff creator ---------------

for (const [who, uid] of [['a Manager, on somebody\'s', UIDS.staff], ['the Staff creator, on their own', UIDS.worker]]) {
  test(`Ordered stays open for ${who}: a part keeps it Ordered; the whole, or a write-off, closes it`, async () => {
    await given(ORDERED);
    await assertSucceeds(doc(uid).update(part(uid, 'Ordered')));
    assert.equal((await stored()).status, 'Ordered');

    await given(ORDERED);
    await assertSucceeds(doc(uid).update(whole(uid)));
    assert.equal((await stored()).status, 'Received');

    await given({ ...ORDERED, ...RECEIPT });
    await assertSucceeds(doc(uid).update(writeOff(uid)));
    const closed = await stored();
    assert.equal(closed.status, 'Received');
    assert.equal(closed.qty, 4);
  });

  test(`a part receipt does not take an Ordered requirement back to Needed — ${who}`, async () => {
    await given(ORDERED);
    await refused(doc(uid).update(part(uid, 'Needed')));
  });
}

test('a V8C4 Ordered row with no stamp is open: a Manager delivers on it, an Administrator takes it back', async () => {
  const v8c4 = { id: ID, name: 'Rack', qty: 10, urgency: 'normal', status: 'Ordered', t: 1712000000000, updated: 1712000000000 };
  await given(v8c4);
  await assertSucceeds(doc(UIDS.staff).update({ ...part(UIDS.staff, 'Ordered'), rev: 1 }));
  await given(v8c4);
  await assertSucceeds(doc(UIDS.admin).update({ ...unorder(UIDS.admin), rev: 1 }));
});

// --- the locks ---------------------------------------------------------------------------

test('once Ordered, a Manager or the creator changes neither name nor quantity — note and urgency still', async () => {
  for (const uid of [UIDS.staff, UIDS.worker]) {
    await given(ORDERED);
    await refused(doc(uid).update(edit(uid, { name: 'Sliding gate rack' })));
    await refused(doc(uid).update(edit(uid, { qty: 12 })));
    await assertSucceeds(doc(uid).update(edit(uid, { note: 'Kandivali' })));
    await given(ORDERED);
    await assertSucceeds(doc(uid).update(edit(uid, { urgency: 'urgent' })));
  }
  // The same edits on a Needed requirement are theirs, as before.
  await given(NEEDED);
  await assertSucceeds(doc(UIDS.staff).update(edit(UIDS.staff, { name: 'Sliding gate rack', qty: 12 })));
});

test('once Ordered, its creator may not take it off the list; an Owner or Administrator may, and may change it', async () => {
  await given(ORDERED);
  await refused(doc(UIDS.worker).update(remove(UIDS.worker)));
  await given({ ...ORDERED, by: 'Manager Person', byUid: UIDS.staff });
  await refused(doc(UIDS.staff).update(remove(UIDS.staff)));

  await given(ORDERED);
  await assertSucceeds(doc(UIDS.admin).update(edit(UIDS.admin, { name: 'Sliding gate rack', qty: 12 })));
  await given(ORDERED);
  await assertSucceeds(doc(UIDS.admin).update(remove(UIDS.admin)));

  // Needed, the creator still removes their own.
  await given(NEEDED);
  await assertSucceeds(doc(UIDS.worker).update(remove(UIDS.worker)));
});

// --- cancel --------------------------------------------------------------------------------

test('a Manager cancels anybody\'s Needed requirement while nothing has arrived — their own too', async () => {
  await given(NEEDED);
  await assertSucceeds(doc(UIDS.staff).update(cancel(UIDS.staff)));
  const cancelled = await stored();
  assert.equal(cancelled.status, 'Cancelled');
  assert.equal(cancelled.cancelledUid, UIDS.staff);
  assert.equal(cancelled.received, false);

  await given({ ...NEEDED, by: 'Manager Person', byUid: UIDS.staff });
  await assertSucceeds(doc(UIDS.staff).update(cancel(UIDS.staff)));
});

test('but not an Ordered one, and not one with anything received', async () => {
  await given(ORDERED);
  await refused(doc(UIDS.staff).update(cancel(UIDS.staff)));
  await given({ ...NEEDED, ...RECEIPT });
  await refused(doc(UIDS.staff).update(cancel(UIDS.staff)));
});

test('an Owner or Administrator cancels an Ordered requirement — but never a part-received one', async () => {
  for (const uid of [UIDS.admin, UIDS.primaryOwner]) {
    await given(ORDERED);
    await assertSucceeds(doc(uid).update(cancel(uid)));

    await given({ ...NEEDED, ...RECEIPT });
    await refused(doc(uid).update(cancel(uid)));
    await given({ ...ORDERED, ...RECEIPT });
    await refused(doc(uid).update(cancel(uid)));
    // Not by clearing the receipt in the same write…
    await refused(doc(uid).update(cancel(uid, { rcvQty: del(), rcvBy: del(), rcvUid: del(), rcvAt: del() })));
    // …and not by writing one beside it.
    await given(NEEDED);
    await refused(doc(uid).update(cancel(uid, { rcvQty: 4, rcvBy: name(uid), rcvUid: uid, rcvAt: Date.now() })));
  }
});

test('Staff have no cancel, not even of their own — they remove it', async () => {
  await given(NEEDED);
  await refused(doc(UIDS.worker).update(cancel(UIDS.worker)));
  await assertSucceeds(doc(UIDS.worker).update(remove(UIDS.worker)));
});

test('a cancel needs its stamp, written now, by the caller', async () => {
  const manager = doc(UIDS.staff);
  await given(NEEDED);
  await refused(manager.update({ ...base(UIDS.staff), status: 'Cancelled' }));
  await refused(manager.update(cancel(UIDS.staff, { cancelledBy: del() })));
  await refused(manager.update(cancel(UIDS.staff, { cancelledAt: '2026-10-05' })));
  await refused(manager.update(cancel(UIDS.staff, { cancelledBy: 'x'.repeat(81) })));
  await refused(manager.update(cancel(UIDS.staff, { cancelledUid: UIDS.admin })));

  // Left over from a V8C4 cancel and restore: neither the old time nor
  // somebody else's uid stands for this cancel.
  const stale = { cancelledBy: 'Administrator', cancelledUid: UIDS.admin, cancelledAt: 1712300000000 };
  await given({ ...NEEDED, ...stale });
  await refused(manager.update({ ...base(UIDS.staff), status: 'Cancelled' }));
  await refused(manager.update({ ...base(UIDS.staff), status: 'Cancelled', cancelledBy: 'Manager Person', cancelledAt: Date.now() }));
  await assertSucceeds(manager.update(cancel(UIDS.staff)));
});

test('a Manager\'s cancel carries nothing else', async () => {
  const manager = doc(UIDS.staff);
  // A row with no `received` at all, as V8C4 creates them, so writing one is
  // a change the rules can see: the native cancel writes none.
  const { received, ...noReceived } = NEEDED;
  await given(noReceived);
  await refused(manager.update(cancel(UIDS.staff, { received: false })));
  await assertSucceeds(manager.update(cancel(UIDS.staff)));

  await given(NEEDED);
  await refused(manager.update(cancel(UIDS.staff, { note: 'Not needed after all' })));
  await refused(manager.update(cancel(UIDS.staff, { urgency: 'urgent' })));
  await refused(manager.update({ ...cancel(UIDS.staff), ...base(UIDS.staff, 12) }));
  // The stamp without the cancel is not a cancel.
  await refused(manager.update({ ...cancel(UIDS.staff), status: 'Needed' }));
  // And a cancelled requirement is not cancelled again over somebody else's stamp.
  await given({ ...NEEDED, status: 'Cancelled', cancelledBy: 'Administrator', cancelledUid: UIDS.admin, cancelledAt: 1712300000000 });
  await refused(manager.update(cancel(UIDS.staff)));
});

test('a removed requirement is not cancelled', async () => {
  await given({ ...NEEDED, del: true });
  await refused(doc(UIDS.staff).update(cancel(UIDS.staff)));
  await refused(doc(UIDS.admin).update(cancel(UIDS.admin)));
});

// --- reopen --------------------------------------------------------------------------------

test('reopen clears the receipt, the cancel and the Ordered stamps, and the row is as good as new', async () => {
  await given({ ...ORDERED, status: 'Cancelled', cancelledBy: 'Administrator', cancelledUid: UIDS.admin, cancelledAt: 1712700000000 });
  await assertSucceeds(doc(UIDS.admin).update(reopen(UIDS.admin)));
  const reopened = await stored();
  assert.equal(reopened.status, 'Needed');
  for (const key of ['cancelledBy', 'cancelledUid', 'cancelledAt', 'orderedBy', 'orderedUid', 'orderedAt', 'rcvQty']) {
    assert.equal(key in reopened, false, key);
  }
  // As good as new: its creator may correct it again.
  await assertSucceeds(doc(UIDS.worker).update({ ...edit(UIDS.worker, { name: 'Sliding gate rack' }), rev: 3 }));

  await given({ ...ORDERED, ...RECEIPT, rcvQty: 10, status: 'Received', received: true });
  await assertSucceeds(doc(UIDS.admin).update(reopen(UIDS.admin)));
});

// --- a new requirement ------------------------------------------------------------------

test('a new requirement is neither Ordered nor Cancelled, and carries neither stamp', async () => {
  const db = as(testEnv, UIDS.worker).collection('purchase');
  const fresh = (id, extra = {}) => ({
    id, name: 'Anchor bolts', qty: 20, urgency: 'normal', status: 'Needed',
    by: 'Staff Person', byUid: UIDS.worker, t: Date.now(), updated: Date.now(), ...extra,
  });
  await refused(db.doc('pr_n1').set(fresh('pr_n1', { status: 'Ordered' })));
  await refused(db.doc('pr_n2').set(fresh('pr_n2', { status: 'Cancelled' })));
  const stamps = {
    orderedBy: 'Staff Person', orderedUid: UIDS.worker, orderedAt: Date.now(),
    cancelledBy: 'Staff Person', cancelledUid: UIDS.worker, cancelledAt: Date.now(),
  };
  for (const [key, value] of Object.entries(stamps)) {
    await refused(db.doc(`pr_${key}`).set(fresh(`pr_${key}`, { [key]: value })));
  }
  await assertSucceeds(db.doc('pr_n3').set(fresh('pr_n3')));
});
