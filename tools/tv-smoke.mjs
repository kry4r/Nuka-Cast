#!/usr/bin/env node
/**
 * End-to-end smoke test against the running app, driven through its own debug API.
 *
 * Runs against whatever is at NUKACAST_URL (default http://localhost:19978, i.e. an emulator with
 * `adb forward`). Each check is a named assertion with the evidence it produced, so a regression is
 * visible as a failing line rather than a vague "it did not work".
 *
 * Usage: node tools/tv-smoke.mjs [baseUrl]
 */

const base = process.argv[2] || process.env.NUKACAST_URL || "http://localhost:19978";
const results = [];

async function call(method, pathname, payload, timeoutMs = 240000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(base + pathname, {
      method,
      signal: controller.signal,
      headers: payload ? { "content-type": "application/json" } : undefined,
      body: payload ? JSON.stringify(payload) : undefined,
    });
    const text = await response.text();
    let data = text;
    try {
      data = JSON.parse(text);
    } catch {
      /* keep the raw text */
    }
    return { status: response.status, data };
  } catch (error) {
    return { status: 0, data: { error: String(error.message || error) } };
  } finally {
    clearTimeout(timer);
  }
}

function check(name, passed, evidence) {
  results.push({ name, passed, evidence });
  console.log(`${passed ? "PASS" : "FAIL"}  ${name}${evidence ? ` — ${evidence}` : ""}`);
}

async function status() {
  return (await call("GET", "/api/status")).data;
}

async function main() {
  console.log(`smoke: ${base}`);
  const statusInfo = await status();
  if (!statusInfo || statusInfo.error || !statusInfo.version) {
    console.error("app is not reachable — start it first (emulator + adb forward)");
    process.exit(2);
  }
  check("app reachable", true, `v${statusInfo.version}, ${statusInfo.siteCount} sites`);

  const sources = (await call("GET", "/api/sources")).data;
  const list = Array.isArray(sources) ? sources : sources.sources || [];
  check("at least one source enabled", list.some((s) => s.enabled), `${list.length} sources`);

  // Home categories come from the CMS itself (?ac=list), not from the config file.
  const categories = (await call("GET", "/api/debug/categories")).data;
  const sitesWithCategories = (categories.sites || []).filter((s) => (s.categories || []).length > 0);
  check("categories available", sitesWithCategories.length > 0,
    `${sitesWithCategories.length} sites, e.g. ${sitesWithCategories[0]?.siteName} ${sitesWithCategories[0]?.categories?.length} categories`);

  // Search by title.
  const search = (await call("POST", "/api/search", { keyword: "流浪地球" })).data;
  const items = search.items || [];
  check("search by title", items.length > 0, `${items.length} results, first “${items[0]?.name}”`);

  // Search by initials, which only works through the local title index.
  const initials = (await call("POST", "/api/search", { keyword: "LLDQ" })).data;
  check("search by initials", (initials.items || []).length > 0,
    `expanded to “${initials.expandedKeyword}”, ${(initials.items || []).length} results`);

  if (items.length === 0) {
    finish();
    return;
  }

  // Detail + episode list.
  const first = items[0];
  const detail = (await call("POST", "/api/detail",
    { sourceId: first.sourceId, siteKey: first.siteKey, vodId: first.vodId })).data;
  const lines = detail.playSources || [];
  const episodes = (lines[0]?.episodes || []).length;
  check("detail with playable lines", lines.length > 0 && episodes > 0,
    `${lines.length} lines, first line “${lines[0]?.name}” with ${episodes} episodes`);

  // Playback resolution must return a usable address (or an explained failure).
  if (lines.length > 0 && episodes > 0) {
    const started = Date.now();
    const play = (await call("POST", "/api/play", {
      sourceId: first.sourceId, siteKey: first.siteKey, flag: lines[0].name,
      episodeId: lines[0].episodes[0].id, vodId: first.vodId, name: first.name, title: first.name,
    })).data;
    const seconds = ((Date.now() - started) / 1000).toFixed(1);
    check("playback address resolved", !play.error && !!play.url,
      `${seconds}s ${String(play.url || play.error).slice(0, 80)}`);

    // Give the decoder a few seconds; either it plays or it must explain itself.
    let player = {};
    for (let i = 0; i < 6; i++) {
      await new Promise((r) => setTimeout(r, 5000));
      player = (await call("GET", "/api/player")).data;
      if (player.state === "playing" && player.positionMs > 1000) break;
      if (player.state === "error") break;
    }
    const played = player.state === "playing" && player.positionMs > 1000;
    check("playback either plays or explains", played || (player.state === "error" && !!player.error),
      `${player.state} pos=${player.positionMs}ms track=${player.videoWidth}x${player.videoHeight} ${String(player.error || "").slice(0, 60)}`);
    await call("GET", "/api/debug/navigate?page=home");
  }

  // Playback that must actually render: a low-bitrate HLS stream. The emulator's decoder refuses
  // 720p, so a real play proof needs a stream it can handle.
  const lowBitrate = "https://test-streams.mux.dev/x36xhzz/url_2/193039199_mp4_h264_aac_ld_7.m3u8";
  await call("POST", "/api/debug/play", { url: lowBitrate, title: "smoke-sd" });
  let sd = {};
  for (let i = 0; i < 8; i++) {
    await new Promise((r) => setTimeout(r, 4000));
    sd = (await call("GET", "/api/player")).data;
    if (sd.state === "playing" && sd.positionMs > 2000) break;
  }
  check("sd stream really plays", sd.state === "playing" && sd.positionMs > 2000,
    `state=${sd.state} pos=${sd.positionMs}ms track=${sd.videoWidth}x${sd.videoHeight} duration=${sd.durationMs}ms`);
  await call("GET", "/api/debug/navigate?page=home");

  // Player menu: speed and aspect must actually change the player.
  const beforeSpeed = (await call("GET", "/api/debug/player/action?name=aspect")).data.aspect;
  const speedAction = (await call("GET", "/api/debug/player/action?name=speed")).data;
  const aspectAction = (await call("GET", "/api/debug/player/action?name=aspect")).data;
  const afterSpeed = (await call("GET", "/api/debug/player/action?name=speed")).data;
  check("player menu changes speed", Number(afterSpeed.speed) !== Number(speedAction.speed),
    `${speedAction.speed} → ${afterSpeed.speed}`);
  check("player menu cycles aspect", Number(aspectAction.aspect) !== Number(beforeSpeed),
    `aspect ${beforeSpeed} → ${aspectAction.aspect}`);
  await call("GET", "/api/debug/player/action?name=exit");

  // Live television.
  const liveSources = (await call("GET", "/api/live/sources")).data;
  const live = Array.isArray(liveSources) ? liveSources : liveSources.sources || [];
  check("live sources present", live.length > 0, `${live.length} sources`);

  finish();
}

function finish() {
  const failed = results.filter((r) => !r.passed);
  console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
  process.exit(failed.length === 0 ? 0 : 1);
}

main().catch((error) => {
  console.error("smoke failed:", error);
  process.exit(1);
});
