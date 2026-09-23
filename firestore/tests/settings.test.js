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
 * `next >= resource.data.next`. Three things close it now, and N5.6b's
 * ablation established which does what:
 *
 *   1. An Administrator no longer reaches configuration at all.
 *   2. `next` must be **strictly greater** wherever it is being changed. This
 *      is what refuses the original re-take; it did so on its own.
 *   3. Configuration may never stamp `lastIssued`. This closes the gap that
 *      (2)'s `!touched(['next'])` escape hatch opens — the hatch exists so a
 *      prefix can be corrected without burning a quotation number, and with
 *      it present a stale re-stamp that leaves `next` alone would otherwise
 *      walk through.
 *
 * Removing either of (2) or (3) alone fails two tests in this file; that is
 * how the division of labour above was established rather than argued.
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

// --- N5.6b: what V8C4 will accept back ------------------------------------------

test('pad is bounded to what V8C4 itself allows, 1 to 6', async () => {
  // V8C4 clamps with `Math.min(6, Math.max(1, pd||3))` on both of its save
  // paths, and its inputs are min="1" max="6". A pad of 7 set here would show
  // up in that input and be silently rewritten to 6 on the PWA's next
  // settings save — changing the printed number format with nobody asking.
  await givenCounter();
  const db = numbering(as(testEnv, UIDS.primaryOwner));
  const at = { prefix: 'SIE/QD', fy: '2025-26', next: 12 };

  await assertFails(db.update({ ...at, pad: 0 }));
  await assertFails(db.update({ ...at, pad: 7 }));
  await assertFails(db.update({ ...at, pad: '3' }));

  await assertSucceeds(db.update({ ...at, pad: 1 }));
  await assertSucceeds(db.update({ prefix: 'SIE/QD', fy: '2025-26', next: 13, pad: 6 }));
  assert.equal((await stored('numbering')).pad, 6);
});

test('the financial year must read like 2026-27, and matches() is proved to anchor', async () => {
  // `matches()` is not assumed to anchor — `2026-278` differs from an
  // accepted value only at the *end*, so it can only be refused if the `$` is
  // being honoured. `2026-27` differing only at the start is covered by the
  // accepted case below.
  await givenCounter();
  const db = numbering(as(testEnv, UIDS.primaryOwner));
  const at = { prefix: 'SIE/QD', pad: 3, next: 12 };

  await assertFails(db.update({ ...at, fy: '2026-278' }));
  await assertFails(db.update({ ...at, fy: 'x2026-27' }));
  await assertFails(db.update({ ...at, fy: '2026-2' }));
  await assertFails(db.update({ ...at, fy: '202-267' }));

  await assertSucceeds(db.update({ ...at, fy: '2026-27' }));
  assert.equal((await stored('numbering')).fy, '2026-27');
});

test('the prefix V8C4 actually stores still saves', async () => {
  // `SIE/QD` is what the exported production counter holds, and what every
  // issued number in `lastIssued` is built from. A prefix pattern that
  // refused it would leave the Owner unable to save the counter at all, so
  // this pins the real value against whatever pattern is chosen later.
  await givenCounter();
  await assertSucceeds(numbering(as(testEnv, UIDS.primaryOwner)).update({
    prefix: 'SIE/QD', fy: '2025-26', next: 12, pad: 3, updated: Date.now(), by: 'Primary Owner',
  }));
});

test('a stored pad the configuration branch would now refuse does not stop a number going out', async () => {
  // The reason `pad` and `fy` are checked on the configuration branch only.
  // A counter seeded by an older V8C4 build can hold `pad: 9`; issuing reads
  // that value and freezes it, and must not start failing because of it.
  await givenCounter({ pad: 9 });
  await assertSucceeds(numbering(as(testEnv, UIDS.staff)).update(issuing(UIDS.staff)));
  assert.equal((await stored('numbering')).next, 10);

  // And the same for a financial year the pattern would refuse.
  await givenCounter({ fy: 'FY25', pad: 3 });
  await assertSucceeds(numbering(as(testEnv, UIDS.staff)).update(issuing(UIDS.staff)));
});

// --- a defect being recorded, not approved ----------------------------------------

test('a Manager is refused /teamSettings/access, and the catch-all no longer overrides it', async () => {
  // **The N5.6b characterisation test, flipped.** It asserted `assertSucceeds`
  // with the defect named beside it: `/teamSettings/access` declared
  // `allow read: if admin()` and was overridden by a `/teamSettings/{other}`
  // catch-all granting `member() && !worker()` read to the whole collection.
  // Firestore ORs across every matching rule, so the narrower named rule could
  // not take anything away.
  //
  // The catch-all is now closed both ways. Every document either app uses has
  // its own named rule, so nothing legitimate lost a read — the tests below
  // prove that document by document.
  const access = (uid) => as(testEnv, uid).collection('teamSettings').doc('access');

  await assertFails(access(UIDS.staff).get());
  await assertFails(access(UIDS.worker).get());

  await assertSucceeds(access(UIDS.admin).get());
  await assertSucceeds(access(UIDS.primaryOwner).get());
});

