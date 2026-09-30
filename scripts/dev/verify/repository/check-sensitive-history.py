#!/usr/bin/env python3
"""Audit the whole public Git history (and the working tree) for sensitive data.

The current-tree gate in ``check-sensitive-data.py`` only sees files that exist
today, so it can never prove anything about what was committed earlier. This
entry reuses that gate's rules, adds a few broad credential shapes, and walks
every ref that is visible in the local repository:

* reachable blobs (content is read once per unique blob through the git
  ``--batch`` streaming protocol instead of once per commit; binary blobs are
  additionally reduced to printable ASCII/UTF-16 strings before scanning);
* commit messages and annotated-tag messages;
* file paths and ref names, with the stored location redacted when the path
  itself contains a match;
* optionally the public pull-request refs (``refs/pull/*``) pulled into an
  isolated temporary bare repository so the audited repository's refs stay
  untouched.

Nothing here is a verdict. Every hit is a *candidate pending human review*: the
report recommends credential rotation only after a reviewer confirms a real
credential, and never infers key leakage from a personal absolute path. Values
never leave the scanner; value-level evidence is stored as a short hash plus
redacted locations so a reviewer can grade each group by hand.
"""

from __future__ import annotations

import argparse
import bisect
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil
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
DEFAULT_STRING_LIMIT = 256 * 1024
MAX_GROUP_LOCATIONS = 25
MAX_BINARY_OBJECTS = 50

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

SUPPLEMENTARY_RULES = (
    ("credential-url", CREDENTIAL_URL_PATTERN),
    ("generic-secret", GENERIC_SECRET_PATTERN),
)

# Ordered so reports are stable.
ALL_RULES = tuple(BASE.RULES) + SUPPLEMENTARY_RULES

# rule -> (category, confidence). Confidence grades how much a hit means; it is
# never a verdict and never removes a hit from the report.
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

# Value-level grading states. Only a published example can be proven to be a
# non-credential; everything else stays "pending-review" until a human decides,
# and a personal path never implies key leakage.
STATUS_PUBLIC_EXAMPLE = "known-public-example"
STATUS_PENDING = "pending-review"
STATUS_PERSONAL_DATA = "personal-data-exposure"
STATUS_ENVIRONMENT = "environment-identifier"

# Values published in vendor documentation; assembled so this file stays clean.
KNOWN_PUBLIC_EXAMPLES = frozenset({"AKIA" + "IOSFODNN7EXAMPLE"})

PLACEHOLDER_HINT = re.compile(
    r"(?i)test|fake|example|local|dummy|unused|placeholder|sample|changeme|"
    r"redact|sensitive|foo|bar|preview|only|candidate|invalid"
)

# Necessary (not sufficient) lowercase substrings per rule: a rule whose marker
# is absent from a blob cannot match, so its regex is skipped. Lowercase makes
# each check a superset, so it can never hide a real match.
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

ASCII_RUN = re.compile(rb"[\x20-\x7e]{6,}")
UTF16_RUN = re.compile(rb"(?:[\x20-\x7e]\x00){6,}")


def is_high(rule):
    """True when a rule is graded high confidence (still not a verdict)."""
    return CATEGORY_CONFIDENCE.get(rule, ("other", "medium"))[1] == "high"


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


def classify_value(rule, value):
    """Grade one matched value without ever exposing it (status, hint)."""
    if rule == "personal-path":
        return STATUS_PERSONAL_DATA, ""
    if rule == "private-environment":
        return STATUS_ENVIRONMENT, ""
    if value in KNOWN_PUBLIC_EXAMPLES:
        return STATUS_PUBLIC_EXAMPLE, "published-vendor-example"
    hint = PLACEHOLDER_HINT.search(value)
    return STATUS_PENDING, hint.group(0).lower() if hint else ""


_LOCATOR_CACHE = {}


def redacted_locator(path):
    """Return the path with every rule match masked by its rule name."""
    cached = _LOCATOR_CACHE.get(path)
    if cached is not None:
        return cached
    spans = []
    for rule_name, pattern in ALL_RULES:
        for match in pattern.finditer(path):
            if not _is_allowlisted(rule_name, match):
                spans.append((match.start(), match.end(), rule_name))
    if not spans:
        _LOCATOR_CACHE[path] = path
        return path
    spans.sort()
    parts = []
    last = 0
    for start, end, rule_name in spans:
        if start < last:
            continue
        parts.append(path[last:start])
        parts.append(f"<redacted:{rule_name}>")
        last = end
    parts.append(path[last:])
    locator = "".join(parts)
    _LOCATOR_CACHE[path] = locator
    return locator


