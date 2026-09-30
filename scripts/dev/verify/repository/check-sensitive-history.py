#!/usr/bin/env python3
"""Audit the whole public Git history (and the working tree) for sensitive data.

The current-tree gate in ``check-sensitive-data.py`` only sees files that exist
today, so it can never prove anything about what was committed earlier. This
entry reuses that gate's rules, adds a few broad credential shapes, and walks
every ref that is visible in the local repository:

* reachable blobs (content is read once per unique blob through the git
  ``--batch`` streaming protocol instead of once per commit; binary blobs are
  additionally reduced to printable ASCII/UTF-16 strings before scanning);
* the full content of every annotated tag object, including tag targets that are
  blobs or trees (which a commit walk alone would never reach);
* commit messages;
* file paths and ref names, with the stored location redacted when the path
  itself contains a match;
* optionally the public pull-request refs (``refs/pull/*``) pulled into an
  isolated temporary bare repository so the audited repository's refs stay
  untouched.

Every read that could not complete is recorded as a degraded-scan item instead
of being skipped silently: a short ``cat-file`` body, a missing object, a
malformed header, a non-zero ``cat-file`` exit, an unreadable worktree file, an
unsupported ref target, or a remote-coverage check that cannot be verified.
``--fail-on-high`` and ``scan_incomplete`` are never reported as success.

Nothing here is a verdict. Every hit is a *candidate pending human review*: the
report recommends credential rotation only after a reviewer confirms a real
credential, and never infers key leakage from a personal absolute path. Values
never leave the scanner; value-level evidence is stored as a short hash plus
redacted locations so a reviewer can grade each group by hand. The remote URL is
reduced to the opaque label ``origin``, so no query token, userinfo, host or
local path can reach a report.
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

# Hosts that are *usually* harmless (reserved example domains and loopback
# aliases). They are only used to label a hit: a password on such a host can
# still be a real credential, so nothing here removes a finding. The label lets
# a reviewer grade the group instead of the tool deciding for them.
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
# ``key`` after the name may be quoted (``"apiKey": "..."``) or camelCased
# (``sensitiveKey``/``clientSecret``/``accessToken``), so the quoted form and the
# bare ``key``/``token`` tails are both accepted; a missed key is worse than a
# noisy candidate, because every hit stays pending human review anyway. The
# separator only spans blanks, never a newline: otherwise a key name at the end
# of one line would be glued to the next line and reported as a fake "value".
GENERIC_SECRET_KEYS = (
    "api[_-]?key",
    "apikey",
    "secret",
    "secret[_-]?key",
    "client[_-]?secret",
    "access[_-]?key",
    "access[_-]?token",
    "auth[_-]?token",
    "api[_-]?token",
    "refresh[_-]?token",
    "id[_-]?token",
    "private[_-]?key",
    "passwd",
    "password",
    "bearer",
    "token",
    "key",
)
GENERIC_SECRET_PATTERN = re.compile(
    "(?i)(?P<key>"
    + "|".join(GENERIC_SECRET_KEYS)
    + ")"
    + "[ \t]*[\"']?[ \t]*[:=][ \t]*"
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

BATCH_EOF = object()


class ScanIssues:
    """Everything the scan could not read completely, as value-free rows.

    A degraded item never contains a matched value, a raw blob body or a remote
    URL: only the surface, a machine-readable detail and (optionally) an object
    id / redacted locator. An empty collector means "nothing was skipped"; it is
    not a statement that the content is clean.
    """

    MAX_ITEMS = 50

    def __init__(self):
        self.items = []
        self.omitted = 0

    def add(self, surface, detail, obj=""):
        if len(self.items) >= self.MAX_ITEMS:
            self.omitted += 1
            return
        self.items.append({"surface": surface, "detail": detail, "object": obj})

    def extend(self, other):
        for item in other.items:
            self.add(item["surface"], item["detail"], item["object"])
        self.omitted += other.omitted

    def as_dict(self):
        return {
            "incomplete": bool(self.items or self.omitted),
            "items": self.items,
            "omitted": self.omitted,
        }


def is_high(rule):
    """True when a rule is graded high confidence (still not a verdict)."""
    return CATEGORY_CONFIDENCE.get(rule, ("other", "medium"))[1] == "high"


def is_reserved_host(host):
    """True for hosts that are usually fixtures; used as a hint, not a filter."""
    host = host.rsplit(":", 1)[0].strip("[]").lower()
    if host in RESERVED_HOST_NAMES:
        return True
    return any(host == suffix or host.endswith("." + suffix) for suffix in RESERVED_HOST_SUFFIXES)


def _is_allowlisted(rule_name, match):
    """Rule-specific allowlist; only shapes that cannot be real secrets pass.

    ``credential-url`` deliberately has no allowlist: a password on a reserved
    host is still a candidate, so the host only becomes a group hint.
    """
    if rule_name == "personal-path":
        return match.group("user") in BASE.ALLOWED_USER_NAMES
    return False


def classify_value(rule, value, hint=""):
    """Grade one matched value without ever exposing it (status, hint)."""
    if rule == "personal-path":
        return STATUS_PERSONAL_DATA, ""
    if rule == "private-environment":
        return STATUS_ENVIRONMENT, ""
    if value in KNOWN_PUBLIC_EXAMPLES:
        return STATUS_PUBLIC_EXAMPLE, "published-vendor-example"
    hints = [hint] if hint else []
    placeholder = PLACEHOLDER_HINT.search(value)
    if placeholder:
        hints.append(placeholder.group(0).lower())
    return STATUS_PENDING, ",".join(hints)


_LOCATOR_CACHE = {}


def redacted_locator(path):
    """Return the path with every rule match masked by its rule name.

    Overlapping or touching spans are merged into one union before masking: with
    a "skip the overlapping span" rule the tail of the dropped span would stay
    visible in the report.
    """
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
    merged = []
    for start, end, rule_name in sorted(spans):
        if merged and start <= merged[-1][1]:
            previous_start, previous_end, rules = merged[-1]
            merged[-1] = (previous_start, max(previous_end, end), rules | {rule_name})
        else:
            merged.append((start, end, {rule_name}))
    parts = []
    last = 0
    for start, end, rules in merged:
        parts.append(path[last:start])
        parts.append("<redacted:" + "+".join(sorted(rules)) + ">")
        last = end
    parts.append(path[last:])
    locator = "".join(parts)
    _LOCATOR_CACHE[path] = locator
    return locator


class ValueGroups:
    """Value-level evidence: sha256 group + redacted locations, never the value."""

    def __init__(self):
        self._groups = {}

    def add(self, rule, value, locator, line, hint=""):
        digest = hashlib.sha256(value.encode("utf-8", "surrogateescape")).hexdigest()[:12]
        group = self._groups.get((rule, digest))
        if group is None:
            status, hint_text = classify_value(rule, value, hint)
            category, confidence = CATEGORY_CONFIDENCE.get(rule, ("other", "medium"))
            group = {
                "rule": rule,
                "category": category,
                "confidence": confidence,
                "value_sha256_12": digest,
                "status": status,
                "hint": hint_text,
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


def identifier_before(text, index, limit=32):
    """The identifier token ending right before ``index`` (used for group hints).

    Hints never remove a candidate; they only tell a reviewer which key name was
    matched, so ``ariaKey``/``labelKey`` style matches can be triaged instead of
    being filtered away automatically.
    """
    start = index
    while start > 0 and (text[start - 1].isalnum() or text[start - 1] in "_-"):
        start -= 1
    return text[start:index][-limit:]


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
                hints = []
                if rule_name == "credential-url" and is_reserved_host(match.group("host")):
                    hints.append("reserved-host")
                if rule_name == "generic-secret":
                    identifier = (
                        identifier_before(text, match.start()) + match.group("key")
                    )
                    hints.append("key-name:" + identifier.lower())
                groups.add(
                    rule_name,
                    match.group(0),
                    path,
                    line_number,
                    hint=",".join(hint for hint in hints if not hint.endswith(":")),
                )
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


def blob_locations(root, revisions):
    """Map every reachable blob to the introducing (commit, path) pairs.

    ``git log --raw`` reports each blob once per commit that actually changed
    it, so the union over every commit covers all reachable content without
    re-reading trees per commit. Only commit-reachable refs are passed in: a ref
    that points at a blob or a tree would make ``git log`` fail, and those
    targets are enumerated separately by :func:`ref_target_blobs`.
    """
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


def peeled_object(root, revision):
    """Resolve a revision through tag chains to ``(oid, objecttype)``."""
    completed = _git(root, "rev-parse", "--verify", "--quiet", revision + "^{}", check=False)
    if completed.returncode != 0 or not completed.stdout.strip():
        return None, None
    oid = completed.stdout.decode("ascii", "replace").strip()
    typed = _git(root, "cat-file", "-t", oid, check=False)
    if typed.returncode != 0:
        return oid, None
    return oid, typed.stdout.decode("utf-8", "replace").strip()


def tree_blobs(root, treeish):
    """Every blob reachable from a tree-ish, as ``(oid, path)`` pairs."""
    completed = _git(root, "ls-tree", "-r", "-z", treeish)
    pairs = []
    for record in completed.stdout.split(b"\0"):
        if not record.strip():
            continue
        meta, _, path = record.partition(b"\t")
        fields = meta.split()
        if len(fields) >= 3 and fields[1] == b"blob":
            pairs.append(
                (fields[2].decode("ascii", "replace"), path.decode("utf-8", "surrogateescape"))
            )
    return pairs


def ref_target_blobs(root, refs, issues):
    """Cover refs whose target is not a commit (tags to blobs or trees).

    A commit walk never reaches these objects, so they would otherwise be
    counted as covered while nothing was read. Anything that cannot be resolved
    or enumerated is recorded as a degraded-scan item.
    """
    locations = {}
    for ref in refs:
        if ref.name.startswith("refs/remotes/") or ref.name == "HEAD":
            continue
        if peel_commit(root, ref.name):
            continue
        oid, object_type = peeled_object(root, ref.name)
        if oid is None:
            issues.add("ref", "unresolvable-ref", ref.name)
            continue
        if object_type == "blob":
            locations.setdefault(oid, set()).add(("", f"<ref:{ref.name}>"))
        elif object_type == "tree":
            for blob_oid, path in tree_blobs(root, oid):
                locations.setdefault(blob_oid, set()).add(("", f"<ref:{ref.name}>/{path}"))
        elif object_type != "commit":
            issues.add("ref", f"unsupported-ref-target:{object_type}", ref.name)
    return locations


def _read(stream, size, keep=True, chunk=1 << 16, issues=None, objective=""):
    """Read exactly ``size`` bytes; a short body is recorded, never ignored."""
    data = bytearray() if keep else None
    remaining = size
    while remaining > 0:
        piece = stream.read(min(remaining, chunk))
        if not piece:
            break
        if data is not None:
            data.extend(piece)
        remaining -= len(piece)
    if remaining > 0 and issues is not None:
        issues.add("blob", f"short-read missing={remaining} of={size}", objective)
    return bytes(data) if data is not None else b""


def read_batch_entry(stream, issues, max_blob_bytes):
    """Read one ``git cat-file --batch`` record, or ``BATCH_EOF`` at end of stream.

    Returns a dict with ``oid``/``type``/``size``/``content``/``too_large``. Every
    anomaly (missing object, malformed header, short body, missing record
    terminator) is recorded on ``issues`` and yields ``content=None`` so the
    caller cannot mistake it for an empty blob.
    """
    header = stream.readline()
    if not header:
        return BATCH_EOF
    parts = header.rstrip(b"\n").split()
    oid = parts[0].decode("ascii", "replace") if parts else ""
    if len(parts) == 2 and parts[1] == b"missing":
        issues.add("object", "missing-object", oid)
        return {"oid": oid, "type": "missing", "size": 0, "content": None, "too_large": False}
    if len(parts) < 3 or not parts[2].isdigit():
        issues.add("object", "malformed-header", oid)
        return {"oid": oid, "type": "malformed", "size": 0, "content": None, "too_large": False}
    object_type = parts[1].decode("utf-8", "replace")
    size = int(parts[2])
    too_large = object_type == "blob" and size > max_blob_bytes
    content = _read(stream, size, keep=not too_large, issues=issues, objective=oid)
    terminator = stream.read(1)
    if terminator not in (b"", b"\n"):
        issues.add("object", "missing-record-terminator", oid)
    return {
        "oid": oid,
        "type": object_type,
        "size": size,
        "content": content,
        "too_large": too_large,
    }


def _scan_blob(root, blob_ids, locations, max_blob_bytes, string_limit):
    """Stream every unique blob once; return findings, groups, counters, issues."""
    stats = {
        "too_large": 0,
        "binary": 0,
        "binary_strings_scanned": 0,
        "lfs": 0,
        "scanned": 0,
        "binary_bytes": 0,
    }
    issues = ScanIssues()
    findings = []
    groups = ValueGroups()
    binary_objects = []
    if not blob_ids:
        # No reachable blob at all: nothing to stream, and an empty batch input
        # would otherwise look like a malformed record.
        return findings, groups, binary_objects, stats, issues
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
                # stderr is dropped, not captured: a full stderr pipe could block
                # git, and git's stderr text could carry paths we must not store.
                stderr=subprocess.DEVNULL,
            )
            try:
                stream = process.stdout
                while True:
                    entry = read_batch_entry(stream, issues, max_blob_bytes)
                    if entry is BATCH_EOF:
                        break
                    if entry["content"] is None:
                        continue
                    if entry["type"] != "blob":
                        issues.add("object", f"unexpected-object-type:{entry['type']}", entry["oid"])
                        continue
                    if entry["too_large"]:
                        stats["too_large"] += 1
                        continue
                    content = entry["content"]
                    size = entry["size"]
                    oid_text = entry["oid"]
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
                stream.close()
                if process.wait() != 0:
                    issues.add("object", f"cat-file-exit {process.returncode}")
    finally:
        list_path.unlink(missing_ok=True)
    return findings, groups, binary_objects, stats, issues


def tag_message(root, objectname, issues, refname):
    """Full message body of an annotated tag object (headers stripped)."""
    completed = _git(root, "cat-file", "tag", objectname, check=False)
    if completed.returncode != 0:
        issues.add("tag", f"cat-file-tag-exit {completed.returncode}", refname)
        return None
    _, separator, body = completed.stdout.partition(b"\n\n")
    return (body if separator else b"").decode("utf-8", "replace").rstrip("\n")


def annotated_tags(root, issues):
    """``(objectname, refname)`` for every annotated tag object."""
    out = _git(
        root,
        "for-each-ref",
        "--format=%(objecttype)%00%(objectname)%00%(refname)",
        "refs/tags",
    ).stdout.decode("utf-8", "replace")
    tags = []
    for line in out.splitlines():
        if not line.strip():
            continue
        object_type, _, remainder = line.partition("\0")
        objectname, _, refname = remainder.partition("\0")
        if object_type != "tag":
            continue
        if not objectname or not refname:
            issues.add("tag", "malformed-tag-record", refname)
            continue
        tags.append((objectname, refname))
    return tags


def scan_history(root, refs, max_blob_bytes, string_limit=DEFAULT_STRING_LIMIT):
    """Scan all reachable content, messages, tag objects, paths and ref names."""
    issues = ScanIssues()
    commit_refs = [ref.name for ref in refs if peel_commit(root, ref.name)]
    locations = blob_locations(root, commit_refs)
    real_paths = set()
    for pairs in locations.values():
        real_paths.update(path for _, path in pairs)
    # Refs whose target is a blob or a tree are unreachable from a commit walk.
    extra_locations = ref_target_blobs(root, refs, issues)
    for blob_oid, pairs in extra_locations.items():
        locations.setdefault(blob_oid, set()).update(pairs)

    blob_findings, groups, binary_objects, stats, blob_issues = _scan_blob(
        root, sorted(locations), locations, max_blob_bytes, string_limit
    )
    issues.extend(blob_issues)
    findings = list(blob_findings)

    # Paths themselves can carry a leaked token or a personal absolute path;
    # the stored locator masks any match so the report never echoes a filename.
    for path in sorted(real_paths):
        locator = redacted_locator(path)
        findings.extend(findings_in_text(path, locator, "", "<path>", groups))

    # Commit messages (explicit revisions so a detached HEAD is covered too).
    commit_count = 0
    if commit_refs:
        log = _git(root, "log", "--format=%x01%H%x00%B", *commit_refs).stdout
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

    # Annotated tag objects: read the whole object, not the first output line.
    tag_message_count = 0
    for objectname, refname in annotated_tags(root, issues):
        message = tag_message(root, objectname, issues, refname)
        if message is None:
            continue
        tag_message_count += 1
        findings.extend(findings_in_text(message, "<tag-message>", "", objectname, groups))

    # Ref names are reported through an opaque locator, never verbatim.
    for ref in refs:
        findings.extend(findings_in_text(ref.name, "<ref-name>", "", ref.objectname, groups))

    stats["commits"] = len({sha for ref in refs for sha in _rev_list(root, ref)}) if refs else 0
    stats["commit_messages"] = commit_count
    stats["tag_messages"] = tag_message_count
    stats["paths"] = len(real_paths)
    stats["unique_blobs"] = len(locations)
    stats["non_commit_refs"] = len(extra_locations)
    return findings, groups, binary_objects, stats, issues


def _rev_list(root, ref):
    commit = peel_commit(root, ref.name)
    if not commit:
        return ()
    return _git(root, "rev-list", commit).stdout.decode().split()


def scan_worktree(root):
    """Reuse the current-tree gate's file discovery with the combined rules."""
    findings = []
    groups = ValueGroups()
    issues = ScanIssues()
    for relative_path in BASE._git_paths(root):
        file_path = root / relative_path
        display = relative_path.as_posix()
        locator = redacted_locator(display)
        findings.extend(findings_in_text(display, locator, "", "<path>", groups))
        if file_path.is_symlink() or not file_path.is_file():
            continue
        try:
            raw = file_path.read_bytes()
        except OSError as error:
            # An unreadable file is a coverage gap, never a silent skip.
            issues.add("worktree", f"read-failed:{type(error).__name__}", locator)
            continue
        if b"\0" in raw:
            text = extract_strings(raw, DEFAULT_STRING_LIMIT)
        else:
            text = raw.decode("utf-8", "replace")
        findings.extend(findings_in_text(text, locator, "", "<worktree>", groups))
    return findings, groups, issues


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
    degraded = report["degraded"]
    remote_check = coverage["remote"]
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
        f"- 扫描完整性：scan_incomplete={degraded['incomplete']}"
        f"（未读全项 {len(degraded['items'])}，省略 {degraded['omitted']}）；"
        "incomplete=true 时不得视为“已覆盖全部内容”。",
        "",
        "## 覆盖范围（coverage）",
        "",
        f"- shallow 仓库：{coverage['shallow']}",
        f"- refs 总数：{coverage['refs_total']}"
        f"（heads={coverage['refs_heads']}, remotes={coverage['refs_remotes']},"
        f" tags={coverage['refs_tags']}, other={coverage['refs_other']}）",
        f"- 本地 origin 跟踪 refs：{remote_check['tracking_refs']}"
        f"；远端覆盖核对：{remote_check['status']}"
        f"（heads={remote_check['heads']}, tags={remote_check['tags']},"
        f" 本地缺失={remote_check['absent_locally']}, OID 不一致={remote_check['oid_mismatch']}）",
        f"- HEAD commit：`{coverage['head_commit']}`",
        f"- 可达 commit 数：{coverage['commits']}",
        f"- 唯一可达 blob 数：{coverage['unique_blobs']}"
        f"（文本 {coverage['blobs_scanned']}，二进制 {coverage['blobs_binary']}"
        f"（{coverage['binary_bytes']} bytes，已做 strings 候选扫描 {coverage['binary_strings_scanned']}），"
        f"超大 {coverage['blobs_too_large']}，LFS 指针 {coverage['blobs_lfs']}）",
        f"- 扫描 commit message 数：{coverage['commit_messages']}；annotated tag message 数：{coverage['tag_messages']}",
        f"- 非 commit 目标的 ref 数：{coverage['non_commit_refs']}（tag 指向 blob/tree 时按可达对象扫描）",
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
        "## 扫描完整性（degraded）",
        "",
        "以下每一项都表示“这块内容没有被完整读取”，因此本次扫描不能宣称覆盖它。",
        "",
    ]
    if degraded["items"]:
        lines += ["| surface | detail | object |", "| --- | --- | --- |"]
        for item in degraded["items"]:
            lines.append(
                f"| {item['surface']} | {item['detail']} | {item['object'] or '-'} |"
            )
        if degraded["omitted"]:
            lines.append(f"\n_另有 {degraded['omitted']} 项未列出（上限 {ScanIssues.MAX_ITEMS}）。_")
    else:
        lines.append("_本次没有未读全项（不代表内容干净）。_")
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
            "## 公开 PR refs 值分级（汇总）",
            "",
            "| rule | status | value groups | occurrences |",
            "| --- | --- | --- | --- |",
        ]
        for rule, status, entry in _status_rollup(remote.get("value_groups", [])):
            lines.append(f"| {rule} | {status} | {entry['groups']} | {entry['occurrences']} |")
        lines += [
            "",
            "## 公开 PR refs findings（明细）",
            "",
            _table(remote.get("findings_detail", [])),
        ]
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
        "- 若扫描完整性为 incomplete，先处理 degraded 明细再采信覆盖范围。",
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


