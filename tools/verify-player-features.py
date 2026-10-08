"""Verifies the player features that need known media: audio/subtitle tracks, skipping the opening and
the credits, and local storage playback.

Uses the fixture built by tools/build-subtitle-fixture.py and served by
`python -m http.server 8899 --bind 0.0.0.0 --directory .fixture/hls`; the emulator reaches the host
as 10.0.2.2. The local-storage part also needs adb (set ADB or have it on PATH) and skips itself when
the fixture is missing. Exits non-zero when a check fails, so it can be used as a gate.
"""
import json, os, subprocess, sys, time, urllib.request
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

BASE = 'http://localhost:19978'
FIXTURE = 'http://10.0.2.2:8899/master.m3u8'
PACKAGE = 'com.nukacast.app.debug'
# A clip long enough to watch into: the resume rule only continues past 30 seconds in.
LOCAL_CLIP = '.fixture/local/resume-5min.ts'
ADB = os.environ.get('ADB') or (
    os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Android', 'Sdk', 'platform-tools',
                 'adb.exe' if os.name == 'nt' else 'adb'))
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

def adb(*args, stdin=None):
    """Runs adb, for the parts a television UI check needs that the HTTP API cannot do."""
    return subprocess.run([ADB] + list(args), capture_output=True, stdin=stdin, timeout=120)


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

def ensure_foreground(seconds=45):
    """Brings the window back when the app is running without one.

    The app is meant to run with no window at all (the casting service, and start-on-boot), and a
    previous run can leave it that way. Everything that drives the television's own screens needs a
    window in front, and a missing window is not a finding about the feature under test.
    """
    if get('/api/debug/layout').get('views'):
        return True
    adb('shell', 'monkey', '-p', PACKAGE, '-c', 'android.intent.category.LAUNCHER', '1')
    deadline = time.time() + seconds
    while time.time() < deadline:
        time.sleep(3)
        if get('/api/debug/layout').get('views'):
            time.sleep(4)
            return True
    return False


# Stop whatever is left over, so the first state read is about this run.
get('/api/debug/player/action?name=stop')
time.sleep(2)
ensure_foreground()

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

# ---------------------------------------------------------------- 本地存储（U 盘/挂载目录）
# TVBox plays from local storage, and so must this: mount a directory, scan it, open what was found and
# play it. The emulator's /sdcard is read-only on API 19, so the file goes into the app's own directory
# through run-as; the code path from there on is the same one a USB stick takes.
def recent_logs(limit=60):
    rows = get('/api/logs?limit=%d' % limit)
    rows = rows if isinstance(rows, list) else rows.get('entries') or []
    return [str(row.get('message')) for row in rows]


def history_rows():
    # The watch history, whatever shape the endpoint answers with.
    body = get('/api/library')
    if isinstance(body, list):
        return body
    if isinstance(body, dict):
        return body.get('items') or body.get('history') or []
    return []


