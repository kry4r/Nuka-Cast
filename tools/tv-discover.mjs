#!/usr/bin/env node
/**
 * Finds a TV running NukaCast on the local network.
 *
 * Why: a watchdog that needs the TV's IP typed in is a watchdog that silently watches nothing —
 * measured: `tools/tv-watch.mjs 192.168.5.3` collected 890 samples and every single one was
 * "not running (timeout)" because the TV had moved to another address.
 *
 * Three ways, cheapest first:
 *   1. `--host <ip>` (or the NUKACAST_HOST variable) is trusted as given;
 *   2. SSDP: the app announces itself as a DLNA MediaRenderer on 239.255.255.250:1900, so its
 *      LOCATION header carries the address it can be reached at;
 *   3. a /24 sweep of the machine's own subnets against the control port.
 *
 * Usage:
 *   node tools/tv-discover.mjs                 # print the first TV found
 *   node tools/tv-discover.mjs --all           # print every TV found
 *   node tools/tv-discover.mjs --host 10.0.2.15
 */

import { createSocket } from "node:dgram";
import { networkInterfaces } from "node:os";

const SSDP_ADDRESS = "239.255.255.250";
const SSDP_PORT = 1900;
const CONTROL_PORT = Number(process.env.NUKACAST_PORT || 9978);

/** Sends an M-SEARCH and collects the LOCATION headers the renderers answer with. */
function ssdpSearch(timeoutMs = 2500) {
  return new Promise((resolve) => {
    const found = new Set();
    const socket = createSocket({ type: "udp4", reuseAddr: true });
    const finish = () => {
      try { socket.close(); } catch {}
      resolve([...found]);
    };
    socket.on("message", (message) => {
      const text = message.toString("utf8");
      const match = /LOCATION:\s*(\S+)/i.exec(text);
      if (match) found.add(match[1].replace(/\/dlna\/description\.xml.*$/, ""));
    });
    socket.on("error", finish);
    socket.bind(() => {
      const query = [
        "M-SEARCH * HTTP/1.1",
        `HOST: ${SSDP_ADDRESS}:${SSDP_PORT}`,
        'MAN: "ssdp:discover"',
        "MX: 1",
        "ST: urn:schemas-upnp-org:device:MediaRenderer:1",
        "", "",
      ].join("\r\n");
      const payload = Buffer.from(query, "utf8");
      try {
        socket.send(payload, 0, payload.length, SSDP_PORT, SSDP_ADDRESS);
      } catch {
        finish();
      }
      setTimeout(finish, timeoutMs);
    });
  });
}

/**
 * True when this address answers as a *TV* running NukaCast.
 *
 * <p>Answering /api/status is not enough — measured: tools/preview-server.mjs (a development fixture
 * server that also lives on port 9978) looks exactly like a TV from the outside, and the watchdog
 * ended up watching fixtures. The layout endpoint only exists on a device.
 */
async function isNukaCast(base, timeoutMs = 1200) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(`${base}/api/debug/layout`, { signal: controller.signal });
    if (!response.ok) return false;
    const body = await response.json();
    // The device reports the views it drew; the fixture server has no such idea.
    return typeof body === "object" && body !== null && Array.isArray(body.views);
  } catch {
    return false;
  } finally {
    clearTimeout(timer);
  }
}

/** The /24 networks this machine is on, emulator addresses first (they are the usual test target). */
function localSubnets() {
  const subnets = [];
  for (const addresses of Object.values(networkInterfaces())) {
    for (const address of addresses || []) {
      if (address.family !== "IPv4" || address.internal) continue;
      const prefix = address.address.split(".").slice(0, 3).join(".");
      subnets.push({ prefix, own: address.address });
    }
  }
  subnets.sort((left, right) => {
    const emulator = (subnet) => (subnet.prefix === "10.0.2" ? 0 : 1);
    return emulator(left) - emulator(right);
  });
  return subnets;
}

/** Sweeps the given /24s for anything answering on the control port. */
async function sweep(subnets, { concurrency = 48, timeoutMs = 700 } = {}) {
  const found = [];
  for (const { prefix } of subnets) {
    const hosts = [];
    for (let last = 1; last <= 254; last += 1) hosts.push(`${prefix}.${last}`);
    let index = 0;
    const workers = new Array(Math.min(concurrency, hosts.length)).fill(0).map(async () => {
      while (index < hosts.length) {
        const host = hosts[index++];
        const base = `http://${host}:${CONTROL_PORT}`;
        if (await isNukaCast(base, timeoutMs)) {
          found.push(base);
          return;
        }
      }
    });
    await Promise.all(workers);
    if (found.length > 0) break;
  }
  return found;
}

const args = process.argv.slice(2);
const explicit = args.includes("--host")
  ? args[args.indexOf("--host") + 1]
  : process.env.NUKACAST_HOST || "";
const wantAll = args.includes("--all");

const addresses = new Set();
// The emulator convention first: `adb forward tcp:19978 tcp:9978` is what every tool in this repo
// documents, so a running emulator is found instantly instead of by sweeping 254 addresses.
if (!explicit && !process.env.NUKACAST_NO_LOCAL) {
  for (const candidate of [`http://127.0.0.1:${CONTROL_PORT}`, "http://127.0.0.1:19978"]) {
    if (await isNukaCast(candidate, 1500)) addresses.add(candidate);
  }
}
if (explicit) {
  addresses.add(explicit.startsWith("http") ? explicit : `http://${explicit.split(":")[0]}:${CONTROL_PORT}`);
}
if (addresses.size === 0) {
  for (const base of await ssdpSearch()) {
    if (await isNukaCast(base)) addresses.add(base);
  }
}
if (addresses.size === 0) {
  for (const base of await sweep(localSubnets())) addresses.add(base);
}

const list = [...addresses];
if (list.length === 0) {
  console.error("没有找到运行中的电视（SSDP 无应答，端口扫描也没有结果）");
  process.exit(1);
}
for (const base of wantAll ? list : list.slice(0, 1)) console.log(base);
