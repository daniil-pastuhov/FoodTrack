#!/usr/bin/env python3
"""Applies the recipe backend's log-based metrics and alert policies (functions/README.md, "Alerts").

The files beside this script are the source of truth: metrics/*.json (Cloud Logging log-based
metrics, LogMetric resources) and policies/*.json (Cloud Monitoring alert policies, without
notification channels). Policies are matched to the live project by displayName, metrics by name.

Dry run by default: validates the files, checks every log filter against the project with a
read-only `gcloud logging read`, and prints what --apply would create or update. Nothing is
written without --apply. Re-running after --apply prints "unchanged" for everything. An --apply
goes on past an item that fails, then lists every failure and exits 1.

The notification channel is never in the files: pass --channel (its resource name or id), or
--channel-name (display name; default "Recipe backend alerts (owner)"). Channels are listed by
name, display name and type only; their labels (the address) are never read into output.

    python3 functions/alerts/alerts.py --project bestbefore-f9ee1            # dry run
    python3 functions/alerts/alerts.py --project bestbefore-f9ee1 --apply    # create / update
    python3 functions/alerts/alerts.py --check-files                         # offline file checks only

Needs gcloud with the beta component, logged in as an account that may read logs and edit Cloud
Monitoring (roles/monitoring.editor and roles/logging.configWriter; the dry run needs only viewer
roles). --account picks a gcloud account other than the active one.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_CHANNEL_NAME = "Recipe backend alerts (owner)"
# Cloud Monitoring's AlertPolicy.Severity: CRITICAL, ERROR, WARNING (WARNING is the lowest; there is
# no INFO, and SEVERITY_UNSPECIFIED is not accepted here). The API refuses anything else on create.
SEVERITIES = {"CRITICAL", "ERROR", "WARNING"}
# Label extractors per log-match condition: at most this many (no documented cap was found for
# log-based alerting policies; 10 is the log-based metrics' label limit, kept as this files' own).
MAX_LABEL_EXTRACTORS = 10
# Documentation limits (AlertPolicy.Documentation): content 8,192 characters and 10,240 bytes; a
# subject is cut to 255 bytes after the variables are filled in, so its own text stays well under;
# up to 3 links.
DOC_CONTENT_CHARS = 8192
DOC_CONTENT_BYTES = 10240
SUBJECT_CHARS = 255
DOC_LINKS = 3
LABEL_KEY = re.compile(r"^[a-z][a-z0-9_]{0,62}$")
EXTRACTOR = re.compile(r"^(EXTRACT\(jsonPayload\.[A-Za-z_][A-Za-z0-9_]*\)|REGEXP_EXTRACT\(jsonPayload\.[A-Za-z_][A-Za-z0-9_]*, *\"[^\"]+\"\))$")
VARIABLE = re.compile(r"\$\{log\.extracted_label\.([^}]*)\}")
# Fields of a live policy or metric that the server owns: never compared, never sent.
SERVER_FIELDS = {"name", "creationRecord", "mutationRecord", "createTime", "updateTime", "metricDescriptor.name",
                 "metricDescriptor.type", "metricDescriptor.displayName", "metricDescriptor.monitoredResourceTypes"}


class Fail(Exception):
    pass


def load(kind):
    out = []
    folder = os.path.join(HERE, kind)
    for f in sorted(os.listdir(folder)):
        if f.endswith(".json"):
            with open(os.path.join(folder, f), encoding="utf-8") as fh:
                out.append((f, json.load(fh)))
    return out


def log_filters(policy):
    return [c["conditionMatchedLog"]["filter"] for c in policy.get("conditions", []) if "conditionMatchedLog" in c]


def validate(metrics, policies):
    """Local checks of the files' shape; raises Fail with every problem found."""
    problems = []
    raw = json.dumps([m for _, m in metrics] + [p for _, p in policies])
    if "@" in raw:
        problems.append("a file contains '@': no address or account belongs in these files")
    metric_names = set()
    for f, m in metrics:
        for key in ("name", "description", "filter", "metricDescriptor"):
            if key not in m:
                problems.append(f"metrics/{f}: missing {key}")
        if m.get("name") and f != f"{m['name']}.json":
            problems.append(f"metrics/{f}: file name must be <name>.json")
        metric_names.add(m.get("name"))
        labels = {l["key"] for l in m.get("metricDescriptor", {}).get("labels", [])}
        if set(m.get("labelExtractors", {})) != labels:
            problems.append(f"metrics/{f}: labelExtractors and metricDescriptor.labels name different labels")
    names = set()
    for f, p in policies:
        where = f"policies/{f}"
        name = p.get("displayName", "")
        if not name.startswith("Recipe: "):
            problems.append(f"{where}: displayName must start with 'Recipe: '")
        if name in names:
            problems.append(f"{where}: duplicate displayName {name!r}")
        names.add(name)
        if "notificationChannels" in p:
            problems.append(f"{where}: notificationChannels is set by the script, not the file")
        if p.get("severity") not in SEVERITIES:
            problems.append(f"{where}: severity {p.get('severity')!r} must be one of {sorted(SEVERITIES)}")
        if p.get("combiner") not in ("OR", "AND"):
            problems.append(f"{where}: combiner must be OR or AND")
        doc = p.get("documentation", {})
        content = doc.get("content") or ""
        if not content:
            problems.append(f"{where}: documentation.content is required")
        if len(content) > DOC_CONTENT_CHARS or len(content.encode("utf-8")) > DOC_CONTENT_BYTES:
            problems.append(f"{where}: documentation.content is over {DOC_CONTENT_CHARS} characters or {DOC_CONTENT_BYTES} bytes")
        if len(VARIABLE.sub("", doc.get("subject", ""))) > SUBJECT_CHARS:
            problems.append(f"{where}: documentation.subject is over {SUBJECT_CHARS} characters before its variables")
        if len(doc.get("links", [])) > DOC_LINKS:
            problems.append(f"{where}: documentation.links holds at most {DOC_LINKS}")
        extracted = set()
        for c in p.get("conditions") or []:
            if "labelExtractors" in c.get("conditionThreshold", {}):
                problems.append(f"{where}: labelExtractors belong only in a conditionMatchedLog")
            ex = (c.get("conditionMatchedLog") or {}).get("labelExtractors") or {}
            if len(ex) > MAX_LABEL_EXTRACTORS:
                problems.append(f"{where}: {len(ex)} label extractors, at most {MAX_LABEL_EXTRACTORS}")
            for key, expr in ex.items():
                if not LABEL_KEY.match(key) or key == "timestamp":
                    problems.append(f"{where}: label key {key!r} must be lower case letters, digits and _ (and never timestamp)")
                if not isinstance(expr, str) or not EXTRACTOR.match(expr) or "timestamp" in expr:
                    problems.append(f"{where}: label {key!r}: the extractor must be EXTRACT(jsonPayload.<field>) or REGEXP_EXTRACT(jsonPayload.<field>, \"<regex>\"), never the timestamp")
            extracted |= set(ex)
        for used in VARIABLE.findall(content + "\n" + doc.get("subject", "")):
            if used not in extracted:
                problems.append(f"{where}: ${{log.extracted_label.{used}}} names no label extractor of this policy")
        conds = p.get("conditions") or []
        if not conds:
            problems.append(f"{where}: no conditions")
        matched = [c for c in conds if "conditionMatchedLog" in c]
        if matched and len(conds) != 1:
            problems.append(f"{where}: a log-match policy has exactly one condition")
        if matched and "notificationRateLimit" not in p.get("alertStrategy", {}):
            problems.append(f"{where}: a log-match policy needs alertStrategy.notificationRateLimit")
        for c in conds:
            if not c.get("displayName"):
                problems.append(f"{where}: every condition needs a displayName")
            ct = c.get("conditionThreshold")
            if ct:
                metric = next((n for n in metric_names if f"logging.googleapis.com/user/{n}" in ct.get("filter", "")), None)
                if not metric:
                    problems.append(f"{where}: threshold condition on a metric not in metrics/")
                for key in ("comparison", "thresholdValue", "duration", "aggregations"):
                    if key not in ct:
                        problems.append(f"{where}: conditionThreshold missing {key}")
        for flt in log_filters(p):
            if 'resource.type="cloud_run_revision"' not in flt:
                problems.append(f"{where}: log filters must name resource.type=\"cloud_run_revision\"")
    if problems:
        raise Fail("\n".join(problems))


