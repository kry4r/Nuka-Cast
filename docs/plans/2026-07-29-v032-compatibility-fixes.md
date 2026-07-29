# NukaCast 0.3.2 Compatibility Fixes Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make AirPlay decoding and TVBox sources reliable on Android 4.x while removing web-management pairing.

**Architecture:** Keep `MediaCodec` lifecycle and legacy input buffers owned by the decode thread. Route source bytes through one charset decoder, resolve a bounded set of TVBox-looking links when a supplied URL is HTML, and express direct LAN API access as an explicit server policy.

**Tech Stack:** Java 8, Android API 17+, MediaCodec, OkHttp 3.12, Gson, jsoup, NanoHTTPD, React 18, TypeScript, Vitest, Gradle.

---

### Task 1: Stabilize Android 4.x MediaCodec input buffers

**Files:**
- Modify: `app/src/main/java/com/nukacast/app/airplay/H264VideoRenderer.java`
- Test: `app/src/test/java/com/nukacast/app/airplay/H264VideoRendererTest.java`

**Step 1: Write failing tests**

```java
@Test public void api19UsesCachedLegacyInputBuffers() {
    assertTrue(H264VideoRenderer.usesLegacyInputBuffers(19));
    assertFalse(H264VideoRenderer.usesLegacyInputBuffers(21));
}

@Test public void selectsCachedInputBufferWithoutRefreshingCodecBuffers() {
    ByteBuffer second = ByteBuffer.allocate(8);
    assertSame(second, H264VideoRenderer.cachedInputBuffer(
            new ByteBuffer[] {ByteBuffer.allocate(4), second}, 1));
}
```

**Step 2: Verify RED**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.nukacast.app.airplay.H264VideoRendererTest`

Expected: compilation fails because the helper methods do not exist.

**Step 3: Implement minimal lifecycle fix**

- Cache `candidate.getInputBuffers()` once immediately after `start()` on API 20 and lower.
- Use `getInputBuffer(index)` on API 21+ and the cached array on older Android.
- Clear cached buffers whenever a candidate fails or the active decoder is released.
- Remove decoder release from `stop()` and release in `decodeLoop`'s `finally` block so the decode thread owns all codec calls.
- Preserve the retained-IDR recovery and hardware/software fallback behavior.

```java
static boolean usesLegacyInputBuffers(int sdk) { return sdk < 21; }

static ByteBuffer cachedInputBuffer(ByteBuffer[] buffers, int index) {
    if (buffers == null || index < 0 || index >= buffers.length || buffers[index] == null) {
        throw new IllegalStateException("H.264 input buffer unavailable");
    }
    return buffers[index];
}
```

**Step 4: Verify GREEN**

Run Step 2 again. Expected: the focused class passes.

**Step 5: Commit**

```powershell
git add app/src/main/java/com/nukacast/app/airplay/H264VideoRenderer.java app/src/test/java/com/nukacast/app/airplay/H264VideoRendererTest.java
git commit -m "fix: stabilize legacy AirPlay codec buffers"
```

### Task 2: Force TLS 1.2 and decode Chinese response text

**Files:**
- Create: `app/src/main/java/com/nukacast/app/net/ResponseTextDecoder.java`
- Create: `app/src/test/java/com/nukacast/app/net/ResponseTextDecoderTest.java`
- Modify: `app/src/main/java/com/nukacast/app/net/Tls12SocketFactory.java`
- Modify: `app/src/test/java/com/nukacast/app/net/Tls12SocketFactoryTest.java`

**Step 1: Write failing charset tests**

```java
@Test public void decodesStrictUtf8ChineseWithoutCharset() throws Exception {
    assertEquals("Chinese UTF-8 text", ResponseTextDecoder.decode(
            "Chinese UTF-8 text".getBytes("UTF-8"), "application/json"));
}

@Test public void decodesUndeclaredGb18030WhenUtf8IsInvalid() throws Exception {
    String value = "Chinese source name";
    assertEquals(value, ResponseTextDecoder.decode(
            value.getBytes("GB18030"), "text/plain"));
}
```

Use actual Chinese literals in the test file, and add UTF-8 BOM and `charset=GBK` cases.

**Step 2: Change the API 19 TLS expectation**

```java
assertArrayEquals(new String[] {"TLSv1.2"},
        Tls12SocketFactory.protocolsFor(19,
                new String[] {"TLSv1", "TLSv1.1", "TLSv1.2"},
                new String[] {"TLSv1"}));
