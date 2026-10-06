import { describe, expect, it } from "vitest"
import { dramaFacts, lineLabel, matchLabel, missingLineHint } from "./drama"
import type { DramaItem, DramaLine } from "./api"

function item(overrides: Partial<DramaItem> = {}): DramaItem {
  return {
    providerId: "p1",
    dramaId: "7690192663693233177",
    title: "重生2000：靠山吃山成首富",
    cover: "",
    intro: "",
    remark: "全115集",
    category: "脑洞",
    tags: ["脑洞"],
    episodeCount: 115,
    heat: "43179826",
    status: "finished",
    contentKind: "short_drama",
    ...overrides,
  }
}

describe("drama helpers", () => {
  it("labels match kinds without hiding uncertainty", () => {
    expect(matchLabel("exact")).toBe("完全同名")
    expect(matchLabel("prefix")).toBe("前缀匹配")
    expect(matchLabel("contains")).toBe("包含匹配")
    expect(matchLabel("")).toBe("候选")
  })

  it("builds catalog facts and omits unknown values", () => {
    expect(dramaFacts(item())).toEqual(["共 115 集", "脑洞", "已完结", "热度 43179826"])
    expect(dramaFacts(item({ episodeCount: 0, category: "", status: "", heat: "" }))).toEqual([])
  })

  it("keeps the 19-digit id untouched in labels", () => {
    const line: DramaLine = {
      sourceId: "s1",
      sourceName: "",
      siteKey: "site-a",
      siteName: "片源A",
      vodId: "7690192663693233177",
      name: "重生2000：靠山吃山成首富",
      remarks: "全115集",
      poster: "",
      year: "2026",
      typeName: "短剧",
      matchScore: 100,
      matchKind: "exact",
      episodeHint: "全115集",
    }
    expect(lineLabel(line)).toBe("片源A · 重生2000：靠山吃山成首富 · 全115集")
  })

  it("explains why no playback line was found", () => {
    expect(missingLineHint(false, 0)).toContain("源管理")
    expect(missingLineHint(true, 2)).toContain("2 个片源")
    expect(missingLineHint(true, 0)).toContain("没有找到")
  })
})