def run(args, gcloud_base, check=True):
    cmd = ["gcloud", *args, *gcloud_base]
    res = subprocess.run(cmd, capture_output=True, text=True)
    # gcloud's messages can name the account; no address ever reaches the output.
    res.stderr = re.sub(r"[\w.+-]+@[\w-]+(\.[\w-]+)+", "<account>", res.stderr)
    if check and res.returncode != 0:
        raise Fail(f"gcloud {' '.join(args[:4])} failed: {res.stderr.strip()[-600:]}")
    return res


def gjson(args, gcloud_base):
    out = run([*args, "--format=json"], gcloud_base).stdout
    return json.loads(out or "[]")


def flat(d, prefix=""):
    """Dotted paths of a JSON value, for comparing the parts the file sets."""
    if isinstance(d, dict):
        out = {}
        for k, v in d.items():
            out.update(flat(v, f"{prefix}{k}."))
        return out
    if isinstance(d, list):
        out = {}
        for i, v in enumerate(d):
            out.update(flat(v, f"{prefix}{i}."))
        out[f"{prefix}#"] = len(d)
        return out
    return {prefix[:-1]: d}


def same(a, b):
    if isinstance(a, (int, float)) and isinstance(b, (int, float)) and not isinstance(a, bool):
        return float(a) == float(b)
    return a == b


