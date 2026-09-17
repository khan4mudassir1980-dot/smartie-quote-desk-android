const test = require('node:test');
const assert = require('node:assert/strict');
const { assertFails, assertSucceeds } = require('@firebase/rules-unit-testing');
const { createTestEnvironment, seed, as, UIDS } = require('./helpers');

let testEnv;

test.before(async () => { testEnv = await createTestEnvironment(); });
test.after(async () => { await testEnv?.cleanup(); });

test.beforeEach(async () => {
  await seed(testEnv);
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.collection('products').doc('gateMotors__SIE1000').set({
      id: 'gateMotors|SIE1000', group: 'gateMotors', seedModel: 'SIE1000', model: 'SIE1000',
      name: 'Sliding gate motor', gst: 18, dealer: 18500, contractor: null, client: null, active: true,
    });
    await db.collection('stock').doc('gateMotors|SIE1000').set({
      key: 'gateMotors|SIE1000', q: 7, min: 2, t: Date.now(), byUid: UIDS.admin,
      name: 'Sliding gate motor', unit: 'each',
    });
    await db.collection('purchase').doc('pr_1').set({
      id: 'pr_1', name: 'Rack', qty: 4, urgency: 'normal', status: 'Needed',
      byUid: UIDS.worker, t: Date.now(), updated: Date.now(), rev: 1,
    });
    await db.collection('customers').doc('c_1').set({ id: 'c_1', name: 'Sunrise Constructions' });
    await db.collection('teamSettings').doc('categories').set({
      map: { 'cat-shutter': { id: 'cat-shutter', name: 'Shutter Motors', order: 30 } },
      updated: Date.now(), by: UIDS.admin,
    });
    await db.collection('teamSettings').doc('productPins').set({
      keys: ['gateMotors|SIE1000'], updatedAt: Date.now(), updatedBy: 'Administrator',
    });
  });
});

test('a Worker sees stock but neither products nor prices', async () => {
  const db = as(testEnv, UIDS.worker);
  await assertSucceeds(db.collection('stock').get());
  await assertFails(db.collection('products').get());
  await assertFails(db.collection('quotations').get());
  await assertFails(db.collection('customers').get());
  await assertFails(db.collection('stockMoves').get());
});

test('Staff view products but never change them', async () => {
  const db = as(testEnv, UIDS.staff);
  await assertSucceeds(db.collection('products').get());
  await assertFails(db.collection('products').doc('gateMotors__SIE1000').update({ dealer: 1 }));
});

test('Owner, Administrator and Staff move stock; a Worker cannot', async () => {
  for (const caller of [UIDS.primaryOwner, UIDS.admin, UIDS.staff]) {
    const db = as(testEnv, caller);
    await assertSucceeds(db.collection('stock').doc('gateMotors|SIE1000').set({
      key: 'gateMotors|SIE1000', q: 8, min: 2, t: Date.now(), byUid: caller, lastAction: 'in',
    }));
  }
  const workerDb = as(testEnv, UIDS.worker);
  await assertFails(workerDb.collection('stock').doc('gateMotors|SIE1000').set({
    key: 'gateMotors|SIE1000', q: 9, min: 2, t: Date.now(), byUid: UIDS.worker, lastAction: 'in',
  }));
});

test('a stock movement needs no note and cannot go negative', async () => {
  const db = as(testEnv, UIDS.staff);
  await assertSucceeds(db.collection('stockMoves').doc('mv_1').set({
    id: 'mv_1', key: 'gateMotors|SIE1000', action: 'in', prev: 7, delta: 1, next: 8,
    at: Date.now(), byUid: UIDS.staff, by: 'Staff',
  }));
  await assertFails(db.collection('stock').doc('gateMotors|SIE1000').set({
    key: 'gateMotors|SIE1000', q: -1, min: 2, t: Date.now(), byUid: UIDS.staff, lastAction: 'out',
  }));
});

test('setting an exact quantity is an Administrator action', async () => {
  const staffDb = as(testEnv, UIDS.staff);
  await assertFails(staffDb.collection('stockMoves').doc('mv_set_staff').set({
    id: 'mv_set_staff', key: 'gateMotors|SIE1000', action: 'set', prev: 7, next: 20,
    at: Date.now(), byUid: UIDS.staff,
  }));
  const adminDb = as(testEnv, UIDS.admin);
  await assertSucceeds(adminDb.collection('stockMoves').doc('mv_set_admin').set({
    id: 'mv_set_admin', key: 'gateMotors|SIE1000', action: 'set', prev: 7, next: 20,
    at: Date.now(), byUid: UIDS.admin,
  }));
});

