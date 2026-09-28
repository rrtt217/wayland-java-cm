import os, re, subprocess, json
GEN = 'vendor/gen-src'
files = {}
for r, _, fs in os.walk(GEN):
    for f in fs:
        if f.endswith('.java'):
            files[f[:-5]] = os.path.join(r, f)
names = set(files)
pat = {n: re.compile(r'(?<![A-Za-z0-9_])' + re.escape(n) + r'(?![A-Za-z0-9_])') for n in names}
def mentions(t):
    return {n for n, p in pat.items() if p.search(t)}
refs = {n: mentions(open(p, encoding='utf-8', errors='ignore').read()) for n, p in files.items()}

roots = set()
for r, _, fs in os.walk('app/src/main/java'):
    for f in fs:
        if f.endswith('.java'):
            roots |= mentions(open(os.path.join(r, f)).read())
for jar in ['stubs-client', 'stubs-shared']:
    blob = subprocess.run(['unzip', '-p', 'vendor/libs/%s.jar' % jar], capture_output=True).stdout
    roots |= mentions(blob.decode('latin-1'))

print('generated classes:', len(names))
print('roots from app+runtime:', len(roots))
def closure(seed):
    seen, stack = set(), list(seed)
    while stack:
        n = stack.pop()
        if n in seen or n not in refs:
            continue
        seen.add(n)
        stack.extend(refs[n] - seen)
    return seen

seen = closure(roots)

# Enums are protocol *vocabulary*, not dead code: an application needs them to
# interpret supported_feature / failed(cause) / protocol errors. Static
# reference analysis cannot see that, and without this rule the whole
# wp_color_manager_v1::feature enum disappears the moment nobody mentions it.
# Keep every enum whose protocol still has a surviving proxy class.
kept_prefixes = {n[:-len('Proxy')] for n in seen if n.endswith('Proxy')}
vocabulary = {n for n in names - seen if any(n.startswith(p) for p in kept_prefixes)}
print('protocol enums kept as vocabulary:', len(vocabulary))
seen = closure(seen | vocabulary)
drop = sorted(names - seen)
print('reachable:', len(seen), ' unreachable:', len(drop))

# Prune in place: this script owns the state of vendor/gen-src.
for n in drop:
    os.remove(files[n])
for root, _dirs, _files in os.walk(GEN, topdown=False):
    if not os.listdir(root):
        os.rmdir(root)
print('removed', len(drop), 'files;', len(seen), 'kept in', GEN)
