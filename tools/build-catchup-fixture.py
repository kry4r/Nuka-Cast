"""Writes the catch-up fixture: a playlist that ships its own guide and declares how to rewind.

The guide times are generated around the moment this runs, so the listing always has programmes that have
finished, one that is on now, and ones that have not started — which is what the catch-up and reminder
checks need.

    python tools/build-catchup-fixture.py     # writes .fixture/hls/{guide.xml,live-catchup.m3u}
"""
import io
import os
import time

OUT = os.path.join('.fixture', 'hls')
CHANNEL_ID = 'catchuptest'
PLAIN_ID = 'plaintest'


def stamp(moment):
    """XMLTV: fourteen digits with the local offset, which is what real feeds write."""
    offset = -time.timezone if not time.daylight else -time.altzone
    sign = '+' if offset >= 0 else '-'
    minutes = abs(offset) // 60
    return time.strftime('%Y%m%d%H%M%S', time.localtime(moment)) + ' %s%02d%02d' % (
        sign, minutes // 60, minutes % 60)


def main():
    now = time.time()
    # Half-hour slots: three hours behind, six ahead.
    # A full day, anchored three hours back: whichever clock the device runs on, the listing has
    # programmes that have finished, one that is on, and ones that have not started.
    first = int(now // 1800 * 1800) - 3 * 3600
    lines = ['<?xml version="1.0" encoding="UTF-8"?>', '<tv>']
    for name in (CHANNEL_ID, PLAIN_ID):
        lines.append('  <channel id="%s"><display-name>%s</display-name></channel>' % (name, name))
    for index in range(48):
        start = first + index * 1800
        end = start + 1800
        title = '节目 %02d（%s）' % (index, time.strftime('%H:%M', time.localtime(start)))
        for name in (CHANNEL_ID, PLAIN_ID):
            lines.append('  <programme start="%s" stop="%s" channel="%s">' % (stamp(start), stamp(end), name))
            lines.append('    <title>%s</title>' % title)
            lines.append('  </programme>')
    lines.append('</tv>')

    playlist = [
        '#EXTM3U url-tvg="http://10.0.2.2:8899/guide.xml"',
        '#EXTINF:-1 tvg-id="%s" tvg-name="回看测试台" group-title="测试" '
        'catchup="append" catchup-days="7",回看测试台' % CHANNEL_ID,
        'http://10.0.2.2:8899/master.m3u8',
        '#EXTINF:-1 tvg-id="%s" tvg-name="无回看台" group-title="测试",无回看台' % PLAIN_ID,
        'http://10.0.2.2:8899/master.m3u8',
        '',
    ]
    io.open(os.path.join(OUT, 'guide.xml'), 'w', encoding='utf-8', newline='\n').write('\n'.join(lines))
    io.open(os.path.join(OUT, 'live-catchup.m3u'), 'w', encoding='utf-8', newline='\n').write(
        '\n'.join(playlist))
    print('wrote .fixture/hls/guide.xml and .fixture/hls/live-catchup.m3u（%d 个节目）' % 48)


if __name__ == '__main__':
    main()
