// N5.10b — measurement only; not run by CI. Headroom of every valid /purchase path (scenarios.js), every rule padded.
process.env.PAD_ALL = process.env.PAD_MATCH ? '' : '1';
const h = require('./headroom.js');
(async () => {
  const env = await h.createTestEnvironment();
  for (const sc of require(process.env.SCEN || './scenarios.js')) {
    await h.seed(env, { withPrimaryOwnerUid: sc.name.startsWith('[uid set]') });
    const r = await h.maxN(env, sc);
    console.log(`${r.maxN === undefined ? '-' : Math.round((163 - r.maxN) * 1000 / 163)}\t${r.at0}\t${sc.name}`);
  }
  await h.loadRules(require('node:fs').readFileSync(process.env.RULES || __dirname + '/../firestore.rules', 'utf8'));
  await env.cleanup();
})();
