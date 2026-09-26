const test = require('node:test');
const assert = require('node:assert/strict');
const { createTestEnvironment, seed, as, UIDS } = require('./helpers');

/**
 * Finalise under contention: several people issuing at once, against the
 * shipped rules.
 *
 * **This file mirrors `QuotationWriteRepository.finalise` step for step** —
 * `app/src/main/java/in/smartie/quotedesk/data/repository/QuotationWriteRepository.kt`,
 * whose KDoc names this file back. The Kotlin cannot run against the emulator
 * (the Android Firestore SDK does not run here), so this is the only place the
 * protocol meets the real rules under real contention. **A change to one that
 * is not made to the other breaks the only thing joining them**; review them
 * together. The correspondence, step by step:
 *
 *   Kotlin `finalise`            here `finalise`
 *     attempt loop, MAX_ATTEMPTS   attempt loop, maxAttempts
 *     store.isRefusal(e)           e.code === 'permission-denied'
 *     backoffFor(attempt, jitter)  backoffFor(attempt, Math.random())
 *   Kotlin `runOnce`             here `runOnce`
 *     readQuotation(draft.id)      tx.get(quotations/{draftId})
 *     exists → AlreadyIssued       exists → { reused, no }
 *     readNumbering()              tx.get(teamSettings/numbering)
 *     Numbering.format(...)        format(...)
 *     writeQuotation(set)          tx.set(quotations/{draftId})
 *     writeNumbering(update)       tx.update(numbering, {next, lastIssued})
 *
 * (The Kotlin reads `/teamSettings/quoting` too, only when a discount is
 * taken. No quotation here carries one, so neither side reads it.)
 *
 * **Why the bound works at all.** With no retry, one contender per round wins;
 * retrying immediately, the losers stay bunched and it is one winner per wave,
 * so the worst case at ten is about ten attempts. The jittered backoff spreads
 * them out so several get through per wave, and the worst case at ten falls
 * to four or five. **The bound rests on that dispersion, not on a guarantee**
 * — and exhausting it is safe: nothing is written, and the next press starts
 * again from the read-first. The scenario "for comparison only" below
 * measures the difference.
 *
 * **The emulator is one process, and its contention is an indication, not a
 * production measurement.** Nothing measured here may be quoted as measured
 * against real Firestore: how the production backend orders its own
 * optimistic-concurrency check against the security rules is not something
 * this file can observe.
 *
 * Titles, for reading this file: stored `staff` is displayed **Manager**.
 */

let testEnv;

test.before(async () => { testEnv = await createTestEnvironment(); });
test.after(async () => { await testEnv?.cleanup(); });
test.beforeEach(async () => { await seed(testEnv); });

const quotations = (db) => db.collection('quotations');
const numbering = (db) => db.collection('teamSettings').doc('numbering');

const START = 9;

async function givenCounter() {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('teamSettings').doc('numbering').set({
      prefix: 'SIE/QD', fy: '2025-26', next: START, pad: 3,
    });
  });
}

async function storedCounter() {
  let stored;
  await testEnv.withSecurityRulesDisabled(async (context) => {
    stored = (await context.firestore().collection('teamSettings').doc('numbering').get()).data();
  });
  return stored;
}

// --- the protocol, mirrored from QuotationWriteRepository.kt ---------------------------

/** `QuotationWriteRepository.MAX_ATTEMPTS`. Chosen from the measurement below. */
const MAX_ATTEMPTS = 6;

/** `QuotationWriteRepository.BACKOFF_STEP_MS` and `BACKOFF_JITTER_MS`. */
const BACKOFF_STEP_MS = 150;
const BACKOFF_JITTER_MS = 150;
const backoffFor = (attempt, jitter) =>
  BACKOFF_STEP_MS * attempt + Math.floor(BACKOFF_JITTER_MS * Math.min(1, Math.max(0, jitter)));

/** `Numbering.format`. */
const format = ({ prefix, fy, next, pad }) =>
  [prefix.trim(), fy.trim(), String(next).padStart(pad, '0')].filter((it) => it).join('/');

