/**
 * Drives the whole report-building path.
 *
 * The dry run once died with "Cannot access 'countShelves' before
 * initialization": the helpers the report literal called were `const` arrows
 * sitting below the top-level dispatch, so they were still in the temporal dead
 * zone when the report was built. `node --check` parses a file without running
 * it, so it saw nothing. These tests execute `buildPlan`, which is that path.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import {
  buildCategoryMap,
  buildPins,
  buildPlan,
  buildProducts,
  countNulls,
  countShelves,
  differs,
  reconcileProducts,
  sameValue,
} from '../lib/plan.mjs';
import { productDocId, productKey } from '../lib/keys.mjs';

const product = (group, seedModel, extra = {}) => ({
  documentId: productDocId(group, seedModel),
  key: productKey(group, seedModel),
  group,
  seedModel,
  model: seedModel,
  name: `${seedModel} motor`,
  unit: 'each',
  spec: '',
  gst: 18,
  dealer: 100,
  contractor: 120,
  client: 140,
  kg: null,
  categoryId: 'cat-sliding',
  active: true,
  conflictResolved: true,
  ...extra,
});

const SEED = {
  sourceSha256: 'abc',
  categories: [
    { id: 'cat-sliding', name: 'Sliding Gate Motors', order: 10 },
    { id: 'cat-shutter', name: 'Shutter Motors', order: 30 },
    { id: 'cat-other', name: 'Other Products', order: 900 },
  ],
  products: [
    product('gate', 'SIE1000', { kg: 1000 }),
    product('gate', 'SIE2.5MSMALL'), // needs a character replaced in its id
    product('shutter', 'RS500', { categoryId: 'cat-shutter', dealer: null, contractor: null, client: null }),
  ],
};

test('the report builds — the path that used to throw', () => {
  const { report, documents } = buildPlan({ seed: SEED, project: 'smartie-quote-desk-staging' });

  assert.equal(documents.size, 3);
  assert.equal(report.seed.products, 3);
  assert.equal(report.mode, 'dry-run');
  assert.equal(report.counts.created, 3);
  assert.equal(report.counts.updated, 0);
  assert.equal(report.counts.unchanged, 0);
  assert.deepEqual(report.shelfCounts, { 'cat-sliding': 2, 'cat-shutter': 1 });
  assert.deepEqual(report.nullPrices, { allThree: 1, dealer: 1 });
  assert.equal(report.deviceBackup, 'NOT SUPPLIED');
  assert.equal(report.export, null);
});

test('the sanitised ids are the models that actually need one', () => {
  const { report } = buildPlan({ seed: SEED, project: 'staging' });
  assert.deepEqual(report.sanitisedIds, [
    { key: 'gate|SIE2.5MSMALL', documentId: 'gate__SIE2_5MSMALL' },
  ]);
});

test('every product is written with schemaVersion 2 and the canonical id', () => {
  const { documents } = buildPlan({ seed: SEED, project: 'staging' });
  for (const [id, fields] of documents) {
    assert.equal(fields.schemaVersion, 2);
    assert.equal(id, productDocId(fields.group, fields.seedModel));
    assert.equal(fields.seeded, true);
  }
});

test('a price the book does not give stays null rather than becoming zero', () => {
  const { documents } = buildPlan({ seed: SEED, project: 'staging' });
  const shutter = documents.get(productDocId('shutter', 'RS500'));
  assert.equal(shutter.dealer, null);
  assert.equal(shutter.contractor, null);
  assert.equal(shutter.client, null);
});

test('production beats the book, and the report says where', () => {
  const exported = {
    products: [
      {
        id: 'gate__SIE1000',
        data: { id: 'gate|SIE1000', key: 'gate|SIE1000', dealer: 175, updated: 1_700_000_000_000, by: 'Asha' },
      },
    ],
    pins: { keys: ['gate|SIE1000'] },
  };
  const { documents, actions, report } = buildPlan({ seed: SEED, exported, project: 'staging' });

  assert.equal(documents.get('gate__SIE1000').dealer, 175);
  // The timestamp is decided when the write is planned, not when the merge is.
  const write = actions.find((action) => action.id === 'gate__SIE1000');
  assert.equal(write.fields.updated, 1_700_000_000_000);
  assert.equal(write.fields.by, 'Asha');
  assert.deepEqual(report.productionBeatSeed, [
    { key: 'gate|SIE1000', field: 'dealer', seed: 100, production: 175 },
  ]);
  assert.deepEqual(report.pins.keys, ['gate|SIE1000']);
});

test('the newer of two id schemes wins', () => {
  const exported = {
    products: [
      { id: 'gate|SIE1000', data: { id: 'gate|SIE1000', dealer: 111, updated: 2 } },
      { id: 'gate__SIE1000', data: { id: 'gate|SIE1000', dealer: 222, updated: 9 } },
    ],
  };
  const { documents } = buildPlan({ seed: SEED, exported, project: 'staging' });
  assert.equal(documents.get('gate__SIE1000').dealer, 222);
  assert.deepEqual(documents.get('gate__SIE1000').legacyDocIds, ['gate__SIE1000']);
});

test('a deliberately unset price in production comes back as null, not as the sentinel', () => {
  const exported = {
    products: [{ id: 'gate__SIE1000', data: { id: 'gate|SIE1000', client: '∅', updated: 5 } }],
  };
  const { documents } = buildPlan({ seed: SEED, exported, project: 'staging' });
  assert.equal(documents.get('gate__SIE1000').client, null);
});

test('device overrides are listed but not applied unless asked for', () => {
  const device = { ov: { gate: { SIE1000: { cl: 999 } } } };

  const listed = buildPlan({ seed: SEED, device, project: 'staging' });
  assert.equal(listed.documents.get('gate__SIE1000').client, 140);
  assert.deepEqual(listed.report.deviceOverrides, [
    { key: 'gate|SIE1000', field: 'client', current: 140, device: 999, applied: false },
  ]);
  assert.equal(listed.report.deviceBackup, 'supplied');

  const applied = buildPlan({ seed: SEED, device, applyDeviceOverrides: true, project: 'staging' });
  assert.equal(applied.documents.get('gate__SIE1000').client, 999);
  assert.equal(applied.report.deviceOverridesApplied, true);
});

test('a device override can switch a product off', () => {
  const device = { ov: { gate: { SIE1000: { x: 1 } } } };
  const { documents } = buildPlan({ seed: SEED, device, applyDeviceOverrides: true, project: 'staging' });
  assert.equal(documents.get('gate__SIE1000').active, false);
});

test('the twelve defaults are added, and an existing shelf is never renamed', () => {
  const existing = { map: { 'cat-sliding': { id: 'cat-sliding', name: 'Sliding gates (renamed)', order: 5 } } };
  const map = buildCategoryMap(SEED, null, existing);
  assert.equal(map['cat-sliding'].name, 'Sliding gates (renamed)');
  assert.equal(map['cat-sliding'].order, 5);
  assert.equal(map['cat-other'].name, 'Other Products');
});

test('pins drop what no longer resolves, and never exceed the cap', () => {
  const { documents } = buildProducts(SEED, null, null, false);
  const wanted = ['gate|SIE1000', 'gate|GONE', 'shutter|RS500'];
  const { keys, dropped } = buildPins({ pins: { keys: wanted } }, documents);
  assert.deepEqual(keys, ['gate|SIE1000', 'shutter|RS500']);
  assert.deepEqual(dropped, [{ key: 'gate|GONE', why: 'no product with that key' }]);

  const many = Array.from({ length: 20 }, (_, index) => `gate|M${index}`);
  const capped = buildPins({ pins: { keys: many } }, new Map());
  assert.equal(capped.keys.length, 0);
  assert.equal(capped.dropped.length, 20);
});

/**
 * What staging would hold after a run: the documents that run actually wrote,
 * in the shape `readCollection` returns them.
 */
