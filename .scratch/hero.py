import json, subprocess, sys, time, urllib.request
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
adb = r"C:\Users\Nidho\AppData\Local\Android\Sdk\platform-tools\adb.exe"
def get(path, timeout=240):
    with urllib.request.urlopen('http://localhost:19978'+path, timeout=timeout) as r:
        return json.loads(r.read().decode('utf-8'))
def press(code, times=1, gap=0.5):
    for _ in range(times):
        subprocess.run([adb, 'shell', 'input', 'keyevent', str(code)], capture_output=True)
        time.sleep(gap)
get('/api/debug/player/action?name=stop')
get('/api/debug/navigate?page=home')
time.sleep(7)
for label, keys in (('RIGHT', [22]), ('RIGHT,RIGHT', [22, 22]), ('RIGHT,RIGHT,UP', [22, 22, 19]),
                    ('RIGHT,RIGHT,UP,UP', [22, 22, 19, 19]), ('LEFT,LEFT,UP', [21, 21, 19])):
    get('/api/debug/navigate?page=home')
    time.sleep(4)
    press(19, 8)  # walk to the sidebar top first
    time.sleep(0.5)
    press(19, 6)
    for code in keys:
        press(code)
        time.sleep(0.4)
    time.sleep(1.2)
    print(label, '→', get('/api/debug/layout').get('focus'))
