const test = require('node:test');
const assert = require('node:assert/strict');
const { assertFails, assertSucceeds } = require('@firebase/rules-unit-testing');
const firebase = require('firebase/compat/app');
require('firebase/compat/firestore');
const { createTestEnvironment, seed, as, UIDS } = require('./helpers');

/**
 * Stopped-item history.
 *
 * An item removed from stock leaves one immutable record behind. The rules
 * have two jobs here that nothing else can do:
 *
 * - history may not be **fabricated** for an item that is still on the board,
 *   which `!existsAfter(/stock/...)` enforces in the same commit;
 * - history may not be **edited**, ever, by anybody.
 *
 * Everything else is the ordinary role matrix: every member may read it,
 * only the roles that could always stop tracking an item may write it, and
 * only they may clear it.
 */

const STOCK = 'gateMotors|SIE1000';
const EVENT = 'sr_abc123';

let testEnv;

test.before(async () => { testEnv = await createTestEnvironment(); });
test.after(async () => { await testEnv?.cleanup(); });

test.beforeEach(async () => { await seed(testEnv); });

const stockRow = (uid) => ({
  key: STOCK, q: 7, min: 2, t: Date.now(), byUid: uid,
  name: 'Sliding gate motor', unit: 'each', lastAction: 'add',
});

/** The record the app writes, with whatever the test wants changed. */
const event = (uid, extra = {}) => ({
  id: EVENT, key: STOCK, stockDoc: STOCK, q: 7, manual: false,
  at: Date.now(), byUid: uid, name: 'Sliding gate motor', unit: 'each',
  ...extra,
});

/** Removal as the app does it: history written, row deleted, one commit. */
function remove(db, uid, { id = EVENT, extra = {}, keepRow = false } = {}) {
  const batch = db.batch();
  batch.set(db.collection('stoppedStock').doc(id), event(uid, { id, ...extra }));
  if (!keepRow) batch.delete(db.collection('stock').doc(STOCK));
  return batch.commit();
}

async function givenStock() {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('stock').doc(STOCK)
      .set(stockRow(UIDS.admin));
  });
}

async function givenHistory(id = EVENT) {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('stoppedStock').doc(id)
      .set({ ...event(UIDS.admin), id });
  });
}

// --- reading -------------------------------------------------------------

test('every member may read stopped-item history', async () => {
  await givenHistory();
  for (const uid of [UIDS.primaryOwner, UIDS.admin, UIDS.staff, UIDS.worker]) {
    await assertSucceeds(as(testEnv, uid).collection('stoppedStock').doc(EVENT).get());
  }
});

test('a signed-out caller may not read it', async () => {
  await givenHistory();
  await assertFails(
    testEnv.unauthenticatedContext().firestore()
      .collection('stoppedStock').doc(EVENT).get()
  );
});

// --- who may write it ----------------------------------------------------

test('an Administrator may remove an item and record it', async () => {
  await givenStock();
  await assertSucceeds(remove(as(testEnv, UIDS.admin), UIDS.admin));
});

test('an Owner may too', async () => {
  await givenStock();
  await assertSucceeds(remove(as(testEnv, UIDS.primaryOwner), UIDS.primaryOwner));
});

test('the displayed Manager — stored staff — may not', async () => {
  await givenStock();
  await assertFails(remove(as(testEnv, UIDS.staff), UIDS.staff));
});

test('the displayed Staff — stored worker — may not', async () => {
  await givenStock();
  await assertFails(remove(as(testEnv, UIDS.worker), UIDS.worker));
});

// --- the cross-document guarantee ----------------------------------------

test('history cannot be written while the item is still on the board', async () => {
  await givenStock();
  await assertFails(remove(as(testEnv, UIDS.admin), UIDS.admin, { keepRow: true }));
});

test('an author cannot record somebody else as having done it', async () => {
  await givenStock();
  await assertFails(remove(as(testEnv, UIDS.admin), UIDS.admin, { extra: { byUid: UIDS.staff } }));
});

