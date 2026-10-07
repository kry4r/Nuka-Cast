#!/usr/bin/env node
/**
 * Refreshes app/src/main/resources/com/nukacast/app/net/mozilla_ca_bundle.pem from Mozilla's CA
 * program (published by curl.se).
 *
 * Android 4.x devices ship a 2014-era trust store, so a stale bundle is the difference between
 * "Trust anchor for certification path not found" and a working source. Run this before a release
 * and review the diff; the file is part of the APK.
 *
 *   node tools/update-ca-bundle.mjs
 */
import { writeFile } from "node:fs/promises"
import { fileURLToPath, URL } from "node:url"

const target = fileURLToPath(new URL(
  "../app/src/main/resources/com/nukacast/app/net/mozilla_ca_bundle.pem", import.meta.url))
const source = "https://curl.se/ca/cacert.pem"

const response = await fetch(source, { signal: AbortSignal.timeout(60_000) })
if (!response.ok) {
  console.error(`download failed: HTTP ${response.status}`)
  process.exit(1)
}
const body = await response.text()
const certificates = (body.match(/BEGIN CERTIFICATE/g) || []).length
if (certificates < 50) {
  console.error(`refusing to write: only ${certificates} certificates found`)
  process.exit(1)
}
await writeFile(target, body.replaceAll("\r\n", "\n"), "utf8")
console.log(`wrote ${certificates} certificates to ${target}`)
console.log(body.split("\n").find((line) => line.includes("Certificate data from Mozilla")) || "")
