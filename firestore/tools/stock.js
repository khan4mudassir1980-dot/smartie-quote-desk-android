// N5.10b step 1 — measurement only. Valid /stock writes as the app makes
// them (StockWrite.kt), both access states, every rule padded.
process.env.PAD_ALL = '1';
const h = require('./headroom.js');
const { UIDS, as, firebase } = h;
const BASE = 'http://127.0.0.1:8080/v1/projects/smartie-rules-test/databases/(default)/documents/';
const KEY = 'gateMotors|SIE1000';
const ts = () => firebase.firestore.FieldValue.serverTimestamp();
const stored = { key: KEY, q: 7, min: 2, t: 1, byUid: UIDS.admin, by: 'Asha', name: 'Sliding gate motor', group: 'gateMotors', model: 'SIE1000', lastAction: 'in' };
const row = (uid, action, extra = {}) => ({ key: KEY, q: 8, min: 2, t: Date.now(), lastAction: action, by: 'Tester', byUid: uid,
  serverAt: ts(), group: 'gateMotors', model: 'SIE1000', name: 'Sliding gate motor', ...extra });
const move = (uid, id, action) => ({ id, key: KEY, group: 'gateMotors', model: 'SIE1000', name: 'Sliding gate motor', action,
  prev: 7, next: 8, delta: 1, min: 2, note: 'Stock updated', by: 'Tester', byUid: uid, at: Date.now(), serverAt: ts() });
const photo = (uid, rev) => ({ key: KEY, bytes: firebase.firestore.Blob.fromUint8Array(new Uint8Array(81920)), w: 800, h: 600, rev, by: 'Tester', byUid: uid, at: Date.now() });

async function plantPhoto(rev) {
  await fetch(BASE + 'stockPhotos/' + encodeURIComponent(KEY), { method: 'PATCH', headers: { Authorization: 'Bearer owner', 'Content-Type': 'application/json' },
    body: JSON.stringify({ fields: { key: { stringValue: KEY }, rev: { integerValue: String(rev) }, byUid: { stringValue: UIDS.admin } } }) });
}

const S = [];
for (const [label, withUid] of [['uid set', true], ['transition', false]]) {
  for (const [who, uid] of [['Manager', UIDS.staff], ['Administrator', UIDS.admin]]) {
    const sc = (what, write, over = {}) => S.push({ name: `[${label}] ${who} ${what}`, withUid, coll: 'stock', id: KEY, stored: { ...stored, ...over }, write });
    for (const action of ['in', 'out', 'min']) {
      sc(`${action} + movement (transaction)`, (e) => {
        const db = as(e, uid);
        return db.runTransaction(async (tx) => {
          await tx.get(db.collection('stock').doc(KEY));
          tx.set(db.collection('stock').doc(KEY), row(uid, action), { merge: true });
          const mv = 'mv_' + action + Date.now();
          tx.set(db.collection('stockMoves').doc(mv), move(uid, mv, action));
        });
      });
    }
    sc('note only', (e) => as(e, uid).collection('stock').doc(KEY).set(row(uid, 'note', { q: 7, stockNote: 'Top shelf' }), { merge: true }));
    sc('pin', (e) => as(e, uid).collection('stock').doc(KEY).set(row(uid, 'pin', { q: 7, pinned: true }), { merge: true }));
    sc('photo set (batch)', (e) => {
      const db = as(e, uid); const b = db.batch();
      b.set(db.collection('stockPhotos').doc(KEY), photo(uid, 2));
      b.set(db.collection('stock').doc(KEY), row(uid, 'photo', { q: 7, hasPhoto: true, photoRev: 2 }), { merge: true });
      return b.commit();
    }, { hasPhoto: true, photoRev: 1 });
    sc('photo removed (batch)', async (e) => {
      await plantPhoto(1);
      const db = as(e, uid); const b = db.batch();
      b.delete(db.collection('stockPhotos').doc(KEY));
      b.set(db.collection('stock').doc(KEY), row(uid, 'photo', { q: 7, hasPhoto: false, photoRev: 2 }), { merge: true });
      return b.commit();
    }, { hasPhoto: true, photoRev: 1 });
    if (who === 'Administrator') {
      sc('set exact + movement (transaction)', (e) => {
        const db = as(e, uid);
        return db.runTransaction(async (tx) => {
          await tx.get(db.collection('stock').doc(KEY));
          tx.set(db.collection('stock').doc(KEY), row(uid, 'set', { q: 20 }), { merge: true });
          const mv = 'mv_set' + Date.now();
          tx.set(db.collection('stockMoves').doc(mv), move(uid, mv, 'set'));
        });
      });
    }
  }
}
(async () => {
  const env = await h.createTestEnvironment();
  for (const sc of S) {
    await h.seed(env, { withPrimaryOwnerUid: sc.withUid });
    await fetch(BASE + 'stockPhotos/' + encodeURIComponent(KEY), { method: 'DELETE', headers: { Authorization: 'Bearer owner' } });
    if (sc.stored.hasPhoto) await plantPhoto(1);
    const r = await h.maxN(env, sc);
    console.log(`${r.maxN === undefined ? '-' : Math.round((163 - r.maxN) * 1000 / 163)}\t${r.at0}\t${sc.name}`);
  }
  await h.loadRules(require('node:fs').readFileSync(__dirname + '/../firestore.rules', 'utf8'));
  await env.cleanup();
})();
