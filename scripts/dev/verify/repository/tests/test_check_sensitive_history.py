"""Unit tests for the full-history sensitive-data scanner."""

from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
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

    def test_allowlists_reserved_hosts_and_fixture_users(self):
        # Intent: only shapes that cannot be a real secret may be skipped.
        quiet = "\n".join(
            [
                "https://user" + ":" + "password" + "@example.com/mcp",
                "https://user" + ":" + "pw" + "@127.0.0.1:15432/db",
                "/home/" + "dev",
                "/Users/" + "kkdaemon",
            ]
        )
        self.assertEqual([], MODULE.findings_in_text(quiet, "fixture.txt"))

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

    def test_remote_url_userinfo_is_redacted(self):
        self.assertEqual(
            "***@github.com:owner/repo.git",
            MODULE.redact_url("git@github.com:owner/repo.git"),
        )
        self.assertEqual(
            "https://***@host/repo.git",
            MODULE.redact_url("https://user:password@host/repo.git"),
        )


class TestCurrentTreeGateStillPasses(unittest.TestCase):
    """The historical audit must not weaken or replace the CI gate."""

    def test_current_repository_passes_the_shared_gate(self):
        # Intent: the audit entry only reads, and the CI gate it reuses is intact.
        self.assertTrue(os.access(MODULE_PATH, os.X_OK))
        self.assertEqual([], MODULE.BASE.scan_repository(REPOSITORY_ROOT))


if __name__ == "__main__":
    unittest.main()