def coverage_stats(root, refs, stats, duration, remote):
    heads = [ref for ref in refs if ref.name.startswith("refs/heads/")]
    remotes = [ref for ref in refs if ref.name.startswith("refs/remotes/")]
    tags = [ref for ref in refs if ref.name.startswith("refs/tags/")]
    other = [
        ref
        for ref in refs
        if not ref.name.startswith(("refs/heads/", "refs/remotes/", "refs/tags/"))
    ]
    return {
        "shallow": is_shallow(root),
        "refs_total": len(refs),
        "refs_heads": len(heads),
        "refs_remotes": len(remotes),
        "refs_tags": len(tags),
        "refs_other": len(other),
        "remote": remote,
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
        "non_commit_refs": stats["non_commit_refs"],
        "paths": stats["paths"],
        "refnames": len(refs),
        "duration_seconds": duration,
    }


def remote_coverage(root, refs):
    """Compare local tracking refs with the remote's advertised OIDs.

    "some remote-tracking ref exists" is not a coverage claim. The remote is
    asked for its exact heads/tags and every advertised ref is compared with the
    local object id; anything the local repository cannot see, or any failure to
    ask, is reported instead of being presented as full coverage. The URL itself
    never enters the report: the remote is only ever labelled ``origin``.
    """
    result = {
        "label": "origin",
        "tracking_refs": sum(
            1 for ref in refs if ref.name.startswith("refs/remotes/origin/")
        ),
        "status": "no-remote",
        "heads": 0,
        "tags": 0,
        "absent_locally": 0,
        "oid_mismatch": 0,
    }
    listed = _git(root, "remote", "get-url", "origin", check=False)
    if listed.returncode != 0 or not listed.stdout.strip():
        return result
    advertised = _git(
        root, "ls-remote", "--refs", "origin", "refs/heads/*", "refs/tags/*", check=False
    )
    if advertised.returncode != 0:
        result["status"] = "ls-remote-failed"
        return result
    local = {}
    for ref in refs:
        if ref.name.startswith("refs/remotes/origin/"):
            local["refs/heads/" + ref.name[len("refs/remotes/origin/") :]] = ref.objectname
        elif ref.name.startswith("refs/tags/"):
            local[ref.name] = ref.objectname
    for line in advertised.stdout.decode("utf-8", "replace").splitlines():
        fields = line.split("\t")
        if len(fields) != 2:
            continue
        oid, name = fields[0].strip(), fields[1].strip()
        if not oid or not name:
            continue
        result["heads" if name.startswith("refs/heads/") else "tags"] += 1
        known = local.get(name)
        if known is None:
            result["absent_locally"] += 1
        elif known != oid:
            result["oid_mismatch"] += 1
    result["status"] = (
        "verified" if not (result["absent_locally"] or result["oid_mismatch"]) else "mismatch"
    )
    return result


