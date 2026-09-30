"""Unit tests for the full-history sensitive-data scanner."""

from __future__ import annotations

import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest


def repository_root():
    """Resolve the checkout root: KK_STUDIO_REPO_ROOT, else the enclosing worktree."""
    override = os.environ.get("KK_STUDIO_REPO_ROOT")
    if override:
        return Path(override).resolve()
    for candidate in Path(__file__).resolve().parents:
        if (candidate / ".git").exists():
            return candidate
    raise RuntimeError("cannot locate the kk-studio worktree root; set KK_STUDIO_REPO_ROOT")


REPOSITORY_ROOT = repository_root()
MODULE_PATH = (
    REPOSITORY_ROOT / "scripts" / "dev" / "verify" / "repository" / "check-sensitive-history.py"
)
SPEC = importlib.util.spec_from_file_location("check_sensitive_history", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def sample_token():
    """AWS documentation example key, assembled so this file never matches a rule."""
    return "AKIA" + "IOSFODNN7EXAMPLE"


def render(findings):
    """Flatten redacted findings; the matched value is never part of a finding."""
    return "\n".join(
        f"{finding.rule} {finding.object} {finding.commit} {finding.path} {finding.line}"
        for finding in findings
    )


def git(root, *args):
    return subprocess.run(
        ["git", *args], cwd=root, capture_output=True, text=True, check=True
    )


def init_repository(root):
    subprocess.run(
        ["git", "init", "--quiet", "-b", "main", str(root)],
        capture_output=True,
        check=True,
    )


def commit_all(root, message):
    git(root, "add", "-A")
    git(
        root,
        "-c",
        "user.email=audit@example.invalid",
        "-c",
        "user.name=audit",
        "commit",
        "--quiet",
        "-m",
        message,
    )
    return git(root, "rev-parse", "HEAD").stdout.strip()


def write_and_commit(root, files, message):
    for name, content in files.items():
        path = Path(root) / name
        path.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(content, bytes):
            path.write_bytes(content)
        else:
            path.write_text(content, encoding="utf-8")
    return commit_all(root, message)


def seed_origin(base, secret, pr_ref="refs/pull/1/head"):
    """Create a local bare 'origin' (optionally with a PR ref) and a consumer repo."""
    remote = Path(base) / "origin.git"
    subprocess.run(
        ["git", "init", "--bare", "--quiet", str(remote)], capture_output=True, check=True
    )
    seed = Path(base) / "seed"
    init_repository(seed)
    (seed / "f.txt").write_text(secret + "\n", encoding="utf-8")
    sha = commit_all(seed, "seed")
    git(seed, "remote", "add", "origin", str(remote))
    git(seed, "push", "--quiet", "origin", "main")
    if pr_ref:
        subprocess.run(
            ["git", "--git-dir", str(remote), "update-ref", pr_ref, sha],
            capture_output=True,
            check=True,
        )
    consumer = Path(base) / "consumer"
    init_repository(consumer)
    git(consumer, "remote", "add", "origin", str(remote))
    return consumer


class TestHistoryScannerRules(unittest.TestCase):
    """Keep the history scanner redacted, rule-reusing and allowlist-safe."""

    def test_reuses_every_current_tree_rule(self):
        # Intent: history mode must be a superset of the CI gate, never weaker.
        base_rules = {rule for rule, _ in MODULE.BASE.RULES}
        self.assertTrue(base_rules.issubset({rule for rule, _ in MODULE.ALL_RULES}))

    def test_every_rule_has_a_prefilter(self):
        # Intent: a rule without a necessary-substring guard would run unguarded
        # after a rule is added and the prefilter map is forgotten.
        for rule, _ in MODULE.ALL_RULES:
            self.assertIn(rule, MODULE.PREFILTERS)

    def test_reports_rule_and_location_without_echoing_values(self):
        # Intent: each supported shape yields only a redacted location.
        samples = {
            "token": (sample_token(), "aws-access-key"),
            "private-key": ("-----BEGIN " + "OPENSSH PRIVATE KEY-----", "private-key"),
            "credential-url": (
                "https://user" + ":" + "verysecretvalue123" + "@api.vendor-corp.net/v1",
                "credential-url",
            ),
            "generic-secret": ("api_key=" + "abcdef0123456789abcdef", "generic-secret"),
            "personal-path": ("/home/" + "alice/work", "personal-path"),
        }
        for name, (value, rule) in samples.items():
            with self.subTest(sample=name):
                findings = MODULE.findings_in_text(f"x={value}", "fixture.txt")
                self.assertIn(rule, [finding.rule for finding in findings])
                self.assertNotIn(value, render(findings))

    def test_allowlists_only_fixture_personal_path_users(self):
        # Intent: only the CI gate's fixture user names are skipped, and only for
        # personal paths; every other shape stays a candidate.
        quiet = "\n".join(["/home/" + "dev", "/Users/" + "kkdaemon"])
        self.assertEqual([], MODULE.findings_in_text(quiet, "fixture.txt"))

    def test_reserved_host_credentials_are_reported_with_a_hint(self):
        # Intent: a password on localhost/example is still a candidate; the host
        # may only downgrade it to a hint, never remove the finding.
        samples = [
            "https://user" + ":" + "password" + "@example.com/mcp",
            "https://user" + ":" + "pw" + "@127.0.0.1:15432/db",
        ]
        for sample in samples:
            with self.subTest(sample=sample.split("@")[1]):
                groups = MODULE.ValueGroups()
                findings = MODULE.findings_in_text(sample, "fixture.txt", groups=groups)
                self.assertIn("credential-url", [finding.rule for finding in findings])
                rendered = groups.render()
                self.assertEqual(["credential-url"], [g["rule"] for g in rendered])
                self.assertEqual([MODULE.STATUS_PENDING], [g["status"] for g in rendered])
                self.assertIn("reserved-host", rendered[0]["hint"])
                self.assertNotIn(sample, render(findings))

    def test_quoted_and_camel_case_keys_are_matched(self):
        # Intent: ``"apiKey": "..."`` (closing quote before the colon) and
        # camelCase tails (``sensitiveKey``) are exactly the shapes that used to
        # escape the generic rule, so each one must produce a candidate.
        samples = [
            '{"apiKey": ' + '"abcdef0123456789abcdef"}',
            '{\'apiKey\': ' + '\'abcdef0123456789abcdef\'}',
            "sensitiveKey = " + '"abcdef0123456789abcdef";',
            '{"clientSecret":"abcdef0123456789abcdef"}',
            "accessToken=" + "abcdef0123456789abcdef",
            "refreshToken: " + "abcdef0123456789abcdef",
        ]
        for sample in samples:
            with self.subTest(sample=sample[:20]):
                findings = MODULE.findings_in_text(sample, "fixture.txt")
                self.assertIn("generic-secret", [finding.rule for finding in findings])
                self.assertNotIn("abcdef0123456789abcdef", render(findings))

    def test_key_separator_never_spans_a_line_break(self):
        # Intent: a key name that ends a line used to be glued to the next line
        # and reported as a bogus "value"; the separator must stay on one line so
        # real assignments are still matched while cross-line noise is dropped.
        cross_line = "MY_API_KEY=\n  internalSchemaIdentifier9999\n"
        self.assertEqual([], [f.rule for f in MODULE.findings_in_text(cross_line, "fixture.txt")])
        assignment = "MY_API_KEY=internalSchemaIdentifier9999\n"
        self.assertIn("generic-secret", [f.rule for f in MODULE.findings_in_text(assignment, "fixture.txt")])

    def test_generic_secret_group_hint_names_the_key(self):
        # Intent: a reviewer must be able to triage camelCase matches such as
        # ``ariaKey`` by the key name recorded in the hint, never by the value.
        groups = MODULE.ValueGroups()
        MODULE.findings_in_text(
            "sensitiveKey = " + '"abcdef0123456789abcdef";', "fixture.txt", groups=groups
        )
        rendered = groups.render()
        self.assertIn("key-name:sensitivekey", rendered[0]["hint"])
        self.assertNotIn("abcdef0123456789abcdef", json.dumps(rendered))

    def test_overlapping_locator_matches_are_masked_as_union(self):
        # Intent: when two rules overlap, the tail of the dropped span must not
        # stay visible in a stored location.
        path = "aaaa" + "SECRET" + "token" + "9999"
        original = MODULE.ALL_RULES
        MODULE._LOCATOR_CACHE.clear()
        MODULE.ALL_RULES = (
            ("synthetic-a", re.compile("SECRETtoken")),
            ("synthetic-b", re.compile("token9999")),
        )
        try:
            locator = MODULE.redacted_locator(path)
        finally:
            MODULE.ALL_RULES = original
            MODULE._LOCATOR_CACHE.clear()
        self.assertNotIn("SECRETtoken", locator)
        self.assertNotIn("token9999", locator)
        self.assertNotIn("9999", locator)
        self.assertIn("<redacted:synthetic-a+synthetic-b>", locator)

    def test_detects_windows_and_wsl_personal_paths(self):
        # Intent: non-Unix personal paths are covered, not only "/home".
        for path in ("C:" + "\\" + "Users" + "\\" + "alice", "/mnt/" + "c/Users/" + "alice"):
            with self.subTest(path=path):
                findings = MODULE.findings_in_text(f'root="{path}"', "fixture.txt")
                self.assertEqual(["personal-path"], [finding.rule for finding in findings])

    def test_scanner_source_does_not_match_its_own_rules(self):
        # Intent: rule literals must stay assembled so the module is not a finding.
        self.assertEqual(
            [], MODULE.findings_in_text(MODULE_PATH.read_text(encoding="utf-8"), "scanner.py")
        )

    def test_value_groups_keep_hash_and_locations_only(self):
        # Intent: grading evidence is a value hash plus locations, never the value.
        groups = MODULE.ValueGroups()
        value = "api_key=" + "abcdef0123456789abcdef"
        MODULE.findings_in_text(value, "fixture.txt", groups=groups)
        rendered = groups.render()
        self.assertEqual(1, len(rendered))
        self.assertEqual("generic-secret", rendered[0]["rule"])
        self.assertEqual(MODULE.STATUS_PENDING, rendered[0]["status"])
        self.assertEqual(12, len(rendered[0]["value_sha256_12"]))
        self.assertNotIn(value, json.dumps(rendered, ensure_ascii=False))

    def test_personal_path_is_not_graded_as_a_credential(self):
        # Intent: a personal path must never be reported as key leakage.
        groups = MODULE.ValueGroups()
        MODULE.findings_in_text("/home/" + "alice/work", "fixture.txt", groups=groups)
        self.assertEqual([MODULE.STATUS_PERSONAL_DATA], [g["status"] for g in groups.render()])

    def test_path_containing_a_match_is_masked(self):
        # Intent: a filename that itself contains a secret must not leak it.
        path = sample_token() + ".txt"
        locator = MODULE.redacted_locator(path)
        self.assertNotIn(sample_token(), locator)
        self.assertIn("<redacted:aws-access-key>", locator)


class TestHistoryScanning(unittest.TestCase):
    """Prove deleted history is audited while the current tree stays clean."""

    def _scan(self, directory, max_blob_bytes=MODULE.DEFAULT_MAX_BLOB_BYTES):
        return MODULE.run(Path(directory), max_blob_bytes)

    def test_finds_deleted_secret_in_history_not_in_worktree(self):
        # Intent: a secret removed from the tip must still be reported against
        # the commit that introduced it, and never leak its value.
        with tempfile.TemporaryDirectory() as directory:
            init_repository(directory)
            introducing = write_and_commit(
                directory, {"secret.txt": "key=" + sample_token() + "\n"}, "add"
            )
            write_and_commit(directory, {"secret.txt": "clean\n"}, "remove")

            report = self._scan(directory)
            rows = [f for f in report["history"]["findings"] if f["path"] == "secret.txt"]
            self.assertTrue(rows, "history must report the removed secret")
            self.assertEqual("aws-access-key", rows[0]["rule"])
            self.assertEqual(introducing, rows[0]["commit"])
            self.assertEqual([], report["worktree"]["findings"])
            self.assertNotIn(sample_token(), json.dumps(report))

    def test_scans_commit_and_tag_messages(self):
        # Intent: messages are part of the public history surface.
        with tempfile.TemporaryDirectory() as directory:
            init_repository(directory)
            write_and_commit(directory, {"a.txt": "x\n"}, "chore " + sample_token())
            git(
                directory,
                "-c",
                "user.email=audit@example.invalid",
                "-c",
                "user.name=audit",
                "tag",
                "-a",
                "v-test",
                "-m",
                "tag " + sample_token(),
            )

            report = self._scan(directory)
            paths = {f["path"] for f in report["history"]["findings"]}
            self.assertIn("<commit-message>", paths)
            self.assertIn("<tag-message>", paths)

    def test_empty_repository_is_not_reported_as_degraded(self):
        # Intent: no reachable object is a complete (empty) scan; an empty batch
        # input must not be mistaken for a malformed record.
        with tempfile.TemporaryDirectory() as directory:
            init_repository(directory)

            report = self._scan(directory)
            self.assertEqual([], report["history"]["findings"])
            self.assertFalse(report["degraded"]["incomplete"])

    def test_dedupes_shared_blob_across_paths(self):
        # Intent: identical content committed twice is one object, attributed to
        # both locations without re-reading it.
        with tempfile.TemporaryDirectory() as directory:
            init_repository(directory)
            payload = "key=" + sample_token() + "\n"
            write_and_commit(directory, {"a.txt": payload}, "a")
            write_and_commit(directory, {"b.txt": payload}, "b")

            report = self._scan(directory)
            rows = [f for f in report["history"]["findings"] if f["rule"] == "aws-access-key"]
            self.assertEqual(1, len({f["object"] for f in rows}))
            self.assertEqual({"a.txt", "b.txt"}, {f["path"] for f in rows})

    def test_binary_blob_strings_are_scanned(self):
        # Intent: binary blobs are candidate-scanned via strings, not skipped silently.
        with tempfile.TemporaryDirectory() as directory:
            init_repository(directory)
            payload = b"\x00\x01api_key=abcdef0123456789abcdef\x00"
            write_and_commit(directory, {"bin.dat": payload}, "fixtures")

            report = self._scan(directory)
            self.assertGreaterEqual(report["coverage"]["blobs_binary"], 1)
            self.assertGreaterEqual(report["coverage"]["binary_strings_scanned"], 1)
            self.assertTrue(report["binary_objects"])
            paths = {f["path"] for f in report["history"]["findings"]}
            self.assertIn("bin.dat", paths)
            self.assertNotIn("abcdef0123456789abcdef", json.dumps(report))

    def test_oversized_blob_is_counted_not_scanned(self):
        # Intent: the blob limit is explicit and counted, not silently dropped.
        with tempfile.TemporaryDirectory() as directory:
            init_repository(directory)
            write_and_commit(
                directory, {"big.txt": "key=" + sample_token() + " " + "z" * 256 + "\n"}, "big"
            )

            report = self._scan(directory, max_blob_bytes=64)
            self.assertGreaterEqual(report["coverage"]["blobs_too_large"], 1)
            self.assertEqual([], report["history"]["findings"])

    def test_generic_secret_is_graded_pending_review(self):
        # Intent: broad hits are listed for review, never auto-cleared as safe.
        with tempfile.TemporaryDirectory() as directory:
            init_repository(directory)
            write_and_commit(directory, {"c.txt": "api_key=abcdef0123456789abcdef\n"}, "c")

            report = self._scan(directory)
            groups = [g for g in report["history"]["value_groups"] if g["rule"] == "generic-secret"]
            self.assertEqual([MODULE.STATUS_PENDING], [g["status"] for g in groups])
            self.assertNotIn("abcdef0123456789abcdef", json.dumps(report))

    def test_large_repository_reports_are_not_claimed_as_clean(self):
        # Intent: a clean current tree must not be presented as a clean history.
        with tempfile.TemporaryDirectory() as directory:
            init_repository(directory)
            write_and_commit(directory, {"a.txt": "x\n"}, "a")
            markdown = MODULE.render_markdown(self._scan(directory))
            self.assertNotIn("无需轮换", markdown)
            self.assertNotIn("必须轮换", markdown)
            self.assertIn("待人工确认", markdown)


class TestHistoryScannerCli(unittest.TestCase):
    """Exercise the CLI contract: redacted reports and optional high-severity gate."""

    def test_cli_writes_redacted_reports_and_fails_on_high(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            init_repository(root)
            write_and_commit(root, {"secret.txt": "key=" + sample_token() + "\n"}, "add")
            json_path = root.parent / (root.name + "-scan.json")
            markdown_path = root.parent / (root.name + "-scan.md")
            try:
                completed = subprocess.run(
                    [
                        sys.executable,
                        str(MODULE_PATH),
                        "--root",
                        str(root),
                        "--json",
                        str(json_path),
                        "--markdown",
                        str(markdown_path),
                        "--fail-on-high",
                    ],
                    capture_output=True,
                    text=True,
                    check=False,
                )
                self.assertEqual(1, completed.returncode)
                payload = json_path.read_text(encoding="utf-8")
                markdown = markdown_path.read_text(encoding="utf-8")
                self.assertIn("aws-access-key", payload)
                self.assertIn("aws-access-key", markdown)
                for text in (payload, markdown, completed.stdout, completed.stderr):
                    self.assertNotIn(sample_token(), text)
            finally:
                json_path.unlink(missing_ok=True)
                markdown_path.unlink(missing_ok=True)

    def _run_cli(self, root, *extra):
        return subprocess.run(
            [sys.executable, str(MODULE_PATH), "--root", str(root), *extra],
            capture_output=True,
            text=True,
            check=False,
        )

    def test_cli_fails_on_high_from_the_worktree_only(self):
        # Intent: the gate must cover the current tree, not only the history.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            init_repository(root)
            write_and_commit(root, {"clean.txt": "x\n"}, "clean")
            (root / "untracked.txt").write_text("key=" + sample_token() + "\n", encoding="utf-8")

            completed = self._run_cli(root, "--fail-on-high")
            self.assertEqual(1, completed.returncode)
            self.assertNotIn(sample_token(), completed.stdout + completed.stderr)

    def test_cli_fails_on_high_from_remote_pr_refs_only(self):
        # Intent: a PR-only secret must fail the gate as well; the PR rows are
        # the evidence, not just a count.
        with tempfile.TemporaryDirectory() as directory:
            consumer = seed_origin(directory, "key=" + sample_token())

            completed = self._run_cli(consumer, "--remote-pr", "--fail-on-high")
            self.assertEqual(1, completed.returncode)
            self.assertIn("remote_pr_refs=", completed.stdout)
            self.assertNotIn(sample_token(), completed.stdout + completed.stderr)

    def test_cli_reports_incomplete_scan_instead_of_success(self):
        # Intent: an unverifiable remote must not be reported as a clean exit.
        with tempfile.TemporaryDirectory() as directory:
            consumer = seed_origin(directory, "clean", pr_ref=None)
            git(consumer, "remote", "set-url", "origin", str(Path(directory) / "missing.git"))

            completed = self._run_cli(consumer)
            self.assertEqual(3, completed.returncode)
            self.assertIn("scan-incomplete", completed.stderr)

    def test_cli_rejects_negative_limits(self):
        # Intent: a negative limit is a caller error, never a silent full scan.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            init_repository(root)
            write_and_commit(root, {"a.txt": "x\n"}, "a")

            completed = self._run_cli(root, "--max-blob-bytes", "-1")
            self.assertEqual(2, completed.returncode)
            self.assertIn("invalid-arguments", completed.stderr)
            with self.assertRaises(ValueError):
                MODULE.run(root, -1)


class TestRemotePrRefs(unittest.TestCase):
    """Public PR refs must be audited in isolation, or their absence recorded."""

    def test_scans_public_pr_refs_from_isolated_bare_repo(self):
        with tempfile.TemporaryDirectory() as directory:
            consumer = seed_origin(directory, "key=" + sample_token())
            before = git(consumer, "for-each-ref", "--format=%(refname)").stdout

            result = MODULE.scan_remote_pr_refs(consumer, MODULE.DEFAULT_MAX_BLOB_BYTES)

            after = git(consumer, "for-each-ref", "--format=%(refname)").stdout
            self.assertEqual(before, after, "main repository refs must not be touched")
            self.assertEqual("scanned", result["status"])
            self.assertEqual(1, result["refs"])
            self.assertGreaterEqual(result["findings"], 1)
            self.assertNotIn(sample_token(), json.dumps(result, ensure_ascii=False))

    def test_records_absence_when_no_pr_refs(self):
        with tempfile.TemporaryDirectory() as directory:
            consumer = seed_origin(directory, "clean", pr_ref=None)
            result = MODULE.scan_remote_pr_refs(consumer, MODULE.DEFAULT_MAX_BLOB_BYTES)
            self.assertEqual("none", result["status"])
            self.assertEqual(0, result["refs"])

    def test_report_never_carries_the_remote_url(self):
        # Intent: a remote URL can embed a query token, userinfo or a local
        # filesystem path, so the report may only ever say "origin".
        with tempfile.TemporaryDirectory() as directory:
            consumer = seed_origin(directory, "clean", pr_ref=None)
            result = MODULE.scan_remote_pr_refs(consumer, MODULE.DEFAULT_MAX_BLOB_BYTES)
            self.assertEqual("origin", result["remote"])
            payload = json.dumps(result, ensure_ascii=False)
            self.assertNotIn(str(Path(directory)), payload)
            self.assertNotIn("origin.git", payload)

            git(
                consumer,
                "remote",
                "set-url",
                "origin",
                "https://user" + ":" + "password"
                + "@private.invalid/owner/repo.git?token=querytokenvalue",
            )
            covered = MODULE.remote_coverage(Path(consumer), MODULE.list_refs(Path(consumer)))
            payload = json.dumps(covered, ensure_ascii=False)
            for leaked in ("private.invalid", "password", "querytokenvalue", "user"):
                self.assertNotIn(leaked, payload)
            self.assertEqual("ls-remote-failed", covered["status"])
            self.assertEqual("origin", covered["label"])


class TestBatchProtocolAnomalies(unittest.TestCase):
    """A read that cannot complete must be recorded, never silently accepted."""

    def test_valid_entry_is_parsed(self):
        # Intent: the happy path still returns the body and no degraded item.
        stream = io.BytesIO(b"a" * 40 + b" blob 5\nhello\n")
        issues = MODULE.ScanIssues()
        entry = MODULE.read_batch_entry(stream, issues, MODULE.DEFAULT_MAX_BLOB_BYTES)
        self.assertEqual(b"hello", entry["content"])
        self.assertEqual([], issues.items)
        self.assertIs(MODULE.BATCH_EOF, MODULE.read_batch_entry(stream, issues, MODULE.DEFAULT_MAX_BLOB_BYTES))

    def test_missing_object_is_recorded(self):
        # Intent: an object the refs point at but the object database lacks is a
        # coverage gap.
        stream = io.BytesIO(b"b" * 40 + b" missing\n")
        issues = MODULE.ScanIssues()
        entry = MODULE.read_batch_entry(stream, issues, MODULE.DEFAULT_MAX_BLOB_BYTES)
        self.assertIsNone(entry["content"])
        self.assertEqual(["missing-object"], [item["detail"] for item in issues.items])

    def test_malformed_header_is_recorded(self):
        # Intent: an unparsable header must not be skipped as if it were empty.
        stream = io.BytesIO(b"not-a-batch-header\n")
        issues = MODULE.ScanIssues()
        entry = MODULE.read_batch_entry(stream, issues, MODULE.DEFAULT_MAX_BLOB_BYTES)
        self.assertIsNone(entry["content"])
        self.assertEqual(["malformed-header"], [item["detail"] for item in issues.items])

    def test_short_body_is_recorded(self):
        # Intent: a truncated body yields partial content plus an explicit
        # short-read item, so the caller cannot treat it as a complete blob.
        stream = io.BytesIO(b"c" * 40 + b" blob 20\nshort")
        issues = MODULE.ScanIssues()
        entry = MODULE.read_batch_entry(stream, issues, MODULE.DEFAULT_MAX_BLOB_BYTES)
        self.assertEqual(b"short", entry["content"])
        self.assertEqual(
            ["short-read missing=15 of=20"], [item["detail"] for item in issues.items]
        )

    def test_wrong_record_terminator_is_recorded(self):
        # Intent: a body that is not followed by the batch record terminator
        # means the stream is out of sync, so the record must be flagged.
        stream = io.BytesIO(b"d" * 40 + b" blob 3\nabcX")
        issues = MODULE.ScanIssues()
        entry = MODULE.read_batch_entry(stream, issues, MODULE.DEFAULT_MAX_BLOB_BYTES)
        self.assertEqual(b"abc", entry["content"])
        self.assertEqual(
            ["missing-record-terminator"], [item["detail"] for item in issues.items]
        )

    def test_unknown_object_id_is_recorded_by_the_scan(self):
        # Intent: the streaming scan reports a missing object instead of
        # returning a silently smaller result set.
        with tempfile.TemporaryDirectory() as directory:
            init_repository(directory)
            write_and_commit(directory, {"a.txt": "x\n"}, "a")
            findings, _, _, stats, issues = MODULE._scan_blob(
                Path(directory),
                ["0" * 39 + "1"],
                {},
                MODULE.DEFAULT_MAX_BLOB_BYTES,
                MODULE.DEFAULT_STRING_LIMIT,
            )
            self.assertEqual([], findings)
            self.assertEqual(["missing-object"], [item["detail"] for item in issues.items])
            self.assertTrue(issues.as_dict()["incomplete"])
            self.assertEqual(0, stats["scanned"])


class TestTagObjectsAndRefTargets(unittest.TestCase):
    """Tag objects and non-commit ref targets are part of the audited surface."""

    def _scan(self, directory):
        return MODULE.run(Path(directory))

    def test_multiline_tag_message_is_fully_scanned(self):
        # Intent: only the first output line used to be scanned, so a secret on
        # line 2/3 of an annotated tag message escaped the audit.
        with tempfile.TemporaryDirectory() as directory:
            init_repository(directory)
            write_and_commit(directory, {"a.txt": "x\n"}, "a")
            git(
                directory,
                "-c",
                "user.email=audit@example.invalid",
                "-c",
                "user.name=audit",
                "tag",
                "-a",
                "v-multiline",
                "-m",
                "subject line\n\nthird line " + sample_token(),
            )

            report = self._scan(directory)
            rows = [f for f in report["history"]["findings"] if f["path"] == "<tag-message>"]
            self.assertTrue(rows, "the tag message must be scanned beyond its first line")
            self.assertEqual(3, rows[0]["line"])
            self.assertNotIn(sample_token(), json.dumps(report))

    def test_tag_pointing_at_a_blob_is_scanned(self):
        # Intent: a lightweight tag to a blob is unreachable from any commit, so
        # a commit walk alone would count it as covered without reading it.
        with tempfile.TemporaryDirectory() as directory:
            init_repository(directory)
            write_and_commit(directory, {"a.txt": "x\n"}, "a")
            blob = subprocess.run(
                ["git", "hash-object", "-w", "--stdin"],
                cwd=directory,
                input="key=" + sample_token() + "\n",
                text=True,
                capture_output=True,
                check=True,
            ).stdout.strip()
            git(directory, "update-ref", "refs/tags/blob-tag", blob)

            report = self._scan(directory)
            rows = [f for f in report["history"]["findings"] if f["rule"] == "aws-access-key"]
            self.assertEqual({"<ref:refs/tags/blob-tag>"}, {f["path"] for f in rows})
            self.assertGreaterEqual(report["coverage"]["non_commit_refs"], 1)
            self.assertNotIn(sample_token(), json.dumps(report))

    def test_tag_pointing_at_a_tree_is_scanned(self):
        # Intent: blobs reachable only through a tree-tag are covered too.
        with tempfile.TemporaryDirectory() as directory:
            init_repository(directory)
            write_and_commit(directory, {"a.txt": "x\n"}, "a")
            blob = subprocess.run(
                ["git", "hash-object", "-w", "--stdin"],
                cwd=directory,
                input="key=" + sample_token() + "\n",
                text=True,
                capture_output=True,
                check=True,
            ).stdout.strip()
            tree = subprocess.run(
                ["git", "mktree"],
                cwd=directory,
                input=f"100644 blob {blob}\tsecret.txt\n",
                text=True,
                capture_output=True,
                check=True,
            ).stdout.strip()
            git(directory, "update-ref", "refs/tags/tree-tag", tree)

            report = self._scan(directory)
            rows = [f for f in report["history"]["findings"] if f["rule"] == "aws-access-key"]
            self.assertEqual({"<ref:refs/tags/tree-tag>/secret.txt"}, {f["path"] for f in rows})

    def test_unreadable_worktree_file_is_recorded(self):
        # Intent: an unreadable file is a coverage gap, not a silent skip.
        if os.geteuid() == 0:
            self.skipTest("root ignores mode 000")
        with tempfile.TemporaryDirectory() as directory:
            init_repository(directory)
            write_and_commit(directory, {"a.txt": "x\n"}, "a")
            target = Path(directory) / "a.txt"
            target.chmod(0)
            try:
                _, _, issues = MODULE.scan_worktree(Path(directory))
            finally:
                target.chmod(0o644)
            self.assertEqual(
                ["read-failed:PermissionError"], [item["detail"] for item in issues.items]
            )


class TestRemoteCoverage(unittest.TestCase):
    """Remote coverage must be an OID comparison, never a presence heuristic."""

    def test_unfetched_remote_head_is_a_mismatch(self):
        # Intent: a tracking ref that was never fetched cannot be claimed as
        # coverage of the remote.
        with tempfile.TemporaryDirectory() as directory:
            consumer = seed_origin(directory, "clean", pr_ref=None)
            result = MODULE.remote_coverage(Path(consumer), MODULE.list_refs(Path(consumer)))
            self.assertEqual("mismatch", result["status"])
            self.assertGreaterEqual(result["absent_locally"], 1)
            self.assertNotIn(str(Path(directory)), json.dumps(result))

    def test_fetched_remote_head_verifies_by_oid(self):
        # Intent: only an OID match may be reported as verified coverage.
        with tempfile.TemporaryDirectory() as directory:
            consumer = seed_origin(directory, "clean", pr_ref=None)
            git(consumer, "fetch", "--quiet", "origin")
            result = MODULE.remote_coverage(Path(consumer), MODULE.list_refs(Path(consumer)))
            self.assertEqual("verified", result["status"])
            self.assertEqual(1, result["heads"])
            self.assertEqual(0, result["oid_mismatch"])
            self.assertEqual(0, result["absent_locally"])

    def test_absent_remote_is_explicit(self):
        # Intent: "no origin" is recorded as its own state, not as success.
        with tempfile.TemporaryDirectory() as directory:
            init_repository(directory)
            write_and_commit(directory, {"a.txt": "x\n"}, "a")
            result = MODULE.remote_coverage(Path(directory), MODULE.list_refs(Path(directory)))
            self.assertEqual("no-remote", result["status"])

    def test_failed_lookup_marks_the_scan_incomplete(self):
        # Intent: when the remote cannot be queried, the report must say so.
        with tempfile.TemporaryDirectory() as directory:
            consumer = seed_origin(directory, "clean", pr_ref=None)
            git(consumer, "remote", "set-url", "origin", str(Path(directory) / "missing.git"))
            report = MODULE.run(Path(consumer))
            self.assertEqual("ls-remote-failed", report["coverage"]["remote"]["status"])
            self.assertTrue(report["degraded"]["incomplete"])


class TestPrFindingsDetail(unittest.TestCase):
    """The PR audit must expose the same evidence as the local history scan."""

    def test_pr_scan_returns_finding_rows(self):
        with tempfile.TemporaryDirectory() as directory:
            consumer = seed_origin(directory, "key=" + sample_token())
            result = MODULE.scan_remote_pr_refs(consumer, MODULE.DEFAULT_MAX_BLOB_BYTES)
            self.assertEqual("scanned", result["status"])
            self.assertTrue(result["findings_detail"], "detail rows are required for gating")
            self.assertEqual(
                {"rule", "category", "object", "commit", "path", "line"},
                set(result["findings_detail"][0]),
            )
            self.assertNotIn(sample_token(), json.dumps(result, ensure_ascii=False))


class TestCurrentTreeGateStillPasses(unittest.TestCase):
    """The historical audit must not weaken or replace the CI gate."""

    def test_current_repository_passes_the_shared_gate(self):
        # Intent: the audit entry only reads, and the CI gate it reuses is intact.
        self.assertTrue(os.access(MODULE_PATH, os.X_OK))
        self.assertEqual([], MODULE.BASE.scan_repository(REPOSITORY_ROOT))


if __name__ == "__main__":
    unittest.main()
