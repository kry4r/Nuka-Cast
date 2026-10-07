import io

p = 'tools/tv-smoke.mjs'
raw = io.open(p, 'rb').read()
print('control char present:', b'\x01' in raw)
s = raw.decode('utf-8').replace('\r\n', '\n')
lines = s.split('\n')
fixed = 0
for i, line in enumerate(lines):
    if 'matchAll' in line:
        lines[i] = ('    for (const match of text.matchAll(/<([A-Za-z0-9_]+)>([^<]*)<\\/'
                    '[A-Za-z0-9_]+>/g)) out[match[1]] = match[2];')
        fixed += 1
        print('fixed line', i + 1)
s = '\n'.join(lines).replace('\x01', '')
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('lines fixed:', fixed)
