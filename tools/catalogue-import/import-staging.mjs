#!/usr/bin/env node
/**
 * Steps 3 and 4: import the catalogue into `smartie-quote-desk-staging`, and
 * restore it again.
 *
 * Dry run by default. Refuses every project but staging. Backs staging up in
 * full before it writes anything, and `--restore` puts it back exactly,
 * including deleting the documents this import created.
 *
 * What to write is decided in `lib/plan.mjs`, which has no Firebase in it and
 * is covered by `test/plan.test.mjs`. This file only talks to Firestore.
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
import { buildPlan } from './lib/plan.mjs';

const argv = process.argv.slice(2);

function flag(name) {
  return argv.includes(name);
}

function option(name) {
  const index = argv.indexOf(name);
  return index >= 0 ? argv[index + 1] : undefined;
}

function readJson(path) {
  return JSON.parse(readFileSync(path, 'utf8'));
}

function stamp() {
  return new Date().toISOString().replace(/[:.]/g, '-');
}

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
  console.log(
    `  products ${existingProducts.length}, categories ${existingCategories ? 'present' : 'absent'}, ` +
      `pins ${existingPins ? 'present' : 'absent'}\n`,
  );

  const { documents, actions, report } = buildPlan({
    seed,
    exported,
    device,
    existingProducts,
    existingCategories,
    existingPins,
    applyDeviceOverrides: flag('--apply-device-overrides'),
    mode: apply ? 'apply' : 'dry-run',
    project: STAGING_PROJECT,
    backupPath,
  });

  writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`);
  printReport(report, seed);

  if (!apply) {
    console.log(`\nDry run. Nothing was written. Report: ${reportPath}`);
    if (report.writes.total === 0) {
      console.log('An --apply from here would write nothing: staging already matches.');
    } else {
      console.log('Re-run with --apply once the report reads right.');
    }
    return;
  }

  if (actions.length === 0 && existingProducts.length > 0) {
    // Nothing to do, so nothing to guard against.
    console.log('\nStaging already matches. No write was attempted.');
    console.log(`Report: ${reportPath}`);
    return;
  }

  if (existingProducts.length > 0 && !flag('--accept-existing')) {
    fail(
      `Staging already holds ${existingProducts.length} products.\n` +
        'Nothing was written. Review the report, then re-run with --apply --accept-existing\n' +
        'if you mean to write over them. The backup above restores this state.',
    );
  }

  // Only what the plan asked for. A run with nothing to do writes nothing at
  // all, which is what makes re-running safe rather than merely quiet.
  if (actions.length === 0) {
    console.log('\nNothing to write: staging already says all of this.');
  } else {
    const productWrites = actions.filter((action) => action.type === 'product');
    if (productWrites.length > 0) {
      console.log(`\nWriting ${productWrites.length} product documents`);
      await inBatches(db, productWrites, (batch, action) => {
        batch.set(db.collection('products').doc(action.id), action.fields, { merge: true });
      });
    } else {
      console.log('\nNo product document needs writing.');
    }

    const categoryWrite = actions.find((action) => action.type === 'categories');
    if (categoryWrite) {
      await db.collection('teamSettings').doc('categories').set(categoryWrite.data, { merge: true });
      console.log('  categories written');
    } else {
      console.log('  categories unchanged, not written');
    }

    const pinWrite = actions.find((action) => action.type === 'pins');
    if (pinWrite) {
      await db.collection('teamSettings').doc('productPins').set(pinWrite.data, { merge: true });
      console.log(`  ${pinWrite.data.keys.length} pins written, in the exported order`);
    } else {
      console.log('  pins unchanged, not written');
    }
  }

  const after = await readCollection(db, 'products');
  console.log(`\nStaging now holds ${after.length} products.`);
  if (after.length !== documents.size) {
    fail(`Expected ${documents.size} products after the import, found ${after.length}.`);
  }
  console.log(`Report: ${reportPath}`);
  console.log(`Backup, for --restore: ${backupPath}`);
}

function printReport(report, seed) {
  console.log('Migration report');
  console.log(`  seed products            ${report.seed.products}`);
  console.log(`  production export        ${report.export ? report.export.products : 'not supplied'}`);
  console.log(`  owner device backup      ${report.deviceBackup}`);
  console.log(
    `  created / updated / unchanged   ${report.counts.created} / ${report.counts.updated} / ${report.counts.unchanged}`,
  );
  console.log(`  no price at any tier     ${report.nullPrices.allThree}`);
  console.log(`  no dealer price          ${report.nullPrices.dealer}`);
  console.log(`  ids needing a character replaced  ${report.sanitisedIds.length}`);
  console.log(
    `  writes this run          ${report.writes.total} ` +
      `(products ${report.writes.products}, categories ${report.writes.categories ? 'yes' : 'no'}, ` +
      `pins ${report.writes.pins ? 'yes' : 'no'})`,
  );
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
    console.log(`\n  pins dropped: ${report.pins.dropped.map((pin) => `${pin.key} (${pin.why})`).join(', ')}`);
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
  if (!matches) {
    fail('Staging does not match the backup after restoring. Do not import again until this is understood.');
  }
  console.log('Restored exactly to the backed-up state.');
}
