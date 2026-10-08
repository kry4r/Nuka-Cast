#!/usr/bin/env python3
"""Builds the subtitle fixture used to verify audio/subtitle track support on the device.

Why a fixture at all: a TV has to show the subtitle track a viewer picks, and public test streams
either have no subtitles or several megabytes of them. This one is four segments of the well-known
mux.dev test stream plus two WebVTT subtitle groups, so the whole thing is ~1.4MB and its cue text is
known in advance ("NukaCast 字幕测试：第一句/第二句", "english subtitle track").

Usage (from the repo root):

    python tools/build-subtitle-fixture.py                 # writes .fixture/hls
    python -m http.server 8899 --bind 0.0.0.0 --directory .fixture/hls
    python tools/verify-subtitle-tracks.py                 # drives the device through the debug API

The emulator reaches the host as 10.0.2.2, which is why the fixture is served over HTTP instead of
being pushed to the device: an API 19 emulator has no writable /sdcard, and HLS over HTTP is what the
app does in real use anyway.
"""
import io
import os
import urllib.request

BASE = "https://test-streams.mux.dev/x36xhzz/url_2/"
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), ".fixture", "hls")
SEGMENTS = 4

MASTER = """#EXTM3U
#EXT-X-VERSION:3
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="中文",LANGUAGE="zh-Hans",DEFAULT=YES,AUTOSELECT=YES,URI="subs-zh.m3u8"
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="en",AUTOSELECT=NO,URI="subs-en.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=300000,RESOLUTION=320x184,CODECS="avc1.42c015,mp4a.40.2",SUBTITLES="subs"
media.m3u8
"""

# HLS subtitles are a playlist of WebVTT segments, not a bare .vtt file: pointing #EXT-X-MEDIA at a
# .vtt is answered with ERROR_CODE_PARSING_MANIFEST_MALFORMED, which is how this was found.
SUBS = """#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:40
#EXT-X-PLAYLIST-TYPE:VOD
#EXTINF:40.000,
{segment}
#EXT-X-ENDLIST
"""

CUE_ZH = """WEBVTT

00:00:01.000 --> 00:00:19.000
NukaCast 字幕测试：第一句

00:00:19.000 --> 00:00:39.000
NukaCast 字幕测试：第二句
"""

CUE_EN = """WEBVTT

00:00:01.000 --> 00:00:39.000
english subtitle track
"""


def write(name, text):
    path = os.path.join(OUT, name)
    io.open(path, "w", encoding="utf-8", newline="\n").write(text)
    print("wrote", os.path.relpath(path))


def fetch(relative, name):
    path = os.path.join(OUT, name)
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with urllib.request.urlopen(BASE + relative, timeout=120) as response:
        open(path, "wb").write(response.read())
    print("downloaded", relative, os.path.getsize(path), "bytes")


def main():
    os.makedirs(OUT, exist_ok=True)
    with urllib.request.urlopen(BASE + "193039199_mp4_h264_aac_ld_7.m3u8", timeout=60) as response:
        variant = response.read().decode("utf-8")
    media = [line.strip() for line in variant.splitlines() if line and not line.startswith("#")]
    keep = media[:SEGMENTS]
    lines = ["#EXTM3U", "#EXT-X-VERSION:3", "#EXT-X-PLAYLIST-TYPE:VOD",
             "#EXT-X-TARGETDURATION:10", "#EXT-X-MEDIA-SEQUENCE:0"]
    for index, relative in enumerate(keep):
        name = "seg/%03d.ts" % index
        fetch(relative, name)
        lines.append("#EXTINF:10.000,")
        lines.append(name)
    lines.append("#EXT-X-ENDLIST")
    write("media.m3u8", "\n".join(lines) + "\n")
    write("master.m3u8", MASTER)
    write("subs-zh.m3u8", SUBS.format(segment="sub-zh.vtt"))
    write("subs-en.m3u8", SUBS.format(segment="sub-en.vtt"))
    write("sub-zh.vtt", CUE_ZH)
    write("sub-en.vtt", CUE_EN)
    print("\nserve with: python -m http.server 8899 --bind 0.0.0.0 --directory",
          os.path.relpath(OUT))


if __name__ == "__main__":
    main()
