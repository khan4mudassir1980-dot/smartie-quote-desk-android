#!/bin/bash
# N5.10b headroom report for the working-tree rules: per document, and the
# per-request sums of the multi-document commits. cost ≈ (163 − N) × 1000/163.
cd "$(dirname "$0")"
F='punycode|trace-deprecation|GrpcConnection|PERMISSION_DENIED|evaluation error|^false for|Unable to evaluate|^Property|^Null value|^\s+at |Warning|^\s*$'
echo "== purchase, every rule padded";                  node purch.js 2>&1 | grep -Ev "$F"
echo "== users, every rule padded";                     node users.js 2>&1 | grep -Ev "$F"
for m in '/quotations/{id}' '/teamSettings/numbering'; do echo "== quotations, only $m padded"; PAD_MATCH="$m" node quotes.js 2>&1 | grep -Ev "$F"; done
for m in '/stock/{key}' '/stockMoves/{id}' '/stockPhotos/{stockDoc}'; do echo "== stock, only $m padded"; PAD_MATCH="$m" node stock.js 2>&1 | grep -Ev "$F"; done
