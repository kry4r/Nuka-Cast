import type { SourceProbe } from "./api"

/** Kind accents. Full class strings live here so Tailwind can see them at build time. */
const KIND_TONES: Record<string, { badge: string; dot: string; icon: string }> = {
  live: {
    badge: "border-sky-500/30 bg-sky-500/10 text-sky-200",
    dot: "bg-sky-400",
    icon: "text-sky-300",
  },
  vod: {
    badge: "border-violet-500/30 bg-violet-500/10 text-violet-200",
    dot: "bg-violet-400",
    icon: "text-violet-300",
  },
  drama: {
    badge: "border-amber-500/30 bg-amber-500/10 text-amber-200",
    dot: "bg-amber-400",
    icon: "text-amber-300",
  },
}

const FALLBACK_TONE = {
  badge: "border-border bg-muted text-muted-foreground",
  dot: "bg-muted-foreground",
  icon: "text-muted-foreground",
}

export function kindTone(kind: string) {
  return KIND_TONES[kind] ?? FALLBACK_TONE
}

export function kindLabel(kind: string): string {
  if (kind === "live") return "直播"
  if (kind === "drama") return "短剧"
  if (kind === "vod") return "点播"
  return kind || "其他"
}

export type ProbeState = "unchecked" | "checking" | "ok" | "failed"

export function probeState(probe: SourceProbe | null | undefined, checking = false): ProbeState {
  if (checking) return "checking"
  if (!probe) return "unchecked"
  return probe.ok ? "ok" : "failed"
}

const PROBE_TONES: Record<ProbeState, string> = {
  unchecked: "bg-muted-foreground/50",
  checking: "bg-amber-400 animate-pulse",
  ok: "bg-emerald-400",
  failed: "bg-rose-400",
}

export function probeDotClass(state: ProbeState): string {
  return PROBE_TONES[state]
}

export function probeText(probe: SourceProbe | null | undefined, checking = false): string {
  if (checking) return "检测中…"
  if (!probe) return "未检测"
  if (probe.ok) {
    const parts = [probe.detail || "可用", `${probe.latencyMs} ms`]
    return parts.filter(Boolean).join(" · ")
  }
  return probe.error || `不可用${probe.httpStatus ? `（HTTP ${probe.httpStatus}）` : ""}`
}

export function formatCheckedAt(timestamp: number): string {
  if (!timestamp) return ""
  const date = new Date(timestamp)
  const pad = (value: number) => String(value).padStart(2, "0")
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** Short host label for a URL, used instead of dumping the whole link into the UI. */
export function hostOf(url: string): string {
  try {
    return new URL(url).host
  } catch {
    return url
  }
}

export function recommendSummary(items: { kind: string; added: boolean }[]) {
  const total = items.length
  const added = items.filter((item) => item.added).length
  return { total, added, pending: total - added }
}
