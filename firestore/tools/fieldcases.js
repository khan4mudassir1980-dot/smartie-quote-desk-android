// N5.10b commit 8b — measurement only; not run by CI. A differential replay aimed at the
// ternaries: writes whose conditions can ERROR (a field deleted, null, or the
// wrong type), since `error || true` and `error ? a : b` are not the same.
// Every caller shape (all test people, no profile, inactive, no role, a
// mis-cased role, a non-boolean active) × three access states × four stored
// rows × seven PurchaseWrite payloads, decided under two rule files.
// usage: node fieldcases.js <old.rules> <new.rules>
const fs = require('node:fs');
const R = __dirname + '/../';
const firebase = require(R + 'node_modules/firebase/compat/app');
require(R + 'node_modules/firebase/compat/firestore');
const { createTestEnvironment, seed, UIDS, PEOPLE } = require(R + 'tests/helpers');
const [OLD, NEW] = [fs.readFileSync(process.argv[2], 'utf8'), fs.readFileSync(process.argv[3], 'utf8')];
const LIMIT = /maximum of 1000 expressions/;
const ts = () => firebase.firestore.FieldValue.serverTimestamp();
const del = () => firebase.firestore.FieldValue.delete();

const EXTRA = {
  uid_norole: { name: 'No Role', email: 'norole@example.invalid', active: true },
  uid_case: { name: 'Mis-cased', email: 'case@example.invalid', role: 'Admin', active: true },
  uid_activestr: { name: 'Active string', email: 'str@example.invalid', role: 'staff', active: 'true' },
  uid_nullrole: { name: 'Null role', email: 'null@example.invalid', role: null, active: true },
  uid_numrole: { name: 'Number role', email: 'num@example.invalid', role: 7, active: true },
};
const CALLERS = [UIDS.primaryOwner, UIDS.admin, UIDS.staff, UIDS.worker, 'uid_norole'];
const person = (uid) => PEOPLE[uid] || EXTRA[uid] || { name: 'Nobody', email: 'nobody@example.invalid' };
const ACCESS = {
  withUid: { secondOwnerUid: UIDS.additionalOwner, updatedAt: 1, updatedBy: UIDS.primaryOwner, primaryOwnerUid: UIDS.primaryOwner },
  transition: { secondOwnerUid: UIDS.additionalOwner, updatedAt: 1, updatedBy: UIDS.primaryOwner },
  none: null,
};
const row = (byUid, extra = {}) => ({
  id: 'pr_1', name: 'Rack', qty: 10, urgency: 'normal', status: 'Needed', note: 'n',
  by: person(byUid).name, byUid, t: 1712000000000, updated: 1712000000000, rev: 3,
  received: false, del: false, upBy: 'x', upUid: byUid, ...extra,
});
const rcv = { rcvQty: 4, rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: 1712100000000 };
const ROWS = {
  ownV8: (uid) => ({ id: 'pr_1', name: 'Rack', qty: 10, urgency: 'normal', status: 'Needed', byUid: uid, t: 1, updated: 1, received: 0, rcvQty: '4' }),
  own: (uid) => row(uid),
  others: () => row(UIDS.otherStaff),
  othersPart: () => row(UIDS.otherStaff, rcv),
  ownPart: (uid) => row(uid, rcv),
  othersDone: () => row(UIDS.otherStaff, { ...rcv, rcvQty: 10, status: 'Received', received: true }),
};
const base = (uid, qty) => ({ id: 'pr_1', qty, updated: Date.now(), rev: 4, upBy: person(uid).name, upUid: uid, serverAt: ts() });
const WRITES = {
  delReceived: (uid) => ({ ...base(uid, 10), received: del() }),
  nullReceived: (uid) => ({ ...base(uid, 10), received: null }),
  strReceived: (uid) => ({ ...base(uid, 10), received: 'yes' }),
  delStatus: (uid) => ({ ...base(uid, 10), status: del() }),
  nullStatus: (uid) => ({ ...base(uid, 10), status: null }),
  numStatus: (uid) => ({ ...base(uid, 10), status: 5 }),
  delStatusClose: (uid) => ({ ...base(uid, 10), status: del(), received: true, rcvQty: 10, rcvBy: person(uid).name, rcvUid: uid, rcvAt: Date.now() }),
  nullRcvQty: (uid) => ({ ...base(uid, 10), rcvQty: null }),
  strRcvQty: (uid) => ({ ...base(uid, 10), rcvQty: '6' }),
  delRcvQtyClose: (uid) => ({ ...base(uid, 10), received: true, status: 'Received', rcvQty: del() }),
  nullUpUid: (uid) => ({ ...base(uid, 10), upUid: null }),
  delUpUid: (uid) => ({ ...base(uid, 10), upUid: del() }),
  numRcvUid: (uid) => ({ ...base(uid, 10), rcvQty: 6, rcvBy: 'x', rcvUid: 7, rcvAt: Date.now() }),
  numDelBy: (uid) => ({ ...base(uid, 10), del: true, deletedBy: uid, delBy: 7, delAt: Date.now() }),
  nullDeletedBy: (uid) => ({ ...base(uid, 10), del: true, deletedBy: null, delBy: 'x', delAt: Date.now() }),
  delDelAt: (uid) => ({ ...base(uid, 10), del: true, deletedBy: uid, delBy: 'x', delAt: del() }),
  nullCancelledUid: (uid) => ({ ...base(uid, 10), status: 'Cancelled', cancelledBy: 'x', cancelledUid: null, cancelledAt: Date.now() }),
  shortfallNullStatus: (uid) => ({ ...base(uid, 4), status: null, received: true }),
};

