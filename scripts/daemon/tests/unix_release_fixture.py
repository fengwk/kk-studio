"""Isolated official-download fixtures shared by the assigned Unix suites."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
INSTALL_SCRIPT = REPOSITORY_ROOT / "scripts/daemon/install.sh"
TOKEN_VALUE = "fixture-registration-token-value"
STUDIO_URL = "https://studio.example.invalid"
UNIT_MARKER = "# Managed by scripts/daemon/install.sh"
SERVICE_NAME = "kk-studio-daemon.service"
LAUNCHD_LABEL = "fun.fengwk.kkstudio.environment-daemon"
PLIST_MARKER = "<!-- Managed by scripts/daemon/install.sh -->"


def write_executable(path, content):
    content = content.replace("#!/usr/bin/env python3\n", f"#!{sys.executable}\n", 1)
    path.write_text(content, encoding="utf-8")
    path.chmod(0o755)


def systemd_escape(value):
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"').replace(
        "%", "%%").replace("$", "$$") + '"'


class Fixture:
    """No checkout/build/real service tools; every subprocess uses a disposable HOME."""

    operating_system = "Linux"

    def __init__(self, root):
        self.root = Path(root).resolve()
        self.home = self.root / "home"
        self.bin = self.root / "bin"
        self.jdk = self.root / "fake-jdk"
        self.staging = self.root / "inputs"
        self.downloads = self.root / "downloads"
        self.record = self.root / "records.jsonl"
        self.script = self.root / "install.sh"
        self.install_root = self.home / ".kk-studio"
        self.jar = self.install_root / "lib/kk-studio-daemon.jar"
        self.config = self.install_root / "daemon.json"
        self.token = self.install_root / "daemon.token"
        self.input_config = self.staging / "daemon.json"
        self.input_token = self.staging / "daemon.token"
        self.unit = self.home / ".config/systemd/user" / SERVICE_NAME
        self.plist = self.home / "Library/LaunchAgents" / (LAUNCHD_LABEL + ".plist")
        self.service = self.plist if self.operating_system == "Darwin" else self.unit
        self.domain = "gui/" + str(os.getuid())
        self.target = self.domain + "/" + LAUNCHD_LABEL
        self.stdout_log = self.install_root / "logs/environment-daemon.stdout.log"
        self.stderr_log = self.install_root / "logs/environment-daemon.stderr.log"
        self.jar_body = "fixture-release-jar"
        for directory in (self.home, self.bin, self.jdk / "bin", self.staging, self.downloads):
            directory.mkdir(parents=True, mode=0o700)
        self.script.write_bytes(INSTALL_SCRIPT.read_bytes())
        self.script.chmod(0o755)
        self.record.touch()
        self.input_config.write_text(json.dumps({"studioUrl": STUDIO_URL}))
        self.input_token.write_text(TOKEN_VALUE + "\n")
        self.input_config.chmod(0o600)
        self.input_token.chmod(0o600)
        fake = Path(__file__).parent / "resources/unix_release_tools.py"
        content = f"#!{sys.executable}\n" + fake.read_text()
        for tool in ("uname", "curl", "systemctl", "journalctl", "launchctl", "plutil",
                     "mvn", "git"):
            write_executable(self.bin / tool, content)
        # Only a runtime is provided: the installer must not require a compiler.
        write_executable(self.jdk / "bin/java", content)

    def environment(self, **overrides):
        environment = {
            "HOME": str(self.home),
            "PATH": f"{self.bin}:{self.jdk / 'bin'}:/usr/bin:/bin",
            "JAVA_HOME_21": str(self.jdk),
            "JAVA_HOME": str(self.jdk),
            "TMPDIR": str(self.downloads),
            "FAKE_ROOT": str(self.root),
            "FAKE_RECORD": str(self.record),
            "FAKE_OS": self.operating_system,
            "FAKE_STAGING": str(self.staging),
            "FAKE_JAR_BODY": self.jar_body,
            "FAKE_LIVE_PID": str(os.getpid()),
            "DAEMON_VERIFY_TIMEOUT_SECONDS": "0",
            "DAEMON_VERIFY_STABLE_SECONDS": "0",
        }
        environment.update(overrides)
        return environment

    def run(self, *arguments, env=None):
        return subprocess.run(
            ["/bin/bash", str(self.script), *arguments],
            cwd=self.root, env=self.environment(**(env or {})),
            text=True, capture_output=True, start_new_session=True, timeout=30,
            check=False,
        )

    def install_arguments(self, **overrides):
        options = {"config-file": str(self.input_config),
                   "token-file": str(self.input_token), "java-home": str(self.jdk)}
        options.update(overrides)
        return [item for key, value in options.items() if value is not None
                for item in ("--" + key, value)]

    def install(self, *extra, env=None, **overrides):
        return self.run("install", *self.install_arguments(**overrides), *extra, env=env)

    def records(self):
        return [json.loads(line) for line in self.record.read_text().splitlines()]

    def tools(self):
        return [item["tool"] for item in self.records()]

    def calls(self, tool):
        return [item["argv"] for item in self.records() if item["tool"] == tool]

    def reset_record(self):
        self.record.write_text("")

    def snapshot(self):
        return {str(path): path.read_bytes() for path in
                (self.jar, self.config, self.token, self.service) if path.exists()}

    def record_filesystem_children(self):
        """Record actual filesystem child argv/env before execing the real GNU/BSD utility."""
        for tool in ("cp", "mv", "chmod", "mktemp", "mkdir", "stat", "wc", "rm", "cat"):
            native = shutil.which(tool)
            content = f"""#!{sys.executable}
