#!/usr/bin/env node
/**
 * Walks every television screen — including the ones behind a key press — and reports what is on them.
 *
 * Why: the console and the phone both look fine while the thing a viewer actually sees is a 10-foot UI
 * driven by a remote. This drives the remote, so a screen cannot be "verified" while never having been
 * looked at: each entry names the keys that reach it, and the run ends with a screenshot per screen, the
 * view count, the layout problems the app reports, and where the focus sits.
 *
 * Usage:
 *   adb forward tcp:19978 tcp:9978
 *   node tools/tv-pages.mjs                    # every screen
 *   node tools/tv-pages.mjs home settings      # only these
 *   node tools/tv-pages.mjs --list             # print the table
 *
 * Screenshots land in .preview/pages/<name>.png.
 */
import { execFileSync } from "node:child_process"
import { mkdirSync } from "node:fs"

const base = (process.env.NUKACAST_BASE || "http://127.0.0.1:19978").replace(/\/$/, "")
const ADB = process.env.ADB || "adb"
const outDir = ".preview/pages"

// keys: Android key codes sent before looking (19 up, 20 down, 21 left, 22 right, 23 OK, 4 back).
// toFirstCard: walk the focus down onto a card first (the rows of chips above the grid differ per site).
// fromPageStart: put the focus on the page's first control before pressing anything.
// opensSheet: this screen opens a modal, which the next screen has to close.
const SCREENS = [
  { name: "home", navigate: "home", settle: 12000 },
  { name: "movies", navigate: "movies", settle: 9000 },
  { name: "movies-recent", navigate: "movies", filter: "最近更新", settle: 7000 },
  { name: "movies-drama", navigate: "movies", filter: "短剧", settle: 15000 },
  { name: "movies-favorites", navigate: "movies", filter: "收藏", settle: 7000 },
  { name: "detail", navigate: "movies", settle: 8000, toFirstCard: true, keys: [23], after: 7000, opensSheet: true },
  { name: "detail-episodes", navigate: "movies", settle: 8000, toFirstCard: true, keys: [23], after: 8000, opensSheet: true },
  { name: "live", navigate: "live", settle: 9000 },
  { name: "live-guide", navigate: "live", settle: 9000, fromPageStart: true, keys: [22, 23], after: 5000, opensSheet: true },
  { name: "live-search", navigate: "live", settle: 9000, fromPageStart: true, keys: [20, 23], after: 4000, opensSheet: true },
  // The TV console has no separate 片库 page: it is the 收藏 view of the movies page.
  { name: "library", navigate: "movies", filter: "收藏", settle: 8000 },
  { name: "cast", navigate: "cast", settle: 6000 },
  { name: "settings", navigate: "settings", settle: 9000 },
  { name: "settings-bottom", navigate: "settings", settle: 9000, fromPageStart: true,
    keys: [20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20], after: 3000 },
  // 查看日志 is the ninth row of the settings list, and every row is reachable by walking down.
  { name: "logs", navigate: "settings", settle: 6000, fromPageStart: true,
    keys: [20, 20, 20, 20, 20, 20, 20, 20, 20, 23], after: 5000, opensSheet: true },
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
  const device = "/data/local/tmp/nukacast-page.png"
  try {
    execFileSync(ADB, ["shell", "screencap", "-p", device], { stdio: "ignore" })
    execFileSync(ADB, ["pull", device, path], { stdio: "ignore" })
    return path
  } catch (error) {
    return `(截图失败：${String(error.message).split("\n")[0]})`
  }
}

/**
 * Sends a remote key through the app's own key handling.
 *
 * <p>Not {@code adb shell input keyevent}: on this emulator those injections are dropped whenever the
 * system is busy (logcat shows "ACTION_UP but key was not down"), which silently turns a page walk into a
 * walk that pressed nothing — measured, a settings screen reported the first control as focused after
 * eleven presses that were supposed to reach the last one. The app's dispatch path is the same one a real
 * remote goes through, and it either works or reports that it did not.
 */
async function press(code) {
  const result = await call("GET", `/api/debug/key?code=${code}`)
  if (result.data && result.data.handled === false) {
    // Not fatal: some keys are meant to fall through to the platform's focus search.
    return false
  }
  return true
}

/**
 * Walks the focus down until it sits on a card, and says whether it got there.
 *
 * <p>A fixed number of key presses is not enough: a page has several rows of chips above the grid (site,
 * category, year, area, language) and how many depends on the site — pressing down twice from the first
 * chip landed on another chip, so a screen meant to open a detail sheet pressed OK on a filter instead.
 */
