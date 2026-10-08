import { useEffect, useState } from "react"
import { api, type PlaybackSettings } from "@/lib/api"
import { InlineButton, SectionCard } from "@/components/ui/primitives"

/**
 * The TV's playback choices, editable from the console.
 *
 * Auto-advance, picture quality and the decoder are the three settings a viewer actually changes; the
 * TV page shows the same values, and both write through the same endpoint.
 */
/** The same step list the television's buttons walk through. */
function nextStep(steps: number[], current: number): number {
  const index = steps.indexOf(current)
  return steps[(index + 1) % steps.length]
}

export function PlaybackSettingsCard({ onError }: { onError: (message: string) => void }) {
  const [settings, setSettings] = useState<PlaybackSettings | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let active = true
    api
      .settings()
      .then((value) => {
        if (active) setSettings(value)
      })
      .catch(() => {
        // An older build without the endpoint: the card simply stays hidden.
      })
    return () => {
      active = false
    }
  }, [])

  if (!settings) return null

  const update = async (
    name: "autoNextEpisode" | "quality" | "softDecoder" | "startOnBoot" | "skipIntroSeconds" | "skipOutroSeconds",
    value: string,
  ) => {
    setBusy(true)
    try {
      setSettings(await api.updateSetting(name, value))
    } catch (reason) {
      onError(reason instanceof Error ? reason.message : String(reason))
    } finally {
      setBusy(false)
    }
  }

  const nextQuality = settings.quality === "auto" ? "highest" : settings.quality === "highest" ? "lowest" : "auto"

  return (
    <SectionCard
      title="播放设置"
      description="与电视端设置页相同，改动立即生效"
      badges={busy ? <span className="text-xs text-muted-foreground">保存中…</span> : undefined}
    >
      <div className="flex flex-wrap items-center gap-2 px-4 py-3 text-sm">
        <span className="w-24 text-muted-foreground">自动连播</span>
        <InlineButton disabled={busy} onClick={() => update("autoNextEpisode", settings.autoNextEpisode ? "0" : "1")}>
          {settings.autoNextEpisode ? "开 · 一集播完进下一集" : "关 · 播完停在本集"}
        </InlineButton>
      </div>
      <div className="flex flex-wrap items-center gap-2 border-t px-4 py-3 text-sm">
        <span className="w-24 text-muted-foreground">画质</span>
        <InlineButton disabled={busy} onClick={() => update("quality", nextQuality)}>
          {settings.qualityLabel}
        </InlineButton>
        <span className="text-xs text-muted-foreground">
          {settings.quality === "highest"
            ? "优先最高清晰度，解码器不支持时自动降级"
            : settings.quality === "lowest"
              ? "优先最低清晰度，适合低配电视"
              : "按带宽与解码能力自动选择"}
        </span>
      </div>
      <div className="flex flex-wrap items-center gap-2 border-t px-4 py-3 text-sm">
        <span className="w-24 text-muted-foreground">解码</span>
        <InlineButton disabled={busy} onClick={() => update("softDecoder", settings.softDecoder ? "0" : "1")}>
          {settings.softDecoder ? "软件" : "自动"}
        </InlineButton>
        <span className="text-xs text-muted-foreground">
          硬件解码器花屏或无输出时，改成软件解码
        </span>
      </div>
      <div className="flex flex-wrap items-center gap-2 border-t px-4 py-3 text-sm">
        <span className="w-24 text-muted-foreground">跳过片头</span>
        <InlineButton
          disabled={busy}
          onClick={() => update("skipIntroSeconds", String(nextStep([0, 30, 60, 90, 120], settings.skipIntroSeconds)))}
        >
          {settings.skipIntroLabel}
        </InlineButton>
        <span className="text-xs text-muted-foreground">
          {settings.skipIntroSeconds > 0
            ? `每集从第 ${settings.skipIntroSeconds} 秒开始；超过片长一半时自动从头播放`
            : "不开：每集从头播放"}
        </span>
      </div>
      <div className="flex flex-wrap items-center gap-2 border-t px-4 py-3 text-sm">
        <span className="w-24 text-muted-foreground">跳过片尾</span>
        <InlineButton
          disabled={busy}
          onClick={() => update("skipOutroSeconds", String(nextStep([0, 30, 60, 90], settings.skipOutroSeconds)))}
        >
          {settings.skipOutroLabel}
        </InlineButton>
        <span className="text-xs text-muted-foreground">
          {settings.skipOutroSeconds > 0
            ? `片尾前 ${settings.skipOutroSeconds} 秒当作播完，配合自动连播直接进下一集`
            : "不开：片尾照常播完"}
        </span>
      </div>
      <div className="flex flex-wrap items-center gap-2 border-t px-4 py-3 text-sm">
        <span className="w-24 text-muted-foreground">开机自启</span>
        <InlineButton disabled={busy} onClick={() => update("startOnBoot", settings.startOnBoot ? "0" : "1")}>
          {settings.startOnBoot ? "开" : "关"}
        </InlineButton>
        <span className="text-xs text-muted-foreground">
          {settings.startOnBoot
            ? "电视开机后自动待机接收：手机不用先打开电视端应用"
            : "需要先在电视上打开本应用，手机才能找到它"}
        </span>
      </div>
    </SectionCard>
  )
}
