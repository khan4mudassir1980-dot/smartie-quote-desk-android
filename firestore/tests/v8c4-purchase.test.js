const test = require('node:test');
const { assertSucceeds } = require('@firebase/rules-unit-testing');
const firebase = require('firebase/compat/app');
require('firebase/compat/firestore');
const { createTestEnvironment, seed, as, refused, UIDS, PEOPLE } = require('./helpers');

/**
 * V8C4's purchase writes, as they meet these rules — **the truth after
 * cutover**. The Owner decided on 2026-09-29 (QZ) that the PWA stops writing
 * Purchase at cutover and everyone uses the native app for it from that day.
 * These tests record which of the PWA's shapes the rules then refuse, and
 * which clause refuses each, so nobody has to rediscover it. Where a shape is
 * still accepted, a test says so too: the PWA stops by decision, not because
 * these rules refuse every write it makes.
 *
 * **Every value here is synthetic.** The shapes are the advisor's reading of
 * V8C4's purchase write path, supplied by the Owner (N5.10b step 1b); no V8C4
 * code is copied, and no real names, rates or company data appear.
 *
 * How V8C4 sends every purchase write, as described: a transaction that
 * merges the **whole local row**, stamped with the caller — so a field it
 * deletes locally stays in Firestore. `received` is written as the number 1
 * or 0, never a boolean. Its receipt is all-or-nothing: a short one closes the
 * row with nothing written off.
 */

let testEnv;

test.before(async () => { testEnv = await createTestEnvironment(); });
test.after(async () => { await testEnv?.cleanup(); });
test.beforeEach(async () => { await seed(testEnv); });

const ID = 'pr_pwa';
const name = (uid) => PEOPLE[uid].name;

/** A requirement as V8C4 creates one: no `received`, no `rev`. */
const pwaRow = (extra = {}) => ({
  id: ID, key: 'gate|G1', name: 'Rack', qty: 10, urgency: 'normal', note: '',
  status: 'Needed', by: name(UIDS.worker), byUid: UIDS.worker,
  upBy: name(UIDS.worker), upUid: UIDS.worker, t: 1712000000000, updated: 1712000000000,
  ...extra,
});

async function given(fields) {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('purchase').doc(ID).set(fields);
  });
}

/** The write as described: the whole local row, merged, stamped with the caller. */
function pwaPush(uid, local) {
  const db = as(testEnv, uid);
  const ref = db.collection('purchase').doc(ID);
  return db.runTransaction(async (tx) => {
    await tx.get(ref);
    tx.set(ref, {
      ...local, upBy: name(uid), upUid: uid, updated: Date.now(),
      serverAt: firebase.firestore.FieldValue.serverTimestamp(),
    }, { merge: true });
  });
}

/** Mark received: all or nothing, `received` the number 1. */
const markReceived = (uid, got, extra = {}) => pwaRow({
  rcvQty: got, rcvBy: name(uid), rcvUid: uid, rcvAt: Date.now(), received: 1, status: 'Received', ...extra,
});

// --- refused: `received` must be a boolean (QA) ----------------------------------

test('V8C4\'s receipt is refused, full or short, a Manager\'s or an Administrator\'s: received is the number 1', async () => {
  // Refused two ways: the number is not a boolean, and a status may move to
  // Received only beside `received: true`. V8C4 always sends both together.
  for (const uid of [UIDS.staff, UIDS.admin]) {
    await given(pwaRow());
    await refused(pwaPush(uid, markReceived(uid, 10)));
    await refused(pwaPush(uid, markReceived(uid, 12)));
  }
});

test('the received: 1 short close — 4 of 10, nothing written off — is refused three ways', async () => {
  // `received` is not a boolean; a status may move to Received only with
  // `received: true`; and whatever closes a requirement meets its total.
  for (const uid of [UIDS.staff, UIDS.admin]) {
    await given(pwaRow());
    await refused(pwaPush(uid, markReceived(uid, 4)));
    await refused(pwaPush(uid, markReceived(uid, 0)));
    // Even with a boolean it is short, so it is still refused…
    await refused(pwaPush(uid, markReceived(uid, 4, { received: true })));
  }
  // …and the native app's write-off — the total set to what arrived — closes it.
  await given(pwaRow({ rcvQty: 4, rcvBy: name(UIDS.staff), rcvUid: UIDS.staff, rcvAt: 1712600000000, received: false }));
  await assertSucceeds(pwaPush(UIDS.staff, pwaRow({ rcvQty: 4, rcvBy: name(UIDS.staff), rcvUid: UIDS.staff,
    rcvAt: 1712600000000, qty: 4, received: true, status: 'Received', rev: 1 })));
});

