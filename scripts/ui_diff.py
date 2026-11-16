#!/usr/bin/env python3
"""The UI screenshot diff that CI puts on a pull request (job "ui-screenshots" in
.github/workflows/ci.yml; README "UI screenshot check").

The committed baselines are recorded on macOS, and Robolectric renders slightly differently on
Linux, so CI never compares with them. It renders the pull request's base and its head on the same
Linux runner image and compares those: the base with its own scripts/ui_check.sh --record, the
head in Roborazzi's compare mode against the base's images. This script does the rest:

    scripts/ui_diff.py collect --results DIR --test-xml DIR --baselines DIR --out DIR
                               --base SHA --head SHA [--gradle-status N]
    scripts/ui_diff.py no-base --out DIR --base SHA --head SHA
    scripts/ui_diff.py summary --manifest FILE [--artifact-url URL]
    scripts/ui_diff.py publish (--manifest FILE | --no-android --head SHA) --repo OWNER/NAME --pr N
                               [--run-url URL] [--artifact-url URL]

collect reads Roborazzi's results-summary.json and the JUnit XML of the head run and writes
manifest.json (changed, new, removed, unchanged, crashed) and images/ (reference | diff | new
for a changed screenshot, the picture for a new or removed one). It exits 2 when the run did not
go through (a preview crashed, no results), 0 otherwise: a difference informs, it never fails.

publish keeps one comment per pull request, found by MARKER: with differences it pushes up to
INLINE_LIMIT images to the companion branch IMAGE_BRANCH (pr-<n>/<head sha>/, the PR's older
pushes and closed PRs' directories removed) and writes the comment with them inline, pinned to
the branch commit; without, it rewrites a comment left from an earlier push to say so, and posts
nothing when there never was one; --no-android does the same for a push after which the pull
request no longer touches Android (the diff job then doesn't run). It needs GH_TOKEN with pull-requests: write and contents: write.
"""
import argparse
import base64
import glob
import json
import os
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

MARKER = "<!-- ui-screenshot-diff -->"
IMAGE_BRANCH = "screenshot-diffs"
INLINE_LIMIT = 20
OPEN_LIMIT = 5        # of the inline ones, shown unfolded
NAME_LIST_LIMIT = 100  # names listed beyond the inline ones
NAME_PREFIX = "com.foodtracker.android."
BOT = "github-actions[bot]"


# --- Reading a run -------------------------------------------------------------------------------

def load_entries(results_dir):
    entries = []
    for path in sorted(glob.glob(os.path.join(results_dir, "**", "results-summary.json"), recursive=True)):
        with open(path) as f:
            entries += json.load(f).get("results", [])
    return entries


def failed_tests(xml_dir):
    failed = []
    for path in sorted(glob.glob(os.path.join(xml_dir, "TEST-*.xml"))):
        for case in ET.parse(path).getroot().iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                failed.append(f"{case.get('classname')}.{case.get('name')}")
    return failed


def shot_name(path):
    """The screenshot's name: the baseline file's name without .png."""
    name = os.path.basename(path or "")
    return name[:-4] if name.endswith(".png") else name


def build_manifest(entries, base_names, failed, base, head):
    """What the head run changed against the base's images. A base image the head no longer
    renders is removed; a head screenshot the base has no image of is new."""
    changed, added, seen = [], [], set()
    unchanged = 0
    for e in entries:
        name = shot_name(e.get("golden_file_path") or e.get("actual_file_path"))
        seen.add(name)
        kind = e.get("type")
        if kind == "changed":
            changed.append({"name": name, "diff_percentage": e.get("diff_percentage"),
                            "image": e.get("compare_file_path")})
        elif kind == "added":
            added.append({"name": name, "image": e.get("actual_file_path")})
        elif kind == "unchanged":
            unchanged += 1
    removed = [{"name": n} for n in sorted(set(base_names) - seen)]
    return {
        "base": base, "head": head, "has_base": True,
        "changed": sorted(changed, key=lambda x: x["name"]),
        "added": sorted(added, key=lambda x: x["name"]),
        "removed": removed,
        "unchanged": unchanged,
        "crashed": failed,
    }


def differences(manifest):
    return [("changed", s) for s in manifest.get("changed", [])] + \
           [("new", s) for s in manifest.get("added", [])] + \
           [("removed", s) for s in manifest.get("removed", [])]


def image_file(kind, name):
    return f"{name}_compare.png" if kind == "changed" else f"{name}.png"


def status(manifest):
    if manifest.get("android") is False:
        return "no-android"
    if not manifest.get("has_base"):
        return "no-base"
    return "differences" if differences(manifest) else "none"


