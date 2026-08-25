#!/usr/bin/env python3
"""Scan tracked and non-ignored untracked files for high-confidence secrets."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
ALLOWED_USER_NAMES = frozenset({"dev", "test", "user", "kkdaemon"})

UNIX_PERSONAL_PATH_PATTERN = re.compile(
    r"(?<![A-Za-z0-9._-])/(?:home|Users)/"
    r"(?P<user>[A-Za-z0-9][A-Za-z0-9._-]*)"
    r"(?![A-Za-z0-9._-])"
)
WINDOWS_PERSONAL_PATH_PATTERN = re.compile(
    r"(?<![A-Za-z0-9._-])[A-Za-z]:[\\/]+Users[\\/]+"
    r"(?P<user>[A-Za-z0-9][A-Za-z0-9._-]*)"
    r"(?![A-Za-z0-9._-])",
    re.IGNORECASE,
)
WSL_MOUNT_PERSONAL_PATH_PATTERN = re.compile(
    r"(?<![A-Za-z0-9._-])/mnt/[A-Za-z]:?[\\/]+Users[\\/]+"
    r"(?P<user>[A-Za-z0-9][A-Za-z0-9._-]*)"
    r"(?![A-Za-z0-9._-])",
    re.IGNORECASE,
)
WSL_UNC_PERSONAL_PATH_PATTERN = re.compile(
    r"(?<![A-Za-z0-9._-])\\\\wsl\.localhost[\\/]+"
    r"[^\\/]+[\\/]+home[\\/]+"
    r"(?P<user>[A-Za-z0-9][A-Za-z0-9._-]*)"
    r"(?![A-Za-z0-9._-])",
    re.IGNORECASE,
)

RULES = (
    (
        "private-key",
        re.compile(r"-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY(?: BLOCK)?-----"),
    ),
    ("aws-access-key", re.compile(r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b")),
    (
        "google-api-key",
        re.compile(
            r"(?<![A-Za-z0-9_-])AIza[A-Za-z0-9_-]{35}(?![A-Za-z0-9_-])"
        ),
    ),
    (
        "gitlab-token",
        re.compile(
            r"(?<![A-Za-z0-9_-])"
            r"(?:glpat|gldt|glrt|glft|gloas|glsoat)-[A-Za-z0-9_-]{20,}"
            r"(?![A-Za-z0-9_-])"
        ),
    ),
    (
        "github-token",
        re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\b"),
    ),
    ("slack-token", re.compile(r"\bxox[baprs]-[0-9A-Za-z-]{10,}\b")),
    ("stripe-live-token", re.compile(r"\b(?:sk|rk)_live_[A-Za-z0-9]{16,}\b")),
    (
        "jwt",
        re.compile(r"\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\b"),
    ),
    (
        "slack-webhook",
        re.compile(r"https?://hooks\.slack\.com/services/[A-Za-z0-9/_-]{10,}"),
    ),
    (
        "discord-webhook",
        re.compile(
            r"https?://discord(?:app)?\.com/api/webhooks/[0-9]{6,}/[A-Za-z0-9._-]{10,}"
        ),
    ),
    (
        "telegram-webhook",
        re.compile(
            r"https?://api\.telegram\.org/bot[0-9]{6,}:[A-Za-z0-9_-]{20,}/[A-Za-z]+"
        ),
    ),
    (
        "dingtalk-webhook",
        re.compile(
            r"https?://oapi\.dingtalk\.com/robot/send\?access_token="
            r"[A-Za-z0-9_-]{20,}"
        ),
    ),
    (
        "feishu-webhook",
        re.compile(
            r"https?://(?:open\.feishu\.cn|open\.larksuite\.com)/"
            r"open-apis/bot/v2/hook/[A-Za-z0-9_-]{20,}"
        ),
    ),
    (
        "wecom-webhook",
        re.compile(
            r"https?://qyapi\.weixin\.qq\.com/cgi-bin/webhook/send\?key="
            r"[A-Za-z0-9_-]{20,}"
        ),
    ),
    ("personal-path", UNIX_PERSONAL_PATH_PATTERN),
    ("personal-path", WINDOWS_PERSONAL_PATH_PATTERN),
    ("personal-path", WSL_MOUNT_PERSONAL_PATH_PATTERN),
    ("personal-path", WSL_UNC_PERSONAL_PATH_PATTERN),
)


class Finding:
    """A redacted location and rule identifier for one sensitive-data match."""

    __slots__ = ("rule", "path", "line")

    def __init__(self, rule, path, line):
        self.rule = rule
        self.path = path
        self.line = line

    def __eq__(self, other):
        if not isinstance(other, Finding):
            return NotImplemented
        return (self.rule, self.path, self.line) == (
            other.rule,
            other.path,
            other.line,
        )

    def __repr__(self):
        return f"Finding(rule={self.rule!r}, path={self.path!r}, line={self.line!r})"


def _is_allowlisted(rule_name, match):
    return rule_name == "personal-path" and match.group("user") in ALLOWED_USER_NAMES


def findings_in_line(line, path, line_number):
    """Return only rule and location data; matched values never leave this function."""
    findings = []
    reported_rules = set()
    for rule_name, pattern in RULES:
        if rule_name in reported_rules:
            continue
        if any(
            not _is_allowlisted(rule_name, match) for match in pattern.finditer(line)
        ):
            findings.append(Finding(rule_name, path, line_number))
            reported_rules.add(rule_name)
    return findings


def scan_text(text, path="<memory>"):
    """Scan text supplied by a caller, retaining only redacted finding locations."""
    findings = []
    for line_number, line in enumerate(text.splitlines(), start=1):
        findings.extend(findings_in_line(line, path, line_number))
    return findings


def _git_paths(root):
    completed = subprocess.run(
        [
            "git",
            "ls-files",
            "--cached",
            "--others",
            "--exclude-standard",
            "-z",
        ],
        cwd=root,
        check=True,
        stdout=subprocess.PIPE,
    )
    return [
        Path(os.fsdecode(raw_path))
        for raw_path in completed.stdout.split(b"\0")
        if raw_path
    ]


def scan_file(file_path, display_path):
    """Scan one repository file line by line without retaining its content."""
    findings = []
    with file_path.open("rb") as source:
        for line_number, raw_line in enumerate(source, start=1):
            line = raw_line.decode("utf-8", errors="replace")
            findings.extend(findings_in_line(line, display_path, line_number))
    return findings


def scan_repository(root=REPOSITORY_ROOT):
    """Scan Git-tracked files and non-ignored untracked files below root."""
    root = Path(root).resolve()
    findings = []
    for relative_path in _git_paths(root):
        file_path = root / relative_path
        if file_path.is_symlink() or not file_path.is_file():
            continue
        findings.extend(scan_file(file_path, relative_path.as_posix()))
    return sorted(findings, key=lambda finding: (finding.path, finding.line, finding.rule))


def format_finding(finding):
    """Format only the rule and redacted path:line location for CI output."""
    return f"{finding.rule} {finding.path}:{finding.line}"


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Scan tracked and non-ignored untracked files for sensitive data."
    )
    parser.add_argument(
        "--root",
        type=Path,
        default=REPOSITORY_ROOT,
        help="repository root (defaults to the root containing this script)",
    )
    args = parser.parse_args(argv)

    try:
        findings = scan_repository(args.root)
    except (OSError, subprocess.CalledProcessError):
        print("scan-error repository", file=sys.stderr)
        return 2

    for finding in findings:
        print(format_finding(finding))
    return 1 if findings else 0


if __name__ == "__main__":
    raise SystemExit(main())
