// Valid /users updates, both access states, with every rule padded.
process.env.PAD_ALL = '1';
const h = require('./headroom.js');
const { UIDS, as } = h;
const P = require(__dirname + '/../tests/helpers').PEOPLE;
const withUid = { secondOwnerUid: UIDS.additionalOwner, updatedAt: 1, updatedBy: UIDS.primaryOwner, primaryOwnerUid: UIDS.primaryOwner };
const transition = { secondOwnerUid: UIDS.additionalOwner, updatedAt: 1, updatedBy: UIDS.primaryOwner };
const S = [];
for (const [label, access] of [['uid set', withUid], ['transition', transition]]) {
  const u = (actor, target, data) => ({ name: `[${label}] ${actor} → ${target} ${JSON.stringify(data)}`, id: target, coll: 'users',
    stored: P[target], access, write: (e) => as(e, actor).collection('users').doc(target).update({ ...data, email: P[target].email }) });
  S.push(u(UIDS.admin, UIDS.worker, { role: 'staff' }));
  S.push(u(UIDS.admin, UIDS.staff, { active: false }));
  S.push(u(UIDS.additionalOwner, UIDS.admin, { role: 'staff' }));
  S.push(u(UIDS.additionalOwner, UIDS.otherAdmin, { active: false }));
  S.push(u(UIDS.primaryOwner, UIDS.admin, { role: 'worker' }));
  S.push(u(UIDS.primaryOwner, UIDS.staff, { active: false }));
}
(async () => {
  const env = await h.createTestEnvironment();
  await h.seed(env);
  for (const sc of S) {
    // A fresh team before every scenario: an earlier one may have demoted
    // the very account this one acts as.
    await h.seed(env, { withPrimaryOwnerUid: sc.name.startsWith('[uid set]') });
    const r = await h.maxN(env, sc);
    console.log(`${r.maxN === undefined ? '-' : Math.round((163 - r.maxN) * 1000 / 163)}\t${r.at0}\t${sc.name}`);
  }
  await h.loadRules(require('node:fs').readFileSync(__dirname + '/../firestore.rules', 'utf8'));
  await env.cleanup();
})();
