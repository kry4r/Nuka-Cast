# API 19 AirPlay and Warehouse Compatibility Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Restore API 19 ARM startup, support single and multi-warehouse TVBox sources, refresh live data immediately, enable TLS 1.2, and expose actionable AirPlay/source diagnostics.

**Architecture:** Parse source responses into either a single TVBox config or a warehouse index, persist warehouse children under their parent, and search one selected child at a time with the fastest healthy child as default. Keep startup on the known-good IPv4 native baseline, lazily initialize optional SMB state, and add a MediaCodec no-output policy that falls back from the HiSilicon decoder to the Google AVC decoder.

**Tech Stack:** Android Java 8 / API 17+, JNI/C, OkHttp 3.12, Gson, JUnit 4, React 18, TypeScript, Vite, Vitest.

---

### Task 1: Decode Real-World Single and Multi-Warehouse Documents

**Files:**
- Modify: `app/src/main/java/com/nukacast/app/tvbox/ConfigDecoder.java`
- Modify: `app/src/test/java/com/nukacast/app/tvbox/ConfigDecoderTest.java`

**Steps:**

1. Add failing tests for UTF-8 BOM, leading `//` comment lines, a `urls` warehouse document, relative child URLs, and rejection of an object that has neither `sites`/`lives` nor `urls`.
2. Run `./gradlew.bat :app:testDebugUnitTest --tests com.nukacast.app.tvbox.ConfigDecoderTest` and verify the new tests fail.
3. Add `ConfigDecoder.Document` and `ConfigDecoder.WarehouseEntry`. Implement `decodeDocument(content)` by unwrapping Base64, stripping BOM/comment prefixes, parsing a lenient `JsonObject`, and detecting `urls` before deserializing `TvBoxConfig`.
4. Keep `decode(content)` as the single-config API and make it reject warehouse documents with a clear message.
5. Run the focused test and commit with `feat: decode TVBox warehouse indexes`.

### Task 2: Persist Warehouse Parents and Children

**Files:**
- Modify: `app/src/main/java/com/nukacast/app/tvbox/model/ConfigSource.java`
- Modify: `app/src/main/java/com/nukacast/app/tvbox/SourceStore.java`
- Modify: `app/src/main/java/com/nukacast/app/tvbox/TvBoxRepository.java`
- Modify: `app/src/test/java/com/nukacast/app/tvbox/SourceStoreTest.java`
- Create: `app/src/test/java/com/nukacast/app/tvbox/WarehouseRankingTest.java`

**Steps:**

1. Add failing pure-Java tests for child URL de-duplication, preserving existing child IDs, removing stale children, cascading parent removal, and ranking healthy sources before failed/slow sources.
2. Run the two focused tests and verify RED.
3. Extend `ConfigSource` with `kind`, `parentId`, `siteCount`, `liveCount`, and `latencyMs`; add `isWarehouse()` and `isChild()` helpers.
4. Extract deterministic `SourceStore.mergeChildren(existing, parent, entries)` and `removeTree(sources, id)` helpers. Persist the merged list atomically through the existing JSON preference.
5. Change the built-in clean-install source to the user-provided reachable multi-warehouse URL `http://xhztv.top/dc`.
6. In `TvBoxRepository.refresh`, measure elapsed time and route warehouse documents to child synchronization. Route single documents through the existing enrich/cache flow and update counts/health.
7. Make `refreshAllAsync` refresh root indexes first, then the current child/single snapshot, sequentially on its background executor to stay below the two-request API 19 limit.
8. Run all JVM tests and commit with `feat: import and rank multi-warehouse sources`.

### Task 3: Restore Android 4.4 TLS and Spider Compatibility

**Files:**
- Modify: `app/src/main/java/com/nukacast/app/net/HttpStack.java`
- Create: `app/src/main/java/com/nukacast/app/net/Tls12SocketFactory.java`
- Create: `app/src/test/java/com/nukacast/app/net/Tls12SocketFactoryTest.java`
- Modify: `app/src/main/java/com/nukacast/app/spider/SpiderManager.java`
- Modify: `app/src/test/java/com/nukacast/app/spider/SpiderManagerTest.java`
- Modify: `app/src/main/java/com/nukacast/app/util/Digests.java`

**Steps:**

1. Add failing tests showing API 19 protocol selection enables `TLSv1.2`, and Spider specs accept `;md5;<32 hex>`, `;sha256=<64 hex>`, and no digest while rejecting malformed declared digests.
2. Verify focused tests fail.
3. Implement a delegating `SSLSocketFactory` that adds TLS 1.2 for API 16-19 and configure OkHttp with the platform trust manager plus modern/compatible connection specs.
4. Replace `requiredSha256` with a parsed digest spec. Permit HTTP because the user explicitly supplies TVBox endpoints; verify a declared MD5/SHA-256 and cache no-digest JARs by URL fingerprint.
5. Add `Digests.md5` and keep all error text actionable.
6. Run focused and full JVM tests; commit with `fix: support legacy TLS and TVBox spider digests`.

### Task 4: Search by Warehouse and Refresh Live State Immediately

**Files:**
- Modify: `app/src/main/java/com/nukacast/app/tvbox/model/SearchQuery.java`
- Modify: `app/src/main/java/com/nukacast/app/tvbox/SearchEngine.java`
- Modify: `app/src/main/java/com/nukacast/app/live/LiveService.java`
- Modify: `app/src/main/java/com/nukacast/app/service/NukaCastService.java`
- Modify: `app/src/main/java/com/nukacast/app/server/ControlServer.java`
- Create: `app/src/test/java/com/nukacast/app/tvbox/WarehouseSearchSelectionTest.java`

