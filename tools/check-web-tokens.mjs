#!/usr/bin/env node
/**
 * Verifies that every literal className token used in web/src exists in the built CSS.
 *
 * Tailwind only emits classes it can see, so a rename or a deleted config key can silently drop
 * styling. Run this after `npm run build` in web/ (the script reads app/src/main/assets/web).
 *
 *   node tools/check-web-tokens.mjs
 */
import { readFile, readdir } from "node:fs/promises"
import { join, basename } from "node:path"
import { fileURLToPath, URL } from "node:url"

const root = fileURLToPath(new URL("..", import.meta.url))
const assetsDir = join(root, "app/src/main/assets/web/assets")

const cssFiles = (await readdir(assetsDir)).filter((name) => name.endsWith(".css"))
if (cssFiles.length === 0) {
  console.error("no built css found; run `npm run build` in web/ first")
  process.exit(2)
}

const raw = await readFile(join(assetsDir, cssFiles[0]), "utf8")
// Undo Tailwind's hex escapes (e.g. \32xl -> 2xl) and drop the remaining backslashes so token
// comparison does not depend on CSS escaping rules.
const css = raw.replace(/\\3([0-9])/g, "$1").replace(/\\/g, "")

const used = new Map()
for (const file of await sources(join(root, "web/src"))) {
  const text = await readFile(file, "utf8")
  for (const match of text.matchAll(/className="([^"]+)"/g)) {
    for (const token of match[1].split(/\s+/)) {
      if (!token || token.includes("{") || token.includes("$") || token === "group" || token === "peer") continue
      if (!used.has(token)) used.set(token, new Set())
      used.get(token).add(basename(file))
    }
  }
}

const missing = [...used.keys()].filter((token) => !css.includes(token)).sort()
console.log(`css: ${cssFiles[0]}`)
console.log(`literal className tokens: ${used.size} | missing: ${missing.length}`)
for (const token of missing) console.log(`   - ${token} (${[...used.get(token)].join(", ")})`)
process.exit(missing.length === 0 ? 0 : 1)

async function sources(dir) {
  const found = []
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) found.push(...(await sources(path)))
    else if (entry.name.endsWith(".tsx")) found.push(path)
  }
  return found
}