import json
import os
import sys
with open(os.environ["FAKE_RECORD"], "a") as record:
    json.dump({{"tool": "{tool}", "argv": sys.argv[1:], "env": dict(os.environ)}}, record)
    record.write("\\n")
os.execv({native!r}, [{native!r}] + sys.argv[1:])
"""
            write_executable(self.bin / tool, content)

    def restrict_path(self):
        """Exercise BSD SHA256 fallback without depending on the host's PATH layout."""
        for tool in ("env", "stat", "id", "chmod", "mkdir", "rm", "mktemp", "cp", "mv",
                     "cat", "tr", "head", "sed", "wc", "sleep"):
            if not (self.bin / tool).exists():
                (self.bin / tool).symlink_to(shutil.which(tool))
        (self.bin / "bash").symlink_to("/bin/bash")
        shasum = shutil.which("shasum")
        if not shasum:
            raise unittest.SkipTest("host has no shasum")
        write_executable(self.bin / "shasum", f'#!/bin/sh\nexec "{shasum}" "$@"\n')


class DarwinFixture(Fixture):
    operating_system = "Darwin"


class FixtureTestCase(unittest.TestCase):
    fixture_type = Fixture

    def fixture(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        return self.fixture_type(temporary.name)

    def assert_ok(self, result):
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def assert_clean(self, fixture):
        self.assertEqual([], list(fixture.downloads.iterdir()))
        self.assertEqual([], list(fixture.home.rglob("*.tmp.*")))
        self.assertNotIn("mvn", fixture.tools())
        self.assertNotIn("git", fixture.tools())

    def assert_private(self, path, mode):
        self.assertEqual(mode, path.stat().st_mode & 0o777)

    def assert_no_switch(self, fixture):
        for tool in ("systemctl", "launchctl"):
            for args in fixture.calls(tool):
                self.assertFalse(set(args) & {
                    "stop", "disable", "restart", "daemon-reload", "enable",
                    "bootout", "bootstrap", "kickstart",
                }, (tool, args))

    def assert_no_secret(self, fixture, result):
        for text in (result.stdout, result.stderr, fixture.record.read_text(),
                     fixture.service.read_text() if fixture.service.exists() else ""):
            self.assertNotIn(TOKEN_VALUE, text)
            self.assertNotIn(STUDIO_URL, text)
