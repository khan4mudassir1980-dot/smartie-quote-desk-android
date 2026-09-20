const test = require('node:test');
const assert = require('node:assert/strict');
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

// `byUid` is the limited role's own uid, so "the person who raised it" and
// "somebody else" are both one edit away in every test below. Stored
// `worker` is displayed **Staff**; the value is not renamed.
const HEALTHY = {
  id: 'pr_healthy', name: 'Rack', qty: 4, urgency: 'normal', status: 'Needed',
  by: 'Staff Person', byUid: UIDS.worker,
  t: 1712000000000, updated: 1712000000000, rev: 1,
};

/** Everything `PurchaseWrite.base()` puts on every update. */
const base = (id, rev, qty) => ({
  id, qty, updated: Date.now(), rev: rev + 1,
  upBy: 'Asha', upUid: UIDS.admin,
  serverAt: firebase.firestore.FieldValue.serverTimestamp(),
});

// --- who owns a requirement, and for how long -------------------------------

test('the limited role corrects the requirement it raised itself', async () => {
  await given('pr_healthy', HEALTHY);

  await assertSucceeds(
    as(testEnv, UIDS.worker).collection('purchase').doc('pr_healthy').update({
      ...base('pr_healthy', 1, 6),
      name: 'Sliding gate rack', note: 'For the Kandivali site', urgency: 'urgent',
    })
  );
});

test('and takes it off the list again while nothing has arrived', async () => {
  await given('pr_healthy', HEALTHY);

  await assertSucceeds(
    as(testEnv, UIDS.worker).collection('purchase').doc('pr_healthy').update({
      id: 'pr_healthy', updated: Date.now(), rev: 2,
      upBy: 'Staff Person', upUid: UIDS.worker,
      serverAt: firebase.firestore.FieldValue.serverTimestamp(),
      del: true, deletedBy: UIDS.worker,
    })
  );
});

test('but not somebody else\'s requirement', async () => {
  await given('pr_theirs', { ...HEALTHY, id: 'pr_theirs', byUid: UIDS.staff });

  await assertFails(
    as(testEnv, UIDS.worker).collection('purchase').doc('pr_theirs').update({
      ...base('pr_theirs', 1, 6), name: 'Mine now',
    })
  );
});

test('a requirement with no recorded creator belongs to nobody', async () => {
  // Most of what the PWA wrote has no byUid at all. '' == '' would hand
  // every one of those rows to whoever happened to be signed in.
  const { byUid, ...orphan } = HEALTHY;
  await given('pr_orphan', { ...orphan, id: 'pr_orphan' });

  await assertFails(
    as(testEnv, UIDS.worker).collection('purchase').doc('pr_orphan').update({
      ...base('pr_orphan', 1, 6), name: 'Mine now',
    })
  );
});

test('the creator may not rewrite who raised it, or when', async () => {
  await given('pr_healthy', HEALTHY);
  const db = as(testEnv, UIDS.worker).collection('purchase').doc('pr_healthy');

  await assertFails(db.update({ ...base('pr_healthy', 1, 6), byUid: UIDS.staff }));
  await assertFails(db.update({ ...base('pr_healthy', 1, 6), by: 'Somebody else' }));
  await assertFails(db.update({ ...base('pr_healthy', 1, 6), t: 1 }));
});

test('not even an Administrator may rewrite who raised a requirement', async () => {
  // prIdentityPinned() sits outside the branch disjunction, so it binds the
  // Administrator too. That is true by construction, which is exactly why it
  // is worth a test: nothing else would notice if the pin were moved inside
  // a branch one day.
  await given('pr_healthy', HEALTHY);
  const db = as(testEnv, UIDS.admin).collection('purchase').doc('pr_healthy');

  await assertFails(db.update({ ...base('pr_healthy', 1, 4), byUid: UIDS.admin }));
  await assertFails(db.update({ ...base('pr_healthy', 1, 4), by: 'Administrator' }));
  await assertFails(db.update({ ...base('pr_healthy', 1, 4), t: 1 }));

  // And the identical write without them is accepted, which is what makes
  // the three refusals mean the pin rather than something else in the rule.
  // A refused write stores nothing, so rev 2 is still the right next one.
  await assertSucceeds(db.update(base('pr_healthy', 1, 4)));
});

