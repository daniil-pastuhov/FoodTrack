/**
 * The owner's report emails through the owner's own Gmail (README "Report emails"): SMTP to
 * smtp.gmail.com on port 465 with implicit TLS (TLS 1.2 or later, the certificate checked), signed in
 * with a Gmail app password, from the owner's address to the same address. Both come from Secret
 * Manager (REPORT_EMAIL, REPORT_SMTP_PASSWORD), bound only to the function that sends.
 *
 * One message per call and no retry: nodemailer itself never retries, and this class sends no
 * second time. Before the message it checks the sign-in on a connection of its own (verify), so a
 * failure there is certainly "not sent"; a failure while the message itself is being handed over
 * is reported as `maybeSent` unless the server refused it outright. No error text, server reply or
 * address leaves this file: only the fixed MailFailure codes.
 */
import { REPORT_EMAIL } from "../constants";
import { MailError, type MailFailure, type Mailer, type ReportMail } from "../ports";

/** A plain address check (one @, no spaces or angle brackets): the value is the owner's own, set by hand. */
const ADDRESS = /^[^\s@<>,;"]+@[^\s@<>,;"]+\.[^\s@<>,;"]+$/;

/**
 * The mailer for the two secrets' values, or null when either is missing, empty or not usable
 * ("not configured": the reports are still made and stored; README "Report emails").
 */
export function gmailMailerOf(address: string | undefined | null, appPassword: string | undefined | null): Mailer | null {
  const a = (address ?? "").trim();
  // Google shows app passwords in groups of four with spaces; the spaces are not part of it.
  const p = (appPassword ?? "").replace(/\s+/g, "");
  if (!ADDRESS.test(a) || a.length > 254 || p.length < 8) return null;
  return new GmailSmtpMailer(a, p);
}

/** nodemailer's error codes to ours; never its message or the server's reply. */
export function mailFailureOf(e: unknown, stage: "verify" | "send"): MailError {
  const code = (e as { code?: unknown } | null)?.code;
  const responseCode = (e as { responseCode?: unknown } | null)?.responseCode;
  let reason: MailFailure = "ERROR";
  if (code === "EAUTH" || code === "ENOAUTH") reason = "AUTH_FAILED";
  else if (code === "ETIMEDOUT") reason = "TIMEOUT";
  else if (code === "ECONNECTION" || code === "ESOCKET" || code === "EDNS" || code === "ETLS" || code === "EREQUIRETLS") reason = "CONNECTION";
  else if (code === "EENVELOPE" || code === "EMESSAGE" || code === "EPROTOCOL" || code === "EMAXLIMIT" || (typeof responseCode === "number" && responseCode >= 400)) reason = "REJECTED";
  // The server answering no (envelope or message refused) did not take it; a broken or timed-out
  // connection during the send might have, after the message was handed over.
  const refused = reason === "REJECTED" || reason === "AUTH_FAILED";
  return new MailError(reason, stage === "send" && !refused);
}

export class GmailSmtpMailer implements Mailer {
  constructor(
    private readonly address: string,
    private readonly appPassword: string,
  ) {}

  async send(mail: ReportMail): Promise<void> {
    // Loaded on the first email only, never at a cold start of the other functions.
    const { createTransport } = await import("nodemailer");
    const transport = createTransport({
      host: REPORT_EMAIL.smtpHost,
      port: REPORT_EMAIL.smtpPort,
      secure: true,
      auth: { user: this.address, pass: this.appPassword },
      tls: { minVersion: "TLSv1.2", rejectUnauthorized: true, servername: REPORT_EMAIL.smtpHost },
      connectionTimeout: REPORT_EMAIL.connectionTimeoutMs,
      greetingTimeout: REPORT_EMAIL.greetingTimeoutMs,
      socketTimeout: REPORT_EMAIL.socketTimeoutMs,
      dnsTimeout: REPORT_EMAIL.dnsTimeoutMs,
      pool: false,
      logger: false,
      debug: false,
      disableFileAccess: true,
      disableUrlAccess: true,
    });
    try {
      try {
        await transport.verify();
      } catch (e) {
        throw mailFailureOf(e, "verify");
      }
      try {
        await transport.sendMail({
          from: { name: REPORT_EMAIL.brand + " reports", address: this.address },
          to: this.address,
          subject: mail.subject,
          text: mail.text,
          html: mail.html,
          disableFileAccess: true,
          disableUrlAccess: true,
        });
      } catch (e) {
        throw mailFailureOf(e, "send");
      }
    } finally {
      transport.close();
    }
  }
}