class ValueGroups:
    """Value-level evidence: sha256 group + redacted locations, never the value."""

    def __init__(self):
        self._groups = {}

    def add(self, rule, value, locator, line):
        digest = hashlib.sha256(value.encode("utf-8", "surrogateescape")).hexdigest()[:12]
        group = self._groups.get((rule, digest))
        if group is None:
            status, hint = classify_value(rule, value)
            category, confidence = CATEGORY_CONFIDENCE.get(rule, ("other", "medium"))
            group = {
                "rule": rule,
                "category": category,
                "confidence": confidence,
                "value_sha256_12": digest,
                "status": status,
                "hint": hint,
                "count": 0,
                "locations": [],
            }
            self._groups[(rule, digest)] = group
        group["count"] += 1
        if len(group["locations"]) < MAX_GROUP_LOCATIONS:
            group["locations"].append({"path": locator, "line": line})

    def render(self):
        rendered = []
        for group in sorted(
            self._groups.values(), key=lambda item: (item["status"], item["rule"], item["value_sha256_12"])
        ):
            entry = dict(group)
            entry["locations_truncated"] = entry["count"] > len(entry["locations"])
            rendered.append(entry)
        return rendered


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
    out = _git(root, "for-each-ref", "--format=%(objectname)%00%(objecttype)%00%(refname)").stdout
    refs = []
    for line in out.decode("utf-8", "replace").splitlines():
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


def _line_starts(text):
    """Offsets of every line start, for match-offset -> line-number mapping."""
    starts = [0]
    index = text.find("\n")
    while index != -1:
        starts.append(index + 1)
        index = text.find("\n", index + 1)
    return starts


