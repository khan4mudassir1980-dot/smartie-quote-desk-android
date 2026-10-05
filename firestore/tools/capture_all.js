// N5.10b — measurement only; not run by CI. Records EVERY purchase write a test makes through
// DocumentReference.update/set: the call site, the actor, the payload, and the
// stored row, access document and actor profile just before it.
const fs = require('node:fs');
const OUT = process.env.N510B_CAPTURE;
const NM = __dirname + '/../node_modules/';
const firebase = require(NM + 'firebase/compat/app');
require(NM + 'firebase/compat/firestore');
const helpers = require(process.env.N510B_HELPERS);
const BASE = 'http://127.0.0.1:8080/v1/projects/smartie-rules-test/databases/(default)/documents/';
async function raw(path) {
  const r = await fetch(BASE + path, { headers: { Authorization: 'Bearer owner' } });
  if (r.status === 404) return null;
  return (await r.json()).fields || {};
}
function ser(v) {
  if (v === null || typeof v !== 'object') return v;
  if (v instanceof firebase.firestore.FieldValue) {
    if (v.isEqual(firebase.firestore.FieldValue.serverTimestamp())) return { __fv: 'serverTimestamp' };
    if (v.isEqual(firebase.firestore.FieldValue.delete())) return { __fv: 'delete' };
    return { __fv: 'other' };
  }
  if (typeof v.toMillis === 'function') return { __ts: v.toMillis() };
  if (v instanceof Date) return { __ts: v.getTime() };
  if (Array.isArray(v)) return v.map(ser);
  return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, ser(x)]));
}
const origAs = helpers.as;
helpers.as = function (env, uid) { const db = origAs(env, uid); db.__uid = uid; return db; };
const Ref = firebase.firestore.DocumentReference.prototype;
for (const method of ['update', 'set']) {
  const orig = Ref[method];
  Ref[method] = function (...args) {
    const ref = this;
    const uid = ref.firestore.__uid;
    if (!ref.path.startsWith('purchase/') || !uid) return orig.apply(ref, args);
    const frame = (new Error().stack || '').split('\n').find((l) => /tests[\\/][a-z0-9-]+\.test\.js:\d+/.test(l));
    const m = frame && frame.match(/tests[\\/]([a-z0-9-]+\.test\.js):(\d+)/);
    return (async () => {
      const rec = { file: m && m[1], line: m && Number(m[2]), write: { path: ref.path, method, uid, data: ser(args[0]), options: args[1] || null,
        stored: await raw(ref.path), access: await raw('teamSettings/access'), actor: await raw('users/' + uid) } };
      try { const r = await orig.apply(ref, args); rec.outcome = 'ok'; fs.appendFileSync(OUT, JSON.stringify(rec) + '\n'); return r; }
      catch (e) { rec.outcome = /maximum of 1000/.test(String(e.message)) ? 'LIMIT' : 'denied'; fs.appendFileSync(OUT, JSON.stringify(rec) + '\n'); throw e; }
    })();
  };
}