test('nor the receipt, the status or any audit field', async () => {
  await given('pr_healthy', HEALTHY);
  const db = as(testEnv, UIDS.worker).collection('purchase').doc('pr_healthy');
  const good = base('pr_healthy', 1, 6);

  await assertFails(db.update({ ...good, rcvQty: 4 }));
  await assertFails(db.update({ ...good, received: true }));
  await assertFails(db.update({ ...good, status: 'Received' }));
  await assertFails(db.update({ ...good, rcvBy: 'Staff Person', rcvUid: UIDS.worker }));
  await assertFails(db.update({ ...good, stocked: true }));
});

test('an edit may not smuggle a removal, and a removal may not smuggle an edit', async () => {
  await given('pr_healthy', HEALTHY);
  const db = as(testEnv, UIDS.worker).collection('purchase').doc('pr_healthy');

  await assertFails(db.update({ ...base('pr_healthy', 1, 6), name: 'Gone', del: true }));
  await assertFails(
    db.update({
      id: 'pr_healthy', updated: Date.now(), rev: 2, qty: 99,
      upBy: 'Staff Person', upUid: UIDS.worker,
      del: true, deletedBy: UIDS.worker,
    })
  );
});

test('the creator loses the requirement the moment something arrives', async () => {
  await given('pr_part', {
    ...HEALTHY, id: 'pr_part', qty: 10,
    rcvQty: 4, rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: 1712600000000,
  });
  const db = as(testEnv, UIDS.worker).collection('purchase').doc('pr_part');

  await assertFails(db.update({ ...base('pr_part', 1, 10), name: 'Too late' }));
  await assertFails(
    db.update({
      id: 'pr_part', updated: Date.now(), rev: 2,
      upBy: 'Staff Person', upUid: UIDS.worker,
      del: true, deletedBy: UIDS.worker,
    })
  );
});

test('clearing the receipt first does not reopen the window', async () => {
  // The two guards that stop this are independent: prUntouched() reads the
  // STORED document, and a removed key lands in touched(), which no
  // creator list admits.
  await given('pr_part', {
    ...HEALTHY, id: 'pr_part', qty: 10,
    rcvQty: 4, rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: 1712600000000,
  });

  await assertFails(
    as(testEnv, UIDS.worker).collection('purchase').doc('pr_part').update({
      ...base('pr_part', 1, 10),
      rcvQty: firebase.firestore.FieldValue.delete(),
      rcvBy: firebase.firestore.FieldValue.delete(),
      rcvUid: firebase.firestore.FieldValue.delete(),
      rcvAt: firebase.firestore.FieldValue.delete(),
      name: 'Mine again',
    })
  );
});

test('and the creator still cannot receive, reopen or hard delete', async () => {
  await given('pr_healthy', HEALTHY);
  const db = as(testEnv, UIDS.worker).collection('purchase').doc('pr_healthy');

  await assertFails(
    db.update({
      ...base('pr_healthy', 1, 4),
      status: 'Received', received: true, rcvQty: 4,
      rcvBy: 'Staff Person', rcvUid: UIDS.worker, rcvAt: Date.now(),
    })
  );
  await assertFails(db.delete());
});

test('a reopened requirement is its creator\'s again', async () => {
  // The Owner's decision: reopen removes the receipt outright, so the row is
  // untouched and means as good as new. Recorded in docs/N4.2-plan.md.
  await given('pr_reopened', { ...HEALTHY, id: 'pr_reopened', received: false, status: 'Needed' });

  await assertSucceeds(
    as(testEnv, UIDS.worker).collection('purchase').doc('pr_reopened').update({
      ...base('pr_reopened', 1, 6), name: 'Corrected again',
    })
  );
});

// --- the Manager, and what a delivery takes away -----------------------------

test('a Manager edits anybody\'s untouched requirement, as before', async () => {
  await given('pr_healthy', HEALTHY);

  await assertSucceeds(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_healthy').update({
      ...base('pr_healthy', 1, 6), name: 'Sliding gate rack', urgency: 'critical',
    })
  );
});

test('but not one a delivery has reached', async () => {
  await given('pr_part', {
    ...HEALTHY, id: 'pr_part', qty: 10,
    rcvQty: 4, rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: 1712600000000,
  });

  await assertFails(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_part').update({
      ...base('pr_part', 1, 10), name: 'Too late', urgency: 'critical',
    })
  );
});

