#!/usr/bin/env node
/**
 * Step 1 of the staging catalogue import: derive the authoritative catalogue
 * from the approved V8C4 PWA.
 *
 * No credentials, no network, nothing touched outside `out/`. The output is
 * the seed half of the import and can be reviewed before any key exists.
 *
 *   node extract-v8c4.mjs --index <path to V8C4 index.html>
 */
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { OUT_DIR, fail, preflight } from './lib/preflight.mjs';
import { readPwaSource, sha256Of } from './lib/pwa-source.mjs';
import { needsSanitising } from './lib/keys.mjs';
import { deriveProducts } from './lib/catalogue.mjs';

const EXPECTED_PRODUCTS = 403;
const EXPECTED_SHELVES = 12;

function argument(name) {
  const index = process.argv.indexOf(name);
  return index > 0 ? process.argv[index + 1] : undefined;
}

const indexPath = argument('--index');
if (!indexPath) fail('Usage: node extract-v8c4.mjs --index <path to V8C4 index.html>');

const outputPath = join(OUT_DIR, 'seed-catalogue.json');
preflight([outputPath]);

let source;
try {
  source = readPwaSource(indexPath);
} catch (error) {
  fail(`Could not read the catalogue out of ${indexPath}: ${error.message}`);
}

let products;
let distinctModels;
try {
  ({ products, distinctModels } = deriveProducts(source));
} catch (error) {
  fail(error.message);
}

const categories = source.DEFAULT_CATEGORIES.map((category) => ({ ...category }));

if (products.length !== EXPECTED_PRODUCTS) {
  fail(
    `Expected ${EXPECTED_PRODUCTS} products, derived ${products.length}. ` +
      'Either the index.html is not the approved V8C4 build, or the extraction is wrong. ' +
      'Nothing was written.',
  );
}
if (categories.length !== EXPECTED_SHELVES) {
  fail(`Expected ${EXPECTED_SHELVES} shelves, derived ${categories.length}. Nothing was written.`);
}

const shelfCounts = {};
for (const product of products) {
  shelfCounts[product.categoryId] = (shelfCounts[product.categoryId] ?? 0) + 1;
}

const noPrice = products.filter((p) => p.dealer === null && p.contractor === null && p.client === null);
const noDealer = products.filter((p) => p.dealer === null);
const sanitised = products.filter((p) => needsSanitising(p.seedModel));

const payload = {
  generatedAt: new Date().toISOString(),
  sourceSha256: source.sha256,
  priceBook: { rev: source.BOOK.rev, effective: source.BOOK.effective },
  groups: source.BOOK.groups.length,
  categories,
  products,
};
payload.contentSha256 = sha256Of({ categories, products });

writeFileSync(outputPath, `${JSON.stringify(payload, null, 2)}\n`);

console.log('Catalogue derived from the approved V8C4 source.\n');
console.log(`  source sha256      ${source.sha256}`);
console.log(`  price book         rev ${source.BOOK.rev}, effective ${source.BOOK.effective}`);
console.log(`  groups             ${source.BOOK.groups.length}`);
console.log(`  products           ${products.length}`);
console.log(`  distinct models    ${distinctModels}`);
console.log(`  no price at all    ${noPrice.length}   (audit section 6 says 22)`);
console.log(`  no dealer price    ${noDealer.length}   (audit section 6 says 74)`);
console.log(`  ids needing a character replaced  ${sanitised.length}`);
for (const product of sanitised) console.log(`      ${product.key}  ->  ${product.documentId}`);
// Printed because the unit was silently wrong until N5.7: it was read from
// the group alone, so every item carrying its own unit was given its group's.
// A tally here means the next extraction shows what it derived instead of
// anyone having to take it on trust.
const unitCounts = {};
for (const product of products) {
  unitCounts[product.unit] = (unitCounts[product.unit] ?? 0) + 1;
}
const ownUnit = products.filter((p) => p.unit !== 'each');
console.log(`  units other than "each"  ${ownUnit.length}`);
for (const [unit, count] of Object.entries(unitCounts).sort((a, b) => b[1] - a[1])) {
  console.log(`      ${unit.padEnd(14)} ${String(count).padStart(3)}`);
}

console.log('\n  shelves');
for (const category of categories) {
  console.log(`      ${category.id.padEnd(14)} ${String(shelfCounts[category.id] ?? 0).padStart(3)}  ${category.name}`);
}
console.log(`\nWritten to ${outputPath}`);
console.log('Nothing was read from or written to any Firebase project.');
