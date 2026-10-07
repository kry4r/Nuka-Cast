#!/usr/bin/env node
/**
 * Fetches TVBox config URLs, decodes their payload and reports what is actually inside.
 *
 * Written for choosing which community configs to recommend: the popular "单仓" URLs hand out
 * 90-140 sites, and almost all of those are plugin sites (type 3) whose JARs need Android 5+ and
 * therefore cannot load on an Android 4.4 TV at all. Counting plugins before recommending a source
 * avoids telling people to install something most of which cannot run.
 *
 *   node tools/probe-tvbox-config.mjs <url> [url ...]
 *   node tools/probe-tvbox-config.mjs --limit 3 <url>     # also test-search the first 3 sites
 */
const TIMEOUT_MS = 20_000;
const UA = "okhttp/3.12.13";

const args = process.argv.slice(2);
let limit = 0;
const urls = [];
for (let i = 0; i < args.length; i++) {
  if (args[i] === "--limit") limit = Number(args[++i]);
  else urls.push(args[i]);
}
if (urls.length === 0) {
  console.error("用法: node tools/probe-tvbox-config.mjs [--limit N] <config-url> [...]");
  process.exit(2);
}

for (const url of urls) {
  const started = Date.now();
  try {
    const text = await fetchConfig(url);
    const json = decode(text);
    if (!json) {
      console.log(`BAD  ${url}\n     payload 不是可解析的 TVBox 配置（${text.slice(0, 40).replace(/\s+/g, " ")}）`);
      continue;
    }
    const sites = json.sites || [];
    const plugins = sites.filter((site) => Number(site.type) === 3);
    const cms = sites.filter((site) => Number(site.type) === 0 || Number(site.type) === 1);
    const spider = json.spider || json.spider2 || "";
    console.log(
      `OK   ${String(Date.now() - started).padStart(5)}ms ${url}\n` +
      `     站点 ${sites.length}（CMS ${cms.length} · 插件 ${plugins.length}）· 直播 ${(json.lives || []).length} · ` +
      `解析 ${(json.parses || []).length} · spider ${spider ? spider.slice(0, 60) : "无"}`,
    );
    if (cms.length > 0) {
      console.log(`     CMS: ${cms.slice(0, 8).map((site) => site.name).join("、")}${cms.length > 8 ? " …" : ""}`);
    }
    if (limit > 0) await testSites([...cms, ...plugins].slice(0, limit));
  } catch (error) {
    console.log(`FAIL ${String(Date.now() - started).padStart(5)}ms ${url}\n     ${error.name}: ${error.message}`);
  }
}

async function fetchConfig(url) {
  const response = await fetch(url, {
    redirect: "follow",
    signal: AbortSignal.timeout(TIMEOUT_MS),
    headers: { "user-agent": UA, accept: "*/*" },
  });
  const buffer = Buffer.from(await response.arrayBuffer());
  if (buffer.length >= 2 && buffer[0] === 0xff && buffer[1] === 0xd8) {
    throw new Error(`服务器返回图片（${buffer.length} 字节），说明该地址对当前网络做了拦截`);
  }
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return buffer.toString("utf8");
}

/** TVBox configs may be raw JSON, hex, or base64, sometimes with leading comments. */
function decode(text) {
  const cleaned = text.trim().replace(/^\uFEFF/, "");
  const candidates = [cleaned];
  const withoutComments = cleaned.replace(/^\s*(\/\/|#).*$/gm, "").trim();
  candidates.push(withoutComments);
  const hex = cleaned.replace(/\s/g, "");
  if (/^[0-9a-fA-F]{400,}$/.test(hex) && hex.length % 2 === 0) {
    try { candidates.push(Buffer.from(hex, "hex").toString("utf8")); } catch { /* ignore */ }
  }
  if (/^[A-Za-z0-9+/=\s]{400,}$/.test(cleaned)) {
    try { candidates.push(Buffer.from(cleaned, "base64").toString("utf8")); } catch { /* ignore */ }
  }
  for (const candidate of candidates) {
    try {
      const parsed = JSON.parse(candidate);
      if (parsed && typeof parsed === "object") return parsed;
    } catch { /* try the next form */ }
  }
  return null;
}

async function testSites(sites) {
  for (const site of sites) {
    const api = String(site.api || "");
    const isPlugin = Number(site.type) === 3;
    const label = `${site.name}${isPlugin ? "(插件)" : ""}`;
    if (isPlugin) {
      // The app runs these through its own JAR/JS host; here only the download is checked.
      if (!api.startsWith("http")) {
        console.log(`     · ${label}: 需要应用内插件运行时，跳过`);
        continue;
      }
      try {
        const response = await fetch(api, {
          signal: AbortSignal.timeout(TIMEOUT_MS),
          headers: { "user-agent": UA },
        });
        const body = Buffer.from(await response.arrayBuffer());
        const kind = /^\s*(PK|dex)/i.test(body.slice(0, 4).toString("latin1")) || body[0] === 0x50
          ? "JAR" : "未知";
        console.log(`     · ${label}: ${response.status} ${body.length} 字节 ${kind}`);
      } catch (error) {
        console.log(`     · ${label}: 下载失败 ${error.name}`);
      }
      continue;
    }
    const base = api.replace(/\/+$/, "");
    const url = `${base}?ac=detail&wd=${encodeURIComponent("庆余年")}`;
    const started = Date.now();
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(TIMEOUT_MS), headers: { "user-agent": UA } });
      const body = await response.text();
      let hits = -1;
      let playable = false;
      try {
        const parsed = JSON.parse(body.replace(/^\uFEFF/, ""));
        const list = parsed.list || [];
        hits = list.length;
        playable = list.some((item) => /\.(m3u8|mp4)(\?|#|$)/.test(String(item.vod_play_url || "")));
      } catch { /* not JSON */ }
      console.log(
        `     · ${label}: HTTP ${response.status} · ${hits >= 0 ? `${hits} 条` : "非 JSON"} · ` +
        `${Date.now() - started}ms${playable ? " · 有直连播放地址" : ""}`,
      );
    } catch (error) {
      console.log(`     · ${label}: 搜索失败 ${error.name}`);
    }
  }
}
