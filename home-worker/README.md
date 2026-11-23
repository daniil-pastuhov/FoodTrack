# Home server

Writes the recipe text of **the owner's own FoodTracker test accounts** on this computer, with the
Claude Code CLI (`claude -p`) under the owner's own Claude subscription. The phone talks to the
deployed Cloud Functions as always, from anywhere (mobile data, release builds); for a listed
tester, `generateRecipe` calls this server over https (through a tunnel) and waits for its answer.
If the computer is off, asleep, busy or too slow, or anything fails, the normal provider writes
the recipe (functions/README.md "Home worker"). Nothing runs on Google's side while nobody listed
asks for a recipe, so the setup costs nothing while idle.

**For the owner's own accounts only.** Anthropic's terms do not allow using a personal Claude
subscription to serve other people. Put only your own uids on the tester list; never anyone else's.

## What it serves, and to whom

One endpoint, `POST /v1/text`; everything else is `404` (no health check, no files, no version).
A request is answered only when it carries **both**:

- `Authorization: Bearer <ID token>`: a Google-signed OpenID Connect ID token, checked here with
  `node:crypto` against Google's public keys (`https://www.googleapis.com/oauth2/v3/certs`, cached
  for their `Cache-Control` max-age, fetched again once for an unknown key id, at most once a
  minute): RS256 only, issuer `https://accounts.google.com` or `accounts.google.com`, audience equal
  to `publicUrl` exactly, unexpired (a minute's clock skew), `email_verified` true and `email` equal
  to `allowedServiceAccount`. Keys that can't be fetched refuse everything.
- `X-Worker-Secret: <HOME_WORKER_SECRET>`, compared in constant time.

Both are checked before a byte of the body is read; every failure is the same `401`, after a 1 s
pause. Then: a body over 256 KiB `413`, not JSON or not the expected shape `400`, another job
still running `429`, the CLI too slow `504`, the CLI failing or an answer over 200,000 characters
`502`, else `200 {text, model}`. If the caller goes away, the CLI is stopped at once.

**What this guarantees**: only code running as the functions' service account in the owner's Google
project (`recipe-functions@bestbefore-f9ee1.iam.gserviceaccount.com`, which `generateRecipe` runs as)
**and** holding the shared secret gets an answer; a stolen secret alone, or a Google ID token of any
other account or for any other address, gets `401`. **What it doesn't**: the tunnel's address is
public, so anyone can reach it and get `401` / `404`, and the tunnel provider sees the traffic's
size and timing. Allowing only Google's addresses isn't possible without paying for it: Cloud
Functions have no fixed outbound IP unless their traffic is sent through a VPC with Cloud NAT and a
reserved static IP (https://docs.cloud.google.com/run/docs/configuring/static-outbound-ip), all
billed; not done.

How it runs the CLI: `claude -p` with the job's system prompt from a temporary file, its user turn
on stdin, its JSON schema (`--json-schema`), a fixed `--model`, no tools (`--tools ""`), none of
your customizations (`--safe-mode`: no hooks, plugins, MCP servers or CLAUDE.md) and no saved
session; never through a shell. One line per job (kind, time, size, model), never a prompt unless
`logPrompts` is on: the prompts hold your pantry and allergens. The temporary folder is deleted
after each job.

## Install on Windows (D:\FoodTrackerServer)

1. **Node.js 18 or later** (the LTS installer from nodejs.org). Check: `node -v`.
2. **Claude Code**, the native installer (PowerShell): `irm https://claude.ai/install.ps1 | iex`.
   It installs `claude.exe`; check with `where claude`. (An npm install gives `claude.cmd`, which
   the server can't start without a shell: set `claude` in config.json to the full path of a
   `claude.exe` instead.)
3. **Sign in with your subscription**, once: run `claude` and log in, or `claude setup-token` (a
   long-lived token for a computer nobody sits at; set it as your user's environment variable
   `CLAUDE_CODE_OAUTH_TOKEN`, e.g. `setx CLAUDE_CODE_OAUTH_TOKEN <token>`). Check:
   `echo hi | claude -p --model sonnet`.
4. **Copy** `server.mjs` and `config.example.json` into `D:\FoodTrackerServer` (no `npm install`,
   no build).
5. **A tunnel** (below) that forwards its public https address to `http://127.0.0.1:8787`.
6. **config.json** in `D:\FoodTrackerServer`, from `config.example.json` (git-ignored; keep it
   private). An environment variable wins over the file:

   | key | env var | meaning |
   |---|---|---|
   | `publicUrl` | `HOME_WORKER_PUBLIC_URL` | the tunnel's https address, exactly as in `config/runtime.homeWorker.url` (the ID token's audience) |
   | `allowedServiceAccount` | `HOME_WORKER_ALLOWED_SA` | `recipe-functions@bestbefore-f9ee1.iam.gserviceaccount.com` |
   | `secret` | `HOME_WORKER_SECRET` | the shared secret (below) |
   | `host` / `port` | `HOME_WORKER_HOST` / `HOME_WORKER_PORT` | where it listens: `127.0.0.1` and `8787` (keep 127.0.0.1: the tunnel runs on this computer) |
   | `model` | `HOME_WORKER_MODEL` | `sonnet` (default), `opus`, `haiku` or a full model id |
   | `effort` | `HOME_WORKER_EFFORT` | optional: `low` … `max`; lower is faster |
   | `claude` | `CLAUDE_BIN` | the CLI: `claude` on the PATH, or a full path to `claude.exe` |
   | `maxJobMs` | `HOME_WORKER_MAX_JOB_MS` | the most one job may take (default 130000); the function's own wait cuts it shorter |
   | `logPrompts` | `HOME_WORKER_LOG_PROMPTS=1` | print each prompt (debugging only: allergens) |

7. **Run it**: `node D:\FoodTrackerServer\server.mjs`. A plain recipe with Sonnet took 7 to 55 s
   end to end in testing; the function waits 90 s by default (`npm run admin -- home-worker wait
   recipe <ms>`, at most 120 s; menus at most 30 s, so most menus go to the normal provider).

### Start it with Windows (Task Scheduler)

In PowerShell (as your own user, not Administrator):

```powershell
$a = New-ScheduledTaskAction -Execute "C:\Program Files\nodejs\node.exe" -Argument "D:\FoodTrackerServer\server.mjs" -WorkingDirectory "D:\FoodTrackerServer"
$t = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$s = New-ScheduledTaskSettingsSet -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero)
Register-ScheduledTask -TaskName "FoodTracker home server" -Action $a -Trigger $t -Settings $s
```

It runs while you are logged on (it needs your Claude login). In Windows' power settings, never
sleep while plugged in. To stop it: `Disable-ScheduledTask -TaskName "FoodTracker home server"`.

## A tunnel

The server only listens on `127.0.0.1`; a tunnel on the same computer gives it a stable public
https address without opening a port on the router. Either of these works; the server doesn't care
which. Set the address it gives as `publicUrl` here and with `home-worker url` (below).

### Tailscale Funnel

Free on every Tailscale plan, the Personal one included; public ports 443, 8443 and 10000 only;
"non-configurable bandwidth limits"; needs Tailscale 1.38.3 or later, MagicDNS and HTTPS on the
tailnet, which the first `tailscale funnel` sets up
(https://tailscale.com/kb/1223/funnel, https://tailscale.com/kb/1311/tailscale-funnel).

1. Install Tailscale for Windows and sign in (https://tailscale.com/download).
2. In a terminal:

   ```
   tailscale funnel --bg --https=443 8787
   tailscale funnel status
   ```

   `--bg` keeps it on across restarts. The address is `https://<computer name>.<tailnet>.ts.net`
   (shown by `status`). Off again: `tailscale funnel --https=443 off`.

### Cloudflare Tunnel

Needs a domain whose DNS is on Cloudflare (a free Cloudflare account and plan are enough). Quick
Tunnels (`trycloudflare.com`) need no account but get a new random address every start and are
"for testing and development", so they don't suit this
(https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/trycloudflare/).

The simplest is a tunnel managed in the dashboard
(https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/get-started/create-remote-tunnel/):
Networking › Tunnels › Create a tunnel, choose Windows, run the installation command it shows (it
installs `cloudflared` as a Windows service with the tunnel's token), then Routes › Add route ›
Published application: a subdomain of your domain, Service URL `http://localhost:8787`. The
address is `https://<subdomain>.<your domain>`.

Or from the command line, with `cloudflared` installed
(https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/local-management/create-local-tunnel/):

```
cloudflared tunnel login
cloudflared tunnel create foodtracker-home
cloudflared tunnel route dns foodtracker-home recipes.example.com
```

with `%USERPROFILE%\.cloudflared\config.yml`:

```yaml
url: http://localhost:8787
tunnel: <the tunnel's UUID>
credentials-file: C:\Users\<you>\.cloudflared\<the tunnel's UUID>.json
```

and `cloudflared tunnel run foodtracker-home` (or install it as a service).

## The secret

Created once in Secret Manager (functions/README.md "Home worker"):

```bash
openssl rand -base64 48 | tr -d '\n' | gcloud secrets create HOME_WORKER_SECRET --data-file=- --project=bestbefore-f9ee1
gcloud secrets add-iam-policy-binding HOME_WORKER_SECRET --project=bestbefore-f9ee1 \
  --member=serviceAccount:recipe-functions@bestbefore-f9ee1.iam.gserviceaccount.com --role=roles/secretmanager.secretAccessor
gcloud secrets versions access latest --secret=HOME_WORKER_SECRET --project=bestbefore-f9ee1
```

Copy the value into `config.json`. To rotate: `gcloud secrets versions add HOME_WORKER_SECRET
--data-file=-` with a new value, update `config.json` and restart the server (the functions read the
new version within 10 minutes; until then the server answers `401` and the normal provider writes).

## Whose recipes go here

From the functions folder, with your gcloud login (`gcloud auth application-default login`):

```bash
npm run admin -- home-worker url https://your-laptop.your-tailnet.ts.net --apply --project bestbefore-f9ee1
npm run admin -- home-worker add <uid> --apply --project bestbefore-f9ee1   # your own account's uid (Firebase console › Authentication)
npm run admin -- home-worker on --apply --project bestbefore-f9ee1
npm run admin -- home-worker list --project bestbefore-f9ee1                # on/off, the address (shortened), waits, the uids
```

Without `--apply` each change is a dry run. Changes reach every instance within 30 s.

## Turning it off

`npm run admin -- home-worker off --apply --project bestbefore-f9ee1`: nobody's recipe is sent here
any more (the list and the address are kept). Stop the server itself with Ctrl+C or by disabling the
scheduled task, and the tunnel with `tailscale funnel --https=443 off` or by stopping `cloudflared`.

## Tests

`node --test test/` in this folder: the token and secret checks with locally made keys, the size
caps, one job at a time, the CLI stopped when the caller leaves (a stand-in CLI, `test/fake-claude.mjs`;
macOS or Linux). Not run by CI.
