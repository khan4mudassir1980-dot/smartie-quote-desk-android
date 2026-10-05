const test = require('node:test');
const assert = require('node:assert/strict');
const { assertSucceeds } = require('@firebase/rules-unit-testing');
const firebase = require('firebase/compat/app');
require('firebase/compat/firestore');
const { createTestEnvironment, seed, as, refused, UIDS, PEOPLE } = require('./helpers');

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

/**
 * Everything `PurchaseWrite.base()` puts on every update — stamped with **the
 * caller**, as the app stamps its author. Until N5.10b this stamped the
 * Administrator on every caller's write; the rules now refuse a `upUid` that
 * is not the caller's, so the fixture says who is writing.
 */
const base = (id, rev, qty, uid) => ({
  id, qty, updated: Date.now(), rev: rev + 1,
  upBy: PEOPLE[uid].name, upUid: uid,
  serverAt: firebase.firestore.FieldValue.serverTimestamp(),
});

// --- who owns a requirement, and for how long -------------------------------

test('the limited role corrects the requirement it raised itself', async () => {
  await given('pr_healthy', HEALTHY);

  await assertSucceeds(
    as(testEnv, UIDS.worker).collection('purchase').doc('pr_healthy').update({
      ...base('pr_healthy', 1, 6, UIDS.worker),
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

  await refused(
    as(testEnv, UIDS.worker).collection('purchase').doc('pr_theirs').update({
      ...base('pr_theirs', 1, 6, UIDS.worker), name: 'Mine now',
    })
  );
});

test('a requirement with no recorded creator belongs to nobody', async () => {
  // Most of what the PWA wrote has no byUid at all. '' == '' would hand
  // every one of those rows to whoever happened to be signed in.
  const { byUid, ...orphan } = HEALTHY;
  await given('pr_orphan', { ...orphan, id: 'pr_orphan' });

  await refused(
    as(testEnv, UIDS.worker).collection('purchase').doc('pr_orphan').update({
      ...base('pr_orphan', 1, 6, UIDS.worker), name: 'Mine now',
    })
  );
});

test('the creator may not rewrite who raised it, or when', async () => {
  await given('pr_healthy', HEALTHY);
  const db = as(testEnv, UIDS.worker).collection('purchase').doc('pr_healthy');

  await refused(db.update({ ...base('pr_healthy', 1, 6, UIDS.worker), byUid: UIDS.staff }));
  await refused(db.update({ ...base('pr_healthy', 1, 6, UIDS.worker), by: 'Somebody else' }));
  await refused(db.update({ ...base('pr_healthy', 1, 6, UIDS.worker), t: 1 }));
});

test('not even an Administrator may rewrite who raised a requirement', async () => {
  // prIdentityPinned() sits outside the branch disjunction, so it binds the
  // Administrator too. That is true by construction, which is exactly why it
  // is worth a test: nothing else would notice if the pin were moved inside
  // a branch one day.
  await given('pr_healthy', HEALTHY);
  const db = as(testEnv, UIDS.admin).collection('purchase').doc('pr_healthy');

  await refused(db.update({ ...base('pr_healthy', 1, 4, UIDS.admin), byUid: UIDS.admin }));
  await refused(db.update({ ...base('pr_healthy', 1, 4, UIDS.admin), by: 'Administrator' }));
  await refused(db.update({ ...base('pr_healthy', 1, 4, UIDS.admin), t: 1 }));

  // And the identical write without them is accepted, which is what makes
  // the three refusals mean the pin rather than something else in the rule.
  // A refused write stores nothing, so rev 2 is still the right next one.
  await assertSucceeds(db.update(base('pr_healthy', 1, 4, UIDS.admin)));
});

test('nor any audit field an edit has no business touching', async () => {
  // Rewritten by N4.3. This used to include the receipt fields, with a
  // payload that changed `qty` from 4 to 6 — so after the creator gained
  // delivery those lines would have kept passing on prQtyKept() rather than
  // on the receipt gate, testing something other than their own name. The
  // receipt cases live in the delivery section now, with `qty` kept.
  await given('pr_healthy', HEALTHY);
  const db = as(testEnv, UIDS.worker).collection('purchase').doc('pr_healthy');
  const good = base('pr_healthy', 1, 6, UIDS.worker);

  await refused(db.update({ ...good, stocked: true }));
  await refused(db.update({ ...good, stockedQty: 4 }));
  await refused(db.update({ ...good, cancelledBy: 'Staff Person' }));
  // An edit still may not close a requirement by hand.
  await refused(db.update({ ...good, status: 'Cancelled' }));
});

test('an edit may not smuggle a removal, and a removal may not smuggle an edit', async () => {
  await given('pr_healthy', HEALTHY);
  const db = as(testEnv, UIDS.worker).collection('purchase').doc('pr_healthy');

  await refused(db.update({ ...base('pr_healthy', 1, 6, UIDS.worker), name: 'Gone', del: true }));
  await refused(
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

  await refused(db.update({ ...base('pr_part', 1, 10, UIDS.worker), name: 'Too late' }));
  await refused(
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

  await refused(
    as(testEnv, UIDS.worker).collection('purchase').doc('pr_part').update({
      ...base('pr_part', 1, 10, UIDS.worker),
      rcvQty: firebase.firestore.FieldValue.delete(),
      rcvBy: firebase.firestore.FieldValue.delete(),
      rcvUid: firebase.firestore.FieldValue.delete(),
      rcvAt: firebase.firestore.FieldValue.delete(),
      name: 'Mine again',
    })
  );
});

test('and the creator still cannot reopen or hard delete', async () => {
  // Changed by N4.3: the receive half of this test now SUCCEEDS, and has
  // moved to the delivery section. Reopening and hard deleting have not
  // moved, and are what is left here.
  await given('pr_done', {
    ...HEALTHY, id: 'pr_done', status: 'Received', received: true,
    rcvQty: 4, rcvBy: 'Staff Person', rcvUid: UIDS.worker, rcvAt: 1712600000000,
  });
  const db = as(testEnv, UIDS.worker).collection('purchase').doc('pr_done');

  await refused(db.update({ ...base('pr_done', 1, 4, UIDS.worker), status: 'Needed', received: false }));
  await refused(db.delete());
});

test('a reopened requirement is its creator\'s again', async () => {
  // The Owner's decision: reopen removes the receipt outright, so the row is
  // untouched and means as good as new. Recorded in docs/N4.2-plan.md.
  await given('pr_reopened', { ...HEALTHY, id: 'pr_reopened', received: false, status: 'Needed' });

  await assertSucceeds(
    as(testEnv, UIDS.worker).collection('purchase').doc('pr_reopened').update({
      ...base('pr_reopened', 1, 6, UIDS.worker), name: 'Corrected again',
    })
  );
});

test('a soft delete may record who removed it and when', async () => {
  await given('pr_mine', { ...HEALTHY, id: 'pr_mine', byUid: UIDS.worker });

  await assertSucceeds(
    as(testEnv, UIDS.worker).collection('purchase').doc('pr_mine').update({
      id: 'pr_mine', updated: Date.now(), rev: 2,
      upBy: 'Staff Person', upUid: UIDS.worker,
      serverAt: firebase.firestore.FieldValue.serverTimestamp(),
      del: true, deletedBy: UIDS.worker,
      delBy: 'Staff Person', delAt: Date.now(),
    })
  );
});

test('but not a stamp that lies about who, or is the wrong shape', async () => {
  await given('pr_mine', { ...HEALTHY, id: 'pr_mine', byUid: UIDS.worker });
  const db = as(testEnv, UIDS.worker).collection('purchase').doc('pr_mine');
  const removal = {
    id: 'pr_mine', updated: Date.now(), rev: 2,
    upBy: 'Staff Person', upUid: UIDS.worker,
    del: true,
  };

  // `deletedBy` was permitted without any constraint on its value until
  // N4.3, so any permitted remover could write anybody's uid there.
  await refused(db.update({ ...removal, deletedBy: UIDS.staff }));
  await refused(db.update({ ...removal, deletedBy: UIDS.worker, delAt: 'yesterday' }));
  await refused(db.update({ ...removal, deletedBy: UIDS.worker, delBy: 42 }));
  await refused(
    db.update({ ...removal, deletedBy: UIDS.worker, delBy: 'x'.repeat(81) })
  );
  // And the honest one still goes through, so the refusals mean the stamp.
  await assertSucceeds(
    db.update({ ...removal, deletedBy: UIDS.worker, delBy: 'x'.repeat(80), delAt: 1 })
  );
});

test('a delivery may not smuggle a removal stamp', async () => {
  await given('pr_mine', { ...HEALTHY, id: 'pr_mine', qty: 10, byUid: UIDS.worker });

  await refused(
    as(testEnv, UIDS.worker).collection('purchase').doc('pr_mine').update({
      ...base('pr_mine', 1, 10, UIDS.worker),
      status: 'Needed', received: false, rcvQty: 4,
      delBy: 'Staff Person', delAt: Date.now(),
    })
  );
});

// --- the Manager, and what a delivery takes away -----------------------------

test('a Manager edits anybody\'s untouched requirement, as before', async () => {
  await given('pr_healthy', HEALTHY);

  await assertSucceeds(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_healthy').update({
      ...base('pr_healthy', 1, 6, UIDS.staff), name: 'Sliding gate rack', urgency: 'critical',
    })
  );
});

test('but not one a delivery has reached', async () => {
  await given('pr_part', {
    ...HEALTHY, id: 'pr_part', qty: 10,
    rcvQty: 4, rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: 1712600000000,
  });

  await refused(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_part').update({
      ...base('pr_part', 1, 10, UIDS.staff), name: 'Too late', urgency: 'critical',
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
  await refused(
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

  await refused(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_done').update({
      ...base('pr_done', 1, 4, UIDS.staff), status: 'Needed', received: false,
    })
  );
  await refused(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_done').update({
      ...base('pr_done', 1, 4, UIDS.staff), status: 'Needed', received: false,
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

  await refused(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_part').update({
      ...base('pr_part', 1, 10, UIDS.staff),
      status: 'Needed', received: false,
      rcvQty: 2, rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: Date.now(),
    })
  );
});

test('a Manager still delivers against anybody\'s requirement', async () => {
  // Regression: widening delivery to the creator must not have narrowed it
  // to the creator.
  await given('pr_theirs', { ...HEALTHY, id: 'pr_theirs', qty: 10, byUid: UIDS.worker });

  await assertSucceeds(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_theirs').update({
      ...base('pr_theirs', 1, 10, UIDS.staff),
      status: 'Needed', received: false, rcvQty: 4,
      rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: Date.now(),
    })
  );
});