```

**Step 3: Verify RED**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.nukacast.app.net.ResponseTextDecoderTest --tests com.nukacast.app.net.Tls12SocketFactoryTest`

Expected: missing decoder compilation failure and TLS assertion failure.

**Step 4: Implement minimal decoding and TLS behavior**

`ResponseTextDecoder.decode(bytes, contentType)` honors Unicode BOMs, then a valid declared charset, then strict UTF-8 with malformed input set to `REPORT`, and finally GB18030. For SDK 16-21, `protocolsFor` returns only TLS 1.2 when supported; modern Android preserves its platform list.

**Step 5: Verify GREEN and commit**

Run Step 3 again, then:

```powershell
git add app/src/main/java/com/nukacast/app/net app/src/test/java/com/nukacast/app/net
git commit -m "fix: support TLS 1.2 and Chinese source text"
```

### Task 3: Discover TVBox configurations linked from HTML

**Files:**
- Modify: `app/build.gradle`
- Create: `app/src/main/java/com/nukacast/app/tvbox/ConfigLinkDiscovery.java`
- Create: `app/src/main/java/com/nukacast/app/tvbox/ConfigPayloadResolver.java`
- Create: `app/src/test/java/com/nukacast/app/tvbox/ConfigLinkDiscoveryTest.java`
- Create: `app/src/test/java/com/nukacast/app/tvbox/ConfigPayloadResolverTest.java`
- Modify: `app/src/main/java/com/nukacast/app/tvbox/TvBoxRepository.java`
- Test: `app/src/test/java/com/nukacast/app/tvbox/ConfigDecoderTest.java`

**Step 1: Add `org.jsoup:jsoup:1.17.2` and write failing discovery tests**

```java
@Test public void ranksLinkedJsonConfigAheadOfUnrelatedLinks() {
    String html = "<a href='https://example.com/releases'>Download</a>"
            + "<a href='https://6800.kstore.vip/fish.json'>Backup</a>";
    List<String> candidates = ConfigLinkDiscovery.candidates(
            html, "https://www.xn--ihq545aq7p.com/");
    assertEquals("https://6800.kstore.vip/fish.json", candidates.get(0));
}
```

Also cover relative `.json`, `tvbox.php`, duplicates, non-HTTP schemes, and the eight-candidate limit.

Add resolver tests with a fake fetcher proving that plain JSON returns immediately, HTML follows at most one level, invalid candidates are skipped, the first valid TVBox document wins, and no more than eight candidate requests are made.

**Step 2: Verify RED**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.nukacast.app.tvbox.ConfigLinkDiscoveryTest`

Expected: missing class compilation failure.

**Step 3: Implement bounded discovery**

Use jsoup `a[href]` parsing, resolve links against the response URL, accept HTTP(S), rank `.json` before names containing `tvbox`/`config` and endpoint extensions such as `.php`, deduplicate, and return at most eight. Implement `ConfigPayloadResolver` with an injected `Fetcher` so the bounded fetch/parse flow is fully covered without Android or live-network mocks.

**Step 4: Integrate repository fetch and decode**

- Refactor one request into a private result containing final URL, bytes, and content type.
- Decode all source bytes with `ResponseTextDecoder`.
- Try normal `ConfigDecoder` parsing first.
- Only after an HTML response fails normal parsing, fetch ranked candidates until one is valid.
- Keep the original user-entered URL, but hash/cache the resolved configuration.
- Allow one HTML discovery level and eight candidate requests.
- Report `No usable TVBox config link found in page` in localized UI text when none work.

**Step 5: Verify GREEN and commit**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.nukacast.app.tvbox.ConfigLinkDiscoveryTest --tests com.nukacast.app.tvbox.ConfigPayloadResolverTest --tests com.nukacast.app.tvbox.ConfigDecoderTest`

Then commit `app/build.gradle` and the TVBox production/test files with message `fix: resolve TVBox configs published in HTML`.

