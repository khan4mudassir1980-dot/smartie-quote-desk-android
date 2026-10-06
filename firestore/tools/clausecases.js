// N5.10b review (2026-10-06) — measurement only; not run by CI. A differential replay
// aimed at the two clauses no test turned red, prRcvNumeric and
// prClosesOnlyWhenMet: every stored shape they look at — `rcvQty` absent, a
// number, a string, null or a boolean; `received` absent, false, 0, 1 or
// true; status Needed, Ordered or Received — × every delivery, close and
// write-off payload, including the malformed ones, × a Manager, the Staff
// creator, a Staff account that is not the creator and an Administrator,
// × both access states, decided under two rule files.
// usage: node clausecases.js <old.rules> <new.rules>
const fs = require('node:fs');
const R = __dirname + '/../';
const firebase = require(R + 'node_modules/firebase/compat/app');
require(R + 'node_modules/firebase/compat/firestore');
const { createTestEnvironment, seed, UIDS, PEOPLE } = require(R + 'tests/helpers');
const [OLD, NEW] = [fs.readFileSync(process.argv[2], 'utf8'), fs.readFileSync(process.argv[3], 'utf8')];
const LIMIT = /maximum of 1000 expressions/;
const ts = () => firebase.firestore.FieldValue.serverTimestamp();
const del = () => firebase.firestore.FieldValue.delete();

// Who raised it: the Staff account (`worker`), whose own it then is, or a
// second Manager, so the Staff caller is not the creator. The Manager
// (`staff`) delivers on anybody's; the Administrator never reaches the
// delivery branch, and is here as the control.
const OWNERS = [UIDS.worker, UIDS.otherStaff];
const CALLERS = [UIDS.admin, UIDS.staff, UIDS.worker];
const name = (uid) => (PEOPLE[uid] || { name: 'Nobody' }).name;

const RCV_QTY = { none: undefined, num: 4, str: '4', null: null, bool: true };
const RECEIVED = { none: undefined, false: false, zero: 0, one: 1, true: true };
const STATUS = ['Needed', 'Ordered', 'Received'];

function stored(owner, rcvQty, received, status) {
  const row = {
    id: 'pr_1', name: 'Rack', qty: 10, urgency: 'normal', status, note: 'n',
    by: name(owner), byUid: owner, t: 1712000000000, updated: 1712000000000, rev: 3,
    del: false, upBy: 'x', upUid: owner,
  };
  if (received !== undefined) row.received = received;
  // A receipt is the four fields together, as the app and V8C4 write them.
  if (rcvQty !== undefined) Object.assign(row, { rcvQty, rcvBy: 'Someone', rcvUid: UIDS.staff, rcvAt: 1712100000000 });
  return row;
}

const base = (uid, qty = 10) => ({ id: 'pr_1', qty, updated: Date.now(), rev: 4, upBy: name(uid), upUid: uid, serverAt: ts() });
const stamp = (uid) => ({ rcvBy: name(uid), rcvUid: uid, rcvAt: Date.now() });
const WRITES = {
  part: (uid) => ({ ...base(uid), rcvQty: 6, ...stamp(uid) }),
  partNeeded: (uid) => ({ ...base(uid), rcvQty: 6, status: 'Needed', received: false, ...stamp(uid) }),
  partLower: (uid) => ({ ...base(uid), rcvQty: 2, ...stamp(uid) }),
  partString: (uid) => ({ ...base(uid), rcvQty: '6', ...stamp(uid) }),
  whole: (uid) => ({ ...base(uid), rcvQty: 10, received: true, status: 'Received', ...stamp(uid) }),
  wholeNoStatus: (uid) => ({ ...base(uid), rcvQty: 10, received: true, ...stamp(uid) }),
  wholeString: (uid) => ({ ...base(uid), rcvQty: '10', received: true, status: 'Received', ...stamp(uid) }),
  shortClose: (uid) => ({ ...base(uid), rcvQty: 6, received: true, status: 'Received', ...stamp(uid) }),
  shortCloseNoStatus: (uid) => ({ ...base(uid), rcvQty: 6, received: true, ...stamp(uid) }),
  closeOnStored: (uid) => ({ ...base(uid), received: true, status: 'Received' }),
  closeOnStoredNoStatus: (uid) => ({ ...base(uid), received: true }),
  closeRcvDeleted: (uid) => ({ ...base(uid), received: true, status: 'Received', rcvQty: del() }),
  statusOnly: (uid) => ({ ...base(uid), status: 'Received' }),
  writeOff: (uid) => ({ ...base(uid, 4), received: true, status: 'Received' }),
  writeOffNoStatus: (uid) => ({ ...base(uid, 4), received: true }),
  writeOffWrongQty: (uid) => ({ ...base(uid, 6), received: true, status: 'Received' }),
  stampOnly: (uid) => ({ ...base(uid) }),
};

// The stored row is written through the emulator's REST API as the owner,
// which skips the rules: one SDK context per case ran node out of memory.
const DOC = 'http://127.0.0.1:8080/v1/projects/smartie-rules-test/databases/(default)/documents/purchase/pr_1';
function value(v) {
  if (v === null) return { nullValue: null };
  if (typeof v === 'string') return { stringValue: v };
  if (typeof v === 'boolean') return { booleanValue: v };
  if (Number.isInteger(v)) return { integerValue: String(v) };
  return { doubleValue: v };
}
async function put(row) {
  const fields = Object.fromEntries(Object.entries(row).map(([k, v]) => [k, value(v)]));
  const r = await fetch(DOC, { method: 'PATCH', headers: { Authorization: 'Bearer owner' }, body: JSON.stringify({ fields }) });
  if (!r.ok) throw new Error((await r.text()).slice(0, 400));
}

async function loadRules(text) {
  const r = await fetch('http://127.0.0.1:8080/emulator/v1/projects/smartie-rules-test:securityRules', { method: 'PUT', body: JSON.stringify({ rules: { files: [{ name: 'firestore.rules', content: text }] } }) });
  if (!r.ok) throw new Error((await r.text()).slice(0, 800));
}

async function decide(env, text) {
  await loadRules(text);
  const out = [];
  for (const withUid of [true, false]) {
    await seed(env, { withPrimaryOwnerUid: withUid });
    // One client per caller for the whole pass.
    const dbs = Object.fromEntries(CALLERS.map((uid) =>
      [uid, env.authenticatedContext(uid, { email: (PEOPLE[uid] || {}).email }).firestore()]));
    for (const owner of OWNERS) for (const [qName, q] of Object.entries(RCV_QTY)) for (const [rName, rv] of Object.entries(RECEIVED)) for (const status of STATUS) {
      for (const uid of CALLERS) for (const [wName, w] of Object.entries(WRITES)) {
        await put(stored(owner, q, rv, status));
        const db = dbs[uid];
        let r;
        try { await db.collection('purchase').doc('pr_1').update(w(uid)); r = 'ok'; }
        catch (e) { r = LIMIT.test(String(e.message)) ? 'LIMIT' : 'denied'; }
        out.push({ key: `${withUid ? 'uid' : 'transition'} by=${owner} rcvQty=${qName} received=${rName} ${status} ${uid} ${wName}`, r });
      }
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
