// N5.10b step 1b — measurement only; not run by CI. V8C4's purchase writes,
// as the Owner read them from V8C4 (advisor-read facts, no V8C4 file or data
// here), replayed through the rules exactly as fbPushPurchase sends them:
// a transaction, the WHOLE local row, merged, with upBy/upUid/serverAt.
const fs = require('node:fs');
const R = __dirname + '/../';
const firebase = require(R + 'node_modules/firebase/compat/app');
require(R + 'node_modules/firebase/compat/firestore');
const { createTestEnvironment, seed, as, UIDS, PEOPLE } = require(R + 'tests/helpers');
const LIMIT = /maximum of 1000 expressions/;
const BASE = 'http://127.0.0.1:8080/v1/projects/smartie-rules-test/databases/(default)/documents/';
const OWNER = { Authorization: 'Bearer owner' };
const NAME = (uid) => PEOPLE[uid].name;
const T0 = 1712000000000;

// --- rows as V8C4 leaves them -------------------------------------------------
const open = (id, creator, extra = {}) => ({ id, key: 'gateMotors|SIE1000', name: 'Rack', qty: 10, urgency: 'normal', note: '',
  status: 'Needed', by: NAME(creator), byUid: creator, upBy: NAME(creator), upUid: creator, t: T0, updated: T0, ...extra });
const rcv = (by, got) => ({ rcvQty: got, rcvBy: NAME(by), rcvUid: by, rcvAt: T0 + 1000 });
const rcvNow = (by, got) => ({ ...rcv(by, got), rcvAt: Date.now() });
const STATES = {
  open_worker: open('pr_v', UIDS.worker),
  open_staff: open('pr_v', UIDS.staff),
  open_admin: open('pr_v', UIDS.admin),
  ordered: open('pr_v', UIDS.worker, { status: 'Ordered' }),
  received_full: open('pr_v', UIDS.worker, { ...rcv(UIDS.admin, 10), received: 1, status: 'Received' }),
  received_short: open('pr_v', UIDS.worker, { ...rcv(UIDS.admin, 4), received: 1, status: 'Received' }),
  received_stocked: open('pr_v', UIDS.worker, { ...rcv(UIDS.admin, 10), received: 1, status: 'Received', stocked: 1, stockedQty: 10 }),
  cancelled: open('pr_v', UIDS.worker, { cancelledBy: NAME(UIDS.admin), cancelledUid: UIDS.admin, cancelledAt: T0 + 2000, received: 0, status: 'Cancelled' }),
  restored_stale: open('pr_v', UIDS.worker, { ...rcv(UIDS.admin, 4), received: 0, status: 'Needed', stocked: 0, stockedQty: 0 }),
  cancelled_stale: open('pr_v', UIDS.worker, { ...rcv(UIDS.admin, 4), stocked: 0, stockedQty: 0,
    cancelledBy: NAME(UIDS.admin), cancelledUid: UIDS.admin, cancelledAt: T0 + 3000, received: 0, status: 'Cancelled' }),
};
const without = (row, keys) => Object.fromEntries(Object.entries(row).filter(([k]) => !keys.includes(k)));

// --- V8C4's actions: the local row it would push ------------------------------
const ACTIONS = {
  'A create': (s, actor) => open('pr_new', actor),
  'A top-up qty+5': (s) => ({ ...s, qty: s.qty + 5 }),
  'B receive full (10)': (s, actor) => ({ ...s, ...rcvNow(actor, 10), received: 1, status: 'Received' }),
  'B receive short (4)': (s, actor) => ({ ...s, ...rcvNow(actor, 4), received: 1, status: 'Received' }),
  'B receive got=0': (s, actor) => ({ ...s, ...rcvNow(actor, 0), received: 1, status: 'Received' }),
  'B receive got>qty (12)': (s, actor) => ({ ...s, ...rcvNow(actor, 12), received: 1, status: 'Received' }),
  'B receive 2 (below stale 4)': (s, actor) => ({ ...s, ...rcvNow(actor, 2), received: 1, status: 'Received' }),
  'B receive full + stocked': (s, actor) => ({ ...s, ...rcvNow(actor, 10), received: 1, status: 'Received', stocked: 1, stockedQty: 10 }),
  'B receive short + stocked': (s, actor) => ({ ...s, ...rcvNow(actor, 4), received: 1, status: 'Received', stocked: 1, stockedQty: 4 }),
  'C status Ordered': (s) => ({ ...s, status: 'Ordered' }),
  'C status Needed': (s) => ({ ...s, status: 'Needed' }),
  'C status Cancelled': (s, actor) => ({ ...without(s, ['rcvQty', 'rcvBy', 'rcvAt', 'stocked', 'stockedQty']),
    cancelledBy: NAME(actor), cancelledUid: actor, cancelledAt: Date.now(), received: 0, status: 'Cancelled' }),
  'D restore': (s) => ({ ...without(s, ['rcvQty', 'rcvBy', 'rcvAt', 'cancelledBy', 'cancelledAt']),
    received: 0, status: 'Needed', stocked: 0, stockedQty: 0 }),
  'E edit': (s) => ({ ...s, name: 'Rack, edited', qty: 12, urgency: 'urgent', note: 'edited' }),
  'F delete': (s) => ({ ...s, del: 1 }),
};
const A = UIDS.admin, M = UIDS.staff, W = UIDS.worker;
// [action, stored state, actors V8C4 allows]
const PLAN = [
  ['A create', null, [A, M, W]],
  ['A top-up qty+5', 'open_admin', [A]], ['A top-up qty+5', 'open_staff', [M]], ['A top-up qty+5', 'open_worker', [W, M, A]],
  ...['B receive full (10)', 'B receive short (4)', 'B receive got=0', 'B receive got>qty (12)', 'B receive full + stocked', 'B receive short + stocked']
    .map((b) => [b, 'open_worker', [A, M]]),
  ['B receive full (10)', 'ordered', [A, M]], ['B receive short (4)', 'ordered', [A, M]],
  ['B receive full (10)', 'restored_stale', [A, M]], ['B receive 2 (below stale 4)', 'restored_stale', [A, M]], ['B receive short (4)', 'restored_stale', [A, M]],
  ['C status Ordered', 'open_worker', [A, M]], ['C status Cancelled', 'open_worker', [A, M]],
  ['C status Needed', 'ordered', [A, M]], ['C status Cancelled', 'ordered', [A, M]],
  ['C status Ordered', 'restored_stale', [A, M]], ['C status Cancelled', 'restored_stale', [A, M]],
  ...['received_full', 'received_short', 'received_stocked', 'cancelled', 'cancelled_stale'].map((st) => ['D restore', st, [A, M]]),
  ['E edit', 'open_worker', [A, M, W]], ['E edit', 'ordered', [A, M, W]], ['E edit', 'restored_stale', [A, M, W]],
  ['F delete', 'open_worker', [A]], ['F delete', 'received_full', [A]], ['F delete', 'cancelled', [A]],
];

