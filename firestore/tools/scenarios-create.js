// N5.12 commit 4b — measurement only. A /purchase CREATE as PurchaseWrite.create
// sends it — `rev: 1`, which the hard-block now requires — by each role that
// raises requirements, in both access states. A fresh id per attempt, so every
// padded attempt is a create and never an update.
// usage: SCEN=./scenarios-create.js node purch.js
const h = require('./headroom.js');
const { UIDS, as, firebase } = h;

const NAMES = { [UIDS.admin]: 'Administrator', [UIDS.staff]: 'Manager Person', [UIDS.worker]: 'Staff Person',
  [UIDS.primaryOwner]: 'Primary Owner' };

let fresh = 0;
const create = (uid) => (env) => {
  const id = `pr_new_${++fresh}`;
  return as(env, uid).collection('purchase').doc(id).set({
    id, name: 'Rack', qty: 10, urgency: 'normal', status: 'Needed', note: 'For site',
    by: NAMES[uid], byUid: uid, t: Date.now(), updated: Date.now(), rev: 1,
    received: false, del: false, serverAt: firebase.firestore.FieldValue.serverTimestamp(), key: 'gate|SIE1000',
  });
};

const withUid = { secondOwnerUid: UIDS.additionalOwner, updatedAt: 1, updatedBy: UIDS.primaryOwner, primaryOwnerUid: UIDS.primaryOwner };
const transition = { secondOwnerUid: UIDS.additionalOwner, updatedAt: 1, updatedBy: UIDS.primaryOwner };

const S = [];
for (const [label, access] of [['uid set', withUid], ['transition (email fallback)', transition]]) {
  for (const uid of [UIDS.admin, UIDS.staff, UIDS.worker]) {
    S.push({ name: `[${label}] ${NAMES[uid]} raises a requirement (create)`, id: 'pr_unused', stored: null, access, write: create(uid) });
  }
}

module.exports = S;
