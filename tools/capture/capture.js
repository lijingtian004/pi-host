#!/usr/bin/env node
/**
 * Golden capture driver: spawn pi in RPC mode, send scripted commands, record
 * raw stdout bytes to a golden file. Usage:
 *   node capture.js <outFile> <commandsJsonFile>
 * commandsJsonFile: [{ cmd: {...rpc command...}, waitFor: "agent_settled" | ms }]
 */
const { spawn } = require("child_process");
const fs = require("fs");
const path = require("path");

const [outFile, cmdsFile, extraArgs] = process.argv.slice(2);
if (!outFile || !cmdsFile) {
	console.error("usage: node capture.js <outFile> <commandsJsonFile> [extraCliArgs,comma-separated]");
	process.exit(2);
}
const script = JSON.parse(fs.readFileSync(cmdsFile, "utf8"));
const extra = extraArgs ? extraArgs.split(",").filter(Boolean) : [];

const CLI = "/root/pi/.capture/node_modules/@earendil-works/pi-coding-agent/dist/cli.js";
const env = {
	...process.env,
	HOME: "/root/pi/.capture/home",
	PI_SKIP_VERSION_CHECK: "1",
	NO_COLOR: "1",
};
const cwd = "/root/pi/.capture/work";
fs.mkdirSync(cwd, { recursive: true });

const child = spawn("node", [CLI, "--mode", "rpc", ...extra], { cwd, env, stdio: ["pipe", "pipe", "pipe"] });

const out = fs.createWriteStream(outFile);
let stderrTail = "";
child.stderr.on("data", (c) => {
	stderrTail = (stderrTail + c.toString()).slice(-4000);
	process.stderr.write("[pi-stderr] " + c);
});
child.stdout.on("data", (chunk) => out.write(chunk));
child.on("exit", (code, signal) => {
	out.end();
	console.log(`\n[capture] child exit code=${code} signal=${signal}`);
	if (stderrTail) console.log(`[capture] stderr tail:\n${stderrTail}`);
	process.exit(0);
});

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let lastEvent = null;
function nextEvent(matchType, timeoutMs) {
	// passive: listen for a line containing "type":"<matchType>"
	return new Promise((resolve) => {
		let buf = "";
		const onData = (chunk) => {
			buf += chunk.toString();
			let idx;
			while ((idx = buf.indexOf("\n")) >= 0) {
				const line = buf.slice(0, idx);
				buf = buf.slice(idx + 1);
				if (!line.trim()) continue;
				try {
					const obj = JSON.parse(line);
					if (obj.type === matchType) {
						child.stdout.off("data", onData);
						lastEvent = obj;
						resolve(obj);
						return;
					}
				} catch {}
			}
		};
		child.stdout.on("data", onData);
		setTimeout(() => {
			child.stdout.off("data", onData);
			resolve(null);
		}, timeoutMs);
	});
}

(async () => {
	// let pi boot
	await sleep(1500);
	for (const step of script) {
		if (step.sleepMs) await sleep(step.sleepMs);
		if (step.cmd) {
			let cmdObj = step.cmd;
			// placeholder substitution: "__EXT_ID__" -> last extension_ui_request id
			if (JSON.stringify(cmdObj).includes("__EXT_ID__") && lastEvent && lastEvent.id) {
				cmdObj = JSON.parse(JSON.stringify(cmdObj).replaceAll("__EXT_ID__", lastEvent.id));
			}
			const line = JSON.stringify(cmdObj) + "\n";
			console.log(`[capture] -> ${line.trim()}`);
			child.stdin.write(line);
		}
		if (step.waitForEvent) {
			const ev = await nextEvent(step.waitForEvent, step.timeoutMs || 60000);
			console.log(`[capture] waited for ${step.waitForEvent}: ${ev ? "seen" : "TIMEOUT"}`);
			if (ev && step.afterEventMs) await sleep(step.afterEventMs);
		}
	}
	// graceful shutdown
	await sleep(800);
	child.stdin.end();
})().catch((e) => {
	console.error(e);
	child.kill("SIGKILL");
});
