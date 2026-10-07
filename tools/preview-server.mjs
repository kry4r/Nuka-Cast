/**
 * Local preview server for the built control page.
 *
 * Serves app/src/main/assets/web and answers the API routes the UI calls with representative
 * sample data, so the interface can be reviewed visually without a TV on the network. It is a
 * development tool: it never runs inside the APK and its numbers are fixtures, not measurements.
 *
 *   node tools/preview-server.mjs [port]
 */
import { createServer } from "node:http"
import { mkdirSync, writeFileSync } from "node:fs"
import { readFile, stat } from "node:fs/promises"
import { dirname, extname, join, normalize } from "node:path"
import { fileURLToPath } from "node:url"

const root = new URL("../app/src/main/assets/web/", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1")
const port = Number(process.argv[2] || 9978)
const now = Date.now()
const previewDir = join(dirname(fileURLToPath(import.meta.url)), "..", ".preview")
const pidFile = join(previewDir, "preview-server.pid")

const types = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".ico": "image/x-icon",
}

const liveSources = [
  { id: "a1", sourceId: "user:pl-1", name: "IPTV 综合（央视+卫视）", url: "https://cdn.jsdelivr.net/gh/bestK/iptv@main/iptv.m3u", epg: "", logo: "" },
  { id: "b2", sourceId: "cfg:office", name: "小盒子直播", url: "http://xhztv.top/live.txt", epg: "", logo: "" },
]

const channels = (prefix, names) => names.map((name, index) => ({
  id: `${prefix}-${index}`,
  name,
  epgId: name,
  logo: "",
  urls: ["http://example.test/live/index.m3u8"],
  headers: {},
}))

