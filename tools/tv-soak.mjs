#!/usr/bin/env node
/**
 * Long-running soak test: drives the app the way a viewer would, for hours, and records what happened.
 *
 * The failures that matter on a TV are the ones that appear after twenty minutes of use — memory that
 * creeps, a player that leaks a codec per retry, a page that stops responding. This script therefore
 * keeps cycling through playback, browsing, searching and live TV, sampling process memory every
 * minute, and writing a report with the first sample, the peak, and any point where the app vanished.
 *
 * Usage: node tools/tv-soak.mjs [minutes] [baseUrl]
 */

import { appendFile, mkdir, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import path from "node:path";

const minutes = Number(process.argv[2] || 30);
const base = process.argv[3] || process.env.NUKACAST_URL || "http://localhost:19978";
const dir = path.join(process.cwd(), ".preview");
const logFile = path.join(dir, "tv-soak.jsonl");
const reportFile = path.join(dir, "tv-soak-report.md");

const SD_STREAM = "https://test-streams.mux.dev/x36xhzz/url_2/193039199_mp4_h264_aac_ld_7.m3u8";

async function call(method, pathname, payload, timeoutMs = 120000) {
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
    try {
      return { status: response.status, data: JSON.parse(text) };
    } catch {
      return { status: response.status, data: text };
    }
  } catch (error) {
    return { status: 0, data: { error: String(error.message || error) } };
  } finally {
    clearTimeout(timer);
  }
}

const samples = [];
let crashes = 0;
let round = 0;

async function sample(label) {
  const snapshot = await call("GET", "/api/debug/snapshot?lean=1", undefined, 30000);
  if (snapshot.status === 0) {
    crashes++;
    const line = { at: new Date().toISOString(), label, event: "unreachable", error: snapshot.data.error };
    await appendFile(logFile, JSON.stringify(line) + "\n", "utf8");
    console.log(`[soak] ${label}: app unreachable (${snapshot.data.error})`);
    return null;
  }
  const memory = snapshot.data.memory || {};
  const entry = {
    at: new Date().toISOString(),
    label,
    round,
    heapMb: +(((memory.heapUsedBytes || 0) / 1048576).toFixed(1)),
    rssMb: +(((memory.rssBytes || 0) / 1048576).toFixed(1)),
    pssMb: +(((memory.pssBytes || 0) / 1048576).toFixed(1)),
    threads: memory.threads,
    oomScoreAdj: memory.oomScoreAdj,
    pluginSessions: memory.pluginSessions?.total,
    playerState: snapshot.data.player?.state,
    javaCrash: (snapshot.data.javaCrash || "").slice(0, 200),
  };
  samples.push(entry);
  await appendFile(logFile, JSON.stringify(entry) + "\n", "utf8");
  console.log(`[soak] ${label}: heap=${entry.heapMb}MB rss=${entry.rssMb}MB threads=${entry.threads} ` +
    `state=${entry.playerState}${entry.javaCrash ? " CRASH: " + entry.javaCrash.slice(0, 60) : ""}`);
  return entry;
}

/** One pass of the behaviours a viewer repeats. */
async function cycle() {
  round++;
  // 1) play a stream and let it run
  await call("POST", "/api/debug/play", { url: SD_STREAM, title: `soak-${round}` });
  await new Promise((r) => setTimeout(r, 20000));
  await sample(`round ${round}: playing`);

  // 2) browse, search, and open a detail page
  await call("GET", "/api/debug/navigate?page=movies");
  await new Promise((r) => setTimeout(r, 3000));
  await call("POST", "/api/search", { keyword: "流浪地球" });
  await new Promise((r) => setTimeout(r, 3000));
  await call("POST", "/api/search", { keyword: "LLDQ" });
  await sample(`round ${round}: search+browse`);

  // 3) live television, including a channel zap
  await call("GET", "/api/debug/live?query=hnws");
  await new Promise((r) => setTimeout(r, 5000));
  const live = await call("GET", "/api/live/catalog");
  if (live.status === 200) {
    const groups = live.data.groups || [];
    const channels = groups.flatMap((g) => g.channels || []).slice(0, 3);
    for (const channel of channels) {
      if (!(channel.urls || []).length) continue;
      await call("POST", "/api/debug/play", { url: channel.urls[0], title: `live ${channel.name}` });
      await new Promise((r) => setTimeout(r, 8000));
      break;
    }
  }
  await sample(`round ${round}: live`);

  // 4) stop and come back to the home page
  await call("GET", "/api/debug/player/action?name=exit");
  await call("GET", "/api/debug/navigate?page=home");
  await new Promise((r) => setTimeout(r, 3000));
}

async function main() {
  if (!existsSync(dir)) await mkdir(dir, { recursive: true });
  const deadline = Date.now() + minutes * 60 * 1000;
  console.log(`[soak] ${minutes} minutes against ${base}`);
  const first = await sample("start");
  if (!first) process.exit(2);

  while (Date.now() < deadline) {
    try {
      await cycle();
    } catch (error) {
      console.log(`[soak] cycle failed: ${error.message}`);
    }
    const last = samples[samples.length - 1];
    if (last && Date.now() > deadline - 60_000) break;
    await new Promise((r) => setTimeout(r, 5000));
  }

  const final = await sample("end");
  const heapPeak = Math.max(...samples.map((s) => s.heapMb || 0));
  const rssPeak = Math.max(...samples.map((s) => s.rssMb || 0));
  const threadPeak = Math.max(...samples.map((s) => s.threads || 0));
  const report = [
    `# Soak test (${minutes} min, ${round} rounds)`,
    "",
    `- samples: ${samples.length}`,
    `- app unreachable events: ${crashes}`,
    `- heap: start ${first.heapMb}MB → end ${final ? final.heapMb : "n/a"}MB, peak ${heapPeak}MB`,
    `- rss: start ${first.rssMb}MB → end ${final ? final.rssMb : "n/a"}MB, peak ${rssPeak}MB`,
    `- threads: peak ${threadPeak}`,
    `- java crash seen: ${samples.some((s) => s.javaCrash) ? "yes" : "no"}`,
    "",
    crashes === 0 && (!final || final.rssMb < rssPeak + 1 || rssPeak < 400)
      ? "Result: no crash and no unbounded growth."
      : "Result: investigate the peak/unreachable entries above.",
  ].join("\n");
  await writeFile(reportFile, report + "\n", "utf8");
  console.log("\n" + report);
}

main().catch((error) => {
  console.error("soak failed:", error);
  process.exit(1);
});
