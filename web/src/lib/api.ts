const TOKEN_KEY = "nukacast-control-token"
export const AUTH_EXPIRED_EVENT = "nukacast-auth-expired"

const tokenStore = sessionStorage
localStorage.removeItem(TOKEN_KEY)

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
  pairingRequired: boolean
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

export type Diagnostics = {
  javaCrash: string
  serviceState: string
  serviceMessage: string
  deviceWarnings: string[]
  airPlay: Status["airPlay"]
  player: Player
  sources: Source[]
  homeErrors: { sourceId: string; siteKey: string; siteName: string; error: string; updatedAt: number }[]
}

export type LogLevel = "DEBUG" | "INFO" | "WARN" | "ERROR"

export type LogEntry = {
  timestamp: number
  level: LogLevel
  component: string
  message: string
  trace: string
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

async function request<T>(path: string, init?: RequestInit, authenticated = true): Promise<T> {
  const headers = new Headers(init?.headers)
  if (init?.body) headers.set("Content-Type", "application/json")
  if (authenticated) {
    const token = tokenStore.getItem(TOKEN_KEY)
    if (token) headers.set("Authorization", `Bearer ${token}`)
  }
  const response = await fetch(path, { ...init, headers })
  const body = await response.json().catch(() => ({}))
  if (response.status === 401 && authenticated) {
    tokenStore.removeItem(TOKEN_KEY)
    window.dispatchEvent(new Event(AUTH_EXPIRED_EVENT))
  }
  if (!response.ok) throw new Error(body.error || `HTTP ${response.status}`)
  return body as T
}

export const api = {
  hasToken: () => Boolean(tokenStore.getItem(TOKEN_KEY)),
  forget: () => tokenStore.removeItem(TOKEN_KEY),
  status: () => request<Status>("/api/status", undefined, false),
  pair: async (code: string) => {
    const result = await request<{ token: string }>("/api/pair", {
      method: "POST",
      body: JSON.stringify({ code }),
    }, false)
    tokenStore.setItem(TOKEN_KEY, result.token)
    return result
  },
  device: () => request<Device>("/api/device"),
  diagnostics: () => request<Diagnostics>("/api/diagnostics"),
  logs: () => request<LogEntry[]>("/api/logs"),
  clearLogs: () => request<{ cleared: boolean }>("/api/logs", { method: "DELETE" }),
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
}
