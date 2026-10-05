// N5.10b step 1 — measurement only. Every VALID /purchase update path, as the
// app's PurchaseWrite sends it, by role and by stored shape.
const h = require('./headroom.js');
const { UIDS, as, firebase } = h;
const NOW = () => Date.now();
const ts = () => firebase.firestore.FieldValue.serverTimestamp();
const del = () => firebase.firestore.FieldValue.delete();

const NAMES = { [UIDS.admin]: 'Administrator', [UIDS.staff]: 'Manager Person', [UIDS.worker]: 'Staff Person',
  [UIDS.primaryOwner]: 'Primary Owner', [UIDS.otherStaff]: 'Second Manager' };

// Stored shapes.
const appRow = (byUid, extra = {}) => ({
  id: 'pr_1', name: 'Rack', qty: 10, urgency: 'normal', status: 'Needed', note: 'For site',
  by: NAMES[byUid], byUid, t: 1712000000000, updated: 1712000000000, rev: 3,
  received: false, del: false, key: 'gate|SIE1000', upBy: NAMES[byUid], upUid: byUid,
  serverAt: new Date(1712000000000), ...extra,
});
const partlyIn = (byUid) => appRow(byUid, { rcvQty: 4, rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: 1712100000000 });
const received = (byUid) => appRow(byUid, { status: 'Received', received: true, rcvQty: 10, rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: 1712100000000 });
const v8c4 = { id: 'pr_1', name: 'Rack', qty: 10, urgency: 'normal', status: 'Needed', t: 1712000000000, updated: 1712000000000 };
const v8c4Mine = (byUid) => ({ ...v8c4, by: NAMES[byUid], byUid, note: 'x' });

// Payloads, exactly PurchaseWrite's shapes.
const base = (uid, rev, qty) => ({ id: 'pr_1', qty, updated: NOW(), rev: rev + 1, upBy: NAMES[uid], upUid: uid, serverAt: ts() });
const baseNoRev = (uid, qty) => ({ id: 'pr_1', qty, updated: NOW(), upBy: NAMES[uid], upUid: uid, serverAt: ts() });
const edit = (uid, rev) => ({ ...base(uid, rev, 12), name: 'Sliding gate rack', urgency: 'urgent', note: 'Kandivali' });
const urgency = (uid, rev) => ({ ...base(uid, rev, 10), urgency: 'critical' });
const receivePart = (uid, rev, total) => ({ ...base(uid, rev, 10), status: 'Needed', received: false,
  rcvQty: total, rcvBy: NAMES[uid], rcvUid: uid, rcvAt: NOW() });
const receiveAll = (uid, rev) => ({ ...base(uid, rev, 10), status: 'Received', received: true,
  rcvQty: 10, rcvBy: NAMES[uid], rcvUid: uid, rcvAt: NOW() });
const shortfall = (uid, rev) => ({ ...base(uid, rev, 4), status: 'Received', received: true });
const softDelete = (uid, rev) => ({ ...base(uid, rev, 10), del: true, deletedBy: uid, delBy: NAMES[uid], delAt: NOW() });
const reopen = (uid, rev) => ({ ...base(uid, rev, 10), status: 'Needed', received: false,
  rcvQty: del(), rcvBy: del(), rcvUid: del(), rcvAt: del() });

const withUid = { secondOwnerUid: UIDS.additionalOwner, updatedAt: 1, updatedBy: UIDS.primaryOwner, primaryOwnerUid: UIDS.primaryOwner };
const transition = { secondOwnerUid: UIDS.additionalOwner, updatedAt: 1, updatedBy: UIDS.primaryOwner };

const upd = (uid, data) => (env) => as(env, uid).collection('purchase').doc('pr_1').update(data);

const S = [];
const add = (name, stored, uid, data, access = withUid) => S.push({ name, id: 'pr_1', stored, access, write: upd(uid, data) });

for (const [label, access] of [['uid set', withUid], ['transition (email fallback)', transition]]) {
  add(`[${label}] Administrator edits a Manager's requirement`, appRow(UIDS.staff), UIDS.admin, edit(UIDS.admin, 3), access);
  add(`[${label}] Administrator reopens a received requirement`, received(UIDS.staff), UIDS.admin, reopen(UIDS.admin, 3), access);
  add(`[${label}] Primary Owner edits`, appRow(UIDS.staff), UIDS.primaryOwner, edit(UIDS.primaryOwner, 3), access);
  add(`[${label}] Manager edits somebody's untouched requirement`, appRow(UIDS.worker), UIDS.staff, edit(UIDS.staff, 3), access);
  add(`[${label}] Manager changes only the urgency`, appRow(UIDS.worker), UIDS.staff, urgency(UIDS.staff, 3), access);
  add(`[${label}] Manager records a part delivery on somebody's`, appRow(UIDS.worker), UIDS.staff, receivePart(UIDS.staff, 3, 4), access);
  add(`[${label}] Manager records a second part delivery`, partlyIn(UIDS.worker), UIDS.staff, receivePart(UIDS.staff, 3, 7), access);
  add(`[${label}] Manager records the whole delivery`, appRow(UIDS.worker), UIDS.staff, receiveAll(UIDS.staff, 3), access);
  add(`[${label}] Manager writes off a shortfall`, partlyIn(UIDS.worker), UIDS.staff, shortfall(UIDS.staff, 3), access);
  add(`[${label}] Manager removes their own untouched requirement`, appRow(UIDS.staff), UIDS.staff, softDelete(UIDS.staff, 3), access);
  add(`[${label}] Manager edits their own untouched requirement`, appRow(UIDS.staff), UIDS.staff, edit(UIDS.staff, 3), access);
  add(`[${label}] Staff edits their own untouched requirement`, appRow(UIDS.worker), UIDS.worker, edit(UIDS.worker, 3), access);
  add(`[${label}] Staff changes only the urgency of their own`, appRow(UIDS.worker), UIDS.worker, urgency(UIDS.worker, 3), access);
  add(`[${label}] Staff removes their own untouched requirement`, appRow(UIDS.worker), UIDS.worker, softDelete(UIDS.worker, 3), access);
  add(`[${label}] Staff records a part delivery on their own`, appRow(UIDS.worker), UIDS.worker, receivePart(UIDS.worker, 3, 4), access);
  add(`[${label}] Staff records a second part delivery on their own`, partlyIn(UIDS.worker), UIDS.worker, receivePart(UIDS.worker, 3, 7), access);
  add(`[${label}] Staff records the whole delivery on their own`, appRow(UIDS.worker), UIDS.worker, receiveAll(UIDS.worker, 3), access);
  add(`[${label}] Staff writes off a shortfall on their own`, partlyIn(UIDS.worker), UIDS.worker, shortfall(UIDS.worker, 3), access);
  add(`[${label}] Manager delivers on a V8C4 row with no rev or creator`, v8c4, UIDS.staff, { ...baseNoRev(UIDS.staff, 10), rev: 1, status: 'Needed', received: false, rcvQty: 4, rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: NOW() }, access);
  add(`[${label}] Staff removes their own V8C4-shaped row`, v8c4Mine(UIDS.worker), UIDS.worker, { ...baseNoRev(UIDS.worker, 10), rev: 1, del: true, deletedBy: UIDS.worker, delBy: 'Staff Person', delAt: NOW() }, access);
}

module.exports = S;
