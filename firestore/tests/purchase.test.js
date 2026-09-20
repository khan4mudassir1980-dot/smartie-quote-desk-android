const test = require('node:test');
const { assertFails, assertSucceeds } = require('@firebase/rules-unit-testing');
const firebase = require('firebase/compat/app');
require('firebase/compat/firestore');
const { createTestEnvironment, seed, as, UIDS } = require('./helpers');

/**
 * Purchase requirements, and the five ways the v9 rules refuse a write
 * without saying why.
 *
 * `data.test.js` already covers the plain role matrix. What is here is the
 * part that only shows up against **V8C4 data**: on an update
 * `request.resource.data` is the merged post-state, so a rule about `qty` or
 * `id` lands on whatever the PWA happened to store — and `touched()` reports
 * keys *added*, so a field written helpfully can refuse an ordinary save.
 *
 * Each of these was found by reading the rules against
 * `app/src/test/resources/fixtures/purchase.json`, and each is the reason a
 * line exists in `PurchaseWrite.base()`.
 */

let testEnv;

test.before(async () => { testEnv = await createTestEnvironment(); });
test.after(async () => { await testEnv?.cleanup(); });
test.beforeEach(async () => { await seed(testEnv); });

/** The V8C4 shapes that actually exist, planted with the rules disabled. */
async function given(id, fields) {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('purchase').doc(id).set(fields);
  });
}

const HEALTHY = {
  id: 'pr_healthy', name: 'Rack', qty: 4, urgency: 'normal', status: 'Needed',
  byUid: UIDS.worker, t: 1712000000000, updated: 1712000000000, rev: 1,
};

/** Everything `PurchaseWrite.base()` puts on every update. */
const base = (id, rev, qty) => ({
  id, qty, updated: Date.now(), rev: rev + 1,
  upBy: 'Asha', upUid: UIDS.admin,
  serverAt: firebase.firestore.FieldValue.serverTimestamp(),
});

// --- trap 1: a legacy string `qty` -----------------------------------------

test('a legacy string qty makes the row unupdatable until it is rewritten', async () => {
  // `pr_received_legacy` in the fixtures holds "10". The rule
  // `qty is number && qty > 0` is applied to the MERGED post-state, so
  // leaving the stored value alone leaves a string there.
  await given('pr_string', { ...HEALTHY, id: 'pr_string', qty: '10' });
  const db = as(testEnv, UIDS.staff);

  await assertFails(
    db.collection('purchase').doc('pr_string').update({ urgency: 'critical', updated: Date.now() })
  );
});

test('and the same write succeeds once qty is sent as a number', async () => {
  await given('pr_string', { ...HEALTHY, id: 'pr_string', qty: '10' });
  const db = as(testEnv, UIDS.staff);

  await assertSucceeds(
    db.collection('purchase').doc('pr_string').update({
      ...base('pr_string', 1, 10), urgency: 'critical',
    })
  );
});

// --- trap 2: a document with no `id` field ----------------------------------

test('a row with no id of its own is unupdatable until one is written', async () => {
  // `request.resource.data.id == id` is merged post-state too, and the reader
  // defaulting `id` to the document id is the evidence such rows exist.
  const { id, ...withoutId } = HEALTHY;
  await given('pr_noid', withoutId);
  const db = as(testEnv, UIDS.staff);

  await assertFails(
    db.collection('purchase').doc('pr_noid').update({ qty: 5, updated: Date.now() })
  );
});

test('and the same write succeeds once the id is re-asserted', async () => {
  const { id, ...withoutId } = HEALTHY;
  await given('pr_noid', withoutId);
  const db = as(testEnv, UIDS.staff);

  await assertSucceeds(
    db.collection('purchase').doc('pr_noid').update(base('pr_noid', 1, 5))
  );
});

// --- trap 3: a `del` key added where there was none -------------------------

test('adding del to a row that has no del key refuses an ordinary save', async () => {
  // `touched()` is diff().affectedKeys(), which reports keys ADDED — so
  // writing `del: false` helpfully trips the guard that reserves soft delete
  // for an Administrator, and refuses a Manager's perfectly ordinary edit.
  const { id, ...rest } = HEALTHY;
  await given('pr_nodel', { ...rest, id: 'pr_nodel' });
  const db = as(testEnv, UIDS.staff);

  await assertFails(
    db.collection('purchase').doc('pr_nodel').update({ ...base('pr_nodel', 1, 4), del: false })
  );
});

