# Rules measurement tools (N5.10b)

Scripts that **measure** the Firestore rules against the local emulator. None
is run by CI — `npm test` runs `tests/` only — and none writes anywhere but
the emulator. No V8C4 code, rates or company data: the V8C4 payloads in
`v8cases.js` are synthetic shapes built from the advisor's description of
V8C4's purchase writes (N5.10b step 1b), with the suite's test people.

Every figure in `docs/PROJECT-STATUS.md` and the N5.10b commit messages names
the script behind it (Rule 5). Run everything from this folder, with the
emulator up in another terminal:

    cd firestore && npx firebase emulators:start --project smartie-rules-test --only firestore

Most scripts load rules into the emulator themselves and put the working
tree's `firestore.rules` back when they finish. **Run one at a time per
emulator**: two scripts loading rules into the same emulator corrupt each
other's results. For two at once, start a second emulator on another port
(a `firebase.json` with a different `emulators.firestore.port`) and use
`suite-copy.sh` for a matching suite copy.

## The expression budget

The rules engine refuses a request that evaluates more than 1,000
expressions. The emulator counts it per document; production's counting is
not confirmed, so the per-request sums are reported too. **The stop line for
any valid write is 900** (the Owner, 2026-10-05).

| Script | What it measures |
|---|---|
| `headroom.js` | The library: pads the rules with N always-true terms (`request.auth != null`, in chunks) and finds the most padding a write survives. **cost ≈ (163 − N) × 1000 / 163, about ±6.** `PAD_ALL=1` pads every allow; `PAD_MATCH='/purchase/{id}'` pads one match block (a per-document figure). |
| `budget.sh` | The headroom report: `purch.js` (every valid purchase path in `scenarios.js`), `users.js`, `quotes.js` and `stock.js` (per block, for the per-request sums of a finalise, a stock movement and a photo). |
| `purch.js` | One purchase table. `RULES=<file>` measures another rules file; `SCEN=./scenarios-ordered.js` measures the Ordered and cancel paths (commit 9). |
| `refcost.js` | The cost of a **refused** write: the padding at which "denied" turns into "limit". |
| `margin.sh` | The margin test: the purchase-side emulator tests with the `/purchase` block padded by N terms. `margin.sh ../firestore.rules 16 <suite>` passing with no limit line means every purchase write those tests make — refusals included — costs under about 900. |
| `padrules.js` | Prints padded rules (used by `margin.sh`). |
| `wrapall.js` | Puts `pad() &&` in front of every `allow` (used by `headroom.js`). |

## Same decision for every write

| Script | What it compares |
|---|---|
| `diffrules.js <old> <new> <out.jsonl>` | The differential replay: every case decided under two rules files. Cases: the suite's own purchase writes (captured, below), the V8C4 payloads and crafted writes (`v8cases.js`), and the valid paths (`scenarios.js`). `OLD_WHOLE=1` decides the old file whole; without it, the old file is split per branch (the 6fbc2bc shape only). |
| `capture_all.js` | A preload recording every purchase write a test makes, with the stored row, access document and actor before it: `N510B_CAPTURE=$PWD/writes.jsonl N510B_HELPERS=$PWD/../tests/helpers.js node --require ./capture_all.js --test --test-concurrency=1 ../tests/purchase.test.js ../tests/data.test.js`, then `CAPTURED=writes.jsonl` for `diffrules.js`. |
| `rolecases.js <old> <new>` | Every caller shape (all test people, no profile, inactive, no role, role null or a number, a mis-cased role, a string `active`) × three access states × five stored rows × eight PurchaseWrite payloads. |
| `fieldcases.js <old> <new>` | Writes whose conditions can **error** — a field deleted, null or the wrong type — since `error \|\| true` and `error ? a : b` are not the same. |
| `admincheck.js` | The Owner's check: an Administrator and the Primary Owner reopen and remove two old V8C4 rows (closed short with `received: 1`; cancelled with stale receipt fields), in both access states, with N4's reopen and with N5.10b's. Every write must be accepted. |

## Ablations

`suite-copy.sh <dir> [port]` makes a suite copy; the ablation scripts rewrite
its `firestore.rules` once per ablation and record which tests go red.

| Script | Ablations |
|---|---|
| `ablate-purchase.py <rules> <out.json> <suite> <spec,…>` | Every `pr*` function neutralised to `true`, the role forced either way, the Manager's removal and `prQtyKept` dropped. Run on two rules files: a behaviour-preserving change leaves every red set alone. |
| `ablate-ordered.py <rules> <out.json> <suite> <spec,…>` | Commit 9's clauses dropped or weakened, one at a time. |

## Probes of the evaluator

| Script | What it showed |
|---|---|
| `probe-short-circuit.js` | At the top of a rule, `false && pad()` and `true \|\| pad()` do not evaluate `pad()`. |
| `probe-deny-once.js` | A denied write is evaluated once: `pad() && false` survives the same padding as `pad()`. |

And what neither shows, measured in N5.10b commit 8b with `refcost.js`: on a
**refused** write, `a && b` and `a \|\| b` inside the purchase functions
went on to evaluate `b`; `a ? b : false` did not. That is why the purchase
rules' branch points are ternaries.

## The names in the N5.10b commit messages

The commit messages of N5.10b commits 8 to 9 name these scripts by their
working names, before they were moved here in commit 13:

| In the messages | Here |
|---|---|
| `n510b/budget.sh` | `budget.sh` |
| `abl8.py` | `ablate-purchase.py` |
| `abl9.py` | `ablate-ordered.py` |
| `SCEN=scenarios9.js` | `SCEN=./scenarios-ordered.js` |
| `denytwice.js` | `probe-deny-once.js` |
| `bisect.js` | not kept: it ran `refcost.js` over text variants of a rules file. Its figure, a refused edit 908 → 411, is `RULES=<file> node refcost.js` on `git show 547d218:firestore/firestore.rules` and on `caa835e`'s |
| `all_writes_c6.jsonl` | made by `capture_all.js` (above) on commit 6's suite; not kept — capture afresh on the suite you are comparing |

The ablation scripts' test lists are the ones each commit measured with;
`purchase-witnesses.test.js` (commit 12) is in `margin.sh`'s default list.
