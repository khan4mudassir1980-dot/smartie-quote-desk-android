const test = require('node:test');
const assert = require('node:assert/strict');
const { assertFails, assertSucceeds } = require('@firebase/rules-unit-testing');
const { createTestEnvironment, seed, as, UIDS } = require('./helpers');

/**
 * The two settings documents N5.6 puts behind the Owner.
 *
 * **`/teamSettings/numbering` has two update branches and they are not the
 * same permission.** Configuring the counter — its prefix, financial year,
 * padding, or where `next` sits — is the Owner's, because it decides what
 * every future quotation number looks like. *Issuing* a number is every
 * quoting role's, from either app, and that branch is deliberately untouched:
 * narrowing it would stop the PWA the day these rules deploy.
 *
 * Which branch a write lands in is decided by **what it touches**, not by who
 * is signed in. That is why an Administrator issuing from a stale read used to
 * be able to re-take a spent number: their write failed the issue branch's
 * exact `+ 1` and fell through to a configuration branch that asked only for
 * `next >= resource.data.next`. Three things close it now: an Administrator
 * no longer reaches configuration at all; configuration may never stamp
 * `lastIssued`, which a stale issue carries by definition; and `next` must be
 * strictly greater wherever it is being changed inside a financial year.
 *
 * The middle one is the load-bearing half, and it was not in the plan.
 * Strictly-greater alone looked sufficient and shipped for one commit — until
 * a screen test showed it also made renaming the prefix cost a quotation
 * number, because leaving `next` where it is was being refused along with
 * moving it backwards.
 *
 * `/teamSettings/quoting` is new and holds the Manager discount cap alone.
 *
 * Titles, for reading this file: stored `staff` is displayed **Manager**,
 * stored `worker` is displayed **Staff**.
 */

let testEnv;

test.before(async () => { testEnv = await createTestEnvironment(); });
test.after(async () => { await testEnv?.cleanup(); });
test.beforeEach(async () => { await seed(testEnv); });

const numbering = (db) => db.collection('teamSettings').doc('numbering');
const quoting = (db) => db.collection('teamSettings').doc('quoting');

/** The counter as V8C4 seeds it, planted with the rules disabled. */
async function givenCounter(fields = {}) {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('teamSettings').doc('numbering').set({
      prefix: 'SIE/QD', fy: '2025-26', next: 9, pad: 3, ...fields,
    });
  });
}

async function givenCap(managerDiscountPct = 10) {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('teamSettings').doc('quoting').set({ managerDiscountPct });
  });
}

async function stored(id) {
  let data;
  await testEnv.withSecurityRulesDisabled(async (context) => {
    data = (await context.firestore().collection('teamSettings').doc(id).get()).data();
  });
  return data;
}

/** One issue-shaped write, which is what the PWA sends. */
const issuing = (uid, extra = {}) => ({
  next: 10,
  lastIssued: { no: 'SIE/QD/2025-26/009', at: Date.now(), by: 'Manager Person', uid, ...extra },
});

// --- who may configure the counter -------------------------------------------

test('configuring the counter is the Owner s, and an Administrator is refused it', async () => {
  await givenCounter();
  const config = { prefix: 'SIE/QD', fy: '2025-26', next: 12, pad: 3 };

  await assertFails(numbering(as(testEnv, UIDS.admin)).update(config));
  await assertFails(numbering(as(testEnv, UIDS.staff)).update(config));
  await assertFails(numbering(as(testEnv, UIDS.worker)).update(config));

  await assertSucceeds(numbering(as(testEnv, UIDS.primaryOwner)).update(config));
});

test('and an Owner who is not the Primary Owner configures it too', async () => {
  // `owner()` is a role, not a seat. The Primary Owner is protected in the
  // team rules; here the two are the same permission, and asserting it stops
  // a future `isPrimaryOwner()` creeping in and locking out the second Owner.
  await givenCounter();
  await assertSucceeds(numbering(as(testEnv, UIDS.additionalOwner)).update({
    prefix: 'SIE/QD', fy: '2025-26', next: 12, pad: 3,
  }));
});

test('an Owner s V8C4-shaped save passes the configuration branch unchanged', async () => {
  // V8C4's `fbSaveNumbering` writes exactly these six keys through a merge
  // transaction. The keys beyond the four the rule names — `pad`, `updated`
  // and `by` — are permitted because the configuration branch carries no
  // `hasOnly`, and this pins that: a `hasOnly` added here later would refuse
  // the PWA's own settings screen.
  await givenCounter();
  await assertSucceeds(numbering(as(testEnv, UIDS.primaryOwner)).update({
    prefix: 'SIE/QD', fy: '2025-26', next: 12, pad: 3, updated: Date.now(), by: 'Primary Owner',
  }));
  assert.equal((await stored('numbering')).next, 12);
});

