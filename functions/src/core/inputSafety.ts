/**
 * Normalisation and validation of the free-text names a client sends (pantry item names and
 * allergen names): the only user-controlled text that reaches the model. Pure: no I/O.
 *
 * A name is accepted only if, after normalisation, it is shaped like a short product name: 1-40
 * characters, at most 8 words, each word at most 28 characters, made of Latin or Cyrillic letters
 * (the scripts of the app's ten languages), ASCII digits, spaces, hyphens, and a few punctuation
 * marks in the positions product names use them (`15%`, `0,5`, `1.5`, `1/2`, `Jerry's`). On top of
 * that, structural patterns that product names do not have but injected text does (homoglyph
 * mixing inside a word, encoded payloads, repeated tokens, spaced-out letters) and a short list of
 * words about the model itself (prompt, instructions, ignore, system...) are refused as
 * "suspicious". README "Prompt injection defences" explains why this is a narrowing, not a proof:
 * a plain-language instruction made of ordinary words can still pass, which is why the output is
 * checked on its own (core/outputSafety.ts).
 */
import { NAME_MAX_LENGTH } from "../constants";

/** Why a name was refused. Fixed codes, safe to log; never the name itself. */
export type InputRule =
  | "TYPE"
  | "EMPTY"
  | "TOO_LONG"
  | "CHARACTERS"
  | "SCRIPT"
  | "WORDS"
  | "WORD_LENGTH"
  | "MARKS"
  | "MIXED_SCRIPT"
  | "ENCODED"
  | "REPEATED"
  | "SPACED_LETTERS"
  | "INSTRUCTION";

/**
 * Rules that an honest client following CONTRACT.md does not trip by cleaning names, and that look
 * like an attempt to smuggle text past the model's instructions. They count against the per-account
 * daily rejection limit (`rejectedPromptsPerDay`); the others are plain malformed input.
 */
export const SUSPICIOUS_INPUT_RULES: ReadonlySet<InputRule> = new Set<InputRule>([
  "MARKS", "MIXED_SCRIPT", "ENCODED", "REPEATED", "SPACED_LETTERS", "INSTRUCTION",
]);

export type NameCheck = { ok: true; name: string } | { ok: false; rule: InputRule };

export const MAX_WORDS_PER_NAME = 8;
export const MAX_CHARS_PER_WORD = 28;
/** Raw input longer than this is refused before any normalisation work is done on it. */
const RAW_MAX_LENGTH = 200;

const LATIN = /\p{Script=Latin}/u;
const CYRILLIC = /\p{Script=Cyrillic}/u;
const LETTER = /\p{L}/u;
const MARK = /\p{M}/u;
const DIGIT = /[0-9]/;

/** Apostrophe look-alikes, folded to U+0027 so `Jerry’s` and `Jerry's` are the same name. */
const APOSTROPHES = /[\u2018\u2019\u201B\u02BC\u02B9\u0060\u00B4\u2032]/g;
/** Dash look-alikes (hyphen, non-breaking hyphen, figure/en/em dash, minus), folded to U+002D. */
const DASHES = /[\u2010-\u2015\u2212\uFE58\uFE63\uFF0D]/g;
/**
 * Removed outright: format characters (zero-width space/joiners, bidi embeddings, overrides and
 * isolates, BOM, soft hyphen, tag characters), variation selectors, and the Hangul fillers and
 * other default-ignorable letters that render as nothing.
 */
const INVISIBLE = /[\p{Cf}\uFE00-\uFE0F\u{E0100}-\u{E01EF}\u115F\u1160\u3164\uFFA0]|[\u180B-\u180F]|\u034F/gu;
/** Controls and every kind of space or line break become one ordinary space. */
const SPACES = /[\p{Cc}\p{Zs}\p{Zl}\p{Zp}]+/gu;

/** Lower case, diacritics removed (Cyrillic й, ё, ї fold to и, е, і), for comparisons only. */
export function fold(s: string): string {
  return s.normalize("NFD").replace(/\p{Mn}/gu, "").toLowerCase().normalize("NFC");
}