def findings_in_text(text, path, commit="", obj="", groups=None):
    """Return redacted findings for one text; matched values are discarded.

    Each rule runs once over the whole text, then a match offset is mapped to a
    line by binary search. ``groups`` (when given) receives value-level evidence
    keyed by a value hash, so grading never needs the raw value in the report.
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
            if groups is not None:
                groups.add(rule_name, match.group(0), path, line_number)
            key = (rule_name, line_number)
            if key in reported:
                continue
            reported.add(key)
            findings.append(HistoryFinding(rule_name, obj, commit, path, line_number))
    return findings


def extract_strings(content, limit):
    """Reduce a binary blob to printable ASCII/UTF-16 runs for candidate checks."""
    chunks = [match.group(0).decode("ascii", "replace") for match in ASCII_RUN.finditer(content)]
    chunks.extend(
        match.group(0).decode("utf-16-le", "replace") for match in UTF16_RUN.finditer(content)
    )
    return "\n".join(chunks)[:limit]


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


def _read(stream, size, keep=True, chunk=1 << 16):
    """Read exactly ``size`` bytes; discards them when ``keep`` is False."""
    data = bytearray() if keep else None
    remaining = size
    while remaining > 0:
        piece = stream.read(min(remaining, chunk))
        if not piece:
            break
        if data is not None:
            data.extend(piece)
        remaining -= len(piece)
    return bytes(data) if data is not None else b""


def _scan_blob(root, blob_ids, locations, max_blob_bytes, string_limit):
    """Stream every unique blob once; return findings, groups and counters."""
    stats = {
        "too_large": 0,
        "binary": 0,
        "binary_strings_scanned": 0,
        "lfs": 0,
        "scanned": 0,
        "binary_bytes": 0,
    }
    findings = []
    groups = ValueGroups()
    binary_objects = []
    with tempfile.NamedTemporaryFile("wb", delete=False) as handle:
        handle.write(("\n".join(blob_ids) + "\n").encode("ascii"))
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
                    if object_type != b"blob" or size > max_blob_bytes:
                        stats["too_large"] += object_type == b"blob"
                        _read(stream, size, keep=False)
                        stream.read(1)
                        continue
                    content = _read(stream, size)
                    stream.read(1)
                    oid_text = oid.decode("ascii", "replace")
                    if b"\0" in content:
                        stats["binary"] += 1
                        stats["binary_bytes"] += size
                        text = extract_strings(content, string_limit)
                        stats["binary_strings_scanned"] += 1
                        binary_objects.append(
                            {
                                "object": oid_text,
                                "size": size,
                                "paths": [redacted_locator(p) for _, p in locations.get(oid_text, ())][:3],
                            }
                        )
                    elif content.startswith(b"version https://git-lfs.github.com/spec/"):
                        stats["lfs"] += 1
                        continue
                    else:
                        stats["scanned"] += 1
                        text = content.decode("utf-8", "replace")
                    if not text:
                        continue
                    blob_paths = sorted(locations.get(oid_text, ()))
                    # Group evidence records the first known location; the full
                    # per-location rows come from the finding re-attribution below.
                    first_locator = (
                        redacted_locator(blob_paths[0][1]) if blob_paths else "<blob>"
                    )
                    blob_findings = findings_in_text(text, first_locator, "", oid_text, groups)
                    if not blob_findings:
                        continue
                    recorded = set()
                    for commit, path in blob_paths:
                        locator = redacted_locator(path)
                        for finding in blob_findings:
                            candidate = HistoryFinding(
                                finding.rule, oid_text, commit, locator, finding.line
                            )
                            if candidate.key not in recorded:
                                recorded.add(candidate.key)
                                findings.append(candidate)
            finally:
                process.stdout.close()
                process.wait()
    finally:
        list_path.unlink(missing_ok=True)
    return findings, groups, binary_objects, stats


def scan_history(root, refs, max_blob_bytes, string_limit=DEFAULT_STRING_LIMIT):
    """Scan all reachable content, messages, tag messages, paths and ref names."""
    locations = blob_locations(root, refs)
    blob_findings, groups, binary_objects, stats = _scan_blob(
        root, sorted(locations), locations, max_blob_bytes, string_limit
    )
    findings = list(blob_findings)

    # Paths themselves can carry a leaked token or a personal absolute path;
    # the stored locator masks any match so the report never echoes a filename.
    seen_paths = set()
    for pairs in locations.values():
        for _, path in pairs:
            if path in seen_paths:
                continue
            seen_paths.add(path)
            locator = redacted_locator(path)
            findings.extend(findings_in_text(path, locator, "", "<path>", groups))

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
                    groups,
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
                findings_in_text(contents, "<tag-message>", "", objectname, groups)
            )

    # Ref names are reported through an opaque locator, never verbatim.
    for ref in refs:
        findings.extend(findings_in_text(ref.name, "<ref-name>", "", ref.objectname, groups))

    stats["commits"] = len({sha for ref in refs for sha in _rev_list(root, ref)}) if refs else 0
    stats["commit_messages"] = commit_count
    stats["tag_messages"] = tag_message_count
    stats["paths"] = len(seen_paths)
    stats["unique_blobs"] = len(locations)
    return findings, groups, binary_objects, stats


def _rev_list(root, ref):
    commit = peel_commit(root, ref.name)
    if not commit:
        return ()
    return _git(root, "rev-list", commit).stdout.decode().split()


def scan_worktree(root):
    """Reuse the current-tree gate's file discovery with the combined rules."""
    findings = []
    groups = ValueGroups()
    for relative_path in BASE._git_paths(root):
        file_path = root / relative_path
        display = relative_path.as_posix()
        locator = redacted_locator(display)
        findings.extend(findings_in_text(display, locator, "", "<path>", groups))
        if file_path.is_symlink() or not file_path.is_file():
            continue
        try:
            raw = file_path.read_bytes()
        except OSError:
            continue
        text = ""
        if b"\0" in raw:
            text = extract_strings(raw, DEFAULT_STRING_LIMIT)
        else:
            text = raw.decode("utf-8", "replace")
        findings.extend(findings_in_text(text, locator, "", "<worktree>", groups))
    return findings, groups


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


def group_summary(groups):
    """Aggregate value groups by status/confidence for the report header."""
    summary = {}
    for group in groups:
        bucket = summary.setdefault(
            group["status"], {"groups": 0, "occurrences": 0, "confidence": group["confidence"]}
        )
        bucket["groups"] += 1
        bucket["occurrences"] += group["count"]
    return summary


