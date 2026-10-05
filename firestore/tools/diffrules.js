// N5.10b — measurement only; not run by CI. Differential replay: every case decided under the
// OLD /purchase update rule (split into its three branches, so no branch reaches
// the limit; allowed iff a branch allows) and under the NEW rules whole.
// usage: node diffrules.js <old-rules-file> <new-rules-file> <out.jsonl>
const fs = require('node:fs');
const R = __dirname + '/../';
const firebase = require(R + 'node_modules/firebase/compat/app');
require(R + 'node_modules/firebase/compat/firestore');
const helpers = require(R + 'tests/helpers');
const { createTestEnvironment, seed, as } = helpers;
const [OLD, NEW] = [fs.readFileSync(process.argv[2], 'utf8'), fs.readFileSync(process.argv[3], 'utf8')];
const LIMIT = /maximum of 1000 expressions/;
const BASE = 'http://127.0.0.1:8080/v1/projects/smartie-rules-test/databases/(default)/documents/';
const OWNER = { Authorization: 'Bearer owner' };

// --- the old rule, split per branch (the 6fbc2bc shape) -------------------------
const BR = {
  admin: 'admin()',
  staff: 'staff() && ((prUntouched() && prEditKeys()) || prDelivery())',
  creator: 'prCreator() && ((prUntouched() && (prEditKeys() || prRemoveKeys())) || prDelivery())',
};
function split(text, branch) {
  const m = text.indexOf('match /purchase/{id}');
  const a = text.indexOf('      allow update: if member()', m);
  const d = text.indexOf('\n                    && (\n', a);
  const e = text.indexOf('\n                    );', d) + '\n                    );'.length;
  if (m < 0 || a < 0 || d < 0 || e < d) throw new Error('split anchors (old rule shape expected)');
  return text.slice(0, a) + `      allow update: if ${text.slice(a + '      allow update: if '.length, d)} && (${BR[branch]});` + text.slice(e);
}

// --- plumbing -----------------------------------------------------------------------
function fields(o) {
  const f = {};
  for (const [k, v] of Object.entries(o)) {
    if (typeof v === 'string') f[k] = { stringValue: v };
    else if (typeof v === 'boolean') f[k] = { booleanValue: v };
    else if (Number.isInteger(v)) f[k] = { integerValue: String(v) };
    else if (typeof v === 'number') f[k] = { doubleValue: v };
    else if (v === null) f[k] = { nullValue: null };
    else if (v instanceof Date) f[k] = { timestampValue: v.toISOString() };
    else throw new Error('field type ' + k);
  }
  return f;
}
async function loadRules(text) {
  const r = await fetch('http://127.0.0.1:8080/emulator/v1/projects/smartie-rules-test:securityRules', { method: 'PUT', body: JSON.stringify({ rules: { files: [{ name: 'firestore.rules', content: text }] } }) });
  if (!r.ok) throw new Error((await r.text()).slice(0, 800));
}
async function plant(path, doc, typed) {
  if (!doc) { await fetch(BASE + path, { method: 'DELETE', headers: OWNER }); return; }
  const r = await fetch(BASE + path, { method: 'PATCH', headers: { ...OWNER, 'Content-Type': 'application/json' },
    body: JSON.stringify({ fields: typed ? doc : fields(doc) }) });
  if (!r.ok) throw new Error('plant ' + path + ' ' + (await r.text()).slice(0, 300));
}
function de(v) {
  if (v === null || typeof v !== 'object') return v;
  if (Array.isArray(v)) return v.map(de);
  if (v.__fv === 'serverTimestamp') return firebase.firestore.FieldValue.serverTimestamp();
  if (v.__fv === 'delete') return firebase.firestore.FieldValue.delete();
  if ('__ts' in v) return firebase.firestore.Timestamp.fromMillis(v.__ts);
  return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, de(x)]));
}
const team = Object.entries(helpers.PEOPLE).map(([uid, p]) => ['users/' + uid, p]);
const withUid = { secondOwnerUid: 'uid_second', updatedAt: 1, updatedBy: 'uid_owner', primaryOwnerUid: 'uid_owner' };

// --- the cases ------------------------------------------------------------------------
function cases() {
  const out = [];
  // (a) every purchase write the suite makes, with the state just before it
  for (const l of fs.readFileSync(process.env.CAPTURED, 'utf8').trim().split('\n')) {
    const r = JSON.parse(l); const w = r.write;
    out.push({ name: `suite ${r.file}:${r.line}`, kind: 'suite',
      prepare: async () => { await plant('users/' + w.uid, w.actor, true); await plant('teamSettings/access', w.access, true); await plant(w.path, w.stored, true); },
      run: (env) => { const ref = as(env, w.uid).doc(w.path);
        return w.method === 'update' ? ref.update(de(w.data)) : ref.set(de(w.data), w.options || undefined); } });
  }
  // (b, c) V8C4's payloads and the crafted writes
  for (const c of require('./v8cases.js').v8c4Cases()) {
    out.push({ name: c.name, kind: c.name.startsWith('crafted') ? 'crafted' : 'v8c4',
      prepare: async () => { await plant('teamSettings/access', withUid); await plant(c.path, c.stored); },
      run: (env) => { const ref = as(env, c.uid).doc(c.path);
        return c.method === 'update' ? ref.update(c.data()) : ref.set(c.data(), c.options); } });
  }
  // (d) the 40 valid paths, as PurchaseWrite sends them
  for (const sc of require('./scenarios.js')) {
    out.push({ name: 'valid ' + sc.name, kind: 'valid',
      prepare: async () => { await plant('teamSettings/access', sc.access); await plant('purchase/' + sc.id, sc.stored); },
      run: (env) => sc.write(env) });
  }
  return out;
}

async function decideAll(env, list, text) {
  await loadRules(text);
  const res = [];
  for (const c of list) {
    for (const [p, d] of team) await plant(p, d);
    await c.prepare();
    try { await c.run(env); res.push('ok'); }
    catch (e) { res.push(LIMIT.test(String(e.message)) ? 'LIMIT' : 'denied'); }
  }
  return res;
}

(async () => {
  const env = await createTestEnvironment();
  await seed(env);
  const list = cases();
  const perBranch = {};
  if (process.env.OLD_WHOLE) { const whole = await decideAll(env, list, OLD); for (const b of Object.keys(BR)) perBranch[b] = whole; }
  else for (const b of Object.keys(BR)) perBranch[b] = await decideAll(env, list, split(OLD, b));
  const fresh = await decideAll(env, list, NEW);
  const rows = list.map((c, i) => {
    const branches = Object.keys(BR).map((b) => perBranch[b][i]);
    const old = branches.includes('ok') ? 'ok' : branches.includes('LIMIT') ? 'LIMIT' : 'denied';
    return { name: c.name, kind: c.kind, old, oldBranches: branches.join('/'), new: fresh[i] };
  });
  await loadRules(NEW);
  fs.writeFileSync(process.argv[4], rows.map((r) => JSON.stringify(r)).join('\n') + '\n');
  const by = {};
  for (const r of rows) { const k = `${r.kind}: old ${r.old} → new ${r.new}`; by[k] = (by[k] || 0) + 1; }
  console.log(JSON.stringify(by, null, 1));
  const diff = rows.filter((r) => r.old !== r.new);
  console.log('cases', rows.length, '| decisions that differ:', diff.length);
  for (const r of diff) console.log('  DIFF', r.name, r.old, '→', r.new);
  await env.cleanup();
})();
