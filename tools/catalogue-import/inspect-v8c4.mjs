#!/usr/bin/env node
/**
 * Read-only inspection of the approved V8C4 `index.html`.
 *
 * Answers the four questions `docs/N3-plan.md` leaves open, by printing the
 * PWA's own source rather than guessing at its behaviour:
 *
 *   1. how a `/stock` and `/stockMoves` document id is built, for a logical
 *      key containing `/` (audit C6);
 *   2. whether a movement is written with a signed `delta` or an absolute
 *      `qty`, and which the history reads;
 *   3. the exact canonicalisation `docId` applies, and which catalogue models
 *      need it;
 *   4. whether changing only the reorder level writes a movement.
 *
 * This script **reads and prints. It never writes.** It contains no `write`,
 * `mkdir`, `rm`, `set`, `update`, `delete` or network call of any kind: the
 * only filesystem call in it is `readFileSync`, and the only output is stdout.
 * Nothing it prints needs committing, and the V8C4 source stays where it is.
 *
 * With `--purchase` it answers three more instead, for N4's partial-receipt
 * work — whether the PWA displays `rcvQty` while `received` is false, whether
 * it derives closure from `rcvQty`, and whether a receipt overwrites that
 * field or adds to it. That section prints **classifications and redacted
 * one-line snippets only**: no prices, no customer details, no secrets, and
 * never a block of the page.
 *
 *   node inspect-v8c4.mjs --index <path to V8C4 index.html>
 *   node inspect-v8c4.mjs --index <path to V8C4 index.html> --purchase
 *
 * Add `--context N` for more surrounding lines (default 6).
 */
import { readFileSync } from 'node:fs';
import { classifyReceipt, VERDICT } from './lib/purchase-receipt.mjs';

const argv = process.argv.slice(2);
const option = (name) => {
  const index = argv.indexOf(name);
  return index >= 0 ? argv[index + 1] : undefined;
};

const indexPath = option('--index');
if (!indexPath) {
  console.error('Usage: node inspect-v8c4.mjs --index <path to V8C4 index.html>');
  process.exit(1);
}
const context = Number(option('--context') ?? 6);
const purchaseOnly = argv.includes('--purchase');

let source;
try {
  source = readFileSync(indexPath, 'utf8');
} catch (error) {
  console.error(`Cannot read ${indexPath}: ${error.message}`);
  process.exit(1);
}
const lines = source.split(/\r?\n/);

console.log(`V8C4 source: ${indexPath}`);
console.log(`${lines.length} lines, ${source.length} characters, read-only.\n`);

/**
 * The three `rcvQty` questions N4's partial receipt depends on.
 *
 * Classifications and redacted single lines, never a block of the page. An
 * `UNKNOWN` is a real answer here: it says the source does not settle the
 * question, which is worth far more than a confident guess written into a
 * data contract.
 */
function purchaseReceipt() {
  const answers = classifyReceipt(source);
  const questions = [
    ['Q5  Is `rcvQty` shown while `received` is false?', answers.display],
    ['Q6  Is closure derived from `rcvQty`, or from `received`/`status`?', answers.closure],
    ['Q7  Does a receipt overwrite `rcvQty`, or add to it?', answers.accumulation],
  ];

  for (const [title, answer] of questions) {
    console.log('='.repeat(72));
    console.log(title);
    console.log('='.repeat(72));
    console.log(`\n  VERDICT: ${answer.verdict}`);
    console.log(`  BECAUSE: ${answer.reason}`);
    if (answer.verdict === VERDICT.UNKNOWN) {
      console.log('  → Static reading does not settle this. Do not assume either way.');
    }
    if (answer.evidence.length === 0) {
      console.log('  (no lines to show)\n');
      continue;
    }
    console.log('\n  Evidence — line numbers, with literals and numbers removed:');
    for (const item of answer.evidence) {
      console.log(`    index.html:${String(item.line).padStart(6)}  ${item.snippet}`);
    }
    console.log();
  }

  console.log('Paste the three VERDICT lines back. Nothing else from the page');
  console.log('is needed, and nothing else has been printed.\n');
}

