const test = require('node:test');
const assert = require('node:assert');
const { assertFails, assertSucceeds } = require('@firebase/rules-unit-testing');
const { createTestEnvironment, seed, as, UIDS } = require('./helpers');

let testEnv;

test.before(async () => { testEnv = await createTestEnvironment(); });
test.after(async () => { await testEnv?.cleanup(); });
test.beforeEach(async () => { await seed(testEnv); });

const user = (db, uid) => db.collection('users').doc(uid);
const access = (db) => db.collection('teamSettings').doc('access');

test('the Primary Owner is protected from everyone, including themselves', async () => {
  for (const caller of [UIDS.primaryOwner, UIDS.additionalOwner, UIDS.admin, UIDS.staff, UIDS.worker]) {
    const db = as(testEnv, caller);
    await assertFails(user(db, UIDS.primaryOwner).update({ role: 'worker' }));
    await assertFails(user(db, UIDS.primaryOwner).update({ active: false }));
    await assertFails(user(db, UIDS.primaryOwner).delete());
  }
});

test('an Administrator manages Administrator, Staff and Worker accounts', async () => {
  const db = as(testEnv, UIDS.admin);
  await assertSucceeds(user(db, UIDS.worker).update({ role: 'admin', email: 'worker@example.invalid' }));
  await assertSucceeds(user(db, UIDS.otherAdmin).update({ role: 'staff', email: 'admin2@example.invalid' }));
  await assertSucceeds(user(db, UIDS.staff).update({ active: false, email: 'staff@example.invalid' }));
  await assertSucceeds(user(db, UIDS.worker).delete());
});

test('an Administrator can never touch an Owner', async () => {
  const db = as(testEnv, UIDS.admin);
  await assertFails(user(db, UIDS.additionalOwner).update({ role: 'staff', email: 'second@example.invalid' }));
  await assertFails(user(db, UIDS.additionalOwner).delete());
  await assertFails(user(db, UIDS.primaryOwner).update({ role: 'staff', email: 'khan4mudassir1980@gmail.com' }));
});

test('nobody changes their own profile', async () => {
  for (const caller of [UIDS.primaryOwner, UIDS.additionalOwner, UIDS.admin, UIDS.staff, UIDS.worker]) {
    const db = as(testEnv, caller);
    await assertFails(user(db, caller).update({ role: 'owner' }));
    await assertFails(user(db, caller).update({ active: true, role: 'admin' }));
    await assertFails(user(db, caller).delete());
  }
});

test('the Additional Owner manages non-owners but neither Owner', async () => {
  const db = as(testEnv, UIDS.additionalOwner);
  await assertSucceeds(user(db, UIDS.staff).update({ role: 'admin', email: 'staff@example.invalid' }));
  await assertFails(user(db, UIDS.primaryOwner).update({ role: 'worker', email: 'khan4mudassir1980@gmail.com' }));
  await assertFails(user(db, UIDS.additionalOwner).update({ role: 'admin', email: 'second@example.invalid' }));
  // It can never grant the Owner position to anyone.
  await assertFails(user(db, UIDS.admin).update({ role: 'owner', email: 'admin@example.invalid' }));
});

test('Staff and Workers manage nobody', async () => {
  for (const caller of [UIDS.staff, UIDS.worker]) {
    const db = as(testEnv, caller);
    await assertFails(user(db, UIDS.admin).update({ role: 'worker', email: 'admin@example.invalid' }));
    await assertFails(user(db, UIDS.worker).delete());
    // They cannot even read the people list.
    await assertFails(db.collection('users').get());
  }
});

test('a switched-off account does nothing at all', async () => {
  const db = as(testEnv, UIDS.switchedOff);
  await assertFails(db.collection('stock').get());
  await assertFails(user(db, UIDS.worker).update({ role: 'staff', email: 'worker@example.invalid' }));
});

test('a first sign-in creates an active Worker and nothing better', async () => {
  const db = testEnv.authenticatedContext(UIDS.outsider, { email: 'new@example.invalid' }).firestore();
  await assertFails(
    user(db, UIDS.outsider).set({ name: 'New', email: 'new@example.invalid', role: 'admin', active: true })
  );
  await assertFails(
    user(db, UIDS.outsider).set({ name: 'New', email: 'new@example.invalid', role: 'worker', active: false })
  );
  await assertSucceeds(
    user(db, UIDS.outsider).set({ name: 'New', email: 'new@example.invalid', role: 'worker', active: true })
  );
});

