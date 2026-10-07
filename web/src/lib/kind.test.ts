import { describe, expect, it } from "vitest"
import { formatCheckedAt, hostOf, kindLabel, kindTone, probeState, probeText, recommendSummary } from "./kind"

describe("kind helpers", () => {
  it("labels and tones known kinds", () => {
    expect(kindLabel("live")).toBe("直播")
    expect(kindLabel("vod")).toBe("点播")
    expect(kindLabel("drama")).toBe("短剧")
    expect(kindLabel("")).toBe("其他")
    expect(kindTone("live").badge).toContain("sky")
    expect(kindTone("unknown").badge).toContain("border-border")
  })

  it("derives probe state without inventing success", () => {
    expect(probeState(null)).toBe("unchecked")
    expect(probeState(null, true)).toBe("checking")
    expect(probeState({ id: "a", ok: false } as never)).toBe("failed")
    expect(probeState({ id: "a", ok: true } as never)).toBe("ok")
  })

  it("describes probe results including failures", () => {
    expect(probeText(null)).toBe("未检测")
    expect(probeText(null, true)).toBe("检测中…")
    expect(probeText({ id: "a", ok: true, detail: "540 个频道", latencyMs: 320 } as never))
      .toBe("540 个频道 · 320 ms")
    expect(probeText({ id: "a", ok: false, error: "HTTP 404", httpStatus: 404 } as never))
      .toBe("HTTP 404")
  })

  it("formats timestamps and hosts defensively", () => {
    expect(formatCheckedAt(0)).toBe("")
    expect(formatCheckedAt(new Date(2026, 9, 7, 9, 5).getTime())).toBe("10-07 09:05")
    expect(hostOf("https://cdn.jsdelivr.net/gh/bestK/iptv@main/iptv.m3u")).toBe("cdn.jsdelivr.net")
    expect(hostOf("not a url")).toBe("not a url")
  })

  it("counts added and pending recommendations", () => {
    expect(recommendSummary([{ kind: "live", added: true }, { kind: "vod", added: false }]))
      .toEqual({ total: 2, added: 1, pending: 1 })
  })
})