def diff(desired, live):
    """Paths the file sets whose live value differs (or is missing)."""
    lv = flat(live)
    return [k for k, v in flat(desired).items() if not same(v, lv.get(k))]


def resolve_channel(opts, gcloud_base):
    channels = gjson(["beta", "monitoring", "channels", "list"], gcloud_base)
    # Only these three fields are ever looked at or printed; labels (the address) are not.
    slim = [{"name": c["name"], "displayName": c.get("displayName", ""), "type": c.get("type", "")} for c in channels]
    if opts.channel:
        want = opts.channel if "/" in opts.channel else f"projects/{opts.project}/notificationChannels/{opts.channel}"
        found = [c for c in slim if c["name"] == want]
    else:
        found = [c for c in slim if c["displayName"] == opts.channel_name]
    if len(found) != 1:
        listing = "\n".join(f"  {c['name']}  {c['displayName']!r}  {c['type']}" for c in slim) or "  (none)"
        raise Fail(
            f"no single notification channel matches {'--channel ' + opts.channel if opts.channel else repr(opts.channel_name)}.\n"
            f"Channels in the project:\n{listing}\n"
            "Create an email channel in the console (Monitoring > Alerting > Edit notification channels; type your address "
            "there yourself), then pass its id with --channel or its display name with --channel-name.")
    return found[0]


def threshold_metrics(policy):
    """Names of the log-based metrics a policy's threshold conditions read."""
    out = set()
    for c in policy.get("conditions", []):
        m = re.search(r"logging\.googleapis\.com/user/([\w.-]+)", (c.get("conditionThreshold") or {}).get("filter", ""))
        if m:
            out.add(m.group(1))
    return out


