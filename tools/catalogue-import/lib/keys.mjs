/**
 * Product identity, exactly as the PWA computes it.
 *
 * The native `Keys.productDocId` replaces only `/`; the PWA's `docId`
 * (index.html:5723) also replaces `. # $ [ ]`, and thirteen of the 403 models
 * need that. The PWA's rule is the one on disk, so it is the one used here.
 */

/** `index.html:5682` — the logical key stored as `id` and `key`. */
export const productKey = (group, seedModel) => `${group}|${seedModel}`;

/** `index.html:5723` — the canonical schema-v2 document id. */
export const productDocId = (group, seedModel) =>
  `${group}__${String(seedModel).replace(/[/.#$[\]]/g, '_')}`;

/** `index.html:5681` — letters and digits only, for duplicate detection. */
export const normalise = (value) =>
  String(value ?? '').toLowerCase().replace(/[^a-z0-9]/g, '');

/** Splits either document-id scheme back into group and model. */
export function splitProductKey(raw) {
  const pipe = String(raw).indexOf('|');
  if (pipe > 0) return [raw.slice(0, pipe), raw.slice(pipe + 1)];
  const underscores = String(raw).indexOf('__');
  if (underscores > 0) return [raw.slice(0, underscores), raw.slice(underscores + 2)];
  return null;
}

/** Models whose document id needs a character replaced; expected to be 13. */
export const needsSanitising = (seedModel) => /[/.#$[\]]/.test(String(seedModel));