function stagingAfter(plan) {
  return plan.actions
    .filter((action) => action.type === 'product')
    .map((action) => ({ id: action.id, data: action.fields }));
}

test('a second run performs no writes at all, not merely no changes', () => {
  const first = buildPlan({ seed: SEED, project: 'staging', now: 1_000 });
  assert.equal(first.report.writes.total, 4); // 3 products + the shelves
  assert.equal(first.report.writes.products, 3);
  assert.equal(first.report.writes.categories, true);

  const second = buildPlan({
    seed: SEED,
    existingProducts: stagingAfter(first),
    existingCategories: first.actions.find((action) => action.type === 'categories').data,
    project: 'staging',
    now: 2_000,
  });

  // The report says nothing changed, and — the part that matters — the plan
  // contains nothing to send.
  assert.equal(second.report.counts.unchanged, 3);
  assert.equal(second.report.counts.created, 0);
  assert.equal(second.report.counts.updated, 0);
  assert.deepEqual(second.actions, []);
  assert.equal(second.report.writes.total, 0);
  assert.equal(second.report.writes.products, 0);
  assert.equal(second.report.writes.categories, false);
  assert.equal(second.report.writes.pins, false);
});

test('a third run is a no-op too, so the property is stable rather than lucky', () => {
  const first = buildPlan({ seed: SEED, project: 'staging', now: 1_000 });
  const categories = first.actions.find((action) => action.type === 'categories').data;
  const stored = stagingAfter(first);

  for (const now of [2_000, 3_000, 4_000]) {
    const again = buildPlan({
      seed: SEED,
      existingProducts: stored,
      existingCategories: categories,
      project: 'staging',
      now,
    });
    assert.deepEqual(again.actions, []);
  }
});

