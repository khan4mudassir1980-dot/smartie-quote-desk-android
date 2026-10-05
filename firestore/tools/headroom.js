// N5.10b — measurement only; not run by CI. For a write, finds the
// largest padding N it survives in front of `/purchase` update, against a
// scratch copy of the rules. cost ≈ (Ncal − N) × (1000 / Ncal).
const fs = require('node:fs');
const firebase = require(__dirname + '/../node_modules/firebase/compat/app');
require(__dirname + '/../node_modules/firebase/compat/firestore');
const { createTestEnvironment, seed, as, UIDS } = require(__dirname + '/../tests/helpers');
const RULES = fs.readFileSync(process.env.RULES || __dirname + '/../firestore.rules', 'utf8');
const LIMIT = /maximum of 1000 expressions/;

function padded(n) {
  // Ten terms a chunk, ten chunks a group: no one expression is long enough
  // for the compiler to call it too complex.
  const chunks = [];
  for (let left = n; left > 0; left -= 10) {
    chunks.push(Array.from({ length: Math.min(10, left) }, () => 'request.auth != null').join(' && '));
  }
  let fns = chunks.map((body, i) => `    function padc${i}() { return ${body}; }\n`).join('');
  const groups = [];
  for (let g = 0; g * 10 < chunks.length; g++) {
    const calls = chunks.slice(g * 10, g * 10 + 10).map((_, j) => `padc${g * 10 + j}()`).join(' && ');
    fns += `    function padg${g}() { return ${calls}; }\n`;
    groups.push(`padg${g}()`);
  }
  const pad = fns + `    function pad() { return true${groups.length ? ' && ' + groups.join(' && ') : ''}; }\n`;
  let r = RULES.replace('    function touched()', pad + '    function touched()');
  if (process.env.PAD_MATCH) {
    // Pad one match block only, so one document's cost in a commit is read alone.
    const head = '    match ' + process.env.PAD_MATCH + ' {';
    const a = r.indexOf(head);
    if (a < 0) throw new Error('no block ' + head);
    const b = r.indexOf('\n    match /', a + head.length);
    r = r.slice(0, a) + require('./wrapall.js').wrapAll(r.slice(a, b)).text + r.slice(b);
  } else if (process.env.PAD_ALL) {
    r = require('./wrapall.js').wrapAll(r).text;
  } else {
    const upd = '      allow update: if member()\n                    && request.resource.data.id == id';
    if (!r.includes(upd)) throw new Error('purchase update anchor not found');
    r = r.replace(upd, '      allow update: if pad() && member()\n                    && request.resource.data.id == id');
  }
  r = r.replace('    match /{document=**} {', '    match /padtest/{id} { allow write: if pad(); }\n\n    match /{document=**} {');
  return r;
}

async function loadRules(text) {
  const res = await fetch('http://127.0.0.1:8080/emulator/v1/projects/smartie-rules-test:securityRules', {
    method: 'PUT', body: JSON.stringify({ rules: { files: [{ name: 'firestore.rules', content: text }] } }),
  });
  if (!res.ok) throw new Error('rules load failed: ' + (await res.text()).slice(0, 400));
}

async function attempt(env, sc) {
  await env.withSecurityRulesDisabled(async (c) => {
    const db = c.firestore();
    if (sc.stored) await db.collection(sc.coll || 'purchase').doc(sc.id).set(sc.stored);
    if (sc.access) await db.collection('teamSettings').doc('access').set(sc.access);
  });
  try { await sc.write(env); return 'allowed'; }
  catch (e) { return LIMIT.test(String(e.message)) ? 'limit' : 'denied'; }
}

async function maxN(env, sc) {
  await loadRules(padded(0));
  const at0 = await attempt(env, sc);
  if (at0 !== 'allowed') return { at0 };
  let lo = 0, hi = 1200;
  while (lo < hi) {
    const mid = Math.ceil((lo + hi) / 2);
    await loadRules(padded(mid));
    const o = await attempt(env, sc);
    if (o === 'allowed') lo = mid; else hi = mid - 1;
  }
  return { at0, maxN: lo };
}

module.exports = { maxN, attempt, loadRules, padded, createTestEnvironment, seed, as, UIDS, firebase };
