/**
 * The shared name fixture (test/fixtures/names.json): the server's verdict on every raw input, and
 * on what the clients send for it. Android (shared ServerNameRuleTest) and iOS
 * (iosApp/NameFixtureCheck) check their cleaners against the same file. Pure: needs no emulator.
 */
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { SUSPICIOUS_INPUT_RULES, normalizeName, type InputRule } from "../src/core/inputSafety";

interface Row {
  input: string;
  lang?: string;
  note?: string;
  clean: string | null;
  allergen: string | null;
  server: string;
  normalized?: string;
  cleanServer?: string;
}

const rows: Row[] = JSON.parse(readFileSync(join(__dirname, "fixtures", "names.json"), "utf8")).names;
const verdict = (name: string) => {
  const r = normalizeName(name);
  return r.ok ? { rule: "ok", name: r.name } : { rule: r.rule as string, name: undefined };
};

describe("shared name fixture", () => {
  test("covers the scripts it promises", () => {
    const langs = new Set(rows.map((r) => r.lang).filter(Boolean));
    for (const l of ["el", "he", "ar", "fa", "hi", "th", "zh-Hans", "zh-Hant", "ja", "ko", "ka", "hy", "vi", "tr"]) {
      expect(langs.has(l), l).toBe(true);
    }
    expect(rows.length).toBeGreaterThanOrEqual(300);
  });

  test.each(rows.map((r) => [r.input, r] as const))("server verdict on %j", (_, row) => {
    const v = verdict(row.input);
    expect(v.rule).toBe(row.server);
    if (v.rule === "ok") expect(v.name).toBe(row.normalized);
  });

  test.each(rows.filter((r) => r.clean !== null).map((r) => [r.clean!, r] as const))(
    "what the clients send for %j is taken as sent, or refused only as suspicious",
    (clean, row) => {
      const v = verdict(clean);
      expect(v.rule).toBe(row.cleanServer);
      if (v.rule === "ok") expect(v.name).toBe(clean);
      else expect(SUSPICIOUS_INPUT_RULES.has(v.rule as InputRule) && v.rule !== "MARKS" && v.rule !== "MIXED_SCRIPT").toBe(true);
    },
  );

  test("an allergen is only ever sent whole: what is sent for it is what is sent for the name", () => {
    for (const row of rows) {
      if (row.allergen !== null) expect(row.allergen).toBe(row.clean);
    }
  });

  test("every real product name in any script reaches the model as the server normalises it", () => {
    for (const row of rows.filter((r) => r.lang)) {
      expect(row.server, row.input).toBe("ok");
      expect(row.clean, row.input).toBe(row.normalized);
    }
  });
});