/** `QuotationWriteRepository.runOnce`: one whole transaction. */
function runOnce(db, person, draftId, log) {
  return db.runTransaction(async (tx) => {
    log.bodyRuns += 1;
    const quotationRef = quotations(db).doc(draftId);
    const existing = await tx.get(quotationRef);
    if (existing.exists) return { reused: true, no: existing.data().no };

    const counter = (await tx.get(numbering(db))).data();
    const no = format(counter);
    const at = Date.now();
    tx.set(quotationRef, {
      id: draftId, no, at, by: person.by, byUid: person.uid,
      tier: 'client', tierName: 'Client',
      party: { name: 'Walk-in Builders' },
      lines: [{ t: 'Site visit', s: '', u: 'each', qty: 1, rate: 1000, origRate: 1000, k: null, manual: true, amt: 1000 }],
      // No `snap`: N5.9b's caller passes null until N6, and `QuotationWrite`
      // then writes no key at all.
      gst: true, gstPct: 18, subtotal: 1000, total: 1180, status: 'Finalised',
    });
    tx.update(numbering(db), {
      next: counter.next + 1,
      lastIssued: { no, at, by: person.by, uid: person.uid, src: 'android' },
    });
    return { reused: false, no };
  });
}

/**
 * `QuotationWriteRepository.finalise`: retry the whole transaction on a
 * refusal, and on nothing else. [log] records every attempt's outcome code.
 */
async function finalise(db, person, draftId, maxAttempts, log, backoff = true) {
  for (let attempt = 1; ; attempt += 1) {
    try {
      const out = await runOnce(db, person, draftId, log);
      log.codes.push('ok');
      return { ...out, attempts: attempt };
    } catch (error) {
      log.codes.push(error.code ?? String(error));
      if (error.code !== 'permission-denied') throw error;
      if (attempt >= maxAttempts) return { refused: true, attempts: attempt };
      if (backoff) await new Promise((resolve) => setTimeout(resolve, backoffFor(attempt, Math.random())));
    }
  }
}

// --- the measurement --------------------------------------------------------------------

/** Quoting accounts, reused round-robin: one person on two phones is ordinary. */
const PEOPLE = [
  { uid: UIDS.staff, by: 'Manager Person' },
  { uid: UIDS.otherStaff, by: 'Second Manager' },
  { uid: UIDS.admin, by: 'Administrator' },
  { uid: UIDS.otherAdmin, by: 'Second Administrator' },
  { uid: UIDS.primaryOwner, by: 'Owner' },
];

/**
 * [rounds] rounds of [contenders] simultaneous finalises, each contender a
 * separate client app — a separate phone — with its own draft. Returns every
 * outcome and every attempt's code.
 */
async function race(tag, contenders, rounds, maxAttempts, backoff = true) {
  await givenCounter();
  const outcomes = [];
  const log = { codes: [], bodyRuns: 0 };
  for (let round = 0; round < rounds; round += 1) {
    const racers = Array.from({ length: contenders }, (_, i) => {
      const person = PEOPLE[i % PEOPLE.length];
      return { db: as(testEnv, person.uid), person, draftId: `qd_${tag}_r${round}_c${i}` };
    });
    const settled = await Promise.allSettled(
      racers.map((r) => finalise(r.db, r.person, r.draftId, maxAttempts, log, backoff)),
    );
    for (const s of settled) {
      outcomes.push(s.status === 'fulfilled' ? s.value : { thrown: s.reason?.code ?? String(s.reason) });
    }
  }
  return { outcomes, log };
}

const tally = (items) => items.reduce((acc, it) => ({ ...acc, [it]: (acc[it] ?? 0) + 1 }), {});

/** The property that must hold however the race resolves. */
async function assertEveryNumberOnceAndNoneSkipped(outcomes) {
  const numbers = outcomes.filter((o) => o.no && !o.reused).map((o) => o.no);
  assert.equal(new Set(numbers).size, numbers.length, `a number was issued twice: ${numbers}`);
  const expected = Array.from({ length: numbers.length }, (_, i) =>
    format({ prefix: 'SIE/QD', fy: '2025-26', next: START + i, pad: 3 }));
  assert.deepEqual([...numbers].sort(), expected.sort(), 'a number was skipped');
  assert.equal((await storedCounter()).next, START + numbers.length);
}

function report(t, label, { outcomes, log }) {
  const attempts = outcomes.filter((o) => o.attempts).map((o) => o.attempts);
  t.diagnostic(`${label}: per-attempt codes ${JSON.stringify(tally(log.codes))}`);
  t.diagnostic(`${label}: transaction bodies run ${log.bodyRuns}, attempts ${attempts.length ? attempts.reduce((a, b) => a + b, 0) : 0}`);
  t.diagnostic(`${label}: attempts per contender ${JSON.stringify(tally(attempts))}, max ${Math.max(0, ...attempts)}`);
  t.diagnostic(`${label}: outcomes ${JSON.stringify(tally(outcomes.map((o) => (o.thrown ? `thrown:${o.thrown}` : o.refused ? 'refused' : o.reused ? 'reused' : 'issued'))))}`);
}

