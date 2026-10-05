// N5.10b commit 9 — measurement only; not run by CI. The cost of a REFUSED write: the most
// padding N at which it is still refused by the rule ('denied') rather than
// by the limit. cost ≈ (163 − N) × 1000/163. usage: RULES=<file> node refcost.js
process.env.PAD_ALL = '1';
const h = require('./headroom.js');
const { UIDS, as } = h;
const W = () => Date.now();
const SC = [
  { name: 'Staff edits a Manager\'s qty (data.test.js:222)', id: 'pr_manager',
    stored: { id: 'pr_manager', name: 'Remote handsets', qty: 4, urgency: 'normal', status: 'Needed', byUid: UIDS.staff, t: 1, updated: 1 },
    write: (env) => as(env, UIDS.worker).collection('purchase').doc('pr_manager').update({ qty: 5, updated: W() }) },
  { name: 'Manager edits somebody\'s untouched (valid)', id: 'pr_w',
    stored: { id: 'pr_w', name: 'R', qty: 4, urgency: 'normal', status: 'Needed', byUid: UIDS.worker, t: 1, updated: 1 },
    write: (env) => as(env, UIDS.staff).collection('purchase').doc('pr_w').update({ qty: 5, updated: W() }) },
];
(async () => {
  const env = await h.createTestEnvironment();
  for (const withUid of [true, false]) {
    await h.seed(env, { withPrimaryOwnerUid: withUid });
    for (const sc of SC) {
      await h.loadRules(h.padded(0));
      const at0 = await h.attempt(env, sc);
      let lo = 0, hi = 1200;
      while (lo < hi) {
        const mid = Math.ceil((lo + hi) / 2);
        await h.loadRules(h.padded(mid));
        const o = await h.attempt(env, sc);
        if (o === at0) lo = mid; else hi = mid - 1;
      }
      console.log(`${Math.round((163 - lo) * 1000 / 163)}\t${at0}\t[${withUid ? 'uid set' : 'transition'}] ${sc.name}`);
    }
  }
  await env.cleanup();
})();
