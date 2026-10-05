// N5.10b — the Owner's check before commit 6, no code change: can an
// Administrator (and the Owner) still reopen and remove two old V8C4 rows,
// sending exactly what PurchaseWrite.reopen / softDelete send?
const R = __dirname + '/../';
const firebase = require(R + 'node_modules/firebase/compat/app');
require(R + 'node_modules/firebase/compat/firestore');
const { createTestEnvironment, seed, as, UIDS, PEOPLE } = require(R + 'tests/helpers');
const del = () => firebase.firestore.FieldValue.delete();
const v8c4 = {
  id: 'pr_old', key: 'gate|G1', name: 'Rack', qty: 10, urgency: 'normal', note: '',
  by: 'Staff Person', byUid: UIDS.worker, upBy: 'Staff Person', upUid: UIDS.worker,
  t: 1712000000000, updated: 1712000000000,
};
const ROWS = {
  'closed short by V8C4 (Received, received: 1, 4 of 10, nothing written off)': { ...v8c4, status: 'Received', received: 1,
    rcvQty: 4, rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: 1712100000000 },
  'cancelled by V8C4, stale receipt fields left by the merge': { ...v8c4, status: 'Cancelled', received: 0,
    cancelledBy: 'Administrator', cancelledUid: UIDS.admin, cancelledAt: 1712300000000,
    rcvQty: 4, rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: 1712100000000, stocked: 0, stockedQty: 0 },
};
// PurchaseWrite.base(): id, qty (stored), updated, rev = stored.revision + 1 (no rev stored → 1), upBy, upUid, serverAt.
const base = (uid) => ({ id: 'pr_old', qty: 10, updated: Date.now(), rev: 1, upBy: PEOPLE[uid].name, upUid: uid,
  serverAt: firebase.firestore.FieldValue.serverTimestamp() });
const WRITES = {
  reopen: (uid) => ({ ...base(uid), status: 'Needed', received: false, rcvQty: del(), rcvBy: del(), rcvUid: del(), rcvAt: del() }),
  // N5.10b commit 10's reopen also removes the cancel and Ordered stamps.
  reopen10: (uid) => ({ ...base(uid), status: 'Needed', received: false, rcvQty: del(), rcvBy: del(), rcvUid: del(), rcvAt: del(),
    cancelledBy: del(), cancelledUid: del(), cancelledAt: del(), orderedBy: del(), orderedUid: del(), orderedAt: del() }),
  remove: (uid) => ({ ...base(uid), del: true, deletedBy: uid, delBy: PEOPLE[uid].name, delAt: Date.now() }),
};
(async () => {
  const env = await createTestEnvironment();
  for (const withUid of [true, false]) {
    await seed(env, { withPrimaryOwnerUid: withUid });
    for (const [rowName, row] of Object.entries(ROWS)) for (const [w, make] of Object.entries(WRITES)) for (const uid of [UIDS.admin, UIDS.primaryOwner]) {
      await env.withSecurityRulesDisabled((c) => c.firestore().collection('purchase').doc('pr_old').set(row));
      let out;
      try { await as(env, uid).collection('purchase').doc('pr_old').update(make(uid)); out = 'ACCEPTED'; }
      catch (e) { out = 'REFUSED ' + (/maximum of 1000/.test(e.message) ? '(limit)' : '') ; }
      console.log(out.padEnd(9), `[${withUid ? 'uid set' : 'transition'}]`, PEOPLE[uid].name.padEnd(14), w.padEnd(7), '|', rowName);
    }
  }
  await env.cleanup();
})();