test('and the identical save succeeds when it mentions no del at all', async () => {
  await given('pr_nodel', { ...HEALTHY, id: 'pr_nodel' });
  const db = as(testEnv, UIDS.staff);

  await assertSucceeds(
    db.collection('purchase').doc('pr_nodel').update(base('pr_nodel', 1, 4))
  );
});

test('an Administrator is the one who may set del', async () => {
  await given('pr_nodel', { ...HEALTHY, id: 'pr_nodel' });

  await assertSucceeds(
    as(testEnv, UIDS.admin).collection('purchase').doc('pr_nodel').update({
      ...base('pr_nodel', 1, 4), del: true, deletedBy: UIDS.admin,
    })
  );
});

// --- trap 4: `updated` must be a number -------------------------------------

test('a server timestamp in updated is refused; the rules want a number', async () => {
  await given('pr_healthy', HEALTHY);
  const db = as(testEnv, UIDS.staff);

  await assertFails(
    db.collection('purchase').doc('pr_healthy').update({
      id: 'pr_healthy', qty: 4, rev: 2,
      updated: firebase.firestore.FieldValue.serverTimestamp(),
    })
  );
});

test('epoch millis in updated, with the server clock beside it, is accepted', async () => {
  await given('pr_healthy', HEALTHY);
  const db = as(testEnv, UIDS.staff);

  await assertSucceeds(
    db.collection('purchase').doc('pr_healthy').update(base('pr_healthy', 1, 4))
  );
});

// --- trap 5: `rev` is what stops two devices completing the same thing ------

test('a stale revision loses rather than overwriting silently', async () => {
  await given('pr_healthy', HEALTHY);
  const db = as(testEnv, UIDS.staff);

  await assertSucceeds(db.collection('purchase').doc('pr_healthy').update(base('pr_healthy', 1, 4)));
  // A second device still holding rev 1 computes the same rev 2 and loses.
  await assertFails(db.collection('purchase').doc('pr_healthy').update(base('pr_healthy', 1, 4)));
});

test('once a row carries a rev, omitting it is refused, not tolerated', async () => {
  // `revOk()` reads the MERGED post-state, so a row that already has `rev: 1`
  // still has it after an update that never mentioned it — and `1 == 1 + 1`
  // is false. The tolerance is narrower than the rule reads at a glance.
  await given('pr_healthy', HEALTHY);
  const db = as(testEnv, UIDS.staff);

  await assertFails(
    db.collection('purchase').doc('pr_healthy').update({ id: 'pr_healthy', qty: 4, updated: Date.now() })
  );
});

test('a V8C4 row that never had a rev is the one case the tolerance is for', async () => {
  // That is the whole of it: the PWA writes no `rev`, so its own documents
  // stay updatable. Every native update sends `stored.rev + 1` regardless,
  // which is what turns the tolerance back into a guard.
  const { rev, ...withoutRev } = HEALTHY;
  await given('pr_norev', { ...withoutRev, id: 'pr_norev' });
  const db = as(testEnv, UIDS.staff);

  await assertSucceeds(
    db.collection('purchase').doc('pr_norev').update({ id: 'pr_norev', qty: 4, updated: Date.now() })
  );
});

// --- a row with no creation time -------------------------------------------

test('a row with no creation time is still updatable', async () => {
  // `pr_critical_no_created` has no `t`. Nothing in the rules asks for one,
  // and the reader falls back to `updated` — which is also why the board must
  // never order this collection server-side by `t`.
  const { t, ...withoutT } = HEALTHY;
  await given('pr_not', { ...withoutT, id: 'pr_not' });

  await assertSucceeds(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_not').update(base('pr_not', 1, 4))
  );
});

// --- the app's own payloads -------------------------------------------------

