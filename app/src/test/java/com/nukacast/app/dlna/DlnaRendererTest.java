package com.nukacast.app.dlna;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The transport rules a control point relies on: what Play/Stop/Seek do, what the replies contain, and
 * which requests are refused.
 */
public class DlnaRendererTest {
    /** Records what the renderer asked the app to do. */
    private static final class FakeSink implements DlnaRenderer.Sink {
        final List<String> calls = new ArrayList<String>();
        int position;
        int duration = 3600_000;

        @Override public void play(String url, String title) {
            calls.add("play " + url + " title=" + title);
        }

        @Override public void pause() { calls.add("pause"); }

        @Override public void resume() { calls.add("resume"); }

        @Override public void stop() { calls.add("stop"); }

        @Override public void seekTo(int positionMs) {
            position = positionMs;
            calls.add("seek " + positionMs);
        }

        @Override public int positionMs() { return position; }

        @Override public int durationMs() { return duration; }

        @Override public void setVolume(int volume0To100) { calls.add("volume " + volume0To100); }
    }

    private final FakeSink sink = new FakeSink();
    private final DlnaRenderer renderer = new DlnaRenderer(sink);
    private final DlnaService service = new DlnaService(renderer);

    private DlnaService.Result call(String action, String... args) {
        StringBuilder body = new StringBuilder("<s:Envelope><s:Body><u:").append(action).append(">");
        for (int i = 0; i + 1 < args.length; i += 2) {
            body.append('<').append(args[i]).append('>').append(args[i + 1])
                    .append("</").append(args[i]).append('>');
        }
        body.append("</u:").append(action).append("></s:Body></s:Envelope>");
        return service.handle(SoapMessage.parse(
                "\"urn:schemas-upnp-org:service:AVTransport:1#" + action + "\"", body.toString()));
    }

    private String bodyOf(DlnaService.Result result) {
        return new String(result.body, java.nio.charset.Charset.forName("UTF-8"));
    }

    @Test
    public void startsStoppedAndWithoutMedia() {
        assertEquals(DlnaRenderer.NO_MEDIA, renderer.state());
        assertEquals("0:00:00", DlnaRenderer.formatTime(0));
    }

    @Test
    public void setUriThenPlayStartsTheApp() {
        assertEquals(200, call("SetAVTransportURI", "InstanceID", "0",
                "CurrentURI", "http://192.168.5.9:8080/电影.m3u8",
                // Metadata travels escaped inside the envelope, as every real control point sends it.
                "CurrentURIMetaData",
                "&lt;DIDL-Lite&gt;&lt;item&gt;&lt;dc:title&gt;测试影片&lt;/dc:title&gt;&lt;/item&gt;"
                        + "&lt;/DIDL-Lite&gt;").status);
        assertEquals(DlnaRenderer.STOPPED, renderer.state());
        assertTrue(sink.calls.contains("stop"));

        call("Play", "InstanceID", "0", "Speed", "1");
        assertEquals(DlnaRenderer.PLAYING, renderer.state());
        assertTrue("calls were " + sink.calls,
                sink.calls.contains("play http://192.168.5.9:8080/电影.m3u8 title=测试影片"));

        // Pause and resume must not reload the media.
        call("Pause", "InstanceID", "0");
        assertEquals(DlnaRenderer.PAUSED, renderer.state());
        assertTrue(sink.calls.contains("pause"));
        call("Play", "InstanceID", "0");
        assertTrue(sink.calls.contains("resume"));
    }

    @Test
    public void refusesAnythingThatIsNotHttp() {
        DlnaService.Result refused = call("SetAVTransportURI", "InstanceID", "0",
                "CurrentURI", "file:///sdcard/a.mp4");
        assertEquals(500, refused.status);
        assertTrue(bodyOf(refused).contains("714"));
        assertTrue(DlnaService.isSupportedUri("https://example.com/a.mp4"));
        assertFalse(DlnaService.isSupportedUri("rtsp://example.com/a.mp4"));
    }

