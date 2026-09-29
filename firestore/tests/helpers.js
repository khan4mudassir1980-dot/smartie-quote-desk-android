const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { assertFails, initializeTestEnvironment } = require('@firebase/rules-unit-testing');

const OWNER_EMAIL = 'khan4mudassir1980@gmail.com';

const UIDS = {
  primaryOwner: 'uid_owner',
  additionalOwner: 'uid_second',
  admin: 'uid_admin',
  otherAdmin: 'uid_admin2',
  staff: 'uid_staff',
  otherStaff: 'uid_staff2',
  worker: 'uid_worker',
  outsider: 'uid_outsider',
  switchedOff: 'uid_off',
};

const PEOPLE = {
  [UIDS.primaryOwner]: { name: 'Primary Owner', email: OWNER_EMAIL, role: 'owner', active: true },
  [UIDS.additionalOwner]: { name: 'Additional Owner', email: 'second@example.invalid', role: 'owner', active: true },
  [UIDS.admin]: { name: 'Administrator', email: 'admin@example.invalid', role: 'admin', active: true },
  [UIDS.otherAdmin]: { name: 'Other Administrator', email: 'admin2@example.invalid', role: 'admin', active: true },
  // The display names are corrected; the uid keys and the stored `role`
  // values are NOT, because those are what the rules and the PWA read.
  // Stored `staff` is displayed **Manager**; stored `worker` is displayed
  // **Staff**. Calling these accounts "Staff" and "Worker" was precisely the
  // confusion that mapping exists to prevent.
  [UIDS.staff]: { name: 'Manager Person', email: 'staff@example.invalid', role: 'staff', active: true },
  // A second Manager, so two ordinary quoting accounts can contend for the
  // same quotation number without either of them reaching an admin-only rule
  // branch and proving something other than what the test claims.
  [UIDS.otherStaff]: { name: 'Second Manager', email: 'staff2@example.invalid', role: 'staff', active: true },
  [UIDS.worker]: { name: 'Staff Person', email: 'worker@example.invalid', role: 'worker', active: true },
  [UIDS.switchedOff]: { name: 'Switched off', email: 'off@example.invalid', role: 'staff', active: false },
};

async function createTestEnvironment() {
  return initializeTestEnvironment({
    projectId: 'smartie-rules-test',
    firestore: {
      rules: fs.readFileSync(path.join(__dirname, '..', 'firestore.rules'), 'utf8'),
      host: '127.0.0.1',
      port: 8080,
    },
  });
}

/**
 * Seeds the people and the access document. `primaryOwnerUid` is only written
 * when asked, so the tests can cover both the migrated state and the
 * transition state where the owner is still identified by email.
 */
async function seed(testEnv, { withPrimaryOwnerUid = true, secondOwnerUid = UIDS.additionalOwner } = {}) {
  await testEnv.clearFirestore();
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    for (const [uid, profile] of Object.entries(PEOPLE)) {
      await db.collection('users').doc(uid).set(profile);
    }
    const access = { secondOwnerUid, updatedAt: Date.now(), updatedBy: UIDS.primaryOwner };
    if (withPrimaryOwnerUid) access.primaryOwnerUid = UIDS.primaryOwner;
    await db.collection('teamSettings').doc('access').set(access);
  });
}

function as(testEnv, uid) {
  const person = PEOPLE[uid];
  return testEnv.authenticatedContext(uid, person ? { email: person.email } : {}).firestore();
}

/**
 * A refusal **by the rule**, never by the engine giving up. Every refusal in
 * this suite goes through it.
 *
 * The rules engine stops at 1,000 evaluated expressions per request and
 * **denies** the write, so a plain `assertFails` cannot tell a refusal by the
 * clause a test names from the engine running out of budget. N5.10's first
 * draft reached that limit on a valid write, and its refusal tests passed
 * because of it; N5.10b then found 58 refusals across `purchase.test.js` and
 * `data.test.js` that passed only because of it. This fails the test when
 * the denial is the limit's, whatever else it says.
 *
 * Returns the error, as `assertFails` does.
 */
async function refused(write) {
  const error = await assertFails(write);
  assert.doesNotMatch(String(error?.message ?? error), /maximum of 1000 expressions/,
    'refused by the expression limit, not by the rule');
  return error;
}

module.exports = { createTestEnvironment, seed, as, refused, UIDS, PEOPLE, OWNER_EMAIL };
