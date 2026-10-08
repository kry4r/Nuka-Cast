#!/usr/bin/env node
/**
 * Restarts the television app in the window where it is most fragile, and reports whether it survived.
 *
 * Why: the process outlives its window (the casting endpoints are a service, and start-on-boot can bring
 * them up with no window at all), so background work started by an activity can come back to a dead one.
 * Measured on the device: exiting during the first home load left a pending render that called into the
 * shut-down image loader and killed the process with RejectedExecutionException — a crash the app's own
 * crash dialog reports, and the reason the TV was leaving playback on its own.
 *
 * Usage:
 *   adb forward tcp:19978 tcp:9978
 *   node tools/tv-restart-stress.mjs [rounds] [baseUrl]
 *
 * Exits non-zero when a round ends with a dead process, a fatal exception in logcat, or an app that is
 * unreachable afterwards.
 */
import { execFileSync } from "node:child_process"

const rounds = Number(process.argv[2] || 5)
const base = (process.argv[3] || "http://127.0.0.1:19978").replace(/\/$/, "")
const ADB = process.env.ADB || "adb"
const PACKAGE = process.env.NUKACAST_PACKAGE || "com.nukacast.app.debug"

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

function adb(args, options = {}) {
  return execFileSync(ADB, args, { encoding: "utf8", timeout: 60000, ...options }).trim()
}

async function reachable() {
  try {
    const response = await fetch(`${base}/api/status`, { signal: AbortSignal.timeout(5000) })
    const body = await response.json()
    return !!body.version
  } catch {
    return false
  }
}

function pid() {
  try {
    // API 19 has no pidof; ps is in every toolbox.
    const rows = adb(["shell", "ps"]).split("\n")
    const row = rows.find((line) => line.includes(PACKAGE))
    return row ? row.trim().split(/\s+/)[1] : ""
  } catch {
    return ""
  }
}

function fatalLines() {
  try {
    const dump = execFileSync(ADB, ["logcat", "-d", "-t", "400"], { encoding: "utf8", timeout: 60000 })
    const lines = dump.split("\n")
    const findings = []
    for (let index = 0; index < lines.length; index++) {
      if (!lines[index].includes("FATAL EXCEPTION")) continue
      findings.push(lines.slice(index, index + 4).join(" ").slice(0, 200))
    }
    return findings
  } catch {
    return []
  }
}

async function main() {
  console.log(`restart stress: ${rounds} rounds against ${base}`)
  const failures = []
  for (let round = 1; round <= rounds; round++) {
    try {
      adb(["logcat", "-c"])
    } catch {
      // A device that cannot clear the log still reports its current one.
    }
    adb(["shell", "am", "force-stop", PACKAGE])
    await sleep(2000)
    adb(["shell", "monkey", "-p", PACKAGE, "-c", "android.intent.category.LAUNCHER", "1"])
    // Inside the first home load: this is the window where a pending render outlives the window.
    await sleep(2500)
    adb(["shell", "input", "keyevent", "4"])
    await sleep(6000)

    const alive = pid()
    const fatals = fatalLines()
    const reachableAfter = await reachable()
    const ok = alive !== "" && fatals.length === 0
    console.log(
      `${ok ? "PASS" : "FAIL"}  第 ${round} 轮：进程 ${alive || "已退出"}，致命异常 ${fatals.length} 个，` +
        `控制接口${reachableAfter ? "可用" : "不可用"}`,
    )
    for (const fatal of fatals) console.log("        · " + fatal)
    if (!ok) failures.push(`round ${round}`)
  }

  // Leave the app in the foreground so whatever runs next is looking at a window.
  adb(["shell", "am", "force-stop", PACKAGE])
  await sleep(1500)
  adb(["shell", "monkey", "-p", PACKAGE, "-c", "android.intent.category.LAUNCHER", "1"])
  await sleep(12000)

  console.log(failures.length === 0 ? "\n每一轮都活下来了。" : `\n有 ${failures.length} 轮出了事：${failures.join(", ")}`)
  process.exit(failures.length === 0 ? 0 : 1)
}

main().catch((error) => {
  console.error("失败：", error.message)
  process.exit(2)
})
