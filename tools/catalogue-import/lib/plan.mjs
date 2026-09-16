/**
 * Deciding what the import would write, with no Firebase anywhere in sight.
 *
 * Two things live here. The merge — seed, production export, device overrides —
 * and the reconciliation that turns it into an explicit list of **write
 * actions**. The script executes that list and nothing else, so "a second run
 * changes nothing" is a property of the plan rather than a claim in a report:
 * an unchanged catalogue produces zero actions, so zero writes leave.
 *
 * Content and bookkeeping are kept apart on purpose. [buildProducts] returns
 * only what a product *is*; `createdAt`, `updated`, and whether to write at all
 * are decided in [reconcileProducts], against what staging already holds.
 *
 * Everything exported is a function declaration: the report path once threw a
 * ReferenceError that `node --check` could not see, because its helpers were
 * `const` arrows below the top-level dispatch.
 */
import { needsSanitising, productKey, splitProductKey } from './keys.mjs';

/** The PWA's "deliberately not set" sentinel. */
export const NULLP = '∅';

export const PRICE_FIELDS = ['dealer', 'contractor', 'client'];

/** Fields the export carries that describe the write, not the product. */
const BOOKKEEPING = new Set(['schemaVersion', 'createdAt', 'updated', 'by', 'byUid', 'serverAt']);

/** Key order must not decide whether two documents are the same. */
function stable(value) {
  if (value === null || typeof value !== 'object') return JSON.stringify(value ?? null);
  if (Array.isArray(value)) return `[${value.map(stable).join(',')}]`;
  const keys = Object.keys(value).sort();
  return `{${keys.map((key) => `${JSON.stringify(key)}:${stable(value[key])}`).join(',')}}`;
}

export function sameValue(left, right) {
  return stable(left) === stable(right);
}

/**
 * What each product *is*, with no timestamps.
 *
 * Winner per field, as audit M2 step 1 specifies: newest Firestore `updated`
 * beats the book, and a device override — which carries no timestamp — is
 * listed for a decision rather than applied, unless [applyDeviceOverrides].
 *
 * `updatedAt` per document is returned separately rather than stamped in, so a
 * document that has not changed can keep the timestamp it already has.
 */
export function buildProducts(seed, exported, device, applyDeviceOverrides) {
  const documents = new Map();
  const exportUpdated = new Map();
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
        if (sameValue(normalised, fields[field])) continue;
        overrides.push({ key: product.key, field, seed: fields[field], production: normalised });
        fields[field] = normalised;
      }
      fields.by = fromExport.data?.by ?? fields.by;
      fields.byUid = fromExport.data?.byUid ?? fields.byUid;
      if (fromExport.data?.updated != null) exportUpdated.set(product.documentId, fromExport.data.updated);
    }

    const override = deviceOverrides?.[product.group]?.[product.seedModel];
    if (override) {
      for (const [short, field] of [['d', 'dealer'], ['c', 'contractor'], ['cl', 'client']]) {
        if (!(short in override)) continue;
        const value = override[short] === NULLP ? null : override[short];
        if (sameValue(value, fields[field])) continue;
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

  return { documents, exportUpdated, overrides, deviceNotes };
}

/**
 * The shelves document's map. Defaults are added where missing; an existing
 * shelf is never renamed or reordered (audit M2 step 2).
 */
export function buildCategoryMap(seed, exported, existing) {
  const map = { ...(existing?.map ?? {}), ...(exported?.categories?.map ?? {}) };
  for (const category of seed.categories) {
    if (!map[category.id]) map[category.id] = { ...category };
  }
  return map;
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

/**
 * Whether a stored document differs from what a product should be.
 *
 * Only the content fields are compared, because [fields] carries no timestamps;
 * anything else already on the document is left alone.
 */
export function differs(current, fields) {
  if (!current) return true;
  return Object.entries(fields).some(([field, value]) => !sameValue(current[field] ?? null, value ?? null));
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

/**
 * Turns the desired products into the writes that are actually needed.
 *
 * A document that already says the right thing produces no action at all, so
 * its `updated` and `createdAt` stay exactly as they were. A document that does
 * change keeps the `createdAt` it was first given.
 */
export function reconcileProducts(documents, existingProducts, exportUpdated, now) {
  const existingById = new Map(existingProducts.map((entry) => [entry.id, entry.data]));
  const created = [];
  const updated = [];
  const unchanged = [];
  const actions = [];

  for (const [id, fields] of documents) {
    const current = existingById.get(id);
    if (current && !differs(current, fields)) {
      unchanged.push(id);
      continue;
    }
    (current ? updated : created).push(id);
    actions.push({
      type: 'product',
      id,
      fields: {
        ...fields,
        // First seen now, or whenever this document was first written.
        createdAt: current?.createdAt ?? now,
        updated: exportUpdated.get(id) ?? now,
      },
    });
  }

  return { created, updated, unchanged, actions };
}

/**
 * The whole plan: what to write, what not to, and the report describing both.
 *
 * `actions` is the contract. An import executes exactly these and nothing else.
 */
export function buildPlan({
  seed,
  exported = null,
  device = null,
  existingProducts = [],
  existingCategories = null,
  existingPins = null,
  applyDeviceOverrides = false,
  mode = 'dry-run',
  project,
  backupPath = null,
  now = Date.now(),
}) {
  const { documents, exportUpdated, overrides, deviceNotes } = buildProducts(
    seed,
    exported,
    device,
    applyDeviceOverrides,
  );
  const { created, updated, unchanged, actions } = reconcileProducts(documents, existingProducts, exportUpdated, now);

  const categoryMap = buildCategoryMap(seed, exported, existingCategories);
  const categoriesChanged = !sameValue(existingCategories?.map ?? null, categoryMap);
  if (categoriesChanged) {
    actions.push({
      type: 'categories',
      data: { map: categoryMap, updated: now, by: 'Catalogue import' },
    });
  }

  const pins = buildPins(exported, documents);
  const pinsChanged = pins.keys.length > 0 && !sameValue(existingPins?.keys ?? null, pins.keys);
  if (pinsChanged) {
    actions.push({
      type: 'pins',
      data: { keys: pins.keys, updatedAt: now, updatedBy: 'Catalogue import' },
    });
  }

  const report = {
    generatedAt: new Date(now).toISOString(),
    project,
    mode,
    seed: { products: seed.products.length, sourceSha256: seed.sourceSha256 ?? null },
    export: exported ? { products: exported.products.length, sha256: exported.contentSha256 ?? null } : null,
    deviceBackup: device ? 'supplied' : 'NOT SUPPLIED',
    deviceOverridesApplied: Boolean(applyDeviceOverrides),
    stagingBefore: { products: existingProducts.length },
    counts: { created: created.length, updated: updated.length, unchanged: unchanged.length },
    // What will actually be sent. Zero here means the run is a no-op.
    writes: {
      products: actions.filter((action) => action.type === 'product').length,
      categories: categoriesChanged,
      pins: pinsChanged,
      total: actions.length,
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

  return { documents, actions, categoryMap, pins, report };
}
