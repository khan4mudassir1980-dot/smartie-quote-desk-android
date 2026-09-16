#!/usr/bin/env node
/**
 * Step 2: the read-only M0 export of the three collections the catalogue needs.
 *
 * READ ONLY. This file contains no `set`, `update`, `delete`, `add`, `commit`
 * or `batch` call, and the service account it is meant to run under holds
 * `roles/datastore.viewer` only, so it could not write even if it did.
 *
 *   GOOGLE_APPLICATION_CREDENTIALS=<production viewer key, outside this repo> \
 *   node export-production.mjs --project smartie-quote-desk
 */
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { OUT_DIR, fail, preflight } from './lib/preflight.mjs';
import { PRODUCTION_PROJECT, connect, readCollection, readDocument } from './lib/firestore.mjs';
import { sha256Of } from './lib/pwa-source.mjs';

function argument(name) {
  const index = process.argv.indexOf(name);
  return index > 0 ? process.argv[index + 1] : undefined;
}

const expected = argument('--project');
if (!expected) {
  fail(
    'Name the project you mean to read, so a mis-set credential cannot read the wrong one:\n' +
      `  node export-production.mjs --project ${PRODUCTION_PROJECT}`,
  );
}

const outputPath = join(OUT_DIR, 'm0-export.json');
preflight([outputPath]);

const { db } = await connect(expected);
console.log(`Reading ${expected}. Nothing will be written to it.\n`);

const products = await readCollection(db, 'products');
const categories = await readDocument(db, 'teamSettings', 'categories');
const pins = await readDocument(db, 'teamSettings', 'productPins');

const payload = {
  exportedAt: new Date().toISOString(),
  project: expected,
  products,
  categories,
  pins,
};
payload.contentSha256 = sha256Of({ products, categories, pins });

writeFileSync(outputPath, `${JSON.stringify(payload, null, 2)}\n`);

const pinKeys = Array.isArray(pins?.keys) ? pins.keys : [];
const shelves = categories?.map ? Object.keys(categories.map).length : 0;

console.log(`  products                 ${products.length}`);
console.log(`  category overrides       ${shelves}`);
console.log(`  pinned products          ${pinKeys.length}`);
if (pinKeys.length > 0) console.log(`  pin order                ${pinKeys.join(' → ')}`);
console.log(`\n  sha256  ${payload.contentSha256}`);
console.log(`\nWritten to ${outputPath}`);
console.log('Production was read and not modified.');
