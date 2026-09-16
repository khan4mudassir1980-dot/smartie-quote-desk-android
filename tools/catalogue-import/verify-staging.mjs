#!/usr/bin/env node
/**
 * Step 5: the evidence for T-P1 and for the part of T-P4 that can be checked.
 *
 * Read-only against staging. Prints the twenty-model table to compare against
 * the PWA by hand, and fails loudly on anything it can check itself.
 *
 *   node verify-staging.mjs --seed out/seed-catalogue.json [--export out/m0-export.json]
 *   node verify-staging.mjs --pins          # after reordering in the staging app
 */
import { readFileSync } from 'node:fs';
import { fail, preflight } from './lib/preflight.mjs';
import { STAGING_PROJECT, connect, readCollection, readDocument } from './lib/firestore.mjs';
import { needsSanitising, productDocId } from './lib/keys.mjs';

const argv = process.argv.slice(2);
const option = (name) => {
  const index = argv.indexOf(name);
  return index >= 0 ? argv[index + 1] : undefined;
};
const readJson = (path) => JSON.parse(readFileSync(path, 'utf8'));

preflight([]);
const { db } = await connect(STAGING_PROJECT);

if (argv.includes('--pins')) {
  await verifyPins();
} else {
  await verifyCatalogue();
}

async function verifyCatalogue() {
  const seedPath = option('--seed');
  if (!seedPath) fail('Usage: node verify-staging.mjs --seed out/seed-catalogue.json');
  const seed = readJson(seedPath);
  const exported = option('--export') ? readJson(option('--export')) : null;

  const products = await readCollection(db, 'products');
  const categories = await readDocument(db, 'teamSettings', 'categories');
  const pins = await readDocument(db, 'teamSettings', 'productPins');

  const failures = [];
  const check = (condition, message) => {
    console.log(`  ${condition ? 'ok  ' : 'FAIL'}  ${message}`);
    if (!condition) failures.push(message);
  };

  console.log('T-P1 — count parity\n');
  check(products.length === seed.products.length, `${seed.products.length} products in staging (found ${products.length})`);
  const keys = new Set(products.map((entry) => entry.data?.key));
  check(keys.size === seed.products.length, `${seed.products.length} distinct logical keys (found ${keys.size})`);
  check(
    products.every((entry) => entry.data?.schemaVersion === 2),
    'every product carries schemaVersion 2',
  );
  check(
    products.every((entry) => entry.id === productDocId(entry.data?.group, entry.data?.seedModel)),
    'every document id is the canonical group__seedModel',
  );
  const sanitised = seed.products.filter((product) => needsSanitising(product.seedModel));
  check(sanitised.length === 13, `the 13 ids that need a character replaced are present (found ${sanitised.length})`);

  console.log('\nT-P1 — every product matches the book, field by field\n');
  const byId = new Map(products.map((entry) => [entry.id, entry.data]));
  const mismatches = [];
  for (const product of seed.products) {
    const stored = byId.get(product.documentId);
    if (!stored) {
      mismatches.push(`${product.key}: missing from staging`);
      continue;
    }
    for (const field of ['group', 'seedModel', 'model', 'name', 'unit', 'spec', 'gst', 'categoryId', 'kg', 'dealer', 'contractor', 'client', 'active']) {
      const expected = expectedValue(product, field, exported);
      if (JSON.stringify(stored[field] ?? null) !== JSON.stringify(expected ?? null)) {
        mismatches.push(`${product.key} ${field}: expected ${JSON.stringify(expected)}, stored ${JSON.stringify(stored[field])}`);
      }
    }
  }
  check(mismatches.length === 0, `all ${seed.products.length} products match (${mismatches.length} mismatches)`);
  for (const mismatch of mismatches.slice(0, 20)) console.log(`          ${mismatch}`);

  console.log('\nT-P1 — a missing price is never zero\n');
  const zeroed = products.filter((entry) =>
    ['dealer', 'contractor', 'client'].some((field) => entry.data?.[field] === 0),
  );
  check(zeroed.length === 0, `no tier is stored as 0 (found ${zeroed.length})`);
  const allNull = products.filter((entry) =>
    ['dealer', 'contractor', 'client'].every((field) => entry.data?.[field] === null),
  ).length;
  const dealerNull = products.filter((entry) => entry.data?.dealer === null).length;
  console.log(`  note  ${allNull} products have no price at any tier, ${dealerNull} have no dealer price`);
  console.log('        (the book alone gives 22 and 74; a difference means production or a device changed one)');

  console.log('\nT-P1 — shelves\n');
  const shelves = categories?.map ? Object.keys(categories.map) : [];
  check(shelves.length >= 12, `the twelve default shelves are present (found ${shelves.length})`);
  const tally = {};
  for (const entry of products) tally[entry.data?.categoryId] = (tally[entry.data?.categoryId] ?? 0) + 1;
  for (const category of seed.categories) {
    console.log(`      ${category.id.padEnd(14)} ${String(tally[category.id] ?? 0).padStart(3)}`);
  }
  const unshelved = products.filter((entry) => !shelves.includes(entry.data?.categoryId));
  check(unshelved.length === 0, `every product sits on a real shelf (${unshelved.length} do not)`);

  console.log('\nT-P1 — twenty models, three tiers. Compare this table against the PWA.\n');
  printSpotCheck(seed, byId);

  console.log('\nT-P4 — the imported pin order\n');
  const stagingKeys = Array.isArray(pins?.keys) ? pins.keys : [];
  const exportedKeys = Array.isArray(exported?.pins?.keys) ? exported.pins.keys : [];
  if (exportedKeys.length === 0) {
    console.log('  skip  no pins in the production export, so there is no order to match');
  } else {
    const wanted = exportedKeys.filter((key) => keys.has(key)).slice(0, 15);
    check(
      JSON.stringify(stagingKeys) === JSON.stringify(wanted),
      `staging pin order equals the exported order (${stagingKeys.join(' → ') || 'empty'})`,
    );
  }
  check(stagingKeys.length <= 15, `no more than fifteen pins (found ${stagingKeys.length})`);
  check(stagingKeys.every((key) => keys.has(key)), 'every pin resolves to a product');

  console.log(
    '\n  The rest of T-P4 — that the order still reads the same in a PWA after a reorder —\n' +
      '  cannot be checked: the production PWA reads the production project and this data\n' +
      '  is in staging. It stays open until a staging PWA exists. Nothing here writes to\n' +
      '  production to close it.',
  );

  console.log(`\n${failures.length === 0 ? 'All automatic checks passed.' : `${failures.length} checks FAILED.`}`);
  if (failures.length > 0) process.exit(1);
}

