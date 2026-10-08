#!/usr/bin/env node
/**
 * Watches a TV running NukaCast over the LAN and records what happens to it.
 *
 * The app's own diagnostics only survive inside the app (the process may be killed), so this
 * external observer keeps polling and writes a JSONL log. When the process disappears between two
 * polls, the last sample — heap, RSS, threads, oom_adj, current stage — is the evidence of why.
 *
 * Usage:  node tools/tv-watch.mjs [host] [port] [intervalSeconds]
 *   node tools/tv-watch.mjs 192.168.5.3 9978 20
 */

import { appendFile, mkdir, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { execFile } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

const host = process.argv[2] || "";
const port = Number(process.argv[3] || 9978);
const intervalSeconds = Number(process.argv[4] || 20);
let base = host ? `http://${host.split(":")[0]}:${port}` : "";

/**
 * Finds the TV when its address moved.
 *
 * <p>A watchdog pointed at a stale address watches nothing: measured, one run collected 890 samples
 * of "not running (timeout)" because the TV was simply on another address. The address is therefore
 * re-discovered whenever the app has been unreachable for a while, and the change is logged.
 */
async function discover() {
  const script = path.join(path.dirname(fileURLToPath(import.meta.url)), "tv-discover.mjs");
  return await new Promise((resolve) => {
    execFile(process.execPath, [script], { timeout: 60000 }, (error, stdout) => {
      const first = String(stdout || "").trim().split(/\r?\n/)[0];
      resolve(error || !first ? "" : first.replace(/\/$/, ""));
    });
  });
}
const dir = path.join(process.cwd(), ".preview");
const out = path.join(dir, "tv-watch.jsonl");
const pidFile = path.join(dir, "tv-watch.pid");

async function get(pathname, timeoutMs = 6000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(base + pathname, { signal: controller.signal });
    if (!response.ok) return { error: `HTTP ${response.status}` };
    return await response.json();
  } catch (error) {
    return { error: error.name === "AbortError" ? "timeout" : String(error.message || error) };
  } finally {
    clearTimeout(timer);
  }
}

function compact(sample, extra) {
  const run = sample?.lastRun || {};
  const stages = (sample?.stages || []).slice(0, 4).map((s) => `${s.component}/${s.stage}=${s.status}`);
  return {
    at: new Date().toISOString(),
    version: extra.status?.version,
    state: extra.player?.state,
    title: extra.player?.title,
    positionMs: extra.player?.positionMs,
    error: extra.player?.error,
    playerTrack: extra.player?.videoWidth ? `${extra.player.videoWidth}x${extra.player.videoHeight}` : "",
    lastRunEndedCleanly: run.endedCleanly,
    lastRunSeconds: run.startedAt && run.endedAt ? Math.round((run.endedAt - run.startedAt) / 1000) : undefined,
    stages,
    javaCrash: (sample?.javaCrash || "").slice(0, 400),
  };
}

async function main() {
  if (!existsSync(dir)) await mkdir(dir, { recursive: true });
  await writeFile(pidFile, String(process.pid), "utf8");
  const startedAt = Date.now();
  console.log(`tv-watch: polling ${base} every ${intervalSeconds}s → ${out}`);
  let wasUp = false;
  let emptyPolls = 0;
  for (;;) {
    const [status, player, diagnostics] = await Promise.all([
      get("/api/status"),
      get("/api/player"),
      get("/api/diagnostics"),
    ]);
    const up = !status.error;
    const line = compact(diagnostics.error ? {} : diagnostics, { status, player });
    line.app = up ? "up" : "down";
    if (!up) line.reason = status.error;

    // A death is only interesting when it happened between two polls of a running app.
    if (wasUp && !up) {
      line.event = "app-disappeared";
      console.log(`[${line.at}] app disappeared (${status.error})`);
    }
    if (up && line.javaCrash) line.event = "java-crash";
    await appendFile(out, JSON.stringify(line) + "\n", "utf8");

    if (up) {
      emptyPolls = 0;
      console.log(
        `[${line.at}] up v${line.version} state=${line.state} pos=${line.positionMs} ` +
          `title=${String(line.title || "").slice(0, 24)} crash=${line.javaCrash ? "yes" : "no"}`
      );
    } else {
      emptyPolls++;
      if (emptyPolls === 1 || emptyPolls % 15 === 0) {
        console.log(`[${line.at}] not running (${status.error}) — waiting`);
      }
      // Nothing has answered for a while: the TV may simply be on another address, which is how one
      // run of this tool spent six hours reporting a TV that was never there.
      if (emptyPolls === 3 || emptyPolls % 300 === 0) {
        const found = await discover();
        if (found && found !== base) {
          console.log(`[${line.at}] address changed: ${base} → ${found}`);
          base = found;
          emptyPolls = 0;
        }
      }
    }
    wasUp = up;
    const elapsed = (Date.now() - startedAt) / 1000;
    await new Promise((resolve) => setTimeout(resolve, intervalSeconds * 1000));
    if (process.env.TV_WATCH_MAX_SECONDS && elapsed > Number(process.env.TV_WATCH_MAX_SECONDS)) {
      console.log("tv-watch: time limit reached");
      return;
    }
  }
}

main().catch((error) => {
  console.error("tv-watch failed:", error);
  process.exit(1);
});