test('seeding a counter that does not exist is the Owner s alone', async () => {
  const fresh = { prefix: 'SIE/QD', fy: '2025-26', next: 1, pad: 3 };

  await assertFails(numbering(as(testEnv, UIDS.admin)).set(fresh));
  await assertFails(numbering(as(testEnv, UIDS.staff)).set(fresh));
  await assertSucceeds(numbering(as(testEnv, UIDS.primaryOwner)).set(fresh));
});

// --- where `next` may be moved to ---------------------------------------------

test('a forward correction is allowed and rewinding is not', async () => {
  await givenCounter();
  const db = numbering(as(testEnv, UIDS.primaryOwner));
  const at = { prefix: 'SIE/QD', fy: '2025-26' };

  // `next` is the number the *next* quotation will carry, so 9 has not gone
  // out yet and 8 has. Setting the counter back to 8 would issue a number a
  // customer is already holding.
  await assertFails(db.update({ ...at, next: 8 }));
  await assertFails(db.update({ ...at, next: 1 }));

  await assertSucceeds(db.update({ ...at, next: 10 }));
  await assertSucceeds(db.update({ ...at, next: 40 }));
  assert.equal((await stored('numbering')).next, 40);
});

test('and the prefix or padding can be corrected without burning a number', async () => {
  // The rule shipped for one commit requiring `next` to be strictly greater
  // on *every* configuration write, which made this impossible: renaming the
  // prefix would have cost a quotation number. Leaving `next` alone is not a
  // move forward, and has to stay allowed.
  await givenCounter();
  const db = numbering(as(testEnv, UIDS.primaryOwner));

  await assertSucceeds(db.update({
    prefix: 'SIE/QT', fy: '2025-26', next: 9, pad: 4, updated: Date.now(), by: 'Primary Owner',
  }));

  const after = await stored('numbering');
  assert.equal(after.prefix, 'SIE/QT');
  assert.equal(after.pad, 4);
  assert.equal(after.next, 9);
});

test('but configuration may never stamp lastIssued, whatever else it does', async () => {
  // This is what actually closes N5.1's second finding. The stale write that
  // caused it carries a `lastIssued` by definition, so forbidding the key on
  // this branch stops a spent number being re-taken however `next` is set —
  // including by an Owner, who is the only one who reaches this branch now.
  await givenCounter();
  const db = numbering(as(testEnv, UIDS.primaryOwner));
  const lastIssued = { no: 'SIE/QD/2025-26/008', at: Date.now(), by: 'Primary Owner', uid: UIDS.primaryOwner };

  await assertFails(db.update({ prefix: 'SIE/QD', fy: '2025-26', next: 9, updated: Date.now(), lastIssued }));
  await assertFails(db.update({ prefix: 'SIE/QD', fy: '2025-26', next: 40, updated: Date.now(), lastIssued }));
  // Issuing still stamps it, through the branch that advances by exactly one.
  await assertSucceeds(db.update(issuing(UIDS.primaryOwner)));
});

test('a new financial year may start anywhere at or above one', async () => {
  // The only case where `next` legitimately goes backwards: a new year
  // restarts the sequence, so the strictly-greater rule does not apply.
  await givenCounter({ next: 87 });
  const db = numbering(as(testEnv, UIDS.primaryOwner));

  await assertSucceeds(db.update({ prefix: 'SIE/QD', fy: '2026-27', next: 1 }));
  assert.equal((await stored('numbering')).next, 1);

  await assertFails(db.update({ prefix: 'SIE/QD', fy: '2027-28', next: 0 }));
});

// --- issuing is untouched, and that is the point ------------------------------

test('a Manager still issues a number, and still advances the counter by exactly one', async () => {
  await givenCounter();
  const db = numbering(as(testEnv, UIDS.staff));

  // Skipping is refused: the issue branch is an exact `+ 1`, and a Manager
  // has no configuration branch to fall through to.
  await assertFails(db.update({ ...issuing(UIDS.staff), next: 11 }));
  await assertSucceeds(db.update(issuing(UIDS.staff)));
  assert.equal((await stored('numbering')).next, 10);
});

