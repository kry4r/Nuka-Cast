import { useEffect, useState } from "react"
import { ExternalLink, RefreshCw } from "lucide-react"
import { api, type UpdateStatus } from "@/lib/api"
import { InlineButton, SectionCard } from "@/components/ui/primitives"

/**
 * Which build the TV is running, and whether a newer release exists.
 *
 * A sideloaded app has no store: without this the console cannot answer "am I up to date?", and the
 * answer is the release feed read by the device itself (so it also proves the TV can reach the network).
 * Nothing is installed here — the link opens the release page for a person to download.
 */
export function UpdateCard({ onError }: { onError: (message: string) => void }) {
  const [status, setStatus] = useState<UpdateStatus | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let active = true
    api
      .update(false)
      .then((value) => {
        if (active) setStatus(value)
      })
      .catch(() => {
        // An older build without the endpoint: the card stays hidden rather than showing an error.
      })
    return () => {
      active = false
    }
  }, [])

  if (!status) return null

  const check = async () => {
    setBusy(true)
    try {
      setStatus(await api.update(true))
    } catch (reason) {
      onError(reason instanceof Error ? reason.message : String(reason))
    } finally {
      setBusy(false)
    }
  }

  const link = status.apkUrl || status.pageUrl

  return (
    <SectionCard
      title="版本"
      description="由电视自己读取发布页，频道不经过本机"
      badges={status.updateAvailable ? <span className="text-xs text-amber-300">有新版本</span> : undefined}
    >
      <div className="flex flex-wrap items-center gap-2 px-4 py-3 text-sm">
        <span className="w-24 text-muted-foreground">当前</span>
        <span className="font-medium">{status.currentVersion || "未知"}</span>
        {status.latestVersion && status.latestVersion !== status.currentVersion && (
          <span className="text-muted-foreground">最新 {status.latestVersion}</span>
        )}
      </div>
      <div className="flex flex-wrap items-center gap-2 border-t px-4 py-3 text-sm">
        <span className="w-24 text-muted-foreground">状态</span>
        <span className={status.error ? "text-rose-300" : status.updateAvailable ? "text-amber-300" : "text-muted-foreground"}>
          {status.summary}
        </span>
      </div>
      <div className="flex flex-wrap items-center gap-2 border-t px-4 py-3">
        <InlineButton disabled={busy} onClick={check}>
          <RefreshCw className={busy ? "size-3.5 animate-spin" : "size-3.5"} />检查更新
        </InlineButton>
        {link && (
          <a className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground" href={link} target="_blank" rel="noreferrer">
            <ExternalLink className="size-3.5" />下载地址
          </a>
        )}
      </div>
    </SectionCard>
  )
}