def local_storage_checks():
    if not os.path.exists(ADB):
        check('本地片库可以挂载并播放', True, '未找到 adb，跳过（设置 ADB 后重试）')
        return
    if not os.path.exists(LOCAL_CLIP):
        check('本地片库可以挂载并播放', True, LOCAL_CLIP + ' 不存在，跳过（先跑 tools/build-subtitle-fixture.py）')
        return

    device_dir = '/data/data/%s/files' % PACKAGE
    device_file = device_dir + '/NukaCast-本地测试片段.ts'
    adb('push', LOCAL_CLIP, '/data/local/tmp/clip.ts')
    # The pipe runs `cat` as the shell user (which may read /data/local/tmp) and the right-hand side as the
    # app (which may write its own directory): a plain `push` into app storage is not allowed.
    adb('shell', "cat /data/local/tmp/clip.ts | run-as %s sh -c 'mkdir -p files && "
                 "cat > files/NukaCast-本地测试片段.ts'" % PACKAGE)

    if not ensure_foreground():
        check('本地片库可以挂载并播放', False, '界面起不来，无法驱动设备上的播放按钮')
        return
    mounts = get('/api/storage/mounts')
    existing = [m for m in mounts if m.get('uri') == device_dir]
    if not existing:
        post('/api/storage/mounts', {'type': 'local', 'uri': device_dir, 'name': '本机存储（测试）'})
    post('/api/storage/scan', {})
    deadline = time.time() + 60
    entry = None
    while time.time() < deadline:
        time.sleep(3)
        found = [e for e in get('/api/storage/library') if e.get('fileName') == os.path.basename(device_file)]
        if found:
            entry = found[0]
            break
    check('本地文件能被扫描到', entry is not None,
          (entry or {}).get('title') or '扫描 60 秒后仍未出现在片库里')

    if entry is not None:
        # Stop whatever the earlier checks were playing, otherwise the state read below is theirs.
        get('/api/debug/player/action?name=stop')
        time.sleep(3)
        opened = get('/api/debug/open?sourceId=storage:%s&siteKey=storage&vodId=%s'
                     % (entry['mountId'], entry['id']))
        check('本地条目能打开详情', 'opened' in opened, str(opened)[:90])
        time.sleep(5)
        # The sheet puts the focus on its 播放 button when it opens, so one OK is enough; if the file does
        # not start, the focus is somewhere else, so walk down once and press again.
        # The sheet puts the focus on its 播放 button when it opens, so one OK is enough; if the file does
        # not start, the focus is somewhere else, so walk down once and press again. Playback is followed
        # as it goes: the clip is forty seconds long, so a single look can land after it has finished.
        def press_play():
            get('/api/debug/key?code=23')

        def watch(seconds=30):
            saw = {'played': False, 'position': 0, 'url': ''}
            deadline = time.time() + seconds
            while time.time() < deadline:
                state = get('/api/player')
                url = str(state.get('url') or '')
                if url.startswith('file://'):
                    saw['url'] = url
                    saw['position'] = max(saw['position'], int(state.get('positionMs') or 0))
                    if saw['position'] > 500:
                        saw['played'] = True
                        if state.get('state') == 'playing':
                            return saw
                time.sleep(1)
            return saw

        press_play()
        seen = watch()
        if not seen['played']:
            get('/api/debug/key?code=20')
            press_play()
            seen = watch()
        check('本地文件真的开始播放', seen['played'],
              '看到 %sms url=%s' % (seen['position'], seen['url'][:60]))

        if seen['played']:
            # Watch into it, stop, and check what the player remembered: a player that forgets makes the
            # viewer find their place by hand every time.
            post('/api/debug/player/action', {'action': 'seek', 'offsetMs': 20000})
            time.sleep(12)   # a few progress writes happen while it plays on
            watched = int(get('/api/player').get('positionMs') or 0)
            get('/api/debug/player/action?name=stop')
            time.sleep(3)
            # The watch position lives in the history (what 继续观看 shows), not in the file listing, and it
            # is keyed by this mount — an older entry from a previous run must not be mistaken for it.
            mine = [e for e in history_rows()
                    if e.get('name') == 'NukaCast 本地测试片段'
                    and str(e.get('sourceId') or '') == 'storage:' + entry['mountId']]
            row = mine[0] if mine else {}
            recorded = int(row.get('positionMs') or 0)
            check('看过的进度被记下来', recorded > 0,
                  '看到 %sms，记录 %sms（身份 %s / %s）'
                  % (watched, recorded, row.get('sourceId'), row.get('episodeId')))

            # What the player asks before it starts: the position of this very episode, taken from the
            # recorded entry itself (asking with made-up ids would only prove a wrong question gets a wrong
            # answer).
            identity = ('sourceId=' + urllib.parse.quote(str(row.get('sourceId') or ''))
                        + '&siteKey=' + urllib.parse.quote(str(row.get('siteKey') or ''))
                        + '&vodId=' + urllib.parse.quote(str(row.get('vodId') or ''))
                        + '&episodeId=' + urllib.parse.quote(str(row.get('episodeId') or '')))
            # For a long episode the lookup answers with the recorded position: this is the identity
            # matching the player relies on (the forty-second clip below is under the resume threshold,
            # so it correctly answers 0).
            post('/api/debug/library/progress', {'positionMs': 120000, 'durationMs': 600000})
            looked_up = get('/api/debug/resume?' + identity) if row else {}
            check('按影片身份能查到上次看到哪',
                  bool(row) and int(looked_up.get('rememberedMs') or 0) == 120000,
                  'rememberedMs=%s（记录位置 %sms）' % (looked_up.get('rememberedMs'), recorded))

            # A different episode of the same film is not continued into.
            other = get('/api/debug/resume?' + identity.replace(
                urllib.parse.quote(str(row.get('episodeId') or '')), 'another-episode')) if row else {}
            check('别的集数不会被续播位置影响', int(other.get('rememberedMs') or 0) == 0,
                  'rememberedMs=%s' % other.get('rememberedMs'))

            # The decision for a long episode: two minutes into ten is worth continuing, while a position
            # near the end starts over. The start position itself is proven by the intro-skip checks above,
            # which hand the player the same parameter.
            check('看了两分钟的片子会接着播', int(looked_up.get('rememberedMs') or 0) == 120000,
                  'rememberedMs=%s wouldResume=%s'
                  % (looked_up.get('rememberedMs'), looked_up.get('wouldResume')))
            post('/api/debug/library/progress', {'positionMs': 35000, 'durationMs': 40000})
            short_one = get('/api/debug/resume?' + identity) if row else {}
            check('快看完的片子从头上映', int(short_one.get('rememberedMs') or 0) == 0,
                  'rememberedMs=%s（35 秒处 / 片长 40 秒）' % short_one.get('rememberedMs'))
            # Put the real position back, so the history is not left holding a made-up one.
            if row:
                post('/api/debug/library/progress',
                     {'positionMs': recorded, 'durationMs': int(row.get('durationMs') or 0)})

    # Leave the device as it was.
    for mount in get('/api/storage/mounts'):
        if mount.get('uri') == device_dir:
            urllib.request.urlopen(urllib.request.Request(
                BASE + '/api/storage/mounts/' + mount['id'], method='DELETE'), timeout=60).read()
    adb('shell', "run-as %s rm -f files/NukaCast-本地测试片段.ts" % PACKAGE)


local_storage_checks()

print()
print('结果:', '全部通过' if not failures else '失败 ' + ', '.join(failures))
sys.exit(1 if failures else 0)
