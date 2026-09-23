const test = require('node:test');
const assert = require('node:assert/strict');
const { assertFails, assertSucceeds } = require('@firebase/rules-unit-testing');
const { createTestEnvironment, seed, as, UIDS } = require('./helpers');

/**
 * What `/products` actually accepts.
 *
 * **Nothing here changes a rule.** The `/products` block has been deployed
 * since N0 and had, until this file, *no positive-path coverage at all*:
 * `priceOk` appeared nowhere in this suite, every product write in it ran
 * under `withSecurityRulesDisabled`, and the single negative case
 * (`data.test.js:50`) is refused at `admin()` before one field predicate
 * evaluates. So the whole `seedModel`/`gst`/`priceOk`/`active` chain had never
 * been shown to accept anything, or to refuse anything. N5.7 builds the first
 * writer against that rule, so what it does is written down first.
 *
 * **The shape in [v8c4Product] is the one the live PWA writes**, traced field
 * by field from `fbPushProduct` by the Owner. It is pinned here so that if
 * that reading is wrong, this file fails in CI rather than the PWA failing on
 * the day the rules are deployed.
 *
 * **Why a whole-document write is the design.** An update is validated
 * against the *merged post-state*, not against the keys it touches — so a
 * document left behind by an older PWA version (`gst` as a string, `active`
 * as `1`, no `seedModel`) refuses even a one-field correction. That pair is
 * pinned below in `a one-field edit on a legacy document is refused` and the
 * test after it, and it is the whole reason the N5.7 editor writes every field
 * the rule names rather than only what changed.
 *
 * **One thing this file settled that nothing in the repository recorded:** a
 * key that is *absent* is not read as `null`. `firestore.rules:167-180` reads
 * nine possibly-absent keys bare, and the emulator answers in its own words —
 * `Property seedModel is undefined on object.`, reported as
 * `evaluation error at L167:32`. An allow whose condition errors does not
 * grant, so an absent key is a refusal. That is why the editor sends
 * `contractor: null` rather than leaving the key out.
 *
 * Every refusal test differs from the accepted document in **exactly one
 * field**, because the rules engine reports the position of the whole `allow`
 * condition rather than the conjunct that failed. Vary two things and the test
 * no longer says which one it proved. `firestore-debug.log` is a trap here for
 * the same reason: it logs `evaluation error` against the line the `allow`
 * starts on, not the conjunct that failed.
 *
 * Titles, for reading this file: stored `staff` is displayed **Manager**,
 * stored `worker` is displayed **Staff**.
 */

let testEnv;

test.before(async () => { testEnv = await createTestEnvironment(); });
test.after(async () => { await testEnv?.cleanup(); });
test.beforeEach(async () => { await seed(testEnv); });

const products = (db) => db.collection('products');

const DOC = 'gateMotors__SIE1000';

/**
 * Exactly what `fbPushProduct` writes, at the types the Owner traced from the
 * V8C4 source on 23 Sept 2026.
 *
 * `contractor: null` is deliberate and is not an oversight: V8C4's `rate()`
 * returns `{v: null, unset: true}` for a price the book does not give, and
 * `applyProductDoc` reads a stored `null` back as "deliberately not set"
 * rather than falling back to the seed figure. A rule that refused it would
 * refuse a legitimate PWA save.
 */
function v8c4Product(overrides = {}) {
  return {
    id: 'gateMotors|SIE1000',
    key: 'gateMotors|SIE1000',
    group: 'gateMotors',
    seedModel: 'SIE1000',
    model: 'SIE1000',
    name: 'Sliding gate motor 1000 kg',
    unit: 'per sq ft',
    spec: '',
    gst: 18,
    dealer: 18500,
    contractor: null,
    client: 25900,
    conflictResolved: true,
    categoryId: 'cat-other',
    active: true,
    updated: 1758600000000,
    by: 'Primary Owner',
    byUid: UIDS.primaryOwner,
    ...overrides,
  };
}

/** The same document with one key removed, for the absence cases. */
function without(field) {
  const document = v8c4Product();
  delete document[field];
  return document;
}

/** Plants a document the rules would refuse, the way an older PWA left it. */
async function givenLegacyProduct(fields) {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('products').doc(DOC).set(fields);
  });
}

// --- the shape the live PWA writes ---------------------------------------------

test('the document V8C4 actually writes for a product is accepted', async () => {
  const db = as(testEnv, UIDS.primaryOwner);
  await assertSucceeds(products(db).doc(DOC).set(v8c4Product()));
});