# --- Writing it up -------------------------------------------------------------------------------

def short(name):
    return name[len(NAME_PREFIX):] if name.startswith(NAME_PREFIX) else name


def plural(n, word):
    return f"{n} {word}" if n == 1 else f"{n} {word}s"


def headline(manifest):
    c, a, r = (len(manifest.get(k, [])) for k in ("changed", "added", "removed"))
    return (f"**{c} changed, {a} new, {r} removed** "
            f"({manifest.get('unchanged', 0)} unchanged), base `{manifest['base'][:7]}` → head `{manifest['head'][:7]}`")


def describe(kind, shot):
    if kind == "changed" and shot.get("diff_percentage") is not None:
        return f"changed, {shot['diff_percentage'] * 100:.2f} % of pixels"
    return kind


def comment_body(manifest, image_url=None, run_url=None, artifact_url=None):
    """The pull request comment (or, with image_url None, the job summary: names only).
    image_url(kind, name) gives an image's URL, or None for one not uploaded."""
    lines = [MARKER, "### UI screenshot diff", ""]
    state = status(manifest)
    if state == "no-android":
        lines.append(f"No differences: as of `{manifest['head'][:7]}` this pull request no longer changes the "
                     f"Android app, so its screenshots are the base's.")
        return "\n".join(lines) + "\n"
    if state == "no-base":
        lines.append(f"The base `{manifest['base'][:7]}` has no UI screenshot check, so there is nothing "
                     f"to compare this pull request's screenshots with.")
        return "\n".join(lines) + "\n"
    if state == "none":
        lines.append(f"No differences: as of `{manifest['head'][:7]}` every screenshot matches the base "
                     f"`{manifest['base'][:7]}` ({manifest.get('unchanged', 0)} compared).")
        return "\n".join(lines) + "\n"

    diffs = differences(manifest)
    lines += [headline(manifest), ""]
    inline = []
    if image_url:
        for kind, shot in diffs:
            if kind == "removed" or len(inline) >= INLINE_LIMIT:
                continue
            url = image_url(kind, shot["name"])
            if url:
                inline.append((kind, shot, url))
    shown = {s["name"] for _, s, _ in inline}
    for i, (kind, shot, url) in enumerate(inline):
        caption = "reference | diff | new" if kind == "changed" else "new"
        lines += [f"<details{' open' if i < OPEN_LIMIT else ''}><summary><code>{short(shot['name'])}</code>"
                  f" · {describe(kind, shot)}</summary>", "",
                  f'<img src="{url}" alt="{short(shot["name"])}: {caption}" width="100%">', "",
                  "</details>", ""]
    rest = [(k, s) for k, s in diffs if s["name"] not in shown]
    if rest:
        if inline:
            lines += [f"The other {plural(len(rest), 'screenshot')}:", ""]
        for kind, shot in rest[:NAME_LIST_LIMIT]:
            lines.append(f"- `{short(shot['name'])}` · {describe(kind, shot)}")
        if len(rest) > NAME_LIST_LIMIT:
            lines.append(f"- and {len(rest) - NAME_LIST_LIMIT} more")
        lines.append("")
    where = []
    if artifact_url:
        where.append(f"every image is in the [run's artifact]({artifact_url})")
    elif run_url:
        where.append(f"every image is in the [run]({run_url})'s artifact")
    note = ("Both sides are rendered on Linux by CI, not compared with the committed macOS baselines. "
            "If a change is meant, record it with `scripts/ui_check.sh --record` on a Mac and commit the images")
    lines.append(note + ("; " + where[0] if where else "") + ".")
    return "\n".join(lines) + "\n"


# --- Companion branch and comment ----------------------------------------------------------------

def run(cmd, cwd=None, check=True, capture=True):
    result = subprocess.run(cmd, cwd=cwd, check=False, text=True,
                            stdout=subprocess.PIPE if capture else None, stderr=subprocess.STDOUT if capture else None)
    if check and result.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd)} failed ({result.returncode}): {result.stdout}")
    return result


def git_auth(token):
    header = base64.b64encode(f"x-access-token:{token}".encode()).decode()
    return ["-c", f"http.https://github.com/.extraheader=AUTHORIZATION: basic {header}"]


def gh_json(args):
    out = run(["gh", "api", *args]).stdout
    return json.loads(out) if out.strip() else None


def closed_prs(repo, numbers):
    closed = set()
    for n in numbers:
        try:
            if gh_json([f"repos/{repo}/pulls/{n}"]).get("state") == "closed":
                closed.add(n)
        except RuntimeError:
            pass  # not knowable now: kept for the next push to look again
    return closed


