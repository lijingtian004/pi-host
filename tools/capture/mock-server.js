#!/usr/bin/env node
/**
 * Mock OpenAI chat-completions server for golden capture.
 * Port 8787. Scenario selection: last user message text matched against
 * scenarios table in ./scenarios.json (regex keys, first match wins).
 */
const http = require("http");
const fs = require("fs");
const path = require("path");

const SCENARIOS_PATH = path.join(__dirname, "scenarios.json");
const LOG_PATH = path.join(__dirname, "requests.log");

let scenarios = [];
try {
	const raw = JSON.parse(fs.readFileSync(SCENARIOS_PATH, "utf8"));
	scenarios = Array.isArray(raw) ? raw : [raw];
} catch {
	scenarios = [];
}

function lastMessage(body) {
	const msgs = body.messages || [];
	return msgs.length ? msgs[msgs.length - 1] : null;
}

function lastUserText(body) {
	for (let i = (body.messages || []).length - 1; i >= 0; i--) {
		const m = body.messages[i];
		if (m.role === "user") {
			if (typeof m.content === "string") return m.content;
			if (Array.isArray(m.content))
				return m.content.filter((p) => p.type === "text").map((p) => p.text).join("\n");
		}
	}
	return "";
}

function pickScenario(body) {
	const last = lastMessage(body);
	const lastRole = last ? last.role : null;
	// tool-result round trip: match scenarios reserved for tool results
	if (lastRole === "tool") {
		for (const s of scenarios) {
			if (s.whenLastRole === "tool" && (s.match === "*" || new RegExp(s.match).test(last.content || "")))
				return s;
		}
		return { response: [{ type: "text", text: "tool-result-received (default)" }] };
	}
	const text = lastUserText(body);
	for (const s of scenarios) {
		if (s.whenLastRole) continue;
		if (s.match === "*" || new RegExp(s.match).test(text)) return s;
	}
	return { response: [{ type: "text", text: "default-mock-reply" }] };
}

function sse(res, obj) {
	res.write(`data: ${JSON.stringify(obj)}\n\n`);
}

const server = http.createServer((req, res) => {
	if (req.method !== "POST" || !req.url.endsWith("/chat/completions")) {
		res.writeHead(404).end();
		return;
	}
	let body = "";
	req.on("data", (c) => (body += c));
	req.on("end", () => {
		let parsed;
		try {
			parsed = JSON.parse(body);
		} catch {
			res.writeHead(400).end();
			return;
		}
		fs.appendFileSync(
			LOG_PATH,
			JSON.stringify({ url: req.url, auth: req.headers.authorization || null, body: parsed }) + "\n",
		);

		const scenario = pickScenario(parsed);
		if (scenario.error) {
			res.writeHead(scenario.error, { "Content-Type": "application/json" });
			res.end(JSON.stringify({ error: { message: scenario.errorMessage || "mock forced failure", type: "mock_error" } }));
			return;
		}
		const model = parsed.model || "mock-1";
		const created = Math.floor(Date.now() / 1000);
		const id = "chatcmpl-mock-" + Math.random().toString(36).slice(2, 10);

		res.writeHead(200, {
			"Content-Type": "text/event-stream",
			"Cache-Control": "no-cache",
			Connection: "keep-alive",
		});

		// optional pre-delay to test streaming windows
		const delay = scenario.delayMs || 0;
		setTimeout(() => {
			sse(res, {
				id,
				object: "chat.completion.chunk",
				created,
				model,
				choices: [{ index: 0, delta: { role: "assistant" }, finish_reason: null }],
			});

			let finish = "stop";
			for (const part of scenario.response || []) {
				if (part.type === "text") {
					// stream text in 2-token chunks to exercise text_delta events
					const words = part.text.split(/(\s+)/);
					for (let i = 0; i < words.length; i += 2) {
						sse(res, {
							id,
							object: "chat.completion.chunk",
							created,
							model,
							choices: [{ index: 0, delta: { content: words.slice(i, i + 2).join("") }, finish_reason: null }],
						});
					}
				} else if (part.type === "thinking") {
					sse(res, {
						id,
						object: "chat.completion.chunk",
						created,
						model,
						choices: [{ index: 0, delta: { reasoning_content: part.thinking }, finish_reason: null }],
					});
				} else if (part.type === "toolCall") {
					finish = "tool_calls";
					sse(res, {
						id,
						object: "chat.completion.chunk",
						created,
						model,
						choices: [
							{
								index: 0,
								delta: {
									tool_calls: [
										{
											index: 0,
											id: part.id || "call_mock_1",
											type: "function",
											function: { name: part.name, arguments: JSON.stringify(part.arguments || {}) },
										},
									],
								},
								finish_reason: null,
							},
						],
					});
				}
			}

			sse(res, {
				id,
				object: "chat.completion.chunk",
				created,
				model,
				choices: [{ index: 0, delta: {}, finish_reason: finish }],
				usage: {
					prompt_tokens: 100,
					completion_tokens: 20,
					total_tokens: 120,
					prompt_tokens_details: { cached_tokens: 0 },
				},
			});
			res.write("data: [DONE]\n\n");
			res.end();
		}, delay);
	});
});

server.listen(8787, "127.0.0.1", () => console.log("mock listening on 127.0.0.1:8787"));