BOUNDARIES = (
    "reflog 条目（`git reflog`）与未写入 refs 的悬空对象不在可达 refs 内，本次不扫描。",
    "远端 Pull Request refs 需显式开启 `--remote-pr`；未开启时 `refs/pull/*` 不在本报告的本地 refs 内。",
    "Git submodule 的内容不在父仓对象库里，本次只扫描 gitlink 指针本身。",
    "已 fetch 的 refs 之外的远端分支不可见；远端覆盖由 `ls-remote` OID 核对给出，核对失败一律记为 incomplete。",
    "二进制 blob 只做 ASCII/UTF-16 字符串候选扫描，不等价于覆盖其全部内容。",
    "报告只以 `origin` 标签指代远端，不写入远端 URL（避免 query token 与本机路径进入报告）。",
)


def scan_remote_pr_refs(root, max_blob_bytes, string_limit=DEFAULT_STRING_LIMIT):
    """Audit public pull-request refs without touching the audited repository.

    Only OIDs are read from the remote; when PR refs exist they are fetched into
    an isolated temporary bare repository under its own ``refs/pull/*``
    namespace, so the audited repository's refs are never modified. The report
    carries the per-finding rows, not just group counts, so the caller can gate
    on the same evidence it would get from the local history.
    """
    listed = _git(root, "remote", "get-url", "origin", check=False)
    if listed.returncode != 0 or not listed.stdout.strip():
        return {"status": "no-origin", "remote": "origin"}
    # The URL is only used to talk to git here; it is never stored or printed.
    url = listed.stdout.decode("utf-8", "replace").strip()
    remote = _git(
        root, "ls-remote", "--refs", "origin", "refs/pull/*/head", "refs/pull/*/merge", check=False
    )
    if remote.returncode != 0:
        return {"status": "ls-remote-failed", "remote": "origin"}
    refs = [
        line.split("\t", 1)[1]
        for line in remote.stdout.decode("utf-8", "replace").splitlines()
        if line.strip()
    ]
    if not refs:
        return {"status": "none", "remote": "origin", "refs": 0}
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
            cwd=root,
        )
        if fetched.returncode != 0:
            return {"status": "fetch-failed", "remote": "origin", "refs": len(refs)}
        sub_report = run(bare, max_blob_bytes, include_worktree=False, string_limit=string_limit)
        return {
            "status": "scanned",
            "remote": "origin",
            "refs": len(refs),
            "findings": len(sub_report["history"]["findings"]),
            "findings_detail": sub_report["history"]["findings"],
            "coverage": sub_report["coverage"],
            "value_groups": sub_report["history"]["value_groups"],
            "degraded": sub_report["degraded"],
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
    if max_blob_bytes < 0 or string_limit < 0:
        raise ValueError("blob byte and string limits must not be negative")
    root = Path(root).resolve()
    fetch_result = None
    if fetch:
        fetched = _git(root, "fetch", "--tags", "origin", check=False)
        fetch_result = "ok" if fetched.returncode == 0 else "failed"
        if fetch_result == "failed":
            sys.stderr.write("git-fetch-failed\n")
    start = time.time()
    refs = list_refs(root)
    history_findings, history_groups, binary_objects, stats, history_issues = scan_history(
        root, refs, max_blob_bytes, string_limit
    )
    if include_worktree:
        work_findings, work_groups, work_issues = scan_worktree(root)
        worktree = (work_findings, work_groups.render())
    else:
        worktree = ([], [])
        work_issues = ScanIssues()
    remote = remote_coverage(root, refs)
    duration = time.time() - start
    issues = ScanIssues()
    issues.extend(history_issues)
    issues.extend(work_issues)
    if remote["status"] in {"ls-remote-failed", "mismatch"}:
        issues.add("remote", "remote-coverage:" + remote["status"])
    coverage = coverage_stats(root, refs, stats, duration, remote)
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
        "degraded": issues.as_dict(),
        "worktree": _section(worktree[0], worktree[1]),
        "history": _section(history_findings, history_groups.render()),
        "binary_objects": binary_objects[:MAX_BINARY_OBJECTS],
        "binary_objects_truncated": len(binary_objects) > MAX_BINARY_OBJECTS,
    }


