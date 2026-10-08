import json, sys, time, urllib.request
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
def get(path, timeout=240):
    with urllib.request.urlopen('http://localhost:19978'+path, timeout=timeout) as r:
        return json.loads(r.read().decode('utf-8'))
get('/api/debug/player/action?name=stop')
get('/api/debug/navigate?page=home')
time.sleep(8)
get('/api/debug/scroll?delta=-9000')
time.sleep(2)
d = get('/api/debug/layout')
print('焦点:', d.get('focus'), '| 问题:', len(d.get('problems') or []), '| 条目:', len(d.get('views') or []))
for v in d.get('views') or []:
    t = str(v.get('text') or '')
    info = str(v.get('view') or '')
    if not t: continue
    print(f"  {info.split('[')[0][:22]:22s} {str(v.get('onScreen')):12s} {t[:46]}")