// Crafted writes (not V8C4) for the pin re-check: the holes, and the uid forgeries.
const CRAFTED = [
  ['hole: Manager 4/10, status Received, received false', M, open('pr_v', W), { ...open('pr_v', W), ...rcv(M, 4), received: false, status: 'Received', rev: 1 }],
  ['hole: Manager 4/10, status Cancelled, received false', M, open('pr_v', W), { ...open('pr_v', W), ...rcv(M, 4), received: false, status: 'Cancelled', rev: 1 }],
  ['hole: Manager 4/10, received 1, status Received (V8C4 short shape)', M, open('pr_v', W), { ...open('pr_v', W), ...rcv(M, 4), received: 1, status: 'Received' }],
  ['hole: Manager status-only Cancelled on a part-received row', M, open('pr_v', W, { ...rcv(M, 4), received: false, rev: 1 }), { ...open('pr_v', W, { ...rcv(M, 4), received: false }), status: 'Cancelled', rev: 2 }],
  ['control: Manager 4/10, received true, status Received', M, open('pr_v', W), { ...open('pr_v', W), ...rcv(M, 4), received: true, status: 'Received', rev: 1 }],
  ['control: native part delivery 4/10 (Needed, false)', M, open('pr_v', W, { rev: 1 }), { ...open('pr_v', W), ...rcv(M, 4), received: false, status: 'Needed', rev: 2 }],
  ['control: native complete delivery 10/10 (Received, true)', M, open('pr_v', W, { rev: 1 }), { ...open('pr_v', W), ...rcv(M, 10), received: true, status: 'Received', rev: 2 }],
  ['native on restored row: Manager edits (rev 1)', M, STATES.restored_stale, { ...STATES.restored_stale, name: 'Rack, edited', rev: 1 }],
  ['native on restored row: its Staff creator removes it', W, STATES.restored_stale, { id: 'pr_v', del: true, deletedBy: W, delBy: NAME(W), delAt: Date.now(), rev: 1 }],
  ['native on restored row: Manager writes off at the stale 4', M, STATES.restored_stale, { id: 'pr_v', qty: 4, status: 'Received', received: true, rev: 1 }],
  ['native on restored row: Manager records 2 more (cumulative 6)', M, STATES.restored_stale, { id: 'pr_v', qty: 10, ...rcv(M, 6), rcvAt: Date.now(), status: 'Needed', received: false, rev: 1 }],
  ['native on Ordered row: Manager records 4 of 10', M, STATES.ordered, { id: 'pr_v', qty: 10, ...rcv(M, 4), rcvAt: Date.now(), status: 'Needed', received: false, rev: 1 }],
  ['native on Ordered row: Manager edits', M, STATES.ordered, { id: 'pr_v', qty: 10, name: 'Rack, edited', rev: 1 }],
  ['native on Ordered row: Staff creator edits', W, STATES.ordered, { id: 'pr_v', qty: 10, name: 'Rack, edited', rev: 1 }],
  ['uid: Manager 4/10 naming the Administrator in rcvUid', M, open('pr_v', W, { rev: 1 }), { ...open('pr_v', W), ...rcv(A, 4), received: false, status: 'Needed', rev: 2 }],
];


/** Every V8C4 push as the rules see it: a merge set of the whole local row. */
function v8c4Cases() {
  const out = [];
  for (const [action, st, actors] of PLAN) for (const actor of actors) for (const rowState of ['v8c4', 'native']) {
    if (!st && rowState === 'native') continue;
    const id = action === 'A create' ? 'pr_new' : 'pr_v';
    let stored = st ? STATES[st] : null;
    if (stored && rowState === 'native') stored = { ...stored, rev: 3 };
    out.push({ name: `v8c4 ${action} | ${st || '(none)'} | ${actor} | ${rowState}`, stored, path: 'purchase/' + id, uid: actor,
      method: 'set', options: { merge: true },
      data: () => ({ ...ACTIONS[action](stored, actor), updated: Date.now(), upBy: NAME(actor), upUid: actor,
        serverAt: firebase.firestore.FieldValue.serverTimestamp() }) });
  }
  for (const [label, actor, stored, write] of CRAFTED) {
    out.push({ name: `crafted ${label}`, stored, path: 'purchase/pr_v', uid: actor, method: 'update', options: null,
      data: () => ({ ...write, updated: Date.now(), upBy: NAME(actor), upUid: actor, serverAt: firebase.firestore.FieldValue.serverTimestamp() }) });
  }
  return out;
}
module.exports = { v8c4Cases };
