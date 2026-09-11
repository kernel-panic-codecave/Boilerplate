#!/usr/bin/env python3
"""Generates a CHANGELOG.md section and/or a mkdocs-material news/blog post for a release.

Walks the first-parent history between the previous tag (auto-detected if not given) and
--range-end (a real ref - defaults to --new-tag, but see below). Each entry on that line is
either a merged pull request (its title, author, and URL are looked up via `gh pr view`) or a
commit pushed directly to the branch (its own subject line is used as-is). Entries are parsed for
a Conventional Commits prefix (`feat:`, `fix:`, ...) and grouped into sections; anything that
doesn't parse lands in "Other Changes" rather than being dropped, since pre-adoption history won't
be Conventional-Commits-shaped.

Two call shapes:
  - Post-tag (.github/workflows/release-notes.yaml): --new-tag is a real, already-pushed tag; used
    as both the git ref to end the walk at and the version display string. Writes both the
    changelog section and a news post.
  - Pre-publish (build.gradle.kts's `generateChangelog` task): the tag doesn't exist yet at
    this point, so pass --new-tag as the *intended* version (e.g. `v1.2.0`, not yet a real ref)
    together with --range-end HEAD (or another real ref) to walk up to. Only pass --changelog-path,
    not --posts-dir, in this mode - modpublisher's `changelog = file(...)` needs CHANGELOG.md
    correct *before* it tags/publishes; the news post has no such ordering requirement and stays
    the reactive post-tag workflow's job.

Requires `git` (full history - the caller must checkout/clone with full depth) and the `gh` CLI
(authenticated via GH_TOKEN) for PR metadata lookups. See .github/workflows/release-notes.yaml.
"""
from __future__ import annotations

import argparse
import dataclasses
import datetime
import json
import re
import subprocess
import sys
from pathlib import Path

CONVENTIONAL_RE = re.compile(
    r"^(?P<type>feat|fix|perf|refactor|docs|test|build|ci|chore|style|revert)"
    r"(?:\((?P<scope>[^)]+)\))?(?P<breaking>!)?:\s*(?P<desc>.+)$",
    re.IGNORECASE,
)

# Display order; sections with no entries are omitted.
SECTION_ORDER = [
    ("feat", "Features"),
    ("fix", "Bug Fixes"),
    ("perf", "Performance"),
    ("refactor", "Refactoring"),
    ("docs", "Documentation"),
    ("test", "Tests"),
    ("build", "Build System"),
    ("ci", "CI/CD"),
    ("chore", "Chores"),
    ("style", "Style"),
    ("revert", "Reverts"),
    ("other", "Other Changes"),
]


@dataclasses.dataclass
class Entry:
    title: str
    url: str | None
    link_text: str | None  # e.g. "#123" or a short SHA - what to show for `url`
    author: str | None


def run(*args: str, check: bool = True) -> str:
    result = subprocess.run(args, capture_output=True, text=True)
    if check and result.returncode != 0:
        raise RuntimeError(f"command failed: {' '.join(args)}\n{result.stderr}")
    return result.stdout.strip()


def gh_json(*args: str) -> dict | None:
    result = subprocess.run(["gh", *args], capture_output=True, text=True)
    if result.returncode != 0:
        print(f"warning: `gh {' '.join(args)}` failed, falling back to git data:\n{result.stderr}",
              file=sys.stderr)
        return None
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError:
        return None


def detect_previous_tag(new_tag: str) -> str | None:
    """Most recent tag reachable from new_tag other than new_tag itself, by creation order."""
    tags = run("git", "tag", "--sort=creatordate", "--merged", new_tag).splitlines()
    tags = [t for t in tags if t != new_tag]
    return tags[-1] if tags else None


def range_start(prev_tag: str | None) -> str:
    if prev_tag:
        return prev_tag
    # No previous tag at all (first-ever release): walk from the repo's root commit.
    return run("git", "rev-list", "--max-parents=0", "HEAD").splitlines()[0]


