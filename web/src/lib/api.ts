export type Status = {
  name: string
  version: string
  serviceState: string
  message: string
  activeMedia: string
  sourceCount: number
  siteCount: number
  stateVersion: number
  contentVersion: number
  storageMountCount: number
  libraryItemCount: number
  storageScanning: boolean
  webAddress: string
  airPlayName: string
  airPlay: {
    state: string
    port: number
    error: string
    sessionActive: boolean
    videoFrames: number
    videoDrops: number
    audioPackets: number
    audioDrops: number
    videoWidth: number
    videoHeight: number
    videoConfigPackets: number
    videoKeyFrames: number
    decoderInputs: number
    decoderOutputs: number
    decoderFormatChanges: number
    decoderName: string
    decoderSoftwareFallback: boolean
    identity: string
  }
}

export type Source = {
  id: string
  name: string
  url: string
  kind: "single" | "warehouse"
  parentId: string
  enabled: boolean
  contentHash: string
  updatedAt: number
  siteCount: number
  searchableSiteCount: number
  liveCount: number
  latencyMs: number
  error: string
  searchError: string
}

export type Site = {
  key: string
  name: string
  type: number
  searchable: number
  filterable: number
  sourceId: string
  sourceName: string
}

export type StageRecord = {
  scope: string
  subject: string
  stage: string
  result: "running" | "ok" | "failed" | string
  startedAt: number
  updatedAt: number
  elapsedMs: number
  detail: string
  errorCode: string
  rootCauseClass: string
  generation: number
}

export type Diagnostics = {
  javaCrash: string
  serviceState: string
  serviceMessage: string
  deviceWarnings: string[]
  airPlay: Status["airPlay"]
  player: Player
  sources: Source[]
  homeErrors: { sourceId: string; siteKey: string; siteName: string; error: string; updatedAt: number }[]
  httpStack: { degraded: boolean; initError: string }
  stages: StageRecord[]
  /** Sites whose plugin cannot run on this device, with the reason (skipped during search). */
  siteIssues: SiteIssue[]
  /** How the previous process ended; null when nothing was recorded. */
  lastRun: LastRun | null
}

export type LogLevel = "DEBUG" | "INFO" | "WARN" | "ERROR"

export type LogEntry = {
  timestamp: number
  level: LogLevel
  component: string
  message: string
  trace: string
  /** Identical consecutive lines are folded into one entry with a repeat count. */
  repeats?: number
}

export type SiteIssue = {
  siteKey: string
  siteName: string
  reason: string
  permanent: boolean
  updatedAt: number
}

export type RunSample = {
  at: number
  heapPercent: number
  heapUsedBytes: number
  nativeHeapBytes: number
  availableMemoryBytes: number
  stage: string
}

export type LastRun = {
  startedAt: number
  endedAt: number
  endedCleanly: boolean
  /** The previous run was replaced by an app update rather than killed by the system. */
  killedByUpdate?: boolean
  durationMs: number
  device: string
  version: string
  peakHeapPercent: number
  lastStage: string
  lastHeapPercent: number
  lastAvailableMemoryBytes: number
  samples: RunSample[]
}

export type SearchItem = {
  siteKey: string
  siteName: string
  sourceId: string
  vodId: string
  name: string
  poster: string
  remarks: string
  year: string
  area: string
  typeName: string
  actor: string
  director: string
  score: string
  plot: string
}

export type SearchResponse = {
  keyword: string
  elapsedMs: number
  searchedSites: number
  failedSites: number
  partial: boolean
  items: SearchItem[]
  errors: { siteKey: string; siteName: string; message: string }[]
}

export type Episode = { name: string; id: string }
export type PlaySource = { name: string; episodes: Episode[] }
export type MediaDetail = SearchItem & { playSources: PlaySource[] }

export type PlaybackInfo = {
  siteKey: string
  title: string
  url: string
  parse: number
  direct: boolean
  sniffUrl: string
  error: string
  headers: Record<string, string>
}

export type DramaItem = {
  providerId: string
  dramaId: string
  title: string
  cover: string
  intro: string
  remark: string
  category: string
  tags: string[]
  episodeCount: number
  heat: string
  status: string
  contentKind: string
}

export type DramaSearchResult = {
  providerId: string
  providerName: string
  keyword: string
  ok: boolean
  error: string
  errorCode: string
  rootCauseClass: string
  total: number
  warning: string
  elapsedMs: number
  partial: boolean
  items: DramaItem[]
}

