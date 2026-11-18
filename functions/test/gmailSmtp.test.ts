/**
 * The Gmail SMTP mailer (src/adapters/gmailSmtp.ts, README "Report emails") with nodemailer mocked:
 * what it connects with (465, implicit TLS 1.2+, short timeouts, no pool), From and To the owner's
 * address, no retry, a sign-in checked before the message, and its failures turned into fixed
 * codes that carry neither the address nor the server's reply. No network.
 */
import { gmailMailerOf, mailFailureOf } from "../src/adapters/gmailSmtp";
import { MailError } from "../src/ports";

const calls: { options?: Record<string, unknown>; verify: number; sent: Record<string, unknown>[]; closed: number } = { verify: 0, sent: [], closed: 0 };
let verifyError: unknown = null;
let sendError: unknown = null;

vi.mock("nodemailer", () => ({
  createTransport: (options: Record<string, unknown>) => {
    calls.options = options;
    return {
      verify: async () => {
        calls.verify++;
        if (verifyError) throw verifyError;
        return true;
      },
      sendMail: async (m: Record<string, unknown>) => {
        calls.sent.push(m);
        if (sendError) throw sendError;
        return { messageId: "<x@example.com>" };
      },
      close: () => {
        calls.closed++;
      },
    };
  },
}));

const OWNER = "owner@example.com";
const MAIL = { subject: "BestBefore weekly report · 2026-W46 (9–15 Nov)", text: "plain", html: "<p>html</p>" };
const smtpError = (code: string, extra: Record<string, unknown> = {}) => Object.assign(new Error("535-5.7.8 Username and Password not accepted for " + OWNER), { code, response: "535 5.7.8 " + OWNER, ...extra });

beforeEach(() => {
  calls.options = undefined;
  calls.verify = 0;
  calls.sent = [];
  calls.closed = 0;
  verifyError = null;
  sendError = null;
});

describe("gmailMailerOf: not configured", () => {
  test("missing, empty or unusable values give no mailer", () => {
    for (const [a, p] of [[undefined, undefined], ["", ""], [OWNER, ""], ["", "abcdefghijklmnop"], ["not an address", "abcdefghijklmnop"], [OWNER, "short"], [" " + OWNER + " ", "   "]] as const) {
      expect(gmailMailerOf(a, p), String(a) + "/" + String(p)).toBeNull();
    }
    expect(gmailMailerOf(" " + OWNER + "\n", "abcd efgh ijkl mnop")).not.toBeNull();
  });
});

describe("the SMTP connection and the message", () => {
  test("465 with implicit TLS 1.2+, short timeouts, no pool and no logging; from and to the owner; the sign-in checked first; closed after", async () => {
    await gmailMailerOf(OWNER, "abcd efgh ijkl mnop")!.send(MAIL);
    expect(calls.options).toMatchObject({
      host: "smtp.gmail.com", port: 465, secure: true, pool: false, logger: false, debug: false,
      auth: { user: OWNER, pass: "abcdefghijklmnop" },
      tls: { minVersion: "TLSv1.2", rejectUnauthorized: true, servername: "smtp.gmail.com" },
      connectionTimeout: 10_000, greetingTimeout: 10_000, socketTimeout: 30_000, dnsTimeout: 10_000,
    });
    expect(calls.verify).toBe(1);
    expect(calls.sent).toHaveLength(1);
    expect(calls.sent[0]).toMatchObject({ to: OWNER, from: { address: OWNER }, subject: MAIL.subject, text: "plain", html: "<p>html</p>", disableFileAccess: true, disableUrlAccess: true });
    expect(calls.closed).toBe(1);
  });

  test("a refused sign-in: AUTH_FAILED, certainly not sent, nothing handed over, no retry", async () => {
    verifyError = smtpError("EAUTH");
    const e = await gmailMailerOf(OWNER, "abcdefghijklmnop")!.send(MAIL).catch((x: unknown) => x);
    expect(e).toBeInstanceOf(MailError);
    expect(e).toMatchObject({ reason: "AUTH_FAILED", maybeSent: false });
    expect(calls.sent).toHaveLength(0);
    expect(calls.verify).toBe(1);
    expect(calls.closed).toBe(1);
    expect(JSON.stringify(e) + String(e) + (e as Error).message).not.toContain(OWNER);
  });

  test("Gmail unreachable before the message: CONNECTION or TIMEOUT, not sent", async () => {
    verifyError = smtpError("ECONNECTION");
    await expect(gmailMailerOf(OWNER, "abcdefghijklmnop")!.send(MAIL)).rejects.toMatchObject({ reason: "CONNECTION", maybeSent: false });
    verifyError = smtpError("ETIMEDOUT");
    await expect(gmailMailerOf(OWNER, "abcdefghijklmnop")!.send(MAIL)).rejects.toMatchObject({ reason: "TIMEOUT", maybeSent: false });
  });

  test("the connection breaks while the message is handed over: maybe sent, never retried", async () => {
    sendError = smtpError("ETIMEDOUT");
    await expect(gmailMailerOf(OWNER, "abcdefghijklmnop")!.send(MAIL)).rejects.toMatchObject({ reason: "TIMEOUT", maybeSent: true });
    expect(calls.sent).toHaveLength(1);
    sendError = smtpError("ESOCKET");
    await expect(gmailMailerOf(OWNER, "abcdefghijklmnop")!.send(MAIL)).rejects.toMatchObject({ reason: "CONNECTION", maybeSent: true });
  });

  test("the message refused by Gmail: REJECTED, not sent", async () => {
    sendError = smtpError("EMESSAGE", { responseCode: 552 });
    await expect(gmailMailerOf(OWNER, "abcdefghijklmnop")!.send(MAIL)).rejects.toMatchObject({ reason: "REJECTED", maybeSent: false });
    sendError = smtpError("EENVELOPE", { responseCode: 553 });
    await expect(gmailMailerOf(OWNER, "abcdefghijklmnop")!.send(MAIL)).rejects.toMatchObject({ reason: "REJECTED", maybeSent: false });
  });

  test("mailFailureOf keeps only a fixed code: never the message or the server's reply", () => {
    const e = mailFailureOf(smtpError("EWHATEVER"), "send");
    expect(e).toMatchObject({ reason: "ERROR", maybeSent: true });
    expect(e.message).toBe("mail not sent: ERROR");
    expect(JSON.stringify(e) + e.message + String(e.stack)).not.toContain(OWNER);
    expect(JSON.stringify(e) + e.message).not.toContain("535");
  });
});
