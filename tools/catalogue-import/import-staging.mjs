#!/usr/bin/env node
/**
 * Steps 3 and 4: import the catalogue into `smartie-quote-desk-staging`, and
 * restore it again.
 *
 * Dry run by default. Refuses every project but staging. Backs staging up in
 * full before it writes anything, and `--restore` puts it back exactly,
 * including deleting the documents this import created.
 *
 *   node import-staging.mjs --seed out/seed-catalogue.json \
 *     [--export out/m0-export.json] [--device out/device-backup.json] \
 *     [--apply-device-overrides] [--apply] [--accept-existing]
 *
 *   node import-staging.mjs --restore out/staging-backup-<stamp>.json [--apply]
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { OUT_DIR, fail, preflight } from './lib/preflight.mjs';
import { STAGING_PROJECT, connect, inBatches, readCollection, readDocument } from './lib/firestore.mjs';
import { sha256Of } from './lib/pwa-source.mjs';
import { productKey, splitProductKey } from './lib/keys.mjs';

const NULLP = '∅'; // the PWA's "deliberately not set"
const PRICE_FIELDS = ['dealer', 'contractor', 'client'];

const argv = process.argv.slice(2);
const flag = (name) => argv.includes(name);
const option = (name) => {
  const index = argv.indexOf(name);
  return index >= 0 ? argv[index + 1] : undefined;
};
const readJson = (path) => JSON.parse(readFileSync(path, 'utf8'));
const stamp = () => new Date().toISOString().replace(/[:.]/g, '-');

const apply = flag('--apply');

if (flag('--restore')) {
  await restore(option('--restore'), apply);
} else {
  await importCatalogue();
}

// --- import ---------------------------------------------------------------

async function importCatalogue() {
  const seedPath = option('--seed');
  if (!seedPath) fail('Usage: node import-staging.mjs --seed out/seed-catalogue.json [--apply]');

  const backupPath = join(OUT_DIR, `staging-backup-${stamp()}.json`);
  const reportPath = join(OUT_DIR, `migration-report-${stamp()}.json`);
  preflight([backupPath, reportPath]);

  const seed = readJson(seedPath);
  const exported = option('--export') ? readJson(option('--export')) : null;
  const device = option('--device') ? readJson(option('--device')) : null;

  if (!exported) {
    console.log('No production export given: prices come from the V8C4 book alone,');
    console.log('and there are no pins to import.\n');
  }
  if (!device) {
    console.log('No Owner device backup given: a price edited on a device and never');
    console.log('pushed to Firestore cannot be seen, so full price parity with the PWA');
    console.log('cannot be claimed from this run.\n');
  }

  const { db } = await connect(STAGING_PROJECT);
  console.log(`${apply ? 'Importing into' : 'Dry run against'} ${STAGING_PROJECT}.\n`);

  const existingProducts = await readCollection(db, 'products');
  const existingCategories = await readDocument(db, 'teamSettings', 'categories');
  const existingPins = await readDocument(db, 'teamSettings', 'productPins');

  const backup = {
    takenAt: new Date().toISOString(),
    project: STAGING_PROJECT,
    products: existingProducts,
    categories: existingCategories,
    pins: existingPins,
    manifest: { productIds: existingProducts.map((entry) => entry.id) },
  };
  backup.contentSha256 = sha256Of(backup.products.concat([backup.categories, backup.pins]));
  writeFileSync(backupPath, `${JSON.stringify(backup, null, 2)}\n`);
  console.log(`Staging backed up before any write: ${backupPath}`);
  console.log(`  products ${existingProducts.length}, categories ${existingCategories ? 'present' : 'absent'}, pins ${existingPins ? 'present' : 'absent'}\n`);

  const { documents, overrides, deviceNotes } = buildProducts(seed, exported, device, flag('--apply-device-overrides'));
  const categories = buildCategories(seed, exported, existingCategories);
  const { keys: pinKeys, dropped: droppedPins } = buildPins(exported, documents);

  const existingById = new Map(existingProducts.map((entry) => [entry.id, entry.data]));
  const created = [];
  const updated = [];
  const unchanged = [];
  for (const [id, fields] of documents) {
    const current = existingById.get(id);
    if (!current) created.push(id);
    else if (differs(current, fields)) updated.push(id);
    else unchanged.push(id);
  }

  const report = {
    generatedAt: new Date().toISOString(),
    project: STAGING_PROJECT,
    mode: apply ? 'apply' : 'dry-run',
    seed: { products: seed.products.length, sourceSha256: seed.sourceSha256 },
    export: exported ? { products: exported.products.length, sha256: exported.contentSha256 } : null,
    deviceBackup: device ? 'supplied' : 'NOT SUPPLIED',
    deviceOverridesApplied: flag('--apply-device-overrides'),
    stagingBefore: { products: existingProducts.length },
    counts: { created: created.length, updated: updated.length, unchanged: unchanged.length },
    productionBeatSeed: overrides,
    deviceOverrides: deviceNotes,
    sanitisedIds: [...documents.keys()].filter((id) => id.includes('_') && !id.includes('__')),
    shelfCounts: countShelves(documents),
    nullPrices: countNulls(documents),
    pins: { keys: pinKeys, dropped: droppedPins },
    backup: backupPath,
  };
  writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`);

  printReport(report, seed);

  if (!apply) {
    console.log(`\nDry run. Nothing was written. Report: ${reportPath}`);
    console.log('Re-run with --apply once the report reads right.');
    return;
  }

  if (existingProducts.length > 0 && !flag('--accept-existing')) {
    fail(
      `Staging already holds ${existingProducts.length} products.\n` +
        'Nothing was written. Review the report, then re-run with --apply --accept-existing\n' +
        'if you mean to write over them. The backup above restores this state.',
    );
  }

  console.log('\nWriting products');
  await inBatches(db, [...documents.entries()], (batch, [id, fields]) => {
    batch.set(db.collection('products').doc(id), fields, { merge: true });
  });

  await db.collection('teamSettings').doc('categories').set(categories, { merge: true });
  console.log('  categories written');

  if (pinKeys.length > 0) {
    await db.collection('teamSettings').doc('productPins').set(
      { keys: pinKeys, updatedAt: Date.now(), updatedBy: 'Catalogue import' },
      { merge: true },
    );
    console.log(`  ${pinKeys.length} pins written, in the exported order`);
  }

  const after = await readCollection(db, 'products');
  console.log(`\nStaging now holds ${after.length} products.`);
  if (after.length !== documents.size) {
    fail(`Expected ${documents.size} products after the import, found ${after.length}.`);
  }
  console.log(`Report: ${reportPath}`);
  console.log(`Backup, for --restore: ${backupPath}`);
}

// --- building the documents ----------------------------------------------

function buildProducts(seed, exported, device, applyDeviceOverrides) {
  const documents = new Map();
  const overrides = [];
  const deviceNotes = [];

  const exportedByKey = new Map();
  for (const entry of exported?.products ?? []) {
    const raw = entry.data?.id || entry.data?.key || entry.id;
    const split = splitProductKey(raw);
    const key = split ? productKey(split[0], split[1]) : raw;
    const previous = exportedByKey.get(key);
    // Newest `updated` wins between the two id schemes (audit M2 step 1).
    if (!previous || (entry.data?.updated ?? 0) > (previous.data?.updated ?? 0)) {
      exportedByKey.set(key, entry);
    }
  }

  const deviceOverrides = device?.ov ?? device?.state?.ov ?? {};
  const now = Date.now();

  for (const product of seed.products) {
    const fields = {
      id: product.key,
      key: product.key,
      group: product.group,
      seedModel: product.seedModel,
      model: product.model,
      name: product.name,
      unit: product.unit,
      spec: product.spec,
      gst: product.gst,
      dealer: product.dealer,
      contractor: product.contractor,
      client: product.client,
      kg: product.kg,
      categoryId: product.categoryId,
      active: product.active,
      conflictResolved: product.conflictResolved,
      seeded: true,
      schemaVersion: 2,
      createdAt: now,
      updated: now,
      by: 'Catalogue import',
      byUid: 'import',
      legacyDocIds: [],
    };

    const fromExport = exportedByKey.get(product.key);
    if (fromExport) {
      fields.legacyDocIds = [fromExport.id];
      for (const [field, value] of Object.entries(fromExport.data ?? {})) {
        if (!(field in fields) || field === 'schemaVersion' || field === 'createdAt') continue;
        if (value === undefined) continue;
        const normalised = value === NULLP ? null : value;
        if (JSON.stringify(normalised) === JSON.stringify(fields[field])) continue;
        overrides.push({ key: product.key, field, seed: fields[field], production: normalised });
        fields[field] = normalised;
      }
      fields.updated = fromExport.data?.updated ?? now;
      fields.by = fromExport.data?.by ?? fields.by;
      fields.byUid = fromExport.data?.byUid ?? fields.byUid;
    }

    const override = deviceOverrides?.[product.group]?.[product.seedModel];
    if (override) {
      for (const [short, field] of [['d', 'dealer'], ['c', 'contractor'], ['cl', 'client']]) {
        if (!(short in override)) continue;
        const value = override[short] === NULLP ? null : override[short];
        if (JSON.stringify(value) === JSON.stringify(fields[field])) continue;
        deviceNotes.push({
          key: product.key,
          field,
          current: fields[field],
          device: value,
          applied: applyDeviceOverrides,
        });
        if (applyDeviceOverrides) fields[field] = value;
      }
      if (override.x === 1 || override.x === true) {
        deviceNotes.push({ key: product.key, field: 'active', current: true, device: false, applied: applyDeviceOverrides });
        if (applyDeviceOverrides) fields.active = false;
      }
    }

    documents.set(product.documentId, fields);
  }

  return { documents, overrides, deviceNotes };
}

function buildCategories(seed, exported, existing) {
  // Defaults are added where missing; an existing shelf is never renamed or
  // reordered (audit M2 step 2).
  const map = { ...(existing?.map ?? {}), ...(exported?.categories?.map ?? {}) };
  for (const category of seed.categories) {
    if (!map[category.id]) map[category.id] = { ...category };
  }
  return { map, updated: Date.now(), by: 'Catalogue import' };
}

function buildPins(exported, documents) {
  const wanted = Array.isArray(exported?.pins?.keys) ? exported.pins.keys : [];
  const live = new Set([...documents.values()].map((fields) => fields.key));
  const keys = [];
  const dropped = [];
  for (const key of wanted) {
    if (keys.length >= 15) {
      dropped.push({ key, why: 'beyond the fifteen the rules allow' });
    } else if (live.has(key)) {
      keys.push(key);
    } else {
      dropped.push({ key, why: 'no product with that key' });
    }
  }
  return { keys, dropped };
}

function differs(current, fields) {
  return Object.entries(fields).some(([field, value]) => {
    if (field === 'updated' || field === 'createdAt') return false;
    return JSON.stringify(current[field] ?? null) !== JSON.stringify(value ?? null);
  });
}

const countShelves = (documents) => {
  const tally = {};
  for (const fields of documents.values()) tally[fields.categoryId] = (tally[fields.categoryId] ?? 0) + 1;
  return tally;
};

const countNulls = (documents) => {
  const values = [...documents.values()];
  return {
    allThree: values.filter((f) => PRICE_FIELDS.every((field) => f[field] === null)).length,
    dealer: values.filter((f) => f.dealer === null).length,
  };
};

function printReport(report, seed) {
  console.log('Migration report');
  console.log(`  seed products            ${report.seed.products}`);
  console.log(`  production export        ${report.export ? report.export.products : 'not supplied'}`);
  console.log(`  owner device backup      ${report.deviceBackup}`);
  console.log(`  created / updated / unchanged   ${report.counts.created} / ${report.counts.updated} / ${report.counts.unchanged}`);
  console.log(`  no price at any tier     ${report.nullPrices.allThree}`);
  console.log(`  no dealer price          ${report.nullPrices.dealer}`);
  console.log('\n  shelves');
  for (const category of seed.categories) {
    console.log(`      ${category.id.padEnd(14)} ${String(report.shelfCounts[category.id] ?? 0).padStart(3)}`);
  }
  if (report.productionBeatSeed.length > 0) {
    console.log(`\n  production beat the book in ${report.productionBeatSeed.length} places`);
    for (const change of report.productionBeatSeed.slice(0, 20)) {
      console.log(`      ${change.key} ${change.field}: ${change.seed} -> ${change.production}`);
    }
    if (report.productionBeatSeed.length > 20) console.log('      … the rest are in the report file');
  }
  if (report.deviceOverrides.length > 0) {
    const verb = report.deviceOverridesApplied ? 'applied' : 'NOT applied';
    console.log(`\n  ${report.deviceOverrides.length} device overrides, ${verb}`);
    for (const change of report.deviceOverrides.slice(0, 20)) {
      console.log(`      ${change.key} ${change.field}: ${change.current} -> ${change.device}`);
    }
    if (!report.deviceOverridesApplied) {
      console.log('      These are prices someone changed on a device and never pushed.');
      console.log('      Review them, then re-run with --apply-device-overrides to take them.');
    }
  }
  if (report.pins.dropped.length > 0) {
    console.log(`\n  pins dropped: ${report.pins.dropped.map((p) => `${p.key} (${p.why})`).join(', ')}`);
  }
}

// --- restore --------------------------------------------------------------

async function restore(backupPath, applyRestore) {
  if (!backupPath) fail('Usage: node import-staging.mjs --restore out/staging-backup-<stamp>.json [--apply]');
  preflight([]);
  const backup = readJson(backupPath);
  if (backup.project !== STAGING_PROJECT) {
    fail(`That backup is from ${backup.project}, not ${STAGING_PROJECT}. Nothing was done.`);
  }

  const { db } = await connect(STAGING_PROJECT);
  console.log(`${applyRestore ? 'Restoring' : 'Restore dry run against'} ${STAGING_PROJECT}`);
  console.log(`  from ${backupPath}, taken ${backup.takenAt}\n`);

  const current = await readCollection(db, 'products');
  const keep = new Map(backup.products.map((entry) => [entry.id, entry.data]));
  const toDelete = current.filter((entry) => !keep.has(entry.id));
  const toRestore = backup.products;

  console.log(`  delete ${toDelete.length} product documents the import created`);
  console.log(`  restore ${toRestore.length} product documents exactly as they were`);
  console.log(`  categories: ${backup.categories ? 'restore previous content' : 'DELETE (there was none)'}`);
  console.log(`  pins: ${backup.pins ? 'restore previous content' : 'DELETE (there was none)'}`);

  if (!applyRestore) {
    console.log('\nDry run. Nothing was changed. Re-run with --apply to restore.');
    return;
  }

  await inBatches(db, toDelete, (batch, entry) => {
    batch.delete(db.collection('products').doc(entry.id));
  });
  // set without merge, so a field the import added is removed rather than left.
  await inBatches(db, toRestore, (batch, entry) => {
    batch.set(db.collection('products').doc(entry.id), entry.data);
  });

  const categories = db.collection('teamSettings').doc('categories');
  if (backup.categories) await categories.set(backup.categories);
  else await categories.delete();

  const pins = db.collection('teamSettings').doc('productPins');
  if (backup.pins) await pins.set(backup.pins);
  else await pins.delete();

  const after = await readCollection(db, 'products');
  const categoriesAfter = await readDocument(db, 'teamSettings', 'categories');
  const pinsAfter = await readDocument(db, 'teamSettings', 'productPins');
  const matches =
    after.length === backup.products.length &&
    Boolean(categoriesAfter) === Boolean(backup.categories) &&
    Boolean(pinsAfter) === Boolean(backup.pins);

  console.log(`\nStaging now holds ${after.length} products.`);
  if (!matches) fail('Staging does not match the backup after restoring. Do not import again until this is understood.');
  console.log('Restored exactly to the backed-up state.');
}
