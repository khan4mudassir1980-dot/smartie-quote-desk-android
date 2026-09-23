/**
 * Product identity, exactly as the PWA computes it.
 *
 * `docId` (`index.html:5723`) replaces `/ . # $ [ ]`, and thirteen of the 403
 * models need it. The native `Keys.productDocId` replaces the same six —
 * it once replaced only `/`, which is right for `SIEBAL58H/V` and wrong for
 * every model carrying a `.`, and this comment described that older state
 * until N5.7. The two are now character for character the same function, and
 * they have to stay that way: a native write computing a different id lands
 * on a second document and re-creates the duplicate the canonical id exists
 * to prevent.
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
