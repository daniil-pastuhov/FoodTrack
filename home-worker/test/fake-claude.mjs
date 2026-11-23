#!/usr/bin/env node
// A stand-in for the Claude Code CLI in the server's tests: FAKE_CLAUDE picks what it does.
import { writeFileSync } from "node:fs";
let input = "";
process.stdin.on("data", (d) => (input += d));
process.stdin.on("end", () => {
  const mode = process.env.FAKE_CLAUDE ?? "ok";
  if (process.env.FAKE_CLAUDE_PID_FILE) writeFileSync(process.env.FAKE_CLAUDE_PID_FILE, String(process.pid));
  const args = process.argv.slice(2);
  const schema = JSON.parse(args[args.indexOf("--json-schema") + 1]);
  if (mode === "slow") return setTimeout(() => undefined, 60_000);
  if (mode === "fail") {
    process.stderr.write("boom\n");
    process.exit(1);
  }
  const text = mode === "huge" ? "x".repeat(200_001) : undefined;
  process.stdout.write(JSON.stringify({
    type: "result", subtype: "success", is_error: false,
    ...(text ? { result: text } : { structured_output: { echo: input, schemaType: schema.type, tools: args[args.indexOf("--tools") + 1] } }),
    modelUsage: { "fake-claude-1": {} },
  }));
});
