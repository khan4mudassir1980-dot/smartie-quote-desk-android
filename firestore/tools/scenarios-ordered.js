// N5.10b commit 9 — measurement only; not run by CI. The valid paths commit 9 adds or
// changes: Ordered, the cancel, the locks — as PurchaseWrite sends them.
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

const ordered = (byUid, extra = {}) => appRow(byUid, { status: 'Ordered', orderedBy: 'Administrator', orderedUid: UIDS.admin, orderedAt: 1712050000000, ...extra });
const order = (uid, rev) => ({ ...base(uid, rev, 10), status: 'Ordered', orderedBy: NAMES[uid], orderedUid: uid, orderedAt: NOW() });
const unorder = (uid, rev) => ({ ...base(uid, rev, 10), status: 'Needed', orderedBy: del(), orderedUid: del(), orderedAt: del() });
const cancel = (uid, rev) => ({ ...base(uid, rev, 10), status: 'Cancelled', cancelledBy: NAMES[uid], cancelledUid: uid, cancelledAt: NOW() });
const note = (uid, rev) => ({ ...base(uid, rev, 10), note: 'Kandivali' });
const receivePartOrdered = (uid, rev, total) => ({ ...receivePart(uid, rev, total), status: 'Ordered' });
const reopenAll = (uid, rev) => ({ ...reopen(uid, rev), cancelledBy: del(), cancelledUid: del(), cancelledAt: del(),
  orderedBy: del(), orderedUid: del(), orderedAt: del() });
const cancelledRow = (byUid) => ordered(byUid, { status: 'Cancelled', cancelledBy: 'Administrator', cancelledUid: UIDS.admin, cancelledAt: 1712060000000 });

for (const [label, access] of [['uid set', withUid], ['transition (email fallback)', transition]]) {
  add(`[${label}] Administrator orders`, appRow(UIDS.worker), UIDS.admin, order(UIDS.admin, 3), access);
  add(`[${label}] Administrator orders a part-received one`, partlyIn(UIDS.worker), UIDS.admin, order(UIDS.admin, 3), access);
  add(`[${label}] Administrator takes the order back`, ordered(UIDS.worker), UIDS.admin, unorder(UIDS.admin, 3), access);
  add(`[${label}] Manager cancels somebody's`, appRow(UIDS.worker), UIDS.staff, cancel(UIDS.staff, 3), access);
  add(`[${label}] Manager cancels their own`, appRow(UIDS.staff), UIDS.staff, cancel(UIDS.staff, 3), access);
  add(`[${label}] Administrator cancels an Ordered one`, ordered(UIDS.worker), UIDS.admin, cancel(UIDS.admin, 3), access);
  add(`[${label}] Administrator reopens a cancelled one, every stamp removed`, cancelledRow(UIDS.worker), UIDS.admin, reopenAll(UIDS.admin, 3), access);
  add(`[${label}] Manager records a part delivery on an Ordered one`, ordered(UIDS.worker), UIDS.staff, receivePartOrdered(UIDS.staff, 3, 4), access);
  add(`[${label}] Manager records the whole delivery on an Ordered one`, ordered(UIDS.worker), UIDS.staff, receiveAll(UIDS.staff, 3), access);
  add(`[${label}] Manager writes off on an Ordered one`, ordered(UIDS.worker, { rcvQty: 4, rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: 1712100000000 }), UIDS.staff, shortfall(UIDS.staff, 3), access);
  add(`[${label}] Staff creator writes off on their own Ordered one`, ordered(UIDS.worker, { rcvQty: 4, rcvBy: 'Manager Person', rcvUid: UIDS.staff, rcvAt: 1712100000000 }), UIDS.worker, shortfall(UIDS.worker, 3), access);
  add(`[${label}] Staff creator records the whole delivery on their own Ordered one`, ordered(UIDS.worker), UIDS.worker, receiveAll(UIDS.worker, 3), access);
  add(`[${label}] Manager changes the note on an Ordered one`, ordered(UIDS.worker), UIDS.staff, note(UIDS.staff, 3), access);
  add(`[${label}] Staff creator changes the urgency on their own Ordered one`, ordered(UIDS.worker), UIDS.worker, urgency(UIDS.worker, 3), access);
  add(`[${label}] Administrator changes what and how many on an Ordered one`, ordered(UIDS.worker), UIDS.admin, edit(UIDS.admin, 3), access);
  add(`[${label}] Administrator removes an Ordered one`, ordered(UIDS.worker), UIDS.admin, softDelete(UIDS.admin, 3), access);
}

module.exports = S;