export type DramaEpisode = {
  index: number
  name: string
  playUrl: string
  pageUrl: string
  headers: Record<string, string>
  direct: boolean
}

export type DramaDetail = {
  item: DramaItem
  related: DramaItem[]
  relatedTotal: number
  relatedPartial: boolean
  episodes: DramaEpisode[]
  directPlayable: boolean
  note: string
}

export type DramaPlayResult = {
  title: string
  url: string
  index: number
  episodeName: string
  headers: Record<string, string>
}

export type DramaLine = {
  sourceId: string
  sourceName: string
  siteKey: string
  siteName: string
  vodId: string
  name: string
  remarks: string
  poster: string
  year: string
  typeName: string
  matchScore: number
  matchKind: "exact" | "prefix" | "contains" | string
  episodeHint: string
}

export type DramaLineResult = {
  lines: DramaLine[]
  searchedSites: number
  failedSites: number
  searched: boolean
  error: string
  elapsedMs: number
}

export type DramaProvider = {
  id: string
  name: string
  baseUrl: string
  kind: string
  builtin: boolean
  enabled: boolean
  error: string
  updatedAt: number
  categoryId: string
  note: string
}

export type DramaProviders = {
  providers: DramaProvider[]
}

/** One site's result from a sweep, and the verdict stored from it. */
export type SiteHealthResult = {
  siteKey: string
  siteName: string
  sourceId: string
  type: number
  ok: boolean
  itemCount: number
  latencyMs: number
  reason: string
  at: number
}

export type SiteHealthVerdict = SiteHealthResult & { checkedAt: number }

export type SiteHealth = {
  sweep: {
    running: boolean
    cancelled: boolean
    startedAt: number
    finishedAt: number
    total: number
    done: number
    ok: number
    failed: number
    keyword: string
    currentSite: string
    error: string
    results: SiteHealthResult[]
  }
  knownGood: number
  knownBad: number
  lastCheckedAt: number
  verdicts: SiteHealthVerdict[]
}

export type SourceProbe = {
  id: string
  ok: boolean
  httpStatus: number
  latencyMs: number
  bytes: number
  detail: string
  errorCode: string
  error: string
  checkedAt: number
}

export type RecommendedSource = {
  id: string
  kind: "live" | "vod" | "drama" | string
  group: string
  name: string
  url: string
  note: string
  categoryId: string
  verifiedAt: string
  added: boolean
  probe: SourceProbe | null
}

export type RecommendedList = {
  verifiedAt: string
  note: string
  items: RecommendedSource[]
}

export type LiveSourceRow = {
  id: string
  name: string
  url: string
  enabled: boolean
  error: string
  updatedAt: number
  user: boolean
}

export type LiveSource = {
  id: string
  sourceId: string
  name: string
  url: string
  epg: string
  logo: string
}

export type LiveChannel = {
  id: string
  name: string
  epgId: string
  logo: string
  group: string
  urls: string[]
  headers: Record<string, string>
}

export type LiveCatalog = {
  sourceId: string
  sourceName: string
  groups: { name: string; channels: LiveChannel[] }[]
}

export type EpgSchedule = {
  channel: string
  date: string
  programs: { title: string; start: string; end: string; description: string }[]
}

export type Device = {
  manufacturer: string
  model: string
  product: string
  androidVersion: string
  sdk: number
  primaryAbi: string
  totalMemoryBytes: number
  appMemoryBytes: number
  displayWidth: number
  displayHeight: number
  refreshRate: number
  hasHardwareAvcDecoder: boolean
  preferredAvcDecoder: string
  avcDecoders: string[]
  warnings: string[]
}

export type Player = {
  state: string
  title: string
  url: string
  positionMs: number
  durationMs: number
  playing: boolean
  error: string
}

export type StorageMount = {
  id: string
  name: string
  type: "local" | "webdav" | "smb"
  uri: string
  username: string
  enabled: boolean
  lastScanAt: number
  fileCount: number
  error: string
}

export type MediaEntry = {
  id: string
  mountId: string
  mountName: string
  title: string
  fileName: string
  uri: string
  poster: string
  typeName: string
  year: string
  season: number
  episode: number
  size: number
  modifiedAt: number
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers)
  if (init?.body) headers.set("Content-Type", "application/json")
  const response = await fetch(path, { ...init, headers })
  const body = await response.json().catch(() => ({}))
  if (!response.ok) throw new Error(body.error || `HTTP ${response.status}`)
  return body as T
}