test('and so does an Owner and an Administrator', async () => {
  for (const uid of [UIDS.primaryOwner, UIDS.admin]) {
    await seed(testEnv);
    await given('pr_theirs', { ...HEALTHY, id: 'pr_theirs', qty: 10, byUid: UIDS.worker });

    await assertSucceeds(
      as(testEnv, uid).collection('purchase').doc('pr_theirs').update({
        ...base('pr_theirs', 1, 10, uid),
        status: 'Needed', received: false, rcvQty: 4,
      })
    );
  }
});

// --- writing off what is not coming --------------------------------------------

test('a Manager closes a shortfall at exactly the stored receipt', async () => {
  await given('pr_part', {
    ...HEALTHY, id: 'pr_part', qty: 10,
    rcvQty: 7, rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: 1712600000000,
  });

  await assertSucceeds(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_part').update({
      ...base('pr_part', 1, 7, UIDS.staff), status: 'Received', received: true,
    })
  );
});

test('and the stored document is exactly what the shortfall promised', async () => {
  // The test above proves the write is *allowed*. This one is about what it
  // did: a shortfall rewrites a stored quantity and closes a requirement, so
  // "permitted" is not the same as "correct", and nothing else in this suite
  // reads a document back.
  // The delivery was taken in by the Staff account; the Manager writes off
  // the rest.
  await given('pr_part', {
    ...HEALTHY, id: 'pr_part', qty: 10,
    rcvQty: 7, rcvBy: 'Staff Person', rcvUid: UIDS.worker, rcvAt: 1712600000000,
  });

  await assertSucceeds(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_part').update({
      ...base('pr_part', 1, 7, UIDS.staff), status: 'Received', received: true,
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
  assert.equal(row.rcvBy, 'Staff Person');
  assert.equal(row.rcvUid, UIDS.worker);
  assert.equal(row.rcvAt, 1712600000000);

  assert.equal(row.rev, 2, 'the stored revision plus one, as every update does');

  // The updater field is its own thing, distinct from the receipt's rcvUid:
  // who wrote off the rest is not who took the delivery in. `base()` stamps
  // the caller, as the app does, and the rules insist it is the caller.
  assert.equal(row.upUid, UIDS.staff);
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
  await refused(db.update({ ...base('pr_part', 1, 5, UIDS.staff), status: 'Received', received: true }));
  await refused(db.update({ ...base('pr_part', 1, 8, UIDS.staff), status: 'Received', received: true }));
  await refused(db.update({ ...base('pr_part', 1, 10, UIDS.staff), status: 'Received', received: true }));
});

test('a shortfall may not rewrite the receipt while it writes off the rest', async () => {
  await given('pr_part', {
    ...HEALTHY, id: 'pr_part', qty: 10,
    rcvQty: 7, rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: 1712600000000,
  });

  await refused(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_part').update({
      ...base('pr_part', 1, 7, UIDS.staff), status: 'Received', received: true,
      rcvBy: 'Somebody else', rcvUid: UIDS.staff,
    })
  );
});