async function focusFirstCard(times = 16) {
  for (let step = 0; step < times; step++) {
    const layout = await call("GET", "/api/debug/layout")
    if (String(layout.data?.focus || "").includes("MediaCardView")) return true
    await call("GET", "/api/debug/focus?target=down")
    await sleep(350)
  }
  return false
}

async function main() {
  if (process.argv.includes("--list")) {
    for (const screen of SCREENS) {
      console.log(`${screen.name.padEnd(18)} navigate=${screen.navigate} filter=${screen.filter || "-"} keys=${(screen.keys || []).join(",") || "-"}`)
    }
    return
  }
  const wanted = process.argv.slice(2).filter((value) => !value.startsWith("--"))
  const screens = wanted.length > 0 ? SCREENS.filter((screen) => wanted.includes(screen.name)) : SCREENS
  mkdirSync(outDir, { recursive: true })

  // A screen is only captured when the app shell is up: leftover playback shows a video surface instead.
  const before = await call("GET", "/api/status")
  if (before.data && before.data.activeMedia) {
    await call("GET", "/api/debug/player/action?name=stop")
    await sleep(2500)
  }

  const rows = []
  for (const screen of screens) {
    // Close whatever modal the previous screen left, through the endpoint that only closes modals: BACK
    // on a plain page closes the window and leaves the app, and every later screen then photographs a
    // black screen (measured — a whole walk came back empty because of it).
    await call("GET", "/api/debug/close")
    await sleep(1000)
    // Walking onto a channel on the live page starts playing it, and the player covers the whole window:
    // every screen after that is a photograph of a video surface (measured). Playback is stopped first.
    const playing = await call("GET", "/api/status")
    if (playing.data && playing.data.activeMedia) {
      // Stopping is enough: the player leaves the screen by itself. A BACK after it would exit the app,
      // which then photographs as fourteen empty screens (measured).
      await call("GET", "/api/debug/player/action?name=stop")
      await sleep(2000)
    }
    await call("GET", `/api/debug/navigate?page=${screen.navigate}` +
      (screen.filter ? `&filter=${encodeURIComponent(screen.filter)}` : ""))
    await sleep(screen.settle || 5000)
    if (screen.fromPageStart) {
      await call("GET", "/api/debug/focus?target=page")
      await sleep(500)
    }
    if (screen.toFirstCard) await focusFirstCard()
    for (const code of screen.keys || []) {
      await press(code)
      await sleep(700)
    }
    await sleep(screen.after || 1500)
    const layout = await call("GET", "/api/debug/layout")
    const views = layout.data?.views || []
    const problems = (layout.data?.problems || []).map(String)
    // The report's own focus line: the per-view flag is filled in while walking the tree and misses the
    // focused view whenever its branch is pruned, which made every screen look unfocused.
    const focusText = String(layout.data?.focus || "")
    // Widgets that claim a state they should not have: two sidebar entries marked as the current page,
    // or a button left pressed.
    const selected = views.filter((view) => view.states && view.states.selected)
      .map((view) => String(view.text || view.view).slice(0, 14))
    const pressed = views.filter((view) => view.states && view.states.pressed)
      .map((view) => String(view.text || view.view).slice(0, 14))
    rows.push({
      name: screen.name,
      views: views.length,
      problems,
      focus: focusText.slice(0, 34) || "(无)",
      focusText: "",
      selected,
      pressed,
      screenshot: claim(screen.name),
      texts: views.filter((view) => view.text).map((view) => String(view.text)),
    })
  }

  console.log("页面".padEnd(20) + "视图".padStart(5) + "问题".padStart(5) +
    "  焦点".padEnd(30) + "选中/按下")
  for (const row of rows) {
    console.log(row.name.padEnd(20) + String(row.views).padStart(5) + String(row.problems.length).padStart(5) +
      "  " + (row.focus + " " + row.focusText).padEnd(28) +
      " 选中[" + row.selected.join(",") + "] 按下[" + row.pressed.join(",") + "]")
    for (const problem of row.problems.slice(0, 3)) console.log("        · " + problem.slice(0, 110))
    if (row.screenshot.startsWith("(")) console.log("        · " + row.screenshot)
  }

  const bad = rows.filter((row) => row.problems.length > 0 || row.views < 6)
  console.log(bad.length === 0 ? "\n所有页面：无布局问题。" : `\n需要处理的有 ${bad.length} 个页面。`)
}

main().catch((error) => {
  console.error("失败：", error.message)
  process.exit(1)
})
