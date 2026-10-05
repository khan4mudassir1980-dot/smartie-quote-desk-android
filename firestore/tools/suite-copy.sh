#!/bin/bash
# N5.10b — measurement only; not run by CI. A copy of the emulator suite for
# the ablation and margin scripts, which rewrite its firestore.rules for each
# run. A port other than 8080 lets a second emulator run alongside the first.
# usage: suite-copy.sh <dir> [port]
set -e
F=$(cd "$(dirname "$0")/.." && pwd)
D=${1:?usage: suite-copy.sh <dir> [port]}
P=${2:-8080}
rm -rf "${D:?}"
mkdir -p "$D"
cp -r "$F/tests" "$D/tests"
cp "$F/package.json" "$D/"
ln -s "$F/node_modules" "$D/node_modules"
sed -i "s/port: 8080,/port: $P,/" "$D/tests/helpers.js"