test('a Worker reads the denormalised name but can change nothing', async () => {
  const db = as(testEnv, UIDS.worker);
  const snapshot = await db.collection('stock').doc('gateMotors|SIE1000').get();
  // The whole point of the additive field: a name without /products access.
  assert.equal(snapshot.data().name, 'Sliding gate motor');
  await assertFails(db.collection('products').doc('gateMotors__SIE1000').get());
  await assertFails(db.collection('stock').doc('gateMotors|SIE1000').update({
    q: 1, min: 2, t: Date.now(), byUid: UIDS.worker, lastAction: 'in',
  }));
  await assertFails(db.collection('stock').doc('manualstock|new').set({
    key: 'manualstock|new', q: 1, min: 0, t: Date.now(), byUid: UIDS.worker, lastAction: 'add',
  }));
});

test('no price or tax field may be written to a Worker-readable stock document', async () => {
  const db = as(testEnv, UIDS.admin);
  const base = {
    key: 'gateMotors|SIE1000', q: 8, min: 2, t: Date.now(),
    byUid: UIDS.admin, lastAction: 'in', name: 'Sliding gate motor',
  };
  await assertSucceeds(db.collection('stock').doc('gateMotors|SIE1000').set(base, { merge: true }));
  for (const leak of ['dealer', 'contractor', 'client', 'gst']) {
    await assertFails(
      db.collection('stock').doc('gateMotors|SIE1000').set({ ...base, [leak]: 18500 }, { merge: true }),
    );
  }
});

test('a name that is not a string is refused', async () => {
  const db = as(testEnv, UIDS.admin);
  await assertFails(db.collection('stock').doc('gateMotors|SIE1000').set({
    key: 'gateMotors|SIE1000', q: 8, min: 2, t: Date.now(),
    byUid: UIDS.admin, lastAction: 'in', name: { first: 'Sliding' },
  }, { merge: true }));
});

test('Staff save a note-only edit and change the reorder level, but not the quantity', async () => {
  const db = as(testEnv, UIDS.staff);
  await assertSucceeds(db.collection('stock').doc('gateMotors|SIE1000').set({
    key: 'gateMotors|SIE1000', q: 7, min: 2, t: Date.now(),
    byUid: UIDS.staff, lastAction: 'note', stockNote: 'Top shelf',
  }, { merge: true }));
  await assertSucceeds(db.collection('stock').doc('gateMotors|SIE1000').set({
    key: 'gateMotors|SIE1000', q: 7, min: 6, t: Date.now(),
    byUid: UIDS.staff, lastAction: 'min',
  }, { merge: true }));
  await assertFails(db.collection('stock').doc('gateMotors|SIE1000').set({
    key: 'gateMotors|SIE1000', q: 99, min: 2, t: Date.now(),
    byUid: UIDS.staff, lastAction: 'set',
  }, { merge: true }));
});

test('a note-only save is never logged as a movement', async () => {
  const db = as(testEnv, UIDS.admin);
  await assertFails(db.collection('stockMoves').doc('mv_note').set({
    id: 'mv_note', key: 'gateMotors|SIE1000', action: 'note', prev: 7, delta: 0, next: 7,
    at: Date.now(), byUid: UIDS.admin, by: 'Administrator',
  }));
});

test('a movement document id must equal its own id field', async () => {
  const db = as(testEnv, UIDS.admin);
  await assertFails(db.collection('stockMoves').doc('mv_wrong_door').set({
    id: 'mv_something_else', key: 'gateMotors|SIE1000', action: 'in', prev: 7, delta: 1, next: 8,
    at: Date.now(), byUid: UIDS.admin, by: 'Administrator',
  }));
  await assertSucceeds(db.collection('stockMoves').doc('mv_matching').set({
    id: 'mv_matching', key: 'gateMotors|SIE1000', action: 'in', prev: 7, delta: 1, next: 8,
    at: Date.now(), byUid: UIDS.admin, by: 'Administrator',
  }));
});

test('a movement is attributed to its author and never rewritten', async () => {
  const db = as(testEnv, UIDS.staff);
  await assertFails(db.collection('stockMoves').doc('mv_forged').set({
    id: 'mv_forged', key: 'gateMotors|SIE1000', action: 'in', prev: 7, delta: 1, next: 8,
    at: Date.now(), byUid: UIDS.admin, by: 'Administrator',
  }));
  await assertSucceeds(db.collection('stockMoves').doc('mv_own').set({
    id: 'mv_own', key: 'gateMotors|SIE1000', action: 'in', prev: 7, delta: 1, next: 8,
    at: Date.now(), byUid: UIDS.staff, by: 'Staff',
  }));
  await assertFails(db.collection('stockMoves').doc('mv_own').update({ next: 99 }));
  await assertFails(db.collection('stockMoves').doc('mv_own').delete());
});