def _section(findings, groups):
    findings = dedupe(findings)
    return {
        "counts": _counts(findings),
        "groups": group_summary(groups),
        "value_groups": groups,
        "findings": [
            finding.as_dict()
            for finding in sorted(
                findings, key=lambda f: (f.rule, f.path, f.line, f.commit)
            )
        ],
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


def _status_rollup(groups):
    """Compact (rule, status) aggregation used for the remote PR section."""
    buckets = {}
    for group in groups:
        key = (group["rule"], group["status"])
        entry = buckets.setdefault(key, {"groups": 0, "occurrences": 0})
        entry["groups"] += 1
        entry["occurrences"] += group["count"]
    return [(rule, status, entry) for (rule, status), entry in sorted(buckets.items())]


def _group_table(groups):
    rows = []
    for group in groups:
        location = group["locations"][0] if group["locations"] else {"path": "-", "line": "-"}
        rows.append(
            "| {rule} | {status} | {hash} | {count} | {hint} | {path}:{line} |".format(
                rule=group["rule"],
                status=group["status"],
                hash=group["value_sha256_12"],
                count=group["count"],
                hint=group["hint"] or "-",
                path=location["path"],
                line=location["line"],
            )
        )
    if not rows:
        return "_no value groups_"
    return "\n".join(
        [
            "| rule | status | value-sha256(12) | count | hint | first location |",
            "| --- | --- | --- | --- | --- | --- |",
        ]
        + rows
    )


def render_markdown(report):
    coverage = report["coverage"]
    limits = report["limits"]
    history = report["history"]
    worktree = report["worktree"]
    pending = [
        group
        for group in history["value_groups"]
        if group["status"] == STATUS_PENDING
    ]
    lines = [
        "# 仓库敏感信息扫描（当前树 + 全历史）",
        "",
        "本报告由 `scripts/dev/verify/repository/check-sensitive-history.py` 生成，只记录",
        "`rule/category/object/commit/path/line`、值哈希与统计；匹配到的值绝不写入报告，",
        "文件名或引用名自身含匹配值时改存脱敏 Locator。报告写在私有 workspace，不进公开代码。",
        "",
        "## 结论（自动分级，非裁决）",
        "",
        "- 所有自动命中都是**待人工确认的候选**，不是结论。",
        "- 仅当人工确认存在**真实凭据**时，才建议轮换该凭据，并在授权后清理历史；本工具不轮换、",
        "  不改写、不推送。",
        "- 个人绝对路径与内部环境标识属于信息暴露，**不能据此推断密钥泄漏**。",
        f"- 历史高置信度候选待核查值组：{_count(history, 'high', STATUS_PENDING)} 组；",
        f"  已证明为公开示例：{_count(history, 'high', STATUS_PUBLIC_EXAMPLE)} 组。",
        f"- 历史中置信度（通用/凭据 URL）待核查值组：{len(pending)} 组，累计 "
        f"{sum(group['count'] for group in pending)} 处（按唯一 blob/消息去重）；未证明为夹具的一律列在“值分级”中。",
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
        f"（文本 {coverage['blobs_scanned']}，二进制 {coverage['blobs_binary']}"
        f"（{coverage['binary_bytes']} bytes，已做 strings 候选扫描 {coverage['binary_strings_scanned']}），"
        f"超大 {coverage['blobs_too_large']}，LFS 指针 {coverage['blobs_lfs']}）",
        f"- 扫描 commit message 数：{coverage['commit_messages']}；annotated tag message 数：{coverage['tag_messages']}",
        f"- 扫描路径数：{coverage['paths']}；扫描 ref 名数：{coverage['refnames']}",
        f"- 扫描耗时：{coverage['duration_seconds']:.2f}s",
    ]
    remote = report.get("remote_pr_refs")
    if remote:
        lines.append(
            f"- 公开 PR refs：status={remote['status']}，refs={remote.get('refs', 0)}，"
            f"命中={remote.get('findings', '-')}"
        )
    lines += [
        "",
        "## 限制（limits）",
        "",
        f"- 单一 blob 上限：{limits['max_blob_bytes']} bytes（超出只计数、不扫描内容）",
        f"- 二进制判定：内容含 NUL 字节；仅对提取出的 ASCII/UTF-16 字符串做候选扫描，",
        "  **不宣称覆盖全部二进制内容**",
        f"- LFS 指针不追内容；Git LFS 对象不在本地对象库里，本次未下载",
        f"- 值分级只保存 sha256 前 12 位与脱敏位置，不保存原值",
        "",
        "## 边界（out of scope）",
        "",
    ]
    lines += [f"- {item}" for item in report["boundaries"]]
    lines += [
        "",
        "## 值分级（待核查证据，按值去重，不含原值）",
        "",
        _group_table(history["value_groups"]),
        "",
        "## 二进制对象（本次）",
        "",
    ]
    if report["binary_objects"]:
        lines += [
            "| object | size | first path | strings 已扫描 |",
            "| --- | --- | --- | --- |",
        ]
        for item in report["binary_objects"]:
            path = item["paths"][0] if item["paths"] else "-"
            lines.append(f"| {item['object'][:12]} | {item['size']} | {path} | yes |")
    else:
        lines.append("_no binary blobs_")
    if remote and remote.get("status") == "scanned":
        lines += [
            "",
            "## 公开 PR refs 值分级（汇总，明细见 JSON）",
            "",
            "| rule | status | value groups | occurrences |",
            "| --- | --- | --- | --- |",
        ]
        for rule, status, entry in _status_rollup(remote.get("value_groups", [])):
            lines.append(f"| {rule} | {status} | {entry['groups']} | {entry['occurrences']} |")
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
        "## 需要的用户动作",
        "",
        "- 逐项核查“值分级”中 status=pending-review 的组；确认是真实凭据才轮换，并在授权后清理历史；",
        "  确认是夹具则记录理由，不能因为文件在测试目录就默认安全。",
        "- personal-data-exposure / environment-identifier 组不是凭据，但属信息暴露，是否清理历史需授权。",
        "- 本工具只读且不上传：不推送代码、不联网校验密钥。",
    ]
    return "\n".join(lines) + "\n"


