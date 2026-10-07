import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react"
import {
  Airplay,
  Cast,
  CircleAlert,
  Clapperboard,
  Download,
  Film,
  FileWarning,
  Gauge,
  HardDrive,
  Library,
  ListFilter,
  LoaderCircle,
  MonitorCog,
  Pause,
  Play,
  Plus,
  Radio,
  RefreshCw,
  Search,
  Server,
  Settings2,
  SkipBack,
  SkipForward,
  Square,
  Trash2,
  Tv,
  Wifi,
  X,
} from "lucide-react"
import { api, type Device, type Diagnostics, type DramaDetail, type DramaEpisode, type DramaItem, type DramaLine, type DramaLineResult, type DramaProvider, type DramaSearchResult, type EpgSchedule, type LiveCatalog, type LiveSource, type LiveSourceRow, type LogEntry, type LogLevel, type MediaDetail, type Player, type SearchItem, type SearchResponse, type Site, type Source, type Status, type StorageMount } from "@/lib/api"
import { formatBytes } from "@/lib/utils"
import { dramaFacts, lineLabel, matchLabel, missingLineHint } from "@/lib/drama"
import { hostOf, kindTone, probeDotClass, probeState } from "@/lib/kind"
import { rankLeafSources, selectPreferredSource } from "@/lib/source-ranking"
import { createLatestRequestGate } from "@/lib/latest-request"
import { RecommendedShelf } from "@/components/source-shelf"
import { ViewBoundary } from "@/components/view-boundary"
import { EmptyState, PageHeader, RowSkeletons, SectionCard, Skeleton, StatusDot } from "@/components/ui/primitives"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"

type View = "overview" | "search" | "drama" | "live" | "sources" | "storage" | "device" | "logs"

const nav: { id: View; label: string; icon: typeof Gauge }[] = [
  { id: "overview", label: "总览", icon: Gauge },
  { id: "search", label: "影视", icon: Film },
  { id: "drama", label: "短剧", icon: Clapperboard },
  { id: "live", label: "直播", icon: Radio },
  { id: "sources", label: "源管理", icon: Library },
  { id: "storage", label: "存储", icon: HardDrive },
  { id: "device", label: "设备", icon: MonitorCog },
  { id: "logs", label: "日志", icon: FileWarning },
]

function navLabel(view: View): string {
  return nav.find((item) => item.id === view)?.label ?? "页面"
}

const VIEW_IDS: View[] = ["overview", "search", "drama", "live", "sources", "storage", "device", "logs"]

/** The current page lives in the URL hash, so a refresh or a shared link keeps the same view. */
function viewFromHash(): View {
  const value = window.location.hash.replace(/^#\/?/, "")
  return (VIEW_IDS as string[]).includes(value) ? (value as View) : "overview"
}

export default function App() {
  const [status, setStatus] = useState<Status | null>(null)
  const [view, setView] = useState<View>(() => viewFromHash())
  const [error, setError] = useState("")

  useEffect(() => {
    const sync = () => setView(viewFromHash())
    window.addEventListener("hashchange", sync)
    return () => window.removeEventListener("hashchange", sync)
  }, [])

  const navigate = useCallback((next: View) => {
    setView(next)
    if (window.location.hash !== `#/${next}`) window.location.hash = `#/${next}`
  }, [])

  const refreshStatus = useCallback(async () => {
    try {
      setStatus(await api.status())
    } catch (reason) {
      setError(message(reason))
    }
  }, [])

  useEffect(() => {
    refreshStatus()
    const timer = window.setInterval(refreshStatus, 2500)
    return () => window.clearInterval(timer)
  }, [refreshStatus])

  return (
    <div className="min-h-screen lg:grid lg:grid-cols-[236px_1fr]">
      <aside className="border-b bg-card/50 backdrop-blur lg:fixed lg:inset-y-0 lg:flex lg:w-[236px] lg:flex-col lg:border-b-0 lg:border-r">
        <div className="flex h-16 items-center gap-3 px-4 lg:h-auto lg:py-5">
          <div className="grid size-10 shrink-0 place-items-center rounded-xl bg-gradient-to-br from-sky-500 to-violet-600 text-white shadow-lg shadow-sky-950/40">
            <Cast className="size-5" />
          </div>
          <div className="min-w-0">
            <div className="truncate text-base font-semibold tracking-tight">NukaCast</div>
            <div className="truncate text-xs text-muted-foreground">{status?.message || "连接中"}</div>
          </div>
        </div>
        <nav className="flex gap-1 overflow-x-auto px-2 pb-3 lg:flex-1 lg:block lg:space-y-0.5 lg:px-2 lg:pb-0">
          {nav.map((item) => {
            const Icon = item.icon
            const active = view === item.id
            return (
              <button key={item.id} type="button" onClick={() => navigate(item.id)}
                className={`group flex shrink-0 items-center gap-2.5 rounded-lg px-3 py-2 text-sm outline-none transition focus-visible:ring-2 focus-visible:ring-ring lg:w-full ${active ? "bg-accent font-medium text-accent-foreground" : "text-muted-foreground hover:bg-accent/50 hover:text-foreground"}`}>
                <Icon className="size-4" />
                <span className="truncate">{item.label}</span>
                {active && <span className="ml-auto hidden size-1.5 rounded-full bg-foreground lg:block" />}
              </button>
            )
          })}
        </nav>
        <div className="hidden p-3 lg:block">
          <div className="space-y-1 rounded-xl border bg-background/60 p-3 text-xs">
            <div className="flex items-center gap-2 font-medium">
              <span className={`size-2 rounded-full ${status?.serviceState === "ready" ? "bg-emerald-400" : "bg-rose-400"}`} />
              {status?.serviceState === "ready" ? "服务已就绪" : "服务未就绪"}
            </div>
            <div className="truncate text-muted-foreground">{status?.webAddress || "局域网服务"}</div>
            <div className="text-muted-foreground">v{status?.version || "-"}</div>
          </div>
        </div>
      </aside>

      <main className="min-w-0 px-4 py-5 sm:px-6 lg:col-start-2 lg:px-8 lg:py-7">
        <div className="mx-auto max-w-[1600px]">
          {error && (
            <div className="mb-4 flex items-center gap-2 rounded-lg border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm text-destructive">
              <CircleAlert className="size-4 shrink-0" />{error}
              <Button variant="ghost" size="sm" className="ml-auto" onClick={() => setError("")}>关闭</Button>
            </div>
          )}
          <ViewBoundary name={navLabel(view)}>
            {view === "overview" && <Overview status={status} onNavigate={navigate} onStatusChanged={refreshStatus} setError={setError} />}
            {view === "search" && <SearchView sourceVersion={status?.stateVersion ?? 0} setError={setError} />}
            {view === "drama" && <DramaView setError={setError} />}
            {view === "live" && <LiveView contentVersion={status?.contentVersion ?? 0} setError={setError} />}
            {view === "sources" && <SourcesView contentVersion={status?.contentVersion ?? 0} onChanged={refreshStatus} setError={setError} />}
            {view === "storage" && <StorageView onChanged={refreshStatus} setError={setError} />}
            {view === "device" && <DeviceView setError={setError} />}
            {view === "logs" && <LogView setError={setError} />}
          </ViewBoundary>
        </div>
      </main>
    </div>
  )
}

function Overview({ status, onNavigate, onStatusChanged, setError }: {
  status: Status | null
  onNavigate: (view: View) => void
  onStatusChanged: () => Promise<void>
  setError: (value: string) => void
}) {
  const [player, setPlayer] = useState<Player | null>(null)
  const [logs, setLogs] = useState<LogEntry[]>([])
  const [disconnecting, setDisconnecting] = useState(false)

  const refresh = useCallback(() => api.player().then(setPlayer).catch((reason) => setError(message(reason))), [setError])
  useEffect(() => {
    refresh()
    const timer = window.setInterval(refresh, 2000)
    return () => window.clearInterval(timer)
  }, [refresh])

  useEffect(() => {
    const load = () => api.logs().then((entries) => setLogs(entries.slice(-6).reverse())).catch(() => {})
    load()
    const timer = window.setInterval(load, 6000)
    return () => window.clearInterval(timer)
  }, [])

  const control = async (action: string, offsetMs?: number) => {
    try {
      setPlayer(await api.control({ action, offsetMs }))
    } catch (reason) {
      setError(message(reason))
    }
  }

  const disconnectAirPlay = async () => {
    setDisconnecting(true)
    try {
      await api.disconnectAirPlay()
      await onStatusChanged()
    } catch (reason) {
      setError(message(reason))
    } finally {
      setDisconnecting(false)
    }
  }

  const airPlay = status?.airPlay
  const shortcuts: { view: View; label: string; hint: string; icon: typeof Gauge }[] = [
    { view: "search", label: "影视搜索", hint: `${status?.siteCount ?? 0} 个站点可用`, icon: Film },
    { view: "drama", label: "短剧", hint: "目录搜索与直连播放", icon: Clapperboard },
    { view: "live", label: "直播", hint: "IPTV 清单与节目单", icon: Radio },
    { view: "sources", label: "源管理", hint: `${status?.sourceCount ?? 0} 个配置`, icon: Library },
  ]

  return (
    <>
      <PageHeader
        title="控制中心"
        subtitle={status?.webAddress ? `在同一局域网用浏览器打开 ${status.webAddress.replace(/^https?:\/\//, "")} 即可控制这台电视。` : "正在连接本机服务…"}
        badges={<Badge variant={status?.serviceState === "ready" ? "secondary" : "destructive"}>{status?.message || "连接中"}</Badge>}
      />

      <div className="grid gap-4 xl:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
        <SectionCard
          title="AirPlay 投屏"
          badges={airPlay?.sessionActive
            ? <Badge variant="secondary">正在镜像{status?.activeMedia && status.activeMedia !== "AirPlay 镜像" ? ` · ${status.activeMedia}` : ""}</Badge>
            : <Badge variant="outline">等待设备</Badge>}
          action={airPlay?.sessionActive
            ? <Button variant="outline" size="sm" disabled={disconnecting} onClick={disconnectAirPlay}>
                {disconnecting ? <LoaderCircle className="animate-spin" /> : <Square />}退出投屏
              </Button>
            : undefined}
        >
          <div className="flex items-start gap-4">
            <div className="grid size-12 shrink-0 place-items-center rounded-xl bg-gradient-to-br from-sky-500 to-violet-600 text-white">
              <Airplay className="size-6" />
            </div>
            <div className="min-w-0 flex-1">
              <h2 className="truncate text-lg font-semibold">
                {airPlay?.sessionActive ? "正在镜像" : "NukaCast 已就绪"}
              </h2>
              {airPlay?.error
                ? <div className="mt-2 flex items-start gap-2 text-sm text-rose-300"><CircleAlert className="mt-0.5 size-4 shrink-0" />{airPlay.error}</div>
                : <p className="mt-1 text-sm text-muted-foreground">
                    {airPlay?.state === "waiting_network" ? "等待可用局域网…" : `接收端口 ${airPlay?.port || "-"} · 设备能力可在“设备”页查看`}
                  </p>}
              <div className="mt-3 flex flex-wrap gap-1.5">
                <Badge variant="outline">{airPlay?.decoderSoftwareFallback ? "软件解码" : "硬解优先"}</Badge>
                {airPlay?.decoderName && <Badge variant="outline" className="max-w-64 truncate">{airPlay.decoderName}</Badge>}
                {(airPlay?.decoderInputs ?? 0) > 0 && <Badge variant="outline">输入 {airPlay?.decoderInputs} / 输出 {airPlay?.decoderOutputs}</Badge>}
                {airPlay?.videoWidth ? <Badge variant="outline">{airPlay.videoWidth}×{airPlay.videoHeight}</Badge> : null}
              </div>
            </div>
          </div>
        </SectionCard>

        <SectionCard
          title="播放器"
          badges={<Badge variant="outline">{player?.playing ? "播放中" : "空闲"}</Badge>}
        >
          <div className="min-w-0">
            <div className="truncate font-medium">{player?.title || "未播放"}</div>
            <div className="mt-1 truncate text-xs text-muted-foreground">{player?.url || "-"}</div>
          </div>
          <div className="mt-4 flex flex-wrap items-center gap-2">
            <Button variant="outline" size="icon" title="后退 10 秒" onClick={() => control("seek", -10000)}><SkipBack /></Button>
            <Button size="icon" title={player?.playing ? "暂停" : "播放"} onClick={() => control("toggle")}>
              {player?.playing ? <Pause /> : <Play />}
            </Button>
            <Button variant="outline" size="icon" title="前进 30 秒" onClick={() => control("seek", 30000)}><SkipForward /></Button>
            <Button variant="outline" size="icon" title="停止" onClick={() => control("stop")}><Square /></Button>
          </div>
        </SectionCard>
      </div>

      <div className="mt-4 grid gap-3 sm:grid-cols-3">
        <OverviewFact icon={Wifi} label="控制地址" value={status?.webAddress?.replace(/^https?:\/\//, "") || "-"} />
        <OverviewFact icon={Server} label="片源" value={`${status?.sourceCount ?? 0} 个配置 · ${status?.siteCount ?? 0} 个站点`} />
        <OverviewFact icon={Film} label="当前播放" value={status?.activeMedia || "空闲"} />
      </div>

      <div className="mt-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        {shortcuts.map((shortcut) => {
          const Icon = shortcut.icon
          return (
            <button key={shortcut.view} type="button" onClick={() => onNavigate(shortcut.view)}
              className="group flex items-center gap-3 rounded-xl border bg-card/40 px-4 py-3 text-left outline-none transition hover:border-foreground/20 hover:bg-accent/40 focus-visible:ring-2 focus-visible:ring-ring">
              <span className="grid size-9 shrink-0 place-items-center rounded-lg border bg-background/60 text-muted-foreground transition group-hover:text-foreground">
                <Icon className="size-4" />
              </span>
              <span className="min-w-0">
                <span className="block truncate text-sm font-medium">{shortcut.label}</span>
                <span className="block truncate text-xs text-muted-foreground">{shortcut.hint}</span>
              </span>
            </button>
          )
        })}
      </div>

      <SectionCard
        className="mt-4"
        title="最近动态"
        badges={<Badge variant="outline">{logs.length}</Badge>}
        action={<Button variant="ghost" size="sm" onClick={() => onNavigate("logs")}>全部日志</Button>}
      >
        {logs.length === 0
          ? <p className="text-sm text-muted-foreground">还没有日志。刷新片源或投屏后这里会显示最近记录。</p>
          : (
            <div className="space-y-1.5">
              {logs.map((entry, index) => (
                <div key={`${entry.timestamp}-${index}`} className="flex items-start gap-3 text-sm">
                  <span className={`mt-1.5 size-2 shrink-0 rounded-full ${levelDot(entry.level)}`} />
                  <span className="w-16 shrink-0 text-xs text-muted-foreground">{clockOf(entry.timestamp)}</span>
                  <span className="w-20 shrink-0 truncate text-xs text-muted-foreground">{entry.component}</span>
                  <span className="min-w-0 flex-1 break-words">{entry.message}</span>
                </div>
              ))}
            </div>
          )}
      </SectionCard>
    </>
  )
}