test('an unnamed teamSettings document is closed to everybody, including the Owner', async () => {
  // What the catch-all is for now: a document nobody has written a rule for
  // is shut by default rather than open by default.
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('teamSettings').doc('someFutureThing').set({ x: 1 });
  });
  const future = (uid) => as(testEnv, uid).collection('teamSettings').doc('someFutureThing');

  await assertFails(future(UIDS.primaryOwner).get());
  await assertFails(future(UIDS.admin).get());
  await assertFails(future(UIDS.staff).get());
  await assertFails(future(UIDS.primaryOwner).set({ x: 2 }));
});

test('every teamSettings document either app uses is still readable by the roles that need it', async () => {
  // Closing the catch-all is only safe because each of these has a named
  // rule. The native app touches access, categories, numbering, productPins
  // and quoting; V8C4 touches numbering, company, categories, productPins and
  // access. This walks the union, document by document, rather than trusting
  // that the named rules were all present.
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.collection('teamSettings').doc('company').set({ name: 'Smart India Enterprises' });
    await db.collection('teamSettings').doc('categories').set({ map: {} });
    await db.collection('teamSettings').doc('productPins').set({ keys: [] });
  });
  await givenCounter();
  await givenCap();

  const read = (uid, id) => as(testEnv, uid).collection('teamSettings').doc(id).get();

  // Everyone who can quote — Owner, Administrator, Manager.
  for (const uid of [UIDS.primaryOwner, UIDS.admin, UIDS.staff]) {
    for (const id of ['numbering', 'quoting', 'company', 'categories', 'productPins']) {
      await assertSucceeds(read(uid, id));
    }
  }

  // A Staff account reads none of them, exactly as before.
  for (const id of ['numbering', 'quoting', 'company', 'categories', 'productPins', 'access']) {
    await assertFails(read(UIDS.worker, id));
  }
});

// --- N5.6c: the prefix pattern ----------------------------------------------------

test('the prefix admits the multi-segment value the live counter holds', async () => {
  // `SIE/QD` is two segments, so a quotation number built from it has four:
  // {prefix}/{fy}/{n} is SIE/QD/2025-26/009. A pattern without `/` would
  // refuse every save of the real data — which is why the first pattern
  // proposed for this was held rather than shipped.
  await givenCounter();
  const db = numbering(as(testEnv, UIDS.primaryOwner));
  const at = { fy: '2025-26', pad: 3, next: 12 };

  await assertSucceeds(db.update({ ...at, prefix: 'SIE/QD' }));
  await assertSucceeds(db.update({ prefix: 'SIE/QT', fy: '2025-26', pad: 3, next: 13 }));
  await assertSucceeds(db.update({ prefix: 'SIE-QD', fy: '2025-26', pad: 3, next: 14 }));
  await assertSucceeds(db.update({ prefix: 'A', fy: '2025-26', pad: 3, next: 15 }));
  await assertSucceeds(db.update({ prefix: 'A'.repeat(16), fy: '2025-26', pad: 3, next: 16 }));
});

test('and refuses whitespace, quotes, control characters and anything too long', async () => {
  // The pattern is anchored at both ends: `SIE/QD ` differs from an accepted
  // value only at the end, and `A`.repeat(17) only in length.
  await givenCounter();
  const db = numbering(as(testEnv, UIDS.primaryOwner));
  const at = { fy: '2025-26', pad: 3, next: 12 };

  await assertFails(db.update({ ...at, prefix: 'SIE QD' }));
  await assertFails(db.update({ ...at, prefix: 'SIE/QD ' }));
  await assertFails(db.update({ ...at, prefix: ' SIE/QD' }));
  await assertFails(db.update({ ...at, prefix: "SIE'QD" }));
  await assertFails(db.update({ ...at, prefix: 'SIE"QD' }));
  await assertFails(db.update({ ...at, prefix: 'SIE\nQD' }));
  await assertFails(db.update({ ...at, prefix: 'SIE\u0000QD' }));
  await assertFails(db.update({ ...at, prefix: '/SIE' }));
  await assertFails(db.update({ ...at, prefix: '-SIE' }));
  await assertFails(db.update({ ...at, prefix: '' }));
  await assertFails(db.update({ ...at, prefix: 'A'.repeat(17) }));
});

test('seeding a counter is bounded exactly as configuring one is', async () => {
  // N8's production migration seeds on an unseeded project, so this branch
  // must not be the weaker of the two.
  const ownerDb = numbering(as(testEnv, UIDS.primaryOwner));
  const good = { prefix: 'SIE/QD', fy: '2025-26', next: 1, pad: 3 };

  await assertFails(ownerDb.set({ ...good, prefix: 'SIE QD' }));
  await assertFails(ownerDb.set({ ...good, prefix: 'A'.repeat(17) }));
  await assertFails(ownerDb.set({ ...good, fy: '2026-278' }));
  await assertFails(ownerDb.set({ ...good, pad: 7 }));
  await assertFails(ownerDb.set({ ...good, pad: 0 }));

  await assertSucceeds(ownerDb.set(good));
  assert.equal((await stored('numbering')).prefix, 'SIE/QD');
});
