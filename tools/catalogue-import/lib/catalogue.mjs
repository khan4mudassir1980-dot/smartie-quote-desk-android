/**
 * Turning the PWA's price book into the seed catalogue.
 *
 * This was inline in `extract-v8c4.mjs` until N5.7, where a defect in it made
 * the case for testing it: `unit` was read from the **group** alone, so every
 * item carrying its own unit was given its group's instead. The group `glass`
 * is the clearest case — its group unit is `each`, and it contains items
 * priced per square foot. Those products reached the native app as `each`,
 * which is the unit the quotation builder would have priced them by.
 *
 * Nothing here reads a file, so it can be tested against a synthetic book and
 * the V8C4 source can stay out of the repository.
 */
import { flatten, shelfFor } from './pwa-source.mjs';
import { normalise, productDocId, productKey } from './keys.mjs';

/**
 * The unit a product is priced in.
 *
 * **Three levels, because V8C4 resolves it through three.** `label()`
 * (`index.html:2048`) reads `o.u || it.u || (g ? g.unit : "")` — a per-item
 * override, then the item's own unit, then the group's. Only the group's was
 * read here before, so an item's own unit was discarded.
 *
 * **Blank falls through, and that is deliberate.** V8C4 chains with `||`, not
 * `??`, so an item whose `u` is an empty string takes its group's unit. Using
 * `??` would let a blank shadow the group's real unit — the same defect this
 * function exists to fix, pointing the other way. The value is trimmed
 * because V8C4's own editor stores it trimmed (`index.html:6058`), so an
 * untrimmed one here would read as drift against the seed forever.
 */
export function unitFor(group, item) {
  const fromItem = String(item?.u ?? '').trim();
  if (fromItem) return fromItem;
  const fromGroup = String(group?.unit ?? '').trim();
  if (fromGroup) return fromGroup;
  return 'each';
}

/**
 * Every product in the book, as the seed catalogue stores it.
 *
 * Throws rather than exiting, so the caller decides how a failure is
 * reported and so this is reachable from a test.
 */
export function deriveProducts(source) {
  const products = [];
  const seenModels = new Map();
  for (const { group, item } of flatten(source)) {
    const key = productKey(group.id, item.m);
    const model = normalise(item.m);
    if (seenModels.has(model)) {
      throw new Error(
        `Two products share the model "${item.m}": ${seenModels.get(model)} and ${key}. ` +
          'The book is meant to hold each model once; resolve it in the PWA first.',
      );
    }
    seenModels.set(model, key);
    products.push({
      documentId: productDocId(group.id, item.m),
      key,
      group: group.id,
      seedModel: item.m,
      model: item.m,
      name: item.n ?? '',
      unit: unitFor(group, item),
      spec: item.spec ?? '',
      gst: item.gst ?? group.gst ?? 18,
      // A price the book does not give stays null and must never become zero.
      dealer: item.d ?? null,
      contractor: item.c ?? null,
      client: item.cl ?? null,
      kg: item.kg ?? null,
      categoryId: shelfFor(source, group, item),
      active: true,
      conflictResolved: !item.conflict,
      conflictNote: item.conflict ? JSON.stringify(item.conflict) : '',
      verifyNote: item.f ?? '',
    });
  }
  return { products, distinctModels: seenModels.size };
}
