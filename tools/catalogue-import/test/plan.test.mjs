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
  buildCategories,
  buildPins,
  buildPlan,
  buildProducts,
  classify,
  countNulls,
  countShelves,
  differs,
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
  const { documents, report } = buildPlan({ seed: SEED, exported, project: 'staging' });

  assert.equal(documents.get('gate__SIE1000').dealer, 175);
  assert.equal(documents.get('gate__SIE1000').updated, 1_700_000_000_000);
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
  const categories = buildCategories(SEED, null, existing);
  assert.equal(categories.map['cat-sliding'].name, 'Sliding gates (renamed)');
  assert.equal(categories.map['cat-sliding'].order, 5);
  assert.equal(categories.map['cat-other'].name, 'Other Products');
});

test('pins drop what no longer resolves, and never exceed the cap', () => {
  const { documents } = buildProducts(SEED, null, null, false, 1);
  const wanted = ['gate|SIE1000', 'gate|GONE', 'shutter|RS500'];
  const { keys, dropped } = buildPins({ pins: { keys: wanted } }, documents);
  assert.deepEqual(keys, ['gate|SIE1000', 'shutter|RS500']);
  assert.deepEqual(dropped, [{ key: 'gate|GONE', why: 'no product with that key' }]);

  const many = Array.from({ length: 20 }, (_, index) => `gate|M${index}`);
  const capped = buildPins({ pins: { keys: many } }, new Map());
  assert.equal(capped.keys.length, 0);
  assert.equal(capped.dropped.length, 20);
});

test('a second run reports everything as unchanged', () => {
  const first = buildPlan({ seed: SEED, project: 'staging', now: 1 });
  const asStored = [...first.documents.entries()].map(([id, data]) => ({ id, data }));

  const second = buildPlan({ seed: SEED, existingProducts: asStored, project: 'staging', now: 2 });
  assert.equal(second.report.counts.created, 0);
  assert.equal(second.report.counts.updated, 0);
  assert.equal(second.report.counts.unchanged, 3);
  assert.equal(second.report.stagingBefore.products, 3);
});

test('differs ignores the timestamps and notices everything else', () => {
  const fields = { dealer: 100, updated: 5, createdAt: 5 };
  assert.equal(differs({ dealer: 100, updated: 999, createdAt: 999 }, fields), false);
  assert.equal(differs({ dealer: 101, updated: 5, createdAt: 5 }, fields), true);
  assert.equal(differs(undefined, fields), true);
});

test('the counting helpers are callable, which is the regression itself', () => {
  const { documents } = buildProducts(SEED, null, null, false, 1);
  assert.equal(typeof countShelves, 'function');
  assert.equal(typeof countNulls, 'function');
  assert.deepEqual(countShelves(documents), { 'cat-sliding': 2, 'cat-shutter': 1 });
  assert.deepEqual(countNulls(documents), { allThree: 1, dealer: 1 });
  assert.deepEqual(classify(documents, []).created.length, 3);
});