test('and an Administrator may write it too, not only the Owner', async () => {
  const db = as(testEnv, UIDS.admin);
  await assertSucceeds(products(db).doc(DOC).set(v8c4Product()));
});

test('a contractor price of null is accepted, because the PWA writes it', async () => {
  // Guarded separately from the shape above so that if this is ever the thing
  // that breaks, the failing test names it.
  const db = as(testEnv, UIDS.primaryOwner);
  await assertSucceeds(products(db).doc(DOC).set(v8c4Product({ contractor: null })));
  await assertSucceeds(
    products(db).doc(DOC).set(v8c4Product({ dealer: null, contractor: null, client: null })),
  );
});

test('a product priced in square feet is accepted, and the unit is not validated', async () => {
  // N5.7 stores `per sq ft`. No rule mentions `unit` at all, which is recorded
  // here so that a later rule change cannot quietly start constraining it.
  const db = as(testEnv, UIDS.primaryOwner);
  await assertSucceeds(products(db).doc(DOC).set(v8c4Product({ unit: 'per sq ft' })));
  await assertSucceeds(products(db).doc(DOC).set(v8c4Product({ unit: 'per m' })));
  await assertSucceeds(products(db).doc(DOC).set(v8c4Product({ unit: '' })));
});

test('and so is the minimum chargeable area N5.7 adds', async () => {
  // Additive: the rule's conjuncts are assertions about named fields rather
  // than a `hasOnly`, so an extra key is permitted.
  const db = as(testEnv, UIDS.primaryOwner);
  await assertSucceeds(products(db).doc(DOC).set(v8c4Product({ minSqft: 10 })));
});

// --- one field wrong at a time -------------------------------------------------

test('a product with no seedModel is refused', async () => {
  const db = as(testEnv, UIDS.primaryOwner);
  await assertFails(products(db).doc(DOC).set(without('seedModel')));
});

test('a gst stored as the string an older PWA wrote is refused', async () => {
  const db = as(testEnv, UIDS.primaryOwner);
  await assertFails(products(db).doc(DOC).set(v8c4Product({ gst: '18' })));
});

test('an active of 1 rather than true is refused', async () => {
  const db = as(testEnv, UIDS.primaryOwner);
  await assertFails(products(db).doc(DOC).set(v8c4Product({ active: 1 })));
});

test('a price stored as a formatted string is refused', async () => {
  const db = as(testEnv, UIDS.primaryOwner);
  await assertFails(products(db).doc(DOC).set(v8c4Product({ dealer: '1,250.50' })));
});

test('and so is the PWA unset marker, which only the reader understands', async () => {
  // `∅` is the PWA's NULLP. Tolerant readers map it to null, but the rule sees
  // a string, so the editor must normalise it to null before writing.
  const db = as(testEnv, UIDS.primaryOwner);
  await assertFails(products(db).doc(DOC).set(v8c4Product({ contractor: '∅' })));
});

test('a gst above the highest Indian slab is refused', async () => {
  const db = as(testEnv, UIDS.primaryOwner);
  await assertFails(products(db).doc(DOC).set(v8c4Product({ gst: 29 })));
  await assertSucceeds(products(db).doc(DOC).set(v8c4Product({ gst: 28 })));
});

test('a negative price is refused', async () => {
  const db = as(testEnv, UIDS.primaryOwner);
  await assertFails(products(db).doc(DOC).set(v8c4Product({ client: -1 })));
  await assertSucceeds(products(db).doc(DOC).set(v8c4Product({ client: 0 })));
});

test('an empty model or group is refused', async () => {
  const db = as(testEnv, UIDS.primaryOwner);
  await assertFails(products(db).doc(DOC).set(v8c4Product({ model: '' })));
  await assertFails(products(db).doc(DOC).set(v8c4Product({ group: '' })));
});

// --- the absence question ------------------------------------------------------