**Steps:**

1. Add a failing test that source selection filters sites by `sourceId` even when site keys collide.
2. Verify RED, then add `sourceId` to `SearchQuery` and filter it in `SearchEngine.selectedSites` through a package-visible pure helper.
3. Expose ranked leaf sources from `TvBoxRepository`; return them from `/api/sources` with health/count/latency fields.
4. After add/refresh/remove, clear `LiveService` cache and call `AppState.updateSources` only after the repository snapshot changes. Add `stateVersion` to `/api/status`.
5. For a newly added warehouse, synchronize the index during the POST, then queue child refresh in the repository background executor so the request returns promptly.
6. Run focused and full JVM tests; commit with `feat: select warehouses and version source content`.

### Task 5: Restore API 19 ARM Startup Baseline and Record Failures

**Files:**
- Modify: `app/src/main/cpp/legacy-airplay/lib/httpd.c`
- Modify: `app/src/main/cpp/legacy-airplay/lib/raop_rtp.c`
- Modify: `app/src/main/cpp/legacy-airplay/lib/raop_rtp_mirror.c`
- Modify: `app/src/main/java/com/nukacast/app/storage/StorageLibrary.java`
- Modify: `app/src/main/java/com/nukacast/app/core/DeviceProbe.java`
- Modify: `app/src/main/java/com/nukacast/app/CrashReporter.java`
- Modify: `app/src/main/java/com/nukacast/app/server/ControlServer.java`
- Create: `app/src/test/java/com/nukacast/app/core/DeviceWarningTest.java`

**Steps:**

1. Add a failing pure-Java test for the corrected API compatibility message.
2. Restore the 0.2.5 IPv4-only RAOP listener and RTP socket choice. Keep the 0.3.0 bounds/lifecycle checks that do not change address family behavior.
3. Lazily create the jcifs-ng `CIFSContext` only when an SMB operation occurs.
4. Change the warning to `应用最低支持 API 17；当前设备 API X` and expose the saved Java crash plus current AirPlay state through `/api/diagnostics`.
5. Build `armeabi-v7a`, inspect ELF dependencies, run the API 19 cold-start instrumentation test, and commit with `fix: restore API 19 ARM startup path`.

### Task 6: Detect MediaCodec Black Screens and Fall Back to Software AVC

**Files:**
- Create: `app/src/main/java/com/nukacast/app/airplay/DecoderFallbackPolicy.java`
- Create: `app/src/test/java/com/nukacast/app/airplay/DecoderFallbackPolicyTest.java`
- Modify: `app/src/main/java/com/nukacast/app/airplay/H264VideoRenderer.java`
- Modify: `app/src/main/java/com/nukacast/app/airplay/AirPlayReceiver.java`
- Modify: `app/src/test/java/com/nukacast/app/airplay/H264VideoRendererTest.java`

**Steps:**

1. Add failing tests for no fallback before enough input/time, fallback for a hardware codec with inputs but zero outputs, no fallback after output, and Annex-B keyframe retention.
2. Verify RED.
3. Track config packets, IDRs, decoder input/output counts, output format, decoder name, and fallback state.
4. Retain the latest IDR. If the policy detects a no-output hardware decoder, rebuild with `OMX.google.h264.decoder`, re-submit SPS/PPS and the retained IDR, and continue from a keyframe.
5. Extend `AirPlayReceiver.Snapshot` with diagnostic counters and decoder identity.
6. Run AirPlay unit tests and the full JVM suite; commit with `fix: recover from AirPlay decoder black screens`.

### Task 7: Add Warehouse, Live Refresh, and Diagnostics Web UI

**Files:**
- Modify: `web/package.json`
- Modify: `web/package-lock.json`
- Create: `web/src/lib/source-ranking.ts`
- Create: `web/src/lib/source-ranking.test.ts`
- Modify: `web/src/lib/api.ts`
- Modify: `web/src/App.tsx`

**Steps:**

1. Add Vitest and failing tests for fastest healthy leaf selection, stable warehouse ordering, and preserving a manual source selection.
2. Verify RED with `npm test -- --run`.
3. Extend API types for parent/child source metadata, `stateVersion`, decoder diagnostics, and `/api/diagnostics`.
4. Show warehouse parents with child health in source management. On search, render a warehouse selector sorted by health/latency, default to the fastest child, and rerun the current keyword when selection changes.
5. Pass `stateVersion` into `LiveView`; reload sources/catalog whenever it changes while preserving the selected live source when possible.
6. Add a diagnostics section to the existing device page for startup, AirPlay, source, and home errors.
7. Run Vitest and `npm run build`; commit with `feat: add warehouse and receiver diagnostics UI`.

### Task 8: Embed Assets and Verify the Release Candidate

**Files:**
- Modify generated assets under: `app/src/main/assets/web/`
- Modify: `README.md`

**Steps:**

1. Run `npm run build` in `web` and use the repository's existing asset-copy workflow to update Android assets.
2. Document single/multi-warehouse behavior, automatic fastest selection, API 19 TLS support, and the diagnostics location.
3. Run `./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug -PincludeTestAbi` with the SDK environment configured.
4. Run the API 19 x86 instrumentation smoke test and confirm the process remains foreground after a cold start.
5. Use `aapt dump badging` to confirm minSdk 17 and `unzip -l` to confirm `armeabi-v7a/libnukacast_airplay.so` plus current web assets.
6. Run `git diff --check` and inspect `git status --short`; commit with `chore: prepare API 19 compatibility release`.