test('the create payload the app sends is accepted', async () => {
  await assertSucceeds(
    as(testEnv, UIDS.worker).collection('purchase').doc('pr_new').set({
      id: 'pr_new', name: 'Anchor bolts', qty: 20, urgency: 'normal',
      status: 'Needed', note: '', by: 'Wes', byUid: UIDS.worker,
      t: Date.now(), updated: Date.now(), rev: 1,
      received: false, del: false,
      serverAt: firebase.firestore.FieldValue.serverTimestamp(),
    })
  );
});

test('a create may not claim somebody else, a different status or a bad urgency', async () => {
  const db = as(testEnv, UIDS.worker);
  const good = {
    id: 'pr_bad', name: 'Anchor bolts', qty: 20, urgency: 'normal',
    status: 'Needed', byUid: UIDS.worker, t: Date.now(), updated: Date.now(),
  };

  await assertFails(db.collection('purchase').doc('pr_bad').set({ ...good, byUid: UIDS.admin }));
  await assertFails(db.collection('purchase').doc('pr_bad').set({ ...good, status: 'Received' }));
  await assertFails(db.collection('purchase').doc('pr_bad').set({ ...good, urgency: 'later' }));
  await assertFails(db.collection('purchase').doc('pr_bad').set({ ...good, qty: 0 }));
  await assertFails(db.collection('purchase').doc('pr_bad').set({ ...good, received: true }));
  await assertFails(db.collection('purchase').doc('pr_bad').set({ ...good, del: true }));
});

test('the receive payload the app sends is accepted', async () => {
  await given('pr_healthy', HEALTHY);

  await assertSucceeds(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_healthy').update({
      ...base('pr_healthy', 1, 4),
      status: 'Received', received: true,
      rcvQty: 4, rcvBy: 'Sam', rcvUid: UIDS.staff, rcvAt: Date.now(),
    })
  );
});

test('the reopen payload deletes the received fields rather than blanking them', async () => {
  await given('pr_done', {
    ...HEALTHY, id: 'pr_done', status: 'Received', received: true,
    rcvQty: 4, rcvBy: 'Sam', rcvUid: UIDS.staff, rcvAt: Date.now(),
  });

  await assertSucceeds(
    as(testEnv, UIDS.admin).collection('purchase').doc('pr_done').update({
      ...base('pr_done', 1, 4),
      status: 'Needed', received: false,
      rcvQty: firebase.firestore.FieldValue.delete(),
      rcvBy: firebase.firestore.FieldValue.delete(),
      rcvUid: firebase.firestore.FieldValue.delete(),
      rcvAt: firebase.firestore.FieldValue.delete(),
    })
  );
});

test('a Manager may reopen too, which is the gap the app closes and the rules cannot', async () => {
  // Reopening is an ordinary update, and `staff()` may update. Owner-and-
  // Administrator-only lives in `Permissions.canReopenPurchase` alone.
  // Tightening the rules needs the V8C4 source to confirm the PWA never
  // offers a Manager an un-receive. Recorded in docs/N4-plan.md.
  await given('pr_done', {
    ...HEALTHY, id: 'pr_done', status: 'Received', received: true,
    rcvQty: 4, rcvBy: 'Sam', rcvUid: UIDS.staff, rcvAt: Date.now(),
  });

  await assertSucceeds(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_done').update({
      ...base('pr_done', 1, 4), status: 'Needed', received: false,
    })
  );
});

// --- what never changes ------------------------------------------------------

test('a requirement is never hard deleted, by anyone', async () => {
  await given('pr_healthy', HEALTHY);

  for (const uid of [UIDS.primaryOwner, UIDS.admin, UIDS.staff, UIDS.worker]) {
    await assertFails(as(testEnv, uid).collection('purchase').doc('pr_healthy').delete());
  }
});

test('a Worker adds but never updates, not even their own requirement', async () => {
  await given('pr_mine', { ...HEALTHY, id: 'pr_mine', byUid: UIDS.worker });

  await assertFails(
    as(testEnv, UIDS.worker).collection('purchase').doc('pr_mine').update(base('pr_mine', 1, 4))
  );
});

test('a switched-off account does nothing at all', async () => {
  await given('pr_healthy', HEALTHY);
  const db = as(testEnv, UIDS.switchedOff);

  await assertFails(db.collection('purchase').doc('pr_healthy').get());
  await assertFails(db.collection('purchase').doc('pr_healthy').update(base('pr_healthy', 1, 4)));
});