function levelDot(level: LogLevel): string {
  if (level === "ERROR") return "bg-rose-400"
  if (level === "WARN") return "bg-amber-400"
  return "bg-emerald-400/70"
}

/** Saves text produced by the device as a file, without a round trip through a URL. */
function downloadText(fileName: string, text: string) {
  const blob = new Blob([text], { type: "text/plain;charset=utf-8" })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement("a")
  anchor.href = url
  anchor.download = fileName
  document.body.appendChild(anchor)
  anchor.click()
  document.body.removeChild(anchor)
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}

function diagnosticFileName(): string {
  const now = new Date()
  const pad = (value: number) => String(value).padStart(2, "0")
  return `nukacast-diagnostics-${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}.txt`
}

function formatDuration(milliseconds: number): string {
  const total = Math.max(0, Math.round(milliseconds / 1000))
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const seconds = total % 60
  if (hours > 0) return `${hours} 小时 ${minutes} 分`
  if (minutes > 0) return `${minutes} 分 ${seconds} 秒`
  return `${seconds} 秒`
}

function clockOf(timestamp: number): string {
  const date = new Date(timestamp)
  return `${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}:${String(date.getSeconds()).padStart(2, "0")}`
}

function OverviewFact({ icon: Icon, label, value }: { icon: typeof Gauge; label: string; value: string }) {
  return (
    <div className="flex min-w-0 items-center gap-3 px-4 py-3">
      <Icon className="size-4 shrink-0 text-muted-foreground" />
      <div className="min-w-0"><div className="text-xs text-muted-foreground">{label}</div><div className="mt-0.5 truncate text-sm font-medium">{value}</div></div>
    </div>
  )
}

