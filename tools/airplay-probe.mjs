#!/usr/bin/env node
/**
 * Asks the television's AirPlay receiver who it is, the way an iPhone does before it starts casting.
 *
 * Why: "投屏" is half the product, and a receiver that fails the first handshake looks exactly like a
 * receiver that is not running. This speaks the RAOP/AirPlay request the sender sends first
 * (`GET /info RTSP/1.0` with a CSeq header) and checks the answer against the identity the app
 * advertises over mDNS — the two disagreeing is a real bug this app has had before (iOS then shows one
 * device name and refuses to cast).
 *
 * What it does not prove: the mirroring stream itself needs a real iPhone or iPad. This is the step
 * before that one, and it is the step that fails when the receiver is broken.
 *
 * Usage:
 *   adb forward tcp:19978 tcp:9978
 *   node tools/airplay-probe.mjs            # forwards the receiver port with adb when needed
 *   node tools/airplay-probe.mjs --no-forward
 */
import { execFileSync } from "node:child_process"
import net from "node:net"

const base = (process.env.NUKACAST_BASE || "http://127.0.0.1:19978").replace(/\/$/, "")
const ADB = process.env.ADB || "adb"
const forward = !process.argv.includes("--no-forward")
const failures = []

function report(label, ok, detail) {
  console.log(`${ok ? "PASS" : "FAIL"}  ${label} — ${detail}`)
  if (!ok) failures.push(label)
}

async function status() {
  const response = await fetch(`${base}/api/status`, { signal: AbortSignal.timeout(8000) })
  return response.json()
}

/** Sends the handshake and returns whatever comes back (the receiver answers with a binary plist). */
function askInfo(port, { withCseq = true, timeoutMs = 6000 } = {}) {
  return new Promise((resolve) => {
    const socket = net.createConnection({ host: "127.0.0.1", port }, () => {
      const request =
        "GET /info RTSP/1.0\r\n" +
        (withCseq ? "CSeq: 1\r\n" : "") +
        "User-Agent: AirPlay/320.20\r\n\r\n"
      socket.write(request)
    })
    const chunks = []
    const done = (reason) => {
      socket.destroy()
      resolve({ reason, body: Buffer.concat(chunks) })
    }
    socket.setTimeout(timeoutMs, () => done(chunks.length ? "timeout" : "no-answer"))
    socket.on("data", (chunk) => {
      chunks.push(chunk)
      const text = Buffer.concat(chunks).toString("latin1")
      if (/<[/]plist>/.test(text) || text.includes("bplist00")) done("answered")
    })
    socket.on("error", (error) => resolve({ reason: "error", error: error.message, body: Buffer.alloc(0) }))
    socket.on("close", () => resolve({ reason: chunks.length ? "answered" : "closed", body: Buffer.concat(chunks) }))
  })
}

async function main() {
  const state = await status()
  const airplay = state.airPlay || {}
  const port = airplay.port
  console.log(`airplay: state=${airplay.state} port=${port}`)
  report("接收器已发布并带端口", !!port && airplay.state === "ready", `state=${airplay.state} port=${port}`)
  if (!port) {
    console.log("\n结果: 接收器没起来，后面的检查无法进行")
    process.exit(1)
  }
  if (forward) {
    try {
      execFileSync(ADB, ["forward", `tcp:${port}`, `tcp:${port}`], { stdio: "ignore" })
    } catch (error) {
      console.log(`（adb forward 失败，按端口已可达继续：${String(error.message).split("\n")[0]}）`)
    }
  }

  const answer = await askInfo(port)
  const text = answer.body.toString("latin1")
  report("GET /info 得到应答", answer.reason === "answered" && /200 OK/.test(text),
    `${answer.reason} · ${answer.body.length} 字节 · ${text.split("\r\n")[0] || "(无)"}`)
  report("应答是 AirPlay 的二进制 plist",
    text.includes("application/x-apple-binary-plist") || text.includes("bplist00"),
    text.includes("bplist00") ? "bplist00" : "Content-Type 缺失")

  // The identity the app advertises (mDNS and /api/status) must be the one the receiver reports, or a
  // sender sees a different device than the one it found.
  const advertised = String(airplay.identity || "")
  const fields = {}
  for (const pair of advertised.split(";")) {
    const [key, value] = pair.split("=")
    if (key && value) fields[key.trim()] = value.trim()
  }
  const raw = answer.body
  const has = (value) => !!value && raw.includes(Buffer.from(value, "latin1"))
  report("receiver 报告的名字与通告一致", has(fields.name), `name=${fields.name}`)
  report("receiver 报告的设备 id 与通告一致", has(fields.deviceId), `deviceId=${fields.deviceId}`)
  report("receiver 报告的型号与通告一致", has(fields.model), `model=${fields.model}`)
  // The public key travels as a 32-byte plist data value, not as text, so the advertised hex has to be
  // decoded before it can be looked for.
  const pkBytes = /^[0-9a-f]{64}$/i.test(String(fields.pk))
    ? Buffer.from(String(fields.pk), "hex") : null
  report("receiver 报告的公钥与通告一致",
    !!pkBytes && raw.includes(pkBytes),
    `pk=${String(fields.pk).slice(0, 16)}…（${pkBytes ? pkBytes.length + " 字节" : "通告里不是十六进制"}）`)
  report("plist 里带 features（iOS 用它决定能不能投）", has("features"), "features 字段存在")
  report("plist 里带屏幕描述（分辨率/刷新率）", has("displays") && has("widthPixels"),
    "displays 字段存在")

  // Negative control: the receiver drops requests without a CSeq, which is why a plain HTTP GET hangs.
  const bare = await askInfo(port, { withCseq: false, timeoutMs: 4000 })
  report("缺少 CSeq 的请求不会得到应答（协议没被将就）", bare.reason !== "answered",
    `结果=${bare.reason}（说明它按 RTSP 处理，而不是随便什么 HTTP）`)

  console.log(failures.length === 0
    ? "\n结果: 全部通过（真正的镜像投屏需要真机 iPhone 才能走完）"
    : `\n结果: 失败 ${failures.join(", ")}`)
  process.exit(failures.length === 0 ? 0 : 1)
}

main().catch((error) => {
  console.error("失败：", error.message)
  process.exit(2)
})