const api = {
  "/api/status": {
    name: "NukaCast",
    version: "0.3.5",
    message: "运行中",
    serviceState: "ready",
    stateVersion: 42,
    sourceCount: 3,
    siteCount: 41,
    contentVersion: 17,
    webAddress: "192.168.1.24:9978",
    activeMedia: "",
    airPlay: {
      state: "ready",
      error: "",
      port: 7000,
      sessionActive: false,
      clientName: "",
      videoWidth: 1920,
      videoHeight: 1080,
      decoderName: "OMX.hisi.video.decoder.avc",
      decoderSoftwareFallback: false,
      decoderInputs: 128,
      decoderOutputs: 127,
      droppedFrames: 2,
      identity: "deviceId=02:AA:BB:CC:DD:EE;name=NukaCast;model=AppleTV3,2;pi=8f2c…",
    },
  },
  "/api/player": { playing: true, title: "重生2000：靠山吃山成首富 · 第1集", positionMs: 42000, durationMs: 180000, bufferedMs: 12000, speed: 1, volume: 1, url: "http://example.test/a.m3u8" },
  "/api/device": {
    manufacturer: "Sharp",
    model: "SHARP-TVC",
    product: "aosp_arm",
    androidVersion: "4.4.2",
    sdk: 19,
    primaryAbi: "armeabi-v7a",
    totalMemoryBytes: 1_500_000_000,
    appMemoryBytes: 192_000_000,
    displayWidth: 1920,
    displayHeight: 1080,
    refreshRate: 60,
    hasHardwareAvcDecoder: true,
    preferredAvcDecoder: "OMX.hisi.video.decoder.avc",
    avcDecoders: ["OMX.hisi.video.decoder.avc", "OMX.google.h264.decoder"],
    warnings: [],
  },
  "/api/sites": [
    { key: "ffzy", name: "非凡资源", type: 1, sourceId: "src-1", sourceName: "PyramidStore 单仓" },
    { key: "bfzy", name: "暴风资源", type: 1, sourceId: "src-1", sourceName: "PyramidStore 单仓" },
  ],
  "/api/live": liveSources,
  "/api/live/sources": [
    { id: "pl-1", name: "IPTV 综合（央视+卫视）", url: "https://cdn.jsdelivr.net/gh/bestK/iptv@main/iptv.m3u", enabled: true, error: "", updatedAt: now - 90000, user: true },
    { id: "pl-2", name: "典藏版直播源（频道最全）", url: "https://gh-proxy.com/raw.githubusercontent.com/suxuang/myIPTV/main/ipv4.m3u", enabled: false, error: "2026-10-06 拉取超时", updatedAt: now - 400000, user: true },
    { id: "b2", name: "小盒子直播", url: "http://xhztv.top/live.txt", enabled: true, error: "", updatedAt: 0, user: false },
  ],
  "/api/live/catalog": {
    sourceId: "a1",
    sourceName: "IPTV 综合（央视+卫视）",
    groups: [
      { name: "央视", channels: channels("cctv", ["CCTV-1 综合", "CCTV-2 财经", "CCTV-5 体育", "CCTV-6 电影", "CCTV-13 新闻", "CCTV-17 农业农村"]) },
      { name: "卫视", channels: channels("sat", ["湖南卫视", "浙江卫视", "江苏卫视", "东方卫视", "北京卫视", "广东卫视", "深圳卫视", "安徽卫视"]) },
      { name: "地方", channels: channels("loc", ["北京新闻", "上海都市", "广州综合", "成都新闻"]) },
    ],
  },
  "/api/live/epg": { channel: "CCTV-1 综合", date: "2026-10-07", programs: [
    { start: "19:00", end: "19:30", title: "新闻联播" },
    { start: "19:30", end: "20:30", title: "焦点访谈" },
    { start: "20:30", end: "22:00", title: "电视剧：山海情" },
  ] },
  "/api/sources": [
    { id: "src-1", name: "PyramidStore 单仓", url: "https://cdn.jsdelivr.net/gh/UndCover/PyramidStore@main/py.json", kind: "single", enabled: true, error: "", searchError: "", siteCount: 23, liveCount: 1, latencyMs: 640, parentId: "" },
    { id: "src-2", name: "小盒子多仓", url: "http://xhztv.top/dc", kind: "warehouse", enabled: true, error: "", searchError: "", siteCount: 0, liveCount: 0, latencyMs: 320, parentId: "" },
    { id: "src-2-1", name: "🐔肥猫", url: "http://我不是.肥猫.live/接口禁止贩卖", kind: "single", enabled: true, error: "", searchError: "最近搜索全部失败", siteCount: 18, liveCount: 2, latencyMs: 1500, parentId: "src-2" },
  ],
  "/api/logs/export": {
    __text: true,
    body: () => {
      const lines = []
      lines.push("NukaCast 诊断报告")
      lines.push("生成时间：" + new Date().toLocaleString())
      lines.push("（这是预览服务的示例内容，电视端导出的文件包含真实日志与设备状态）")
      lines.push("")
      lines.push("== 设备 ==")
      lines.push("厂商/型号：Sharp SHARP-TVC")
      lines.push("系统：Android 4.4.2（API 19） · ABI armeabi-v7a")
      lines.push("")
      lines.push("== 运行日志 ==")
      for (const entry of api["/api/logs"]) {
        lines.push(new Date(entry.timestamp).toISOString() + "  " + entry.level + "  [" + entry.tag + "]")
        lines.push(entry.message + (entry.repeats ? "（重复 " + entry.repeats + " 次）" : ""))
      }
      return lines.join("\n")
    },
  },
  "/api/debug/ping": { app: "NukaCast（预览）", time: now, pid: 1 },
  "/api/debug/snapshot": {
    generatedAt: now,
    status: { serviceState: "ready", message: "运行中（预览）", sourceCount: 3, siteCount: 41 },
    get device() { return api["/api/device"] },
    memory: { heapUsedBytes: 8_000_000, heapMaxBytes: 268_000_000, rssBytes: 45_000_000, threads: 36, pluginBudgetBytes: 201_000_000 },
    airPlay: { state: "ready", port: 7000 },
    sites: { enabledSites: 41, pluginSites: 39, cmsSites: 2, searchSiteLimit: 24, homePluginLimit: 2 },
    sources: { sources: [] },
    health: { knownGood: 5, knownBad: 36, sweep: { running: false, total: 41, done: 41, ok: 5, failed: 36 } },
    player: { state: "idle" },
  },
  "/api/debug/sites": { enabledSites: 41, pluginSites: 39, cmsSites: 2, searchSiteLimit: 24, homePluginLimit: 2, sites: [] },
  "/api/debug/sources": { sources: [], liveSources: [] },
  "/api/debug/health": {
    sweep: { running: false, cancelled: false, total: 41, done: 41, ok: 5, failed: 36, keyword: "庆余年", results: [] },
    knownGood: 5,
    knownBad: 36,
    verdicts: [],
  },
  "/api/debug/player": { state: "idle", title: "", url: "" },
  "/api/logs": [
    { level: "INFO", tag: "片源", message: "配置刷新成功 [PyramidStore 单仓]：23 个站点", timestamp: now - 20000 },
    { level: "WARN", tag: "短剧", message: "目录详情失败：目录 HTTP 502（api.ffzyapi.com）", timestamp: now - 60000 },
    { level: "INFO", tag: "AirPlay", message: "接收器已发布，可被 iOS 发现，端口 7000", timestamp: now - 120000 },
    { level: "ERROR", tag: "AirPlay 视频", message: "解码循环异常：IllegalStateException", timestamp: now - 200000, repeats: 63 },
    { level: "WARN", tag: "片源", message: "首页加载：12 个站点 → 4 成功 / 5 超时 / 3 失败", timestamp: now - 240000 },
    { level: "ERROR", tag: "网页服务", message: "请求处理失败 [/api/drama/search]", timestamp: now - 180000 },
  ],
  "/api/diagnostics": {
    javaCrash: "",
    serviceState: "ready",
    serviceMessage: "运行中",
    deviceWarnings: [],
    airPlay: { state: "ready", error: "", port: 7000, sessionActive: false, clientName: "", videoWidth: 1920, videoHeight: 1080, decoderName: "OMX.hisi.video.decoder.avc", decoderSoftwareFallback: false, decoderInputs: 128, decoderOutputs: 127, droppedFrames: 2, identity: "deviceId=02:AA:BB:CC:DD:EE;name=NukaCast;model=AppleTV3,2;pi=8f2c…" },
    player: { playing: true, title: "重生2000 · 第1集", positionMs: 42000, durationMs: 180000, bufferedMs: 12000, speed: 1, volume: 1, url: "" },
    sources: [],
    homeErrors: [],
    httpStack: { degraded: false, initError: "" },
    stages: [
      { scope: "source", subject: "PyramidStore 单仓", stage: "persist", result: "ok", startedAt: now - 9000, updatedAt: now - 8000, elapsedMs: 640, detail: "", errorCode: "", rootCauseClass: "", generation: 12 },
      { scope: "airplay", subject: "video", stage: "first_output", result: "ok", startedAt: now - 30000, updatedAt: now - 30000, elapsedMs: 240, detail: "OMX.hisi.video.decoder.avc 1920x1080", errorCode: "", rootCauseClass: "", generation: 11 },
      { scope: "spider", subject: "src-1|ffzy", stage: "plugin_init", result: "failed", startedAt: now - 60000, updatedAt: now - 59000, elapsedMs: 1200, detail: "dalvik verifier rejected class", errorCode: "linkage_error", rootCauseClass: "java.lang.VerifyError", generation: 10 },
    ],
    siteIssues: [
      { siteKey: "kua-fu", siteName: "☀️┆夸父┆4K", reason: "该站点的 Spider 需要 Android 5.0 以上，当前设备无法运行", permanent: true, updatedAt: now - 3600000 },
      { siteKey: "ting-feng", siteName: "☘️┆听风┆知秋", reason: "配置里的 Spider JAR 校验值与下载内容不一致，已拒绝加载", permanent: false, updatedAt: now - 1800000 },
    ],
    lastRun: {
      startedAt: now - 11400000, endedAt: now - 10500000, endedCleanly: false,
      durationMs: 900000, device: "Sharp SHARP-TVC", version: "0.3.7",
      peakHeapPercent: 84, lastStage: "airplay/video/codec_config", lastHeapPercent: 84,
      lastAvailableMemoryBytes: 96000000,
      samples: [{ "at": 1750000000000, "heapPercent": 22, "heapUsedBytes": 42240000, "nativeHeapBytes": 6600000, "availableMemoryBytes": 227000000, "stage": "startup" },{ "at": 1750000030000, "heapPercent": 24, "heapUsedBytes": 46080000, "nativeHeapBytes": 7200000, "availableMemoryBytes": 224000000, "stage": "source/PyramidStore/persist" },{ "at": 1750000060000, "heapPercent": 31, "heapUsedBytes": 59520000, "nativeHeapBytes": 9300000, "availableMemoryBytes": 213500000, "stage": "search" },{ "at": 1750000090000, "heapPercent": 38, "heapUsedBytes": 72960000, "nativeHeapBytes": 11400000, "availableMemoryBytes": 203000000, "stage": "airplay/video/native_listen" },{ "at": 1750000120000, "heapPercent": 52, "heapUsedBytes": 99840000, "nativeHeapBytes": 15600000, "availableMemoryBytes": 182000000, "stage": "airplay/video/codec_config" },{ "at": 1750000150000, "heapPercent": 61, "heapUsedBytes": 117120000, "nativeHeapBytes": 18300000, "availableMemoryBytes": 168500000, "stage": "airplay/video/first_output" },{ "at": 1750000180000, "heapPercent": 70, "heapUsedBytes": 134400000, "nativeHeapBytes": 21000000, "availableMemoryBytes": 155000000, "stage": "airplay/video/codec_config" },{ "at": 1750000210000, "heapPercent": 78, "heapUsedBytes": 149760000, "nativeHeapBytes": 23400000, "availableMemoryBytes": 143000000, "stage": "airplay/video/codec_config" },{ "at": 1750000240000, "heapPercent": 84, "heapUsedBytes": 161280000, "nativeHeapBytes": 25200000, "availableMemoryBytes": 134000000, "stage": "airplay/video/codec_config" }],
    },
  },
  "/api/recommended": {
    verifiedAt: "2026-10-07",
    note: "每条都经过实测；检测按钮会用本机网络重新确认。",
    items: [
      { id: "vod-fantuan", kind: "vod", group: "点播", name: "饭太硬（小盒子镜像）", url: "http://xhztv.top/dc/饭太硬/api.json", note: "饭太硬线路，53 个站点 + 3 个直播源", categoryId: "", verifiedAt: "2026-10-07", added: true, probe: { id: "vod-fantuan", ok: true, httpStatus: 200, latencyMs: 523, bytes: 15000, detail: "sites=53 lives=3", errorCode: "", error: "", checkedAt: now - 60000 } },
      { id: "vod-wex", kind: "vod", group: "点播", name: "王二小", url: "https://9280.kstore.vip/newwex.json", note: "96 个站点 + 2 个直播源（备用地址：tvbox.王二小放牛娃.top）", categoryId: "", verifiedAt: "2026-10-07", added: false, probe: { id: "vod-wex", ok: true, httpStatus: 200, latencyMs: 118, bytes: 42000, detail: "sites=96 lives=2", errorCode: "", error: "", checkedAt: now - 90000 } },
      { id: "drama-ffzy", kind: "drama", group: "短剧", name: "非凡资源 · 短剧", url: "https://api.ffzyapi.com/api.php/provide/vod", categoryId: "36", note: "短剧约 1.99 万部；剧集页会自动解析成 m3u8 后再播放", verifiedAt: "2026-10-07", added: false, probe: { id: "drama-ffzy", ok: true, httpStatus: 200, latencyMs: 890, bytes: 42000, detail: "共 19874 条剧目", errorCode: "", error: "", checkedAt: now - 45000 } },
    ],
  },
  "/api/drama/providers": {
    providers: [
      { id: "p-ffzy", name: "非凡资源·短剧", baseUrl: "https://api.ffzyapi.com/api.php/provide/vod", kind: "cms.drama", builtin: false, enabled: true, error: "", updatedAt: now, categoryId: "36", note: "短剧分类约 1.9 万部" },
      { id: "p-vote", name: "红果短剧榜（资料目录）", baseUrl: "https://vote.252035.xyz", kind: "vote.catalog", builtin: true, enabled: true, error: "目录 HTTP 502", updatedAt: now - 60000, categoryId: "", note: "" },
    ],
  },
  "/api/drama/search": {
    providerId: "p-ffzy",
    providerName: "非凡资源·短剧",
    keyword: "重生",
    ok: true,
    error: "",
    errorCode: "",
    rootCauseClass: "",
    total: 19874,
    warning: "",
    elapsedMs: 890,
    partial: false,
    items: Array.from({ length: 12 }).map((_, index) => ({
      providerId: "p-ffzy",
      dramaId: `769019266369323${3100 + index}`,
      title: ["重生2000：靠山吃山成首富", "重生七零小辣媳第二季", "重生后我成了首富千金", "重生之我在都市当神医", "重生八零：娇妻有点甜", "重生之逆袭人生"][index % 6],
      cover: "",
      intro: "重回2000年，他靠着前世的记忆一路逆袭，把山货卖到了全世界。",
      remark: `全${80 + index * 7}集`,
      category: ["脑洞", "都市", "逆袭", "甜宠"][index % 4],
      tags: ["短剧", "重生"],
      episodeCount: 80 + index * 7,
      heat: String(4317982 + index * 137),
      status: "finished",
      contentKind: "short_drama",
    })),
  },
  "/api/drama/browse": {
    providerId: "p-ffzy",
    providerName: "非凡资源·短剧",
    keyword: "",
    ok: true,
    error: "",
    errorCode: "",
    rootCauseClass: "",
    total: 19874,
    warning: "",
    elapsedMs: 740,
    partial: false,
    items: Array.from({ length: 18 }).map((_, index) => ({
      providerId: "p-ffzy",
      dramaId: `769019266369323${4100 + index}`,
      title: ["总裁的隐婚妻子", "闪婚老公是首富", "我的霸道男友", "穿书后我成了反派", "离婚后她惊艳了世界", "天才萌宝：妈咪快跑"][index % 6] + `（${index + 1}）`,
      cover: "",
      intro: "短剧简介",
      remark: `全${60 + index * 5}集`,
      category: ["都市", "甜宠", "虐恋"][index % 3],
      tags: [],
      episodeCount: 60 + index * 5,
      heat: "",
      status: "finished",
      contentKind: "short_drama",
    })),
  },
  "/api/drama/detail": {
    item: { providerId: "p-ffzy", dramaId: "7690192663693233100", title: "重生2000：靠山吃山成首富", cover: "", intro: "重回2000年，他靠着前世的记忆一路逆袭，把山货卖到了全世界。", remark: "全115集", category: "脑洞", tags: ["脑洞", "重生", "逆袭"], episodeCount: 115, heat: "43179826", status: "finished", contentKind: "short_drama" },
    related: Array.from({ length: 6 }).map((_, index) => ({ providerId: "p-ffzy", dramaId: `rel-${index}`, title: ["重生七零小辣媳第二季", "重生后我成了首富千金", "重生之我在都市当神医", "重生八零：娇妻有点甜", "重生之逆袭人生", "重生之最强赘婿"][index], cover: "", intro: "", remark: "", category: "", tags: [], episodeCount: 0, heat: "", status: "", contentKind: "short_drama" })),
    relatedTotal: 6,
    relatedPartial: false,
    episodes: Array.from({ length: 40 }).map((_, index) => ({ index: index + 1, name: `第${String(index + 1).padStart(2, "0")}集`, playUrl: `https://cdn.example.test/${index + 1}/index.m3u8`, pageUrl: "", headers: {}, direct: true })),
    directPlayable: true,
    note: "",
  },
  "/api/drama/lines": { lines: [], searchedSites: 0, failedSites: 0, searched: false, error: "", elapsedMs: 0 },
  "/api/storage/mounts": [
    { id: "m1", name: "家庭 NAS", url: "http://192.168.1.8:5000", username: "media", enabled: true, error: "", itemCount: 1284, scannedAt: now - 600000 },
  ],
  "/api/storage/items": { items: [], total: 0 },
}