const HOMOGLYPHS: Readonly<Record<string, string>> = {
  а: "a", в: "b", е: "e", к: "k", м: "m", н: "h", о: "o", р: "p", с: "c", т: "t", у: "y", х: "x",
  і: "i", ј: "j", ѕ: "s", ԁ: "d", ӏ: "l", ԛ: "q", ԝ: "w", ү: "y", һ: "h", ʏ: "y",
};
const LEET: Readonly<Record<string, string>> = { "0": "o", "1": "i", "3": "e", "4": "a", "5": "s", "7": "t" };

/** A word as Latin look-alikes: Cyrillic homoglyphs mapped, and digits mapped when the word also has letters. */
function skeleton(word: string): string {
  const f = fold(word);
  const leet = /\p{L}/u.test(f) && /[0-9]/.test(f);
  return Array.from(f, (c) => HOMOGLYPHS[c] ?? (leet ? (LEET[c] ?? c) : c)).join("");
}

/**
 * Words about the model and the conversation rather than food, in the app's ten languages
 * (folded). A word is refused when it starts with one of STEMS or equals one of WORDS. Kept short
 * and specific on purpose: every entry was checked against real product names, and a longer list
 * would refuse real food (Italian "ai", Spanish "cuenta", Swedish "system"-brands) while still
 * being easy to paraphrase around.
 */
const STEMS = [
  "ignor", "игнор", "ігнор", "disregard", "jailbreak", "overrid",
  "instruc", "instruk", "istruz", "anweisung", "инструкц", "інструкц",
  "prompt", "promt", "промпт", "промт", "systemprompt", "систем",
  "assistant", "assistent", "asystent", "ассистент", "асистент",
  "chatgpt", "chatbot", "openai", "llm",
];
const WORDS = new Set([
  "system", "systeme", "systemet",
  "forget", "vergiss", "vergessen", "oublie", "oubliez", "olvida", "olvide", "dimentica", "zapomnij",
  "glem", "glom", "забудь", "забудьте", "pretend", "roleplay", "bypass",
  "json", "schema", "html", "http", "https", "www", "markdown", "output", "gpt",
]);

function isInstructionWord(word: string): boolean {
  const candidates = new Set([fold(word), skeleton(word)]);
  for (const w of candidates) {
    if (WORDS.has(w)) return true;
    if (STEMS.some((s) => w.startsWith(s))) return true;
  }
  return false;
}

/** Encoded-looking words: long hex runs, or many case or letter/digit switches inside a word. */
function looksEncoded(part: string): boolean {
  if (part.length >= 12 && /^[0-9a-f]+$/i.test(part) && /[0-9]/.test(part) && /[a-f]/i.test(part)) return true;
  // Mixed case with several capitals inside the word (base64): "SWdub3Jl..." but not "McDonald's".
  const inner = Array.from(part).slice(1);
  if (/\p{Ll}/u.test(part) && inner.filter((c) => /\p{Lu}/u.test(c)).length >= 3) return true;
  let caseSwitches = 0;
  let letterDigitSwitches = 0;
  let digitInsideLetters = 0;
  const chars = Array.from(part);
  for (let i = 1; i < chars.length; i++) {
    const a = chars[i - 1];
    const b = chars[i];
    if (a !== a.toUpperCase() && b !== b.toLowerCase()) caseSwitches++;
    if ((DIGIT.test(a) && LETTER.test(b)) || (LETTER.test(a) && DIGIT.test(b))) letterDigitSwitches++;
    if (i + 1 < chars.length && LETTER.test(a) && DIGIT.test(b) && LETTER.test(chars[i + 1])) digitInsideLetters++;
  }
  return caseSwitches >= 3 || letterDigitSwitches >= 5 || digitInsideLetters >= 2;
}

/**
 * Normalises a name: NFKC (fullwidth and other compatibility forms fold to plain letters and
 * digits, ligatures split, `½` becomes `1/2`), apostrophe and dash look-alikes folded, invisible
 * characters removed, every control or space character turned into a space, runs of spaces and of
 * hyphens collapsed, trimmed. Then validates it as described at the top of this file.
 */
