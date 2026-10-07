#!/usr/bin/env node
/**
 * Re-probes every entry of app/src/main/assets/sources/recommended.json and prints a table.
 *
 * Use it before a release (or when a curated source stops working) to keep the bundled list
 * honest: the JSON is what the app ships, and "verified by the maintainer on <date>" has to be a
 * real measurement rather than a guess. The probe mirrors what the app itself does, including the
 * MACCMS drama class and the metadata-catalog search endpoint.
 *
 *   node tools/check-recommended-sources.mjs [id ...]
 */
import { readFile } from "node:fs/promises"
import { URL } from "node:url"

const ASSET = new URL("../app/src/main/assets/sources/recommended.json", import.meta.url)
const TIMEOUT_MS = 25_000
const UA = "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
const SEARCH_KEYWORD = "重生"

const { items, verifiedAt } = JSON.parse(await readFile(ASSET, "utf8"))
const wanted = process.argv.slice(2)
const targets = wanted.length ? items.filter((item) => wanted.includes(item.id)) : items

const results = []
for (const item of targets) results.push(await probe(item))

let failures = 0
for (const row of results) {
  if (!row.ok) failures += 1
  console.log(`${row.ok ? "OK  " : "FAIL"} ${row.item.kind.padEnd(5)} ${row.item.id.padEnd(18)} ${row.detail}`)
  console.log(`     ${row.item.url}`)
}
console.log(`\n${targets.length - failures}/${targets.length} reachable · bundled list verified ${verifiedAt}`)
process.exit(failures > 0 ? 1 : 0)

async function probe(item) {
  const url = requestUrl(item)
  const started = Date.now()
  try {
    const response = await fetch(url, {
      headers: { "user-agent": UA, accept: "application/json,text/plain,*/*" },
      redirect: "follow",
      signal: AbortSignal.timeout(TIMEOUT_MS),
    })
    const body = await response.text()
    const latency = Date.now() - started
    if (!response.ok) return { item, ok: false, detail: `HTTP ${response.status} (${latency} ms)` }
    const described = describe(item, body)
    const suffix = `${latency} ms · ${response.headers.get("content-type") || "-"}`
    return described.ok
      ? { item, ok: true, detail: `${described.detail} · ${suffix}` }
      : { item, ok: false, detail: `${described.detail} · ${suffix}` }
  } catch (error) {
    return { item, ok: false, detail: `${error.name}: ${error.message}` }
  }
}

export function requestUrl(item) {
  if (item.kind !== "drama") return item.url
  const base = item.url.split("?")[0].replace(/\/+$/, "")
  if (base.includes("api.php/provide/vod")) {
    // MACCMS API: the configured drama class is exactly what the app will load.
    return `${base}?ac=detail&pg=1${item.categoryId ? `&t=${item.categoryId}` : ""}`
  }
  // Metadata catalog: only a real search proves the JSON contract still answers.
  return `${base}/api/search?q=${encodeURIComponent(SEARCH_KEYWORD)}`
}

export function describe(item, body) {
  const text = body.trim()
  if (item.kind === "live") {
    const m3u = (text.match(/^#EXTINF/gm) || []).length
    const txt = text.split("\n").filter((line) => /^[^#].+,\s*(?:https?:|rtmp|\/\/)/.test(line)).length
    const count = m3u || txt
    return count > 0
      ? { ok: true, detail: `${count} channels` }
      : { ok: false, detail: "not an m3u/txt playlist" }
  }
  let data
  try {
    data = JSON.parse(text.replace(/^\uFEFF/, ""))
  } catch {
    return { ok: false, detail: "not JSON" }
  }
  if (Array.isArray(data.list)) {
    return data.list.length > 0
      ? { ok: true, detail: `list=${data.list.length} total=${data.total ?? "-"}` }
      : { ok: false, detail: "empty list" }
  }
  if (item.kind === "drama") {
    const listed = Array.isArray(data.items) ? data.items.length : 0
    const total = Number(data.total ?? listed)
    return total > 0
      ? { ok: true, detail: `${total} dramas` }
      : { ok: false, detail: `catalog has no dramas (keys=${Object.keys(data).slice(0, 5).join(",")})` }
  }
  const parts = []
  if (Array.isArray(data.sites)) parts.push(`sites=${data.sites.length}`)
  if (Array.isArray(data.urls)) parts.push(`urls=${data.urls.length}`)
  if (Array.isArray(data.storeHouse)) parts.push(`storeHouse=${data.storeHouse.length}`)
  if (Array.isArray(data.lives)) parts.push(`lives=${data.lives.length}`)
  return parts.length > 0
    ? { ok: true, detail: parts.join(" ") }
    : { ok: false, detail: `unexpected shape (keys=${Object.keys(data).slice(0, 5).join(",")})` }
}
