# AirPlay Session and API 19 Trust Fixes Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Keep iOS mirroring alive through static screens, recover from the HiSilicon AVC black screen, and load the reported TVBox Spider JAR securely on Android 4.4.

**Architecture:** Make H.264 configuration updates idempotent so repeated SPS/PPS packets cannot reset fallback progress. Keep the native mirror-session callback authoritative instead of deriving connection lifetime from media cadence. Extend only the API 16-21 TLS client with a composite platform-plus-bundled-root trust manager while retaining OkHttp hostname verification and Spider JAR integrity checks.

**Tech Stack:** Java 8, Android API 17+, MediaCodec, OkHttp 3.12, JUnit 4, AndroidX instrumentation, Gradle.

---

### Task 1: Preserve Decoder Progress Across Repeated SPS/PPS

**Files:**
- Modify: `app/src/test/java/com/nukacast/app/airplay/H264VideoRendererTest.java`
- Modify: `app/src/main/java/com/nukacast/app/airplay/H264VideoRenderer.java`

**Step 1: Write the failing test**

Add tests that call a package-private `sameCodecConfiguration(byte[], byte[])` helper. Assert that the same SPS/PPS with three- versus four-byte Annex B prefixes is equivalent, and that changing either PPS payload is not equivalent.

```java
assertTrue(H264VideoRenderer.sameCodecConfiguration(first, sameParameters));
assertFalse(H264VideoRenderer.sameCodecConfiguration(first, changedPps));
```

**Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.nukacast.app.airplay.H264VideoRendererTest
```

Expected: compilation fails because `sameCodecConfiguration` does not exist.

**Step 3: Write minimal implementation**

Extract SPS and PPS NAL payloads, strip either Annex B prefix, and compare both payloads with `Arrays.equals`. In `offer()`, always increment `configPackets`, but reset the decoder, queue, retained IDR, and `softwareFallback` only when the parameter sets differ from `codecConfig`. Return after handling a configuration packet so it does not consume queue capacity.

**Step 4: Run test to verify it passes**

Run the focused test above, then:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.nukacast.app.airplay.DecoderFallbackPolicyTest
```

Expected: both test classes pass.

**Step 5: Commit**

```powershell
git add app/src/main/java/com/nukacast/app/airplay/H264VideoRenderer.java app/src/test/java/com/nukacast/app/airplay/H264VideoRendererTest.java
git commit -m "fix: preserve AirPlay decoder fallback progress"
```

### Task 2: Stop Treating Static Screens as Disconnections

**Files:**
- Modify: `app/src/test/java/com/nukacast/app/airplay/AirPlaySessionStateTest.java`
- Modify: `app/src/main/java/com/nukacast/app/airplay/AirPlaySessionState.java`
- Modify: `app/src/main/java/com/nukacast/app/airplay/AirPlayReceiver.java`

**Step 1: Write the failing test**

Replace the idle-disconnect expectation with a test named `mediaSilenceDoesNotOverrideNativeSessionState`. Start the receiver state, call a new `nativeConnected(1000L)` transition, and assert `isIdle(600000L, 2000L)` remains false. Add a second assertion that explicit `disconnect()` makes the state inactive and rejects later packets.

**Step 2: Run test to verify it fails**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.nukacast.app.airplay.AirPlaySessionStateTest
```

Expected: the silence assertion fails because the current two-second timeout returns true.

**Step 3: Write minimal implementation**

Track a `nativeConnected` flag in `AirPlaySessionState`. The new `nativeConnected(long)` transition marks the session active and returns the same started/continuing result used by packet receipt. `isIdle()` may only report a stale packet-only session when no native connection is active. Clear the flag on explicit disconnect/receiver stop. Refactor `AirPlayReceiver.onSession(true)` to use this transition and share the existing one-time session-start logging/UI callback with `packetReceived()`. Explicit native `onSession(false)` and user disconnect behavior remain unchanged.

**Step 4: Run test to verify it passes**

Run the focused session test and the AirPlay package tests:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.nukacast.app.airplay.*"
```

Expected: all AirPlay tests pass.

**Step 5: Commit**

```powershell
git add app/src/main/java/com/nukacast/app/airplay/AirPlayReceiver.java app/src/main/java/com/nukacast/app/airplay/AirPlaySessionState.java app/src/test/java/com/nukacast/app/airplay/AirPlaySessionStateTest.java
git commit -m "fix: keep static AirPlay sessions connected"
```