if (purchaseOnly) {
  purchaseReceipt();
  process.exit(0);
}

/** Every line matching [pattern], with [context] lines either side, de-duplicated. */
function show(title, pattern, { limit = 12 } = {}) {
  console.log('='.repeat(72));
  console.log(title);
  console.log('='.repeat(72));

  const hits = [];
  lines.forEach((line, index) => {
    if (pattern.test(line)) hits.push(index);
    pattern.lastIndex = 0;
  });

  if (hits.length === 0) {
    console.log('  (no match — tell Claude, the pattern is wrong, not the source)\n');
    return;
  }

  const printed = new Set();
  for (const hit of hits.slice(0, limit)) {
    const from = Math.max(0, hit - context);
    const to = Math.min(lines.length - 1, hit + context);
    if (printed.has(hit)) continue;
    console.log(`\n--- around line ${hit + 1} ---`);
    for (let n = from; n <= to; n += 1) {
      printed.add(n);
      const marker = n === hit ? '>' : ' ';
      console.log(`${marker} ${String(n + 1).padStart(6)}  ${lines[n]}`);
    }
  }
  if (hits.length > limit) {
    console.log(`\n  … ${hits.length - limit} further matches not shown.`);
  }
  console.log();
}

// 1. How a stock document is addressed.
show(
  'Q1  Stock document ids — how /stock and /stockMoves are addressed',
  /(collection\s*\(\s*['"`]stock)|(['"`]stockMoves['"`])|(doc\s*\(\s*['"`]stock)/,
  { limit: 14 },
);

// 2. What a movement carries, and what reads it.
show(
  'Q2  Movement shape — signed `delta` versus absolute `qty`',
  /\b(delta|qty)\s*[:=]|\.(delta|qty)\b/,
  { limit: 16 },
);

// 3. The canonicalisation applied to a document id.
show(
  'Q3  Canonicalisation — the exact character set `docId` replaces',
  /docId|replace\s*\(\s*\/\[[^\]]*\]/,
  { limit: 10 },
);

// 4. Whether a reorder-level-only change writes a movement.
show(
  'Q4  Reorder level — `min` writes, and whether they log a movement',
  /lastAction|['"`]min['"`]\s*[:,)]|reorder/i,
  { limit: 14 },
);

// The models that need the broader canonicalisation, straight from the seed
// the import already produced. Read-only, and skipped when it is not there.
const seedPath = new URL('./out/seed-catalogue.json', import.meta.url);
console.log('='.repeat(72));
console.log('Q3b  Catalogue models whose document id needs a character replaced');
console.log('='.repeat(72));
try {
  const seed = JSON.parse(readFileSync(seedPath, 'utf8'));
  const affected = (seed.products ?? []).filter((product) => /[/.#$[\]]/.test(String(product.seedModel)));
  console.log(`\n  ${affected.length} of ${(seed.products ?? []).length} models, from out/seed-catalogue.json:\n`);
  for (const product of affected) {
    const docId = `${product.group}__${String(product.seedModel).replace(/[/.#$[\]]/g, '_')}`;
    console.log(`    ${product.group}|${product.seedModel}  ->  ${docId}`);
  }
  console.log('\n  Paste this list back: it becomes the fixture rows that prove');
  console.log('  Android resolves the same ids the importer created.\n');
} catch {
  console.log('\n  out/seed-catalogue.json not found beside this script.');
  console.log('  Re-create it read-only with:');
  console.log('    node extract-v8c4.mjs --index <the same index.html>\n');
}

// The purchase questions run last in a full inspection, so `--purchase` is a
// way to skip everything above rather than the only way to reach them.
purchaseReceipt();