/** Fetches a plain-text artifact (the diagnostic bundle) rather than JSON. */
async function requestText(path: string): Promise<string> {
  const response = await fetch(path)
  const text = await response.text()
  if (!response.ok) throw new Error(text.slice(0, 200) || `HTTP ${response.status}`)
  return text
}

export interface LibraryEntry {
  name: string
  siteName: string
  vodId: string
  episodeName: string
  positionMs: number
  durationMs: number
}

/** What the device reports about its own version and the newest release it can see. */
export interface UpdateStatus {
  currentVersion: string
  latestVersion: string
  updateAvailable: boolean
  apkUrl: string
  pageUrl: string
  error: string
  summary: string
  checkedAt: number
}

export interface PlaybackSettings {
  autoNextEpisode: boolean
  quality: "auto" | "highest" | "lowest"
  qualityLabel: string
  softDecoder: boolean
  /** Whether the box starts its casting endpoints by itself when it powers on. */
  startOnBoot: boolean
  /** Seconds skipped at the start of every episode, 0 when off. */
  skipIntroSeconds: number
  skipIntroLabel: string
  /** Seconds before the end that count as finished, 0 when off. */
  skipOutroSeconds: number
  skipOutroLabel: string
}

export interface Library {
  favorites: LibraryEntry[]
  history: LibraryEntry[]
}

