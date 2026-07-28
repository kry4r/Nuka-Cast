import { describe, expect, it } from "vitest"
import type { Source } from "./api"
import { rankLeafSources, selectPreferredSource } from "./source-ranking"

function source(overrides: Partial<Source>): Source {
  return {
    id: "source",
    name: "仓库",
    url: "http://example.test/config.json",
    kind: "single",
    parentId: "",
    enabled: true,
    contentHash: "",
    updatedAt: 0,
    siteCount: 1,
    searchableSiteCount: 1,
    liveCount: 0,
    latencyMs: 100,
    error: "",
    searchError: "",
    ...overrides,
  }
}

describe("warehouse ranking", () => {
  it("selects the fastest healthy leaf", () => {
    const ranked = rankLeafSources([
      source({ id: "parent", kind: "warehouse", latencyMs: 1 }),
      source({ id: "slow", latencyMs: 800 }),
      source({ id: "failed", latencyMs: 5, error: "HTTP 502" }),
      source({ id: "fast", latencyMs: 120 }),
    ])

    expect(ranked.map((item) => item.id)).toEqual(["fast", "slow", "failed"])
    expect(selectPreferredSource(ranked, "", false)).toBe("fast")
  })

  it("keeps original order when warehouse health and latency tie", () => {
    const ranked = rankLeafSources([
      source({ id: "first", parentId: "warehouse", latencyMs: 200 }),
      source({ id: "second", parentId: "warehouse", latencyMs: 200 }),
    ])

    expect(ranked.map((item) => item.id)).toEqual(["first", "second"])
  })

  it("preserves a still-available manual selection", () => {
    const ranked = rankLeafSources([
      source({ id: "fast", latencyMs: 50 }),
      source({ id: "manual", latencyMs: 500 }),
    ])

    expect(selectPreferredSource(ranked, "manual", true)).toBe("manual")
    expect(selectPreferredSource(ranked, "removed", true)).toBe("fast")
  })

  it("keeps following the fastest source until the user selects one", () => {
    const ranked = rankLeafSources([
      source({ id: "new-fast", latencyMs: 25 }),
      source({ id: "initial", latencyMs: 500 }),
    ])

    expect(selectPreferredSource(ranked, "initial", false)).toBe("new-fast")
  })

  it("excludes live-only and fast-failing sources from healthy search choices", () => {
    const ranked = rankLeafSources([
      source({ id: "live", searchableSiteCount: 0, liveCount: 4, latencyMs: 5 }),
      source({ id: "failed", searchError: "最近搜索全部失败", latencyMs: 10 }),
      source({ id: "working", latencyMs: 400 }),
    ])

    expect(ranked.map((item) => item.id)).toEqual(["working", "failed"])
  })
})
