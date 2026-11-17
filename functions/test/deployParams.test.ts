/**
 * Every deploy parameter the functions define has a value in functions/.env.bestbefore-f9ee1
 * (README "Parameters and secrets"). The Firebase CLI ignores a parameter's default when the env
 * file has no line for it: a non-interactive deploy then fails and an interactive one prompts. So
 * a parameter added in code (in src/index.ts, or declared by a provider's adapter in
 * providers/registry.ts) needs its line in that file in the same change.
 */
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { allProviders, providerSecrets } from "../src/providers/registry";

const ROOT = join(__dirname, "..");

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((f) => {
    const p = join(dir, f);
    return statSync(p).isDirectory() ? sources(p) : p.endsWith(".ts") ? [p] : [];
  });
}

/** The env file's parameter names (a line `NAME=` with an empty value counts as set). */
function envNames(): Set<string> {
  const out = new Set<string>();
  for (const line of readFileSync(join(ROOT, ".env.bestbefore-f9ee1"), "utf8").split("\n")) {
    const m = /^([A-Z][A-Z0-9_]*)=/.exec(line.trim());
    if (m) out.add(m[1]);
  }
  return out;
}

/** The two loops in src/index.ts that define the adapters' params and their secrets' params. */
const DYNAMIC = new Set(["p.name", "s.param"]);

function definedInCode(): { literal: string[]; dynamic: string[] } {
  const literal: string[] = [];
  const dynamic: string[] = [];
  for (const file of sources(join(ROOT, "src"))) {
    const text = readFileSync(file, "utf8");
    // defineSecret is Secret Manager, not the env file.
    for (const m of text.matchAll(/\bdefine(String|Int|Boolean|Float|List)\(\s*([^,)]+)/g)) {
      const arg = m[2].trim();
      const lit = /^"([A-Z][A-Z0-9_]*)"$/.exec(arg);
      if (lit) literal.push(lit[1]);
      else dynamic.push(`${file.slice(ROOT.length + 1)}: ${arg}`);
    }
  }
  return { literal, dynamic };
}

describe("deploy parameters", () => {
  test("every parameter defined in code has a value in .env.bestbefore-f9ee1", () => {
    const env = envNames();
    const { literal, dynamic } = definedInCode();
    // A new dynamically named parameter must be added to this test's list of sources.
    expect(dynamic.filter((d) => !DYNAMIC.has(d.slice(d.indexOf(": ") + 2)))).toEqual([]);
    const adapters = allProviders().flatMap((d) => (d.params ?? []).map((p) => p.name));
    const secrets = providerSecrets().map((s) => s.param);
    const all = [...new Set([...literal, ...adapters, ...secrets])];
    expect(literal).toEqual(expect.arrayContaining(["TEXT_PROVIDER", "IMAGE_PROVIDER", "FUNCTIONS_SERVICE_ACCOUNT"]));
    expect(adapters.length).toBeGreaterThan(0);
    expect(all.filter((n) => !env.has(n))).toEqual([]);
  });
});