function SearchView({ sourceVersion, setError }: { sourceVersion: number; setError: (value: string) => void }) {
  const [keyword, setKeyword] = useState("")
  const [contentType, setContentType] = useState("")
  const [year, setYear] = useState("")
  const [region, setRegion] = useState("")
  const [sites, setSites] = useState<Site[]>([])
  const [sources, setSources] = useState<Source[]>([])
  const [selectedSource, setSelectedSource] = useState("")
  const [selectedSites, setSelectedSites] = useState<string[]>([])
  const [result, setResult] = useState<SearchResponse | null>(null)
  const [busy, setBusy] = useState(false)
  const [detail, setDetail] = useState<MediaDetail | null>(null)
  const [detailBusy, setDetailBusy] = useState(false)
  const manualSourceSelection = useRef(false)
  const requestGate = useMemo(() => createLatestRequestGate(), [])
  const selectedSourceRef = useRef("")
  const searchCriteriaRef = useRef({ keyword, contentType, year, region })
  searchCriteriaRef.current = { keyword, contentType, year, region }

  const rankedSources = useMemo(() => rankLeafSources(sources), [sources])
  const visibleSites = useMemo(() => sites.filter((site) => site.sourceId === selectedSource), [sites, selectedSource])

  useEffect(() => {
    Promise.all([api.sources(), api.sites()]).then(([sourceItems, siteItems]) => {
      const ranked = rankLeafSources(sourceItems)
      const preferred = selectPreferredSource(ranked, selectedSourceRef.current, manualSourceSelection.current)
      setSources(sourceItems)
      setSites(siteItems)
      if (preferred === selectedSourceRef.current) return
      requestGate.begin()
      setBusy(false)
      setResult(null)
      setSelectedSites([])
      setSelectedSource(preferred)
      selectedSourceRef.current = preferred
      if (preferred && searchCriteriaRef.current.keyword.trim()) void search(preferred, [])
    }).catch((reason) => setError(message(reason)))
  }, [sourceVersion, setError])

  async function search(sourceId = selectedSource, siteKeys = selectedSites) {
    const request = requestGate.begin()
    const criteria = searchCriteriaRef.current
    setBusy(true)
    try {
      const next = await api.search({ sourceId, keyword: criteria.keyword, contentType: criteria.contentType, year: criteria.year, region: criteria.region, siteKeys, page: 1, pageSize: 80 })
      if (requestGate.isLatest(request)) setResult(next)
    } catch (reason) {
      if (requestGate.isLatest(request)) setError(message(reason))
    } finally {
      if (requestGate.isLatest(request)) setBusy(false)
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    await search()
  }

  function changeSource(sourceId: string) {
    manualSourceSelection.current = true
    selectedSourceRef.current = sourceId
    setSelectedSource(sourceId)
    setSelectedSites([])
    if (keyword.trim()) void search(sourceId, [])
  }

  async function openDetail(item: SearchItem) {
    setDetailBusy(true)
    try {
      setDetail(await api.detail({ sourceId: item.sourceId, siteKey: item.siteKey, vodId: item.vodId }))
    } catch (reason) {
      setError(message(reason))
    } finally {
      setDetailBusy(false)
    }
  }

  return (
    <>
      <PageHeader title="全站搜索" action={result && <Badge variant="outline">{result.items.length} 条 · {result.elapsedMs} ms</Badge>} />
      <form onSubmit={submit} className="border-y py-4">
        <div className="grid gap-2 sm:grid-cols-[minmax(180px,280px)_1fr_auto]">
          <select value={selectedSource} onChange={(event) => changeSource(event.target.value)} aria-label="搜索仓库" className="h-10 rounded-md border bg-background px-3 text-sm outline-none focus:ring-2 focus:ring-ring">
            {rankedSources.map((source, index) => <option key={source.id} value={source.id}>{index === 0 ? "最快 · " : ""}{sourceLabel(source, sources)}</option>)}
          </select>
          <Input value={keyword} onChange={(e) => setKeyword(e.target.value)} placeholder="片名、演员或导演" className="h-10" />
          <Button className="h-10" disabled={busy || !keyword.trim() || !selectedSource}>{busy ? <LoaderCircle className="animate-spin" /> : <Search />}搜索</Button>
        </div>
        {rankedSources.length > 0 && <div className="mt-2 text-xs text-muted-foreground">默认使用最快的健康仓；手动切换后保留当前选择。</div>}
        <div className="mt-3 grid gap-2 sm:grid-cols-3 lg:grid-cols-[160px_160px_160px_1fr]">
          <Select value={contentType} onChange={setContentType} label="全部类型" values={["电影", "电视剧", "综艺", "动漫"]} />
          <Select value={year} onChange={setYear} label="全部年份" values={["2026", "2025", "2024", "2023", "2022", "2021", "2020"]} />
          <Select value={region} onChange={setRegion} label="全部地区" values={["中国", "美国", "日本", "韩国", "英国"]} />
          <div className="flex items-center gap-2 overflow-x-auto">
            <ListFilter className="size-4 shrink-0 text-muted-foreground" />
            {visibleSites.slice(0, 12).map((site) => {
              const active = selectedSites.includes(site.key)
              return <Button key={site.key} type="button" size="sm" variant={active ? "secondary" : "outline"} onClick={() => setSelectedSites(active ? selectedSites.filter((key) => key !== site.key) : [...selectedSites, site.key])}>{site.name}</Button>
            })}
          </div>
        </div>
      </form>

      {result?.partial && <div className="mt-4 flex items-center gap-2 text-sm text-destructive"><CircleAlert className="size-4" />{result.failedSites} 个站点未完成</div>}
      <section className="mt-5 grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-4 xl:grid-cols-6 2xl:grid-cols-8">
        {result?.items.map((item) => <Poster key={`${item.sourceId}-${item.siteKey}-${item.vodId}`} item={item} onClick={() => openDetail(item)} />)}
      </section>
      {result && result.items.length === 0 && <Empty icon={Search} label="没有匹配结果" />}
      {detailBusy && <div className="fixed inset-0 z-40 grid place-items-center bg-background/80"><LoaderCircle className="size-8 animate-spin text-primary" /></div>}
      {detail && <DetailDialog detail={detail} onClose={() => setDetail(null)} setError={setError} />}
    </>
  )
}

function Select({ value, onChange, label, values }: { value: string; onChange: (value: string) => void; label: string; values: string[] }) {
  return <select value={value} onChange={(e) => onChange(e.target.value)} className="h-9 rounded-md border bg-background px-3 text-sm outline-none focus:ring-2 focus:ring-ring"><option value="">{label}</option>{values.map((item) => <option key={item}>{item}</option>)}</select>
}

function Poster({ item, onClick }: { item: SearchItem; onClick: () => void }) {
  const [failed, setFailed] = useState(false)
  return (
    <button type="button" onClick={onClick} className="group min-w-0 rounded-md text-left outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2">
      <div className="aspect-[2/3] overflow-hidden rounded-md border bg-muted">
        {!failed && item.poster ? <img src={item.poster} alt="" className="h-full w-full object-cover transition-transform duration-200 group-hover:scale-[1.02]" loading="lazy" onError={() => setFailed(true)} /> : <div className="grid h-full place-items-center text-muted-foreground"><Film className="size-8" /></div>}
      </div>
      <h3 className="mt-2 truncate text-sm font-medium">{item.name}</h3>
      <div className="mt-1 flex items-center justify-between gap-1 text-xs text-muted-foreground"><span className="truncate">{item.remarks || item.year || "-"}</span><span className="shrink-0">{item.siteName}</span></div>
    </button>
  )
}

function DetailDialog({ detail, onClose, setError }: { detail: MediaDetail; onClose: () => void; setError: (value: string) => void }) {
  const [posterFailed, setPosterFailed] = useState(false)

  return (
    <div className="fixed inset-0 z-50 overflow-y-auto bg-background/90 p-3 backdrop-blur-xs sm:p-6" role="dialog" aria-modal="true" aria-label={detail.name}>
      <div className="mx-auto min-h-full max-w-5xl border bg-background shadow-xl">
        <header className="sticky top-0 z-10 flex min-h-14 items-center gap-3 border-b bg-background/95 px-4 backdrop-blur">
          <div className="min-w-0 flex-1"><h2 className="truncate text-lg font-semibold">{detail.name}</h2><div className="text-xs text-muted-foreground">{detail.siteName}</div></div>
          <Button variant="ghost" size="icon" title="关闭" onClick={onClose}><X /></Button>
        </header>
        <div className="grid gap-6 p-4 md:grid-cols-[190px_1fr] md:p-6">
          <div>
            <div className="aspect-[2/3] overflow-hidden rounded-md border bg-muted">
              {!posterFailed && detail.poster ? <img src={detail.poster} alt="" className="h-full w-full object-cover" onError={() => setPosterFailed(true)} /> : <div className="grid h-full place-items-center text-muted-foreground"><Film className="size-10" /></div>}
            </div>
            <div className="mt-3 flex flex-wrap gap-1.5">{[detail.year, detail.area, detail.typeName, detail.score].filter(Boolean).map((value) => <Badge key={value} variant="outline">{value}</Badge>)}</div>
          </div>
          <div className="min-w-0">
            <div className="grid gap-2 text-sm sm:grid-cols-[70px_1fr]"><span className="text-muted-foreground">主演</span><span>{detail.actor || "-"}</span><span className="text-muted-foreground">导演</span><span>{detail.director || "-"}</span></div>
            {detail.plot && <p className="mt-4 max-h-28 overflow-y-auto border-y py-3 text-sm leading-6 text-muted-foreground">{detail.plot.replace(/<[^>]+>/g, "")}</p>}
            <PlaySources detail={detail} title={detail.name} setError={setError} />
          </div>
        </div>
      </div>
    </div>
  )
}

function PlaySources({ detail, title, setError }: { detail: MediaDetail; title: string; setError: (value: string) => void }) {
  const [playing, setPlaying] = useState("")

  async function play(flag: string, episodeId: string, episodeName: string) {
    setPlaying(`${flag}-${episodeId}`)
    try {
      await api.playItem({
        sourceId: detail.sourceId,
        siteKey: detail.siteKey,
        siteName: detail.siteName,
        vodId: detail.vodId,
        name: title,
        poster: detail.poster,
        remarks: detail.remarks,
        year: detail.year,
        typeName: detail.typeName,
        flag,
        episodeId,
        episodeName,
        title: `${title} · ${episodeName}`,
      })
    } catch (reason) {
      setError(message(reason))
    } finally {
      setPlaying("")
    }
  }

  return (
    <section className="mt-5 space-y-5">
      {detail.playSources.map((source) => (
        <div key={source.name}>
          <div className="mb-2 flex items-center gap-2"><h3 className="section-title">{source.name}</h3><Badge variant="secondary">{source.episodes.length}</Badge></div>
          <div className="grid grid-cols-3 gap-2 sm:grid-cols-5 lg:grid-cols-7">
            {source.episodes.map((episode) => {
              const key = `${source.name}-${episode.id}`
              return <Button key={key} variant="outline" size="sm" className="min-w-0 justify-center truncate" title={episode.name} disabled={playing === key} onClick={() => play(source.name, episode.id, episode.name)}>{playing === key ? <LoaderCircle className="animate-spin" /> : episode.name}</Button>
            })}
          </div>
        </div>
      ))}
      {detail.playSources.length === 0 && <Empty icon={Film} label="该站点未返回可播放选集" />}
    </section>
  )
}

/**
 * Short-drama catalog. The catalog only ships metadata: playback lines are matched against the
 * user's enabled TVBox sources and every candidate is shown for confirmation before playback.
 */
function DramaView({ setError }: { setError: (value: string) => void }) {
  const [providers, setProviders] = useState<DramaProvider[]>([])
  const [providerId, setProviderId] = useState("")
  const [keyword, setKeyword] = useState("")
  const [result, setResult] = useState<DramaSearchResult | null>(null)
  const [mode, setMode] = useState<"search" | "browse">("search")
  const [page, setPage] = useState(1)
  const [busy, setBusy] = useState(false)
  const [detail, setDetail] = useState<DramaDetail | null>(null)
  const [detailBusy, setDetailBusy] = useState(false)
  const [showSources, setShowSources] = useState(false)
  const [customName, setCustomName] = useState("")
  const [customUrl, setCustomUrl] = useState("")
  const [providerBusy, setProviderBusy] = useState(false)
  const gate = useMemo(() => createLatestRequestGate(), [])


  const load = useCallback(() => api.dramaProviders().then((data) => {
    setProviders(data.providers)
    setProviderId((current) => {
      if (current && data.providers.some((provider) => provider.id === current && provider.enabled)) return current
      const enabled = data.providers.find((provider) => provider.enabled)
      return enabled ? enabled.id : ""
    })
  }).catch((reason) => setError(message(reason))), [setError])

  useEffect(() => { void load() }, [load])

  const enabled = providers.filter((provider) => provider.enabled)
  const active = providers.find((provider) => provider.id === providerId) ?? null
  const canBrowse = !!active && active.kind === "cms.drama"

  async function search(event?: FormEvent) {
    event?.preventDefault()
    if (!keyword.trim() || !providerId) return
    const request = gate.begin()
    setBusy(true)
    setMode("search")
    try {
      const next = await api.dramaSearch({ providerId, keyword: keyword.trim() })
      if (!gate.isLatest(request)) return
      setResult(next)
      if (!next.ok) setError(next.error || "短剧搜索失败")
    } catch (reason) {
      if (gate.isLatest(request)) setError(message(reason))
    } finally {
      if (gate.isLatest(request)) setBusy(false)
    }
  }

  async function browse(nextPage = 1) {
    if (!providerId) return
    const request = gate.begin()
    setBusy(true)
    setMode("browse")
    setPage(nextPage)
    try {
      const next = await api.dramaBrowse({ providerId, page: nextPage })
      if (!gate.isLatest(request)) return
      setResult(next)
      if (!next.ok) setError(next.error || "分类浏览失败")
    } catch (reason) {
      if (gate.isLatest(request)) setError(message(reason))
    } finally {
      if (gate.isLatest(request)) setBusy(false)
    }
  }

  async function openDetail(item: DramaItem) {
    setDetailBusy(true)
    setDetail(null)
    try {
      setDetail(await api.dramaDetail({ providerId: item.providerId || providerId, dramaId: item.dramaId }))
    } catch (reason) {
      setError(message(reason))
    } finally {
      setDetailBusy(false)
    }
  }

  async function runProviderAction(action: () => Promise<unknown>) {
    setProviderBusy(true)
    try {
      await action()
      await load()
    } catch (reason) {
      setError(message(reason))
    } finally {
      setProviderBusy(false)
    }
  }

  return (
    <>
      <PageHeader
        title="短剧"
        badges={<Badge variant={enabled.length ? "secondary" : "destructive"}>{enabled.length ? `${enabled.length} 个目录已启用` : "未启用目录"}</Badge>}
        action={<Button variant="outline" size="sm" onClick={() => setShowSources(!showSources)}><Library className="size-4" />目录管理</Button>}
      />

      <div className="space-y-4">
        <RecommendedShelf
          kind="drama"
          title="推荐源"
          onChanged={load}
          setError={setError}
        />

        {showSources && (
          <SectionCard
            title="短剧目录"
            badges={<Badge variant="outline">{providers.length}</Badge>}
            action={<Button variant="ghost" size="sm" onClick={() => setShowSources(false)}><X className="size-4" />收起</Button>}
          >
            <div className="space-y-2">
              {providers.map((provider) => (
                <div key={provider.id} className="flex flex-wrap items-center gap-3 rounded-lg border bg-background/40 p-3">
                  <span className={`inline-flex items-center rounded-md border px-2 py-0.5 text-xs ${kindTone(provider.kind === "cms.drama" ? "drama" : "vod").badge}`}>
                    {provider.kind === "cms.drama" ? "CMS 直连" : "资料目录"}
                  </span>
                  <div className="min-w-[200px] flex-1">
                    <div className="flex items-center gap-2 text-sm font-medium">
                      {provider.name}
                      {!provider.enabled && <Badge variant="outline">已停用</Badge>}
                      {provider.error && <Badge variant="destructive">异常</Badge>}
                    </div>
                    <div className="truncate text-xs text-muted-foreground">
                      {hostOf(provider.baseUrl)}{provider.categoryId ? ` · 分类 ${provider.categoryId}` : ""}
                    </div>
                    {provider.error && <div className="mt-1 text-xs text-rose-300">{provider.error}</div>}
                  </div>
                  <Button variant="ghost" size="sm" disabled={providerBusy}
                    onClick={() => runProviderAction(() => api.setDramaProviderEnabled(provider.id, !provider.enabled))}>
                    {provider.enabled ? "停用" : "启用"}
                  </Button>
                  <Button variant="ghost" size="icon" title="删除" disabled={providerBusy}
                    onClick={() => runProviderAction(() => api.removeDramaProvider(provider.id))}>
                    <Trash2 className="size-4" />
                  </Button>
                </div>
              ))}
              {providers.length === 0 && (
                <EmptyState icon={Clapperboard} title="还没有短剧目录" hint="用推荐源一键添加，或粘贴地址。" />
              )}
              <form className="grid gap-2 pt-2 sm:grid-cols-[200px_1fr_auto]"
                onSubmit={(event) => {
                  event.preventDefault()
                  void runProviderAction(async () => {
                    await api.addDramaProvider({ name: customName, url: customUrl })
                    setCustomName("")
                    setCustomUrl("")
                  })
                }}>
                <Input value={customName} onChange={(event) => setCustomName(event.target.value)} placeholder="名称（可选）" />
                <Input value={customUrl} onChange={(event) => setCustomUrl(event.target.value)} placeholder="短剧站地址，或 …/api.php/provide/vod" />
                <Button disabled={providerBusy || !customUrl.trim()}>{providerBusy ? <LoaderCircle className="animate-spin" /> : <Plus />}添加</Button>
              </form>
            </div>
          </SectionCard>
        )}

        <SectionCard
          title="查找剧集"
          description={active ? active.name : "先添加一个短剧目录"}
        >
          <div className="flex flex-wrap items-center gap-2">
            {providers.map((provider) => (
              <button key={provider.id} type="button" disabled={!provider.enabled} onClick={() => setProviderId(provider.id)}
                className={`rounded-full border px-3 py-1 text-xs outline-none transition focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-40 ${providerId === provider.id ? "border-foreground/30 bg-accent text-foreground" : "text-muted-foreground hover:bg-accent/50"}`}>
                {provider.name}
              </button>
            ))}
          </div>

          <form onSubmit={search} className="mt-3 flex flex-wrap gap-2">
            <Input value={keyword} onChange={(event) => setKeyword(event.target.value)} placeholder="输入短剧名，如：重生" className="h-10 min-w-[220px] flex-1" />
            <Button className="h-10" disabled={busy || !keyword.trim() || !providerId}>
              {busy && mode === "search" ? <LoaderCircle className="animate-spin" /> : <Search />}搜索
            </Button>
            <Button type="button" variant="outline" className="h-10" disabled={busy || !canBrowse}
              title={canBrowse ? "浏览该站短剧分类" : "该目录不支持分类浏览"} onClick={() => browse(1)}>
              {busy && mode === "browse" ? <LoaderCircle className="animate-spin" /> : <Clapperboard />}分类浏览
            </Button>
          </form>

          {result && (
            <div className="mt-3 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
              <Badge variant="outline">{result.items.length} 条{result.total >= 0 ? ` / 共 ${result.total}` : ""}</Badge>
              <span>{result.elapsedMs} ms</span>
              {result.warning && <span className="text-rose-300">{result.warning}</span>}
              {result.partial && <span className="text-rose-300">结果可能不完整</span>}
              {mode === "browse" && (
                <span className="flex items-center gap-1">
                  <Button variant="ghost" size="sm" disabled={busy || page <= 1} onClick={() => browse(page - 1)}>上一页</Button>
                  <span>第 {page} 页</span>
                  <Button variant="ghost" size="sm" disabled={busy || result.items.length === 0} onClick={() => browse(page + 1)}>下一页</Button>
                </span>
              )}
            </div>
          )}

          <div className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-4 xl:grid-cols-6 2xl:grid-cols-8">
            {busy && !result && Array.from({ length: 8 }).map((_, index) => (
              <div key={index} className="space-y-2">
                <Skeleton className="aspect-[2/3] w-full" />
                <Skeleton className="h-4 w-4/5" />
              </div>
            ))}
            {result?.items.map((item) => (
              <DramaCard key={`${item.providerId}-${item.dramaId}`} item={item} onClick={() => openDetail(item)} />
            ))}
          </div>

          {!busy && result && result.items.length === 0 && (
            <EmptyState icon={Clapperboard} title="没有匹配的短剧" hint="换个关键词试试。" />
          )}
          {!result && !busy && (
            <EmptyState icon={Clapperboard} title={providerId ? "搜索或浏览短剧" : "先添加并启用一个短剧目录"}
              hint={providerId ? "CMS 直连目录可以直接在播放器里播放，无需再匹配片源线路。" : undefined} />
          )}
        </SectionCard>
      </div>

      {detailBusy && (
        <div className="fixed inset-0 z-40 grid place-items-center bg-background/80">
          <LoaderCircle className="size-8 animate-spin text-primary" />
        </div>
      )}
      {detail && <DramaDialog detail={detail} onSwitch={openDetail} onClose={() => setDetail(null)} setError={setError} />}
    </>
  )
}

function DramaCard({ item, onClick }: { item: DramaItem; onClick: () => void }) {
  const [failed, setFailed] = useState(false)
  const facts = dramaFacts(item)
  return (
    <button type="button" onClick={onClick}
      className="group min-w-0 rounded-xl text-left outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background">
      <div className="relative aspect-[2/3] overflow-hidden rounded-xl border bg-muted">
        {!failed && item.cover
          ? <img src={item.cover} alt="" loading="lazy" onError={() => setFailed(true)}
              className="h-full w-full object-cover transition duration-300 group-hover:scale-[1.04]" />
          : <div className="grid h-full place-items-center text-muted-foreground"><Clapperboard className="size-8" /></div>}
        <div className="pointer-events-none absolute inset-x-0 bottom-0 bg-gradient-to-t from-background/90 to-transparent p-2 pt-8">
          <div className="flex items-center gap-1">
            {item.episodeCount > 0 && <Badge variant="secondary">{item.episodeCount} 集</Badge>}
            {item.category && <Badge variant="outline">{item.category}</Badge>}
          </div>
        </div>
      </div>
      <h3 className="mt-2 truncate text-sm font-medium">{item.title}</h3>
      <div className="mt-0.5 truncate text-xs text-muted-foreground">{item.remark || facts[0] || "短剧"}</div>
    </button>
  )
}

function DramaDialog({ detail, onSwitch, onClose, setError }: {
  detail: DramaDetail
  onSwitch: (item: DramaItem) => void
  onClose: () => void
  setError: (value: string) => void
}) {
  const item = detail.item
  const [lines, setLines] = useState<DramaLineResult | null>(null)
  const [linesBusy, setLinesBusy] = useState(false)
  const [showLines, setShowLines] = useState(!detail.directPlayable)
  const [selected, setSelected] = useState<{ line: DramaLine; media: MediaDetail } | null>(null)
  const [posterFailed, setPosterFailed] = useState(false)
  const [playing, setPlaying] = useState(0)

  async function findLines() {
    setLinesBusy(true)
    setShowLines(true)
    try {
      setLines(await api.dramaLines({ providerId: item.providerId, dramaId: item.dramaId }))
    } catch (reason) {
      setError(message(reason))
    } finally {
      setLinesBusy(false)
    }
  }

  async function chooseLine(line: DramaLine) {
    setLinesBusy(true)
    try {
      const media = await api.detail({ sourceId: line.sourceId, siteKey: line.siteKey, vodId: line.vodId })
      setSelected({ line, media })
    } catch (reason) {
      setError(message(reason))
    } finally {
      setLinesBusy(false)
    }
  }

  async function play(episode: DramaEpisode) {
    setPlaying(episode.index)
    try {
      await api.dramaPlay({
        providerId: item.providerId,
        dramaId: item.dramaId,
        index: episode.index,
        title: item.title,
        poster: item.cover,
      })
    } catch (reason) {
      setError(message(reason))
    } finally {
      setPlaying(0)
    }
  }

  return (
    <div className="fixed inset-0 z-50 overflow-y-auto bg-background/90 p-3 backdrop-blur-sm sm:p-6" role="dialog" aria-modal="true" aria-label={item.title}>
      <div className="mx-auto max-w-5xl overflow-hidden rounded-2xl border bg-background shadow-2xl">
        <header className="sticky top-0 z-10 flex min-h-14 items-center gap-3 border-b bg-background/95 px-4 backdrop-blur">
          <div className="min-w-0 flex-1">
            <h2 className="truncate text-lg font-semibold">{item.title}</h2>
            <div className="truncate text-xs text-muted-foreground">ID {item.dramaId}</div>
          </div>
          <Button variant="ghost" size="icon" title="关闭" onClick={onClose}><X /></Button>
        </header>
        <div className="grid gap-6 p-4 md:grid-cols-[190px_1fr] md:p-6">
          <div>
            <div className="aspect-[2/3] overflow-hidden rounded-xl border bg-muted">
              {!posterFailed && item.cover
                ? <img src={item.cover} alt="" className="h-full w-full object-cover" onError={() => setPosterFailed(true)} />
                : <div className="grid h-full place-items-center text-muted-foreground"><Clapperboard className="size-10" /></div>}
            </div>
            <div className="mt-3 flex flex-wrap gap-1.5">
              {dramaFacts(item).map((fact) => <Badge key={fact} variant="outline">{fact}</Badge>)}
            </div>
          </div>
          <div className="min-w-0">
            {item.tags.length > 0 && (
              <div className="flex flex-wrap gap-1.5">{item.tags.map((tag) => <Badge key={tag} variant="secondary">{tag}</Badge>)}</div>
            )}
            {item.intro && (
              <p className="mt-4 max-h-28 overflow-y-auto rounded-lg border bg-card/40 p-3 text-sm leading-6 text-muted-foreground">{item.intro}</p>
            )}

            {detail.directPlayable ? (
              <section className="mt-5">
                <div className="mb-2 flex flex-wrap items-center gap-2">
                  <h3 className="section-title">剧集</h3>
                  <Badge variant="secondary">{detail.episodes.length} 集</Badge>
                  <span className="text-xs text-muted-foreground">点击即在电视上播放</span>
                  <Button variant="ghost" size="sm" className="ml-auto" onClick={findLines} disabled={linesBusy}>
                    <Search className="size-4" />用片源线路播放
                  </Button>
                </div>
                <div className="grid max-h-72 grid-cols-4 gap-2 overflow-y-auto pr-1 sm:grid-cols-6 lg:grid-cols-8">
                  {detail.episodes.map((episode) => (
                    <Button key={episode.index} variant="outline" size="sm" className="min-w-0 justify-center truncate"
                      title={episode.name} disabled={playing === episode.index} onClick={() => play(episode)}>
                      {playing === episode.index ? <LoaderCircle className="animate-spin" /> : episode.name}
                    </Button>
                  ))}
                </div>
                {detail.note && <p className="mt-2 text-xs text-muted-foreground">{detail.note}</p>}
              </section>
            ) : (
              <section className="mt-5">
                <div className="mb-2 flex flex-wrap items-center gap-2">
                  <h3 className="section-title">播放线路</h3>
                  <Badge variant="outline">资料目录</Badge>
                </div>
                <p className="text-xs leading-5 text-muted-foreground">
                  该目录只提供剧目资料，播放线路来自“源管理”里已启用的片源，按片名匹配；确认条目后再播放。
                </p>
                {!lines && (
                  <Button className="mt-3" variant="outline" disabled={linesBusy} onClick={findLines}>
                    {linesBusy ? <LoaderCircle className="animate-spin" /> : <Search />}在已启用片源中匹配
                  </Button>
                )}
              </section>
            )}

            {showLines && lines && (
              <section className="mt-4 space-y-2">
                <div className="text-xs text-muted-foreground">
                  已查询 {lines.searchedSites} 个站点{lines.failedSites > 0 ? `，${lines.failedSites} 个失败` : ""} · {lines.elapsedMs} ms
                </div>
                {lines.lines.map((line) => (
                  <button key={`${line.siteKey}-${line.vodId}`} type="button" disabled={linesBusy} onClick={() => chooseLine(line)}
                    className="flex w-full min-w-0 items-center gap-3 rounded-lg border bg-card px-3 py-2 text-left outline-none transition hover:bg-accent focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-60">
                    <span className="min-w-0 flex-1 truncate text-sm font-medium">{lineLabel(line)}</span>
                    <Badge variant={line.matchKind === "exact" ? "secondary" : "outline"}>{matchLabel(line.matchKind)}</Badge>
                  </button>
                ))}
                {lines.lines.length === 0 && (
                  <div className="rounded-lg border border-dashed px-3 py-4 text-sm text-muted-foreground">
                    暂无可用播放线路。{missingLineHint(lines.searched, lines.failedSites)}
                  </div>
                )}
                {lines.error && lines.lines.length > 0 && <div className="text-xs text-rose-300">{lines.error}</div>}
              </section>
            )}

            {selected && (
              <section className="mt-5 border-t pt-4">
                <div className="mb-2 flex flex-wrap items-center gap-2">
                  <h3 className="section-title">选集</h3>
                  <Badge variant="outline">{selected.line.siteName}</Badge>
                  <Button variant="ghost" size="sm" onClick={() => setSelected(null)}>重新选择线路</Button>
                </div>
                <PlaySources detail={selected.media} title={item.title} setError={setError} />
              </section>
            )}

            {detail.related.length > 0 && (
              <section className="mt-6 border-t pt-4">
                <div className="mb-2 flex items-center gap-2">
                  <h3 className="section-title">相关短剧</h3>
                  <Badge variant="secondary">{detail.related.length}</Badge>
                </div>
                <div className="flex flex-wrap gap-2">
                  {detail.related.slice(0, 12).map((related) => (
                    <Button key={related.dramaId} variant="outline" size="sm" className="max-w-56 truncate" onClick={() => onSwitch(related)}>
                      {related.title}
                    </Button>
                  ))}
                </div>
                {detail.relatedPartial && <div className="mt-2 text-xs text-muted-foreground">相关推荐加载不完整</div>}
              </section>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

function LiveView({ contentVersion, setError }: { contentVersion: number; setError: (value: string) => void }) {
  const [sources, setSources] = useState<LiveSource[]>([])
  const [selected, setSelected] = useState("")
  const [catalog, setCatalog] = useState<LiveCatalog | null>(null)
  const [busy, setBusy] = useState(false)
  const [playing, setPlaying] = useState("")
  const [schedule, setSchedule] = useState<EpgSchedule | null>(null)
  const [epgBusy, setEpgBusy] = useState(false)
  const [playlists, setPlaylists] = useState<LiveSourceRow[]>([])
  const [showManager, setShowManager] = useState(false)
  const [playlistName, setPlaylistName] = useState("")
  const [playlistUrl, setPlaylistUrl] = useState("")
  const [playlistBusy, setPlaylistBusy] = useState(false)
  const [channelFilter, setChannelFilter] = useState("")
  const requestGate = useMemo(() => createLatestRequestGate(), [])

  const loadPlaylists = useCallback(() => api.liveSourceRows().then(setPlaylists).catch((reason) => setError(message(reason))), [setError])

  useEffect(() => {
    void loadPlaylists()
  }, [loadPlaylists, contentVersion])

  useEffect(() => {
    api.liveSources().then((items) => {
      setSources(items)
      if (!items.length) {
        setSelected("")
        setCatalog(null)
        setSchedule(null)
        return
      }
      const next = items.some((source) => source.id === selected) ? selected : items[0].id
      void load(next)
    }).catch((reason) => setError(message(reason)))
  }, [contentVersion, setError, playlists.length])

  async function load(id: string) {
    const request = requestGate.begin()
    setSelected(id)
    setBusy(true)
    setCatalog(null)
    setSchedule(null)
    try {
      const next = await api.liveCatalog(id)
      if (requestGate.isLatest(request)) setCatalog(next)
    } catch (reason) {
      if (requestGate.isLatest(request)) setError(message(reason))
    } finally {
      if (requestGate.isLatest(request)) setBusy(false)
    }
  }

  async function play(channelId: string) {
    setPlaying(channelId)
    setSchedule(null)
    try {
      await api.playLive(selected, channelId)
    } catch (reason) {
      setError(message(reason))
      setPlaying("")
      return
    }
    setPlaying("")
    setEpgBusy(true)
    try {
      setSchedule(await api.epg(selected, channelId))
    } catch {
      setSchedule(null)
    } finally {
      setEpgBusy(false)
    }
  }

  async function addPlaylist(event: FormEvent) {
    event.preventDefault()
    setPlaylistBusy(true)
    try {
      await api.addLiveSource({ name: playlistName, url: playlistUrl })
      setPlaylistName("")
      setPlaylistUrl("")
      await loadPlaylists()
    } catch (reason) {
      setError(message(reason))
    } finally {
      setPlaylistBusy(false)
    }
  }

  async function removePlaylist(id: string) {
    try {
      await api.removeLiveSource(id)
      await loadPlaylists()
    } catch (reason) {
      setError(message(reason))
    }
  }

  async function togglePlaylist(row: LiveSourceRow) {
    try {
      await api.setLiveSourceEnabled(row.id, !row.enabled)
      await loadPlaylists()
    } catch (reason) {
      setError(message(reason))
    }
  }

  const channelCount = catalog?.groups.reduce((sum, group) => sum + group.channels.length, 0) ?? 0
  const filter = channelFilter.trim().toLowerCase()
  const visibleGroups = (catalog?.groups ?? [])
    .map((group) => ({
      ...group,
      channels: filter ? group.channels.filter((channel) => channel.name.toLowerCase().includes(filter)) : group.channels,
    }))
    .filter((group) => group.channels.length > 0)
  const visibleCount = visibleGroups.reduce((sum, group) => sum + group.channels.length, 0)

  return (
    <>
      <PageHeader
        title="直播"
        badges={<Badge variant="outline">{channelCount} 个频道</Badge>}
        action={<Button variant="outline" size="sm" onClick={() => setShowManager(!showManager)}><ListFilter className="size-4" />直播源管理</Button>}
      />

      <div className="space-y-4">
        {showManager && (
          <>
            <RecommendedShelf
              kind="live"
              title="推荐源"
              onChanged={loadPlaylists}
              setError={setError}
            />
            <SectionCard
              title="我的直播源"
              badges={<Badge variant="outline">{playlists.filter((row) => row.user).length} 条自定义</Badge>}
            >
              <form onSubmit={addPlaylist} className="grid gap-2 sm:grid-cols-[200px_1fr_auto]">
                <Input value={playlistName} onChange={(event) => setPlaylistName(event.target.value)} placeholder="名称（可选）" />
                <Input value={playlistUrl} onChange={(event) => setPlaylistUrl(event.target.value)} placeholder="https://.../live.m3u" />
                <Button disabled={playlistBusy || !playlistUrl.trim()}>{playlistBusy ? <LoaderCircle className="animate-spin" /> : <Plus />}添加</Button>
              </form>
              <div className="mt-3 space-y-2">
                {playlists.filter((row) => row.user).map((row) => (
                  <div key={row.id} className="flex flex-wrap items-center gap-3 rounded-lg border bg-background/40 p-3">
                    <StatusDot className={row.error ? "bg-rose-400" : row.enabled ? "bg-emerald-400" : "bg-muted-foreground/50"} />
                    <div className="min-w-[200px] flex-1">
                      <div className="text-sm font-medium">{row.name}</div>
                      <div className="truncate text-xs text-muted-foreground">{hostOf(row.url)}</div>
                      {row.error && <div className="mt-1 text-xs text-rose-300">{row.error}</div>}
                    </div>
                    <Button variant="ghost" size="sm" onClick={() => togglePlaylist(row)}>{row.enabled ? "停用" : "启用"}</Button>
                    <Button variant="ghost" size="icon" title="删除" onClick={() => removePlaylist(row.id)}><Trash2 className="size-4" /></Button>
                  </div>
                ))}
                {playlists.filter((row) => row.user).length === 0 && (
                  <p className="rounded-lg border border-dashed px-3 py-4 text-center text-sm text-muted-foreground">
                    还没有自定义直播源。也可以直接用上面的推荐源一键添加。
                  </p>
                )}
              </div>
            </SectionCard>
          </>
        )}

        <div className="flex flex-wrap items-center gap-2">
          {sources.map((source) => (
            <button key={source.id} type="button" onClick={() => load(source.id)}
              className={`flex items-center gap-2 rounded-full border px-3 py-1.5 text-sm outline-none transition focus-visible:ring-2 focus-visible:ring-ring ${selected === source.id ? "border-foreground/30 bg-accent text-foreground" : "text-muted-foreground hover:bg-accent/50"}`}>
              <Radio className="size-3.5" />{source.name}
            </button>
          ))}
          {channelCount > 0 && (
            <Input value={channelFilter} onChange={(event) => setChannelFilter(event.target.value)}
              placeholder={`筛选 ${channelCount} 个频道`} className="h-9 w-full sm:ml-auto sm:w-56" />
          )}
        </div>

        {busy && <RowSkeletons count={4} />}
        {!busy && sources.length === 0 && (
          <EmptyState icon={Radio} title="还没有直播源" hint="添加一个 m3u / txt 清单。"
            action={<Button size="sm" onClick={() => setShowManager(true)}><Plus />添加直播源</Button>} />
        )}

        {epgBusy && <Skeleton className="h-16 w-full" />}
        {schedule && schedule.programs.length > 0 && (
          <SectionCard title={`${schedule.channel} · 节目单`} badges={<Badge variant="outline">{schedule.date}</Badge>}>
            <div className="flex gap-2 overflow-x-auto pb-1">
              {schedule.programs.map((program, index) => (
                <div key={`${program.start}-${index}`} className="w-44 shrink-0 rounded-lg border bg-background/40 px-3 py-2">
                  <div className="truncate text-sm font-medium">{program.title}</div>
                  <div className="mt-1 text-xs text-muted-foreground">{program.start} - {program.end}</div>
                </div>
              ))}
            </div>
          </SectionCard>
        )}

        {!busy && catalog && (
          <div className="space-y-6">
            {filter && (
              <p className="text-xs text-muted-foreground">筛选出 {visibleCount} 个频道</p>
            )}
            {visibleGroups.map((group) => (
              <section key={group.name}>
                <div className="mb-3 flex items-center gap-2">
                  <h2 className="section-title">{group.name}</h2>
                  <Badge variant="secondary">{group.channels.length}</Badge>
                </div>
                <div className="grid grid-cols-[repeat(auto-fill,minmax(200px,1fr))] gap-2">
                  {group.channels.map((channel) => (
                    <button key={channel.id} type="button" onClick={() => play(channel.id)} disabled={playing === channel.id}
                      title={channel.urls[0]}
                      className="flex h-16 min-w-0 items-center gap-3 rounded-xl border bg-card px-3 text-left outline-none transition hover:border-foreground/20 hover:bg-accent focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-60">
                      {channel.logo
                        ? <img src={channel.logo} alt="" loading="lazy" className="size-9 shrink-0 object-contain" />
                        : <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-muted text-muted-foreground"><Tv className="size-5" /></span>}
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-sm font-medium">{channel.name}</span>
                        {channel.urls.length > 1 && (
                          <span className="block truncate text-xs text-muted-foreground">{channel.urls.length} 个地址</span>
                        )}
                      </span>
                      {playing === channel.id && <LoaderCircle className="size-4 shrink-0 animate-spin" />}
                    </button>
                  ))}
                </div>
              </section>
            ))}
            {filter && visibleCount === 0 && (
              <EmptyState icon={Search} title="没有匹配的频道" hint="换个关键词。" />
            )}
          </div>
        )}
      </div>
    </>
  )
}

function SourcesView({ contentVersion, onChanged, setError }: { contentVersion: number; onChanged: () => void; setError: (value: string) => void }) {
  const [sources, setSources] = useState<Source[]>([])
  const [name, setName] = useState("")
  const [url, setUrl] = useState("")
  const [busy, setBusy] = useState(false)
  const load = useCallback(() => api.sources().then(setSources).catch((reason) => setError(message(reason))), [setError])
  useEffect(() => { load() }, [load, contentVersion])
  const orderedSources = useMemo(() => orderSourceTree(sources), [sources])

  async function add(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    try {
      await api.addSource(name, url)
      setName("")
      setUrl("")
      await load()
      onChanged()
    } catch (reason) {
      await load()
      onChanged()
      setError(message(reason))
    } finally {
      setBusy(false)
    }
  }

  async function remove(id: string) {
    try {
      await api.removeSource(id)
      await load()
      onChanged()
    } catch (reason) {
      setError(message(reason))
    }
  }

  async function refresh() {
    try {
      await api.refreshSources()
      onChanged()
    } catch (reason) {
      setError(message(reason))
    }
  }

  const healthy = sources.filter((source) => !source.error).length

  return (
    <>
      <PageHeader
        title="点播源"
        badges={<Badge variant="outline">{healthy}/{sources.length} 正常</Badge>}
        action={<Button variant="outline" size="sm" onClick={refresh}><RefreshCw />刷新全部</Button>}
      />
      <div className="space-y-4">
        <RecommendedShelf
          kind="vod"
          title="推荐源"
          onChanged={onChanged}
          setError={setError}
        />

        <SectionCard
          title="添加自定义配置"
        >
          <form onSubmit={add} className="grid gap-2 sm:grid-cols-[200px_1fr_auto]">
            <Input value={name} onChange={(event) => setName(event.target.value)} placeholder="名称（可选）" />
            <Input value={url} onChange={(event) => setUrl(event.target.value)} placeholder="http://.../tvbox.json" />
            <Button disabled={busy || !url.trim()}>{busy ? <LoaderCircle className="animate-spin" /> : <Plus />}添加</Button>
          </form>
        </SectionCard>

        <SectionCard title="已添加的源" badges={<Badge variant="outline">{orderedSources.length}</Badge>}>
          <div className="divide-y">
            {orderedSources.map((source) => (
              <div key={source.id} className={`flex flex-col gap-3 py-3 sm:flex-row sm:items-center ${source.parentId ? "pl-6" : ""}`}>
                <StatusDot className={source.error ? "bg-rose-400" : "bg-emerald-400"} />
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-medium">{source.name}</span>
                    <Badge variant={source.error ? "destructive" : "secondary"}>
                      {source.error ? "异常" : source.kind === "warehouse" ? "多仓" : source.parentId ? "子仓" : "单仓"}
                    </Badge>
                  </div>
                  <div className="mt-1 truncate text-xs text-muted-foreground">{source.url}</div>
                  {(source.error || source.searchError) && <div className="mt-1 break-words text-xs text-rose-300">{source.error || source.searchError}</div>}
                </div>
                <div className="shrink-0 text-xs text-muted-foreground">
                  {source.kind === "warehouse"
                    ? `${sources.filter((item) => item.parentId === source.id).length} 个子仓`
                    : `${source.siteCount} 站点 · ${source.liveCount} 直播 · ${source.latencyMs || "-"} ms`}
                </div>
                {!source.parentId && (
                  <Button variant="ghost" size="icon" title="删除" onClick={() => remove(source.id)}><Trash2 className="size-4" /></Button>
                )}
              </div>
            ))}
            {sources.length === 0 && <EmptyState icon={Library} title="还没有配置源" hint="用推荐源一键添加，或粘贴配置地址。" />}
          </div>
        </SectionCard>
      </div>
    </>
  )
}

function StorageView({ onChanged, setError }: { onChanged: () => void; setError: (value: string) => void }) {
  const [mounts, setMounts] = useState<StorageMount[]>([])
  const [name, setName] = useState("")
  const [type, setType] = useState("local")
  const [uri, setUri] = useState("")
  const [username, setUsername] = useState("")
  const [password, setPassword] = useState("")
  const [busy, setBusy] = useState(false)
  const load = useCallback(() => api.storageMounts().then(setMounts).catch((reason) => setError(message(reason))), [setError])
  useEffect(() => { load() }, [load])

  async function add(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    try {
      await api.addStorageMount({ name, type, uri, username, password })
      setName(""); setUri(""); setUsername(""); setPassword("")
      await load(); onChanged()
    } catch (reason) { setError(message(reason)) } finally { setBusy(false) }
  }

  async function remove(id: string) {
    try { await api.removeStorageMount(id); await load(); onChanged() } catch (reason) { setError(message(reason)) }
  }

  async function scan() {
    setBusy(true)
    try {
      await api.scanStorage()
      for (let attempt = 0; attempt < 120; attempt += 1) {
        await new Promise((resolve) => window.setTimeout(resolve, 1500))
        const status = await api.status()
        if (!status.storageScanning) break
      }
      await load(); onChanged()
    } catch (reason) { setError(message(reason)) } finally { setBusy(false) }
  }

  return (
    <>
      <PageHeader title="存储与片库" action={<Button variant="outline" disabled={busy} onClick={scan}>{busy ? <LoaderCircle className="animate-spin" /> : <RefreshCw />}扫描片库</Button>} />
      <form onSubmit={add} className="grid gap-2 border-y py-4 lg:grid-cols-[120px_160px_1fr_160px_160px_auto]">
        <Input value={name} onChange={(event) => setName(event.target.value)} placeholder="名称" />
        <select value={type} onChange={(event) => setType(event.target.value)} className="h-9 rounded-md border bg-background px-3 text-sm outline-none focus:ring-2 focus:ring-ring">
          <option value="local">本机 / U 盘</option><option value="webdav">WebDAV</option><option value="smb">SMB</option>
        </select>
        <Input value={uri} onChange={(event) => setUri(event.target.value)} placeholder={type === "local" ? "/storage/usb1/Movies" : type === "smb" ? "smb://192.168.8.1/Movies/" : "https://nas/dav/Movies/"} />
        <Input value={username} onChange={(event) => setUsername(event.target.value)} placeholder="用户名" autoComplete="username" />
        <Input value={password} onChange={(event) => setPassword(event.target.value)} placeholder="密码" type="password" autoComplete="current-password" />
        <Button disabled={busy || !name.trim() || !uri.trim()}>{busy ? <LoaderCircle className="animate-spin" /> : <Plus />}挂载</Button>
      </form>
      <section className="mt-5 divide-y rounded-md border">
        {mounts.map((mount) => (
          <div key={mount.id} className="flex flex-col gap-3 p-4 sm:flex-row sm:items-center">
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-2"><span className="font-medium">{mount.name}</span><Badge variant={mount.error ? "destructive" : "secondary"}>{mount.type.toUpperCase()}</Badge></div>
              <div className="mt-1 truncate text-xs text-muted-foreground">{mount.uri}</div>
              {mount.error && <div className="mt-1 text-xs text-destructive">{mount.error}</div>}
            </div>
            <div className="shrink-0 text-xs text-muted-foreground">{mount.fileCount} 个媒体文件</div>
            <Button variant="ghost" size="icon" title="删除" onClick={() => remove(mount.id)}><Trash2 /></Button>
          </div>
        ))}
        {mounts.length === 0 && <Empty icon={HardDrive} label="还没有挂载存储" compact />}
      </section>
    </>
  )
}

function DeviceView({ setError }: { setError: (value: string) => void }) {
  const [device, setDevice] = useState<Device | null>(null)
  const [diagnostics, setDiagnostics] = useState<Diagnostics | null>(null)
  useEffect(() => {
    let active = true
    let refreshing = false
    const refresh = () => {
      if (refreshing) return Promise.resolve()
      refreshing = true
      return Promise.all([api.device(), api.diagnostics()])
        .then(([nextDevice, nextDiagnostics]) => {
          if (active) {
            setDevice(nextDevice)
            setDiagnostics(nextDiagnostics)
          }
        })
        .catch((reason) => {
          if (active) setError(message(reason))
        })
        .finally(() => {
          refreshing = false
        })
    }
    void refresh()
    const timer = window.setInterval(refresh, 2500)
    return () => {
      active = false
      window.clearInterval(timer)
    }
  }, [setError])

  const [exporting, setExporting] = useState(false)
  const exportBundle = async () => {
    setExporting(true)
    try {
      downloadText(diagnosticFileName(), await api.exportDiagnostics("ERROR"))
    } catch (reason) {
      setError(message(reason))
    } finally {
      setExporting(false)
    }
  }

  const rows = useMemo(() => device ? [
    ["系统", `${device.manufacturer} ${device.model} · Android ${device.androidVersion} / API ${device.sdk}`],
    ["架构", device.primaryAbi],
    ["运行内存", formatBytes(device.totalMemoryBytes)],
    ["应用内存上限", formatBytes(device.appMemoryBytes)],
    ["显示输出", `${device.displayWidth} × ${device.displayHeight} @ ${device.refreshRate.toFixed(1)} Hz`],
    ["H.264", device.hasHardwareAvcDecoder ? `硬解 · ${device.preferredAvcDecoder}` : "未发现硬件解码器"],
  ] : [], [device])

  const airPlay = diagnostics?.airPlay
  const failedStages = diagnostics?.stages?.filter((stage) => stage.result === "failed") ?? []

  return (
    <>
      <PageHeader
        title="设备能力"
        badges={
          <Badge variant={device?.hasHardwareAvcDecoder ? "secondary" : "destructive"}>
            {device?.hasHardwareAvcDecoder ? "支持 1080p 硬解" : "未检测到硬解"}
          </Badge>
        }
        action={
          <Button size="sm" disabled={exporting} onClick={exportBundle}>
            {exporting ? <LoaderCircle className="animate-spin" /> : <Download />}导出诊断包
          </Button>
        }
      />

      <div className="grid gap-4 xl:grid-cols-2">
        <SectionCard title="设备信息">
          <div className="divide-y">
            {rows.map(([label, value]) => (
              <div key={label} className="grid gap-1 py-2.5 first:pt-0 last:pb-0 sm:grid-cols-[120px_1fr]">
                <span className="text-sm text-muted-foreground">{label}</span>
                <span className="break-words text-sm font-medium">{value}</span>
              </div>
            ))}
            {device?.warnings.map((warning) => (
              <div key={warning} className="flex items-start gap-2 py-2.5 text-sm text-rose-300">
                <CircleAlert className="mt-0.5 size-4 shrink-0" />{warning}
              </div>
            ))}
          </div>
          {device && device.avcDecoders.length > 0 && (
            <div className="mt-3 border-t pt-3">
              <div className="mb-2 text-xs text-muted-foreground">H.264 解码器（按优先级）</div>
              <div className="flex flex-wrap gap-1.5">
                {device.avcDecoders.map((codec) => <Badge key={codec} variant="outline">{codec}</Badge>)}
              </div>
            </div>
          )}
        </SectionCard>

        <SectionCard
          title="投屏诊断"
          badges={<Badge variant={airPlay?.sessionActive ? "secondary" : "outline"}>{airPlay?.sessionActive ? "会话中" : airPlay?.state || "未启动"}</Badge>}
        >
          <div className="grid gap-3 sm:grid-cols-2">
            <DiagnosticFact label="接收视频" value={`${airPlay?.videoFrames ?? 0} 包 · ${airPlay?.videoKeyFrames ?? 0} IDR`} />
            <DiagnosticFact label="解码输入 / 输出" value={`${airPlay?.decoderInputs ?? 0} / ${airPlay?.decoderOutputs ?? 0}`} />
            <DiagnosticFact label="实际解码器" value={airPlay?.decoderName || "尚未创建"} />
            <DiagnosticFact label="解码模式" value={airPlay?.decoderSoftwareFallback ? "软件回退" : "系统硬解优先"} />
          </div>
          {airPlay?.identity && (
            <div className="mt-3 break-all rounded-lg border bg-background/60 p-3 font-mono text-xs text-muted-foreground">
              原生 /info 身份：{airPlay.identity}
            </div>
          )}
          {airPlay?.error && (
            <div className="mt-3 flex items-start gap-2 rounded-lg border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive">
              <CircleAlert className="mt-0.5 size-4 shrink-0" />{airPlay.error}
            </div>
          )}
        </SectionCard>
      </div>

      <SectionCard
        className="mt-4"
        title="阶段诊断"
        badges={<Badge variant={failedStages.length > 0 ? "destructive" : "outline"}>{diagnostics?.stages?.length ?? 0} 条{failedStages.length > 0 ? ` · ${failedStages.length} 失败` : ""}</Badge>}
        action={<Button variant="ghost" size="sm" onClick={() => { void api.diagnostics().then(setDiagnostics).catch((reason) => setError(message(reason))) }}><RefreshCw className="size-4" />刷新</Button>}
      >
        <div className="space-y-1.5">
          {diagnostics?.stages?.slice(0, 14).map((stage) => (
            <div key={`${stage.generation}-${stage.scope}-${stage.subject}`}
              className="grid gap-1 rounded-lg border bg-background/40 px-3 py-2 text-sm sm:grid-cols-[190px_1fr_auto]">
              <span className="truncate font-medium">{stage.scope} · {stage.subject}</span>
              <span className="min-w-0 break-words">
                <span className="font-mono text-xs">{stage.stage}</span>
                {stage.detail && <span className="ml-2 text-muted-foreground">{stage.detail}</span>}
                {stage.rootCauseClass && <span className="ml-2 text-xs text-muted-foreground">{stage.rootCauseClass}</span>}
              </span>
              <span className={`text-xs ${stage.result === "failed" ? "text-rose-300" : "text-muted-foreground"}`}>
                {stage.result === "failed" ? `失败${stage.errorCode ? ` · ${stage.errorCode}` : ""}` : stage.result === "ok" ? `${stage.elapsedMs} ms` : "进行中"}
              </span>
            </div>
          ))}
          {(!diagnostics?.stages || diagnostics.stages.length === 0) && (
            <EmptyState icon={Gauge} title="还没有阶段记录"  />
          )}
        </div>
      </SectionCard>

      <div className="mt-4 grid gap-4 xl:grid-cols-2">
        <SectionCard title="源与启动诊断">
          <div className="space-y-2 text-sm">
            {diagnostics?.httpStack?.degraded && (
              <div className="flex items-start gap-2 rounded-lg border border-destructive/40 bg-destructive/10 p-3 text-destructive">
                <CircleAlert className="mt-0.5 size-4 shrink-0" />
                旧版 TLS 初始化失败，已回退平台 TLS：{diagnostics.httpStack.initError}
              </div>
            )}
            {diagnostics?.sources.filter((source) => source.error || source.searchError).map((source) => (
              <div key={source.id} className="grid gap-1 border-b py-2 last:border-b-0 sm:grid-cols-[150px_1fr]">
                <span className="font-medium">{source.name}</span>
                <span className="break-words text-rose-300">{source.error || source.searchError}</span>
              </div>
            ))}
            {diagnostics?.homeErrors.map((failure) => (
              <div key={`${failure.sourceId}-${failure.siteKey}`} className="grid gap-1 border-b py-2 last:border-b-0 sm:grid-cols-[150px_1fr]">
                <span className="font-medium">{failure.siteName}</span>
                <span className="break-words text-rose-300">首页：{failure.error}</span>
              </div>
            ))}
            {diagnostics && diagnostics.sources.every((source) => !source.error && !source.searchError) && diagnostics.homeErrors.length === 0 && (
              <div className="text-muted-foreground">当前没有源刷新或首页加载错误。</div>
            )}
          </div>
        </SectionCard>

        <SectionCard
          className="mt-4"
          title="上次运行"
          badges={diagnostics?.lastRun
            ? <Badge variant={diagnostics.lastRun.endedCleanly ? "outline" : "destructive"}>
                {diagnostics.lastRun.endedCleanly ? "正常退出" : "被外部结束"}
              </Badge>
            : <Badge variant="outline">无记录</Badge>}
        >
          {!diagnostics?.lastRun && <div className="text-sm text-muted-foreground">还没有可用的运行记录。</div>}
          {diagnostics?.lastRun && (
            <div className="space-y-3">
              <div className="grid gap-2 sm:grid-cols-3">
                <DiagnosticFact label="运行时长" value={formatDuration(diagnostics.lastRun.durationMs)} />
                <DiagnosticFact label="内存峰值" value={`${diagnostics.lastRun.peakHeapPercent}% · 最后 ${diagnostics.lastRun.lastHeapPercent}%`} />
                <DiagnosticFact label="最后阶段" value={diagnostics.lastRun.lastStage || "启动阶段"} />
              </div>
              {diagnostics.lastRun.samples.length > 1 && (
                <div className="rounded-lg border bg-background/40 p-3">
                  <div className="mb-2 text-xs text-muted-foreground">内存占用（{diagnostics.lastRun.samples.length} 次采样，每 30 秒一次）</div>
                  <div className="flex h-16 items-end gap-1">
                    {diagnostics.lastRun.samples.map((sample) => (
                      <div key={sample.at}
                        className={`flex-1 rounded-t ${sample.heapPercent >= 80 ? "bg-rose-400/70" : sample.heapPercent >= 60 ? "bg-amber-400/70" : "bg-primary/60"}`}
                        style={{ height: `${Math.max(8, Math.round(sample.heapPercent * 100 / Math.max(10, diagnostics.lastRun!.peakHeapPercent)))}%` }}
                        title={`${new Date(sample.at).toLocaleTimeString()} · 堆 ${sample.heapPercent}% · 系统可用 ${formatBytes(sample.availableMemoryBytes)}${sample.stage ? ` · ${sample.stage}` : ""}`} />
                    ))}
                  </div>
                </div>
              )}
              {!diagnostics.lastRun.endedCleanly && (
                <p className="text-xs leading-5 text-muted-foreground">
                  没有走正常退出流程，说明进程是被系统或系统策略结束的（内存、后台限制或强制停止）。若内存峰值接近 100%，
                  可在下次投屏时留意设备页的实时内存与解码计数。
                </p>
              )}
            </div>
          )}
        </SectionCard>

        <SectionCard
          className="mt-4"
          title="本机不支持的站点"
          badges={<Badge variant={(diagnostics?.siteIssues?.length ?? 0) > 0 ? "destructive" : "outline"}>
            {diagnostics?.siteIssues?.length ?? 0} 个
          </Badge>}
        >
          {(diagnostics?.siteIssues?.length ?? 0) === 0
            ? <div className="text-sm text-muted-foreground">没有记录到不兼容站点。</div>
            : (
              <div className="space-y-1.5">
                {diagnostics?.siteIssues?.map((issue) => (
                  <div key={issue.siteKey} className="grid gap-1 rounded-lg border bg-background/40 px-3 py-2 sm:grid-cols-[180px_1fr]">
                    <span className="truncate text-sm font-medium">{issue.siteName}</span>
                    <span className="text-xs leading-5 text-muted-foreground">
                      {issue.reason}{issue.permanent ? "（本次运行不再重试）" : ""}
                    </span>
                  </div>
                ))}
              </div>
            )}
        </SectionCard>

        <SectionCard title="上次 Java 闪退记录">
          {diagnostics?.javaCrash
            ? <pre className="max-h-72 overflow-auto whitespace-pre-wrap rounded-lg border bg-background/60 p-3 text-xs leading-5 text-rose-300">{diagnostics.javaCrash}</pre>
            : <div className="text-sm text-muted-foreground">没有保存的 Java 闪退记录。</div>}
        </SectionCard>
      </div>
    </>
  )
}

function DiagnosticFact({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0 rounded-lg border bg-background/40 px-3 py-2">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="mt-1 break-words text-sm font-medium">{value}</div>
    </div>
  )
}

const logFilters: { value: "ALL" | LogLevel; label: string }[] = [
  { value: "ALL", label: "全部" },
  { value: "DEBUG", label: "调试" },
  { value: "INFO", label: "信息" },
  { value: "WARN", label: "警告" },
  { value: "ERROR", label: "错误" },
]

function LogView({ setError }: { setError: (value: string) => void }) {
  const [entries, setEntries] = useState<LogEntry[]>([])
  const [filter, setFilter] = useState<"ALL" | LogLevel>("ALL")
  const [refreshing, setRefreshing] = useState(false)
  const [clearing, setClearing] = useState(false)

  const refresh = useCallback(async () => {
    setRefreshing(true)
    try {
      setEntries(await api.logs())
    } catch (reason) {
      setError(message(reason))
    } finally {
      setRefreshing(false)
    }
  }, [setError])

  useEffect(() => {
    void refresh()
    const timer = window.setInterval(refresh, 5000)
    return () => window.clearInterval(timer)
  }, [refresh])

  const [exporting, setExporting] = useState(false)
  const visible = useMemo(() => (filter === "ALL" ? entries : entries.filter((entry) => entry.level === filter)).slice().reverse(), [entries, filter])
  const counts = useMemo(() => ({
    ERROR: entries.filter((entry) => entry.level === "ERROR").length,
    WARN: entries.filter((entry) => entry.level === "WARN").length,
  }), [entries])

  const clear = async () => {
    setClearing(true)
    try {
      await api.clearLogs()
      setEntries([])
    } catch (reason) {
      setError(message(reason))
    } finally {
      setClearing(false)
    }
  }

  // Export always includes the log filter currently shown: "only errors" is the common case when
  // reporting a problem, and the bundle carries device state either way.
  const exportBundle = async () => {
    setExporting(true)
    try {
      const text = await api.exportDiagnostics(filter === "ALL" ? undefined : filter)
      downloadText(diagnosticFileName(), text)
    } catch (reason) {
      setError(message(reason))
    } finally {
      setExporting(false)
    }
  }

  return (
    <>
      <PageHeader
        title="日志"
        badges={<Badge variant="outline">{visible.length} / {entries.length} 条</Badge>}
        action={
          <>
            <Button variant="outline" size="sm" disabled={refreshing} onClick={refresh}>
              <RefreshCw className={refreshing ? "animate-spin" : ""} />刷新
            </Button>
            <Button size="sm" disabled={exporting} onClick={exportBundle} title="导出日志与设备状态为一个文本文件">
              {exporting ? <LoaderCircle className="animate-spin" /> : <Download />}
              {filter === "ALL" ? "导出诊断包" : `导出${filter === "ERROR" ? "错误" : "警告"}`}
            </Button>
            <Button variant="outline" size="sm" disabled={clearing || entries.length === 0} onClick={clear}>
              {clearing ? <LoaderCircle className="animate-spin" /> : <Trash2 />}清空
            </Button>
          </>
        }
      />
      <div className="mb-4 flex flex-wrap items-center gap-2">
        {logFilters.map((item) => (
          <button key={item.value} type="button" onClick={() => setFilter(item.value)}
            className={`rounded-full border px-3 py-1 text-sm outline-none transition focus-visible:ring-2 focus-visible:ring-ring ${filter === item.value ? "border-foreground/30 bg-accent text-foreground" : "text-muted-foreground hover:bg-accent/50"}`}>
            {item.label}
          </button>
        ))}
        {(counts.ERROR > 0 || counts.WARN > 0) && (
          <span className="flex items-center gap-3 text-xs text-muted-foreground">
            {counts.ERROR > 0 && <span className="flex items-center gap-1"><span className="size-2 rounded-full bg-rose-400" />{counts.ERROR} 个错误</span>}
            {counts.WARN > 0 && <span className="flex items-center gap-1"><span className="size-2 rounded-full bg-amber-400" />{counts.WARN} 个警告</span>}
          </span>
        )}
      </div>

      <SectionCard>
        <div className="divide-y">
          {visible.map((entry, index) => (
            <article key={`${entry.timestamp}-${index}`} className="py-3 first:pt-0 last:pb-0">
              <div className="mb-1.5 flex flex-wrap items-center gap-2">
                <span className={`size-2 rounded-full ${levelDot(entry.level)}`} />
                <span className="text-xs text-muted-foreground">{clockOf(entry.timestamp)}</span>
                <span className="text-xs text-muted-foreground">{entry.component}</span>
                <Badge variant={entry.level === "ERROR" ? "destructive" : entry.level === "WARN" ? "outline" : "secondary"}>{logLevelLabel(entry.level)}</Badge>
              </div>
              <div className="break-words text-sm leading-6">{entry.message}</div>
              {entry.repeats && entry.repeats > 1 && (
                <Badge variant="outline" className="mt-1">重复 {entry.repeats} 次</Badge>
              )}
              {entry.trace && (
                <details className="mt-2">
                  <summary className="cursor-pointer text-xs text-muted-foreground">调用栈</summary>
                  <pre className="mt-2 max-h-72 overflow-auto whitespace-pre-wrap rounded-md border bg-background/60 p-3 text-xs leading-5 text-muted-foreground">{entry.trace}</pre>
                </details>
              )}
            </article>
          ))}
          {visible.length === 0 && (
            <EmptyState icon={FileWarning} title="当前级别暂无日志"  />
          )}
        </div>
      </SectionCard>
    </>
  )
}

function logLevelLabel(level: LogLevel) {
  if (level === "DEBUG") return "调试"
  if (level === "INFO") return "信息"
  if (level === "WARN") return "警告"
  return "错误"
}

function sourceLabel(source: Source, allSources: Source[]) {
  const parent = allSources.find((item) => item.id === source.parentId)
  const name = parent ? `${parent.name} / ${source.name}` : source.name
  return source.error || source.searchError ? `${name}（异常）` : `${name} · ${source.latencyMs || "-"} ms`
}

function orderSourceTree(sources: Source[]) {
  const ordered: Source[] = []
  const roots = sources.filter((source) => !source.parentId)
  roots.forEach((root) => {
    ordered.push(root)
    ordered.push(...sources.filter((source) => source.parentId === root.id))
  })
  ordered.push(...sources.filter((source) => source.parentId && !sources.some((parent) => parent.id === source.parentId)))
  return ordered
}

function Empty({ icon: Icon, label, compact = false }: { icon: typeof Gauge; label: string; compact?: boolean }) {
  return <div className={`grid place-items-center ${compact ? "min-h-32" : "min-h-64 border-y"}`}><div className="text-center text-muted-foreground"><Icon className="mx-auto mb-3 size-8" /><div className="text-sm">{label}</div></div></div>
}

function message(reason: unknown) {
  return reason instanceof Error ? reason.message : String(reason)
}
