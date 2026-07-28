import type { Source } from "./api"

function health(source: Source) {
  if (source.error || source.searchError) return 2
  return source.searchableSiteCount > 0 ? 0 : 1
}

export function rankLeafSources(sources: Source[]) {
  return sources
    .map((source, index) => ({ source, index }))
    .filter(({ source }) => source.enabled && source.kind !== "warehouse" && source.searchableSiteCount > 0)
    .sort((left, right) => {
      const healthDifference = health(left.source) - health(right.source)
      if (healthDifference !== 0) return healthDifference
      const leftLatency = left.source.latencyMs > 0 ? left.source.latencyMs : Number.MAX_SAFE_INTEGER
      const rightLatency = right.source.latencyMs > 0 ? right.source.latencyMs : Number.MAX_SAFE_INTEGER
      if (leftLatency !== rightLatency) return leftLatency - rightLatency
      return left.index - right.index
    })
    .map(({ source }) => source)
}

export function selectPreferredSource(ranked: Source[], currentId: string, manual: boolean) {
  if (manual && ranked.some((source) => source.id === currentId)) return currentId
  return ranked[0]?.id ?? ""
}