test('an unchanged document keeps the updated and createdAt it already had', () => {
  const first = buildPlan({ seed: SEED, project: 'staging', now: 1_000 });
  const stored = stagingAfter(first);
  for (const entry of stored) {
    assert.equal(entry.data.createdAt, 1_000);
    assert.equal(entry.data.updated, 1_000);
  }

  const second = buildPlan({
    seed: SEED,
    existingProducts: stored,
    existingCategories: first.actions.find((action) => action.type === 'categories').data,
    project: 'staging',
    now: 9_999,
  });
  // No action means nothing is sent, so the stored timestamps cannot move.
  assert.deepEqual(second.actions, []);
  assert.equal(stored[0].data.updated, 1_000);
});

test('a document that does change keeps the createdAt it was first given', () => {
  const first = buildPlan({ seed: SEED, project: 'staging', now: 1_000 });
  const stored = stagingAfter(first);

  const repriced = {
    ...SEED,
    products: SEED.products.map((product) =>
      product.seedModel === 'SIE1000' ? { ...product, dealer: 555 } : product,
    ),
  };
  const second = buildPlan({
    seed: repriced,
    existingProducts: stored,
    existingCategories: first.actions.find((action) => action.type === 'categories').data,
    project: 'staging',
    now: 8_000,
  });

  assert.equal(second.report.writes.products, 1);
  const write = second.actions.find((action) => action.type === 'product');
  assert.equal(write.id, productDocId('gate', 'SIE1000'));
  assert.equal(write.fields.dealer, 555);
  assert.equal(write.fields.createdAt, 1_000, 'createdAt must survive an update');
  assert.equal(write.fields.updated, 8_000);
});

test('the shelves are written once and never again', () => {
  const first = buildPlan({ seed: SEED, project: 'staging', now: 1 });
  const categories = first.actions.find((action) => action.type === 'categories').data;
  assert.ok(categories);

  const second = buildPlan({
    seed: SEED,
    existingProducts: stagingAfter(first),
    existingCategories: categories,
    project: 'staging',
    now: 2,
  });
  assert.equal(second.actions.filter((action) => action.type === 'categories').length, 0);
  assert.equal(second.report.writes.categories, false);
});

test('a shelf map that differs only in key order is not a change', () => {
  const first = buildPlan({ seed: SEED, project: 'staging', now: 1 });
  const categories = first.actions.find((action) => action.type === 'categories').data;
  const shuffled = { map: Object.fromEntries(Object.entries(categories.map).reverse()) };

  const second = buildPlan({
    seed: SEED,
    existingProducts: stagingAfter(first),
    existingCategories: shuffled,
    project: 'staging',
    now: 2,
  });
  assert.equal(second.report.writes.categories, false);
});

test('pins are written once, and rewritten only when the order changes', () => {
  const exported = { products: [], pins: { keys: ['gate|SIE1000', 'shutter|RS500'] } };
  const first = buildPlan({ seed: SEED, exported, project: 'staging', now: 1 });
  const pinWrite = first.actions.find((action) => action.type === 'pins');
  assert.deepEqual(pinWrite.data.keys, ['gate|SIE1000', 'shutter|RS500']);

  const settled = {
    seed: SEED,
    exported,
    existingProducts: stagingAfter(first),
    existingCategories: first.actions.find((action) => action.type === 'categories').data,
    project: 'staging',
  };
  const second = buildPlan({ ...settled, existingPins: pinWrite.data, now: 2 });
  assert.equal(second.report.writes.pins, false);
  assert.deepEqual(second.actions, []);

  // Someone reorders them in the app; the import would put its order back.
  const reordered = { keys: ['shutter|RS500', 'gate|SIE1000'] };
  const third = buildPlan({ ...settled, existingPins: reordered, now: 3 });
  assert.equal(third.report.writes.pins, true);
});

test('with no pins to import, the pins document is never touched', () => {
  const plan = buildPlan({ seed: SEED, project: 'staging', now: 1 });
  assert.equal(plan.actions.filter((action) => action.type === 'pins').length, 0);
  assert.equal(plan.report.writes.pins, false);
});

test('differs compares content and ignores what is only on the stored document', () => {
  const fields = { dealer: 100 };
  assert.equal(differs({ dealer: 100, updated: 999, createdAt: 999 }, fields), false);
  assert.equal(differs({ dealer: 101 }, fields), true);
  assert.equal(differs(undefined, fields), true);
  assert.equal(sameValue({ a: 1, b: 2 }, { b: 2, a: 1 }), true);
});

test('the counting helpers are callable, which is the regression itself', () => {
  const { documents, exportUpdated } = buildProducts(SEED, null, null, false);
  assert.equal(typeof countShelves, 'function');
  assert.equal(typeof countNulls, 'function');
  assert.deepEqual(countShelves(documents), { 'cat-sliding': 2, 'cat-shutter': 1 });
  assert.deepEqual(countNulls(documents), { allThree: 1, dealer: 1 });
  assert.equal(reconcileProducts(documents, [], exportUpdated, 1).created.length, 3);
});

test('a product carries no timestamp until reconciliation gives it one', () => {
  const { documents } = buildProducts(SEED, null, null, false);
  for (const fields of documents.values()) {
    assert.equal('createdAt' in fields, false);
    assert.equal('updated' in fields, false);
  }
});