test('a Manager removes their own untouched requirement and no other', async () => {
  await given('pr_mine', { ...HEALTHY, id: 'pr_mine', byUid: UIDS.staff });
  await assertSucceeds(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_mine').update({
      id: 'pr_mine', updated: Date.now(), rev: 2,
      upBy: 'Manager Person', upUid: UIDS.staff,
      del: true, deletedBy: UIDS.staff,
    })
  );

  await given('pr_theirs', { ...HEALTHY, id: 'pr_theirs' });
  await assertFails(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_theirs').update({
      id: 'pr_theirs', updated: Date.now(), rev: 2,
      upBy: 'Manager Person', upUid: UIDS.staff,
      del: true, deletedBy: UIDS.staff,
    })
  );
});

test('a Manager reopening a received requirement is refused by the rules', async () => {
  // This replaces the test that asserted the opposite. It was true, and
  // docs/N4-plan.md recorded reopen as the one restriction the rules could
  // not express. They express it now, and not by a special case: a reopen
  // removes rcvQty, and no branch below an Administrator may let a received
  // total fall.
  await given('pr_done', {
    ...HEALTHY, id: 'pr_done', status: 'Received', received: true,
    rcvQty: 4, rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: 1712600000000,
  });

  await assertFails(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_done').update({
      ...base('pr_done', 1, 4), status: 'Needed', received: false,
    })
  );
  await assertFails(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_done').update({
      ...base('pr_done', 1, 4), status: 'Needed', received: false,
      rcvQty: firebase.firestore.FieldValue.delete(),
      rcvBy: firebase.firestore.FieldValue.delete(),
      rcvUid: firebase.firestore.FieldValue.delete(),
      rcvAt: firebase.firestore.FieldValue.delete(),
    })
  );
});

test('a received total may not be reduced by a Manager', async () => {
  await given('pr_part', {
    ...HEALTHY, id: 'pr_part', qty: 10,
    rcvQty: 6, rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: 1712600000000,
  });

  await assertFails(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_part').update({
      ...base('pr_part', 1, 10),
      status: 'Needed', received: false,
      rcvQty: 2, rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: Date.now(),
    })
  );
});

// --- writing off what is not coming --------------------------------------------

test('a Manager closes a shortfall at exactly the stored receipt', async () => {
  await given('pr_part', {
    ...HEALTHY, id: 'pr_part', qty: 10,
    rcvQty: 7, rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: 1712600000000,
  });

  await assertSucceeds(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_part').update({
      ...base('pr_part', 1, 7), status: 'Received', received: true,
    })
  );
});

test('and the stored document is exactly what the shortfall promised', async () => {
  // The test above proves the write is *allowed*. This one is about what it
  // did: a shortfall rewrites a stored quantity and closes a requirement, so
  // "permitted" is not the same as "correct", and nothing else in this suite
  // reads a document back.
  await given('pr_part', {
    ...HEALTHY, id: 'pr_part', qty: 10,
    rcvQty: 7, rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: 1712600000000,
  });

  await assertSucceeds(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_part').update({
      ...base('pr_part', 1, 7), status: 'Received', received: true,
    })
  );

  // `withSecurityRulesDisabled` does not hand back the callback's value, so
  // the row is captured rather than returned — as in photos.test.js.
  let row;
  await testEnv.withSecurityRulesDisabled(async (context) => {
    row = (await context.firestore().collection('purchase').doc('pr_part').get()).data();
  });

  assert.equal(row.qty, 7, 'the required total becomes the receipt');
  assert.equal(row.received, true);
  assert.equal(row.status, 'Received');

  // The receipt records a delivery somebody made, and the person writing off
  // the remainder is usually not that person. All four survive untouched.
  assert.equal(row.rcvQty, 7);
  assert.equal(row.rcvBy, 'Manager Person');
  assert.equal(row.rcvUid, UIDS.staff);
  assert.equal(row.rcvAt, 1712600000000);

  assert.equal(row.rev, 2, 'the stored revision plus one, as every update does');

  // The updater field is its own thing, distinct from the receipt's rcvUid:
  // who wrote off the rest is not who took the delivery in. `base()` stamps
  // a fixed updater rather than deriving one from the caller, so this says
  // the field survives and differs from rcvUid — not that Firestore filled
  // it in.
  assert.equal(row.upUid, UIDS.admin);
  assert.notEqual(row.upUid, row.rcvUid);

  assert.equal(row.byUid, UIDS.worker, 'and who raised it is untouched');
  assert.equal(row.t, 1712000000000);
});

