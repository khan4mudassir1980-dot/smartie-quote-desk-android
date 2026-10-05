#!/bin/bash
# N5.10b — measurement only; not run by CI. Run the purchase-side emulator tests with the
# /purchase block padded by N terms (≈ 6.13 expressions each): no limit hit
# means every purchase write those tests make costs under 1000 − 6.13·N.
# usage: margin.sh <rules> <N> <suite-dir> [tests...]   (paths absolute or from here)
S=$(cd "$(dirname "$0")" && pwd)
R=$(realpath "$1"); N=$2; D=$(realpath "$3"); shift 3
T=${*:-tests/purchase.test.js tests/data.test.js tests/v8c4-purchase.test.js tests/purchase-ordered.test.js tests/purchase-witnesses.test.js}
cd $S && RULES=$R PAD_MATCH='/purchase/{id}' node padrules.js $N > $D/firestore.rules 2>/dev/null
cd $D && node --test --test-concurrency=1 $T > margin.log 2>&1
echo "N=$N $(basename $R): $(grep -E '^# (tests|fail)' margin.log | tr '\n' ' ') limit-lines=$(grep -c 'maximum of 1000 expressions' margin.log)"
