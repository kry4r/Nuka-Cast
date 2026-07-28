import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react"
import {
  Airplay,
  Cast,
  CircleAlert,
  Film,
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
import { api, AUTH_EXPIRED_EVENT, type Device, type Diagnostics, type EpgSchedule, type LiveCatalog, type LiveSource, type MediaDetail, type Player, type SearchItem, type SearchResponse, type Site, type Source, type Status, type StorageMount } from "@/lib/api"
import { formatBytes } from "@/lib/utils"
import { rankLeafSources, selectPreferredSource } from "@/lib/source-ranking"
import { createLatestRequestGate } from "@/lib/latest-request"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"

type View = "overview" | "search" | "live" | "sources" | "storage" | "device"

const nav: { id: View; label: string; icon: typeof Gauge }[] = [
  { id: "overview", label: "总览", icon: Gauge },
  { id: "search", label: "影视", icon: Film },
  { id: "live", label: "直播", icon: Radio },
  { id: "sources", label: "源管理", icon: Library },
  { id: "storage", label: "存储", icon: HardDrive },
  { id: "device", label: "设备", icon: MonitorCog },
]

export default function App() {
  const [paired, setPaired] = useState(api.hasToken())
  const [status, setStatus] = useState<Status | null>(null)
  const [view, setView] = useState<View>("overview")
  const [error, setError] = useState("")

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

  useEffect(() => {
    const expired = () => {
      setPaired(false)
      setError("")
    }
    window.addEventListener(AUTH_EXPIRED_EVENT, expired)
    return () => window.removeEventListener(AUTH_EXPIRED_EVENT, expired)
  }, [])

  if (!paired) return <Pairing status={status} onPaired={() => setPaired(true)} />

  return (
    <div className="min-h-screen lg:grid lg:grid-cols-[220px_1fr]">
      <aside className="border-b bg-card lg:fixed lg:inset-y-0 lg:w-[220px] lg:border-b-0 lg:border-r">
        <div className="flex h-16 items-center gap-3 px-4 lg:h-20">
          <div className="grid size-9 place-items-center rounded-md border bg-background text-primary"><Cast className="size-5" /></div>
          <div className="min-w-0">
            <div className="truncate text-base font-semibold">NukaCast</div>
            <div className="truncate text-xs text-muted-foreground">{status?.message || "连接中"}</div>
          </div>
        </div>
        <nav className="flex gap-1 overflow-x-auto px-2 pb-3 lg:block lg:space-y-1 lg:px-3">
          {nav.map((item) => {
            const Icon = item.icon
            return (
              <Button key={item.id} variant={view === item.id ? "secondary" : "ghost"}
                className="shrink-0 justify-start lg:w-full" onClick={() => setView(item.id)}>
                <Icon />{item.label}
              </Button>
            )
          })}
        </nav>
        <div className="hidden px-4 lg:absolute lg:bottom-5 lg:block lg:w-full">
          <div className="flex items-center gap-2 text-xs text-muted-foreground">
            <span className={`size-2 rounded-full ${status?.serviceState === "ready" ? "bg-foreground" : "bg-destructive"}`} />
            {status?.webAddress || "局域网服务"}
          </div>
        </div>
      </aside>

      <main className="min-w-0 px-4 py-5 sm:px-6 lg:col-start-2 lg:px-8 lg:py-7">
        <div className="mx-auto max-w-[1600px]">
        {error && (
          <div className="mb-4 flex items-center gap-2 rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm text-destructive">
            <CircleAlert className="size-4" />{error}
            <Button variant="ghost" size="sm" className="ml-auto" onClick={() => setError("")}>关闭</Button>
          </div>
        )}
        {view === "overview" && <Overview status={status} onStatusChanged={refreshStatus} setError={setError} />}
        {view === "search" && <SearchView sourceVersion={status?.stateVersion ?? 0} setError={setError} />}
        {view === "live" && <LiveView contentVersion={status?.contentVersion ?? 0} setError={setError} />}
        {view === "sources" && <SourcesView contentVersion={status?.contentVersion ?? 0} onChanged={refreshStatus} setError={setError} />}
        {view === "storage" && <StorageView onChanged={refreshStatus} setError={setError} />}
        {view === "device" && <DeviceView setError={setError} />}
        </div>
      </main>
    </div>
  )
}

function Pairing({ status, onPaired }: { status: Status | null; onPaired: () => void }) {
  const [code, setCode] = useState("")
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError("")
    try {
      await api.pair(code)
      onPaired()
    } catch (reason) {
      setError(message(reason))
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="grid min-h-screen place-items-center px-4">
      <form onSubmit={submit} className="panel w-full max-w-sm p-5">
        <div className="mb-5 flex items-center gap-3">
          <div className="grid size-10 place-items-center rounded-md border bg-background text-primary"><Cast /></div>
          <div><h1 className="text-lg font-semibold">NukaCast</h1><p className="text-sm text-muted-foreground">{status?.message || "局域网控制"}</p></div>
        </div>
        <label htmlFor="pair-code" className="mb-2 block text-sm font-medium">电视配对码</label>
        <Input id="pair-code" inputMode="numeric" autoComplete="one-time-code" maxLength={6}
          value={code} onChange={(event) => setCode(event.target.value.replace(/\D/g, ""))}
          className="h-12 text-center text-xl" autoFocus />
        {error && <p className="mt-2 text-sm text-destructive">{error}</p>}
        <Button className="mt-4 h-11 w-full" disabled={busy || code.length !== 6}>
          {busy && <LoaderCircle className="animate-spin" />}配对
        </Button>
      </form>
    </main>
  )
}

function PageHeader({ title, action }: { title: string; action?: React.ReactNode }) {
  return <header className="mb-5 flex min-h-10 items-center justify-between gap-3"><h1 className="text-xl font-semibold sm:text-2xl">{title}</h1>{action}</header>
}

function Overview({ status, onStatusChanged, setError }: { status: Status | null; onStatusChanged: () => Promise<void>; setError: (value: string) => void }) {
  const [player, setPlayer] = useState<Player | null>(null)
  const [disconnecting, setDisconnecting] = useState(false)

  const refresh = useCallback(() => api.player().then(setPlayer).catch((reason) => setError(message(reason))), [setError])
  useEffect(() => {
    refresh()
    const timer = window.setInterval(refresh, 2000)
    return () => window.clearInterval(timer)
  }, [refresh])

  const control = async (action: string, offsetMs?: number) => {
    try { setPlayer(await api.control({ action, offsetMs })) } catch (reason) { setError(message(reason)) }
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

  return (
    <>
      <PageHeader title="控制中心" action={<Badge variant={status?.serviceState === "ready" ? "secondary" : "destructive"}>{status?.message || "连接中"}</Badge>} />
      <section className="panel flex min-h-40 flex-col justify-between gap-6 p-5 sm:flex-row sm:items-center sm:p-6">
        <div className="flex min-w-0 items-center gap-4">
          <div className="grid size-12 shrink-0 place-items-center rounded-md bg-primary text-primary-foreground"><Airplay className="size-6" /></div>
          <div className="min-w-0">
            <div className="text-xs font-medium text-muted-foreground">AIRPLAY</div>
            <h2 className="mt-1 truncate text-2xl font-semibold">{status?.airPlay?.sessionActive ? "正在镜像" : status?.airPlayName || "NukaCast"}</h2>
            {status?.airPlay?.error && <div className="mt-2 text-sm text-destructive">{status.airPlay.error}</div>}
          </div>
        </div>
        {status?.airPlay?.sessionActive
          ? <Button variant="outline" disabled={disconnecting} onClick={disconnectAirPlay}>{disconnecting ? <LoaderCircle className="animate-spin" /> : <Square />}退出投屏</Button>
          : <Badge variant="outline">等待设备</Badge>}
      </section>

      <section className="mt-4 grid divide-y rounded-md border sm:grid-cols-3 sm:divide-x sm:divide-y-0">
        <OverviewFact icon={Wifi} label="控制地址" value={status?.webAddress?.replace(/^https?:\/\//, "") || "-"} />
        <OverviewFact icon={Server} label="片源" value={`${status?.sourceCount ?? 0} 个配置 · ${status?.siteCount ?? 0} 个站点`} />
        <OverviewFact icon={Film} label="播放状态" value={status?.activeMedia || "空闲"} />
      </section>

      <section className="mt-6 border-t pt-5">
        <div className="mb-3 flex items-center justify-between"><h2 className="section-title">播放器</h2><Badge variant="outline">{player?.state || "idle"}</Badge></div>
        <div className="panel flex min-h-28 flex-col justify-between gap-4 p-4 sm:flex-row sm:items-center">
          <div className="min-w-0">
            <div className="truncate font-medium">{player?.title || "未播放"}</div>
            <div className="mt-1 truncate text-xs text-muted-foreground">{player?.url || "-"}</div>
          </div>
          <div className="flex shrink-0 items-center gap-2">
            <Button variant="outline" size="icon" title="后退 10 秒" onClick={() => control("seek", -10000)}><SkipBack /></Button>
            <Button size="icon" title={player?.playing ? "暂停" : "播放"} onClick={() => control("toggle")}>
              {player?.playing ? <Pause /> : <Play />}
            </Button>
            <Button variant="outline" size="icon" title="前进 30 秒" onClick={() => control("seek", 30000)}><SkipForward /></Button>
            <Button variant="outline" size="icon" title="停止" onClick={() => control("stop")}><Square /></Button>
          </div>
        </div>
      </section>
    </>
  )
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
  const [playing, setPlaying] = useState("")
  const [posterFailed, setPosterFailed] = useState(false)

  async function play(flag: string, episodeId: string, episodeName: string) {
    setPlaying(`${flag}-${episodeId}`)
    try {
      await api.playItem({
        sourceId: detail.sourceId,
        siteKey: detail.siteKey,
        siteName: detail.siteName,
        vodId: detail.vodId,
        name: detail.name,
        poster: detail.poster,
        remarks: detail.remarks,
        year: detail.year,
        typeName: detail.typeName,
        flag,
        episodeId,
        episodeName,
        title: `${detail.name} · ${episodeName}`,
      })
    } catch (reason) {
      setError(message(reason))
    } finally {
      setPlaying("")
    }
  }

  return (
    <div className="fixed inset-0 z-50 overflow-y-auto bg-background/90 p-3 backdrop-blur-sm sm:p-6" role="dialog" aria-modal="true" aria-label={detail.name}>
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
  const requestGate = useMemo(() => createLatestRequestGate(), [])

  useEffect(() => {
    api.liveSources().then((items) => {
      setSources(items)
      if (!items.length) {
        setSelected(""); setCatalog(null); setSchedule(null)
        return
      }
      const next = items.some((source) => source.id === selected) ? selected : items[0].id
      void load(next)
    }).catch((reason) => setError(message(reason)))
  }, [contentVersion, setError])

  async function load(id: string) {
    const request = requestGate.begin()
    setSelected(id); setBusy(true); setCatalog(null); setSchedule(null)
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
    setPlaying(channelId); setSchedule(null)
    try { await api.playLive(selected, channelId) } catch (reason) { setError(message(reason)); setPlaying(""); return }
    setPlaying(""); setEpgBusy(true)
    try { setSchedule(await api.epg(selected, channelId)) } catch { setSchedule(null) } finally { setEpgBusy(false) }
  }

  const channelCount = catalog?.groups.reduce((sum, group) => sum + group.channels.length, 0) ?? 0
  return (
    <>
      <PageHeader title="直播" action={<Badge variant="outline">{channelCount} 个频道</Badge>} />
      <div className="flex gap-2 overflow-x-auto border-y py-3">
        {sources.map((source) => <Button key={source.id} variant={selected === source.id ? "secondary" : "ghost"} onClick={() => load(source.id)}><Radio />{source.name}</Button>)}
      </div>
      {busy && <div className="grid min-h-64 place-items-center"><LoaderCircle className="size-7 animate-spin text-primary" /></div>}
      {!busy && sources.length === 0 && <Empty icon={Radio} label="当前配置没有直播源" />}
      {epgBusy && <div className="mt-5 flex h-16 items-center justify-center border-y"><LoaderCircle className="size-5 animate-spin text-primary" /></div>}
      {schedule && schedule.programs.length > 0 && <section className="mt-5 border-y py-4"><div className="mb-3 flex items-center justify-between gap-3"><h2 className="section-title truncate">{schedule.channel} · 节目单</h2><span className="text-xs text-muted-foreground">{schedule.date}</span></div><div className="flex gap-2 overflow-x-auto pb-1">{schedule.programs.map((program, index) => <div key={`${program.start}-${index}`} className="w-44 shrink-0 rounded-md border bg-card px-3 py-2"><div className="truncate text-sm font-medium">{program.title}</div><div className="mt-1 text-xs text-muted-foreground">{program.start} - {program.end}</div></div>)}</div></section>}
      {!busy && catalog && <div className="mt-5 space-y-7">{catalog.groups.map((group) => (
        <section key={group.name}>
          <div className="mb-3 flex items-center gap-2"><h2 className="section-title">{group.name}</h2><Badge variant="secondary">{group.channels.length}</Badge></div>
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-4 2xl:grid-cols-6">
            {group.channels.map((channel) => <button key={channel.id} type="button" onClick={() => play(channel.id)} disabled={playing === channel.id} className="flex h-16 min-w-0 items-center gap-3 rounded-md border bg-card px-3 text-left outline-none hover:bg-accent focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-60">{channel.logo ? <img src={channel.logo} alt="" className="size-9 shrink-0 object-contain" /> : <span className="grid size-9 shrink-0 place-items-center rounded-md bg-muted text-muted-foreground"><Tv className="size-5" /></span>}<span className="min-w-0 flex-1 truncate text-sm font-medium">{channel.name}</span>{playing === channel.id && <LoaderCircle className="size-4 shrink-0 animate-spin" />}</button>)}
          </div>
        </section>
      ))}</div>}
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
      setName(""); setUrl(""); await load(); onChanged()
    } catch (reason) { await load(); onChanged(); setError(message(reason)) } finally { setBusy(false) }
  }

  async function remove(id: string) {
    try { await api.removeSource(id); await load(); onChanged() } catch (reason) { setError(message(reason)) }
  }

  async function refresh() {
    try { await api.refreshSources(); onChanged() } catch (reason) { setError(message(reason)) }
  }

  return (
    <>
      <PageHeader title="源管理" action={<Button variant="outline" onClick={refresh}><RefreshCw />刷新全部</Button>} />
      <form onSubmit={add} className="grid gap-2 border-y py-4 sm:grid-cols-[180px_1fr_auto]">
        <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="名称" />
        <Input value={url} onChange={(e) => setUrl(e.target.value)} placeholder="http://.../tvbox.json" />
        <Button disabled={busy || !url.trim()}>{busy ? <LoaderCircle className="animate-spin" /> : <Plus />}添加</Button>
      </form>
      <section className="mt-5 divide-y rounded-md border">
        {orderedSources.map((source) => (
          <div key={source.id} className={`flex flex-col gap-3 p-4 sm:flex-row sm:items-center ${source.parentId ? "bg-muted/20 pl-8" : ""}`}>
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-center gap-2"><span className="font-medium">{source.name}</span><Badge variant={source.error ? "destructive" : "secondary"}>{source.error ? "异常" : source.kind === "warehouse" ? "多仓" : source.parentId ? "子仓" : "单仓"}</Badge></div>
              <div className="mt-1 truncate text-xs text-muted-foreground">{source.url}</div>
              {(source.error || source.searchError) && <div className="mt-1 break-words text-xs text-destructive">{source.error || source.searchError}</div>}
            </div>
            <div className="shrink-0 text-xs text-muted-foreground">{source.kind === "warehouse" ? `${sources.filter((item) => item.parentId === source.id).length} 个子仓` : `${source.siteCount} 站点 · ${source.liveCount} 直播 · ${source.latencyMs || "-"} ms`}</div>
            {!source.parentId && <Button variant="ghost" size="icon" title="删除" onClick={() => remove(source.id)}><Trash2 /></Button>}
          </div>
        ))}
        {sources.length === 0 && <Empty icon={Library} label="还没有配置源" compact />}
      </section>
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
        if (active) { setDevice(nextDevice); setDiagnostics(nextDiagnostics) }
      })
      .catch((reason) => { if (active) setError(message(reason)) })
      .finally(() => { refreshing = false })
    }
    void refresh()
    const timer = window.setInterval(refresh, 2500)
    return () => { active = false; window.clearInterval(timer) }
  }, [setError])
  const rows = useMemo(() => device ? [
    ["系统", `${device.manufacturer} ${device.model} · Android ${device.androidVersion} / API ${device.sdk}`],
    ["架构", device.primaryAbi],
    ["运行内存", formatBytes(device.totalMemoryBytes)],
    ["应用内存上限", formatBytes(device.appMemoryBytes)],
    ["显示输出", `${device.displayWidth} × ${device.displayHeight} @ ${device.refreshRate.toFixed(1)} Hz`],
    ["H.264", device.hasHardwareAvcDecoder ? `硬解 · ${device.preferredAvcDecoder}` : "未发现硬件解码器"],
  ] : [], [device])

  return (
    <>
      <PageHeader title="设备能力" action={<Badge variant={device?.hasHardwareAvcDecoder ? "secondary" : "destructive"}>{device?.hasHardwareAvcDecoder ? "1080p 候选" : "待检测"}</Badge>} />
      <section className="divide-y rounded-md border">
        {rows.map(([label, value]) => <div key={label} className="grid gap-1 px-4 py-3 sm:grid-cols-[150px_1fr]"><div className="text-sm text-muted-foreground">{label}</div><div className="break-words text-sm font-medium">{value}</div></div>)}
      </section>
      {device?.warnings.length ? <section className="mt-5"><h2 className="section-title mb-3">检测提示</h2>{device.warnings.map((warning) => <div key={warning} className="mb-2 flex items-center gap-2 text-sm text-destructive"><CircleAlert className="size-4" />{warning}</div>)}</section> : null}
      <section className="mt-5"><h2 className="section-title mb-3">H.264 解码器</h2><div className="flex flex-wrap gap-2">{device?.avcDecoders.map((codec) => <Badge key={codec} variant="outline">{codec}</Badge>)}</div></section>
      <section className="mt-7 border-t pt-5">
        <div className="mb-3 flex items-center justify-between gap-3"><h2 className="section-title">投屏诊断</h2><Badge variant={diagnostics?.airPlay.decoderOutputs ? "secondary" : "outline"}>{diagnostics?.airPlay.sessionActive ? "会话中" : diagnostics?.airPlay.state || "未启动"}</Badge></div>
        <div className="grid divide-y rounded-md border sm:grid-cols-2 sm:divide-x sm:divide-y-0 lg:grid-cols-4">
          <DiagnosticFact label="接收视频" value={`${diagnostics?.airPlay.videoFrames ?? 0} 包 · ${diagnostics?.airPlay.videoKeyFrames ?? 0} IDR`} />
          <DiagnosticFact label="解码输入 / 输出" value={`${diagnostics?.airPlay.decoderInputs ?? 0} / ${diagnostics?.airPlay.decoderOutputs ?? 0}`} />
          <DiagnosticFact label="实际解码器" value={diagnostics?.airPlay.decoderName || "尚未创建"} />
          <DiagnosticFact label="解码模式" value={diagnostics?.airPlay.decoderSoftwareFallback ? "Google 软件回退" : "系统硬解优先"} />
        </div>
        {diagnostics?.airPlay.error && <div className="mt-3 break-words text-sm text-destructive">AirPlay：{diagnostics.airPlay.error}</div>}
      </section>
      <section className="mt-7 border-t pt-5">
        <h2 className="section-title mb-3">源与启动诊断</h2>
        <div className="space-y-2">
          {diagnostics?.sources.filter((source) => source.error || source.searchError).map((source) => <div key={source.id} className="grid gap-1 border-b py-2 text-sm sm:grid-cols-[180px_1fr]"><span className="font-medium">{source.name}</span><span className="break-words text-destructive">{source.error || source.searchError}</span></div>)}
          {diagnostics?.homeErrors.map((failure) => <div key={`${failure.sourceId}-${failure.siteKey}`} className="grid gap-1 border-b py-2 text-sm sm:grid-cols-[180px_1fr]"><span className="font-medium">{failure.siteName}</span><span className="break-words text-destructive">首页：{failure.error}</span></div>)}
          {diagnostics && diagnostics.sources.every((source) => !source.error && !source.searchError) && diagnostics.homeErrors.length === 0 && <div className="text-sm text-muted-foreground">当前没有源刷新或首页加载错误。</div>}
        </div>
        {diagnostics?.javaCrash ? <details className="mt-4 rounded-md border"><summary className="cursor-pointer px-4 py-3 text-sm font-medium">上次 Java 闪退记录</summary><pre className="max-h-80 overflow-auto whitespace-pre-wrap border-t p-4 text-xs leading-5 text-destructive">{diagnostics.javaCrash}</pre></details> : <div className="mt-4 text-sm text-muted-foreground">没有保存的 Java 闪退记录。</div>}
      </section>
    </>
  )
}

function DiagnosticFact({ label, value }: { label: string; value: string }) {
  return <div className="min-w-0 px-4 py-3"><div className="text-xs text-muted-foreground">{label}</div><div className="mt-1 break-words text-sm font-medium">{value}</div></div>
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
