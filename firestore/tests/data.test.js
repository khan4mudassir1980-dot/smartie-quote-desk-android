const test = require('node:test');
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
