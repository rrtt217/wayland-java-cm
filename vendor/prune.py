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
seen, stack = set(), list(roots)
while stack:
    n = stack.pop()
    if n in seen or n not in refs:
        continue
    seen.add(n)
    stack.extend(refs[n] - seen)
drop = sorted(names - seen)
print('reachable:', len(seen), ' unreachable:', len(drop))
print('dropped sample:', ', '.join(drop[:20]))
open('vendor/dropped.txt', 'w').write('\n'.join(drop) + '\n')
