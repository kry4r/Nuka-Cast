#!/usr/bin/env node
/**
 * MCP server for debugging a running NukaCast instance over the network.
 *
 * The TV is the only place where some questions have answers: which configured site actually
 * responds, whether a stream URL returns 200 or 403 *from the device's network*, whether the
 * decoder accepted a format, what the platform's killer saw before it ended the process. This
 * exposes those through the app's /api/debug endpoints as MCP tools, so an agent can investigate
 * a real device instead of guessing from a pasted log.
 *
 * Configure in ~/.pi/agent/mcp.json:
 *
 *   {
 *     "mcpServers": {
 *       "nukacast": {
 *         "command": "node",
 *         "args": ["C:/Softwares/code/Nuka-Cast/tools/nukacast-mcp.mjs"],
 *         "env": { "NUKACAST_HOST": "192.168.1.50:9978" }
 *       }
 *     }
 *   }
 *
 * Environment:
 *   NUKACAST_HOST  host:port of the device control server (required; 9978 is the app default)
 *   NUKACAST_TIMEOUT_MS  per-request timeout, default 30000 (health sweeps need more: pass
 *                        `timeoutMs` to a tool call for a longer wait)
 */
import { createInterface } from "node:readline"

const HOST = (process.env.NUKACAST_HOST || "").trim().replace(/^https?:\/\//, "").replace(/\/+$/, "")
const DEFAULT_TIMEOUT_MS = Number(process.env.NUKACAST_TIMEOUT_MS || 30_000)
const PROTOCOL_VERSION = "2024-11-05"
const SERVER_INFO = { name: "nukacast", version: "1.0.0" }

const TOOLS = [
  {
    name: "nukacast_snapshot",
    description:
      "Everything about the running app in one call: status, device, process memory against the plugin budget, AirPlay counters, " +
      "HTTP stack state, newest stage traces, the previous run's end state, the post-mortem log, sites, sources, site health and the " +
      "player. Start here.",
    inputSchema: { type: "object", properties: { logs: { type: "boolean", description: "Include the last 60 log entries" } } },
  },
  {
    name: "nukacast_logs",
    description: "Read the app log buffer. level=WARN or ERROR narrows it; entries repeat-folded rows carry a `repeats` count.",
    inputSchema: {
      type: "object",
      properties: {
        level: { type: "string", enum: ["DEBUG", "INFO", "WARN", "ERROR", "all"] },
        limit: { type: "number", description: "How many of the newest entries (default 80)" },
      },
    },
  },
  {
    name: "nukacast_clear_logs",
    description: "Clear the log buffer before reproducing a problem, so a fresh trace starts empty.",
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "nukacast_sites",
    description: "Configured sites with type, searchability and the effective caps (search fan-out, plugin sites on home).",
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "nukacast_sources",
    description: "Configured sources with site counts, latency and error text, plus user-added live playlists.",
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "nukacast_refresh_sources",
    description: "Ask the device to re-fetch every configured source now and report the result per source.",
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "nukacast_search",
    description:
      "Search from the device, returning per-site failures as well as items. Optionally pin siteKeys and forceSites to bypass " +
      "recorded health verdicts.",
    inputSchema: {
      type: "object",
      properties: {
        keyword: { type: "string" },
        siteKeys: { type: "array", items: { type: "string" }, description: "Only these site keys" },
        sourceId: { type: "string" },
        forceSites: { type: "boolean", description: "Ignore site-health verdicts" },
        page: { type: "number" },
        pageSize: { type: "number" },
      },
      required: ["keyword"],
    },
  },
  {
    name: "nukacast_test_site",
    description: "Run one search against one site on the device and record the verdict. Use it to check a specific suspected site.",
    inputSchema: {
      type: "object",
      properties: { siteKey: { type: "string" }, keyword: { type: "string" } },
      required: ["siteKey"],
    },
  },
  {
    name: "nukacast_site_health",
    description: "Current site-health data: the running or last sweep's progress and results plus every stored verdict.",
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "nukacast_site_sweep",
    description:
      "Action run: measure sites on the device one by one and store the verdicts, which then steer search and home. " +
      "action=start|stop|clear. A full sweep of 140 sites takes minutes: poll nukacast_site_health or pass a large timeoutMs.",
    inputSchema: {
      type: "object",
      properties: {
        action: { type: "string", enum: ["start", "stop", "clear"] },
        limit: { type: "number", description: "Maximum sites to test (default 200)" },
        keyword: { type: "string" },
        pluginsOnly: { type: "boolean" },
        failedOnly: { type: "boolean", description: "Only re-test sites currently recorded as failing" },
        waitForCompletion: { type: "boolean", description: "Poll until the sweep finishes before returning" },
      },
      required: ["action"],
    },
  },
  {
    name: "nukacast_probe_url",
    description:
      "Fetch a URL from the device itself and report status, timing, headers, whether it looks like a playlist and a body preview. " +
      "This is the tool for \"the URL works on my PC but not on the TV\".",
    inputSchema: {
      type: "object",
      properties: { url: { type: "string" }, method: { type: "string", enum: ["GET", "HEAD"] } },
      required: ["url"],
    },
  },
  {
    name: "nukacast_play",
    description:
      "Start playback on the device, either from a URL or from siteKey + vodId (+ episodeIndex). Probes the stream first, so the " +
      "answer distinguishes an HTTP failure from a decoder failure.",
    inputSchema: {
      type: "object",
      properties: {
        url: { type: "string" },
        title: { type: "string" },
        siteKey: { type: "string" },
        vodId: { type: "string" },
        episodeIndex: { type: "number" },
      },
    },
  },
  {
    name: "nukacast_player",
    description: "Player state: title, URL, position, playback state and the last error.",
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "nukacast_epg",
    description:
      "Programme list of a live channel as the TV fetched it (what is on now and next), plus whether the " +
      "source has a guide at all.",
    inputSchema: {
      type: "object",
      properties: {
        sourceId: { type: "string", description: "Live source id (see nukacast_live_catalog)." },
        channelId: { type: "string", description: "Channel id from nukacast_live_catalog." },
      },
      required: ["sourceId", "channelId"],
    },
  },
  {
    name: "nukacast_live_catalog",
    description: "Live sources and their channels grouped as the TV lists them (ids for nukacast_epg).",
    inputSchema: {
      type: "object",
      properties: {
        sourceId: { type: "string", description: "Omit to list the sources instead of one source's channels." },
      },
    },
  },
  {
    name: "nukacast_live_search",
    description: "Find a channel by name or pinyin initials across a source's channels (the TV's own search).",
    inputSchema: {
      type: "object",
      properties: {
        query: { type: "string", description: "Channel name or initials, e.g. 湖南 or hnws." },
        source: { type: "string", description: "Optional: switch to the live source whose name contains this." },
      },
      required: ["query"],
    },
  },
  {
    name: "nukacast_settings",
    description:
      "Read or change the TV's playback settings: autoNextEpisode, quality (auto|highest|lowest), softDecoder. " +
      "Omit name/value to read them.",
    inputSchema: {
      type: "object",
      properties: {
        name: { type: "string", description: "autoNextEpisode | quality | softDecoder" },
        value: { type: "string", description: "1/0 for the switches, auto|highest|lowest for quality." },
      },
    },
  },
  {
    name: "nukacast_library",
    description: "Favourites and watch history as recorded on the TV (nukacast_favorite toggles one).",
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "nukacast_favorite",
    description: "Toggles a favourite on the TV, exactly like holding OK on a card does.",
    inputSchema: {
      type: "object",
      properties: {
        sourceId: { type: "string" },
        siteKey: { type: "string" },
        vodId: { type: "string" },
        name: { type: "string" },
        poster: { type: "string" },
        remarks: { type: "string" },
      },
      required: ["name", "vodId"],
    },
  },
  {
    name: "nukacast_dlna",
    description:
      "DLNA renderer state: whether discovery is running, how many M-SEARCH requests were answered, and " +
      "what is loaded (the form's control URL is in the reply, for tools/dlna-cast.mjs).",
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "nukacast_export",
    description: "Download the full text diagnostic bundle the TV's export button produces (same content, without touching the TV).",
    inputSchema: { type: "object", properties: {} },
  },
]

const readline = createInterface({ input: process.stdin, crlfDelay: Infinity })
readline.on("line", (line) => {
  const trimmed = line.trim()
  if (!trimmed) return
  let message
  try {
    message = JSON.parse(trimmed)
  } catch {
    return
  }
  void handle(message)
})

/*
 * The same tools are reachable from a shell, which is how a debug session starts before an MCP
 * client is configured:
 *
 *   NUKACAST_HOST=192.168.5.3:9978 node tools/nukacast-mcp.mjs --list
 *   NUKACAST_HOST=192.168.5.3:9978 node tools/nukacast-mcp.mjs --call nukacast_snapshot
 *   NUKACAST_HOST=192.168.5.3:9978 node tools/nukacast-mcp.mjs --call nukacast_search '{"keyword":"庆余年"}'
 */
const cliIndex = process.argv.indexOf("--call")
if (cliIndex >= 0) {
  const tool = process.argv[cliIndex + 1]
  let args = {}
  const rawArgs = process.argv[cliIndex + 2]
  if (rawArgs) {
    try {
      args = JSON.parse(rawArgs)
    } catch (error) {
      console.error(`参数不是合法 JSON：${error.message}`)
      process.exit(2)
    }
  }
  const result = await callTool(tool, args).catch((error) => ({ error: `${error.name}: ${error.message}` }))
  process.stdout.write(typeof result === "string" ? result : JSON.stringify(result, null, 2))
  // Closing the reader first keeps Node from asserting on teardown when stdout is a closed pipe
  // (e.g. `... --call nukacast_library | head`).
  readline.close()
  process.stdout.write("\n")
  process.exitCode = 0
}
if (process.argv.includes("--list")) {
  for (const tool of TOOLS) console.log(`${tool.name}\n    ${tool.description.split(/\n/)[0]}`)
  process.exitCode = 0
}

async function handle(message) {
  const { id, method, params } = message
  try {
    if (method === "initialize") {
      respond(id, {
        protocolVersion: PROTOCOL_VERSION,
        capabilities: { tools: {} },
        serverInfo: SERVER_INFO,
        instructions:
          "Debug a running NukaCast instance. Requires NUKACAST_HOST=host:port of the device control server.",
      })
      return
    }
    if (method === "notifications/initialized" || method === "initialized") return
    if (method === "tools/list") {
      respond(id, { tools: TOOLS })
      return
    }
    if (method === "tools/call") {
      const name = params?.name
      const args = params?.arguments ?? {}
      const result = await callTool(name, args)
      respond(id, {
        content: [{ type: "text", text: typeof result === "string" ? result : JSON.stringify(result, null, 2) }],
        isError: false,
      })
      return
    }
    if (method === "ping") {
      respond(id, {})
      return
    }
    respondError(id, -32601, `未实现的方法：${method}`)
  } catch (error) {
    respond(id, {
      content: [{ type: "text", text: `${error.name}: ${error.message}` }],
      isError: true,
    })
  }
}

async function callTool(name, args) {
  switch (name) {
    case "nukacast_snapshot":
      return request("GET", `/api/debug/snapshot${args.logs === false ? "?logs=0" : ""}`)
    case "nukacast_logs":
      return request("GET", `/api/debug/logs?level=${encodeURIComponent(args.level || "all")}&limit=${args.limit || 80}`)
    case "nukacast_clear_logs":
      return request("POST", "/api/debug/logs/clear", {})
    case "nukacast_sites":
      return request("GET", "/api/debug/sites")
    case "nukacast_sources":
      return request("GET", "/api/debug/sources")
    case "nukacast_refresh_sources":
      return request("POST", "/api/debug/sources/refresh", {}, 120_000)
    case "nukacast_search":
      return request("POST", "/api/debug/search", args, 90_000)
    case "nukacast_test_site":
      return request("POST", "/api/debug/site/test", args, 90_000)
    case "nukacast_site_health":
      return request("GET", "/api/debug/health")
    case "nukacast_site_sweep": {
      if (args.action === "stop") return request("POST", "/api/debug/health/stop", {})
      if (args.action === "clear") return request("POST", "/api/debug/health/clear", {})
      const started = await request("POST", "/api/debug/health/run", {
        limit: args.limit,
        keyword: args.keyword,
        pluginsOnly: args.pluginsOnly,
        failedOnly: args.failedOnly,
      })
      if (!args.waitForCompletion) return started
      return waitForSweep(started)
    }
    case "nukacast_probe_url":
      return request("POST", "/api/debug/probe", args, 60_000)
    case "nukacast_play":
      return request("POST", "/api/debug/play", args, 90_000)
    case "nukacast_player":
      return request("GET", "/api/debug/player")
    case "nukacast_epg": {
      const params = new URLSearchParams({ sourceId: args.sourceId, channelId: args.channelId })
      return request("GET", `/api/debug/epg?${params}`)
    }
    case "nukacast_live_catalog": {
      if (args.sourceId) {
        const params = new URLSearchParams({ sourceId: args.sourceId })
        return request("GET", `/api/live/catalog?${params}`)
      }
      return request("GET", "/api/live/sources")
    }
    case "nukacast_live_search": {
      const params = new URLSearchParams({ query: args.query })
      if (args.source) params.set("source", args.source)
      return request("GET", `/api/debug/live?${params}`)
    }
    case "nukacast_settings": {
      if (!args.name) return request("GET", "/api/settings")
      return request("POST", "/api/settings", { name: args.name, value: args.value ?? "" })
    }
    case "nukacast_library":
      return request("GET", "/api/library")
    case "nukacast_favorite":
      return request("POST", "/api/debug/favorite", {
        sourceId: args.sourceId,
        siteKey: args.siteKey,
        vodId: args.vodId,
        name: args.name,
        poster: args.poster,
        remarks: args.remarks,
      })
    case "nukacast_dlna":
      return request("GET", "/api/debug/dlna")
    case "nukacast_export":
      return requestText("GET", "/api/logs/export")
    default:
      throw new Error(`未知工具：${name}`)
  }
}

/** Polls a running sweep until it stops, so a tool call can return the finished table. */
async function waitForSweep(initial) {
  let state = initial
  const deadline = Date.now() + 30 * 60 * 1000
  while (state?.sweep?.running && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 3000))
    state = await request("GET", "/api/debug/health")
  }
  return state
}

async function request(method, path, body, timeoutMs) {
  if (!HOST) throw new Error("未设置 NUKACAST_HOST（形如 192.168.1.50:9978）")
  const response = await fetch(`http://${HOST}${path}`, {
    method,
    headers: body === undefined ? undefined : { "content-type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(timeoutMs || DEFAULT_TIMEOUT_MS),
  })
  const text = await response.text()
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${text.slice(0, 400)}`)
  try {
    return JSON.parse(text)
  } catch {
    return text
  }
}

async function requestText(method, path) {
  if (!HOST) throw new Error("未设置 NUKACAST_HOST（形如 192.168.1.50:9978）")
  const response = await fetch(`http://${HOST}${path}`, { method, signal: AbortSignal.timeout(60_000) })
  return response.text()
}

function respond(id, result) {
  if (id === undefined || id === null) return
  process.stdout.write(`${JSON.stringify({ jsonrpc: "2.0", id, result })}\n`)
}

function respondError(id, code, message) {
  if (id === undefined || id === null) return
  process.stdout.write(`${JSON.stringify({ jsonrpc: "2.0", id, error: { code, message } })}\n`)
}
