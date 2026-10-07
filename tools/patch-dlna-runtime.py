import io

# ---------------------------------------------------------------- runtime wiring
p = 'app/src/main/java/com/nukacast/app/core/NukaRuntime.java'
s = io.open(p, encoding='utf-8', newline='').read().replace('\r\n', '\n')


def swap(old, new, count=1):
    global s
    assert old in s, 'MISSING: ' + old[:130]
    s = s.replace(old, new, count)


swap("""    private final AirPlayReceiver airPlayReceiver;""",
"""    private final AirPlayReceiver airPlayReceiver;
    private final com.nukacast.app.dlna.DlnaRenderer dlnaRenderer;
    private final com.nukacast.app.dlna.DlnaService dlnaService;
    private com.nukacast.app.dlna.DlnaSsdp dlnaSsdp;""")

swap("""        airPlayReceiver = new AirPlayReceiver(this.context, state, new Runnable() {""",
"""        // DLNA playback goes through the same player as everything else, so the TV shows the same
        // picture, HUD and controls whether the media came from the remote or from a phone.
        dlnaRenderer = new com.nukacast.app.dlna.DlnaRenderer(new com.nukacast.app.dlna.DlnaRenderer.Sink() {
            @Override public void play(String url, String title) {
                playerController.play(contextRef(), url, title, java.util.Collections.<String, String>emptyMap());
                updateActiveMedia(title);
            }

            @Override public void pause() {
                playerController.pause();
            }

            @Override public void resume() {
                playerController.resume();
            }

            @Override public void stop() {
                playerController.stop();
            }

            @Override public void seekTo(int positionMs) {
                playerController.seekTo(positionMs);
            }

            @Override public int positionMs() {
                return playerController.snapshot().positionMs;
            }

            @Override public int durationMs() {
                return playerController.snapshot().durationMs;
            }

            @Override public void setVolume(int volume0To100) {
                playerController.setVolume(volume0To100 / 100f);
            }
        });
        dlnaService = new com.nukacast.app.dlna.DlnaService(dlnaRenderer);
        airPlayReceiver = new AirPlayReceiver(this.context, state, new Runnable() {""")

io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('runtime: dlna renderer created')