const json = (value) => JSON.stringify(value)

async function serveStatic(pathname, response) {
  const relative = pathname === "/" ? "index.html" : pathname.slice(1)
  const target = normalize(join(root, relative))
  if (!target.startsWith(normalize(root))) {
    response.writeHead(403).end("forbidden")
    return
  }
  try {
    const info = await stat(target)
    if (info.isDirectory()) throw new Error("directory")
    const body = await readFile(target)
    response.writeHead(200, { "content-type": types[extname(target)] || "application/octet-stream" }).end(body)
  } catch {
    const body = await readFile(join(root, "index.html"))
    response.writeHead(200, { "content-type": types[".html"] }).end(body)
  }
}

createServer(async (request, response) => {
  const url = new URL(request.url, `http://localhost:${port}`)
  if (url.pathname.startsWith("/api/")) {
    const payload = api[url.pathname]
    if (!payload) {
      response.writeHead(404, { "content-type": "application/json" }).end(json({ error: "preview 未实现该接口" }))
      return
    }
    const body = request.method === "POST" ? await readBody(request) : null
    if (payload.__text) {
      // Plain-text artifacts (the diagnostic bundle) keep their own content type.
      const text = typeof payload.body === "function" ? payload.body() : String(payload.body)
      response.writeHead(200, { "content-type": "text/plain; charset=utf-8", "content-disposition": "attachment; filename=nukacast-diagnostics-preview.txt" })
      response.end(text)
      return
    }
    if (url.pathname === "/api/recommended/add" || url.pathname === "/api/recommended/verify") {
      response.writeHead(200, { "content-type": "application/json" }).end(json({ added: 0, probes: [], items: api["/api/recommended"].items }))
      return
    }
    if (url.pathname === "/api/drama/search" && body?.keyword) {
      api["/api/drama/search"].keyword = body.keyword
    }
    response.writeHead(200, { "content-type": "application/json" }).end(json(payload))
    return
  }
  await serveStatic(url.pathname, response)
}).listen(port, () => {
  console.log(`preview server: http://localhost:${port}/  (root: ${root})`)
  // Record our pid so tools/preview-restart.mjs can stop exactly this process (never all node.exe).
  try {
    mkdirSync(previewDir, { recursive: true })
    writeFileSync(pidFile, `${process.pid}\n`)
  } catch {
    /* the pid file is optional */
  }
})

function readBody(request) {
  return new Promise((resolve) => {
    let data = ""
    request.on("data", (chunk) => { data += chunk })
    request.on("end", () => {
      try {
        resolve(data ? JSON.parse(data) : null)
      } catch {
        resolve(null)
      }
    })
  })
}