test('the id in the document must be the id of the document', async () => {
  await givenStock();
  const db = as(testEnv, UIDS.admin);
  const batch = db.batch();
  batch.set(db.collection('stoppedStock').doc(EVENT), event(UIDS.admin, { id: 'sr_somethingelse' }));
  batch.delete(db.collection('stock').doc(STOCK));
  await assertFails(batch.commit());
});

// --- what may not be smuggled into it ------------------------------------

for (const field of ['note', 'stockNote', 'hasPhoto', 'photoRev', 'min', 'pinned']) {
  test(`a history entry may not carry ${field}`, async () => {
    await givenStock();
    await assertFails(
      remove(as(testEnv, UIDS.admin), UIDS.admin, { extra: { [field]: 1 } })
    );
  });
}

test('a history entry may not carry a price', async () => {
  await givenStock();
  await assertFails(remove(as(testEnv, UIDS.admin), UIDS.admin, { extra: { dealer: 100 } }));
});

test('a history entry needs its quantity and its source', async () => {
  await givenStock();
  await assertFails(remove(as(testEnv, UIDS.admin), UIDS.admin, { extra: { q: 'seven' } }));
  await givenStock();
  await assertFails(remove(as(testEnv, UIDS.admin), UIDS.admin, { extra: { manual: 'no' } }));
});

// --- immutability --------------------------------------------------------

test('nobody may edit a history entry', async () => {
  await givenHistory();
  for (const uid of [UIDS.primaryOwner, UIDS.admin, UIDS.staff, UIDS.worker]) {
    await assertFails(
      as(testEnv, uid).collection('stoppedStock').doc(EVENT).update({ q: 999 })
    );
  }
});

test('not even by writing it again with different contents', async () => {
  await givenStock();
  await assertSucceeds(remove(as(testEnv, UIDS.admin), UIDS.admin));
  await assertFails(
    as(testEnv, UIDS.admin).collection('stoppedStock').doc(EVENT).set(
      event(UIDS.admin, { q: 999 })
    )
  );
});

// --- clearing ------------------------------------------------------------

test('an Owner and an Administrator may clear history', async () => {
  await givenHistory('sr_one');
  await assertSucceeds(as(testEnv, UIDS.admin).collection('stoppedStock').doc('sr_one').delete());
  await givenHistory('sr_two');
  await assertSucceeds(as(testEnv, UIDS.primaryOwner).collection('stoppedStock').doc('sr_two').delete());
});

test('the displayed Manager and Staff may not clear it', async () => {
  await givenHistory();
  await assertFails(as(testEnv, UIDS.staff).collection('stoppedStock').doc(EVENT).delete());
  await assertFails(as(testEnv, UIDS.worker).collection('stoppedStock').doc(EVENT).delete());
});

// --- what removal must not touch -----------------------------------------

test('removing an item leaves the catalogue product alone', async () => {
  await givenStock();
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('products').doc('gateMotors__SIE1000')
      .set({ key: STOCK, group: 'gateMotors', model: 'SIE1000', name: 'Sliding gate motor' });
  });

  await assertSucceeds(remove(as(testEnv, UIDS.admin), UIDS.admin));

  const product = await as(testEnv, UIDS.admin).collection('products').doc('gateMotors__SIE1000').get();
  assert.equal(product.exists, true, 'the product must survive its stock row');
});

test('a removed identity may be added to stock again, fresh', async () => {
  await givenStock();
  await assertSucceeds(remove(as(testEnv, UIDS.admin), UIDS.admin));

  await assertSucceeds(
    as(testEnv, UIDS.admin).collection('stock').doc(STOCK).set({
      key: STOCK, q: 0, min: 0, t: Date.now(), byUid: UIDS.admin,
      name: 'Sliding gate motor', unit: 'each', lastAction: 'add',
    })
  );
});
