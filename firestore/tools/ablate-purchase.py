# N5.10b commit 8 — measurement only; not run by CI. Ablate one clause at a time in a rules
# file, run the purchase, data and V8C4 tests in a port-shifted suite copy, and
# record which tests go red. Run once on commit 6's rules and once on commit
# 8's: a behaviour-preserving reorder leaves every ablation's red set alone.
# usage: python3 ablate-purchase.py <rules> <out.json> <suite-dir> <spec,spec,...>
import subprocess, re, json, sys, os
RULES, OUT, SUITE = open(sys.argv[1]).read(), sys.argv[2], sys.argv[3]
SPECS = sys.argv[4].split(',')
TESTS = 'tests/purchase.test.js tests/data.test.js tests/v8c4-purchase.test.js'.split()

def neutralise(text, name):
    m = re.search(r'\n    function ' + name + r'\(([^)]*)\) \{\n', text)
    if not m:
        raise SystemExit('no function ' + name)
    end = text.index('\n    }\n', m.end()) + len('\n    }\n')
    return text[:m.start()] + '\n    function %s(%s) { return true; }\n' % (name, m.group(1)) + text[end:]

def swap(text, pairs):
    for old, new in pairs:
        if text.count(old) == 1:
            return text.replace(old, new)
    raise SystemExit('no anchor among %r' % [p[0] for p in pairs])

SPECIAL = {
    # The role, forced either way, in whichever spelling the rules use.
    'adminTrue': [('prPermitted(touched(), admin())', 'prPermitted(touched(), true)'),
                  ('prPermitted(touched(), prAdmin())', 'prPermitted(touched(), true)')],
    'adminFalse': [('prPermitted(touched(), admin())', 'prPermitted(touched(), false)'),
                   ('prPermitted(touched(), prAdmin())', 'prPermitted(touched(), false)')],
    'managerTrue': [('(isCreator || staff())', '(isCreator || true)'),
                    ('(isCreator || prManager())', '(isCreator || true)')],
    'managerFalse': [('(isCreator || staff())', '(isCreator || false)'),
                     ('(isCreator || prManager())', '(isCreator || false)')],
    'mgrRemove': [('(isCreator && prRemoveKeys(changed))', 'prRemoveKeys(changed)'),
                  ('(isCreator ? prRemoveKeys(changed) : false)', 'prRemoveKeys(changed)')],
    'qtyKept': [('&& prQtyKept()', ''), ('prQtyKept() && ', '')],
}

res = {}
for spec in SPECS:
    text = RULES
    if spec == 'none':
        pass
    elif spec in SPECIAL:
        text = swap(text, SPECIAL[spec])
    else:
        text = neutralise(text, spec)
    open(os.path.join(SUITE, 'firestore.rules'), 'w').write(text)
    p = subprocess.run(['node', '--test', '--test-concurrency=1'] + TESTS, cwd=SUITE, capture_output=True, text=True)
    red = sorted(re.findall(r'^not ok \d+ - (.*)$', p.stdout, re.M))
    total = re.search(r'^# tests (\d+)', p.stdout, re.M)
    res[spec] = red
    print(spec, len(red), 'of', total.group(1) if total else '?', flush=True)
json.dump(res, open(OUT, 'w'), indent=1)
