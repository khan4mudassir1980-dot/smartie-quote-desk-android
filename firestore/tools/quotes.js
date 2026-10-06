// Valid /quotations writes at their heaviest, both access states, every rule padded.
process.env.PAD_ALL = '1';
const h = require('./headroom.js');
const { UIDS, as, firebase } = h;
// N5.11: the stamps are the server's — an edit's `lastEditedAt` and a finalise's
// `serverAt` are `serverTimestamp()`, as the app sends them.
const serverTime = () => firebase.firestore.FieldValue.serverTimestamp();
const quotation = (id, uid, extra = {}) => ({
  id, no: 'SIE/QD/2025-26/009', at: Date.now(), by: 'Manager Person', byUid: uid, tier: 'client', tierName: 'Client',
  partyId: 'c_1', party: { name: 'Sunrise Constructions', city: 'Mumbai' },
  lines: [{ t: 'Sliding gate motor', u: 'each', qty: 2, rate: 22200, k: 'gateMotors|SIE1000', amt: 44400 }],
  gst: true, gstPct: 18, subtotal: 44400, total: 52392, status: 'Finalised', ...extra });
const disc = (base, amt, kind = 'pct', value = 10) => ({
  lines: [{ t: 'Sliding gate motor', u: 'each', qty: 1, rate: base, amt: base }],
  disc: { kind, value, amt }, discBase: base, subtotal: base - amt, total: Math.round((base - amt) * 1.18) });
const edit = (uid, rev, fields) => ({ ...fields, lastEditedBy: 'Editor Person', lastEditedByUid: uid, lastEditedAt: serverTime(), rev });
const withUid = { secondOwnerUid: UIDS.additionalOwner, updatedAt: 1, updatedBy: UIDS.primaryOwner, primaryOwnerUid: UIDS.primaryOwner };
const transition = { secondOwnerUid: UIDS.additionalOwner, updatedAt: 1, updatedBy: UIDS.primaryOwner };

async function prime(env) {
  await env.withSecurityRulesDisabled(async (c) => {
    const db = c.firestore();
    await db.collection('teamSettings').doc('quoting').set({ managerDiscountPct: 5 });
    await db.collection('teamSettings').doc('numbering').set({ prefix: 'SIE/QD', fy: '2025-26', next: 9, pad: 3 });
  });
}

const S = [];
for (const [label, access] of [['uid set', withUid], ['transition', transition]]) {
  S.push({ name: `[${label}] Manager's edit, flat discount raised in rate within the cap ((iv) witness)`, coll: 'quotations', id: 'q1', access,
    stored: quotation('q1', UIDS.staff, disc(44400, 1000, 'amt', 1000)),
    write: (e) => as(e, UIDS.staff).collection('quotations').doc('q1').update(edit(UIDS.staff, 1, disc(22200, 1000, 'amt', 1000))) });
  S.push({ name: `[${label}] Manager's edit, percentage raised within the cap ((ii) witness)`, coll: 'quotations', id: 'q1', access,
    stored: quotation('q1', UIDS.staff, disc(44400, 1332, 'pct', 3)),
    write: (e) => as(e, UIDS.staff).collection('quotations').doc('q1').update(edit(UIDS.staff, 1, disc(44400, 1776, 'pct', 4))) });
  S.push({ name: `[${label}] Manager cancels their own`, coll: 'quotations', id: 'q1', access,
    stored: quotation('q1', UIDS.staff),
    write: (e) => as(e, UIDS.staff).collection('quotations').doc('q1').update({ status: 'Cancelled', cancelledBy: 'Manager Person', cancelledAt: Date.now() }) });
  let fresh = 0;
  S.push({ name: `[${label}] Manager finalises with a discount inside the cap (quotation + counter, one batch)`,
    coll: 'teamSettings', id: 'numbering', access,
    stored: { prefix: 'SIE/QD', fy: '2025-26', next: 9, pad: 3 },
    write: async (e) => {
      const db = as(e, UIDS.staff);
      const id = `q_new_${label.length}_${++fresh}`;
      const b = db.batch();
      b.set(db.collection('quotations').doc(id), quotation(id, UIDS.staff, { ...disc(44400, 2220, 'pct', 5), serverAt: serverTime() }));
      b.update(db.collection('teamSettings').doc('numbering'), { next: 10, lastIssued: { no: 'SIE/QD/2025-26/009', at: Date.now(), by: 'Manager Person', uid: UIDS.staff, src: 'android' } });
      await b.commit();
    } });
}
(async () => {
  const env = await h.createTestEnvironment();
  for (const sc of S) {
    await h.seed(env, { withPrimaryOwnerUid: sc.name.startsWith('[uid set]') });
    await prime(env);
    const r = await h.maxN(env, sc);
    console.log(`${r.maxN === undefined ? '-' : Math.round((163 - r.maxN) * 1000 / 163)}\t${r.at0}\t${sc.name}`);
  }
  await h.loadRules(require('node:fs').readFileSync(__dirname + '/../firestore.rules', 'utf8'));
  await env.cleanup();
})();
