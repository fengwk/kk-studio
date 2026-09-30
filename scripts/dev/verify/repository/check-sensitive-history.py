#!/usr/bin/env python3
"""Audit the whole public Git history (and the working tree) for sensitive data.

The current-tree gate in ``check-sensitive-data.py`` only sees files that exist
today, so it can never prove anything about what was committed earlier. This
entry reuses that gate's rules, adds a few broad credential shapes, and walks
every ref that is visible in the local repository:

* reachable blobs (content is read once per unique blob through the git
  ``--batch`` streaming protocol instead of once per commit);
* commit messages and annotated-tag messages;
* file paths and ref names.

Every report row is redacted to ``rule/object/commit/path/line/category``; the
matched value never leaves the scanner. Nothing is fixed, rewritten, or pushed
here: a historical credential can only be rotated and evicted from history by
an explicit, authorized operator action.
"""

from __future__ import annotations

import argparse
import bisect
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time


def _load_base_scanner():
    """Import the sibling current-tree gate to reuse its rules and helpers."""
    spec = importlib.util.spec_from_file_location(
        "kk_studio_check_sensitive_data",
        Path(__file__).resolve().parent / "check-sensitive-data.py",
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


BASE = _load_base_scanner()

DEFAULT_MAX_BLOB_BYTES = 2 * 1024 * 1024

# Hosts that can never carry a working credential: reserved example domains and
# loopback/container aliases. Excluding them keeps the broad credential-url rule
# usable without hiding a real remote secret (a real token in such a URL is still
# caught by the format rules).
RESERVED_HOST_NAMES = frozenset(
    {"localhost", "127.0.0.1", "0.0.0.0", "::1", "host.docker.internal"}
)
RESERVED_HOST_SUFFIXES = (
    "example.com",
    "example.org",
    "example.net",
    "example.test",
    "example",
    "test",
    "invalid",
    "localhost",
)

# Broad, lower-confidence shapes the formatted-token rules do not cover.
# Regexes are assembled from fragments so this file never matches its own rules.
CREDENTIAL_URL_PATTERN = re.compile(
    "[A-Za-z][A-Za-z0-9+.-]*"
    + "://"
    + "(?P<userinfo>[^/@\\s:]+)"
    + ":"
    + "(?P<password>[^/@\\s]+)"
    + "@"
    + "(?P<host>[^/\\s:@?#]+)"
)
GENERIC_SECRET_PATTERN = re.compile(
    "(?i)(?:"
    + "|".join(
        (
            "api[_-]?key",
            "apikey",
            "secret",
            "secret[_-]?key",
            "client[_-]?secret",
            "access[_-]?key",
            "auth[_-]?token",
            "api[_-]?token",
            "private[_-]?key",
            "passwd",
            "password",
            "bearer",
        )
    )
    + ")"
    + "\\b\\s*[:=]\\s*"
    + "(?P<quote>[\"']?)"
    + "(?P<value>[A-Za-z0-9_./+=\\-]{16,})"
    + "(?P=quote)"
)

# rule -> (category, confidence). Confidence grades how much a hit means: a
# formatted token or a private key is actionable, a broad assignment or a
# personal path is context. Grading never removes a hit from the report.
CATEGORY_CONFIDENCE = {
    "private-key": ("private-key", "high"),
    "aws-access-key": ("token", "high"),
    "google-api-key": ("token", "high"),
    "gitlab-token": ("token", "high"),
    "github-token": ("token", "high"),
    "slack-token": ("token", "high"),
    "stripe-live-token": ("token", "high"),
    "jwt": ("token", "high"),
    "slack-webhook": ("webhook", "high"),
    "discord-webhook": ("webhook", "high"),
    "telegram-webhook": ("webhook", "high"),
    "dingtalk-webhook": ("webhook", "high"),
    "feishu-webhook": ("webhook", "high"),
    "wecom-webhook": ("webhook", "high"),
    "private-environment": ("private-environment", "high"),
    "personal-path": ("personal-path", "medium"),
    "credential-url": ("credential-url", "medium"),
    "generic-secret": ("generic-secret", "medium"),
}

SUPPLEMENTARY_RULES = (
    ("credential-url", CREDENTIAL_URL_PATTERN),
    ("generic-secret", GENERIC_SECRET_PATTERN),
)

# Ordered so reports are stable.
ALL_RULES = tuple(BASE.RULES) + SUPPLEMENTARY_RULES

# Necessary (not sufficient) lowercase substrings per rule: a rule whose marker
# is absent from a blob cannot match, so its regex is skipped. Using lowercase
# makes each check a superset, so it can never hide a real match.
PREFILTERS = {
    "private-key": ("private key",),
    "aws-access-key": ("akia", "asia"),
    "google-api-key": ("aiza",),
    "gitlab-token": ("glpat-", "gldt-", "glrt-", "glft-", "gloas-", "glsoat-"),
    "github-token": ("ghp_", "gho_", "ghu_", "ghs_", "ghr_", "github_pat_"),
    "slack-token": ("xox",),
    "stripe-live-token": ("sk_live_", "rk_live_"),
    "jwt": ("eyj",),
    "slack-webhook": ("hooks.slack.com",),
    "discord-webhook": ("discord",),
    "telegram-webhook": ("api.telegram.org",),
    "dingtalk-webhook": ("dingtalk",),
    "feishu-webhook": ("feishu", "larksuite"),
    "wecom-webhook": ("qyapi",),
    "private-environment": tuple(
        marker.lower() for marker in BASE.PRIVATE_ENVIRONMENT_MARKERS
    ),
    "personal-path": ("/home", "/users", "\\users", "/mnt/", "wsl.localhost"),
    "credential-url": ("://",),
    "generic-secret": ("api", "key", "secret", "password", "passwd", "bearer", "token"),
}


def _is_reserved_host(host):
    """Return True for hosts that cannot hold a real credential target."""
    host = host.rsplit(":", 1)[0].strip("[]").lower()
    if host in RESERVED_HOST_NAMES:
        return True
    return any(host == suffix or host.endswith("." + suffix) for suffix in RESERVED_HOST_SUFFIXES)


def _is_allowlisted(rule_name, match):
    """Rule-specific allowlist; only shapes that cannot be real secrets pass."""
    if rule_name == "personal-path":
        return match.group("user") in BASE.ALLOWED_USER_NAMES
    if rule_name == "credential-url":
        return _is_reserved_host(match.group("host"))
    return False


def _line_starts(text):
    """Byte-independent offsets of every line start, for match -> line mapping."""
    starts = [0]
    index = text.find("\n")
    while index != -1:
        starts.append(index + 1)
        index = text.find("\n", index + 1)
    return starts


def findings_in_text(text, path, commit="", obj=""):
    """Return redacted findings for one text blob; matched values are discarded.

    Each rule is run once over the whole text (instead of once per line), then a
    match offset is mapped back to its line with a binary search. This keeps the
    same per-line, first-match-per-rule semantics while avoiding millions of
    tiny regex calls on large histories.
    """
    findings = []
    reported = set()
    starts = _line_starts(text)
    lowered = text.lower()
    for rule_name, pattern in ALL_RULES:
        required = PREFILTERS.get(rule_name)
        if required is not None and not any(marker in lowered for marker in required):
            continue
        for match in pattern.finditer(text):
            if _is_allowlisted(rule_name, match):
                continue
            line_number = bisect.bisect_right(starts, match.start())
            key = (rule_name, line_number)
            if key in reported:
                continue
            reported.add(key)
            findings.append(HistoryFinding(rule_name, obj, commit, path, line_number))
    return findings


class HistoryFinding:
    """A redacted location for one sensitive-data match; carries no value."""

    __slots__ = ("rule", "object", "commit", "path", "line")

    def __init__(self, rule, obj, commit, path, line):
        self.rule = rule
        self.object = obj
        self.commit = commit
        self.path = path
        self.line = line

    @property
    def category(self):
        return CATEGORY_CONFIDENCE.get(self.rule, ("other", "medium"))[0]

    @property
    def confidence(self):
        return CATEGORY_CONFIDENCE.get(self.rule, ("other", "medium"))[1]

    @property
    def key(self):
        return (self.rule, self.object, self.commit, self.path, self.line)

    def as_dict(self):
        return {
            "rule": self.rule,
            "category": self.category,
            "object": self.object,
            "commit": self.commit,
            "path": self.path,
            "line": self.line,
        }


class Ref:
    __slots__ = ("name", "objectname", "objecttype")

    def __init__(self, name, objectname, objecttype):
        self.name = name
        self.objectname = objectname
        self.objecttype = objecttype


def _git(root, *args, check=True):
    return subprocess.run(
        ["git", *args],
        cwd=root,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=check,
    )


def list_refs(root):
    """Every visible ref, plus a synthetic HEAD entry when it resolves."""
    out = _git(
        root,
        "for-each-ref",
        "--format=%(objectname)%00%(objecttype)%00%(refname)",
    ).stdout.decode("utf-8", "replace")
    refs = []
    for line in out.splitlines():
        if not line.strip():
            continue
        objectname, objecttype, name = line.split("\0", 2)
        refs.append(Ref(name, objectname, objecttype))
    head = _git(root, "rev-parse", "--verify", "--quiet", "HEAD", check=False)
    if head.returncode == 0:
        refs.append(Ref("HEAD", head.stdout.decode().strip(), "commit"))
    return refs


def is_shallow(root):
    return _git(root, "rev-parse", "--is-shallow-repository").stdout.strip() == b"true"


def peel_commit(root, revision):
    """Resolve a ref/object to a commit id, or None for non-commit refs."""
    completed = _git(root, "rev-parse", "--verify", "--quiet", revision + "^{commit}", check=False)
    if completed.returncode != 0:
        return None
    return completed.stdout.decode().strip()


def reachable_commits(root, refs):
    commits = set()
    for ref in refs:
        commit = peel_commit(root, ref.name)
        if not commit:
            continue
        out = _git(root, "rev-list", commit).stdout.decode()
        commits.update(out.split())
    return commits


def blob_locations(root, refs):
    """Map every reachable blob to the introducing (commit, path) pairs.

    ``git log --raw`` reports each blob once per commit that actually changed
    it, so the union over every commit covers all reachable content without
    re-reading trees per commit.
    """
    revisions = [ref.name for ref in refs]
    if not revisions:
        return {}
    completed = _git(
        root,
        "log",
        "-m",
        "--raw",
        "--root",
        "--no-renames",
        "--no-abbrev",
        "--format=%x00COMMIT%x00%H",
        "-z",
        *revisions,
    )
    tokens = completed.stdout.split(b"\0")
    locations = {}
    commit = ""
    index = 0
    while index < len(tokens):
        token = tokens[index]
        if token == b"COMMIT":
            commit = tokens[index + 1].decode("ascii", "replace")
            index += 2
            continue
        meta = token.lstrip(b"\r\n")
        if meta.startswith(b":") and index + 1 < len(tokens):
            fields = meta.decode("ascii", "replace").split()
            path = tokens[index + 1].decode("utf-8", "surrogateescape")
            index += 2
            if len(fields) >= 5:
                dst_mode, dst_sha, status = fields[1], fields[3], fields[4]
                if (
                    dst_mode in {"100644", "100755", "120000"}
                    and status != "D"
                    and set(dst_sha) != {"0"}
                ):
                    locations.setdefault(dst_sha, set()).add((commit, path))
            continue
        index += 1
    return locations


def _read_exact(stream, size, chunk=1 << 16):
    remaining = size
    data = bytearray()
    while remaining > 0:
        piece = stream.read(min(remaining, chunk))
        if not piece:
            break
        data.extend(piece)
        remaining -= len(piece)
    return bytes(data)


def _discard_exact(stream, size, chunk=1 << 16):
    remaining = size
    while remaining > 0:
        piece = stream.read(min(remaining, chunk))
        if not piece:
            break
        remaining -= len(piece)


def _scan_stream(root, blob_ids, root_blobs, max_blob_bytes):
    """Stream every unique blob once and return findings plus skip counters."""
    stats = {"too_large": 0, "binary": 0, "lfs": 0, "scanned": 0}
    findings = []
    with tempfile.NamedTemporaryFile("wb", delete=False) as handle:
        handle.write("\n".join(blob_ids).encode("ascii"))
        handle.write(b"\n")
        list_path = Path(handle.name)
    try:
        with list_path.open("rb") as stdin:
            process = subprocess.Popen(
                ["git", "cat-file", "--batch"],
                cwd=root,
                stdin=stdin,
                stdout=subprocess.PIPE,
            )
            try:
                stream = process.stdout
                while True:
                    header = stream.readline()
                    if not header:
                        break
                    parts = header.rstrip(b"\n").split()
                    if len(parts) == 2 and parts[1] == b"missing":
                        continue
                    if len(parts) < 3:
                        continue
                    oid, object_type, size = parts[0], parts[1], int(parts[2])
                    if object_type != b"blob":
                        _discard_exact(stream, size)
                        stream.read(1)
                        continue
                    if size > max_blob_bytes:
                        stats["too_large"] += 1
                        _discard_exact(stream, size)
                        stream.read(1)
                        continue
                    content = _read_exact(stream, size)
                    stream.read(1)
                    if b"\0" in content:
                        stats["binary"] += 1
                        continue
                    if content.startswith(b"version https://git-lfs.github.com/spec/"):
                        stats["lfs"] += 1
                        continue
                    stats["scanned"] += 1
                    oid_text = oid.decode("ascii", "replace")
                    text = content.decode("utf-8", "replace")
                    # Scan the blob once, then attribute its hits to every
                    # (commit, path) where the content is reachable.
                    blob_findings = findings_in_text(text, "", "", oid_text)
                    if not blob_findings:
                        continue
                    recorded = set()
                    for commit, path in root_blobs.get(oid_text, ()):
                        for finding in blob_findings:
                            candidate = HistoryFinding(
                                finding.rule, oid_text, commit, path, finding.line
                            )
                            if candidate.key not in recorded:
                                recorded.add(candidate.key)
                                findings.append(candidate)
            finally:
                process.stdout.close()
                process.wait()
    finally:
        list_path.unlink(missing_ok=True)
    return findings, stats


def scan_history(root, refs, max_blob_bytes):
    """Scan all reachable content, messages, tag messages, paths and ref names."""
    findings = []
    locations = blob_locations(root, refs)
    blob_ids = sorted(locations)
    blob_findings, stats = _scan_stream(root, blob_ids, locations, max_blob_bytes)
    findings.extend(blob_findings)

    # Paths themselves can carry a leaked token or a personal absolute path.
    seen_paths = set()
    for pairs in locations.values():
        for _, path in pairs:
            if path in seen_paths:
                continue
            seen_paths.add(path)
            findings.extend(findings_in_text(path, path, "", "<path>"))

    # Commit messages (explicit revisions so a detached HEAD is covered too).
    revisions = [ref.name for ref in refs]
    commit_count = 0
    if revisions:
        log = _git(root, "log", "--format=%x01%H%x00%B", *revisions).stdout
        for chunk in log.split(b"\x01"):
            if not chunk.strip():
                continue
            sha, _, body = chunk.partition(b"\x00")
            commit_count += 1
            findings.extend(
                findings_in_text(
                    body.decode("utf-8", "replace").rstrip("\n"),
                    "<commit-message>",
                    sha.decode("ascii", "replace"),
                    sha.decode("ascii", "replace"),
                )
            )

    # Annotated tag messages.
    tags = _git(
        root,
        "for-each-ref",
        "--format=%(objecttype)%00%(objectname)%00%(contents)",
        "refs/tags",
    ).stdout.decode("utf-8", "replace")
    tag_message_count = 0
    for line in tags.splitlines():
        if not line.strip():
            continue
        object_type, _, remainder = line.partition("\0")
        objectname, _, contents = remainder.partition("\0")
        if object_type == "tag" and contents.strip():
            tag_message_count += 1
            findings.extend(
                findings_in_text(contents, "<tag-message>", "", objectname)
            )

    for ref in refs:
        findings.extend(findings_in_text(ref.name, "<ref-name>", "", ref.objectname))

    stats["commits"] = len(reachable_commits(root, refs))
    stats["commit_messages"] = commit_count
    stats["tag_messages"] = tag_message_count
    stats["paths"] = len(seen_paths)
    stats["unique_blobs"] = len(blob_ids)
    return findings, stats


def scan_worktree(root):
    """Reuse the current-tree gate's file discovery with the combined rules."""
    findings = []
    for relative_path in BASE._git_paths(root):
        file_path = root / relative_path
        display = relative_path.as_posix()
        findings.extend(findings_in_text(display, display, "", "<path>"))
        if file_path.is_symlink() or not file_path.is_file():
            continue
        try:
            with file_path.open("rb") as source:
                raw = source.read()
        except OSError:
            continue
        if b"\0" in raw:
            continue
        findings.extend(
            findings_in_text(raw.decode("utf-8", "replace"), display, "", "<worktree>")
        )
    return findings


def dedupe(findings):
    seen = set()
    unique = []
    for finding in findings:
        if finding.key in seen:
            continue
        seen.add(finding.key)
        unique.append(finding)
    return unique


def _counts(findings):
    counts = {}
    for finding in findings:
        entry = counts.setdefault(
            finding.rule,
            {"category": finding.category, "confidence": finding.confidence, "count": 0},
        )
        entry["count"] += 1
    return counts


def build_report(worktree_findings, history_findings, coverage, limits, boundaries):
    """Assemble the redacted JSON payload (never contains a matched value)."""
    worktree_findings = dedupe(worktree_findings)
    history_findings = dedupe(history_findings)
    return {
        "repository": "kk-studio",
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "coverage": coverage,
        "limits": limits,
        "boundaries": list(boundaries),
        "worktree": {
            "counts": _counts(worktree_findings),
            "findings": [
                finding.as_dict()
                for finding in sorted(
                    worktree_findings, key=lambda f: (f.path, f.line, f.rule)
                )
            ],
        },
        "history": {
            "counts": _counts(history_findings),
            "findings": [
                finding.as_dict()
                for finding in sorted(
                    history_findings,
                    key=lambda f: (f.rule, f.path, f.line, f.commit),
                )
            ],
        },
    }


def _table(findings):
    """Redacted markdown table over report dicts (rule/object/commit/path/line)."""
    rows = []
    for finding in findings:
        commit = finding["commit"]
        rows.append(
            "| {rule} | {category} | {object} | {commit} | {path} | {line} |".format(
                rule=finding["rule"],
                category=finding["category"],
                object=finding["object"] or "-",
                commit=commit[:12] if commit else "-",
                path=finding["path"],
                line=finding["line"],
            )
        )
    if not rows:
        return "_no findings_"
    header = [
        "| rule | category | object | commit | path | line |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    return "\n".join(header + rows)


def render_markdown(report):
    coverage = report["coverage"]
    limits = report["limits"]
    history = report["history"]
    worktree = report["worktree"]
    high_history = [
        finding
        for finding in history["findings"]
        if CATEGORY_CONFIDENCE.get(finding["rule"], ("", ""))[1] == "high"
    ]
    lines = [
        "# 仓库敏感信息扫描（当前树 + 全历史）",
        "",
        "本报告由 `scripts/dev/verify/repository/check-sensitive-history.py` 生成，只记录",
        "`rule/category/object/commit/path/line` 与统计信息；匹配到的值绝不写入报告。",
        "报告写在 workspace 中（不在公开仓库内），也不包含任何被匹配的密钥或历史个人路径值。",
        "",
        "## 结论",
        "",
    ]
    if high_history:
        lines += [
            "**历史上存在高置信度敏感信息命中**，必须由仓库所有者执行密钥轮换（rotation）",
            "并授权后再进行历史清理（`filter-repo`/BFG 一类改写）。本工具不轮换、不改写、不推送。",
        ]
    else:
        lines += [
            "本次扫描未在可达历史中发现高置信度（格式 token / 私钥 / webhook）命中。",
            "注意：这只能说明**本次覆盖的 refs**是干净的，不能据此推断历史中不存在泄露；",
            "若未来出现泄露，仍需要人为轮换密钥并授权后清理历史。",
        ]
    lines += [
        "",
        "## 覆盖范围（coverage）",
        "",
        f"- shallow 仓库：{coverage['shallow']}",
        f"- refs 总数：{coverage['refs_total']}"
        f"（heads={coverage['refs_heads']}, remotes={coverage['refs_remotes']},"
        f" tags={coverage['refs_tags']}, other={coverage['refs_other']}）",
        f"- remote heads 覆盖：{coverage['remote_heads_present']}；本地 tags 存在：{coverage['tags_present']}",
        f"- HEAD commit：`{coverage['head_commit']}`",
        f"- 可达 commit 数：{coverage['commits']}",
        f"- 唯一可达 blob 数：{coverage['unique_blobs']}"
        f"（已扫描 {coverage['blobs_scanned']}，二进制 {coverage['blobs_binary']}，"
        f"超大 {coverage['blobs_too_large']}，LFS 指针 {coverage['blobs_lfs']}）",
        f"- 扫描 commit message 数：{coverage['commit_messages']}；annotated tag message 数：{coverage['tag_messages']}",
        f"- 扫描路径数：{coverage['paths']}；扫描 ref 名数：{coverage['refnames']}",
        f"- 扫描耗时：{coverage['duration_seconds']:.2f}s",
        "",
        "## 限制（limits）",
        "",
        f"- 单一 blob 上限：{limits['max_blob_bytes']} bytes（超出只计数、不扫描内容）",
        f"- 二进制判定：内容含 NUL 字节即跳过",
        f"- LFS 指针不追内容；Git LFS 对象不在本地对象库里，本次未下载",
        "",
        "## 边界（out of scope）",
        "",
    ]
    lines += [f"- {item}" for item in report["boundaries"]]
    lines += [
        "",
        "## 当前树 findings",
        "",
        _table(worktree["findings"]),
        "",
        "## 历史 findings",
        "",
        _table(history["findings"]),
        "",
        "## 按规则统计",
        "",
    ]
    for section in ("worktree", "history"):
        lines.append(f"### {section}")
        lines.append("")
        lines.append("| rule | category | confidence | count |")
        lines.append("| --- | --- | --- | --- |")
        for rule, entry in sorted(report[section]["counts"].items()):
            lines.append(
                f"| {rule} | {entry['category']} | {entry['confidence']} | {entry['count']} |"
            )
        lines.append("")
    lines += [
        "## 需要的用户动作",
        "",
        "- 若“历史 findings”出现高置信度命中：轮换对应凭据，并**在授权后**清理历史，",
        "  两者缺一不可；仅当前树干净不等于历史干净。",
        "- 中置信度命中（`credential-url`/`generic-secret`/`personal-path`）需人工确认是否为",
        "  合成样例；确认前不得直接删除报告或忽略。",
        "- 本工具只读且不上传：不推送代码、不联网校验密钥。",
    ]
    return "\n".join(lines) + "\n"


def coverage_stats(root, refs, stats, duration):
    heads = [ref for ref in refs if ref.name.startswith("refs/heads/")]
    remotes = [ref for ref in refs if ref.name.startswith("refs/remotes/")]
    tags = [ref for ref in refs if ref.name.startswith("refs/tags/")]
    other = [
        ref
        for ref in refs
        if not ref.name.startswith(("refs/heads/", "refs/remotes/", "refs/tags/"))
    ]
    remote_names = {ref.name for ref in remotes}
    return {
        "shallow": is_shallow(root),
        "refs_total": len(refs),
        "refs_heads": len(heads),
        "refs_remotes": len(remotes),
        "refs_tags": len(tags),
        "refs_other": len(other),
        "remote_heads_present": any(
            name in remote_names
            for name in ("refs/remotes/origin/main", "refs/remotes/origin/dev")
        ),
        "tags_present": bool(tags),
        "head_commit": peel_commit(root, "HEAD") or "",
        "commits": stats["commits"],
        "unique_blobs": stats["unique_blobs"],
        "blobs_scanned": stats["scanned"],
        "blobs_binary": stats["binary"],
        "blobs_too_large": stats["too_large"],
        "blobs_lfs": stats["lfs"],
        "commit_messages": stats["commit_messages"],
        "tag_messages": stats["tag_messages"],
        "paths": stats["paths"],
        "refnames": len(refs),
        "duration_seconds": duration,
    }


BOUNDARIES = (
    "reflog 条目（`git reflog`）与未写入 refs 的悬空对象不在可达 refs 内，本次不扫描。",
    "远端 Pull Request 的 `refs/pull/*` 或其他未 fetch 的私有 ref 不在本地，本次不扫描。",
    "Git submodule 的内容不在父仓对象库里，本次只扫描 gitlink 指针本身。",
    "已 fetch 的 refs 之外的远端分支不可见；覆盖以本次 refs 清单为准。",
)


def run(root, max_blob_bytes, include_worktree=True, fetch=False):
    root = Path(root).resolve()
    fetch_result = None
    if fetch:
        fetched = _git(root, "fetch", "--tags", "origin", check=False)
        fetch_result = "ok" if fetched.returncode == 0 else "failed"
        if fetch_result == "failed":
            sys.stderr.write("git-fetch-failed\n")
    start = time.time()
    refs = list_refs(root)
    history_findings, stats = scan_history(root, refs, max_blob_bytes)
    worktree_findings = scan_worktree(root) if include_worktree else []
    duration = time.time() - start
    coverage = coverage_stats(root, refs, stats, duration)
    if fetch_result is not None:
        coverage["fetch"] = fetch_result
    limits = {"max_blob_bytes": max_blob_bytes}
    report = build_report(worktree_findings, history_findings, coverage, limits, BOUNDARIES)
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Audit the full Git history and working tree for sensitive data (redacted)."
    )
    parser.add_argument("--root", type=Path, default=BASE.REPOSITORY_ROOT)
    parser.add_argument("--json", type=Path, help="write the redacted JSON report here")
    parser.add_argument("--markdown", type=Path, help="write the redacted markdown report here")
    parser.add_argument("--max-blob-bytes", type=int, default=DEFAULT_MAX_BLOB_BYTES)
    parser.add_argument("--no-worktree", action="store_true", help="skip the current-tree scan")
    parser.add_argument("--fetch", action="store_true", help="refresh origin heads/tags first")
    parser.add_argument("--fail-on-high", action="store_true", help="exit 1 on high-confidence hits")
    args = parser.parse_args(argv)

    try:
        report = run(
            args.root,
            args.max_blob_bytes,
            include_worktree=not args.no_worktree,
            fetch=args.fetch,
        )
    except (OSError, subprocess.CalledProcessError) as error:
        sys.stderr.write(f"scan-error {type(error).__name__}\n")
        return 2

    payload = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.json:
        args.json.write_text(payload, encoding="utf-8")
    if args.markdown:
        args.markdown.write_text(render_markdown(report), encoding="utf-8")

    coverage = report["coverage"]
    print(
        "refs={refs_total} commits={commits} blobs={unique_blobs} "
        "worktree_findings={wf} history_findings={hf} duration={duration_seconds:.2f}s".format(
            wf=len(report["worktree"]["findings"]),
            hf=len(report["history"]["findings"]),
            **coverage,
        )
    )
    high = [
        finding
        for finding in report["history"]["findings"]
        if CATEGORY_CONFIDENCE.get(finding["rule"], ("", ""))[1] == "high"
    ]
    if args.fail_on_high and high:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
