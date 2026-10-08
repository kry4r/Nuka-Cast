#!/usr/bin/env node
/**
 * Walks every television page, captures it, and reports what the layout inspector thinks.
 *
 * Why: the console and the phone both look fine while the thing a viewer actually sees is a 10-foot UI
 * driven by a remote. This drives the remote, so a page cannot be "verified" while never having been
 * looked at.
 *
 * Usage:
 *   adb forward tcp:19978 tcp:9978
 *   node tools/tv-pages.mjs                # all pages
 *   node tools/tv-pages.mjs home live      # only these
 *
 * Screenshots land in .preview/pages/<page>.png; the summary says how many views each page has, whether
 * anything overlaps or is cut off, and where the focus is.
 */
import { execFileSync } from "node:child_process"
import { mkdirSync } from "node:fs"

const base = (process.env.NUKACAST_BASE || "http://127.0.0.1:19978").replace(/\/$/, "")
const ADB = process.env.ADB || "adb"
const outDir = ".preview/pages"

// name → how to reach it: page to open, and the movies view to switch to afterwards.
const PAGES = [
  { name: "home", page: "home" },
  { name: "movies-browse", page: "movies" },
  { name: "movies-recent", page: "movies", filter: "最近更新" },
  { name: "movies-drama", page: "movies", filter: "短剧" },
  { name: "movies-favorites", page: "movies", filter: "收藏" },
  { name: "live", page: "live" },
  { name: "cast", page: "cast" },
  { name: "settings", page: "settings" },
]

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

async function call(method, pathname, payload) {
  const response = await fetch(base + pathname, {
    method,
    headers: payload ? { "content-type": "application/json" } : undefined,
    body: payload ? JSON.stringify(payload) : undefined,
  })
  const text = await response.text()
  try {
    return { status: response.status, data: JSON.parse(text) }
  } catch {
    return { status: response.status, data: text }
  }
}

function claim(name) {
  const path = `${outDir}/${name}.png`
  // screencap to a file then pull it: `exec-out cat` drops the connection on this emulator ("error: closed").
  const device = `/data/local/tmp/nukacast-page.png`
  try {
    execFileSync(ADB, ["shell", "screencap", "-p", device], { stdio: "ignore" })
    execFileSync(ADB, ["pull", device, path], { stdio: "ignore" })
    return path
  } catch (error) {
    return `(截图失败：${String(error.message).split("\n")[0]})`
  }
}

async function main() {
  const wanted = process.argv.slice(2)
  const pages = wanted.length > 0 ? PAGES.filter((entry) => wanted.includes(entry.name)) : PAGES
  mkdirSync(outDir, { recursive: true })

  // A page is only captured when the app shell is up: leftover playback shows a video surface instead.
  const before = await call("GET", "/api/status")
  if (before.data && before.data.activeMedia) {
    await call("GET", "/api/debug/player/action?name=stop")
    await sleep(2500)
  }

  const rows = []
  for (const entry of pages) {
    const query = `/api/debug/navigate?page=${entry.page}`
    await call("GET", query)
    if (entry.filter) await call("GET", `${query}&filter=${encodeURIComponent(entry.filter)}`)
    await sleep(entry.name === "home" ? 9000 : 5000)
    const layout = await call("GET", "/api/debug/layout")
    const views = layout.data?.views || []
    const problems = layout.data?.problems || []
    const screenshot = claim(entry.name)
    rows.push({
      page: entry.name,
      views: views.length,
      problems: problems.length,
      focus: String(layout.data?.focus || "").slice(0, 28),
      screenshot,
      first: problems.slice(0, 2).map((p) => String(p).slice(0, 60)),
    })
  }

  console.log("页面".padEnd(9) + "视图".padStart(5) + "问题".padStart(5) + "  焦点  截图")
  for (const row of rows) {
    console.log(
      row.page.padEnd(9) +
        String(row.views).padStart(5) +
        String(row.problems).padStart(5) +
        "  " +
        row.focus.padEnd(28) +
        " " +
        row.screenshot,
    )
    for (const problem of row.first) console.log("        · " + problem)
  }
  const bad = rows.filter((row) => row.problems > 0 || row.views < 6)
  console.log(bad.length === 0 ? "\n所有页面：无布局问题。" : `\n需要看的有 ${bad.length} 个页面。`)
}

main().catch((error) => {
  console.error("失败：", error.message)
  process.exit(1)
})
