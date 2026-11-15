/**
 * The always-on board (README "The board", "Always on"): the scheduled `redrawBoard` reads the
 * same aggregates as `npm run admin -- board` and writes `board.html` and `board.json` to a
 * private Cloud Storage bucket the owner opens through the authenticated browser URL.
 *
 * It fails closed: before every write it reads the bucket's settings and IAM policy and writes
 * nothing unless public access prevention is enforced, uniform bucket-level access is on and no
 * binding names `allUsers`, `allAuthenticatedUsers` or a whole domain, and never to the default
 * Firebase Storage bucket (its Security Rules are a second way in). It never creates the
 * bucket or changes its policy (the runtime account has no permission to); both are owner setup.
 * Storage is behind the small `BoardBucket` seam so the tests need no Storage emulator.
 */
import type { Firestore } from "firebase-admin/firestore";
import type { Logger } from "../ports";
import { buildBoard } from "./boardData";
import { boardJson, renderBoardHtml, type HostedOptions } from "./boardHtml";
import { readBoardInput } from "./boardReader";

export const BOARD_HTML_OBJECT = "board.html";
export const BOARD_JSON_OBJECT = "board.json";
export const HOSTED_BOARD_DAYS = 30;
export const HOSTED_BOARD: HostedOptions = { everyHours: 1, staleAfterHours: 3 };
/** Every object of the board: never kept by a cache, shown in the browser rather than saved. */
export const BOARD_CACHE_CONTROL = "no-store, private";

/** What the function reads about the bucket before it writes: settings and IAM bindings. */
export interface BucketFacts {
  publicAccessPrevention: string | null;
  uniformBucketLevelAccess: boolean;
  bindings: { role: string; members: string[] }[];
}

export interface BoardObject {
  contentType: string;
  cacheControl: string;
  contentDisposition: string;
  body: string;
}

export interface BoardBucket {
  readonly name: string;
  /** The bucket's facts, or null when it does not exist. Never creates it. */
  facts(): Promise<BucketFacts | null>;
  write(object: string, content: BoardObject): Promise<void>;
}

const BROAD_MEMBER = /^(allUsers|allAuthenticatedUsers|domain:.+)$/;

/** The project's default Firebase Storage buckets: their Security Rules are another way in. */
export const isDefaultFirebaseBucket = (name: string) => /\.(appspot\.com|firebasestorage\.app)$/i.test(name);

export type BucketVerdict =
  | { ok: true }
  | { ok: false; reason: "default_firebase_bucket" | "missing" | "public_access_prevention_not_enforced" | "uniform_access_off" | "public_binding"; role?: string };

/** Pure: is this bucket private enough to write the board to? */
export function judgeBucket(facts: BucketFacts | null): BucketVerdict {
  if (!facts) return { ok: false, reason: "missing" };
  if (facts.publicAccessPrevention !== "enforced") return { ok: false, reason: "public_access_prevention_not_enforced" };
  if (!facts.uniformBucketLevelAccess) return { ok: false, reason: "uniform_access_off" };
  for (const b of facts.bindings) {
    if (b.members.some((m) => BROAD_MEMBER.test(m))) return { ok: false, reason: "public_binding", role: b.role };
  }
  return { ok: true };
}

export class BoardBucketRefused extends Error {
  constructor(readonly reason: string) {
    super(`board bucket refused: ${reason}`);
  }
}

export interface RedrawEnv {
  db: Firestore;
  bucket: BoardBucket;
  project: string;
  now(): number;
  logger: Logger;
  /** Purchases read per run at most (default BOARD_PURCHASES_CAP). */
  purchasesCap?: number;
}

/**
 * One redraw: check the bucket, read and render, check the bucket again just before writing (a
 * policy changed during the read still stops the write), then write the page and the JSON. Logs
 * `board_bucket_public` and throws when the bucket is missing or not private; logs
 * `board_redraw_failed` and throws on any other failure.
 */
export async function redrawBoard(env: RedrawEnv): Promise<{ reads: number; bytes: number }> {
  const bucketName = env.bucket.name;
  const check = async () => {
    const verdict: BucketVerdict = isDefaultFirebaseBucket(bucketName)
      ? { ok: false, reason: "default_firebase_bucket" }
      : judgeBucket(await env.bucket.facts());
    if (!verdict.ok) {
      // The bucket name and the offending role only; never a member (it may be an email).
      env.logger.error("board_bucket_public", { bucket: bucketName, reason: verdict.reason, ...(verdict.role ? { role: verdict.role } : {}) });
      throw new BoardBucketRefused(verdict.reason);
    }
  };
  try {
    await check();
    const nowMs = env.now();
    const input = await readBoardInput(env.db, {
      project: env.project,
      days: HOSTED_BOARD_DAYS,
      nowMs,
      logger: env.logger,
      ...(env.purchasesCap !== undefined ? { purchasesCap: env.purchasesCap } : {}),
    });
    const board = buildBoard(input);
    const html = renderBoardHtml(board, HOSTED_BOARD);
    const json = `${JSON.stringify(boardJson(board), null, 2)}\n`;
    await check();
    const common = { cacheControl: BOARD_CACHE_CONTROL, contentDisposition: "inline" };
    await env.bucket.write(BOARD_HTML_OBJECT, { ...common, contentType: "text/html; charset=utf-8", body: html });
    await env.bucket.write(BOARD_JSON_OBJECT, { ...common, contentType: "application/json; charset=utf-8", body: json });
    const bytes = Buffer.byteLength(html) + Buffer.byteLength(json);
    env.logger.info("board_redrawn", { bucket: bucketName, reads: board.reads, bytes, capped: board.purchasesCapped !== null });
    return { reads: board.reads, bytes };
  } catch (e) {
    if (!(e instanceof BoardBucketRefused)) {
      const err = e as { code?: unknown; message?: unknown };
      env.logger.error("board_redraw_failed", {
        bucket: bucketName,
        code: typeof err.code === "string" || typeof err.code === "number" ? err.code : null,
        error: String(err.message ?? e).slice(0, 200),
      });
    }
    throw e;
  }
}
