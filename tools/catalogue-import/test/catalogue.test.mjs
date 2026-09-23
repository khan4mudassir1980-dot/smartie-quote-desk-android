/**
 * The unit a product is priced in, and the defect that made this file.
 *
 * Until N5.7 the extractor read `group.unit` alone, so an item carrying its
 * own unit was given its group's. The group `glass` is the case that shows
 * why it matters: its group unit is `each`, and it holds items priced per
 * square foot. Those reached the native app as `each` — the unit the
 * quotation builder would have priced them by.
 *
 * **Every book here is synthetic.** The V8C4 source is deliberately not in
 * this repository, and `out/` is gitignored, so nothing about the real
 * catalogue has to be true for these tests to mean something: they prove the
 * resolution order, which is the part that was wrong.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import { deriveProducts, unitFor } from '../lib/catalogue.mjs';

/**
 * The shape the derivation walks: `source.BOOK.groups[].items[]`, plus the
 * three shelf lookups `shelfFor` cascades through. The classifier returns
 * nothing, so every product here falls to `cat-other` — shelving is
 * `shelfFor`'s own concern and is not what these tests are about.
 */
const book = (groups) => ({
  BOOK: { rev: 1, effective: '2026-04-01', groups },
  DEFAULT_CATEGORIES: [{ id: 'cat-other', name: 'Other' }],
  CATEGORY_ALIAS: {},
  GROUP_CATEGORY: {},
  classifyProduct: () => '',
});

const item = (m, extra = {}) => ({ m, n: `${m} description`, d: 100, ...extra });

const unitsOf = (source) =>
  Object.fromEntries(deriveProducts(source).products.map((p) => [p.seedModel, p.unit]));

// --- the resolution order ------------------------------------------------------

test("an item's own unit beats its group's", () => {
  // The defect, and the case that matters most: `glass` is an `each` group
  // holding per-square-foot items.
  const source = book([
    { id: 'glass', unit: 'each', items: [item('GD-GLASS-60', { u: 'per sq ft' }), item('GD-PLAIN')] },
  ]);
  assert.deepEqual(unitsOf(source), {
    'GD-GLASS-60': 'per sq ft',
    'GD-PLAIN': 'each',
  });
});

test("an item with no unit of its own takes its group's", () => {
  const source = book([{ id: 'rail', unit: 'per m', items: [item('TRACK-6')] }]);
  assert.equal(unitsOf(source)['TRACK-6'], 'per m');
});

test('an item with no unit in a group with no unit is priced each', () => {
  const source = book([{ id: 'misc', items: [item('ODDMENT')] }]);
  assert.equal(unitsOf(source)['ODDMENT'], 'each');
});

test('a blank unit on an item falls through rather than shadowing the group', () => {
  // V8C4 chains with `||`, not `??`, so an empty string is "not set". Using
  // `??` here would let a blank hide the group's real unit — the same defect
  // this function fixes, pointing the other way.
  const source = book([
    { id: 'rail', unit: 'per m', items: [item('A', { u: '' }), item('B', { u: '   ' })] },
  ]);
  assert.deepEqual(unitsOf(source), { A: 'per m', B: 'per m' });
});

test('a unit is stored trimmed, as the PWA editor stores it', () => {
  // `index.html:6058` trims before saving, so an untrimmed value here would
  // read as drift against the seed on every verification, forever.
  const source = book([{ id: 'rail', unit: ' per m ', items: [item('A', { u: ' per sq ft ' })] }]);
  assert.deepEqual(unitsOf(source), { A: 'per sq ft' });
});

test('unitFor answers the three levels directly', () => {
  assert.equal(unitFor({ unit: 'each' }, { u: 'per sq ft' }), 'per sq ft');
  assert.equal(unitFor({ unit: 'per m' }, {}), 'per m');
  assert.equal(unitFor({}, {}), 'each');
  assert.equal(unitFor(undefined, undefined), 'each');
});

// --- what the rest of the derivation must keep doing ---------------------------

test('a price the book does not give stays null and never becomes zero', () => {
  const source = book([{ id: 'g', items: [{ m: 'NOPRICE' }] }]);
  const [product] = deriveProducts(source).products;
  assert.equal(product.dealer, null);
  assert.equal(product.contractor, null);
  assert.equal(product.client, null);
});

test('the document id and the logical key use the two different schemes', () => {
  // `pid` is pipe-separated and goes in the `id`/`key` fields; `docId` is
  // double-underscored with six characters replaced, and is the document.
  const source = book([{ id: 'hwWheel', items: [item('SIEBAL58H/V')] }]);
  const [product] = deriveProducts(source).products;
  assert.equal(product.key, 'hwWheel|SIEBAL58H/V');
  assert.equal(product.documentId, 'hwWheel__SIEBAL58H_V');
  assert.equal(product.seedModel, 'SIEBAL58H/V', 'the seed model keeps the slash');
});

test("an item's gst beats its group's, and 18 is the last resort", () => {
  const source = book([
    { id: 'g', gst: 12, items: [item('A', { gst: 5 }), item('B')] },
    { id: 'h', items: [item('C')] },
  ]);
  const byModel = Object.fromEntries(deriveProducts(source).products.map((p) => [p.seedModel, p.gst]));
  assert.deepEqual(byModel, { A: 5, B: 12, C: 18 });
});

test('two products sharing a model are refused by name', () => {
  // Case and punctuation are stripped before comparing, so `SIE-1000` and
  // `sie1000` are the same model to the PWA and must be to us.
  const source = book([{ id: 'g', items: [item('SIE-1000'), item('sie1000')] }]);
  assert.throws(() => deriveProducts(source), /Two products share the model "sie1000"/);
});
