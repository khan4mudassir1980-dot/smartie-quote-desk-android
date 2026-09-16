/**
 * Reading the approved V8C4 `index.html` as data.
 *
 * The price book is embedded in the PWA, so the authoritative 403 products and
 * the twelve shelves are derived from the file itself rather than retyped.
 * Each declaration is sliced out by bracket balance and evaluated in a bare
 * VM context with no globals, so nothing in the page can run.
 */
import { readFileSync } from 'node:fs';
import { createContext, runInContext } from 'node:vm';
import { createHash } from 'node:crypto';

/** The end of a `const NAME = {…}` or `function name(…){…}` declaration. */
function endOfBlock(lines, startIndex) {
  let depth = 0;
  let opened = false;
  for (let i = startIndex; i < lines.length; i += 1) {
    for (const character of lines[i]) {
      if (character === '{' || character === '[') {
        depth += 1;
        opened = true;
      } else if (character === '}' || character === ']') {
        depth -= 1;
      }
    }
    if (opened && depth === 0) return i;
  }
  throw new Error('Unbalanced block in the PWA source');
}

function slice(lines, pattern, label) {
  const start = lines.findIndex((line) => pattern.test(line));
  if (start < 0) throw new Error(`The PWA source has no ${label}`);
  return lines.slice(start, endOfBlock(lines, start) + 1).join('\n');
}

/**
 * Returns the catalogue and the shelving rules exactly as the PWA holds them,
 * with `mergeCatalogue` (index.html:5675) already applied.
 */
export function readPwaSource(indexPath) {
  const source = readFileSync(indexPath, 'utf8');
  const sha256 = createHash('sha256').update(source).digest('hex');
  const lines = source.split('\n');

  const declarations = [
    slice(lines, /^const BOOK\s*=/, 'BOOK'),
    slice(lines, /^const CAT2026\s*=/, 'CAT2026'),
    slice(lines, /^const SLIDING2026_NEW\s*=/, 'SLIDING2026_NEW'),
    slice(lines, /^const DEFAULT_CATEGORIES\s*=/, 'DEFAULT_CATEGORIES'),
    slice(lines, /^const CATEGORY_ALIAS\s*=/, 'CATEGORY_ALIAS'),
    slice(lines, /^const GROUP_CATEGORY\s*=/, 'GROUP_CATEGORY'),
    slice(lines, /^function classifyProduct\s*\(/, 'classifyProduct'),
  ].join('\n');

  // mergeCatalogue, index.html:5675-5679, verbatim.
  const merge = `
    (function mergeCatalogue(){
      const gate = BOOK.groups.find(g => g.id === "gate");
      if (gate) SLIDING2026_NEW.forEach(it => {
        if (!gate.items.some(x => x.m === it.m)) gate.items.push(it);
      });
      CAT2026.forEach(g => {
        if (!BOOK.groups.some(x => x.id === g.id)) BOOK.groups.push(g);
      });
    })();
    ({ BOOK, DEFAULT_CATEGORIES, CATEGORY_ALIAS, GROUP_CATEGORY, classifyProduct });
  `;

  const context = createContext(Object.create(null));
  const extracted = runInContext(`${declarations}\n${merge}`, context, {
    timeout: 10_000,
  });

  return { ...extracted, sha256 };
}

/**
 * Where a product sits, by the PWA's own cascade (`categoryOf` 7431-7438) with
 * no device state: the classifier, aliased, then the group's shelf, then Other.
 */
export function shelfFor(source, group, item) {
  const alias = (id) => source.CATEGORY_ALIAS[id] || id;
  const known = new Set(source.DEFAULT_CATEGORIES.map((category) => category.id));
  const guessed = alias(source.classifyProduct(group.id, item.m, item.n, item.spec));
  if (guessed && known.has(guessed)) return guessed;
  const byGroup = alias(source.GROUP_CATEGORY[group.id]);
  return byGroup && known.has(byGroup) ? byGroup : 'cat-other';
}

/** Every item in the book, flattened, with its group. */
export function flatten(source) {
  const products = [];
  for (const group of source.BOOK.groups) {
    for (const item of group.items) products.push({ group, item });
  }
  return products;
}

export const sha256Of = (value) =>
  createHash('sha256').update(typeof value === 'string' ? value : JSON.stringify(value)).digest('hex');