export function normalizeName(raw: unknown): NameCheck {
  if (typeof raw !== "string") return { ok: false, rule: "TYPE" };
  if (raw.length > RAW_MAX_LENGTH) return { ok: false, rule: "TOO_LONG" };
  const name = raw
    .normalize("NFKC")
    .replace(/⁄/g, "/")
    .replace(APOSTROPHES, "'")
    .replace(DASHES, "-")
    .replace(INVISIBLE, "")
    .replace(SPACES, " ")
    .replace(/-{2,}/g, "-")
    .trim()
    // NFKC again: removing invisible characters can bring a base letter and a mark together.
    .normalize("NFKC");
  if (name.length === 0) return { ok: false, rule: "EMPTY" };
  if (name.length > NAME_MAX_LENGTH) return { ok: false, rule: "TOO_LONG" };

  const chars = Array.from(name);
  for (let i = 0; i < chars.length; i++) {
    const c = chars[i];
    const prev = chars[i - 1] ?? "";
    const next = chars[i + 1] ?? "";
    if (LETTER.test(c)) {
      if (!LATIN.test(c) && !CYRILLIC.test(c)) return { ok: false, rule: "SCRIPT" };
      continue;
    }
    if (MARK.test(c)) {
      // One combining mark on a letter (what NFKC could not compose); a stack is "Zalgo" text.
      if (!LETTER.test(prev)) return { ok: false, rule: "MARKS" };
      continue;
    }
    if (DIGIT.test(c) || c === " " || c === "-") continue;
    if (c === "'" && LETTER.test(prev) && LETTER.test(next)) continue;
    // "&" between letters or as its own word ("M&S", "Ben & Jerry's"); never first or last.
    if (c === "&" && i > 0 && i < chars.length - 1 && (LETTER.test(prev) || prev === " ") && (LETTER.test(next) || next === " ")) continue;
    // An abbreviation's full stop inside a name ("Dr. Oetker", "n.5"), never at the end.
    if (c === "." && LETTER.test(prev) && (next === " " || DIGIT.test(next))) continue;
    if (c === "%" && (DIGIT.test(prev) || (prev === " " && DIGIT.test(chars[i - 2] ?? "")))) continue;
    if ((c === "," || c === "." || c === "/") && DIGIT.test(prev) && DIGIT.test(next)) continue;
    if (/\p{N}/u.test(c)) return { ok: false, rule: "SCRIPT" };
    return { ok: false, rule: "CHARACTERS" };
  }

  const words = name.split(" ");
  if (words.length > MAX_WORDS_PER_NAME) return { ok: false, rule: "WORDS" };
  const counts = new Map<string, number>();
  let singleLetterRun = 0;
  for (const word of words) {
    const parts = word.split("-").filter((p) => p.length > 0);
    for (const part of parts) {
      if (Array.from(part).length > MAX_CHARS_PER_WORD) return { ok: false, rule: "WORD_LENGTH" };
      if (LATIN.test(part) && CYRILLIC.test(part)) return { ok: false, rule: "MIXED_SCRIPT" };
      if (looksEncoded(part)) return { ok: false, rule: "ENCODED" };
      if (/(.)\1{4,}/u.test(part)) return { ok: false, rule: "REPEATED" };
      if (isInstructionWord(part)) return { ok: false, rule: "INSTRUCTION" };
    }
    if (parts.length > 1 && isInstructionWord(parts.join(""))) return { ok: false, rule: "INSTRUCTION" };
    const key = fold(word);
    if (key.length >= 2) {
      const n = (counts.get(key) ?? 0) + 1;
      counts.set(key, n);
      if (n >= 3) return { ok: false, rule: "REPEATED" };
    }
    singleLetterRun = Array.from(word).length === 1 && LETTER.test(word) ? singleLetterRun + 1 : 0;
    if (singleLetterRun >= 4) return { ok: false, rule: "SPACED_LETTERS" };
  }
  // Spaced or hyphenated letters that spell a meta word ("i g n o r e", "p-r-o-m-p-t").
  if (isInstructionWord(name.replace(/[ -]/g, "")) && words.some((w) => Array.from(w).length <= 2)) {
    return { ok: false, rule: "INSTRUCTION" };
  }
  return { ok: true, name };
}