function expectedValue(product, field, exported) {
  const fromSeed = product[field] ?? null;
  if (!exported) return fromSeed;
  const match = exported.products.find((entry) => (entry.data?.id || entry.data?.key) === product.key);
  if (!match || !(field in (match.data ?? {}))) return fromSeed;
  const value = match.data[field];
  return value === '∅' ? null : value;
}

/**
 * Twenty models chosen the same way every run: the biggest shelf's first two,
 * one from each of the other eleven, then the awkward ones — the two sanitised
 * ids, one product with no price at all, one with no dealer price, and one the
 * book flags as a conflict.
 */
function printSpotCheck(seed, byId) {
  const chosen = new Map();
  const add = (product, why) => {
    if (product && !chosen.has(product.key)) chosen.set(product.key, { product, why });
  };
  const byShelf = {};
  for (const product of seed.products) (byShelf[product.categoryId] ??= []).push(product);
  const biggest = Object.entries(byShelf).sort((a, b) => b[1].length - a[1].length)[0];
  add(biggest[1][0], biggest[0]);
  add(biggest[1][1], biggest[0]);
  for (const category of seed.categories) {
    if (category.id === biggest[0]) continue;
    add(byShelf[category.id]?.[0], category.id);
  }
  add(seed.products.find((p) => p.seedModel === 'SIE2.5MSMALL'), 'sanitised id');
  add(seed.products.find((p) => p.seedModel === 'SIEBAL58H/V'), 'sanitised id');
  add(seed.products.find((p) => p.dealer === null && p.contractor === null && p.client === null), 'no price at all');
  add(seed.products.find((p) => p.dealer === null && p.client !== null), 'no dealer price');
  add(seed.products.find((p) => p.conflictNote), 'flagged as a conflict');

  const money = (value) => (value === null || value === undefined ? 'not set' : `₹${Math.round(value).toLocaleString('en-IN')}`);
  console.log('  model                          dealer        contractor    client        why');
  for (const { product, why } of [...chosen.values()].slice(0, 20)) {
    const stored = byId.get(product.documentId) ?? {};
    console.log(
      `  ${String(product.model).slice(0, 28).padEnd(30)}` +
        `${money(stored.dealer).padEnd(14)}${money(stored.contractor).padEnd(14)}${money(stored.client).padEnd(14)}${why}`,
    );
  }
}

async function verifyPins() {
  const pins = await readDocument(db, 'teamSettings', 'productPins');
  const keys = Array.isArray(pins?.keys) ? pins.keys : [];
  console.log('T-P4 — what staging Firestore holds right now\n');
  console.log(`  ${keys.length} pins, in this order:`);
  keys.forEach((key, index) => console.log(`    ${index + 1}. ${key}`));
  console.log(`\n  updatedBy ${pins?.updatedBy ?? 'unknown'}, updatedAt ${pins?.updatedAt ?? 'unknown'}`);
  console.log('\n  Compare this with the Pinned Products shelf in the staging APK.');
  console.log('  Force-stop the app, reopen it, and confirm the order is still this one.');
}