def apply_plan(todo, gcloud_base, tmp, runner=None):
    """Creates or updates every planned item; one failure does not stop the others.

    Metrics go first, since a threshold policy needs its metric; a policy whose metric failed is
    skipped. Returns the failures as (kind, name, file, message), in the order they happened."""
    runner = runner or run
    failures, failed_metrics = [], set()
    for action, kind, name, f, body, live, _ in sorted(todo, key=lambda x: x[1] != "metric"):
        path = os.path.join(tmp, f)
        try:
            if kind == "metric":
                with open(path, "w", encoding="utf-8") as fh:
                    json.dump({k: v for k, v in body.items() if k != "name"}, fh)
                runner(["logging", "metrics", action, name, f"--config-from-file={path}"], gcloud_base)
            else:
                missing = sorted(threshold_metrics(body) & failed_metrics)
                if missing:
                    raise Fail(f"skipped: its metric {', '.join(missing)} failed above")
                with open(path, "w", encoding="utf-8") as fh:
                    json.dump(body, fh)
                if action == "create":
                    runner(["beta", "monitoring", "policies", "create", f"--policy-from-file={path}"], gcloud_base)
                else:
                    runner(["beta", "monitoring", "policies", "update", live["name"], f"--policy-from-file={path}"], gcloud_base)
        except Fail as e:
            if kind == "metric":
                failed_metrics.add(name)
            failures.append((kind, name, f, str(e)))
            print(f"  FAILED to {action} {kind} {name!r} ({f})")
            continue
        print(f"  {action}d {kind} {name!r}")
    return failures


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--project")
    ap.add_argument("--check-files", action="store_true", help="only the local checks of the files (offline; used by the tests)")
    ap.add_argument("--account", help="gcloud account to use (default: the active one)")
    ap.add_argument("--channel", help="notification channel resource name or numeric id")
    ap.add_argument("--channel-name", default=DEFAULT_CHANNEL_NAME, help=f"channel display name (default {DEFAULT_CHANNEL_NAME!r})")
    ap.add_argument("--apply", action="store_true", help="create or update; without it nothing is written")
    ap.add_argument("--skip-filter-check", action="store_true", help="do not run the read-only `gcloud logging read` checks")
    opts = ap.parse_args()
    if opts.check_files:
        metrics, policies = load("metrics"), load("policies")
        validate(metrics, policies)
        print(f"Files: {len(metrics)} metric(s), {len(policies)} polic(ies); local checks passed.")
        return 0
    if not opts.project:
        ap.error("--project is required (unless --check-files)")
    base = [f"--project={opts.project}", "--quiet"] + ([f"--account={opts.account}"] if opts.account else [])

    metrics = load("metrics")
    policies = load("policies")
    validate(metrics, policies)
    print(f"Files: {len(metrics)} metric(s), {len(policies)} polic(ies); local checks passed.")

    channel = resolve_channel(opts, base)
    print(f"Notification channel: {channel['displayName']!r} ({channel['type']}, {channel['name'].rsplit('/', 1)[-1]})")

    if not opts.skip_filter_check:
        print("Log filters (read-only `gcloud logging read`, last 30 days):")
        checks = [(f"metrics/{f}", m["filter"]) for f, m in metrics] + [(f"policies/{f}", flt) for f, p in policies for flt in log_filters(p)]
        for where, flt in checks:
            res = run(["logging", "read", flt, "--freshness=30d", "--limit=1", "--format=value(timestamp)"], base, check=False)
            if res.returncode != 0:
                raise Fail(f"{where}: the filter is refused by Cloud Logging: {res.stderr.strip()[-400:]}")
            last = res.stdout.strip()
            print(f"  ok  {where}: {'last match ' + last if last else 'no match in 30 days'}")

    live_metrics = {m["name"]: m for m in gjson(["logging", "metrics", "list"], base)}
    live_policies = {}
    for p in gjson(["beta", "monitoring", "policies", "list"], base):
        live_policies.setdefault(p.get("displayName", ""), []).append(p)

    plan = []
    for f, m in metrics:
        live = live_metrics.get(m["name"])
        if not live:
            plan.append(("create", "metric", m["name"], f, m, None, []))
        else:
            d = diff({k: v for k, v in m.items() if k != "name"}, live)
            plan.append(("update" if d else "unchanged", "metric", m["name"], f, m, live, d))
    for f, p in policies:
        lives = live_policies.get(p["displayName"], [])
        if len(lives) > 1:
            plan.append(("skip", "policy", p["displayName"], f, p, None, [f"{len(lives)} live policies share this name; remove the extra ones in the console"]))
            continue
        live = lives[0] if lives else None
        channels = sorted(set((live or {}).get("notificationChannels", [])) | {channel["name"]})
        desired = {**p, "notificationChannels": channels}
        if not live:
            plan.append(("create", "policy", p["displayName"], f, desired, None, []))
            continue
        d = diff(desired, live)
        plan.append(("update" if d else "unchanged", "policy", p["displayName"], f, desired, live, d))

    print("Plan:")
    for action, kind, name, f, _, _, d in plan:
        print(f"  {action:9} {kind:6} {name!r}  ({f})" + (f"\n            differs at: {', '.join(d[:8])}{' …' if len(d) > 8 else ''}" if d else ""))
    todo = [x for x in plan if x[0] in ("create", "update")]
    if not opts.apply:
        print(f"Dry run: {len(todo)} change(s) would be made. Nothing was written. Re-run with --apply to make them.")
        return 0

    with tempfile.TemporaryDirectory() as tmp:
        failures = apply_plan(todo, base, tmp)
    if failures:
        print(f"Applied {len(todo) - len(failures)} of {len(todo)} change(s); {len(failures)} failed:", file=sys.stderr)
        for kind, name, f, msg in failures:
            print(f"  {kind} {name!r} ({f}): {msg}", file=sys.stderr)
        print("Fix them and re-run: what was applied shows as unchanged.", file=sys.stderr)
        return 1
    print(f"Applied {len(todo)} change(s).")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Fail as e:
        print(f"error: {e}", file=sys.stderr)
        sys.exit(1)