def update_branch(repo, token, pr, files=None, head=None, attempts=5):
    """Replaces pr-<pr>/ on IMAGE_BRANCH with files (name -> local path) under pr-<pr>/<head>/,
    or removes it when files is None, and drops the directories of closed pull requests. Returns
    the branch commit, or None when there was nothing to change."""
    auth = git_auth(token)
    with tempfile.TemporaryDirectory() as tmp:
        run(["git", "init", "-q", tmp])
        run(["git", "-C", tmp, "remote", "add", "origin", f"https://github.com/{repo}"])
        run(["git", "-C", tmp, "config", "user.name", BOT])
        run(["git", "-C", tmp, "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com"])
        for attempt in range(attempts):
            fetched = run(["git", *auth, "-C", tmp, "fetch", "-q", "--depth", "1", "origin", IMAGE_BRANCH],
                          check=False).returncode == 0
            if fetched:
                run(["git", "-C", tmp, "checkout", "-q", "-f", "-B", IMAGE_BRANCH, "FETCH_HEAD"])
                run(["git", "-C", tmp, "clean", "-qfdx"])
            elif files is None:
                return None
            else:
                run(["git", "-C", tmp, "checkout", "-q", "--orphan", IMAGE_BRANCH])
                Path(tmp, "README.md").write_text(
                    "Images for the UI screenshot diff comments that CI puts on pull requests "
                    "(scripts/ui_diff.py). One directory per open pull request, removed when it closes.\n")
            numbers = [int(p.name[3:]) for p in Path(tmp).glob("pr-*") if p.name[3:].isdigit()]
            gone = closed_prs(repo, [n for n in numbers if n != pr])
            for n in [pr, *sorted(gone)]:
                shutil.rmtree(Path(tmp, f"pr-{n}"), ignore_errors=True)
            if files:
                dest = Path(tmp, f"pr-{pr}", head)
                dest.mkdir(parents=True)
                for name, src in files.items():
                    shutil.copyfile(src, dest / name)
            run(["git", "-C", tmp, "add", "-A"])
            if run(["git", "-C", tmp, "diff", "--cached", "--quiet"], check=False).returncode == 0:
                return run(["git", "-C", tmp, "rev-parse", "HEAD"]).stdout.strip() if fetched else None
            message = f"PR #{pr}: {'images for ' + head[:7] if files else 'removed'}"
            if gone:
                message += f" (and closed {', '.join('#' + str(n) for n in sorted(gone))})"
            run(["git", "-C", tmp, "commit", "-q", "-m", message])
            if run(["git", *auth, "-C", tmp, "push", "-q", "origin", f"HEAD:refs/heads/{IMAGE_BRANCH}"],
                   check=False).returncode == 0:
                return run(["git", "-C", tmp, "rev-parse", "HEAD"]).stdout.strip()
            print(f"Push to {IMAGE_BRANCH} refused (attempt {attempt + 1}); fetching again", file=sys.stderr)
        raise RuntimeError(f"Could not push to {IMAGE_BRANCH} after {attempts} attempts")


def find_comment(repo, pr):
    pages = json.loads(run(["gh", "api", "--paginate", "--slurp", f"repos/{repo}/issues/{pr}/comments"]).stdout)
    for comment in (c for page in pages for c in page):
        if comment.get("user", {}).get("login") == BOT and MARKER in (comment.get("body") or ""):
            return comment["id"]
    return None


def write_comment(repo, pr, comment_id, body):
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
        json.dump({"body": body}, f)
    try:
        if comment_id:
            run(["gh", "api", "-X", "PATCH", f"repos/{repo}/issues/comments/{comment_id}", "--input", f.name])
        else:
            run(["gh", "api", "-X", "POST", f"repos/{repo}/issues/{pr}/comments", "--input", f.name])
    finally:
        os.unlink(f.name)


def publish(manifest, manifest_dir, repo, pr, token, run_url=None, artifact_url=None):
    existing = find_comment(repo, pr)
    if status(manifest) != "differences":
        if existing:
            update_branch(repo, token, pr)
            write_comment(repo, pr, existing, comment_body(manifest, run_url=run_url, artifact_url=artifact_url))
            return "comment updated: no differences"
        return "no comment: no differences"
    files = {}
    for kind, shot in differences(manifest):
        if kind == "removed" or len(files) >= INLINE_LIMIT:
            continue
        path = Path(manifest_dir, "images", image_file(kind, shot["name"]))
        if path.exists():
            files[path.name] = path
    commit = update_branch(repo, token, pr, files, manifest["head"]) if files else None

    def url(kind, name):
        file_name = image_file(kind, name)
        if commit and file_name in files:
            return f"https://raw.githubusercontent.com/{repo}/{commit}/pr-{pr}/{manifest['head']}/{file_name}"
        return None

    write_comment(repo, pr, existing, comment_body(manifest, url, run_url, artifact_url))
    return f"comment {'updated' if existing else 'posted'}: {len(differences(manifest))} differences, {len(files)} inline"