### Task 4: Remove server and TV pairing

**Files:**
- Modify: `app/src/main/java/com/nukacast/app/server/ControlServer.java`
- Modify: `app/src/main/java/com/nukacast/app/core/NukaRuntime.java`
- Delete: `app/src/main/java/com/nukacast/app/security/PairingManager.java`
- Delete: `app/src/main/java/com/nukacast/app/security/PairingSecret.java`
- Delete: `app/src/test/java/com/nukacast/app/security/PairingSecretTest.java`
- Create: `app/src/test/java/com/nukacast/app/server/ControlServerAccessTest.java`
- Modify: `app/src/main/java/com/nukacast/app/MainActivity.java`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/values/strings.xml`

**Step 1: Write failing policy test**

```java
@Test public void managementApisAllowDirectLanAccess() {
    assertFalse(ControlServer.requiresAuthentication("/api/device"));
    assertFalse(ControlServer.requiresAuthentication("/api/sources"));
    assertFalse(ControlServer.requiresAuthentication("/api/player"));
}
```

**Step 2: Verify RED**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.nukacast.app.server.ControlServerAccessTest`

Expected: missing policy method compilation failure.

**Step 3: Implement direct access**

- Add package-private `requiresAuthentication` and use it at API dispatch; the direct LAN policy returns false.
- Remove `/api/pair`, bearer parsing, and pair request data.
- Keep `pairingRequired: false` temporarily so stale cached web assets can open directly.
- Remove `PairingManager` from runtime and delete unused pairing classes/tests.
- Remove TV pairing-code state, update logic, clear button, layout, and strings.
- Keep `/media/` loopback-only protection unchanged.

**Step 4: Verify GREEN and commit**

Run Step 2 plus `.\gradlew.bat :app:testDebugUnitTest --tests com.nukacast.app.storage.StorageIpv4Test`, then commit with `feat: allow direct LAN web control`.

### Task 5: Remove browser token and pairing UI

**Files:**
- Modify: `web/src/lib/api.ts`
- Create: `web/src/lib/api.test.ts`
- Modify: `web/src/App.tsx`
- Regenerate: `app/src/main/assets/web/index.html`
- Regenerate: `app/src/main/assets/web/assets/*`

**Step 1: Write failing API test**

Stub `sessionStorage` with an old token and stub `fetch`. Dynamically import the module, call `api.device()`, and assert:

```ts
expect(new Headers(fetchMock.mock.calls[0][1]?.headers).has("Authorization")).toBe(false)
```

**Step 2: Verify RED**

Run `npm test -- --run src/lib/api.test.ts` in `web`.

Expected: the existing client attaches a bearer token.

**Step 3: Implement direct browser access**

Remove token storage, the auth-expired event, `hasToken`, `forget`, and `pair`. Remove paired state, event handling, and the `Pairing` component. Render the operational application immediately.

**Step 4: Verify GREEN and rebuild assets**

Run in `web`:

```powershell
npm test -- --run
npm run build
```

Commit web sources and generated Android assets with `feat: open web control without pairing`.

### Task 6: Complete verification

**Files:**
- Modify only if verification exposes an in-scope defect.

**Step 1: Run all web checks**

In `web`: `npm test -- --run` and `npm run build`.

**Step 2: Run Android checks**

Run: `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`

Expected: zero tests/lint failures and `BUILD SUCCESSFUL`.

**Step 3: Probe reported URLs**

```powershell
$html = (Invoke-WebRequest -UseBasicParsing 'https://www.xn--ihq545aq7p.com/').Content
if ($html -notmatch 'https://6800\.kstore\.vip/fish\.json') { throw 'config link missing' }
'' | openssl s_client -connect 6800.kstore.vip:443 -servername 6800.kstore.vip -tls1_2 -cipher 'ECDHE-RSA-AES128-SHA' -brief
```

Expected: the page still publishes the link and OpenSSL reports TLS 1.2.

**Step 4: Inspect final state**

Run `git status --short`, `git diff --check`, and `rg -n "PairingManager|pair-code|AUTH_EXPIRED_EVENT|Authorization.*Bearer" app/src web/src`.

Confirm only expected generated assets and implementation files changed, and no pairing code remains.