// --- first: what does contention actually produce? ------------------------------------

test('with no self-retry, what counter contention surfaces as', async (t) => {
  // The load-bearing question, asked before any bound: V8C4 has no retry
  // loop at all. If contention is ABORTED the SDK already retries it; if the
  // rules turn a stale counter read into permission-denied, the SDK gives up
  // and only a self-retry stands between a busy minute and a hard failure.
  // maxAttempts 1 is V8C4's behaviour.
  for (const [label, contenders, rounds] of [['realistic 3', 3, 20], ['pessimistic 10', 10, 10]]) {
    const run = await race(`once${contenders}`, contenders, rounds, 1);
    report(t, `no retry, ${label}`, run);
    await assertEveryNumberOnceAndNoneSkipped(run.outcomes);

    // **The answer, pinned.** Contention surfaces as permission-denied and
    // never as aborted, and the SDK re-runs no transaction body on its own:
    // every body ran exactly once per attempt. If a future SDK or emulator
    // turns contention into ABORTED and retries it, this fails — and the
    // self-retry's reason for existing has to be looked at again.
    const codes = tally(run.log.codes);
    assert.deepEqual(Object.keys(codes).sort(), ['ok', 'permission-denied'], JSON.stringify(codes));
    assert.equal(run.log.bodyRuns, run.log.codes.length, 'the SDK re-ran a transaction body itself');
  }
});

// --- then: how many attempts do contenders need? -----------------------------------------

test('with the self-retry unbounded, every contender ends with exactly one number', async (t) => {
  // Unbounded (in practice: 50) so the measurement is attempts *needed*, not
  // attempts allowed; the bound is chosen from the pessimistic worst case.
  for (const [label, contenders, rounds] of [['realistic 3', 3, 20], ['pessimistic 10', 10, 10]]) {
    const run = await race(`free${contenders}`, contenders, rounds, 50);
    report(t, `unbounded, ${label}`, run);
    assert.ok(run.outcomes.every((o) => o.no && !o.reused), `a contender ended without a number: ${JSON.stringify(run.outcomes.filter((o) => !o.no))}`);
    assert.equal(run.outcomes.length, contenders * rounds);
    await assertEveryNumberOnceAndNoneSkipped(run.outcomes);
  }
});

// --- what the backoff is for -------------------------------------------------------------

test('for comparison only: retrying immediately, with no backoff', async (t) => {
  // **The app always backs off; this scenario exists to show why.** With no
  // retry, one contender per round wins — yet with the retry the worst case
  // at ten is a handful of attempts, not ten. The losers' retries arrive
  // spread out, so several get through per round. This measures how much of
  // that spread the jittered backoff creates on purpose, against the spread
  // that timing alone produces. Reported, not asserted, beyond the property.
  for (const [label, contenders, rounds] of [['realistic 3', 3, 20], ['pessimistic 10', 10, 10]]) {
    const run = await race(`now${contenders}`, contenders, rounds, 50, false);
    report(t, `unbounded, no backoff, ${label}`, run);
    assert.ok(run.outcomes.every((o) => o.no && !o.reused));
    await assertEveryNumberOnceAndNoneSkipped(run.outcomes);
  }
});

// --- and at the bound the app ships -----------------------------------------------------

test('at the shipped bound, what exhausts it is reported, and nothing is shared or skipped', async (t) => {
  // Reported, not asserted: how many contenders exhaust MAX_ATTEMPTS is a
  // measurement with variance, and a test that failed on a rare seventh
  // attempt would be red for a reason that is not a defect. What must hold
  // every time is asserted.
  for (const [label, contenders, rounds] of [['realistic 3', 3, 20], ['pessimistic 10', 10, 10]]) {
    const run = await race(`bound${contenders}`, contenders, rounds, MAX_ATTEMPTS);
    report(t, `bound ${MAX_ATTEMPTS}, ${label}`, run);
    t.diagnostic(`bound ${MAX_ATTEMPTS}, ${label}: exhausted ${run.outcomes.filter((o) => o.refused).length} of ${run.outcomes.length}`);
    assert.ok(run.outcomes.every((o) => o.no || o.refused), JSON.stringify(run.outcomes.filter((o) => !o.no && !o.refused)));
    await assertEveryNumberOnceAndNoneSkipped(run.outcomes);
  }
});
