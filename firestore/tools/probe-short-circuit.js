// Does the emulator skip the right side of `false && X`, and of `true || X`?
process.env.PAD_ALL = '';
process.env.PAD_MATCH = '/stockPhotos/{stockDoc}';
const h = require('./headroom.js');
const fs = require('node:fs');
const base = fs.readFileSync(__dirname + '/../firestore.rules', 'utf8');
function withPad(n, cond) {
  let r = h.padded(n).replace('    match /padtest/{id} { allow write: if pad(); }', `    match /padtest/{id} { allow write: if ${cond}; }`);
  return r;
}
(async () => {
  const env = await h.createTestEnvironment();
  await h.seed(env);
  for (const cond of ['false && pad()', 'pad() && false', 'true || pad()', 'request.auth.uid == "nobody" && pad()', 'request.auth.uid != "nobody" || pad()']) {
    await h.loadRules(withPad(400, cond));   // 400 terms is well past the budget if evaluated
    let out;
    try { await h.as(env, h.UIDS.admin).collection('padtest').doc('x').set({ a: 1 }); out = 'allowed'; }
    catch (e) { out = /maximum of 1000/.test(e.message) ? 'LIMIT (right side evaluated)' : 'denied (no limit)'; }
    console.log(cond.padEnd(42), out);
  }
  await h.loadRules(base);
  await env.cleanup();
})();