test('an Administrator creates a manual stock row; a negative one is refused', async () => {
  const db = as(testEnv, UIDS.admin);
  await assertSucceeds(db.collection('stock').doc('manualstock|shed_padlock').set({
    key: 'manualstock|shed_padlock', group: 'manualstock', model: 'shed_padlock',
    name: 'Brass padlock', q: 4, min: 1, off: false, t: Date.now(), lastAction: 'add',
    manual: true, manualName: 'Brass padlock', manualModel: 'Shed padlock',
    byUid: UIDS.admin, by: 'Administrator',
  }));
  await assertFails(db.collection('stock').doc('manualstock|bad').set({
    key: 'manualstock|bad', q: -1, min: 0, t: Date.now(), byUid: UIDS.admin, lastAction: 'add',
  }));
  await assertFails(db.collection('stock').doc('manualstock|bad2').set({
    key: 'manualstock|bad2', q: 1, min: -1, t: Date.now(), byUid: UIDS.admin, lastAction: 'add',
  }));
});

test('a stock write is attributed to the caller and cannot re-key a row', async () => {
  const db = as(testEnv, UIDS.staff);
  await assertFails(db.collection('stock').doc('gateMotors|SIE1000').set({
    key: 'gateMotors|SIE1000', q: 8, min: 2, t: Date.now(), byUid: UIDS.admin, lastAction: 'in',
  }, { merge: true }));
  await assertFails(db.collection('stock').doc('gateMotors|SIE1000').set({
    key: 'somethingElse|SIE1000', q: 8, min: 2, t: Date.now(), byUid: UIDS.staff, lastAction: 'in',
  }, { merge: true }));
});

test('a Worker adds a purchase requirement but never edits one', async () => {
  const db = as(testEnv, UIDS.worker);
  await assertSucceeds(db.collection('purchase').doc('pr_worker').set({
    id: 'pr_worker', name: 'Anchor bolts', qty: 20, urgency: 'normal', status: 'Needed',
    byUid: UIDS.worker, t: Date.now(), updated: Date.now(),
  }));
  await assertFails(db.collection('purchase').doc('pr_worker').update({ qty: 30, updated: Date.now() }));
  await assertFails(db.collection('purchase').doc('pr_1').update({ qty: 5, updated: Date.now() }));
});

test('a purchase requirement is never hard deleted and only an admin soft deletes', async () => {
  const adminDb = as(testEnv, UIDS.admin);
  await assertFails(adminDb.collection('purchase').doc('pr_1').delete());
  await assertSucceeds(adminDb.collection('purchase').doc('pr_1').update({
    del: true, deletedBy: UIDS.admin, updated: Date.now(), rev: 2,
  }));

  const staffDb = as(testEnv, UIDS.staff);
  await assertFails(staffDb.collection('purchase').doc('pr_1').update({
    del: true, updated: Date.now(), rev: 3,
  }));
});

test('a stale revision loses instead of overwriting silently', async () => {
  const db = as(testEnv, UIDS.staff);
  await assertSucceeds(db.collection('purchase').doc('pr_1').update({
    qty: 6, updated: Date.now(), rev: 2,
  }));
  // Another device still holding rev 1 must be refused.
  await assertFails(db.collection('purchase').doc('pr_1').update({
    qty: 9, updated: Date.now(), rev: 2,
  }));
});

test('a quotation cannot be edited or deleted, only cancelled by an administrator', async () => {
  const staffDb = as(testEnv, UIDS.staff);
  await assertSucceeds(staffDb.collection('quotations').doc('q_1').set({
    id: 'q_1', no: 'SIE/QD/2025-26/009', byUid: UIDS.staff, at: Date.now(), total: 1000,
  }));
  await assertFails(staffDb.collection('quotations').doc('q_1').update({ total: 1 }));
  await assertFails(staffDb.collection('quotations').doc('q_1').delete());

  const adminDb = as(testEnv, UIDS.admin);
  await assertSucceeds(adminDb.collection('quotations').doc('q_1').update({
    status: 'Cancelled', cancelledBy: 'Administrator', cancelledAt: Date.now(),
  }));
});

test('Staff correct a party contact but never rename or archive it', async () => {
  const db = as(testEnv, UIDS.staff);
  await assertSucceeds(db.collection('customers').doc('c_1').update({
    id: 'c_1', name: 'Sunrise Constructions', phone: '9876543210',
  }));
  await assertFails(db.collection('customers').doc('c_1').update({ id: 'c_1', name: 'Renamed' }));
  await assertFails(db.collection('customers').doc('c_1').update({ id: 'c_1', name: 'Sunrise Constructions', archived: true }));
});