test('a profile cannot be created for somebody else or with another email', async () => {
  const db = testEnv.authenticatedContext(UIDS.outsider, { email: 'new@example.invalid' }).firestore();
  await assertFails(
    user(db, UIDS.worker).set({ name: 'X', email: 'new@example.invalid', role: 'worker', active: true })
  );
  await assertFails(
    user(db, UIDS.outsider).set({ name: 'X', email: 'someone@example.invalid', role: 'worker', active: true })
  );
});

test('appointing the Additional Owner needs the access slot in the same write', async () => {
  const db = as(testEnv, UIDS.primaryOwner);
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('teamSettings').doc('access')
      .set({ primaryOwnerUid: UIDS.primaryOwner, secondOwnerUid: '' }, { merge: true });
  });

  // Promoting alone is refused.
  await assertFails(user(db, UIDS.admin).update({ role: 'owner', email: 'admin@example.invalid' }));

  const batch = db.batch();
  batch.update(user(db, UIDS.admin), { role: 'owner', email: 'admin@example.invalid', active: true });
  batch.set(access(db), { primaryOwnerUid: UIDS.primaryOwner, secondOwnerUid: UIDS.admin }, { merge: true });
  await assertSucceeds(batch.commit());
});

test('emergency revoke clears the slot and switches the account off', async () => {
  const db = as(testEnv, UIDS.primaryOwner);
  const batch = db.batch();
  batch.update(user(db, UIDS.additionalOwner), {
    role: 'worker', active: false, email: 'second@example.invalid',
  });
  batch.set(access(db), { primaryOwnerUid: UIDS.primaryOwner, secondOwnerUid: '' }, { merge: true });
  await assertSucceeds(batch.commit());
});

test('only the Primary Owner writes the access document', async () => {
  for (const caller of [UIDS.additionalOwner, UIDS.admin, UIDS.staff, UIDS.worker]) {
    const db = as(testEnv, caller);
    await assertFails(access(db).set({ secondOwnerUid: caller }, { merge: true }));
  }
});

test('primaryOwnerUid cannot be changed once it is set', async () => {
  const db = as(testEnv, UIDS.primaryOwner);
  await assertFails(
    access(db).set({ primaryOwnerUid: UIDS.admin, secondOwnerUid: '' }, { merge: true })
  );
  await assertSucceeds(
    access(db).set({ primaryOwnerUid: UIDS.primaryOwner, secondOwnerUid: '' }, { merge: true })
  );
});

test('before migration the owner email identifies the Primary Owner', async () => {
  await seed(testEnv, { withPrimaryOwnerUid: false });
  const db = as(testEnv, UIDS.primaryOwner);
  await assertSucceeds(user(db, UIDS.staff).update({ role: 'admin', email: 'staff@example.invalid' }));
  // And the same owner document is still protected from the Administrator.
  const adminDb = as(testEnv, UIDS.admin);
  await assertFails(user(adminDb, UIDS.primaryOwner).update({ role: 'staff', email: 'khan4mudassir1980@gmail.com' }));
});

test('the audit log is append-only and readable by administrators only', async () => {
  const adminDb = as(testEnv, UIDS.admin);
  const entry = { id: 'ta_1', action: 'role_changed', byUid: UIDS.admin, at: Date.now() };
  await assertSucceeds(adminDb.collection('teamAudit').doc('ta_1').set(entry));
  await assertFails(adminDb.collection('teamAudit').doc('ta_1').update({ action: 'tampered' }));
  await assertFails(adminDb.collection('teamAudit').doc('ta_1').delete());
  await assertFails(as(testEnv, UIDS.staff).collection('teamAudit').get());
  // An entry cannot be attributed to somebody else.
  await assertFails(
    adminDb.collection('teamAudit').doc('ta_2').set({ id: 'ta_2', action: 'x', byUid: UIDS.primaryOwner, at: 1 })
  );
});