test('V8C4\'s cancel and restore write received: 0, and are refused — an Administrator\'s included', async () => {
  await given(pwaRow());
  await refused(pwaPush(UIDS.admin, pwaRow({ cancelledBy: name(UIDS.admin), cancelledUid: UIDS.admin,
    cancelledAt: Date.now(), received: 0, status: 'Cancelled' })));

  await given(markReceived(UIDS.admin, 10));
  await refused(pwaPush(UIDS.admin, markReceived(UIDS.admin, 10, { received: 0, status: 'Needed', stocked: 0, stockedQty: 0 })));
});

// --- refused: how status may move ---------------------------------------------------

test('V8C4\'s "Ordered" is refused — the Owner\'s own Ordered is N5.10b commit 7\'s', async () => {
  await given(pwaRow());
  await refused(pwaPush(UIDS.admin, pwaRow({ status: 'Ordered' })));
  await refused(pwaPush(UIDS.staff, pwaRow({ status: 'Ordered' })));
});

test('a Manager\'s V8C4 cancel is refused — a Manager\'s cancel is N5.10b commit 7\'s', async () => {
  await given(pwaRow());
  await refused(pwaPush(UIDS.staff, pwaRow({ cancelledBy: name(UIDS.staff), cancelledUid: UIDS.staff,
    cancelledAt: Date.now(), received: false, status: 'Cancelled' })));
});

// --- refused: decided not to fix (QZ) --------------------------------------------------

test('a Manager\'s receipt with add-to-stock stays refused (D1, not fixed)', async () => {
  // `stocked` and `stockedQty` are in no Manager's list. Sent with a boolean,
  // so the stock keys are the only thing wrong.
  await given(pwaRow());
  await refused(pwaPush(UIDS.staff, markReceived(UIDS.staff, 10, { received: true, stocked: 1, stockedQty: 10 })));
});

test('a Manager\'s restore stays refused, and reopen stays Owner and Administrator (D4, not fixed)', async () => {
  await given(markReceived(UIDS.admin, 10, { received: true }));
  await refused(pwaPush(UIDS.staff, markReceived(UIDS.admin, 10, { received: false, status: 'Needed' })));
});

test('every V8C4 update to a row the native app has written is refused — it carries no rev', async () => {
  await given(pwaRow({ rev: 3 }));
  for (const uid of [UIDS.admin, UIDS.staff, UIDS.worker]) {
    await refused(pwaPush(uid, pwaRow({ rev: 3, name: 'Rack, edited' })));
  }
});

// --- still accepted: the PWA stops by decision, not by these rules --------------------

test('still accepted on a row the native app never wrote: create, top-up, edit, an Administrator\'s delete', async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('purchase').doc(ID).delete();
  });
  await assertSucceeds(pwaPush(UIDS.worker, pwaRow()));

  await assertSucceeds(pwaPush(UIDS.staff, pwaRow({ qty: 15 })));
  await assertSucceeds(pwaPush(UIDS.worker, pwaRow({ qty: 15, name: 'Rack, edited', note: 'Kandivali' })));
  await assertSucceeds(pwaPush(UIDS.admin, pwaRow({ qty: 15, name: 'Rack, edited', note: 'Kandivali', del: 1 })));
});

test('still accepted: an Administrator\'s cancel or restore where received is already 0', async () => {
  // `received` does not change, so no boolean is asked for; an Administrator
  // may cancel an open requirement and reopen a cancelled one.
  await given(pwaRow({ received: 0 }));
  await assertSucceeds(pwaPush(UIDS.admin, pwaRow({ cancelledBy: name(UIDS.admin), cancelledUid: UIDS.admin,
    cancelledAt: 1712700000000, received: 0, status: 'Cancelled' })));
  await assertSucceeds(pwaPush(UIDS.admin, pwaRow({ cancelledUid: UIDS.admin, received: 0, status: 'Needed' })));
});