def walk_first_parent(start: str, end: str) -> list[tuple[str, str, list[str]]]:
    """Returns (sha, subject, parent_shas) for each commit on end's mainline, oldest first."""
    out = run(
        "git", "log", f"{start}..{end}", "--first-parent", "--reverse",
        "--pretty=format:%H%x1f%s%x1f%P",
    )
    records = []
    for line in out.splitlines():
        if not line:
            continue
        sha, subject, parents = line.split("\x1f")
        records.append((sha, subject, parents.split()))
    return records


MERGE_PR_RE = re.compile(r"^Merge pull request #(\d+) from")
SQUASH_PR_RE = re.compile(r"^(?P<title>.+) \(#(?P<num>\d+)\)$")


def resolve_entry(repo: str, sha: str, subject: str, parents: list[str]) -> Entry:
    pr_number = None
    if len(parents) > 1:
        m = MERGE_PR_RE.match(subject)
        if m:
            pr_number = m.group(1)
    else:
        m = SQUASH_PR_RE.match(subject)
        if m:
            pr_number = m.group("num")

    if pr_number:
        data = gh_json("pr", "view", pr_number, "--repo", repo,
                        "--json", "title,url,author,number")
        if data:
            author = data.get("author", {}).get("login")
            return Entry(title=data["title"], url=data["url"], link_text=f"#{pr_number}",
                         author=f"@{author}" if author else None)
        # `gh pr view` failed (rate limit, fork PR, ...) - the PR URL itself needs no API call.
        return Entry(title=f"PR #{pr_number}", url=f"https://github.com/{repo}/pull/{pr_number}",
                     link_text=f"#{pr_number}", author=None)

    # A commit pushed directly to the branch, not via a merged/squashed PR.
    author_name = run("git", "show", "-s", "--format=%an", sha)
    short_sha = sha[:7]
    return Entry(title=subject, url=f"https://github.com/{repo}/commit/{sha}",
                 link_text=short_sha, author=author_name)


def categorize(entries: list[Entry]) -> dict[str, list[tuple[Entry, re.Match | None]]]:
    buckets: dict[str, list] = {key: [] for key, _ in SECTION_ORDER}
    for entry in entries:
        m = CONVENTIONAL_RE.match(entry.title)
        key = m.group("type").lower() if m else "other"
        key = key if key in buckets else "other"
        buckets[key].append((entry, m))
    return buckets


def render_body(buckets: dict[str, list]) -> str:
    lines = []
    for key, heading in SECTION_ORDER:
        items = buckets.get(key, [])
        if not items:
            continue
        lines.append(f"### {heading}\n")
        for entry, m in items:
            desc = m.group("desc") if m else entry.title
            scope = m.group("scope") if m else None
            breaking = " **BREAKING**" if m and m.group("breaking") else ""
            scope_prefix = f"**{scope}:** " if scope else ""
            link = f" ([{entry.link_text}]({entry.url}))" if entry.url else ""
            by = f" - {entry.author}" if entry.author else ""
            lines.append(f"- {scope_prefix}{desc}{breaking}{link}{by}")
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def update_changelog(path: Path, project_name: str, version_heading: str, date: str, body: str) -> None:
    # body already ends in exactly one newline (render_body), so this leaves one blank line
    # after the section before whatever follows.
    section = f"## {version_heading} - {date}\n\n{body}\n"
    if path.exists():
        content = path.read_text()
    else:
        content = (
            "# Changelog\n\n"
            f"All notable changes to {project_name} are documented here, generated automatically from "
            "merged pull requests and direct commits following "
            "[Conventional Commits](https://www.conventionalcommits.org/).\n\n"
        )
    marker = re.search(r"^## \[", content, re.MULTILINE)
    # A retried/re-run pre-publish generation (--new-tag hasn't become a real tag yet, so the
    # walk range and version_heading are identical to last time) would otherwise prepend another
    # full duplicate section on every retry instead of regenerating the same one - replace the
    # existing section in place when it's for this exact version and already sits at the top.
    if marker and content[marker.start():].startswith(f"## {version_heading} - "):
        next_marker = re.search(r"^## \[", content[marker.end():], re.MULTILINE)
        section_end = marker.end() + next_marker.start() if next_marker else len(content)
        content = content[:marker.start()] + section + content[section_end:]
    elif marker:
        content = content[:marker.start()] + section + content[marker.start():]
    else:
        content = content.rstrip("\n") + "\n\n" + section
    path.write_text(content)


