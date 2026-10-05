// N5.10b commit 9 — measurement only; not run by CI. Is a DENIED write's budget spent twice?
// The padding a write survives when the rule allows it (pad(N)), against when
// it denies it after the pad (pad(N) && false). Same pad, same document.
process.env.PAD_ALL = '';
process.env.PAD_MATCH = '/stockPhotos/{stockDoc}';
const h = require('./headroom.js');
const fs = require('node:fs');
function rules(n, cond) {
  return h.padded(n).replace('    match /padtest/{id} { allow write: if pad(); }', `    match /padtest/{id} { allow write: if ${cond}; }`);
}
async function outcome(env, n, cond) {
  await h.loadRules(rules(n, cond));
  try { await h.as(env, h.UIDS.admin).collection('padtest').doc('x').set({ a: 1 }); return 'allowed'; }
  catch (e) { return /maximum of 1000/.test(e.message) ? 'limit' : 'denied'; }
}
(async () => {
  const env = await h.createTestEnvironment();
  await h.seed(env);
  for (const cond of ['pad()', 'pad() && false', 'pad() && request.auth.uid == "nobody"']) {
    const first = await outcome(env, 0, cond);
    let lo = 0, hi = 400;
    while (lo < hi) { const mid = Math.ceil((lo + hi) / 2); if ((await outcome(env, mid, cond)) === first) lo = mid; else hi = mid - 1; }
    console.log(cond.padEnd(42), first, 'survives up to N =', lo);
  }
  await h.loadRules(fs.readFileSync(__dirname + '/../firestore.rules', 'utf8'));
  await env.cleanup();
})();