async function loadRules(text) {
  const r = await fetch('http://127.0.0.1:8080/emulator/v1/projects/smartie-rules-test:securityRules', { method: 'PUT', body: JSON.stringify({ rules: { files: [{ name: 'firestore.rules', content: text }] } }) });
  if (!r.ok) throw new Error((await r.text()).slice(0, 800));
}

async function decide(env, text) {
  await loadRules(text);
  const out = [];
  for (const [aName, access] of Object.entries(ACCESS)) {
    await seed(env, { withPrimaryOwnerUid: false });
    await env.withSecurityRulesDisabled(async (c) => {
      const db = c.firestore();
      for (const [uid, p] of Object.entries(EXTRA)) await db.collection('users').doc(uid).set(p);
      if (access) await db.collection('teamSettings').doc('access').set(access);
      else await db.collection('teamSettings').doc('access').delete();
    });
    for (const uid of CALLERS) for (const [rName, mk] of Object.entries(ROWS)) for (const [wName, w] of Object.entries(WRITES)) {
      await env.withSecurityRulesDisabled((c) => c.firestore().collection('purchase').doc('pr_1').set(mk(uid)));
      const db = env.authenticatedContext(uid, { email: person(uid).email }).firestore();
      let r;
      try { await db.collection('purchase').doc('pr_1').update(w(uid)); r = 'ok'; }
      catch (e) { r = LIMIT.test(String(e.message)) ? 'LIMIT' : 'denied'; }
      out.push({ key: `${aName} ${uid} ${rName} ${wName}`, r });
    }
  }
  return out;
}

(async () => {
  const env = await createTestEnvironment();
  const a = await decide(env, OLD);
  const b = await decide(env, NEW);
  await loadRules(fs.readFileSync(R + 'firestore.rules', 'utf8'));
  await env.cleanup();
  let differ = 0; const tally = {};
  a.forEach((x, i) => {
    const k = `${x.r} → ${b[i].r}`; tally[k] = (tally[k] || 0) + 1;
    if (x.r !== b[i].r) { differ++; console.log('  DIFF', x.key, x.r, '→', b[i].r); }
  });
  console.log(JSON.stringify(tally), '| cases', a.length, '| decisions that differ:', differ);
})();
