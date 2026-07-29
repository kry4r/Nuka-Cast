# AirPlay Session and API 19 Trust Compatibility Design

## Goal

Restore usable AirPlay mirroring and TVBox Spider loading on the Android 4.4
receiver reported at `192.168.5.4:9978`, without disabling TLS certificate or
hostname verification.

## Evidence and Root Causes

The device log shows `OMX.hisi.video.decoder.avc` being created twice about one
second apart, with no decoded output or fallback message. iOS periodically
resends the same SPS/PPS configuration. `H264VideoRenderer.offer()` currently
resets the decoder and clears software-fallback state for every configuration
packet. This restarts the 1.5 second no-output timer before it can switch away
from the HiSilicon decoder.

`AirPlayReceiver` also treats two seconds without an audio or video packet as a
disconnected session. A static iOS screen may legitimately produce no new media
packets, so this watchdog destroys and restarts a healthy native receiver. The
native bridge already reports authoritative mirror-session start and end events.

The TVBox configuration parses successfully and exposes 86 sites. Home and
search fail while downloading the declared Spider JAR from `tc-new.z.wiki`,
which redirects to `img.pagehost.cn`. Both use a chain rooted at DigiCert Global
Root G2. The API 19 system image does not contain its legacy subject-hash entry
`c90bc37d.0`, so platform certificate validation fails before the JAR digest can
be checked.

## Design

### Stable H.264 Configuration

Compare incoming SPS/PPS parameter sets with the active configuration. Count
every configuration packet for diagnostics, but do not clear the queue, retained
IDR, decoder, or fallback state when the parameter sets are unchanged. A real
parameter-set change still resets the decoder because dimensions or profile may
have changed. A new AirPlay session still clears fallback state through
`flush()`.

This allows the existing no-output policy to observe the HiSilicon decoder for
long enough to switch to `OMX.google.h264.decoder` and requeue the retained IDR.

### Authoritative Session Lifetime

Use native `session_changed` callbacks as the source of truth for connection
lifetime. Keep the watchdog only for republishing the receiver after network
changes. Do not terminate a session based on media-packet silence. Explicit
native disconnect and user disconnect continue to flush renderers and schedule
receiver restart.

### API 19 Certificate Compatibility

Bundle the official DigiCert Global Root G2 certificate. On SDK 16-21, construct
a composite `X509TrustManager` that first attempts the Android platform trust
manager and then a trust manager backed only by the bundled root. Use the
application Conscrypt provider for this legacy SSL context because the API 19
system provider has no AES-GCM suites while the reported JAR redirect endpoint
accepts only AES-GCM over TLS 1.2. SDK 22 and newer keep their platform defaults.

This does not add a trust-all verifier. OkHttp hostname verification remains
enabled, unrelated unknown roots remain rejected, redirects are verified per
host, and the existing declared JAR MD5/SHA-256 or first-use fingerprint check
still runs after transport validation.

## Testing

- Unit-test that identical SPS/PPS is treated as unchanged while changed PPS or
  SPS triggers a reset decision.
- Unit-test that media silence cannot end a session and native disconnect still
  can.
- Unit-test composite trust-manager fallback and rejection behavior with strict
  fake managers.
- On API 19, verify the reported Spider JAR URL follows its redirect and
  downloads through `HttpStack`, then confirm its declared MD5.
- On the target TV, verify the decoder log changes from HiSilicon no-output to
  Google software fallback with decoder output greater than zero, and that a
  static iOS desktop remains mirrored.
- Run the full Web, JVM, lint, APK, API 19, and CI release checks before tagging
  `v0.3.4`.
