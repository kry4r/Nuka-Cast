import { useCallback, useEffect, useMemo, useState } from "react"
import { CheckCircle2, Download, LoaderCircle, Plus, RefreshCw, ShieldCheck, TriangleAlert } from "lucide-react"

import { api, type RecommendedSource } from "@/lib/api"
import { formatCheckedAt, hostOf, kindLabel, kindTone, probeDotClass, probeState, probeText, recommendSummary } from "@/lib/kind"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { EmptyState, RowSkeletons, SectionCard, StatusDot } from "@/components/ui/primitives"

type RecommendedKind = "live" | "vod" | "drama"

export function RecommendedShelf({ kind, title, description, onChanged, setError }: {
  kind: RecommendedKind
  title: string
  description?: string
  onChanged?: () => void
  setError: (value: string) => void
}) {
  const [items, setItems] = useState<RecommendedSource[] | null>(null)
  const [verifiedAt, setVerifiedAt] = useState("")
  const [busy, setBusy] = useState("")
  const [adding, setAdding] = useState(false)

  const load = useCallback(async () => {
    try {
      const data = await api.recommended()
      setItems(data.items)
      setVerifiedAt(data.verifiedAt)
    } catch (reason) {
      setError(message(reason))
    }
  }, [setError])

  useEffect(() => {
    void load()
  }, [load])

  const shelf = useMemo(() => (items ?? []).filter((item) => item.kind === kind), [items, kind])
  const summary = recommendSummary(shelf)

  async function add(item: RecommendedSource) {
    setBusy(item.id)
    try {
      const result = await api.addRecommended({ id: item.id })
      setItems(result.items)
      onChanged?.()
    } catch (reason) {
      setError(message(reason))
    } finally {
      setBusy("")
    }
  }

  async function addAll() {
    setAdding(true)
    try {
      const result = await api.addRecommended({ kind, all: true })
      setItems(result.items)
      onChanged?.()
    } catch (reason) {
      setError(message(reason))
    } finally {
      setAdding(false)
    }
  }

  async function verifyOne(item: RecommendedSource) {
    setBusy(item.id)
    try {
      const result = await api.verifyRecommended({ id: item.id })
      setItems(result.items)
    } catch (reason) {
      setError(message(reason))
    } finally {
      setBusy("")
    }
  }

  async function verifyAll() {
    setBusy("__all__")
    try {
      const result = await api.verifyRecommended({ kind, all: true })
      setItems(result.items)
    } catch (reason) {
      setError(message(reason))
    } finally {
      setBusy("")
    }
  }

  return (
    <SectionCard
      title={title}
      description={description}
      badges={
        <>
          <Badge variant="outline">已添加 {summary.added}/{summary.total}</Badge>
          {verifiedAt && <span className="text-xs text-muted-foreground">内置清单校验于 {verifiedAt}</span>}
        </>
      }
      action={
        <>
          <Button variant="outline" size="sm" disabled={busy === "__all__" || shelf.length === 0} onClick={verifyAll}>
            <RefreshCw className={busy === "__all__" ? "animate-spin" : ""} />检测全部
          </Button>
          <Button size="sm" disabled={adding || summary.pending === 0} onClick={addAll}>
            {adding ? <LoaderCircle className="animate-spin" /> : <Download />}
            {summary.pending > 0 ? `添加全部（${summary.pending}）` : "已全部添加"}
          </Button>
        </>
      }
    >
      {!items && <RowSkeletons />}
      {items && shelf.length === 0 && (
        <EmptyState icon={ShieldCheck} title={`内置清单里没有${kindLabel(kind)}源`} />
      )}
      {items && shelf.length > 0 && (
        <div className="grid gap-2 xl:grid-cols-2">
          {shelf.map((item) => {
            const state = probeState(item.probe, busy === item.id)
            return (
              <div key={item.id}
                className="flex items-start gap-3 rounded-lg border bg-background/40 p-3 transition hover:border-foreground/20 hover:bg-accent/30">
                <span className={`mt-0.5 inline-flex size-8 shrink-0 items-center justify-center rounded-lg border text-xs font-medium ${kindTone(item.kind).badge}`}>
                  {item.name.slice(0, 1)}
                </span>
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="truncate text-sm font-medium">{item.name}</span>
                    {item.added && <Badge variant="secondary" className="gap-1"><CheckCircle2 className="size-3" />已添加</Badge>}
                  </div>
                  <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                    <span className="truncate">{hostOf(item.url)}</span>
                    <StatusDot className={probeDotClass(state)} />
                    <span className={state === "failed" ? "text-rose-300" : state === "ok" ? "text-emerald-300" : ""}>
                      {probeText(item.probe, busy === item.id)}
                    </span>
                    {item.probe?.checkedAt ? <span>{formatCheckedAt(item.probe.checkedAt)}</span> : null}
                  </div>
                  {item.note && <p className="mt-1 text-xs leading-5 text-muted-foreground/90">{item.note}</p>}
                  {state === "failed" && (
                    <p className="mt-1 flex items-center gap-1 text-xs text-rose-300">
                      <TriangleAlert className="size-3" />当前网络不可用，可换用同组镜像
                    </p>
                  )}
                </div>
                <div className="flex shrink-0 items-center gap-1.5">
                  <Button variant="outline" size="sm" disabled={busy === item.id} title="用本机网络检测" onClick={() => verifyOne(item)}>
                    <RefreshCw className={busy === item.id ? "animate-spin" : ""} />
                  </Button>
                  <Button variant={item.added ? "ghost" : "secondary"} size="sm" disabled={item.added || busy === item.id}
                    onClick={() => add(item)}>
                    {item.added ? <CheckCircle2 /> : <Plus />}{item.added ? "已添加" : "添加"}
                  </Button>
                </div>
              </div>
            )
          })}
        </div>
      )}
    </SectionCard>
  )
}

function message(reason: unknown): string {
  return reason instanceof Error ? reason.message : String(reason)
}