def write_news_post(posts_dir: Path, repo: str, version_display: str, date: str,
                     prev_tag: str | None, body: str, entry_count: int) -> Path:
    posts_dir.mkdir(parents=True, exist_ok=True)
    slug = re.sub(r"[^a-zA-Z0-9.]+", "-", version_display).strip("-")
    post_path = posts_dir / f"{slug}.md"
    since = f" since [{prev_tag}](https://github.com/{repo}/releases/tag/{prev_tag})" if prev_tag else ""
    front_matter = (
        "---\n"
        f"date:\n  created: {date}\n"
        "categories:\n  - Release\n"
        "---\n\n"
    )
    intro = (
        f"# {repo.split('/')[-1]} {version_display}\n\n"
        f"{entry_count} change{'s' if entry_count != 1 else ''}{since}.\n\n"
        "<!-- more -->\n\n"
    )
    post_path.write_text(front_matter + intro + body)
    return post_path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", required=True, help="owner/name")
    parser.add_argument("--new-tag", required=True,
                         help="version identity for headings/filenames - a real tag ref in the "
                              "post-tag flow, or the not-yet-created intended tag in the "
                              "pre-publish flow (see --range-end)")
    parser.add_argument("--range-end", default=None,
                         help="real git ref to end the walk at; defaults to --new-tag. Set this "
                              "explicitly (e.g. HEAD) when --new-tag isn't a ref that exists yet")
    parser.add_argument("--prev-tag", default=None, help="auto-detected if omitted")
    parser.add_argument("--changelog-path", type=Path, default=None)
    parser.add_argument("--latest-path", type=Path, default=None,
                         help="also write just this release's own rendered body (no accumulated "
                              "history) here - e.g. for modpublisher's changelog = file(...), "
                              "which submits its whole input as a single platform-side upload "
                              "(Modrinth's version_body has a length limit CHANGELOG.md's full, "
                              "ever-growing history will eventually exceed)")
    parser.add_argument("--posts-dir", type=Path, default=None,
                         help="omit to skip news-post generation (e.g. the pre-publish flow)")
    parser.add_argument("--dry-run", action="store_true", help="print instead of writing files")
    args = parser.parse_args()

    if not args.dry_run and not args.changelog_path and not args.latest_path and not args.posts_dir:
        parser.error("nothing to do - pass --changelog-path, --latest-path, and/or --posts-dir, or --dry-run")

    range_end = args.range_end or args.new_tag
    prev_tag = args.prev_tag or detect_previous_tag(range_end)
    start = range_start(prev_tag)

    records = walk_first_parent(start, range_end)
    if not records:
        print(f"No commits between {prev_tag or '(root)'} and {range_end} - nothing to generate.")
        return

    entries = [resolve_entry(args.repo, sha, subject, parents) for sha, subject, parents in records]
    buckets = categorize(entries)
    body = render_body(buckets)

    version_display = args.new_tag[1:] if args.new_tag.startswith("v") else args.new_tag
    date = datetime.date.today().isoformat()

    if args.dry_run:
        print(f"# Previous tag: {prev_tag or '(none - first release)'}")
        print(f"# {len(entries)} entries\n")
        print(body)
        return

    if args.changelog_path:
        update_changelog(args.changelog_path, args.repo.split("/")[-1], f"[{version_display}]", date, body)
        print(f"Updated {args.changelog_path}")

    if args.latest_path:
        args.latest_path.parent.mkdir(parents=True, exist_ok=True)
        args.latest_path.write_text(body)
        print(f"Wrote {args.latest_path}")

    if args.posts_dir:
        post_path = write_news_post(
            args.posts_dir, args.repo, version_display, date, prev_tag, body, len(entries),
        )
        print(f"Wrote {post_path}")


if __name__ == "__main__":
    main()