export const api = {
  status: () => request<Status>("/api/status"),
  device: () => request<Device>("/api/device"),
  diagnostics: () => request<Diagnostics>("/api/diagnostics"),
  update: (refresh = false) => request<UpdateStatus>(`/api/update${refresh ? "?refresh=1" : ""}`),
  logs: () => request<LogEntry[]>("/api/logs"),
  clearLogs: () => request<{ cleared: boolean }>("/api/logs", { method: "DELETE" }),
  exportDiagnostics: (level?: LogLevel) =>
    requestText(`/api/logs/export${level ? `?level=${level}` : ""}`),
  sources: () => request<Source[]>("/api/sources"),
  sites: () => request<Site[]>("/api/sites"),
  addSource: (name: string, url: string) => request<Source>("/api/sources", {
    method: "POST",
    body: JSON.stringify({ name, url }),
  }),
  removeSource: (id: string) => request<{ removed: boolean }>(`/api/sources/${id}`, { method: "DELETE" }),
  refreshSources: () => request<{ refreshing: boolean }>("/api/sources/refresh", { method: "POST" }),
  storageMounts: () => request<StorageMount[]>("/api/storage/mounts"),
  addStorageMount: (payload: { name: string; type: string; uri: string; username: string; password: string }) =>
    request<StorageMount>("/api/storage/mounts", { method: "POST", body: JSON.stringify(payload) }),
  removeStorageMount: (id: string) => request<{ removed: boolean }>(`/api/storage/mounts/${id}`, { method: "DELETE" }),
  scanStorage: () => request<{ scanning: boolean }>("/api/storage/scan", { method: "POST" }),
  storageLibrary: () => request<MediaEntry[]>("/api/storage/library"),
  search: (payload: Record<string, unknown>) => request<SearchResponse>("/api/search", {
    method: "POST",
    body: JSON.stringify(payload),
  }),
  detail: (payload: { sourceId: string; siteKey: string; vodId: string }) => request<MediaDetail>("/api/detail", {
    method: "POST",
    body: JSON.stringify(payload),
  }),
  playItem: (payload: Record<string, unknown>) => request<PlaybackInfo>("/api/play", {
    method: "POST",
    body: JSON.stringify(payload),
  }),
  liveSources: () => request<LiveSource[]>("/api/live"),
  liveCatalog: (sourceId: string) => request<LiveCatalog>(`/api/live/catalog?sourceId=${encodeURIComponent(sourceId)}`),
  epg: (sourceId: string, channelId: string) => request<EpgSchedule>(`/api/live/epg?sourceId=${encodeURIComponent(sourceId)}&channelId=${encodeURIComponent(channelId)}`),
  playLive: (sourceId: string, channelId: string, urlIndex = 0) => request<Player>("/api/live/play", {
    method: "POST",
    body: JSON.stringify({ sourceId, channelId, urlIndex }),
  }),
  player: () => request<Player>("/api/player"),
  control: (payload: Record<string, unknown>) => request<Player>("/api/player", {
    method: "POST",
    body: JSON.stringify(payload),
  }),
  disconnectAirPlay: () => request<{ disconnected: boolean }>("/api/airplay/disconnect", {
    method: "POST",
  }),
  dramaProviders: () => request<DramaProviders>("/api/drama/providers"),
  addDramaProvider: (payload: { name?: string; url: string }) =>
    request<DramaProvider>("/api/drama/providers", { method: "POST", body: JSON.stringify(payload) }),
  removeDramaProvider: (id: string) => request<{ removed: boolean }>(`/api/drama/providers/${id}`, {
    method: "DELETE",
  }),
  setDramaProviderEnabled: (id: string, enabled: boolean) =>
    request<{ enabled: boolean }>(`/api/drama/providers/${id}/enabled`, {
      method: "POST",
      body: JSON.stringify({ enabled }),
    }),
  dramaSearch: (payload: { providerId?: string; keyword: string }) =>
    request<DramaSearchResult>("/api/drama/search", { method: "POST", body: JSON.stringify(payload) }),
  dramaBrowse: (payload: { providerId?: string; categoryId?: string; page?: number }) =>
    request<DramaSearchResult>("/api/drama/browse", { method: "POST", body: JSON.stringify(payload) }),
  dramaDetail: (payload: { providerId: string; dramaId: string }) =>
    request<DramaDetail>("/api/drama/detail", { method: "POST", body: JSON.stringify(payload) }),
  dramaLines: (payload: { providerId: string; dramaId: string; sourceId?: string }) =>
    request<DramaLineResult>("/api/drama/lines", { method: "POST", body: JSON.stringify(payload) }),
  dramaPlay: (payload: { providerId: string; dramaId: string; index: number; title?: string; poster?: string }) =>
    request<DramaPlayResult>("/api/drama/play", { method: "POST", body: JSON.stringify(payload) }),
  recommended: () => request<RecommendedList>("/api/recommended"),
  verifyRecommended: (payload: { id?: string; kind?: string; all?: boolean }) =>
    request<{ probes: SourceProbe[]; items: RecommendedSource[] }>("/api/recommended/verify", {
      method: "POST",
      body: JSON.stringify(payload),
    }),
  addRecommended: (payload: { id?: string; ids?: string[]; kind?: string; all?: boolean }) =>
    request<{ added: number; items: RecommendedSource[] }>("/api/recommended/add", {
      method: "POST",
      body: JSON.stringify(payload),
    }),
  liveSourceRows: () => request<LiveSourceRow[]>("/api/live/sources"),
  addLiveSource: (payload: { name?: string; url: string }) =>
    request<{ id: string; name: string; url: string }>("/api/live/sources", {
      method: "POST",
      body: JSON.stringify(payload),
    }),
  removeLiveSource: (id: string) => request<{ removed: boolean }>(`/api/live/sources/${id}`, {
    method: "DELETE",
  }),
  setLiveSourceEnabled: (id: string, enabled: boolean) =>
    request<{ enabled: boolean }>(`/api/live/sources/${id}/enabled`, {
      method: "POST",
      body: JSON.stringify({ enabled }),
    }),
  settings: () => request<PlaybackSettings>("/api/settings"),
  updateSetting: (
    name: "autoNextEpisode" | "quality" | "softDecoder" | "startOnBoot" | "skipIntroSeconds" | "skipOutroSeconds",
    value: string,
  ) =>
    request<PlaybackSettings>("/api/settings", { method: "POST", body: JSON.stringify({ name, value }) }),
  library: () => request<Library>("/api/library"),
  removeLibraryEntry: (payload: { kind: "favorite" | "history"; vodId?: string; name?: string; all?: string }) =>
    request<{ kind: string; removed: number }>("/api/library/remove", {
      method: "POST",
      body: JSON.stringify(payload),
    }),
  siteHealth: () => request<SiteHealth>("/api/debug/health"),
  runSiteSweep: (options: { limit?: number; keyword?: string; failedOnly?: boolean; pluginsOnly?: boolean } = {}) =>
    request<SiteHealth>("/api/debug/health/run", { method: "POST", body: JSON.stringify(options) }),
  stopSiteSweep: () => request<{ stopped: boolean }>("/api/debug/health/stop", { method: "POST" }),
}