test('and cannot reach the configuration branch by touching the shape of a number', async () => {
  await givenCounter();
  const db = numbering(as(testEnv, UIDS.staff));
  const lastIssued = issuing(UIDS.staff).lastIssued;

  await assertFails(db.update({ next: 10, lastIssued, prefix: 'SIE/X' }));
  await assertFails(db.update({ next: 10, lastIssued, fy: '2026-27' }));
  await assertFails(db.update({ next: 10, lastIssued, pad: 4 }));
  await assertSucceeds(db.update({ next: 10, lastIssued }));
});

// --- the issuer marker --------------------------------------------------------

test('V8C4 writes lastIssued with no src at all, and must keep passing', async () => {
  // The compatibility assertion this whole marker depends on. A missing key
  // reads as `''`, which is a string of size 0, so `numberingSrcOk()` is
  // satisfied by a write that has never heard of it.
  await givenCounter();
  await assertSucceeds(numbering(as(testEnv, UIDS.staff)).update(issuing(UIDS.staff)));
});

test('a src is accepted when it is a short string', async () => {
  await givenCounter();
  await assertSucceeds(numbering(as(testEnv, UIDS.staff)).update(issuing(UIDS.staff, { src: 'android' })));
  assert.equal((await stored('numbering')).lastIssued.src, 'android');
});

test('and refused when it is too long, or not a string at all', async () => {
  await givenCounter();
  const db = numbering(as(testEnv, UIDS.staff));

  await assertSucceeds(db.update(issuing(UIDS.staff, { src: 'x'.repeat(16) })));
  await givenCounter();
  await assertFails(db.update(issuing(UIDS.staff, { src: 'x'.repeat(17) })));
  await assertFails(db.update(issuing(UIDS.staff, { src: 7 })));
});

test('the counter can never be deleted, not even by the Owner', async () => {
  await givenCounter();
  await assertFails(numbering(as(testEnv, UIDS.primaryOwner)).delete());
});

// --- the Manager discount cap --------------------------------------------------

test('the discount cap is readable by everyone who can quote, and not by Staff', async () => {
  // The quotation form has to show a Manager the limit it is about to hold
  // them to, so reading it follows the quoting roles rather than the Owner.
  await givenCap(10);

  await assertSucceeds(quoting(as(testEnv, UIDS.staff)).get());
  await assertSucceeds(quoting(as(testEnv, UIDS.admin)).get());
  await assertSucceeds(quoting(as(testEnv, UIDS.primaryOwner)).get());
  await assertFails(quoting(as(testEnv, UIDS.worker)).get());
  await assertFails(quoting(as(testEnv, UIDS.outsider)).get());
});

test('and written by the Owner alone', async () => {
  await assertFails(quoting(as(testEnv, UIDS.admin)).set({ managerDiscountPct: 10 }));
  await assertFails(quoting(as(testEnv, UIDS.staff)).set({ managerDiscountPct: 10 }));

  await assertSucceeds(quoting(as(testEnv, UIDS.primaryOwner)).set({ managerDiscountPct: 10 }));
  assert.equal((await stored('quoting')).managerDiscountPct, 10);

  // Changing it is the same permission as creating it.
  await assertFails(quoting(as(testEnv, UIDS.admin)).update({ managerDiscountPct: 50 }));
  await assertSucceeds(quoting(as(testEnv, UIDS.primaryOwner)).update({ managerDiscountPct: 15 }));
});

test('a cap of nothing and a cap of everything are both real settings', async () => {
  // Zero means a Manager may not discount at all, which is a decision rather
  // than a missing value; 100 is the other end and is equally deliberate.
  const db = quoting(as(testEnv, UIDS.primaryOwner));

  await assertSucceeds(db.set({ managerDiscountPct: 0 }));
  await assertSucceeds(db.set({ managerDiscountPct: 100 }));
  await assertSucceeds(db.set({ managerDiscountPct: 12.5 }));
});

test('but a cap outside nought to a hundred is not', async () => {
  const db = quoting(as(testEnv, UIDS.primaryOwner));

  await assertFails(db.set({ managerDiscountPct: -1 }));
  await assertFails(db.set({ managerDiscountPct: 101 }));
  await assertFails(db.set({ managerDiscountPct: '10' }));
  await assertFails(db.set({ somethingElse: 10 }));
});

test('the cap document can never be deleted', async () => {
  await givenCap();
  await assertFails(quoting(as(testEnv, UIDS.primaryOwner)).delete());
});
