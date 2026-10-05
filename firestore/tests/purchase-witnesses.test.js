const test = require('node:test');
const { assertSucceeds } = require('@firebase/rules-unit-testing');
const firebase = require('firebase/compat/app');
require('firebase/compat/firestore');
const { createTestEnvironment, seed, as, UIDS, PEOPLE } = require('./helpers');

/**
 * Witnesses: the **dearest valid** `/purchase` writes, each as the native app
 * sends it, in both access states — `primaryOwnerUid` recorded, and the
 * transition state that still identifies the Owner by email.
 *
 * N5.10b measured these with the headroom tools (`firestore/tools/`, cost ≈
 * (163 − N) × 1000 / 163, ±6, every rule padded). The dearest at commit 9:
 * a Manager's cancel 755, a Manager's whole delivery or write-off — on an
 * Ordered row or not — 687, a Staff creator's write-off on their own Ordered
 * row 663. The stop line is 900. These tests do not measure; they make sure
 * that if a later rule pushes one of these over the 1,000-expression limit,
 * the suite goes red on the write that was refused, rather than a phone
 * finding it.
 */

let testEnv;

test.before(async () => { testEnv = await createTestEnvironment(); });
test.after(async () => { await testEnv?.cleanup(); });

const ID = 'pr_witness';
const name = (uid) => PEOPLE[uid].name;
const del = () => firebase.firestore.FieldValue.delete();
const doc = (uid) => as(testEnv, uid).collection('purchase').doc(ID);

async function given(fields) {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('purchase').doc(ID).set(fields);
  });
}

const row = (byUid, extra = {}) => ({
  id: ID, name: 'Rack', qty: 10, urgency: 'normal', status: 'Needed', note: 'For site',
  by: name(byUid), byUid, t: 1712000000000, updated: 1712000000000, rev: 3,
  received: false, del: false, upBy: name(byUid), upUid: byUid, ...extra,
});
const ORDERED = { status: 'Ordered', orderedBy: 'Administrator', orderedUid: UIDS.admin, orderedAt: 1712050000000 };
const PART = { rcvQty: 4, rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: 1712100000000 };

const base = (uid, qty = 10) => ({
  id: ID, qty, updated: Date.now(), rev: 4, upBy: name(uid), upUid: uid,
  serverAt: firebase.firestore.FieldValue.serverTimestamp(),
});
const whole = (uid) => ({ ...base(uid), status: 'Received', received: true,
  rcvQty: 10, rcvBy: name(uid), rcvUid: uid, rcvAt: Date.now() });
const writeOff = (uid) => ({ ...base(uid, 4), status: 'Received', received: true });
const cancel = (uid) => ({ ...base(uid), status: 'Cancelled',
  cancelledBy: name(uid), cancelledUid: uid, cancelledAt: Date.now() });

const STATES = [['primaryOwnerUid recorded', true], ['the email-fallback transition', false]];

for (const [state, withUid] of STATES) {
  test(`[${state}] a Manager cancels somebody's requirement, and their own`, async () => {
    await seed(testEnv, { withPrimaryOwnerUid: withUid });
    await given(row(UIDS.worker));
    await assertSucceeds(doc(UIDS.staff).update(cancel(UIDS.staff)));
    await given(row(UIDS.staff));
    await assertSucceeds(doc(UIDS.staff).update(cancel(UIDS.staff)));
  });

  test(`[${state}] a Manager's whole delivery and write-off, on an Ordered row and on a Needed one`, async () => {
    await seed(testEnv, { withPrimaryOwnerUid: withUid });
    for (const extra of [ORDERED, {}]) {
      await given(row(UIDS.worker, extra));
      await assertSucceeds(doc(UIDS.staff).update(whole(UIDS.staff)));
      await given(row(UIDS.worker, { ...extra, ...PART }));
      await assertSucceeds(doc(UIDS.staff).update(writeOff(UIDS.staff)));
    }
  });

  test(`[${state}] the Staff creator's whole delivery and write-off on their own Ordered row`, async () => {
    await seed(testEnv, { withPrimaryOwnerUid: withUid });
    await given(row(UIDS.worker, ORDERED));
    await assertSucceeds(doc(UIDS.worker).update(whole(UIDS.worker)));
    await given(row(UIDS.worker, { ...ORDERED, ...PART }));
    await assertSucceeds(doc(UIDS.worker).update(writeOff(UIDS.worker)));
  });

  test(`[${state}] the Staff creator removes their own V8C4-shaped row`, async () => {
    await seed(testEnv, { withPrimaryOwnerUid: withUid });
    // As V8C4 created it: no rev, no received, no del.
    await given({ id: ID, name: 'Rack', qty: 10, urgency: 'normal', status: 'Needed', note: 'x',
      by: name(UIDS.worker), byUid: UIDS.worker, t: 1712000000000, updated: 1712000000000 });
    await assertSucceeds(doc(UIDS.worker).update({ ...base(UIDS.worker), rev: 1,
      del: true, deletedBy: UIDS.worker, delBy: name(UIDS.worker), delAt: Date.now() }));
  });

  test(`[${state}] an Administrator orders a part-received row, takes it back, cancels an Ordered one and reopens it`, async () => {
    await seed(testEnv, { withPrimaryOwnerUid: withUid });
    await given(row(UIDS.worker, PART));
    await assertSucceeds(doc(UIDS.admin).update({ ...base(UIDS.admin), status: 'Ordered',
      orderedBy: name(UIDS.admin), orderedUid: UIDS.admin, orderedAt: Date.now() }));
    await given(row(UIDS.worker, ORDERED));
    await assertSucceeds(doc(UIDS.admin).update({ ...base(UIDS.admin), status: 'Needed',
      orderedBy: del(), orderedUid: del(), orderedAt: del() }));
    await given(row(UIDS.worker, ORDERED));
    await assertSucceeds(doc(UIDS.admin).update(cancel(UIDS.admin)));
    await given(row(UIDS.worker, { ...ORDERED, status: 'Cancelled',
      cancelledBy: 'Administrator', cancelledUid: UIDS.admin, cancelledAt: 1712060000000 }));
    await assertSucceeds(doc(UIDS.admin).update({ ...base(UIDS.admin), status: 'Needed', received: false,
      rcvQty: del(), rcvBy: del(), rcvUid: del(), rcvAt: del(),
      cancelledBy: del(), cancelledUid: del(), cancelledAt: del(),
      orderedBy: del(), orderedUid: del(), orderedAt: del() }));
  });
}
