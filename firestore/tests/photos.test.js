const test = require('node:test');
const assert = require('node:assert/strict');
const { assertFails, assertSucceeds } = require('@firebase/rules-unit-testing');
const firebase = require('firebase/compat/app');
require('firebase/compat/firestore');
const { createTestEnvironment, seed, as, UIDS } = require('./helpers');

/**
 * N3.1 stock photos.
 *
 * A photo lives in its own document so the board never downloads image bytes
 * with the stock list. The two documents must agree in the same commit, which
 * is what most of these tests are about: a photo document cannot appear
 * without its stock metadata, and the metadata cannot claim a photo that is
 * not there.
 */

const STOCK = 'gateMotors|SIE1000';
const MAX_BYTES = 81920;

let testEnv;

test.before(async () => { testEnv = await createTestEnvironment(); });
test.after(async () => { await testEnv?.cleanup(); });

const bytes = (n) => firebase.firestore.Blob.fromUint8Array(new Uint8Array(n));

/** The stock row as the app maintains it, with whatever photo state is asked. */
const stockRow = (uid, { hasPhoto = false, photoRev = 0, lastAction = 'photo' } = {}) => ({
  key: STOCK, q: 7, min: 2, t: Date.now(), byUid: uid,
  name: 'Sliding gate motor', unit: 'each', lastAction, hasPhoto, photoRev,
});

const photoDoc = (uid, rev, size = 1024) => ({
  key: STOCK, bytes: bytes(size), w: 800, h: 600, rev,
  by: 'Tester', byUid: uid, at: Date.now(),
});

/** Set a photo the way the app does: both documents, one commit. */
function setPhoto(db, uid, rev, { size = 1024, photo = {}, stock = {} } = {}) {
  const batch = db.batch();
  batch.set(db.collection('stockPhotos').doc(STOCK), { ...photoDoc(uid, rev, size), ...photo });
  batch.set(db.collection('stock').doc(STOCK), { ...stockRow(uid, { hasPhoto: true, photoRev: rev }), ...stock });
  return batch.commit();
}

function removePhoto(db, uid, rev) {
  const batch = db.batch();
  batch.delete(db.collection('stockPhotos').doc(STOCK));
  batch.set(db.collection('stock').doc(STOCK), stockRow(uid, { hasPhoto: false, photoRev: rev }));
  return batch.commit();
}

test.beforeEach(async () => {
  await seed(testEnv);
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.collection('stock').doc(STOCK).set({
      key: STOCK, q: 7, min: 2, t: Date.now(), byUid: UIDS.admin,
      name: 'Sliding gate motor', unit: 'each',
    });
  });
});

// --- who may do what ---------------------------------------------------

test('Staff may set a photo; a Worker may only read one', async () => {
  await assertSucceeds(setPhoto(as(testEnv, UIDS.staff), UIDS.staff, 1));

  const workerDb = as(testEnv, UIDS.worker);
  await assertSucceeds(workerDb.collection('stockPhotos').doc(STOCK).get());
  await assertFails(setPhoto(workerDb, UIDS.worker, 2));
  await assertFails(workerDb.collection('stockPhotos').doc(STOCK).delete());
});

test('a signed-out caller gets nothing', async () => {
  await assertSucceeds(setPhoto(as(testEnv, UIDS.admin), UIDS.admin, 1));
  const anon = testEnv.unauthenticatedContext().firestore();
  await assertFails(anon.collection('stockPhotos').doc(STOCK).get());
  await assertFails(setPhoto(anon, UIDS.admin, 2));
});

test('a switched-off account may not write a photo', async () => {
  await assertFails(setPhoto(as(testEnv, UIDS.switchedOff), UIDS.switchedOff, 1));
});

// --- the payload -------------------------------------------------------

test('the 80 KiB ceiling is enforced by the rules, not just by the client', async () => {
  const db = as(testEnv, UIDS.staff);
  await assertSucceeds(setPhoto(db, UIDS.staff, 1, { size: MAX_BYTES }));
  await assertFails(setPhoto(db, UIDS.staff, 2, { size: MAX_BYTES + 1 }));
});

test('the image field has to be bytes', async () => {
  const db = as(testEnv, UIDS.staff);
  await assertFails(setPhoto(db, UIDS.staff, 1, { photo: { bytes: 'not an image' } }));
  await assertFails(setPhoto(db, UIDS.staff, 1, { photo: { bytes: 12345 } }));
});

