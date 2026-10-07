import { useCallback, useEffect, useState } from "react"
import { Square, Stethoscope } from "lucide-react"
import { api } from "../lib/api"
import type { SiteHealth, SiteHealthResult } from "../lib/api"
import { Badge } from "./ui/badge"
import { Button } from "./ui/button"
import { SectionCard } from "./ui/primitives"
/** Errors from the API arrive as Error objects; the message is what the user needs. */
const describe = (reason: unknown) => reason instanceof Error ? reason.message : String(reason)

export function SiteHealthCard({ contentVersion, setError }: {
  contentVersion: number
  setError: (value: string) => void
}) {
  const [health, setHealth] = useState<SiteHealth | null>(null)
  const [expanded, setExpanded] = useState(false)

  const load = useCallback(
    () => api.siteHealth().then(setHealth).catch((reason) => setError(describe(reason))),
    [setError],
  )
  useEffect(() => {
    void load()
    const timer = window.setInterval(() => {
      if (health?.sweep.running) void load()
    }, 4000)
    return () => window.clearInterval(timer)
  }, [load, contentVersion, health?.sweep.running])

  const sweep = health?.sweep
  const running = sweep?.running ?? false

  async function start(options: { failedOnly?: boolean; pluginsOnly?: boolean }) {
    try {
      await api.runSiteSweep({ limit: 200, failedOnly: options.failedOnly, pluginsOnly: options.pluginsOnly })
      await load()
    } catch (reason) {
      setError(describe(reason))
    }
  }

  async function stop() {
    try {
      await api.stopSiteSweep()
      await load()
    } catch (reason) {
      setError(describe(reason))
    }
  }

  const results: SiteHealthResult[] = sweep?.results ?? []
  const good = results.filter((row) => row.ok)
  const bad = results.filter((row) => !row.ok)

  return (
    <SectionCard
      title="站点体检"
      badges={health
        ? <Badge variant="outline">可用 {health.knownGood} · 不可用 {health.knownBad}</Badge>
        : undefined}
      action={
        <div className="flex items-center gap-2">
          {running
            ? <Button variant="outline" size="sm" onClick={stop}><Square />停止</Button>
            : (
              <>
                <Button variant="outline" size="sm" onClick={() => start({ failedOnly: true })}>重测失败</Button>
                <Button size="sm" onClick={() => start({})}><Stethoscope />开始体检</Button>
              </>
            )}
          <Button variant="ghost" size="sm" onClick={() => setExpanded(!expanded)}>
            {expanded ? "收起" : "明细"}
          </Button>
        </div>
      }
    >
      {running && (
        <div className="mb-2 space-y-1">
          <div className="flex items-center justify-between text-xs text-muted-foreground">
            <span>正在测 {sweep!.currentSite || "…"}</span>
            <span>{sweep!.done}/{sweep!.total} · 可用 {sweep!.ok}</span>
          </div>
          <div className="h-1 overflow-hidden rounded-full bg-muted">
            <div className="h-full rounded-full bg-primary transition-all"
              style={{ width: `${sweep!.total ? Math.round(sweep!.done * 100 / sweep!.total) : 0}%` }} />
          </div>
        </div>
      )}

      {results.length === 0 && !running && (
        <p className="text-sm text-muted-foreground">
          还没有体检记录。体检会逐个站点测一次搜索，之后首页与搜索只使用能用的站点。
        </p>
      )}

      {results.length > 0 && (
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <Badge variant="secondary">可用 {good.length}</Badge>
          <Badge variant="destructive">不可用 {bad.length}</Badge>
          {bad.length > 0 && <span className="text-muted-foreground">不可用站点会在下次搜索时直接跳过</span>}
        </div>
      )}

      {expanded && results.length > 0 && (
        <div className="mt-3 max-h-80 divide-y overflow-auto">
          {[...good, ...bad].map((row) => (
            <div key={row.siteKey} className="grid gap-1 py-2 text-sm sm:grid-cols-[minmax(0,1fr)_auto]">
              <span className="min-w-0">
                <span className="truncate font-medium">{row.siteName}</span>
                {row.type === 3 && <Badge variant="outline" className="ml-2">插件</Badge>}
                {!row.ok && <span className="ml-2 break-words text-rose-300">{row.reason || "无响应"}</span>}
              </span>
              <span className="text-xs text-muted-foreground">
                {row.ok ? `${row.itemCount} 条 · ${row.latencyMs} ms` : "不可用"}
              </span>
            </div>
          ))}
        </div>
      )}
    </SectionCard>
  )
}