test('nothing to write off: an untouched requirement cannot be closed short', async () => {
  await given('pr_healthy', HEALTHY);

  await refused(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_healthy').update({
      ...base('pr_healthy', 1, 0.0001, UIDS.staff), status: 'Received', received: true,
    })
  );
});

test('the limited role closes a shortfall on the requirement it raised', async () => {
  // Changed by N4.3. This asserted a refusal, and the row it used was the
  // limited role's own — so it flips by design rather than by accident. The
  // refusal it used to make is the next test, against somebody else's row.
  await given('pr_part', {
    ...HEALTHY, id: 'pr_part', qty: 10,
    rcvQty: 7, rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: 1712600000000,
  });

  await assertSucceeds(
    as(testEnv, UIDS.worker).collection('purchase').doc('pr_part').update({
      ...base('pr_part', 1, 7, UIDS.worker), status: 'Received', received: true,
    })
  );
});

test('but never on one somebody else raised', async () => {
  await given('pr_theirs', {
    ...HEALTHY, id: 'pr_theirs', qty: 10, byUid: UIDS.staff,
    rcvQty: 7, rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: 1712600000000,
  });

  await refused(
    as(testEnv, UIDS.worker).collection('purchase').doc('pr_theirs').update({
      ...base('pr_theirs', 1, 7, UIDS.worker), status: 'Received', received: true,
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

  await refused(
    db.update({
      ...base('pr_legacy', 1, 10, UIDS.staff), status: 'Needed', received: false,
      rcvQty: 6, rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: Date.now(),
    })
  );
  await refused(db.update({ ...base('pr_legacy', 1, 4, UIDS.staff), status: 'Received', received: true }));
});

test('and an Administrator may still rescue it', async () => {
  await given('pr_legacy', {
    ...HEALTHY, id: 'pr_legacy', qty: 10,
    rcvQty: '4', rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: 1712600000000,
  });

  await assertSucceeds(
    as(testEnv, UIDS.admin).collection('purchase').doc('pr_legacy').update({
      ...base('pr_legacy', 1, 10, UIDS.admin),
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

  // `rev` is the stored one plus one, so the string is the only thing wrong
  // with this write. Without it `revOk()` refused it as well, and dropping
  // the `qty` clause from the rule left this test green (N5.10b).
  await refused(
    db.collection('purchase').doc('pr_string').update({ urgency: 'critical', updated: Date.now(), rev: 2 })
  );
});

test('and the same write succeeds once qty is sent as a number', async () => {
  await given('pr_string', { ...HEALTHY, id: 'pr_string', qty: '10' });
  const db = as(testEnv, UIDS.staff);

  await assertSucceeds(
    db.collection('purchase').doc('pr_string').update({
      ...base('pr_string', 1, 10, UIDS.staff), urgency: 'critical',
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

  // `rev` is the stored one plus one, so the missing id is the only thing
  // wrong with this write — see the string `qty` test above (N5.10b).
  await refused(
    db.collection('purchase').doc('pr_noid').update({ qty: 5, updated: Date.now(), rev: 2 })
  );
});

test('and the same write succeeds once the id is re-asserted', async () => {
  const { id, ...withoutId } = HEALTHY;
  await given('pr_noid', withoutId);
  const db = as(testEnv, UIDS.staff);

  await assertSucceeds(
    db.collection('purchase').doc('pr_noid').update(base('pr_noid', 1, 5, UIDS.staff))
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

  await refused(
    db.collection('purchase').doc('pr_nodel').update({ ...base('pr_nodel', 1, 4, UIDS.staff), del: false })
  );
});

test('and the identical save succeeds when it mentions no del at all', async () => {
  await given('pr_nodel', { ...HEALTHY, id: 'pr_nodel' });
  const db = as(testEnv, UIDS.staff);

  await assertSucceeds(
    db.collection('purchase').doc('pr_nodel').update(base('pr_nodel', 1, 4, UIDS.staff))
  );
});

test('an Administrator is the one who may set del', async () => {
  await given('pr_nodel', { ...HEALTHY, id: 'pr_nodel' });

  await assertSucceeds(
    as(testEnv, UIDS.admin).collection('purchase').doc('pr_nodel').update({
      ...base('pr_nodel', 1, 4, UIDS.admin), del: true, deletedBy: UIDS.admin,
    })
  );
});

// --- trap 4: `updated` must be a number -------------------------------------

test('a server timestamp in updated is refused; the rules want a number', async () => {
  await given('pr_healthy', HEALTHY);
  const db = as(testEnv, UIDS.staff);

  await refused(
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
    db.collection('purchase').doc('pr_healthy').update(base('pr_healthy', 1, 4, UIDS.staff))
  );
});

// --- trap 5: `rev` is what stops two devices completing the same thing ------

test('a stale revision loses rather than overwriting silently', async () => {
  await given('pr_healthy', HEALTHY);
  const db = as(testEnv, UIDS.staff);

  await assertSucceeds(db.collection('purchase').doc('pr_healthy').update(base('pr_healthy', 1, 4, UIDS.staff)));
  // A second device still holding rev 1 computes the same rev 2 and loses.
  await refused(db.collection('purchase').doc('pr_healthy').update(base('pr_healthy', 1, 4, UIDS.staff)));
});

test('once a row carries a rev, omitting it is refused, not tolerated', async () => {
  // `revOk()` reads the MERGED post-state, so a row that already has `rev: 1`
  // still has it after an update that never mentioned it — and `1 == 1 + 1`
  // is false. The tolerance is narrower than the rule reads at a glance.
  await given('pr_healthy', HEALTHY);
  const db = as(testEnv, UIDS.staff);

  await refused(
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
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_not').update(base('pr_not', 1, 4, UIDS.staff))
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

  await refused(db.collection('purchase').doc('pr_bad').set({ ...good, byUid: UIDS.admin }));
  await refused(db.collection('purchase').doc('pr_bad').set({ ...good, status: 'Received' }));
  await refused(db.collection('purchase').doc('pr_bad').set({ ...good, urgency: 'later' }));
  await refused(db.collection('purchase').doc('pr_bad').set({ ...good, qty: 0 }));
  await refused(db.collection('purchase').doc('pr_bad').set({ ...good, received: true }));
  await refused(db.collection('purchase').doc('pr_bad').set({ ...good, del: true }));
});

test('the receive payload the app sends is accepted', async () => {
  await given('pr_healthy', HEALTHY);

  await assertSucceeds(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_healthy').update({
      ...base('pr_healthy', 1, 4, UIDS.staff),
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
      ...base('pr_healthy', 1, 10, UIDS.staff),
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
      ...base('pr_part', 1, 10, UIDS.staff),
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
    ...base('pr_healthy', 1, 10, UIDS.staff),
    status: 'Needed', received: false,
    rcvQty: 4, rcvBy: 'Sam', rcvUid: UIDS.staff, rcvAt: Date.now(),
  };

  await refused(
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

  await refused(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_legacy').update({
      id: 'pr_legacy', updated: Date.now(), rev: 2,
      upBy: 'Sam', upUid: UIDS.staff,
      status: 'Needed', received: false, rcvQty: 4,
    })
  );
  await assertSucceeds(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_legacy').update({
      ...base('pr_legacy', 1, 10, UIDS.staff),
      status: 'Needed', received: false, rcvQty: 4,
    })
  );
});

test('the limited role records a part delivery against its own requirement', async () => {
  // Changed by N4.3, and again the row was already its own, so the flip is
  // the design rather than an accident of the fixture.
  await given('pr_mine', { ...HEALTHY, id: 'pr_mine', qty: 10, byUid: UIDS.worker });

  await assertSucceeds(
    as(testEnv, UIDS.worker).collection('purchase').doc('pr_mine').update({
      ...base('pr_mine', 1, 10, UIDS.worker),
      status: 'Needed', received: false, rcvQty: 4,
      rcvBy: 'Staff Person', rcvUid: UIDS.worker, rcvAt: Date.now(),
    })
  );
});

test('and the delivery that completes it', async () => {
  await given('pr_mine', {
    ...HEALTHY, id: 'pr_mine', qty: 10, byUid: UIDS.worker,
    rcvQty: 6, rcvBy: 'Staff Person', rcvUid: UIDS.worker, rcvAt: 1712600000000,
  });

  await assertSucceeds(
    as(testEnv, UIDS.worker).collection('purchase').doc('pr_mine').update({
      ...base('pr_mine', 1, 10, UIDS.worker),
      status: 'Received', received: true, rcvQty: 10,
      rcvBy: 'Staff Person', rcvUid: UIDS.worker, rcvAt: Date.now(),
    })
  );
});

test('but not a part delivery against somebody else\'s requirement', async () => {
  await given('pr_theirs', { ...HEALTHY, id: 'pr_theirs', qty: 10, byUid: UIDS.staff });

  await refused(
    as(testEnv, UIDS.worker).collection('purchase').doc('pr_theirs').update({
      ...base('pr_theirs', 1, 10, UIDS.worker),
      status: 'Needed', received: false, rcvQty: 4,
    })
  );
});

test('nor against one with no recorded creator', async () => {
  const { byUid, ...orphan } = HEALTHY;
  await given('pr_orphan', { ...orphan, id: 'pr_orphan', qty: 10 });

  await refused(
    as(testEnv, UIDS.worker).collection('purchase').doc('pr_orphan').update({
      ...base('pr_orphan', 1, 10, UIDS.worker),
      status: 'Needed', received: false, rcvQty: 4,
    })
  );
});

test('the creator cannot reduce a received total while delivering', async () => {
  await given('pr_mine', {
    ...HEALTHY, id: 'pr_mine', qty: 10, byUid: UIDS.worker,
    rcvQty: 6, rcvBy: 'Staff Person', rcvUid: UIDS.worker, rcvAt: 1712600000000,
  });

  await refused(
    as(testEnv, UIDS.worker).collection('purchase').doc('pr_mine').update({
      ...base('pr_mine', 1, 10, UIDS.worker),
      status: 'Needed', received: false, rcvQty: 2,
    })
  );
});

test('nor rewrite who raised it while delivering', async () => {
  await given('pr_mine', { ...HEALTHY, id: 'pr_mine', qty: 10, byUid: UIDS.worker });

  await refused(
    as(testEnv, UIDS.worker).collection('purchase').doc('pr_mine').update({
      ...base('pr_mine', 1, 10, UIDS.worker),
      status: 'Needed', received: false, rcvQty: 4,
      byUid: UIDS.staff,
    })
  );
});

test('two devices cannot both record the same part delivery', async () => {
  // The running total is read inside a transaction and written back as
  // `rev + 1`, so the second device loses rather than both appearing to
  // succeed and one delivery being counted twice.
  await given('pr_part', { ...HEALTHY, id: 'pr_part', qty: 10 });

  const first = {
    ...base('pr_part', 1, 10, UIDS.staff), status: 'Needed', received: false, rcvQty: 4,
  };
  await assertSucceeds(
    as(testEnv, UIDS.staff).collection('purchase').doc('pr_part').update(first)
  );
  // The second device planned against rev 1 as well, and is refused.
  await refused(
    as(testEnv, UIDS.admin).collection('purchase').doc('pr_part').update({
      ...base('pr_part', 1, 10, UIDS.admin), status: 'Needed', received: false, rcvQty: 4,
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
      ...base('pr_done', 1, 4, UIDS.admin),
      status: 'Needed', received: false,
      rcvQty: firebase.firestore.FieldValue.delete(),
      rcvBy: firebase.firestore.FieldValue.delete(),
      rcvUid: firebase.firestore.FieldValue.delete(),
      rcvAt: firebase.firestore.FieldValue.delete(),
    })
  );
});


// --- N5.10b: how status moves, `received` is a boolean, closing short needs the write-off ---
//
// The Owner's rules of 2026-09-29, for everyone, an Owner or Administrator
// included: status moves only by closing to Received (met, or written off),
// cancel (Owner and Administrator, and since commit 9 a Manager — with its
// stamp, and only while nothing has arrived; purchase-ordered.test.js), or
// reopen (Owner and Administrator); a write that sets `received` sets a boolean; and
// whatever closes a requirement meets its total. Found in N5.10b step 1: a
// delivery could write any status beside `received: false`, and the app
// counts "Received" and "Cancelled" as closed.

/** A requirement ten were asked for, and four of which have arrived. */
const PART = {
  ...HEALTHY, id: 'pr_part4', qty: 10,
  rcvQty: 4, rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: 1712600000000,
};
const receipt = (by, total) => ({ rcvQty: total, rcvBy: 'Someone', rcvUid: by, rcvAt: Date.now() });

test('a delivery may not close a requirement by its status alone', async () => {
  await given('pr_healthy', { ...HEALTHY, qty: 10 });
  const db = as(testEnv, UIDS.staff).collection('purchase').doc('pr_healthy');

  await refused(db.update({ ...base('pr_healthy', 1, 10, UIDS.staff), ...receipt(UIDS.staff, 4), received: false, status: 'Received' }));
  await refused(db.update({ ...base('pr_healthy', 1, 10, UIDS.staff), ...receipt(UIDS.staff, 4), received: false, status: 'Cancelled' }));
  // The same delivery, leaving the status where it was, is what the app sends.
  await assertSucceeds(db.update({ ...base('pr_healthy', 1, 10, UIDS.staff), ...receipt(UIDS.staff, 4), received: false, status: 'Needed' }));
});

test('nor may the person who raised it', async () => {
  await given('pr_mine', { ...HEALTHY, id: 'pr_mine', qty: 10, byUid: UIDS.worker });
  const db = as(testEnv, UIDS.worker).collection('purchase').doc('pr_mine');

  await refused(db.update({ ...base('pr_mine', 1, 10, UIDS.worker), ...receipt(UIDS.worker, 4), received: false, status: 'Received' }));
  await refused(db.update({ ...base('pr_mine', 1, 10, UIDS.worker), ...receipt(UIDS.worker, 4), received: false, status: 'Cancelled' }));
  await assertSucceeds(db.update({ ...base('pr_mine', 1, 10, UIDS.worker), ...receipt(UIDS.worker, 4), received: false, status: 'Needed' }));
});

test('a Manager may not change only the status of a part-received requirement', async () => {
  // No receipt field changes, so this is not a delivery — and it used to pass
  // through the delivery branch all the same, because the stored rcvQty
  // satisfied it.
  await given('pr_part4', PART);
  const db = as(testEnv, UIDS.staff).collection('purchase').doc('pr_part4');

  await refused(db.update({ ...base('pr_part4', 1, 10, UIDS.staff), status: 'Cancelled' }));
  await refused(db.update({ ...base('pr_part4', 1, 10, UIDS.staff), status: 'Received' }));
  // A further delivery, the status left alone, still goes through.
  await assertSucceeds(db.update({ ...base('pr_part4', 1, 10, UIDS.staff), ...receipt(UIDS.staff, 6), received: false, status: 'Needed' }));
});

test('received is a boolean whenever a write sets it — an Administrator\'s too', async () => {
  await given('pr_healthy', { ...HEALTHY, qty: 10 });
  const manager = as(testEnv, UIDS.staff).collection('purchase').doc('pr_healthy');
  const admin = as(testEnv, UIDS.admin).collection('purchase').doc('pr_healthy');
  // Since N5.10b commit 9 a cancel carries its stamp; it is here so the
  // number is the only thing wrong with the refused one.
  const stamp = () => ({ cancelledBy: 'Administrator', cancelledUid: UIDS.admin, cancelledAt: Date.now() });

  await refused(manager.update({ ...base('pr_healthy', 1, 10, UIDS.staff), ...receipt(UIDS.staff, 4), received: 0 }));
  await refused(admin.update({ ...base('pr_healthy', 1, 10, UIDS.admin), received: 0, status: 'Cancelled', ...stamp() }));
  await refused(admin.update({ ...base('pr_healthy', 1, 10, UIDS.admin), ...receipt(UIDS.admin, 10), received: 1, status: 'Received' }));
  // A boolean, the same writes otherwise, is accepted.
  await assertSucceeds(admin.update({ ...base('pr_healthy', 1, 10, UIDS.admin), received: false, status: 'Cancelled', ...stamp() }));
});

test('a stored number is left alone: only a write that sets received must send a boolean', async () => {
  // V8C4 rows hold `received: 1`. Correcting one without touching `received`
  // is still an Administrator's to make.
  await given('pr_pwa_done', { ...HEALTHY, id: 'pr_pwa_done', qty: 10, status: 'Received', received: 1,
    rcvQty: 10, rcvBy: 'Someone', rcvUid: UIDS.admin, rcvAt: 1712600000000 });

  await assertSucceeds(as(testEnv, UIDS.admin).collection('purchase').doc('pr_pwa_done')
    .update({ ...base('pr_pwa_done', 1, 10, UIDS.admin), note: 'Checked against the invoice' }));
});

test('closing short needs the write-off — for an Administrator too', async () => {
  await given('pr_part4', PART);
  const admin = as(testEnv, UIDS.admin).collection('purchase').doc('pr_part4');

  await refused(admin.update({ ...base('pr_part4', 1, 10, UIDS.admin), status: 'Received', received: true }));
  await refused(admin.update({ ...base('pr_part4', 1, 10, UIDS.admin), received: true }));
  // Written off — the total set to what arrived — it closes.
  await assertSucceeds(admin.update({ ...base('pr_part4', 1, 4, UIDS.admin), status: 'Received', received: true }));
});

test('an Administrator may still correct a closed requirement without reopening it', async () => {
  // Not a close: `received` and `status` stay as they were, so the total may
  // move past the receipt.
  await given('pr_done', { ...HEALTHY, id: 'pr_done', qty: 10, status: 'Received', received: true,
    rcvQty: 10, rcvBy: 'Someone', rcvUid: UIDS.staff, rcvAt: 1712600000000 });

  await assertSucceeds(as(testEnv, UIDS.admin).collection('purchase').doc('pr_done')
    .update({ ...base('pr_done', 1, 12, UIDS.admin) }));
});

test('status moves to nothing else — not for an Administrator either', async () => {
  await given('pr_healthy', { ...HEALTHY, qty: 10 });
  const admin = as(testEnv, UIDS.admin).collection('purchase').doc('pr_healthy');

  // "Ordered" arrived with N5.10b commit 9, and only with its stamp; this is
  // V8C4's bare one. The stamped order is purchase-ordered.test.js's.
  await refused(admin.update({ ...base('pr_healthy', 1, 10, UIDS.admin), status: 'Ordered' }));
  await refused(admin.update({ ...base('pr_healthy', 1, 10, UIDS.admin), status: 'Archived' }));

  await given('pr_done', { ...HEALTHY, id: 'pr_done', qty: 10, status: 'Received', received: true,
    rcvQty: 10, rcvBy: 'Someone', rcvUid: UIDS.staff, rcvAt: 1712600000000 });
  const adminDone = as(testEnv, UIDS.admin).collection('purchase').doc('pr_done');
  // Cancel is from an open requirement — stamped, so that is what refuses
  // it — and a reopen is not still received.
  await refused(adminDone.update({ ...base('pr_done', 1, 10, UIDS.admin), status: 'Cancelled', received: false,
    cancelledBy: 'Administrator', cancelledUid: UIDS.admin, cancelledAt: Date.now() }));
  await refused(adminDone.update({ ...base('pr_done', 1, 10, UIDS.admin), status: 'Needed' }));
});

// --- N5.10b: a uid a write sets is the caller's ------------------------------------
//
// The Owner's decision of 2026-09-29 (Q1b): `upUid`, `rcvUid` and
// `cancelledUid`, if a write sets one, must be the caller — for everyone, an
// Owner or Administrator included. Removing one stays allowed (QG). Step 1
// found a Manager could record a delivery naming the Administrator, and the
// app shows the name it resolves from that uid.

test('a uid a write sets is the caller\'s — upUid, rcvUid, cancelledUid — an Administrator\'s too', async () => {
  await given('pr_healthy', { ...HEALTHY, qty: 10 });
  const manager = as(testEnv, UIDS.staff).collection('purchase').doc('pr_healthy');

  await refused(manager.update({ ...base('pr_healthy', 1, 10, UIDS.staff), upUid: UIDS.admin, urgency: 'urgent' }));
  await refused(manager.update({ ...base('pr_healthy', 1, 10, UIDS.staff), ...receipt(UIDS.admin, 4), received: false }));
  await assertSucceeds(manager.update({ ...base('pr_healthy', 1, 10, UIDS.staff), ...receipt(UIDS.staff, 4), received: false }));

  await given('pr_open', { ...HEALTHY, id: 'pr_open', qty: 10 });
  const admin = as(testEnv, UIDS.admin).collection('purchase').doc('pr_open');
  await refused(admin.update({ ...base('pr_open', 1, 10, UIDS.admin), status: 'Cancelled', received: false,
    cancelledBy: 'Manager Person', cancelledUid: UIDS.staff, cancelledAt: Date.now() }));
  await refused(admin.update({ ...base('pr_open', 1, 10, UIDS.admin), upUid: UIDS.otherAdmin }));
  await assertSucceeds(admin.update({ ...base('pr_open', 1, 10, UIDS.admin), status: 'Cancelled', received: false,
    cancelledBy: 'Administrator', cancelledUid: UIDS.admin, cancelledAt: Date.now() }));
});

test('a uid carried unchanged, or removed, is not asked about', async () => {
  // The Manager took this delivery in; the Administrator corrects the note.
  // `rcvUid` stays the Manager's, which is not the caller, and that is fine.
  await given('pr_done', { ...HEALTHY, id: 'pr_done', qty: 10, status: 'Received', received: true,
    rcvQty: 10, rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: 1712600000000 });
  const admin = as(testEnv, UIDS.admin).collection('purchase').doc('pr_done');
  await assertSucceeds(admin.update({ ...base('pr_done', 1, 10, UIDS.admin), note: 'Checked' }));

  // And the reopen removes it outright (QG).
  await assertSucceeds(admin.update({
    ...base('pr_done', 2, 10, UIDS.admin), status: 'Needed', received: false,
    rcvQty: firebase.firestore.FieldValue.delete(), rcvBy: firebase.firestore.FieldValue.delete(),
    rcvUid: firebase.firestore.FieldValue.delete(), rcvAt: firebase.firestore.FieldValue.delete(),
  }));
});

test('a new requirement names nobody else either', async () => {
  const db = as(testEnv, UIDS.worker).collection('purchase');
  const fresh = (id, extra = {}) => ({
    id, name: 'Anchor bolts', qty: 20, urgency: 'normal', status: 'Needed',
    by: 'Staff Person', byUid: UIDS.worker, t: Date.now(), updated: Date.now(), ...extra,
  });

  await refused(db.doc('pr_new1').set(fresh('pr_new1', { upBy: 'Administrator', upUid: UIDS.admin })));
  await refused(db.doc('pr_new2').set(fresh('pr_new2', { rcvUid: UIDS.admin })));
  await assertSucceeds(db.doc('pr_new3').set(fresh('pr_new3', { upBy: 'Staff Person', upUid: UIDS.worker })));
});

// --- what never changes ------------------------------------------------------

test('a requirement is never hard deleted, by anyone', async () => {
  await given('pr_healthy', HEALTHY);

  for (const uid of [UIDS.primaryOwner, UIDS.admin, UIDS.staff, UIDS.worker]) {
    await refused(as(testEnv, uid).collection('purchase').doc('pr_healthy').delete());
  }
});

test('the limited role adds, and is still refused what N4.3 did not loosen', async () => {
  // Changed by N4.3: the rcvQty line used to be here and now succeeds, so it
  // has moved to the delivery section. What stays is what raising a
  // requirement still does not buy — inventing a status, or a hard delete.
  await given('pr_mine', { ...HEALTHY, id: 'pr_mine', byUid: UIDS.worker });
  const db = as(testEnv, UIDS.worker).collection('purchase').doc('pr_mine');

  await refused(db.update({ ...base('pr_mine', 1, 4, UIDS.worker), status: 'Cancelled' }));
  await refused(db.update({ ...base('pr_mine', 1, 4, UIDS.worker), stocked: true }));
  await refused(db.delete());
});

test('a switched-off account does nothing at all', async () => {
  await given('pr_healthy', HEALTHY);
  const db = as(testEnv, UIDS.switchedOff);

  await refused(db.collection('purchase').doc('pr_healthy').get());
  await refused(db.collection('purchase').doc('pr_healthy').update(base('pr_healthy', 1, 4, UIDS.switchedOff)));
});