def _count(section, confidence, status):
    """Number of distinct value groups in a (confidence, status) bucket."""
    return sum(
        1
        for group in section["value_groups"]
        if group["confidence"] == confidence and group["status"] == status
    )


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
        "binary_bytes": stats["binary_bytes"],
        "binary_strings_scanned": stats["binary_strings_scanned"],
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
    "远端 Pull Request refs 需显式开启 `--remote-pr`；未开启时 `refs/pull/*` 不在本报告的本地 refs 内。",
    "Git submodule 的内容不在父仓对象库里，本次只扫描 gitlink 指针本身。",
    "已 fetch 的 refs 之外的远端分支不可见；覆盖以本次 refs 清单为准。",
    "二进制 blob 只做 ASCII/UTF-16 字符串候选扫描，不等价于覆盖其全部内容。",
)


def redact_url(url):
    """Strip any userinfo (http or scp-like) before a URL can reach a report."""
    if "://" in url:
        return re.sub(r"://[^/@\s]*@", "://***@", url)
    return re.sub(r"^[^@/\s]+@", "***@", url)


def scan_remote_pr_refs(root, max_blob_bytes, string_limit=DEFAULT_STRING_LIMIT):
    """Audit public pull-request refs without touching the audited repository.

    Only OIDs are read from the remote; when PR refs exist they are fetched into
    an isolated temporary bare repository under its own ``refs/pull/*``
    namespace, so the audited repository's refs are never modified.
    """
    listed = _git(root, "remote", "get-url", "origin", check=False)
    url = listed.stdout.decode("utf-8", "replace").strip() if listed.returncode == 0 else ""
    if not url:
        return {"status": "no-origin"}
    display = redact_url(url)
    remote = _git(
        root, "ls-remote", "--refs", url, "refs/pull/*/head", "refs/pull/*/merge", check=False
    )
    if remote.returncode != 0:
        return {"status": "ls-remote-failed", "url": display}
    refs = [
        line.split("\t", 1)[1]
        for line in remote.stdout.decode("utf-8", "replace").splitlines()
        if line.strip()
    ]
    if not refs:
        return {"status": "none", "url": display, "refs": 0}
    workspace = Path(tempfile.mkdtemp(prefix="kk-studio-pr-refs-"))
    try:
        bare = workspace / "pr.git"
        subprocess.run(
            ["git", "init", "--bare", "--quiet", str(bare)],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=True,
        )
        fetched = subprocess.run(
            [
                "git",
                "--git-dir",
                str(bare),
                "fetch",
                "--no-tags",
                "--quiet",
                url,
                "+refs/pull/*:refs/pull/*",
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        if fetched.returncode != 0:
            return {"status": "fetch-failed", "url": display, "refs": len(refs)}
        sub_report = run(bare, max_blob_bytes, include_worktree=False, string_limit=string_limit)
        return {
            "status": "scanned",
            "url": display,
            "refs": len(refs),
            "findings": len(sub_report["history"]["findings"]),
            "coverage": sub_report["coverage"],
            "value_groups": sub_report["history"]["value_groups"],
        }
    finally:
        shutil.rmtree(workspace, ignore_errors=True)


def run(
    root,
    max_blob_bytes=DEFAULT_MAX_BLOB_BYTES,
    include_worktree=True,
    fetch=False,
    string_limit=DEFAULT_STRING_LIMIT,
):
    root = Path(root).resolve()
    fetch_result = None
    if fetch:
        fetched = _git(root, "fetch", "--tags", "origin", check=False)
        fetch_result = "ok" if fetched.returncode == 0 else "failed"
        if fetch_result == "failed":
            sys.stderr.write("git-fetch-failed\n")
    start = time.time()
    refs = list_refs(root)
    history_findings, history_groups, binary_objects, stats = scan_history(
        root, refs, max_blob_bytes, string_limit
    )
    if include_worktree:
        work_findings, work_groups = scan_worktree(root)
        worktree = (work_findings, work_groups.render())
    else:
        worktree = ([], [])
    duration = time.time() - start
    coverage = coverage_stats(root, refs, stats, duration)
    if fetch_result is not None:
        coverage["fetch"] = fetch_result
    limits = {
        "max_blob_bytes": max_blob_bytes,
        "binary_string_limit": string_limit,
    }
    return {
        "repository": "kk-studio",
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "coverage": coverage,
        "limits": limits,
        "boundaries": list(BOUNDARIES),
        "worktree": _section(worktree[0], worktree[1]),
        "history": _section(history_findings, history_groups.render()),
        "binary_objects": binary_objects[:MAX_BINARY_OBJECTS],
        "binary_objects_truncated": len(binary_objects) > MAX_BINARY_OBJECTS,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Audit the full Git history and working tree for sensitive data (redacted)."
    )
    parser.add_argument("--root", type=Path, default=BASE.REPOSITORY_ROOT)
    parser.add_argument("--json", type=Path, help="write the redacted JSON report here")
    parser.add_argument("--markdown", type=Path, help="write the redacted markdown report here")
    parser.add_argument("--max-blob-bytes", type=int, default=DEFAULT_MAX_BLOB_BYTES)
    parser.add_argument("--string-limit", type=int, default=DEFAULT_STRING_LIMIT)
    parser.add_argument("--no-worktree", action="store_true", help="skip the current-tree scan")
    parser.add_argument("--fetch", action="store_true", help="refresh origin heads/tags first")
    parser.add_argument(
        "--remote-pr", action="store_true", help="also audit public refs/pull/* in a temp bare repo"
    )
    parser.add_argument("--fail-on-high", action="store_true", help="exit 1 on high-confidence hits")
    args = parser.parse_args(argv)

    try:
        report = run(
            args.root,
            args.max_blob_bytes,
            include_worktree=not args.no_worktree,
            fetch=args.fetch,
            string_limit=args.string_limit,
        )
        if args.remote_pr:
            report["remote_pr_refs"] = scan_remote_pr_refs(
                args.root, args.max_blob_bytes, args.string_limit
            )
    except (OSError, subprocess.CalledProcessError) as error:
        sys.stderr.write(f"scan-error {type(error).__name__}\n")
        return 2

    if args.json:
        args.json.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
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
    if report.get("remote_pr_refs"):
        print("remote_pr_refs=" + json.dumps(report["remote_pr_refs"].get("status")))
    if args.fail_on_high and any(
        is_high(finding["rule"]) for finding in report["history"]["findings"]
    ):
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
