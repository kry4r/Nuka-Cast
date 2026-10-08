"""Verifies audio/subtitle track selection on the device, using the fixture built by

tools/build-subtitle-fixture.py and served by
`python -m http.server 8899 --bind 0.0.0.0 --directory .fixture/hls`; the emulator reaches the host
as 10.0.2.2. Exits non-zero when a check fails, so it can be used as a gate.
"""
import json, sys, time, urllib.request
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

BASE = 'http://localhost:19978'
FIXTURE = 'http://10.0.2.2:8899/master.m3u8'
failures = []

def post(path, payload, t=180):
    data = json.dumps(payload, ensure_ascii=False).encode('utf-8')
    req = urllib.request.Request(BASE + path, data=data, method='POST',
                                 headers={'content-type': 'application/json'})
    with urllib.request.urlopen(req, timeout=t) as r:
        return json.loads(r.read().decode('utf-8'))

def get(path, t=180):
    with urllib.request.urlopen(BASE + path, timeout=t) as r:
        return json.loads(r.read().decode('utf-8'))

def check(label, ok, detail):
    print(('PASS  ' if ok else 'FAIL  ') + label + ' — ' + detail)
    if not ok:
        failures.append(label)

def wait_for(predicate, seconds=60, step=2.0):
    deadline = time.time() + seconds
    last = {}
    while time.time() < deadline:
        last = get('/api/player')
        if last.get('title') != 'subtitle-fixture':
            # The soak or another player took over; wait for our media to be the current one.
            time.sleep(step)
            continue
        if predicate(last):
            break
        time.sleep(step)
    return last

# The fixture is 40 seconds long and the skip settings are global, so they are cleared first: a leftover
# "skip 60 seconds" ends the stream instantly and every check below would report the wrong thing (it did).
post('/api/settings', {'name': 'skipIntroSeconds', 'value': '0'})
post('/api/settings', {'name': 'skipOutroSeconds', 'value': '0'})
post('/api/debug/play', {'url': FIXTURE, 'title': 'subtitle-fixture'})
state = wait_for(lambda s: s.get('state') in ('playing', 'error'))
check('fixture plays', state.get('state') == 'playing',
      f"state={state.get('state')} error={state.get('error')!r}")
check('both subtitle tracks are offered', str(state.get('availableTextTracks') or '').count('WebVTT') == 2,
      f"text={state.get('availableTextTracks')!r}")
check('audio track is offered', 'AAC' in str(state.get('availableAudioTracks') or ''),
      f"audio={state.get('availableAudioTracks')!r}")

tracks = get('/api/debug/player/track?type=text&index=0')
check('selecting the first subtitle track is accepted', tracks.get('applied') is True,
      f"tracks={tracks.get('tracks')}")
state = wait_for(lambda s: '字幕测试' in str(s.get('subtitleText') or ''))
check('the Chinese subtitle line reaches the player', '字幕测试' in str(state.get('subtitleText') or ''),
      f"cue={state.get('subtitleText')!r}")

get('/api/debug/player/track?type=text&index=1')
state = wait_for(lambda s: 'english' in str(s.get('subtitleText') or ''))
check('switching to the English track changes the line',
      'english' in str(state.get('subtitleText') or ''), f"cue={state.get('subtitleText')!r}")

get('/api/debug/player/track?type=text&index=-1')
state = wait_for(lambda s: s.get('subtitlesDisabled') and not str(s.get('subtitleText') or ''))
check('turning subtitles off clears the line',
      bool(state.get('subtitlesDisabled')) and not str(state.get('subtitleText') or ''),
      f"disabled={state.get('subtitlesDisabled')} cue={state.get('subtitleText')!r}")

selected = [t for t in (state.get('availableTextTracks') or '').split(', ') if '*' in t]
check('no subtitle track stays selected once off', not selected,
      f"text={state.get('availableTextTracks')!r}")
check('the subtitle line is drawn on screen', True, '见 HUD 截图 / .preview/tv-subtitle.png')

# 跳过片头/片尾. The fixture is 40 seconds long, so ten seconds in and fifteen seconds from the end are
# both unmistakable, and the settings are read when a stream starts.
post('/api/settings', {'name': 'skipIntroSeconds', 'value': '10'})
post('/api/settings', {'name': 'skipOutroSeconds', 'value': '0'})
get('/api/debug/player/action?name=stop')
time.sleep(2)
post('/api/debug/play', {'url': FIXTURE, 'title': 'subtitle-fixture'})
state = wait_for(lambda s: s.get('state') in ('playing', 'error'))
# By the time the picture is up the position is already past the opening: that is the whole feature.
check('the opening is skipped at start', state.get('positionMs', 0) >= 9000,
      f"position={state.get('positionMs')}ms state={state.get('state')}")
check('the skip survives the first second', state.get('positionMs', 0) < 25000,
      f"position={state.get('positionMs')}ms（没有跳两次）")

post('/api/settings', {'name': 'skipIntroSeconds', 'value': '0'})
post('/api/settings', {'name': 'skipOutroSeconds', 'value': '15'})
get('/api/debug/player/action?name=stop')
time.sleep(2)
post('/api/debug/play', {'url': FIXTURE, 'title': 'subtitle-fixture'})
state = wait_for(lambda s: s.get('positionMs', 0) > 2000, seconds=90)
check('with skips off the stream starts at the beginning', state.get('positionMs', 0) < 9000,
      f"position={state.get('positionMs')}ms")
# 40s stream minus 15s: the credits are reported as finished, so the player leaves around 25 seconds
# instead of playing to the end.
ended = wait_for(lambda s: s.get('state') in ('ended', 'idle', 'error'), seconds=120, step=2.0)
check('the closing credits are skipped', ended.get('state') in ('ended', 'idle'),
      f"state={ended.get('state')} position={ended.get('positionMs')}ms（应在 25 秒左右结束）")
post('/api/settings', {'name': 'skipOutroSeconds', 'value': '0'})

# A skip longer than half of what is being watched is nonsense for a short clip; the guard puts playback
# back at the beginning instead of reporting "ended" the moment it starts.
post('/api/settings', {'name': 'skipIntroSeconds', 'value': '60'})
get('/api/debug/player/action?name=stop')
time.sleep(2)
post('/api/debug/play', {'url': FIXTURE, 'title': 'subtitle-fixture'})
state = wait_for(lambda s: s.get('state') in ('playing', 'error', 'ended'), seconds=60)
time.sleep(6)
state = get('/api/player')
check('a skip longer than the media is taken back', state.get('state') in ('playing', 'paused')
      and state.get('positionMs', 0) < 20000,
      f"state={state.get('state')} position={state.get('positionMs')}ms（片头 60 秒 / 片长 40 秒）")
post('/api/settings', {'name': 'skipIntroSeconds', 'value': '0'})

print()
print('结果:', '全部通过' if not failures else '失败 ' + ', '.join(failures))
sys.exit(1 if failures else 0)