test('an absent price key is refused where a null one is accepted', async () => {
  // THE PROBE, and the reason this file was written before any Kotlin.
  //
  // `priceOk(request.resource.data.contractor)` reads the key **bare**, where
  // ~30 other sites in the same file guard with `.get()` or `hasAny()` —
  // `firestore.rules:85` does exactly that for these same four fields. In CEL
  // a missing map key is an *error*, not `null`, and an `allow` whose
  // condition errors does not grant.
  //
  // **Settled, and not by reasoning.** The emulator says so itself: the
  // refusal below is reported as `evaluation error at L167:32`, and the same
  // probe against `seedModel` names it outright —
  // `Property seedModel is undefined on object.` An absent key errors, and an
  // allow whose condition errors does not grant.
  //
  // This is what makes the N5.7 editor send `contractor: null` where the
  // stored key is missing rather than leaving it absent. The two writes below
  // are the same document, at the same path, by the same account, differing
  // in nothing but whether the key is present.
  const db = as(testEnv, UIDS.primaryOwner);
  await assertFails(products(db).doc(DOC).set(without('contractor')));
  await assertSucceeds(products(db).doc(DOC).set(v8c4Product({ contractor: null })));
});

test('a gst key that is absent is refused', async () => {
  // The same question for a field with no `null` form at all, so the two
  // cases cannot be confused.
  const db = as(testEnv, UIDS.primaryOwner);
  await assertFails(products(db).doc(DOC).set(without('gst')));
});

// --- why the editor writes every field -----------------------------------------

test('a one-field edit on a legacy document is refused', async () => {
  // The thesis of N5.7, pinned. An update is validated against the merged
  // post-state, so a document an older PWA left with a string `gst` refuses
  // even a correction that does not touch `gst`. There is no partial fix.
  await givenLegacyProduct({
    id: 'gateMotors|SIE1000', key: 'gateMotors|SIE1000', group: 'gateMotors',
    seedModel: 'SIE1000', model: 'SIE1000', name: 'Sliding gate motor',
    gst: '18', dealer: 18500, contractor: null, client: 25900, active: 1,
  });
  const db = as(testEnv, UIDS.primaryOwner);
  await assertFails(products(db).doc(DOC).update({ unit: 'per sq ft' }));
});

test('and the same edit as a complete, correctly typed write is accepted', async () => {
  // The other half of the pair: the legacy document repairs itself as a side
  // effect of any edit, which is why no rule change is needed.
  await givenLegacyProduct({
    id: 'gateMotors|SIE1000', key: 'gateMotors|SIE1000', group: 'gateMotors',
    seedModel: 'SIE1000', model: 'SIE1000', name: 'Sliding gate motor',
    gst: '18', dealer: 18500, contractor: null, client: 25900, active: 1,
  });
  const db = as(testEnv, UIDS.primaryOwner);
  await assertSucceeds(
    products(db).doc(DOC).set(v8c4Product({ unit: 'per sq ft' }), { merge: true }),
  );
  const stored = await products(as(testEnv, UIDS.admin)).doc(DOC).get();
  assert.equal(stored.data().gst, 18, 'the string gst must have been repaired');
  assert.equal(stored.data().active, true, 'and the 1 must have become a boolean');
});

test('a document written before seedModel existed repairs the same way', async () => {
  await givenLegacyProduct({
    id: 'gateMotors|SIE1000', group: 'gateMotors', model: 'SIE1000',
    name: 'Sliding gate motor', gst: 18, dealer: 18500, active: true,
  });
  const db = as(testEnv, UIDS.primaryOwner);
  await assertFails(products(db).doc(DOC).update({ name: 'Sliding gate motor 1000 kg' }));
  await assertSucceeds(products(db).doc(DOC).set(v8c4Product(), { merge: true }));
});

// --- who may write -------------------------------------------------------------

test('a Manager is refused a product write however well formed it is', async () => {
  // Refused at `admin()`, before any field predicate runs — so this says
  // nothing about the shape, and is not evidence that the shape is good.
  const db = as(testEnv, UIDS.staff);
  await assertFails(products(db).doc(DOC).set(v8c4Product()));
});

test('and so is a Staff account, which cannot even read the catalogue', async () => {
  const db = as(testEnv, UIDS.worker);
  await assertFails(products(db).doc(DOC).set(v8c4Product()));
  await assertFails(products(db).doc(DOC).get());
});

test('a switched-off account is refused', async () => {
  const db = as(testEnv, UIDS.switchedOff);
  await assertFails(products(db).doc(DOC).set(v8c4Product()));
});

test('deleting a product stays with an Administrator', async () => {
  await givenLegacyProduct(v8c4Product());
  await assertFails(products(as(testEnv, UIDS.staff)).doc(DOC).delete());
  await assertSucceeds(products(as(testEnv, UIDS.admin)).doc(DOC).delete());
});
