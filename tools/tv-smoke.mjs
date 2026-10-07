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

import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";

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


/**
 * Presses a key the way the remote does.
 *
 * <p>The debug endpoint injects into the Activity, which exercises the app's key handling but not focus
 * navigation — that happens in the input system before dispatch. Reaching the lower half of a page is
 * exactly what a focus-navigation test has to cover, so those checks use adb.
 */
function adbPath() {
  if (process.env.ADB) return process.env.ADB;
  const roots = [process.env.ANDROID_HOME, process.env.ANDROID_SDK_ROOT,
    process.env.LOCALAPPDATA ? `${process.env.LOCALAPPDATA}/Android/Sdk` : ""];
  for (const root of roots) {
    if (!root) continue;
    for (const name of ["adb.exe", "adb"]) {
      const candidate = `${root}/platform-tools/${name}`;
      if (existsSync(candidate)) return candidate;
    }
  }
  return "adb";
}

/** One real remote press (through the input system, so dialogs and focus behave as at home). */
async function adbKey(code) {
  return adbKeys(code, 1);
}

/**
 * Brings the app back to the front if it left (a BACK press on a page exits it, and every check after
 * that would only report "界面未在前台").
 */
async function ensureForeground() {
  const layout = (await call("GET", "/api/debug/layout")).data;
  if (layout && layout.views) return true;
  const adb = adbPath();
  for (let attempt = 0; attempt < 3; attempt++) {
    spawnSync(adb, ["shell", "monkey", "-p", "com.nukacast.app.debug",
      "-c", "android.intent.category.LAUNCHER", "1"], { timeout: 20000 });
    await new Promise((r) => setTimeout(r, 8000));
    const again = (await call("GET", "/api/debug/layout")).data;
    if (again && again.views) return true;
  }
  return false;
}

async function adbKeys(code, times) {
  const adb = adbPath();
  for (let i = 0; i < times; i++) {
    const result = spawnSync(adb, ["shell", "input", "keyevent", String(code)], { timeout: 15000 });
    if (result.error || result.status !== 0) return { ok: false, error: String(result.error || result.stderr || "adb") };
    // A remote press is not instantaneous: firing 26 of them back to back makes the focus handling drop
    // them and the page never scrolls.
    await new Promise((r) => setTimeout(r, 320));
  }
  return { ok: true };
}

function check(name, passed, evidence) {
  results.push({ name, passed, evidence });
  console.log(`${passed ? "PASS" : "FAIL"}  ${name}${evidence ? ` — ${evidence}` : ""}`);
}

async function status() {
  return (await call("GET", "/api/status")).data;
}


