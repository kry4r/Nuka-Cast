import type { DramaItem, DramaLine } from "./api"

/** Human label for the backend match kind; keeps "silently matching another drama" visible. */
export function matchLabel(kind: string): string {
  if (kind === "exact") return "完全同名"
  if (kind === "prefix") return "前缀匹配"
  if (kind === "contains") return "包含匹配"
  return "候选"
}

/** Catalog facts rendered as badges; unknown values are omitted instead of guessed. */
export function dramaFacts(item: DramaItem): string[] {
  const facts: string[] = []
  if (item.episodeCount > 0) facts.push(`共 ${item.episodeCount} 集`)
  if (item.category) facts.push(item.category)
  if (item.status === "finished") facts.push("已完结")
  if (item.status === "ongoing" || item.status === "连载中") facts.push("连载中")
  if (item.heat) facts.push(`热度 ${item.heat}`)
  return facts
}

export function lineLabel(line: DramaLine): string {
  return [line.siteName, line.name, line.remarks].filter(Boolean).join(" · ")
}

export function missingLineHint(searched: boolean, failedSites: number): string {
  if (!searched) return "还没有启用可搜索的片源，先在“源管理”添加片源"
  if (failedSites > 0) return `没有匹配线路，且 ${failedSites} 个片源查询失败`
  return "已启用的片源里没有找到这部剧，确认片源包含短剧内容后重试"
}
