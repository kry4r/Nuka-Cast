#!/usr/bin/env node
/**
 * A DLNA control point, for casting a URL to the TV and for checking the renderer.
 *
 * The app is a DLNA MediaRenderer, so any phone or desktop player can push media to it. This script
 * does the same over SOAP, which makes the feature testable without a phone in hand.
 *
 * Usage:
 *   node tools/dlna-cast.mjs list                       # find renderers on the LAN
 *   node tools/dlna-cast.mjs info  [baseUrl]            # description + transport state
 *   node tools/dlna-cast.mjs play  <url> [baseUrl]      # cast a URL and start it
 *   node tools/dlna-cast.mjs stop  [baseUrl]
 *
 * Braces around discovery: SSDP is multicast, which does not cross into an emulator's NAT. In that
 * case pass the device's address directly (for the emulator: http://localhost:19978 after
 * `adb forward tcp:9978 tcp:9978`).
 */

import dgram from "node:dgram";

const GROUP = "239.255.255.250";
const PORT = 1900;
const AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1";
const RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1";

function log(...parts) {
  console.log(...parts);
}

async function discover(timeoutMs = 4000) {
  const socket = dgram.createSocket({ type: "udp4", reuseAddr: true });
  const found = new Map();
  const search = [
    "M-SEARCH * HTTP/1.1",
    `HOST: ${GROUP}:${PORT}`,
    'MAN: "ssdp:discover"',
    "MX: 2",
    "ST: urn:schemas-upnp-org:device:MediaRenderer:1",
    "",
    "",
  ].join("\r\n");
  await new Promise((resolve) => socket.bind(resolve));
  socket.on("message", (message) => {
    const text = message.toString("utf8");
    const location = /LOCATION:\s*(\S+)/i.exec(text)?.[1];
    if (location) found.set(location, text);
  });
  const payload = Buffer.from(search, "utf8");
  socket.send(payload, PORT, GROUP);
  await new Promise((resolve) => setTimeout(resolve, timeoutMs));
  socket.close();
  return [...found.keys()];
}

async function soap(baseUrl, service, action, args = {}) {
  const body = `<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:${action} xmlns:u="${service}">${
    Object.entries(args).map(([k, v]) => `<${k}>${escapeXml(v)}</${k}>`).join("")
  }</u:${action}></s:Body></s:Envelope>`;
  const response = await fetch(`${baseUrl}/dlna/control/${service.split(":").slice(3, 4)[0]}`, {
    method: "POST",
    headers: {
      "content-type": 'text/xml; charset="utf-8"',
      soapaction: `"${service}#${action}"`,
    },
    body,
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`${action} → HTTP ${response.status}: ${text.slice(0, 200)}`);
  const out = {};
  for (const match of text.matchAll(/<(\w+)>([^<]*)<\/\1>/g)) out[match[1]] = match[2];
  return out;
}

function escapeXml(value) {
  return String(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

async function waitForPlaying(baseUrl, seconds = 30) {
  for (let i = 0; i < seconds; i++) {
    await new Promise((r) => setTimeout(r, 1000));
    const info = await soap(baseUrl, AV_TRANSPORT, "GetPositionInfo", { InstanceID: 0 });
    const state = await soap(baseUrl, AV_TRANSPORT, "GetTransportInfo", { InstanceID: 0 });
    log(
      `  t+${i + 1}s state=${state.CurrentTransportState} pos=${info.RelTime || "0:00:00"} ` +
        `duration=${info.TrackDuration || "?"}`
    );
    if (state.CurrentTransportState === "PLAYING" && info.RelTime && info.RelTime !== "0:00:00") return true;
    if (state.CurrentTransportState === "STOPPED" && i > 5) return false;
  }
  return false;
}

async function main() {
  const [command, ...rest] = process.argv.slice(2);
  if (command === "list") {
    const locations = await discover();
    log(locations.length === 0 ? "no renderer answered SSDP" : "renderers:");
    for (const location of locations) log("  " + location);
    return;
  }

  if (command === "info" || !command) {
    const baseUrl = rest[0] || "http://localhost:9978";
    const description = await (await fetch(`${baseUrl}/dlna/description.xml`)).text();
    const name = /<friendlyName>([^<]*)</.exec(description)?.[1];
    const state = await soap(baseUrl, AV_TRANSPORT, "GetTransportInfo", { InstanceID: 0 });
    const position = await soap(baseUrl, AV_TRANSPORT, "GetPositionInfo", { InstanceID: 0 });
    const volume = await soap(baseUrl, RENDERING_CONTROL, "GetVolume", { InstanceID: 0, Channel: "Master" });
    log(`${name || "renderer"} @ ${baseUrl}`);
    log(`  state=${state.CurrentTransportState} position=${position.RelTime} duration=${position.TrackDuration}`);
    log(`  uri=${position.TrackURI || "(none)"} volume=${volume.CurrentVolume}`);
    return;
  }

  if (command === "play") {
    const url = rest[0];
    const baseUrl = rest[1] || "http://localhost:9978";
    if (!url) throw new Error("usage: play <url> [baseUrl]");
    const metadata = `<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" ` +
      `xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">` +
      `<item id="0" parentID="-1" restricted="1"><dc:title>DLNA 测试</dc:title>` +
      `<upnp:class>object.item.videoItem</upnp:class>` +
      `<res protocolInfo="http-get:*:application/vnd.apple.mpegurl:*">${escapeXml(url)}</res></item></DIDL-Lite>`;
    log(`casting ${url}`);
    await soap(baseUrl, AV_TRANSPORT, "SetAVTransportURI", {
      InstanceID: 0,
      CurrentURI: url,
      CurrentURIMetaData: metadata,
    });
    await soap(baseUrl, AV_TRANSPORT, "Play", { InstanceID: 0, Speed: 1 });
    const playing = await waitForPlaying(baseUrl);
    log(playing ? "PASS  the TV is playing the cast media" : "FAIL  the TV did not start playing");
    process.exit(playing ? 0 : 1);
  }

  if (command === "stop") {
    const baseUrl = rest[0] || "http://localhost:9978";
    await soap(baseUrl, AV_TRANSPORT, "Stop", { InstanceID: 0 });
    log("stopped");
    return;
  }

  throw new Error(`unknown command: ${command}`);
}

main().catch((error) => {
  console.error("dlna-cast failed:", error.message);
  process.exit(1);
});