test('and at no other quantity whatsoever', async () => {
  await given('pr_part', {
    ...HEALTHY, id: 'pr_part', qty: 10,
    rcvQty: 7, rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: 1712600000000,
  });
  const db = as(testEnv, UIDS.staff).collection('purchase').doc('pr_part');

  // Below the receipt, above it, and the original total: all refused.
  await assertFails(db.update({ ...base('pr_part', 1, 5), status: 'Received', received: true }));
  await assertFails(db.update({ ...base('pr_part', 1, 8), status: 'Received', received: true }));
  await assertFails(db.update({ ...base('pr_part', 1, 10), status: 'Received', received: true }));
});

test('a shortfall may not rewrite the receipt while it writes off the rest', async () => {
  await given('pr_part', {
    ...HEALTHY, id: 'pr_part', qty: 10,
    rcvQty: 7, rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: 1712600000000,
  });

  await assertFails(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_part').update({
      ...base('pr_part', 1, 7), status: 'Received', received: true,
      rcvBy: 'Somebody else', rcvUid: UIDS.staff,
    })
  );
});

test('nothing to write off: an untouched requirement cannot be closed short', async () => {
  await given('pr_healthy', HEALTHY);

  await assertFails(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_healthy').update({
      ...base('pr_healthy', 1, 0.0001), status: 'Received', received: true,
    })
  );
});

test('the limited role can never close a shortfall', async () => {
  await given('pr_part', {
    ...HEALTHY, id: 'pr_part', qty: 10,
    rcvQty: 7, rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: 1712600000000,
  });

  await assertFails(
    as(testEnv, UIDS.worker).collection('purchase').doc('pr_part').update({
      ...base('pr_part', 1, 7), status: 'Received', received: true,
    })
  );
});

// --- a receipt total the rules will not reason about ----------------------------

test('a string receipt total fails closed for a Manager, both ways', async () => {
  // Comparing a string to a number here raises an error rather than
  // coercing, so the rule refuses rather than guessing.
  await given('pr_legacy', {
    ...HEALTHY, id: 'pr_legacy', qty: 10,
    rcvQty: '4', rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: 1712600000000,
  });
  const db = as(testEnv, UIDS.staff).collection('purchase').doc('pr_legacy');

  await assertFails(
    db.update({
      ...base('pr_legacy', 1, 10), status: 'Needed', received: false,
      rcvQty: 6, rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: Date.now(),
    })
  );
  await assertFails(db.update({ ...base('pr_legacy', 1, 4), status: 'Received', received: true }));
});

test('and an Administrator may still rescue it', async () => {
  await given('pr_legacy', {
    ...HEALTHY, id: 'pr_legacy', qty: 10,
    rcvQty: '4', rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: 1712600000000,
  });

  await assertSucceeds(
    as(testEnv, UIDS.admin).collection('purchase').doc('pr_legacy').update({
      ...base('pr_legacy', 1, 10),
      status: 'Needed', received: false,
      rcvQty: 6, rcvBy: 'Administrator', rcvUid: UIDS.admin, rcvAt: Date.now(),
    })
  );
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

// --- part deliveries, against the rules as deployed -------------------------

test('the partial receipt payload the app sends is accepted', async () => {
  // The whole question Batch C had to answer before it could ship: does a
  // requirement that is *partly* received need a rules change? It does not.
  // The update rule constrains id, qty, updated, rev and del, and says
  // nothing at all about rcvQty, received or status.
  await given('pr_healthy', { ...HEALTHY, qty: 10 });

  await assertSucceeds(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_healthy').update({
      ...base('pr_healthy', 1, 10),
      // Still open, and saying so out loud rather than by omission.
      status: 'Needed', received: false,
      rcvQty: 4, rcvBy: 'Sam', rcvUid: UIDS.staff, rcvAt: Date.now(),
    })
  );
});