    @Test
    public void reportsTheStatePositionAndDuration() {
        call("SetAVTransportURI", "InstanceID", "0", "CurrentURI", "http://x/a.mp4");
        call("Play", "InstanceID", "0");
        sink.position = 65_000;
        String position = bodyOf(call("GetPositionInfo", "InstanceID", "0"));
        assertTrue(position.contains("<TrackDuration>1:00:00</TrackDuration>"));
        assertTrue(position.contains("<TrackURI>"));
        assertTrue(DlnaRenderer.formatTime(65_000).equals("0:01:05"));

        String info = bodyOf(call("GetTransportInfo", "InstanceID", "0"));
        assertTrue(info.contains("<CurrentTransportState>PLAYING</CurrentTransportState>"));
        assertTrue(info.contains("<CurrentTransportStatus>OK</CurrentTransportStatus>"));

        String media = bodyOf(call("GetMediaInfo", "InstanceID", "0"));
        assertTrue(media.contains("<CurrentURI>http://x/a.mp4</CurrentURI>"));
        assertTrue(media.contains("<NrTracks>1</NrTracks>"));
    }

    @Test
    public void seeksUsingTheRelTimeUnit() {
        call("SetAVTransportURI", "InstanceID", "0", "CurrentURI", "http://x/a.mp4");
        call("Play", "InstanceID", "0");
        assertEquals(200, call("Seek", "InstanceID", "0", "Unit", "REL_TIME", "Target", "0:10:30").status);
        assertTrue(sink.calls.contains("seek 630000"));
        // Track-based seeking is refused rather than silently ignored.
        assertEquals(500, call("Seek", "InstanceID", "0", "Unit", "TRACK_NR", "Target", "2").status);
    }

    @Test
    public void parsesTimesInEveryFormControlPointsUse() {
        assertEquals(6_300_000, DlnaService.parseTime("1:45:00"));
        assertEquals(65_000, DlnaService.parseTime("01:05"));
        assertEquals(3_000, DlnaService.parseTime("0:00:03.250"));
        assertEquals(0, DlnaService.parseTime(""));
        assertEquals(0, DlnaService.parseTime("garbage"));
    }

    @Test
    public void volumeAndMuteGoToTheApp() {
        assertTrue(bodyOf(call("GetVolume", "InstanceID", "0", "Channel", "Master"))
                .contains("<CurrentVolume>60</CurrentVolume>"));
        call("SetVolume", "InstanceID", "0", "Channel", "Master", "DesiredVolume", "35");
        assertTrue(sink.calls.contains("volume 35"));
        call("SetMute", "InstanceID", "0", "Channel", "Master", "DesiredMute", "1");
        assertTrue(renderer.muted());
        assertTrue(sink.calls.contains("volume 0"));
    }

    @Test
    public void stopResetsThePositionAndUnknownActionsAreRefused() {
        call("SetAVTransportURI", "InstanceID", "0", "CurrentURI", "http://x/a.mp4");
        call("Play", "InstanceID", "0");
        sink.position = 30_000;
        call("Stop", "InstanceID", "0");
        assertEquals(DlnaRenderer.STOPPED, renderer.state());
        assertEquals(0, renderer.positionMs());
        assertEquals(500, call("Next", "InstanceID", "0").status);
        assertEquals(500, call("NoSuchAction", "InstanceID", "0").status);
    }

    @Test
    public void connectionManagerAdvertisesWhatTheAppPlays() {
        String sinkList = bodyOf(call("GetProtocolInfo"));
        assertTrue(sinkList.contains("http-get:*:video/mp4:*"));
        assertTrue(sinkList.contains("application/vnd.apple.mpegurl"));
        assertTrue(bodyOf(call("GetCurrentConnectionIDs")).contains("<ConnectionIDs>0</ConnectionIDs>"));
    }

    @Test
    public void titleFallsBackToTheUrlWhenMetadataIsMissing() {
        assertEquals("a.mp4", DlnaRenderer.titleFromMetadata("", "http://x/path/a.mp4?token=1"));
        assertEquals("影片", DlnaRenderer.titleFromMetadata(
                "<DIDL-Lite><item><dc:title>影片</dc:title></item></DIDL-Lite>", "http://x/a.mp4"));
    }
}
