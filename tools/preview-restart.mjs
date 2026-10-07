#!/usr/bin/env node
/**
 * Restart the local preview server (tools/preview-server.mjs) — without killing
 * unrelated node processes.
 *
 * Why this exists
 *
 * The old habit was `taskkill //F //IM node.exe`, which force-kills *every*
 * process named node.exe on Windows. pi runs on node.exe, so that command killed
 * the pi session issuing it: no crash record, no bash tool result, and the
 * terminal stayed in mouse-reporting mode afterwards. It happened six times.
 *
 * This script kills only the pid that is actually serving the preview port, and
 * refuses to touch a pid whose command line is not the preview server.
 *
 *   node tools/preview-restart.mjs [port]      # default port: 9978
 *
 * Output of the new server goes to .preview/preview-server.log (and its pid to
 * .preview/preview-server.pid).
 */

import { execFileSync, spawn } from "node:child_process"
import { closeSync, mkdirSync, openSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"

const repoRoot = dirname(dirname(fileURLToPath(import.meta.url)))
const serverScript = join(repoRoot, "tools", "preview-server.mjs")
const previewDir = join(repoRoot, ".preview")
const pidFile = join(previewDir, "preview-server.pid")
const logFile = join(previewDir, "preview-server.log")
const port = Number(process.argv[2] || 9978)

const say = (message) => console.log(`preview-restart: ${message}`)

function commandLineOf(pid) {
	try {
		const powershell = join(process.env.SystemRoot ?? "C:\\Windows", "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
		return execFileSync(powershell, ["-NoProfile", "-NonInteractive", "-Command", `(Get-CimInstance Win32_Process -Filter 'ProcessId=${pid}').CommandLine`], { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim()
	} catch {
		return ""
	}
}

function isAlive(pid) {
	try {
		process.kill(pid, 0)
		return true
	} catch {
		return false
	}
}

function isPreviewServer(pid) {
	return /preview-server\.mjs/i.test(commandLineOf(pid))
}

/** Pids listening on the port, from netstat (never from an image-name match). */
function listeningPids() {
	try {
		const output = execFileSync("netstat", ["-ano", "-p", "TCP"], { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] })
		const pids = new Set()
		for (const line of output.split(/\r?\n/)) {
			if (!/LISTENING/i.test(line)) continue
			const columns = line.trim().split(/\s+/)
			const local = columns[1] ?? ""
			if (!local.endsWith(`:${port}`)) continue
			const pid = Number(columns[columns.length - 1])
			if (Number.isInteger(pid) && pid > 0) pids.add(pid)
		}
		return [...pids]
	} catch {
		return []
	}
}

function readPidFile() {
	try {
		const pid = Number(readFileSync(pidFile, "utf8").trim())
		return Number.isInteger(pid) && pid > 0 ? pid : undefined
	} catch {
		return undefined
	}
}

async function waitForServer(timeoutMs = 15000) {
	const deadline = Date.now() + timeoutMs
	while (Date.now() < deadline) {
		try {
			const response = await fetch(`http://127.0.0.1:${port}/api/status`)
			if (response.ok) return true
		} catch {
			/* not up yet */
		}
		await new Promise((resolve) => setTimeout(resolve, 200))
	}
	return false
}

mkdirSync(previewDir, { recursive: true })

// 1. Collect the previous server: the recorded pid and/or whatever listens on the port.
const candidates = new Set()
const recorded = readPidFile()
if (recorded !== undefined && isAlive(recorded)) candidates.add(recorded)

const listeners = listeningPids()
for (const pid of listeners) candidates.add(pid)

let stopped = 0
for (const pid of candidates) {
	if (pid === process.pid) continue
	if (!isAlive(pid)) continue
	if (!isPreviewServer(pid)) {
		say(`refusing to kill pid ${pid}: it is not the preview server (${commandLineOf(pid).slice(0, 120) || "unknown process"})`)
		continue
	}
	try {
		execFileSync("taskkill", ["/PID", String(pid), "/T", "/F"], { stdio: "ignore" })
		say(`stopped preview server pid ${pid}`)
		stopped += 1
	} catch {
		say(`could not stop pid ${pid}`)
	}
}
if (stopped === 0 && listeners.length > 0) {
	say(`port ${port} is still held by a process that is not the preview server — not starting a second one`)
	process.exit(1)
}

try {
	rmSync(pidFile, { force: true })
} catch {
	/* ignore */
}

// 2. Start a fresh one, detached, so it survives this script and the tool call.
const logFd = openSync(logFile, "a")
const child = spawn(process.execPath, [serverScript, String(port)], {
	cwd: repoRoot,
	detached: true,
	stdio: ["ignore", logFd, logFd],
	windowsHide: true,
})
closeSync(logFd)

const tail = () => {
	try {
		return `\n--- ${logFile} ---\n${readFileSync(logFile, "utf8").split(/\r?\n/).slice(-10).join("\n")}`
	} catch {
		return ""
	}
}

// The child writes its own pid file too; this is just the earliest possible value.
await new Promise((resolve) => setTimeout(resolve, 300))
writeFileSync(pidFile, `${child.pid}\n`)
child.unref()

if (!(await waitForServer())) {
	say(`server pid ${child.pid} did not answer on http://127.0.0.1:${port}/api/status${tail()}`)
	process.exit(1)
}
say(`ready: http://localhost:${port}/ (pid ${child.pid}, log ${logFile})`)