test('the quotation counter only ever moves forward', async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('teamSettings').doc('numbering').set({
      prefix: 'SIE/QD', fy: '2025-26', next: 9, pad: 3,
    });
  });
  const db = as(testEnv, UIDS.staff);
  await assertSucceeds(db.collection('teamSettings').doc('numbering').update({
    next: 10, lastIssued: { no: 'SIE/QD/2025-26/009', at: Date.now(), by: 'Staff', uid: UIDS.staff },
  }));
  await assertFails(db.collection('teamSettings').doc('numbering').update({ next: 5 }));
  const adminDb = as(testEnv, UIDS.admin);
  await assertFails(adminDb.collection('teamSettings').doc('numbering').update({
    prefix: 'SIE/QD', fy: '2025-26', next: 2,
  }));
});

test('settings are readable by staff and writable by administrators only', async () => {
  const staffDb = as(testEnv, UIDS.staff);
  await assertFails(staffDb.collection('teamSettings').doc('company').set({ name: 'X' }));
  const adminDb = as(testEnv, UIDS.admin);
  await assertSucceeds(adminDb.collection('teamSettings').doc('company').set({ name: 'Smart India Enterprises' }));
  await assertSucceeds(staffDb.collection('teamSettings').doc('company').get());
  await assertFails(as(testEnv, UIDS.worker).collection('teamSettings').doc('company').get());
});

test('a signed-out visitor reads nothing', async () => {
  const db = testEnv.unauthenticatedContext().firestore();
  await assertFails(db.collection('stock').get());
  await assertFails(db.collection('users').get());
  await assertFails(db.collection('products').get());
});

test('the catalogue shelves and pins read like the products they describe', async () => {
  for (const uid of [UIDS.primaryOwner, UIDS.admin, UIDS.staff]) {
    const db = as(testEnv, uid);
    await assertSucceeds(db.collection('teamSettings').doc('categories').get());
    await assertSucceeds(db.collection('teamSettings').doc('productPins').get());
  }
});

test('a Worker reads neither the shelves nor the pins', async () => {
  const db = as(testEnv, UIDS.worker);
  await assertFails(db.collection('teamSettings').doc('categories').get());
  await assertFails(db.collection('teamSettings').doc('productPins').get());
});

test('only an administrator changes the pinned shelf', async () => {
  const keys = ['gateMotors|SIE1000', 'glass|TG12'];
  await assertSucceeds(
    as(testEnv, UIDS.admin).collection('teamSettings').doc('productPins')
      .set({ keys, updatedAt: Date.now(), updatedBy: 'Administrator' }, { merge: true }),
  );
  await assertFails(
    as(testEnv, UIDS.staff).collection('teamSettings').doc('productPins')
      .set({ keys, updatedAt: Date.now(), updatedBy: 'Staff' }, { merge: true }),
  );
  await assertFails(
    as(testEnv, UIDS.worker).collection('teamSettings').doc('productPins')
      .set({ keys, updatedAt: Date.now(), updatedBy: 'Worker' }, { merge: true }),
  );
});

test('a sixteenth pin is refused by the rules as well as by the app', async () => {
  const db = as(testEnv, UIDS.admin).collection('teamSettings').doc('productPins');
  const fifteen = Array.from({ length: 15 }, (_, i) => `gate|M${i + 1}`);
  await assertSucceeds(db.set({ keys: fifteen, updatedAt: Date.now(), updatedBy: 'Administrator' }, { merge: true }));
  await assertFails(db.set({ keys: [...fifteen, 'gate|M16'], updatedAt: Date.now(), updatedBy: 'Administrator' }, { merge: true }));
});

test('only an administrator changes the category shelves', async () => {
  const map = { 'cat-shutter': { id: 'cat-shutter', name: 'Shutter and rolling motors', order: 30 } };
  await assertSucceeds(
    as(testEnv, UIDS.admin).collection('teamSettings').doc('categories').set({ map }, { merge: true }),
  );
  await assertFails(
    as(testEnv, UIDS.staff).collection('teamSettings').doc('categories').set({ map }, { merge: true }),
  );
});

test('a signed-out visitor reads neither shelves nor pins', async () => {
  const db = testEnv.unauthenticatedContext().firestore();
  await assertFails(db.collection('teamSettings').doc('categories').get());
  await assertFails(db.collection('teamSettings').doc('productPins').get());
});
