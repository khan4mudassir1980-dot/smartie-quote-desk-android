# N5.10b commit 9 — measurement only; not run by CI. Each new clause dropped or weakened in
# turn; which tests go red. usage: python3 ablate-ordered.py <rules> <out.json> <suite> <spec,...>
import subprocess, re, json, sys, os
RULES, OUT, SUITE = open(sys.argv[1]).read(), sys.argv[2], sys.argv[3]
SPECS = sys.argv[4].split(',')
TESTS = 'tests/purchase.test.js tests/data.test.js tests/v8c4-purchase.test.js tests/purchase-ordered.test.js'.split()
def neutralise(text, name):
    m = re.search(r'\n    function ' + name + r'\(([^)]*)\) \{\n', text)
    end = text.index('\n    }\n', m.end()) + len('\n    }\n')
    return text[:m.start()] + '\n    function %s(%s) { return true; }\n' % (name, m.group(1)) + text[end:]
SUB = {
  'orderedUidPin': [("\n        && prSetUidIsCaller(changed, 'orderedUid');", ";")],
  'openOrdered': [("        && resource.data.get('status', 'Needed') in ['Needed', 'Ordered'];\n    }\n\n    function prUntouched",
                   "        && resource.data.get('status', 'Needed') == 'Needed';\n    }\n\n    function prUntouched")],
  'shortfallOrdered': [("        && resource.data.get('status', 'Needed') in ['Needed', 'Ordered']\n        && request.resource.data.qty == resource.data.rcvQty",
                        "        && resource.data.get('status', 'Needed') == 'Needed'\n        && request.resource.data.qty == resource.data.rcvQty")],
  'createKeys': [("                    && !request.resource.data.keys().hasAny(['orderedBy','orderedUid','orderedAt',\n                                                             'cancelledBy','cancelledUid','cancelledAt'])\n", "")],
  'orderAdmin': [("after == 'Ordered' ? (isAdmin && before == 'Needed'", "after == 'Ordered' ? (true && before == 'Needed'")],
  'undoOrdered': [("isAdmin && before in ['Received', 'Cancelled', 'Ordered'] && receivedAfter", "isAdmin && before in ['Received', 'Cancelled'] && receivedAfter")],
  'undoAdmin': [("isAdmin && before in ['Received', 'Cancelled', 'Ordered'] && receivedAfter", "(isAdmin || before == 'Ordered') && before in ['Received', 'Cancelled', 'Ordered'] && receivedAfter")],
  'managerNeededOnly': [("(before == 'Needed' || (isAdmin && before == 'Ordered'))", "(before in ['Needed', 'Ordered'])")],
  'cancelUntouched': [("        ? prUntouched()\n          && !request.resource.data.keys()", "        ? true\n          && !request.resource.data.keys()")],
  'cancelNoNewReceipt': [("          && !request.resource.data.keys().hasAny(['rcvQty','rcvBy','rcvUid','rcvAt'])\n          && prStampedNow(changed, 'cancelledBy'", "          && prStampedNow(changed, 'cancelledBy'")],
  'closeFromOrdered': [("after == 'Received' ? before in ['Needed', 'Ordered'] && receivedAfter", "after == 'Received' ? before == 'Needed' && receivedAfter")],
  'managerCancel': [("            || (prCancelKeys(changed) ? prManager() : false)", "            || false")],
  'cancelRole': [("            || (prCancelKeys(changed) ? prManager() : false)", "            || prCancelKeys(changed)")],
  'stampNow': [("      return changed.hasAny([at])\n        ? request", "      return true\n        ? request")],
  'stampUid': [("          && request.resource.data.get(uid, '') == mine()\n", "\n")],
  'stampBy': [("          && request.resource.data.get(by, null) is string\n          && request.resource.data.get(by, '').size() <= 80\n", "\n")],
  'cancelStatus': [("          && request.resource.data.status == 'Cancelled'\n        : false;", "\n        : false;")],
}
res = {}
for spec in SPECS:
    text = RULES
    for part in spec.split('+'):   # a+b: both at once
        if part == 'none': pass
        elif part in SUB:
            for a, b in SUB[part]:
                if text.count(a) != 1: raise SystemExit('anchor %s x%d: %r' % (part, text.count(a), a[:70]))
                text = text.replace(a, b)
        else: text = neutralise(text, part)
    open(os.path.join(SUITE, 'firestore.rules'), 'w').write(text)
    p = subprocess.run(['node', '--test', '--test-concurrency=1'] + TESTS, cwd=SUITE, capture_output=True, text=True)
    red = sorted(re.findall(r'^not ok \d+ - (.*)$', p.stdout, re.M))
    total = re.search(r'^# tests (\d+)', p.stdout, re.M)
    lim = len(re.findall('maximum of 1000 expressions', p.stdout))
    res[spec] = red
    print(spec, len(red), 'of', total.group(1) if total else '?', 'limit', lim, flush=True)
json.dump(res, open(OUT, 'w'), indent=1)