### Task 3: Add Strict DigiCert G2 Trust on API 19

**Files:**
- Modify: `app/build.gradle`
- Create: `app/src/main/java/com/nukacast/app/net/FallbackTrustManager.java`
- Create: `app/src/main/resources/com/nukacast/app/net/digicert_global_root_g2.pem`
- Create: `app/src/test/java/com/nukacast/app/net/FallbackTrustManagerTest.java`
- Modify: `app/src/main/java/com/nukacast/app/net/HttpStack.java`
- Temporarily create, then delete: `app/src/androidTest/java/com/nukacast/app/spider/ReportedJarApi19Test.java`

**Step 1: Write the failing unit tests**

Use strict fake `X509TrustManager` instances to prove the new composite manager:

- returns when the platform manager accepts;
- invokes the bundled manager only after platform rejection;
- rethrows a certificate failure when both managers reject;
- combines accepted issuer arrays.

**Step 2: Run tests to verify they fail**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.nukacast.app.net.FallbackTrustManagerTest
```

Expected: compilation fails because `FallbackTrustManager` does not exist.

**Step 3: Implement the composite trust manager**

Create a package-private `FallbackTrustManager` that tries the primary manager and catches only `CertificateException` before trying the fallback. Do not add a hostname verifier or trust-all path.

Add the official PEM certificate from `https://cacerts.digicert.com/DigiCertGlobalRootG2.crt.pem`. In `HttpStack`, load it with `CertificateFactory`, insert it into an in-memory `KeyStore`, and create a second `X509TrustManager`. Add the official `conscrypt-android` dependency and initialize the API 16-21 Conscrypt `SSLContext` with `new FallbackTrustManager(platform, bundled)`, providing AES-GCM without changing global providers.

**Step 4: Run unit tests to verify green**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.nukacast.app.net.*"
```

Expected: all network unit tests pass.

**Step 5: Add and run a temporary API 19 reproduction test**

The temporary instrumentation test must request the exact configured JAR URL through `HttpStack.client()`, follow the redirect, read the bounded body, and assert MD5 `863da1e40556a6b08e1969cf03d9c0e5`.

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest -PincludeLegacyTestAbi '-Pandroid.testInstrumentationRunnerArguments.class=com.nukacast.app.spider.ReportedJarApi19Test'
```

Expected: one API 19 test passes. Delete the temporary live-site test afterward so external availability cannot make CI flaky.

**Step 6: Commit**

```powershell
git add app/build.gradle app/src/main/java/com/nukacast/app/net app/src/main/resources/com/nukacast/app/net/digicert_global_root_g2.pem app/src/test/java/com/nukacast/app/net
git commit -m "fix: trust modern DigiCert chains on Android 4"
```

### Task 4: Verify, Version, and Release

**Files:**
- Modify: `app/build.gradle`
- Modify: `web/package.json`
- Modify: `web/package-lock.json`
- Modify: `README.md`

**Step 1: Run complete local verification**

```powershell
Set-Location web
npm test -- --run
npm run build
Set-Location ..
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
git diff --check
```

Expected: Web 7 tests pass and Android build is successful with no lint errors.

**Step 2: Perform target-device acceptance**

Install the debug APK on the API 19 receiver. Refresh the reported fish source and verify home and search complete without `Trust anchor` errors. Start iOS mirroring and verify logs show HiSilicon no-output fallback to `OMX.google.h264.decoder`, decoder output count becomes positive, and a static desktop remains connected for at least 30 seconds.

**Step 3: Bump patch version**

Set Android `versionCode` to 10 and default `versionName` to `0.3.4`. Set both Web package versions to `0.3.4` and update the README tag example.

**Step 4: Rebuild and commit release metadata**

```powershell
git add README.md app/build.gradle web/package.json web/package-lock.json app/src/main/assets/web
git commit -m "chore: prepare v0.3.4 release"
```

**Step 5: Merge and release through CI**

Merge the feature branch into `main`, rerun tests on the merged tree, push `main`, wait for both Android CI jobs to pass, create annotated tag `v0.3.4`, push it, and wait for Android Release to publish the signed APK and SHA-256 asset. Download both assets and independently verify the checksum and APK signature before reporting completion.