test('no price or tax field may ride along on a photo document', async () => {
  const db = as(testEnv, UIDS.staff);
  for (const leak of ['dealer', 'contractor', 'client', 'gst']) {
    await assertFails(setPhoto(db, UIDS.staff, 1, { photo: { [leak]: 18500 } }));
  }
});

test('a photo cannot be attributed to somebody else', async () => {
  const db = as(testEnv, UIDS.staff);
  await assertFails(setPhoto(db, UIDS.staff, 1, { photo: { byUid: UIDS.admin } }));
});

// --- the two documents must agree --------------------------------------

test('a photo document alone is refused', async () => {
  const db = as(testEnv, UIDS.staff);
  await assertFails(db.collection('stockPhotos').doc(STOCK).set(photoDoc(UIDS.staff, 1)));
});

test('stock metadata claiming a photo that is not there is refused', async () => {
  const db = as(testEnv, UIDS.staff);
  await assertFails(
    db.collection('stock').doc(STOCK).set(stockRow(UIDS.staff, { hasPhoto: true, photoRev: 1 })),
  );
});

test('a rev that disagrees between the two documents is refused', async () => {
  const db = as(testEnv, UIDS.staff);
  await assertFails(setPhoto(db, UIDS.staff, 1, { stock: { hasPhoto: true, photoRev: 2 } }));
});

test('deleting the photo while the row still claims one is refused', async () => {
  await assertSucceeds(setPhoto(as(testEnv, UIDS.staff), UIDS.staff, 1));
  const db = as(testEnv, UIDS.staff);
  await assertFails(db.collection('stockPhotos').doc(STOCK).delete());
});

test('removing a photo takes both documents together', async () => {
  await assertSucceeds(setPhoto(as(testEnv, UIDS.staff), UIDS.staff, 1));
  await assertSucceeds(removePhoto(as(testEnv, UIDS.staff), UIDS.staff, 2));
});

test('photoRev may not go backwards', async () => {
  await assertSucceeds(setPhoto(as(testEnv, UIDS.staff), UIDS.staff, 5));
  const db = as(testEnv, UIDS.staff);
  await assertFails(setPhoto(db, UIDS.staff, 4));
  await assertSucceeds(setPhoto(db, UIDS.staff, 6));
});

// --- deleting a photographed row ---------------------------------------

test('a stock row cannot be deleted out from under its photo', async () => {
  await assertSucceeds(setPhoto(as(testEnv, UIDS.admin), UIDS.admin, 1));
  const db = as(testEnv, UIDS.admin);
  await assertFails(db.collection('stock').doc(STOCK).delete());
});

test('deleting both together is allowed, and is how a photographed row goes', async () => {
  await assertSucceeds(setPhoto(as(testEnv, UIDS.admin), UIDS.admin, 1));
  const db = as(testEnv, UIDS.admin);
  const batch = db.batch();
  batch.delete(db.collection('stockPhotos').doc(STOCK));
  batch.delete(db.collection('stock').doc(STOCK));
  await assertSucceeds(batch.commit());
});

// --- a photo is not a stock movement -----------------------------------

test('photo is a stock lastAction but never a stockMoves action', async () => {
  const db = as(testEnv, UIDS.staff);
  await assertSucceeds(setPhoto(db, UIDS.staff, 1));

  await assertFails(db.collection('stockMoves').doc('mv_photo').set({
    id: 'mv_photo', key: STOCK, action: 'photo', prev: 7, next: 7, at: Date.now(),
    byUid: UIDS.staff,
  }));
});

test('an ordinary stock write still works on a photographed row', async () => {
  await assertSucceeds(setPhoto(as(testEnv, UIDS.staff), UIDS.staff, 1));

  // A merge write leaves hasPhoto/photoRev in place, so the guards still see
  // a consistent pair. This is the case that would break every quantity
  // change on a photographed item if the rules were wrong.
  const db = as(testEnv, UIDS.staff);
  await assertSucceeds(db.collection('stock').doc(STOCK).set({
    key: STOCK, q: 9, min: 2, t: Date.now(), byUid: UIDS.staff, lastAction: 'in',
  }, { merge: true }));

  // withSecurityRulesDisabled does not hand back the callback's value, so
  // the row is captured rather than returned.
  let row;
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const snapshot = await context.firestore().collection('stock').doc(STOCK).get();
    row = snapshot.data();
  });
  assert.equal(row.q, 9);
  assert.equal(row.hasPhoto, true, 'the merge write must leave the photo attached');
  assert.equal(row.photoRev, 1);
});