def high_confidence_findings(report):
    """Every high-confidence finding row the report holds, across all surfaces."""
    rows = list(report["history"]["findings"]) + list(report["worktree"]["findings"])
    remote = report.get("remote_pr_refs") or {}
    rows.extend(remote.get("findings_detail") or [])
    return [row for row in rows if is_high(row["rule"])]


def mark_remote_pr_degraded(report):
    """Record a failed PR audit as a degraded item instead of a silent success."""
    remote = report.get("remote_pr_refs") or {}
    if remote.get("status") in {"ls-remote-failed", "fetch-failed"}:
        report["degraded"]["items"].append(
            {"surface": "remote-pr", "detail": "status:" + remote["status"], "object": "origin"}
        )
        report["degraded"]["incomplete"] = True
    nested = remote.get("degraded")
    if nested and nested.get("incomplete"):
        report["degraded"]["items"].append(
            {"surface": "remote-pr", "detail": "nested-scan-incomplete", "object": "origin"}
        )
        report["degraded"]["incomplete"] = True


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

    if args.max_blob_bytes < 0 or args.string_limit < 0:
        sys.stderr.write("invalid-arguments: limits must not be negative\n")
        return 2

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
            mark_remote_pr_degraded(report)
    except (OSError, subprocess.CalledProcessError, ValueError) as error:
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
    high = high_confidence_findings(report)
    print(f"high_confidence_findings={len(high)}")
    incomplete = report["degraded"]["incomplete"]
    if incomplete:
        sys.stderr.write(
            f"scan-incomplete items={len(report['degraded']['items'])}"
            f" omitted={report['degraded']['omitted']}\n"
        )
    if args.fail_on_high and high:
        return 1
    if incomplete:
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
