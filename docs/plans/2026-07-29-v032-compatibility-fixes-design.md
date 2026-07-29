# NukaCast 0.3.2 Compatibility Fixes Design

## Goal

Fix the Android 4.x AirPlay decoder crash, remove web-management pairing, accept TVBox links published inside HTML pages, support TLS 1.2-only source servers, and preserve Chinese source names.

## Findings

- `H264VideoRenderer` calls `MediaCodec.getInputBuffers()` for every frame. Android 4.4 vendor codecs can throw `IllegalStateException` from that legacy native call after `dequeueInputBuffer()` succeeds. Codec lifecycle and buffer access need one decode-thread-owned state.
- Web management has a separate six-digit `PairingManager` flow. It is unrelated to AirPlay's required cryptographic `pair-setup` and can be removed without weakening the AirPlay transport handshake.
- `https://www.xn--ihq545aq7p.com/` returns HTML, not TVBox JSON. The page publishes `https://6800.kstore.vip/fish.json` as a link.
- `fish.json` is valid UTF-8 JSON without a charset response parameter. Its server rejects protocols older than TLS 1.2.
- Source response bytes are currently decoded unconditionally as UTF-8. There is no BOM, declared-charset, or legacy Chinese encoding detection.

## Design

### AirPlay video

Keep all `MediaCodec` start, input, drain, stop, and release operations on the decode thread. Cache the legacy input buffer array immediately after a decoder starts and clear it when the decoder is released. Treat a buffer-state exception as a recoverable decoder reset that waits for the retained IDR frame instead of repeatedly using a stale codec state.

### Direct web management

Remove the pairing screen and token storage from the web client. Make control API requests directly. Remove `/api/pair`, bearer-token enforcement, pairing status, and TV pairing-code presentation. Keep the server bound to the LAN address and retain existing request validation. This intentionally allows any client on the same reachable network to control NukaCast, as requested.

### Source discovery

Add a bounded HTML discovery step after a response fails normal TVBox decoding. Parse absolute and relative `href` values with an HTML-aware parser available in the Android platform or a small focused extractor, resolve them against the response URL, and rank only HTTP(S) candidates that look like JSON/config endpoints. Fetch candidates with a strict count, size, redirect, and same refresh timeout budget. The published `fish.json` link is selected without adding a domain-specific exception.

### TLS and text decoding

For Android versions where TLS 1.2 is supported but disabled by default, configure HTTPS sockets with TLS 1.2 only. Do not advertise TLS 1.0 or TLS 1.1 to TLS 1.2-only servers. Decode source bytes in this order: Unicode BOM, valid declared HTTP charset, strict UTF-8, then GB18030. Store and cache the resulting Unicode string so warehouse and site names remain intact.

## Error Handling

- Decoder failures retain the latest IDR, release the codec on its owner thread, and retry through the existing hardware/software fallback policy.
- HTML without a viable configuration link reports that the page contains no discoverable TVBox configuration rather than a generic JSON-format error.
- Candidate discovery is bounded and reports the final attempted URL in diagnostics.
- Unsupported or invalid declared charsets fall through to strict byte detection.
- TLS failures continue to surface the hostname and protocol exception through source diagnostics.

## Verification

- JVM unit tests cover buffer-state decisions, direct web authorization, HTML candidate extraction/ranking, response charset detection, and TLS protocol selection.
- Existing JVM tests and the Android lint/build tasks must remain green.
- A live desktop probe confirms the HTML page still publishes `fish.json`, that the JSON is valid UTF-8, and that its server accepts TLS 1.2.
- When an API 19 emulator/device is available, an instrumentation request verifies `fish.json` through the app's actual `HttpStack`.
