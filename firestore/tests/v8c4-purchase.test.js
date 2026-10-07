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
 * **Since N5.12 commit 4b they do** — the Owner's hard-block, option (b) of
 * 2026-10-07: every `/purchase` create and update must carry `rev`, and V8C4
 * never sends one. So that each refusal below still proves **its own
 * clause**, its V8C4 payload carries the `rev` the native app would send
 * (`NATIVE_REV`): the write is then refused by the clause the test names,
 * not by the missing `rev`. The 19 writes that were still accepted are at the
 * end, each refused without `rev` beside a witness that the same write with
 * it is accepted — so the refusal is the hard-block's.
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

/**
 * The write as described: the whole local row, merged, stamped with the
 * caller. With [rev], the same write carrying the revision the native app
 * would send — the stored one plus one, so 1 on a row V8C4 wrote.
 */
function pwaPush(uid, local, rev) {
  const db = as(testEnv, uid);
  const ref = db.collection('purchase').doc(ID);
  return db.runTransaction(async (tx) => {
    await tx.get(ref);
    tx.set(ref, {
      ...local, upBy: name(uid), upUid: uid, updated: Date.now(),
      serverAt: firebase.firestore.FieldValue.serverTimestamp(),
      ...(rev === undefined ? {} : { rev }),
    }, { merge: true });
  });
}

/** The `rev` the native app sends on a row V8C4 wrote, which carries none. */
const NATIVE_REV = 1;

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
    await refused(pwaPush(uid, markReceived(uid, 10), NATIVE_REV));
    await refused(pwaPush(uid, markReceived(uid, 12), NATIVE_REV));
  }
});

test('the received: 1 short close — 4 of 10, nothing written off — is refused three ways', async () => {
  // `received` is not a boolean; a status may move to Received only with
  // `received: true`; and whatever closes a requirement meets its total.
  for (const uid of [UIDS.staff, UIDS.admin]) {
    await given(pwaRow());
    await refused(pwaPush(uid, markReceived(uid, 4), NATIVE_REV));
    await refused(pwaPush(uid, markReceived(uid, 0), NATIVE_REV));
    // Even with a boolean it is short, so it is still refused…
    await refused(pwaPush(uid, markReceived(uid, 4, { received: true }), NATIVE_REV));
  }
  // …and the native app's write-off — the total set to what arrived — closes it.
  await given(pwaRow({ rcvQty: 4, rcvBy: name(UIDS.staff), rcvUid: UIDS.staff, rcvAt: 1712600000000, received: false }));
  await assertSucceeds(pwaPush(UIDS.staff, pwaRow({ rcvQty: 4, rcvBy: name(UIDS.staff), rcvUid: UIDS.staff,
    rcvAt: 1712600000000, qty: 4, received: true, status: 'Received', rev: 1 })));
});

test('V8C4\'s cancel and restore write received: 0, and are refused — an Administrator\'s included', async () => {
  await given(pwaRow());
  await refused(pwaPush(UIDS.admin, pwaRow({ cancelledBy: name(UIDS.admin), cancelledUid: UIDS.admin,
    cancelledAt: Date.now(), received: 0, status: 'Cancelled' }), NATIVE_REV));

  await given(markReceived(UIDS.admin, 10));
  await refused(pwaPush(UIDS.admin, markReceived(UIDS.admin, 10, { received: 0, status: 'Needed', stocked: 0, stockedQty: 0 }), NATIVE_REV));
});

// --- refused: how status may move ---------------------------------------------------

test('V8C4\'s "Ordered" is refused — it carries no Ordered stamp, and a Manager may not order at all', async () => {
  // The Owner's own Ordered, N5.10b commit 9, is an Owner's or an
  // Administrator's, with `orderedBy` / `orderedUid` / `orderedAt` written
  // in the same write. V8C4 sends the status alone.
  await given(pwaRow());
  await refused(pwaPush(UIDS.admin, pwaRow({ status: 'Ordered' }), NATIVE_REV));
  await refused(pwaPush(UIDS.staff, pwaRow({ status: 'Ordered' }), NATIVE_REV));
});

test('a Manager\'s V8C4 cancel stays refused — it writes received, which a Manager\'s cancel does not', async () => {
  // A Manager may cancel since N5.10b commit 9: the status, the cancel stamp
  // and the update stamp, nothing else. V8C4's merge adds `received: false`
  // to a row that had none, and that one key refuses it.
  await given(pwaRow());
  await refused(pwaPush(UIDS.staff, pwaRow({ cancelledBy: name(UIDS.staff), cancelledUid: UIDS.staff,
    cancelledAt: Date.now(), received: false, status: 'Cancelled' }), NATIVE_REV));
});

// --- refused: decided not to fix (QZ) --------------------------------------------------

test('a Manager\'s receipt with add-to-stock stays refused (D1, not fixed)', async () => {
  // `stocked` and `stockedQty` are in no Manager's list. Sent with a boolean,
  // so the stock keys are the only thing wrong.
  await given(pwaRow());
  await refused(pwaPush(UIDS.staff, markReceived(UIDS.staff, 10, { received: true, stocked: 1, stockedQty: 10 }), NATIVE_REV));
});

test('a Manager\'s restore stays refused, and reopen stays Owner and Administrator (D4, not fixed)', async () => {
  await given(markReceived(UIDS.admin, 10, { received: true }));
  await refused(pwaPush(UIDS.staff, markReceived(UIDS.admin, 10, { received: false, status: 'Needed' }), NATIVE_REV));
});

test('every V8C4 update to a row the native app has written is refused — it carries no rev', async () => {
  await given(pwaRow({ rev: 3 }));
  for (const uid of [UIDS.admin, UIDS.staff, UIDS.worker]) {
    await refused(pwaPush(uid, pwaRow({ rev: 3, name: 'Rack, edited' })));
  }
});

