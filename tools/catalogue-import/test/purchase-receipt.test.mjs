/**
 * The three `rcvQty` questions, against synthetic sources.
 *
 * The approved V8C4 `index.html` lives only on the Owner's machine, so the
 * classifier is proved here on small sources written to be each of the
 * answers it can give — including the two it must **refuse** to answer.
 *
 * Nothing here reads the PWA, and nothing here writes anything.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  classifyReceipt,
  codeOnly,
  redact,
  VERDICT,
} from '../lib/purchase-receipt.mjs';

// --- Q3, how a receipt updates the field --------------------------------

test('a receipt that replaces the field is called overwriting', () => {
  const source = `
    function markReceived(item, arrived) {
      db.collection('purchase').doc(item.id).update({
        status: 'Received',
        received: true,
        rcvQty: arrived,
        rcvAt: Date.now()
      });
    }
  `;
  const { accumulation } = classifyReceipt(source);
  assert.equal(accumulation.verdict, VERDICT.OVERWRITES);
  assert.match(accumulation.reason, /discard a running total/);
  assert.ok(accumulation.evidence.length >= 1, 'the write has to be pointed at');
});

test('a receipt that reads the field first is called accumulating', () => {
  const source = `
    function markReceived(item, arrived) {
      db.collection('purchase').doc(item.id).update({
        rcvQty: (item.rcvQty || 0) + arrived,
        updated: Date.now()
      });
    }
  `;
  const { accumulation } = classifyReceipt(source);
  assert.equal(accumulation.verdict, VERDICT.ACCUMULATES);
});

test('a compound assignment is accumulating too', () => {
  const source = 'function take(it, n){ it.rcvQty += n; save(it); }';
  assert.equal(classifyReceipt(source).accumulation.verdict, VERDICT.ACCUMULATES);
});

test('a conditional write is not classified either way', () => {
  // A ternary is exactly where a reader's eye goes wrong, and being wrong
  // here would silently reset somebody's running total.
  const source = 'update({ rcvQty: partial ? (it.rcvQty || 0) + n : n });';
  const { accumulation } = classifyReceipt(source);
  assert.equal(accumulation.verdict, VERDICT.UNKNOWN);
  assert.match(accumulation.reason, /ambiguous/);
});

test('a source that never writes the field says so rather than guessing', () => {
  const source = 'const shown = it.rcvQty > 0 ? it.rcvQty : 0;';
  const { accumulation } = classifyReceipt(source);
  assert.equal(accumulation.verdict, VERDICT.UNKNOWN);
  assert.match(accumulation.reason, /never assigns rcvQty/);
});

// --- Q2, where closure comes from ---------------------------------------

test('closure decided by received or status is reported as such', () => {
  const source = `
    function isDone(item) {
      return item.received === true || item.status === 'Received';
    }
    function openItems(all) { return all.filter(it => !isDone(it)); }
  `;
  const { closure } = classifyReceipt(source);
  assert.equal(closure.verdict, VERDICT.CLOSURE_FROM_RECEIVED_OR_STATUS);
});

test('closure wrongly derived from a positive rcvQty is caught', () => {
  // The dangerous answer: a partly received requirement would read as
  // finished in the PWA, and N4 must not create that shape if so.
  const source = `
    function isDone(item) {
      return (item.rcvQty || 0) > 0 && item.status !== 'Cancelled';
    }
  `;
  const { closure } = classifyReceipt(source);
  assert.equal(closure.verdict, VERDICT.CLOSURE_FROM_RCVQTY);
  assert.match(closure.reason, /partly received/);
});

test('a bad rcvQty path is reported even when an honest check also exists', () => {
  // One wrong path is enough to matter, so it must not be averaged away.
  const source = `
    function isDone(item) { return item.received === true; }
    function badge(item) { if (item.rcvQty > 0) { item.status = 'Received'; } }
  `;
  assert.equal(classifyReceipt(source).closure.verdict, VERDICT.CLOSURE_FROM_RCVQTY);
});

test('a source with no closure decision at all is UNKNOWN', () => {
  const source = 'const rows = all.map(it => ({ id: it.id, name: it.name }));';
  const { closure } = classifyReceipt(source);
  assert.equal(closure.verdict, VERDICT.UNKNOWN);
  assert.match(closure.reason, /no closure decision/);
});

test('markup in a template literal is not mistaken for a comparison', () => {
  // Found by running the tool on a synthetic page: `</span>` carries a `>`
  // that reads exactly like an operator, and the first version called this
  // rendering line a closure decision.
  const source = `
    function isDone(item) { return item.received === true; }
    function row(item) {
      if (item.received) { return \`<span>\${item.rcvQty} in</span>\`; }
      return '';
    }
  `;
  const { closure } = classifyReceipt(source);
  assert.equal(closure.verdict, VERDICT.CLOSURE_FROM_RECEIVED_OR_STATUS);
});

test('emptying literals leaves the code and takes the prose', () => {
  const stripped = codeOnly("if (x) { el.innerHTML = `<td>${it.rcvQty}</td>`; }");
  assert.doesNotMatch(stripped, /</, 'no markup survives to look like an operator');
  assert.match(stripped, /innerHTML/, 'the code around it still does');
});

// --- Q1, whether a partial quantity reaches the screen -------------------

test('a partial quantity rendered with no received check is flagged', () => {
  const source = `
    function row(item) {
      return \`<td>\${item.name}</td><td>\${item.rcvQty} in</td>\`;
    }
  `;
  const { display } = classifyReceipt(source);
  assert.equal(display.verdict, VERDICT.USED_REGARDLESS_OF_RECEIVED);
  assert.ok(display.evidence.length >= 1);
});

test('a quantity rendered only inside a received branch is reported as guarded', () => {
  const source = `
    function row(item) {
      if (item.received) {
        return \`<span>\${item.rcvQty} in</span>\`;
      }
      return '<span></span>';
    }
  `;
  assert.equal(classifyReceipt(source).display.verdict, VERDICT.USED_ONLY_WHEN_RECEIVED);
});

test('guarded and unguarded uses together are UNKNOWN, not a majority vote', () => {
  const source = [
    'function a(it){ if (it.received) { return `${it.rcvQty} in`; } return ""; }',
    '',
    '',
    '',
    '',
    '',
    '',
    '',
    '',
    '',
    'function b(it){ return `<div>${it.rcvQty}</div>`; }',
  ].join('\n');
  const { display } = classifyReceipt(source);
  assert.equal(display.verdict, VERDICT.UNKNOWN);
  assert.match(display.reason, /unguarded/);
});

test('a source that never mentions the field says so', () => {
  const { display } = classifyReceipt('function row(it){ return it.name; }');
  assert.equal(display.verdict, VERDICT.NOT_USED);
});

test('uses that never reach the screen are UNKNOWN rather than assumed hidden', () => {
  const source = 'const total = items.reduce((sum, it) => sum + (it.rcvQty || 0), 0);';
  const { display } = classifyReceipt(source);
  assert.equal(display.verdict, VERDICT.UNKNOWN);
  assert.match(display.reason, /never on a line that renders/);
});

// --- what may be printed -------------------------------------------------

test('a snippet carries no price, no long literal and no number', () => {
  const line = "  const price = 18500.50; const acct = 'HDFC 50200012345678'; // rcvQty";
  const safe = redact(line);
  assert.doesNotMatch(safe, /18500/, 'a price must not leave the machine');
  assert.doesNotMatch(safe, /50200012345678/, 'nor an account number');
  assert.doesNotMatch(safe, /\d/, 'no digits at all survive redaction');
  assert.match(safe, /rcvQty/, 'the field being classified still has to be legible');
});

test('a template literal keeps its field names and loses its prose', () => {
  const safe = redact('return `Ordered for the Kandivali site: ${item.rcvQty} arrived`;');
  assert.match(safe, /\$\{item\.rcvQty\}/, 'the field being classified must stay legible');
  assert.doesNotMatch(safe, /Kandivali/, 'the page\'s own words must not leave the machine');
});

test('a short status word survives, because it is the whole question', () => {
  assert.match(redact("if (it.status === 'Received') { close(it); }"), /'Received'/);
});

test('a long string literal is elided even when it holds no digits', () => {
  const safe = redact("const note = 'the customer asked for the blue one again';");
  assert.match(safe, /'…'/);
  assert.doesNotMatch(safe, /customer/);
});

test('a snippet is one line and never a block of the page', () => {
  const safe = redact('x'.repeat(400));
  assert.ok(safe.length <= 100, `a snippet was ${safe.length} characters`);
  assert.doesNotMatch(safe, /\n/);
});

// --- the whole answer ----------------------------------------------------

test('every question is answered, and only with a verdict it declares', () => {
  const answers = classifyReceipt('function row(it){ return it.name; }');
  const known = new Set(Object.values(VERDICT));
  for (const key of ['display', 'closure', 'accumulation']) {
    assert.ok(answers[key], `${key} must be answered`);
    assert.ok(known.has(answers[key].verdict), `${key} returned an undeclared verdict`);
    assert.ok(answers[key].reason.length > 0, `${key} must say why`);
  }
});
