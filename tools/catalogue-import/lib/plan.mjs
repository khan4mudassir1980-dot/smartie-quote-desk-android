/**
 * Deciding what the import would write, with no Firebase anywhere in sight.
 *
 * This is the whole merge — seed, production export, device overrides — and the
 * migration report built from it. It lives here rather than inside
 * `import-staging.mjs` so a test can execute it: the report path once threw a
 * ReferenceError that `node --check` could not see, because the helpers it
 * called were `const` arrows below the top-level dispatch. Everything exported
 * here is a function declaration, so ordering cannot bite again.
 */
import { needsSanitising, productKey, splitProductKey } from './keys.mjs';

/** The PWA's "deliberately not set" sentinel. */
export const NULLP = '∅';

export const PRICE_FIELDS = ['dealer', 'contractor', 'client'];

/** Fields the export carries that describe the write, not the product. */
const BOOKKEEPING = new Set(['schemaVersion', 'createdAt', 'updated', 'by', 'byUid', 'serverAt']);

/**
 * The documents the import would write, keyed by canonical document id.
 *
 * Winner per field, as audit M2 step 1 specifies: newest Firestore `updated`
 * beats the book, and a device override — which carries no timestamp — is
 * listed for a decision rather than applied, unless [applyDeviceOverrides].
 */
export function buildProducts(seed, exported, device, applyDeviceOverrides, now = Date.now()) {
  const documents = new Map();
  const overrides = [];
  const deviceNotes = [];

  const exportedByKey = new Map();
  for (const entry of exported?.products ?? []) {
    const raw = entry.data?.id || entry.data?.key || entry.id;
    const split = splitProductKey(raw);
    const key = split ? productKey(split[0], split[1]) : raw;
    const previous = exportedByKey.get(key);
    if (!previous || (entry.data?.updated ?? 0) > (previous.data?.updated ?? 0)) {
      exportedByKey.set(key, entry);
    }
  }

  const deviceOverrides = device?.ov ?? device?.state?.ov ?? {};

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
        // Bookkeeping, not content: these are carried over below, and listing
        // them as "production beat the book" would bury the price changes.
        if (BOOKKEEPING.has(field)) continue;
        if (!(field in fields)) continue;
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
          applied: Boolean(applyDeviceOverrides),
        });
        if (applyDeviceOverrides) fields[field] = value;
      }
      if (override.x === 1 || override.x === true) {
        deviceNotes.push({
          key: product.key,
          field: 'active',
          current: true,
          device: false,
          applied: Boolean(applyDeviceOverrides),
        });
        if (applyDeviceOverrides) fields.active = false;
      }
    }

    documents.set(product.documentId, fields);
  }

  return { documents, overrides, deviceNotes };
}

/**
 * The shelves document. Defaults are added where missing; an existing shelf is
 * never renamed or reordered (audit M2 step 2).
 */
export function buildCategories(seed, exported, existing, now = Date.now()) {
  const map = { ...(existing?.map ?? {}), ...(exported?.categories?.map ?? {}) };
  for (const category of seed.categories) {
    if (!map[category.id]) map[category.id] = { ...category };
  }
  return { map, updated: now, by: 'Catalogue import' };
}

/** The pinned shelf: exported order, live keys only, never more than fifteen. */
export function buildPins(exported, documents, max = 15) {
  const wanted = Array.isArray(exported?.pins?.keys) ? exported.pins.keys : [];
  const live = new Set([...documents.values()].map((fields) => fields.key));
  const keys = [];
  const dropped = [];
  for (const key of wanted) {
    if (keys.length >= max) dropped.push({ key, why: 'beyond the fifteen the rules allow' });
    else if (live.has(key)) keys.push(key);
    else dropped.push({ key, why: 'no product with that key' });
  }
  return { keys, dropped };
}

/** Whether a stored document differs from what would be written. */
export function differs(current, fields) {
  return Object.entries(fields).some(([field, value]) => {
    if (field === 'updated' || field === 'createdAt') return false;
    return JSON.stringify(current?.[field] ?? null) !== JSON.stringify(value ?? null);
  });
}

export function countShelves(documents) {
  const tally = {};
  for (const fields of documents.values()) {
    tally[fields.categoryId] = (tally[fields.categoryId] ?? 0) + 1;
  }
  return tally;
}

export function countNulls(documents) {
  const values = [...documents.values()];
  return {
    allThree: values.filter((fields) => PRICE_FIELDS.every((field) => fields[field] === null)).length,
    dealer: values.filter((fields) => fields.dealer === null).length,
  };
}

/** Which documents are new, which change and which are already right. */
export function classify(documents, existingProducts) {
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
  return { created, updated, unchanged };
}

/**
 * The whole plan, and the report that describes it. This is the path that used
 * to throw; the test drives it end to end.
 */
export function buildPlan({
  seed,
  exported = null,
  device = null,
  existingProducts = [],
  existingCategories = null,
  applyDeviceOverrides = false,
  mode = 'dry-run',
  project,
  backupPath = null,
  now = Date.now(),
}) {
  const { documents, overrides, deviceNotes } = buildProducts(seed, exported, device, applyDeviceOverrides, now);
  const categories = buildCategories(seed, exported, existingCategories, now);
  const pins = buildPins(exported, documents);
  const counts = classify(documents, existingProducts);

  const report = {
    generatedAt: new Date(now).toISOString(),
    project,
    mode,
    seed: { products: seed.products.length, sourceSha256: seed.sourceSha256 ?? null },
    export: exported ? { products: exported.products.length, sha256: exported.contentSha256 ?? null } : null,
    deviceBackup: device ? 'supplied' : 'NOT SUPPLIED',
    deviceOverridesApplied: Boolean(applyDeviceOverrides),
    stagingBefore: { products: existingProducts.length },
    counts: {
      created: counts.created.length,
      updated: counts.updated.length,
      unchanged: counts.unchanged.length,
    },
    productionBeatSeed: overrides,
    deviceOverrides: deviceNotes,
    sanitisedIds: seed.products
      .filter((product) => needsSanitising(product.seedModel))
      .map((product) => ({ key: product.key, documentId: product.documentId })),
    shelfCounts: countShelves(documents),
    nullPrices: countNulls(documents),
    pins,
    backup: backupPath,
  };

  return { documents, categories, pins, report };
}