// --- since N5.12 commit 4b, refused: the writes that were still accepted ----------
//
// Until the hard-block these were accepted on a row the native app never wrote
// — 19 of V8C4's payloads in the differential replay (`firestore/tools/
// diffrules.js`), pinned here by category. Each is now refused for want of
// `rev`, beside a witness: the same write carrying the native `rev` is
// accepted, so the refusal is the hard-block's and nothing else's.

test('since N5.12, a V8C4 create is refused — it carries no rev', async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('purchase').doc(ID).delete();
  });
  await refused(pwaPush(UIDS.worker, pwaRow()));
  await assertSucceeds(pwaPush(UIDS.worker, pwaRow(), NATIVE_REV));
});

test('since N5.12, the hard-block asks for exactly the native revision — not any rev', async () => {
  // A create is revision 1; an update is the stored revision plus one, so 1
  // on a row V8C4 wrote. Anything else is refused, like no rev at all.
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('purchase').doc(ID).delete();
  });
  await refused(pwaPush(UIDS.worker, pwaRow(), 2));
  await refused(pwaPush(UIDS.worker, pwaRow(), 0));
  await given(pwaRow());
  await refused(pwaPush(UIDS.staff, pwaRow({ qty: 15 }), 2));
  await refused(pwaPush(UIDS.staff, pwaRow({ qty: 15 }), 0));
});

test('since N5.12, a V8C4 top-up is refused — it carries no rev', async () => {
  await given(pwaRow());
  await refused(pwaPush(UIDS.staff, pwaRow({ qty: 15 })));
  await assertSucceeds(pwaPush(UIDS.staff, pwaRow({ qty: 15 }), NATIVE_REV));
});

test('since N5.12, a V8C4 edit of name, quantity and note is refused — it carries no rev', async () => {
  await given(pwaRow({ qty: 15 }));
  await refused(pwaPush(UIDS.worker, pwaRow({ qty: 15, name: 'Rack, edited', note: 'Kandivali' })));
  await assertSucceeds(pwaPush(UIDS.worker, pwaRow({ qty: 15, name: 'Rack, edited', note: 'Kandivali' }), NATIVE_REV));
});

test('since N5.12, an Administrator\'s V8C4 delete (del: 1) is refused — it carries no rev', async () => {
  const edited = { qty: 15, name: 'Rack, edited', note: 'Kandivali' };
  await given(pwaRow(edited));
  await refused(pwaPush(UIDS.admin, pwaRow({ ...edited, del: 1 })));
  await assertSucceeds(pwaPush(UIDS.admin, pwaRow({ ...edited, del: 1 }), NATIVE_REV));
});

test('since N5.12, an Administrator\'s V8C4 cancel and restore where received is already 0 are refused — no rev', async () => {
  // `received` does not change, so no boolean is asked for; an Administrator
  // may cancel an open requirement nothing has arrived against — V8C4's
  // cancel carries a whole stamp, the caller's — and reopen a cancelled one.
  const cancel = pwaRow({ cancelledBy: name(UIDS.admin), cancelledUid: UIDS.admin,
    cancelledAt: 1712700000000, received: 0, status: 'Cancelled' });
  await given(pwaRow({ received: 0 }));
  await refused(pwaPush(UIDS.admin, cancel));
  await assertSucceeds(pwaPush(UIDS.admin, cancel, NATIVE_REV));

  const restore = pwaRow({ cancelledUid: UIDS.admin, received: 0, status: 'Needed' });
  await given(cancel);
  await refused(pwaPush(UIDS.admin, restore));
  await assertSucceeds(pwaPush(UIDS.admin, restore, NATIVE_REV));
});

// --- what N5.10b commit 9 changed for V8C4's own shapes ------------------------------
//
// Found by the differential replay of commit 8b's rules against commit 9's
// (`firestore/tools/diffrules.js`): of V8C4's payloads, exactly these two
// decisions moved. One more was accepted and one fewer, so the count of V8C4
// writes still accepted on rows the native app never wrote stayed 19 — until
// N5.12's hard-block refused them all.

test('since commit 9 an Administrator may take a V8C4 Ordered row back to Needed — with rev, since N5.12; a Manager still may not', async () => {
  await given(pwaRow({ status: 'Ordered' }));
  await refused(pwaPush(UIDS.staff, pwaRow({ status: 'Needed' }), NATIVE_REV));
  await refused(pwaPush(UIDS.admin, pwaRow({ status: 'Needed' })));
  await assertSucceeds(pwaPush(UIDS.admin, pwaRow({ status: 'Needed' }), NATIVE_REV));
});

test('since commit 9, an Administrator\'s V8C4 cancel of a restored row with stale receipt fields is refused — nothing received binds an Administrator', async () => {
  // V8C4 deletes the receipt locally; its merge leaves it in Firestore, so the
  // row still counts as received. An Administrator may still remove it — with
  // rev, since N5.12 — and the N8 cleanup clears the fields.
  const stale = { rcvQty: 4, rcvBy: name(UIDS.admin), rcvUid: UIDS.admin, rcvAt: 1712100000000, received: 0 };
  await given(pwaRow({ ...stale, stocked: 0, stockedQty: 0 }));
  await refused(pwaPush(UIDS.admin, pwaRow({ received: 0, cancelledBy: name(UIDS.admin),
    cancelledUid: UIDS.admin, cancelledAt: Date.now(), status: 'Cancelled' }), NATIVE_REV));
  await refused(pwaPush(UIDS.admin, pwaRow({ ...stale, del: 1 })));
  await assertSucceeds(pwaPush(UIDS.admin, pwaRow({ ...stale, del: 1 }), NATIVE_REV));
});