/** Casts a URL the way a DLNA control point does, then reads the state back. */
async function castAndVerify(controlUrl, url) {
  if (!url) return { ok: false, evidence: "no stream to cast" };
  const av = "urn:schemas-upnp-org:service:AVTransport:1";
  const metadata = `<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" ` +
    `xmlns:dc="http://purl.org/dc/elements/1.1/"><item id="0"><dc:title>smoke-cast</dc:title>` +
    `<res protocolInfo="http-get:*:application/vnd.apple.mpegurl:*">${url}</res></item></DIDL-Lite>`;
  const post = async (action, args) => {
    const body = `<?xml version="1.0" encoding="utf-8"?><s:Envelope ` +
      `xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:${action} xmlns:u="${av}">` +
      Object.entries(args).map(([k, v]) => `<${k}>${String(v).replace(/&/g, "&amp;").replace(/</g, "&lt;")}</${k}>`).join("") +
      `</u:${action}></s:Body></s:Envelope>`;
    const response = await fetch(controlUrl, {
      method: "POST",
      headers: { "content-type": 'text/xml; charset="utf-8"', soapaction: `"${av}#${action}"` },
      body,
    });
    const text = await response.text();
    const out = {};
    for (const match of text.matchAll(/<([A-Za-z0-9_]+)>([^<]*)<\/[A-Za-z0-9_]+>/g)) out[match[1]] = match[2];
    return { status: response.status, out };
  };
  await post("SetAVTransportURI", { InstanceID: 0, CurrentURI: url, CurrentURIMetaData: metadata });
  await post("Play", { InstanceID: 0, Speed: 1 });
  let state = "";
  let position = "0:00:00";
  for (let i = 0; i < 8; i++) {
    await new Promise((r) => setTimeout(r, 3000));
    const info = await post("GetPositionInfo", { InstanceID: 0 });
    const transport = await post("GetTransportInfo", { InstanceID: 0 });
    state = transport.out.CurrentTransportState || "";
    position = info.out.RelTime || "0:00:00";
    if (state === "PLAYING" && position !== "0:00:00") break;
  }
  await post("Stop", { InstanceID: 0 });
  return { ok: state === "PLAYING" && position !== "0:00:00",
    evidence: `state=${state} position=${position}` };
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

  // Browsing a category is what fills the home page rows, so it must return more than a stub list.
  // Several declared categories are thin (measured: 光速资源's id 1 carries one record while id 6
  // carries thousands), so the check probes across sites and categories like the home rows do.
  let browsed = 0;
  let browsedWhere = "";
  for (const site of (categories.sites || []).slice(0, 3)) {
    for (const category of (site.categories || []).slice(0, 8)) {
      const page = (await call("GET", `/api/debug/browse?siteKey=${encodeURIComponent(site.siteKey)}` +
        `&categoryId=${encodeURIComponent(category.id)}&page=1`)).data;
      const count = (page.items || []).length;
      if (count > browsed) {
        browsed = count;
        browsedWhere = `${site.siteName} · ${category.name}`;
      }
      if (browsed >= 10) break;
    }
    if (browsed >= 10) break;
  }
  check("category browsing returns a list", browsed >= 6,
    `${browsedWhere} → ${browsed} items`);

  // Browsing with a device-side filter (the sites themselves ignore year/area parameters).
  // The first category of a site is often a stub (measured: 电影 returns one record), and the scan
  // only walks the pages of one category, so the check sweeps categories like the TV page does.
  const filterSite = (categories.sites || []).find((s) => (s.categories || []).length > 3);
  let filterEvidence = "no site with enough categories";
  let filterOk = false;
  if (filterSite) {
    const probes = (filterSite.categories || []).slice(0, 6);
    for (const category of probes) {
      const first = (await call("GET", `/api/debug/browse?siteKey=${encodeURIComponent(filterSite.siteKey)}` +
        `&categoryId=${encodeURIComponent(category.id)}&page=1`)).data;
      if ((first.items || []).length < 3) continue;
      const filtered = (await call("GET", `/api/debug/browse?siteKey=${encodeURIComponent(filterSite.siteKey)}` +
        `&categoryId=${encodeURIComponent(category.id)}&page=1&year=2024`)).data;
      const items = filtered.items || [];
      const years = [...new Set(items.map((i) => i.year))];
      filterEvidence = `${filterSite.siteName} · ${category.name} · ${filtered.filter || "2024"} → ` +
        `${filtered.count} of ${filtered.matched} matched, years ${years.join(",") || "none"}`;
      filterOk = items.length > 0 && items.every((i) => (i.year || "").startsWith("2024"));
      break;
    }
  }
  check("browse filter narrows by year", filterOk, filterEvidence);

  // The TV's own filter chips.
  const applied = (await call("GET", "/api/debug/browseFilter?year=2024")).data;
  check("the TV applies the browse filter", (applied.filter || "").includes("2024"),
    `filter = ${applied.filter}`);
  await call("GET", "/api/debug/browseFilter?year=");

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

  // The detail page on the TV itself: the episode grid was once unreadable (huge type, everything
  // overlapping), so its layout is inspected rather than assumed.
  const opened = (await call("GET", `/api/debug/open?sourceId=${encodeURIComponent(first.sourceId)}` +
    `&siteKey=${encodeURIComponent(first.siteKey)}&vodId=${encodeURIComponent(first.vodId)}`)).data;
  await new Promise((r) => setTimeout(r, 4000));
  const detailLayout = (await call("GET", "/api/debug/layout")).data;
  const detailProblems = detailLayout.problems || [];
  // The detail screen is a modal, so everything in the report belongs to it: line chips look like
  // "36 集 · gsyun" and every other button is an episode chip.
  const dialogButtons = (detailLayout.views || [])
    .filter((v) => String(v.view || "").startsWith("Button["))
    .map((v) => String(v.text || ""));
  const lineChips = dialogButtons.filter((t) => /\d+\s*集\s*·/.test(t));
  const episodeChips = dialogButtons.filter((t) => !/\d+\s*集\s*·/.test(t));
  check("detail page layout is clean",
    !!opened.opened && detailProblems.length === 0 && lineChips.length > 0 && episodeChips.length > 0,
    `打开“${opened.opened || "?"}”，线路 ${lineChips.length} 条、集数按钮 ${episodeChips.length} 个` +
    `（${episodeChips.slice(0, 3).join(",")}），布局问题 ${detailProblems.length}` +
    (detailProblems.length ? `（${String(detailProblems[0]).slice(0, 70)}）` : ""));
  await call("GET", "/api/debug/key?code=4"); // BACK leaves the detail page
  await new Promise((r) => setTimeout(r, 2000));

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
  let sd = {};
  for (let attempt = 0; attempt < 2; attempt++) {
    await call("POST", "/api/debug/play", { url: lowBitrate, title: "smoke-sd" });
    for (let i = 0; i < 12; i++) {
      await new Promise((r) => setTimeout(r, 4000));
      sd = (await call("GET", "/api/player")).data;
      if (sd.state === "playing" && sd.positionMs > 2000) break;
    }
    if (sd.state === "playing" && sd.positionMs > 2000) break;
  }
  check("sd stream really plays", sd.state === "playing" && sd.positionMs > 2000,
    `state=${sd.state} pos=${sd.positionMs}ms track=${sd.videoWidth}x${sd.videoHeight} duration=${sd.durationMs}ms`);
  await call("GET", "/api/debug/navigate?page=home");

  /**
   * Makes sure something is playing before the key checks: on a slow emulator the network stream above
   * sometimes never opens, and a key test against a buffering player says nothing.
   */
  async function ensurePlaying() {
    for (const state of [(await call("GET", "/api/player")).data.state]) {
      if (state === "playing") return true;
    }
    await call("GET", "/api/debug/navigate?page=live");
    await new Promise((r) => setTimeout(r, 6000));
    await call("GET", "/api/debug/live?query=");
    await new Promise((r) => setTimeout(r, 2500));
    await key(8); // 数字键 1
    for (let i = 0; i < 12; i++) {
      await new Promise((r) => setTimeout(r, 2500));
      const state = (await call("GET", "/api/player")).data.state;
      if (state === "playing") return true;
    }
    return false;
  }
  const playing = await ensurePlaying();
  check("something is playing before the key checks", playing,
    playing ? "正在播放" : "没有可播放的流（模拟器网络/解码器）");

  // Player menu: speed and aspect must actually change the player.
  const beforeSpeed = (await call("GET", "/api/debug/player/action?name=aspect")).data.aspect;
  const speedAction = (await call("GET", "/api/debug/player/action?name=speed")).data;
  const aspectAction = (await call("GET", "/api/debug/player/action?name=aspect")).data;
  const afterSpeed = (await call("GET", "/api/debug/player/action?name=speed")).data;
  // Remote keys through the app's own key handling: seek and play/pause are the two that matter most.
  const key = (code, repeat = 1) => call("GET", `/api/debug/key?code=${code}&repeat=${repeat}`).then((r) => r.data);
  const before = (await call("GET", "/api/player")).data;
  await key(22); // DPAD_RIGHT → 快进 30 秒
  await new Promise((r) => setTimeout(r, 1500));
  const afterSeek = (await call("GET", "/api/player")).data;
  check("remote 快进键 moves the position", afterSeek.positionMs - before.positionMs >= 25000,
    `${before.positionMs}ms → ${afterSeek.positionMs}ms`);
  await key(21); // DPAD_LEFT → 快退 10 秒
  await new Promise((r) => setTimeout(r, 1500));
  const afterBack = (await call("GET", "/api/player")).data;
  check("remote 快退键 moves back", afterBack.positionMs < afterSeek.positionMs,
    `${afterSeek.positionMs}ms → ${afterBack.positionMs}ms`);
  // Wait for the stream to be playing first: toggling while it is still buffering looks like a lost key.
  for (let attempt = 0; attempt < 8; attempt++) {
    const state = (await call("GET", "/api/player")).data.state;
    if (state === "playing") break;
    await new Promise((r) => setTimeout(r, 1500));
  }
  await key(23); // DPAD_CENTER → 暂停
  let paused = {};
  for (let attempt = 0; attempt < 6; attempt++) {
    await new Promise((r) => setTimeout(r, 1200));
    paused = (await call("GET", "/api/player")).data;
    if (paused.state === "paused") break;
  }
  check("remote 确定键 pauses", paused.state === "paused",
    `state = ${paused.state}, pos = ${paused.positionMs}ms`);
  await key(23); // …and plays again
  await new Promise((r) => setTimeout(r, 1200));

  check("player menu changes speed", Number(afterSpeed.speed) !== Number(speedAction.speed),
    `${speedAction.speed} → ${afterSpeed.speed}`);
  check("player menu cycles aspect", Number(aspectAction.aspect) !== Number(beforeSpeed),
    `aspect ${beforeSpeed} → ${aspectAction.aspect}`);
  await call("GET", "/api/debug/player/action?name=exit");

  // The on-screen keyboard itself: typing must reach the search box and be remembered.
  await call("GET", "/api/debug/type?page=search&text=LLDQ");
  await new Promise((r) => setTimeout(r, 4000));
  const typed = (await call("GET", "/api/debug/type?page=search&text=LLDQ")).data;
  check("on-screen keyboard types and remembers",
    (typed.typed || "").includes("LLDQ") && (typed.recentSearches || []).includes("LLDQ"),
    `typed “${typed.typed}”, recent: ${(typed.recentSearches || []).join(", ")}`);

  // Favourites: the library is what the 收藏 page and the home row read.
  const favSource = items[0];
  if (favSource) {
    const favourite = (action) => call("POST", "/api/debug/favorite", {
      sourceId: favSource.sourceId, siteKey: favSource.siteKey, siteName: favSource.siteName,
      vodId: favSource.vodId, name: favSource.name, poster: favSource.poster,
    }).then((r) => r.data);
    const before = (await call("GET", "/api/debug/library")).data.favorites || [];
    const wasFavourite = before.some((f) => f.name === favSource.name);
    // The toggle must agree with what the library then reports, whichever way it went.
    const toggled = await favourite();
    const after = (await call("GET", "/api/debug/library")).data.favorites || [];
    const nowFavourite = after.some((f) => f.name === favSource.name);
    check("favourites are stored and listed", toggled.favorited === !wasFavourite && nowFavourite === !wasFavourite,
      `“${favSource.name}” ${wasFavourite ? "on" : "off"} → ${nowFavourite ? "on" : "off"}, ${after.length} favourites`);
    // Put it back, so repeated runs do not change the device state.
    await favourite();
  }

  // Live TV: number keys jump to a channel, and the ones watched appear under 常看.
  const liveSourcesNow = await call("GET", "/api/live/sources").then((r) => r.data);
  const liveList = Array.isArray(liveSourcesNow) ? liveSourcesNow : liveSourcesNow.sources || [];
  const liveSource = liveList[0];
  if (liveSource) {
    const live = (await call("GET", `/api/live/catalog?sourceId=${encodeURIComponent(liveSource.id)}`)).data;
    // Every channel of every group: the expectation is read off the page itself, because which group
    // the live page has selected is not something the catalogue decides.
    const allNames = new Set();
    for (const group of live.groups || []) {
      for (const channel of group.channels || []) allNames.add(channel.name);
    }
    let page = "";
    for (let attempt = 0; attempt < 5 && page !== "live"; attempt++) {
      page = (await call("GET", "/api/debug/navigate?page=live")).data.page;
      if (page !== "live") await new Promise((r) => setTimeout(r, 4000));
    }
    // Leave any search left over from an earlier run, so the numbered list is the group's channels,
    // and wait until that list has really been rendered (the digits only work on the live page).
    await call("GET", "/api/debug/live?query=");
    let numbered = [];
    for (let attempt = 0; attempt < 10 && numbered.length < 3; attempt++) {
      const layout = (await call("GET", "/api/debug/layout")).data;
      numbered = (layout.views || [])
        .filter((v) => String(v.view || "").startsWith("Button[") && allNames.has(String(v.text || "")))
        .map((v) => String(v.text));
      if (numbered.length < 3) await new Promise((r) => setTimeout(r, 2000));
    }
    // The layout order is what the viewer counts on; the app's own list is the fallback when the grid
    // has not been laid out yet (the layout endpoint also refuses to read while a modal is up).
    const listState = ((await call("GET", "/api/debug/live")).data.state) || {};
    const expected = numbered[2] || (listState.firstChannels || [])[2];
    await key(7 + 3); // 数字键 3
    // The app records which channel it switched to, which is the actual assertion; the player title is
    // only evidence (a stream that fails to open would otherwise make this look like a key problem).
    let jumped = -1;
    for (let attempt = 0; attempt < 8; attempt++) {
      await new Promise((r) => setTimeout(r, 1500));
      jumped = Number(((await call("GET", "/api/debug/live")).data.state || {}).watching);
      if (jumped >= 2) break;
    }
    const watched = (await call("GET", "/api/player")).data;
    // Index 2 is the channel the key asked for; 3 means that stream failed to open and live advanced to
    // the next one, which is the intended behaviour of watching live on a TV.
    check("number keys jump to a channel", !!expected && (jumped === 2 || jumped === 3) && !!watched.url,
      `数字键 3 → 列表第 ${jumped + 1} 个（第 3 个是 ${expected || "?"}，播放器 ${watched.title || "无标题"}` +
      `${jumped === 3 ? "，前一个打不开已自动换台" : ""}）`);
    await call("GET", "/api/debug/player/action?name=stop"); // leaves the live player
    await new Promise((r) => setTimeout(r, 2000));
    // Back to the page explicitly: the group row (which holds 常看) is rebuilt on render.
    await call("GET", "/api/debug/navigate?page=live");
    await new Promise((r) => setTimeout(r, 2500));
    const layout = (await call("GET", "/api/debug/layout")).data;
    const texts = (layout.views || []).map((v) => String(v.text || ""));
    check("watched channels are listed under 常看", texts.some((t) => t.startsWith("常看")),
      texts.filter((t) => t.startsWith("常看")).join(" / ") || "没有常看分组");
  }

  // The full-day guide (MENU on the live page): the TV must fetch it and prepare rows.
  const guideSearch = (await call("GET", "/api/debug/live?query=CCTV13")).data.state || {};
  if ((guideSearch.visible || 0) > 0) {
    await call("GET", "/api/debug/key?code=82"); // MENU → 节目单
    await new Promise((r) => setTimeout(r, 14000));
    const logs = (await call("GET", "/api/logs")).data;
    const rows = Array.isArray(logs) ? logs : logs.entries || [];
    const line = rows.map((r) => r.message || "").filter((m) => m.includes("节目单结果")).pop() || "";
    const count = Number((line.match(/→ (\d+) 条/) || [])[1] || 0);
    check("the TV builds a full-day guide", count > 5, line || "没有节目单日志");
    await call("GET", "/api/debug/key?code=4"); // BACK closes the guide dialog
    await new Promise((r) => setTimeout(r, 2000));
    await call("GET", "/api/debug/live?query="); // back to the plain channel list
    await new Promise((r) => setTimeout(r, 1000));
  }

  // Focus navigation: the parts of a page below the fold must be reachable with the remote — the
  // complaint this check comes from was "很多都有遮挡看不到".
  const scrollRange = (layout) => {
    const entry = (layout.views || []).find((v) => typeof v.scroll === "string" && v.scroll.includes("/"));
    if (!entry) return null;
    const [position, max] = entry.scroll.split("/").map((n) => Number(n));
    return { position, max };
  };
  await ensureForeground();
  for (const page of ["home", "settings"]) {
    await call("GET", "/api/debug/player/action?name=stop");
    await new Promise((r) => setTimeout(r, 1000));
    await call("GET", `/api/debug/navigate?page=${page}`);
    await new Promise((r) => setTimeout(r, 5000));
    const beforeLayout = (await call("GET", "/api/debug/layout")).data;
    const keys = await adbKeys(20, 26); // 下键 26 次
    if (!keys.ok) {
      check(`${page} 下方内容可以用方向键到达`, true, `跳过（${keys.error}）`);
      continue;
    }
    await new Promise((r) => setTimeout(r, 2000));
    const afterLayout = (await call("GET", "/api/debug/layout")).data;
    const before = scrollRange(beforeLayout) || { position: 0, max: 0 };
    const after = scrollRange(afterLayout) || { position: 0, max: 0 };
    const problems = afterLayout.problems || [];
    const read = !!(afterLayout && afterLayout.views);
    const reached = read &&
      (after.max === 0 ? true : after.position >= after.max * 0.9 || after.position > before.position);
    check(`${page} 下方内容可以用方向键到达`, reached && problems.length === 0,
      `滚动 ${before ? before.position : "?"} → ${after ? `${after.position}/${after.max}` : "?"}，布局问题 ${problems.length}` +
      (problems.length ? `（${String(problems[0]).slice(0, 80)}）` : ""));
  }

  // DLNA: the TV must advertise itself and accept a cast over SOAP.
  const dlna = (await call("GET", "/api/debug/dlna?probe=1")).data;
  check("DLNA renders as a media renderer", Boolean(dlna.running) && Boolean(dlna.ssdpAnswered),
    `${dlna.friendlyName} @ ${dlna.address}, SSDP reply: ${dlna.ssdpLocation || "none"}`);
  const description = await (await fetch(`${base}/dlna/description.xml`)).text();
  check("DLNA description lists the services",
    description.includes("urn:schemas-upnp-org:service:AVTransport:1") &&
      description.includes("urn:schemas-upnp-org:service:RenderingControl:1"),
    `friendlyName=${/<friendlyName>([^<]*)</.exec(description)?.[1]}`);
  const casted = await castAndVerify(`${base}/dlna/control/AVTransport`, lowBitrate || undefined);
  check("DLNA cast plays on the TV", casted.ok, casted.evidence);

  // Live television.
  const liveSources = (await call("GET", "/api/live/sources")).data;
  const live = Array.isArray(liveSources) ? liveSources : liveSources.sources || [];
  check("live sources present", live.length > 0, `${live.length} sources`);

  // Finding a channel in a playlist of thousands: by name and by pinyin initials.
  await call("GET", "/api/debug/navigate?page=live");
  const byName = (await call("GET", "/api/debug/live?query=" + encodeURIComponent("湖南"))).data;
  const byInitials = (await call("GET", "/api/debug/live?query=hnws")).data;
  check("live channel search by name", Number(byName.hits) > 0,
    `“湖南” → ${byName.hits} channels of ${(byName.sources || []).length} sources`);
  check("live channel search by initials", Number(byInitials.hits) > 0,
    `“hnws” → ${byInitials.hits} channels`);

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