test('and the delivery that completes it is accepted too', async () => {
  await given('pr_part', {
    ...HEALTHY, id: 'pr_part', qty: 10,
    status: 'Needed', received: false,
    rcvQty: 4, rcvBy: 'Sam', rcvUid: UIDS.staff, rcvAt: 1712000000000,
  });

  await assertSucceeds(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_part').update({
      ...base('pr_part', 1, 10),
      status: 'Received', received: true,
      // The cumulative total, which is what the app computes inside the
      // transaction from the stored figure.
      rcvQty: 10, rcvBy: 'Sam', rcvUid: UIDS.staff, rcvAt: Date.now(),
    })
  );
});

test('a partial receipt still carries no del key, on a row that has none', async () => {
  // Trap 3, on the payload this batch adds: `touched()` reports keys *added*,
  // so a helpful `del: false` here would trip the guard that keeps soft
  // delete an Administrator's and refuse an ordinary Manager's receipt.
  await given('pr_healthy', { ...HEALTHY, qty: 10 });
  const payload = {
    ...base('pr_healthy', 1, 10),
    status: 'Needed', received: false,
    rcvQty: 4, rcvBy: 'Sam', rcvUid: UIDS.staff, rcvAt: Date.now(),
  };

  await assertFails(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_healthy')
      .update({ ...payload, del: false })
  );
  await assertSucceeds(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_healthy').update(payload)
  );
});

test('a partly received row still rewrites a legacy string qty as a number', async () => {
  // Trap 1 does not go away because a delivery is partial: the merged
  // post-state still has to satisfy `qty is number && qty > 0`.
  await given('pr_legacy', { ...HEALTHY, id: 'pr_legacy', qty: '10' });

  await assertFails(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_legacy').update({
      id: 'pr_legacy', updated: Date.now(), rev: 2,
      upBy: 'Sam', upUid: UIDS.staff,
      status: 'Needed', received: false, rcvQty: 4,
    })
  );
  await assertSucceeds(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_legacy').update({
      ...base('pr_legacy', 1, 10),
      status: 'Needed', received: false, rcvQty: 4,
    })
  );
});

test('a Worker may not record a part delivery either', async () => {
  await given('pr_mine', { ...HEALTHY, id: 'pr_mine', qty: 10, byUid: UIDS.worker });

  await assertFails(
    as(testEnv, UIDS.worker).collection('purchase').doc('pr_mine').update({
      ...base('pr_mine', 1, 10),
      status: 'Needed', received: false, rcvQty: 4,
    })
  );
});

test('two devices cannot both record the same part delivery', async () => {
  // The running total is read inside a transaction and written back as
  // `rev + 1`, so the second device loses rather than both appearing to
  // succeed and one delivery being counted twice.
  await given('pr_part', { ...HEALTHY, id: 'pr_part', qty: 10 });

  const first = {
    ...base('pr_part', 1, 10), status: 'Needed', received: false, rcvQty: 4,
  };
  await assertSucceeds(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_part').update(first)
  );
  // The second device planned against rev 1 as well, and is refused.
  await assertFails(
    as(testEnv, UIDS.admin).collection('purchase').doc('pr_part').update({
      ...base('pr_part', 1, 10), status: 'Needed', received: false, rcvQty: 4,
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


// --- what never changes ------------------------------------------------------

test('a requirement is never hard deleted, by anyone', async () => {
  await given('pr_healthy', HEALTHY);

  for (const uid of [UIDS.primaryOwner, UIDS.admin, UIDS.staff, UIDS.worker]) {
    await assertFails(as(testEnv, uid).collection('purchase').doc('pr_healthy').delete());
  }
});

test('the limited role adds, and is still refused every privileged write', async () => {
  // What the creator rule does NOT loosen. The row is their own, which is
  // the point: raising a requirement does not make its receipt theirs.
  await given('pr_mine', { ...HEALTHY, id: 'pr_mine', byUid: UIDS.worker });
  const db = as(testEnv, UIDS.worker).collection('purchase').doc('pr_mine');

  await assertFails(db.update({ ...base('pr_mine', 1, 4), rcvQty: 4, received: true }));
  await assertFails(db.update({ ...base('pr_mine', 1, 4), status: 'Cancelled' }));
  await assertFails(db.delete());
});

test('a switched-off account does nothing at all', async () => {
  await given('pr_healthy', HEALTHY);
  const db = as(testEnv, UIDS.switchedOff);

  await assertFails(db.collection('purchase').doc('pr_healthy').get());
  await assertFails(db.collection('purchase').doc('pr_healthy').update(base('pr_healthy', 1, 4)));
});
