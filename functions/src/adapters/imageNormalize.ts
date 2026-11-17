/**
 * Every provider's picture becomes a delivered JPEG, in one of two ways:
 *
 * - **Passed through untouched** when it already is what the apps need: a well-formed JPEG
 *   (SOI, a baseline, extended or progressive 8-bit frame of 1 or 3 components, EOI at the end)
 *   of at most IMAGE.passThroughMaxEdge pixels on its longer side and IMAGE.passThroughMaxBytes
 *   bytes. Its header is read here (readJpegInfo), nothing is decoded and sharp is never loaded:
 *   Vertex's 1K 4:3 picture (1200 x 896, about 200-260 KB) takes this path. Its embedded metadata
 *   (Google's AI-provenance marks, SynthID's C2PA manifest and the like) is kept, on purpose:
 *   nothing of the person is in it.
 * - **Re-encoded** otherwise (another format, larger or heavier): decoded and written again as a
 *   JPEG of at most IMAGE.maxEdge pixels on its longer side, sRGB, no metadata, at
 *   IMAGE.jpegQuality. A picture that cannot be decoded is refused.
 *
 * sharp (libvips) is loaded on the first re-encode only, so that no other function, and not the
 * pass-through path, pays for it. It ships prebuilt binaries for linux-x64 (glibc), which Cloud
 * Functions 2nd gen runs on; no install script or build step is needed. Its own cache is off, it
 * uses one thread, and re-encodes run one after another (normalizeImage's queue), so the 256 MiB
 * instance holds one decoded picture (about 4 MB) at a time.
 */
import { IMAGE } from "../constants";

export interface NormalizedImage {
  mimeType: "image/jpeg";
  data: Buffer;
  width: number;
  height: number;
  /** True when the provider's bytes are delivered as they came (logs only). */
  passedThrough: boolean;
}

/** A picture that could not be read or re-encoded (a fixed message, never the bytes). */
export class ImageNormalizeError extends Error {}

type Sharp = typeof import("sharp");
let sharpModule: Promise<Sharp> | undefined;

function loadSharp(): Promise<Sharp> {
  sharpModule ??= import("sharp").then((m) => {
    const sharp = ((m as unknown as { default?: Sharp }).default ?? m) as Sharp;
    sharp.cache(false);
    sharp.concurrency(1);
    return sharp;
  });
  return sharpModule;
}

/** The formats a provider may answer in; anything else is refused without decoding. */
function sniff(bytes: Buffer): "jpeg" | "png" | "webp" | null {
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return "jpeg";
  if (bytes.length >= 8 && bytes.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) return "png";
  if (bytes.length >= 12 && bytes.toString("latin1", 0, 4) === "RIFF" && bytes.toString("latin1", 8, 12) === "WEBP") return "webp";
  return null;
}

/** SOF markers of the frames every phone decodes: baseline, extended sequential, progressive (Huffman). */
const PLAIN_SOF: ReadonlySet<number> = new Set([0xc0, 0xc1, 0xc2]);
/** Every other SOF marker (lossless, hierarchical, arithmetic): never passed through. */
const OTHER_SOF: ReadonlySet<number> = new Set([0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf]);

/**
 * The size of a well-formed JPEG, read from its markers without decoding: SOI, segments up to
 * the first frame header, which must be 8-bit with 1 (grey) or 3 (YCbCr) components, and EOI as
 * the last two bytes. Null for anything else (the picture is then re-encoded, or refused when it
 * cannot be decoded).
 */
export function readJpegInfo(bytes: Buffer): { width: number; height: number } | null {
  const n = bytes.length;
  if (n < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8) return null;
  if (bytes[n - 2] !== 0xff || bytes[n - 1] !== 0xd9) return null;
  let pos = 2;
  while (pos + 4 <= n) {
    if (bytes[pos] !== 0xff) return null;
    let marker = bytes[pos + 1];
    // Fill bytes: any number of 0xFF before the marker.
    while (marker === 0xff && pos + 2 < n) {
      pos++;
      marker = bytes[pos + 1];
    }
    if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
      pos += 2;
      continue;
    }
    // EOI or the scan before any frame header, or a second SOI: not a JPEG we pass through.
    if (marker === 0xd9 || marker === 0xda || marker === 0xd8) return null;
    if (pos + 4 > n) return null;
    const length = bytes.readUInt16BE(pos + 2);
    if (length < 2 || pos + 2 + length > n) return null;
    if (PLAIN_SOF.has(marker)) {
      if (length < 8) return null;
      const precision = bytes[pos + 4];
      const height = bytes.readUInt16BE(pos + 5);
      const width = bytes.readUInt16BE(pos + 7);
      const components = bytes[pos + 9];
      if (precision !== 8 || (components !== 1 && components !== 3) || width === 0 || height === 0) return null;
      if (length !== 8 + 3 * components) return null;
      return { width, height };
    }
    if (OTHER_SOF.has(marker)) return null;
    pos += 2 + length;
  }
  return null;
}

/** Whether a provider's JPEG is delivered as it came (see the file comment). */
function passThrough(bytes: Buffer): { width: number; height: number } | null {
  if (bytes.length > IMAGE.passThroughMaxBytes) return null;
  const info = readJpegInfo(bytes);
  if (!info) return null;
  if (Math.max(info.width, info.height) > IMAGE.passThroughMaxEdge) return null;
  return info;
}

/**
 * One picture is decoded at a time per instance: the provider calls may run in parallel, their
 * re-encoding queues here, so a recipe never holds several decoded pictures in the 256 MiB
 * instance at once. A passed-through picture never waits in it.
 */
let queue: Promise<unknown> = Promise.resolve();

export function normalizeImage(bytes: Buffer): Promise<NormalizedImage> {
  if (bytes.length === 0 || bytes.length > IMAGE.maxInputBytes) return Promise.reject(new ImageNormalizeError("image size out of bounds"));
  const format = sniff(bytes);
  if (!format) return Promise.reject(new ImageNormalizeError("image format not accepted"));
  const kept = format === "jpeg" ? passThrough(bytes) : null;
  if (kept) return Promise.resolve({ mimeType: "image/jpeg", data: bytes, width: kept.width, height: kept.height, passedThrough: true });
  const run = queue.then(() => reencode(bytes));
  queue = run.catch(() => undefined);
  return run;
}

async function reencode(bytes: Buffer): Promise<NormalizedImage> {
  const sharp = await loadSharp();
  try {
    const { data, info } = await sharp(bytes, { limitInputPixels: IMAGE.maxInputPixels, failOn: "error", animated: false })
      .rotate()
      .resize({ width: IMAGE.maxEdge, height: IMAGE.maxEdge, fit: "inside", withoutEnlargement: true })
      .toColourspace("srgb")
      .flatten({ background: "#ffffff" })
      .jpeg({ quality: IMAGE.jpegQuality, chromaSubsampling: "4:2:0" })
      .toBuffer({ resolveWithObject: true });
    if (data.length > IMAGE.maxOutputBytes) throw new ImageNormalizeError("image too large after re-encoding");
    return { mimeType: "image/jpeg", data, width: info.width, height: info.height, passedThrough: false };
  } catch (e) {
    if (e instanceof ImageNormalizeError) throw e;
    throw new ImageNormalizeError("image could not be decoded");
  }
}