# --- Commands ------------------------------------------------------------------------------------

def set_output(key, value):
    path = os.environ.get("GITHUB_OUTPUT")
    if path:
        with open(path, "a") as f:
            f.write(f"{key}={value}\n")


def write_manifest(out, manifest):
    Path(out).mkdir(parents=True, exist_ok=True)
    Path(out, "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    set_output("status", status(manifest))


def cmd_collect(a):
    entries = load_entries(a.results)
    failed = failed_tests(a.test_xml)
    base_names = [shot_name(p) for p in glob.glob(os.path.join(a.baselines, "*.png"))]
    manifest = build_manifest(entries, base_names, failed, a.base, a.head)
    images = Path(a.out, "images")
    images.mkdir(parents=True, exist_ok=True)
    for kind, shot in differences(manifest):
        src = shot.get("image") or (os.path.join(a.baselines, shot["name"] + ".png") if kind == "removed" else None)
        if src and os.path.exists(src):
            shutil.copyfile(src, images / image_file(kind, shot["name"]))
    write_manifest(a.out, manifest)
    c, n, r = (len(manifest[k]) for k in ("changed", "added", "removed"))
    print(f"{len(entries)} screenshots compared with the base: {manifest['unchanged']} unchanged, "
          f"{c} changed, {n} new, {r} removed.")
    if failed or a.gradle_status != 0 or not entries:
        print(f"The head run did not go through (Gradle exit {a.gradle_status}, {len(failed)} failed tests):")
        for name in failed[:20]:
            print("  " + name)
        return 2
    return 0


def cmd_no_base(a):
    write_manifest(a.out, {"base": a.base, "head": a.head, "has_base": False})
    print(f"The base {a.base[:7]} has no UI screenshot check: nothing to compare with.")
    return 0


def cmd_summary(a):
    manifest = json.loads(Path(a.manifest).read_text())
    print(comment_body(manifest, artifact_url=a.artifact_url).replace(MARKER + "\n", ""))
    return 0


def cmd_publish(a):
    if a.no_android:
        manifest, manifest_dir = {"head": a.head, "android": False}, Path(".")
    else:
        manifest, manifest_dir = json.loads(Path(a.manifest).read_text()), Path(a.manifest).parent
    token = os.environ.get("GH_TOKEN")
    if not token:
        print("GH_TOKEN is not set", file=sys.stderr)
        return 2
    print(publish(manifest, manifest_dir, a.repo, a.pr, token, a.run_url, a.artifact_url))
    return 0


def parse_args(argv):
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = p.add_subparsers(dest="command", required=True)
    c = sub.add_parser("collect")
    c.add_argument("--results", required=True)
    c.add_argument("--test-xml", required=True)
    c.add_argument("--baselines", required=True)
    c.add_argument("--out", required=True)
    c.add_argument("--base", required=True)
    c.add_argument("--head", required=True)
    c.add_argument("--gradle-status", type=int, default=0)
    c.set_defaults(func=cmd_collect)
    n = sub.add_parser("no-base")
    n.add_argument("--out", required=True)
    n.add_argument("--base", required=True)
    n.add_argument("--head", required=True)
    n.set_defaults(func=cmd_no_base)
    s = sub.add_parser("summary")
    s.add_argument("--manifest", required=True)
    s.add_argument("--artifact-url")
    s.set_defaults(func=cmd_summary)
    u = sub.add_parser("publish")
    which = u.add_mutually_exclusive_group(required=True)
    which.add_argument("--manifest")
    which.add_argument("--no-android", action="store_true",
                       help="the pull request no longer touches Android: clear a comment left from before")
    u.add_argument("--head", help="with --no-android: the pull request's head commit")
    u.add_argument("--repo", required=True)
    u.add_argument("--pr", type=int, required=True)
    u.add_argument("--run-url")
    u.add_argument("--artifact-url")
    u.set_defaults(func=cmd_publish)
    return p.parse_args(argv)


def main(argv=None):
    a = parse_args(sys.argv[1:] if argv is None else argv)
    return a.func(a)


if __name__ == "__main__":
    sys.exit(main())
