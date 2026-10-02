"""Standalone official release contracts; every network/service command is a local fake."""

import os
import pty
import select
import shutil
import subprocess
import tempfile
import termios
import time
import unittest

from test_daemon_install import Fixture, GATEWAY_URI, TOKEN_VALUE, write_executable
from test_daemon_install_macos import DarwinFixture

RELEASE_BASE = "https://github.com/fengwk/kk-studio/releases"
TAG = "v1.0.0"


class ReleaseTools:
    """No checkout marker/build inputs exist; curl writes fixture bytes, never uses the network."""

    source_mode = False

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        shutil.rmtree(self.repo / ".git")
        shutil.rmtree(self.repo / "harness")
        self.downloads = self.root / "downloads"
        self.downloads.mkdir()
        write_executable(
            self.bin / "mvn",
            '#!/usr/bin/env bash\nprintf "mvn|FORBIDDEN\\n" >> "$FAKE_RECORD"\nexit 99\n',
        )
        write_executable(
            self.bin / "git",
            '#!/usr/bin/env bash\nprintf "git|FORBIDDEN\\n" >> "$FAKE_RECORD"\nexit 99\n',
        )
        write_executable(
            self.bin / "curl",
            """#!/usr/bin/env python3
import hashlib
import os
from pathlib import Path
import signal
import sys

args = sys.argv[1:]
with open(os.environ["FAKE_RECORD"], "a") as record:
    record.write("curl|" + " ".join(args) + "\\n")
assert args[:1] == ["-q"]
assert "--fail" in args and "--location" in args
assert args[args.index("--proto") + 1] == "=https"
assert args[args.index("--proto-redir") + 1] == "=https"
url = args[-1]
mode = os.environ.get("FAKE_DOWNLOAD_MODE", "ok")
base = "https://github.com/fengwk/kk-studio/releases"
if url == base + "/latest":
    if mode == "latest-fail":
        sys.exit(22)
    print(os.environ.get("FAKE_EFFECTIVE_URL", base + "/tag/v1.0.0"), end="")
    sys.exit(0)
target = Path(args[args.index("--output") + 1])
tag = url.split("/download/", 1)[1].split("/", 1)[0]
asset = "kk-studio-daemon-" + tag + ".jar"
assert url in (base + "/download/" + tag + "/" + asset,
               base + "/download/" + tag + "/" + asset + ".sha256")
body = os.environ["FAKE_JAR_BODY"].encode()
if mode == "interrupted":
    target.write_bytes(b"partial")
    os.kill(os.getppid(), signal.SIGTERM)
    sys.exit(0)
if mode == "jar-fail" and not url.endswith(".sha256"):
    target.write_bytes(b"partial")
    sys.exit(22)
if url.endswith(".sha256"):
    if mode == "sha-fail":
        target.write_text("partial")
        sys.exit(22)
    digest = hashlib.sha256(body).hexdigest()
    if mode == "sha-mismatch":
        digest = "0" * 64
    text = digest + "  " + asset + "\\n"
    if mode == "sha-filename":
        text = digest + "  other.jar\\n"
    if mode == "sha-multiline":
        text += digest + "  " + asset + "\\n"
    if mode == "sha-invalid":
        text = "not-a-checksum"
    target.write_text(text)
else:
    target.write_bytes(body)
""",
        )
        if hasattr(self, "plist"):
            # Faithful subset of macOS plutil: XML lint plus reading the first saved argv.
            write_executable(
                self.bin / "plutil",
                """#!/usr/bin/env python3
import os
import plistlib
import sys
with open(os.environ["FAKE_RECORD"], "a") as record:
    record.write("plutil|" + " ".join(sys.argv[1:]) + "\\n")
if os.environ.get("FAKE_PLUTIL_MODE") == "fail":
    sys.exit(1)
with open(sys.argv[-1], "rb") as source:
    data = plistlib.load(source)
if sys.argv[1] == "-extract":
    assert sys.argv[1:-1] == ["-extract", "ProgramArguments.0", "raw", "-o", "-"]
    print(data["ProgramArguments"][0])
else:
    assert sys.argv[1] == "-lint"
""",
            )

    def environment(self, **overrides):
        environment = super().environment(**overrides)
        environment.update({
            "TMPDIR": str(self.downloads),
            # Must be completely ignored by standalone management.
            "KK_STUDIO_REPO_ROOT": str(self.root / "nonexistent-checkout"),
        })
        environment.update(overrides)
        return environment


class ReleaseFixture(ReleaseTools, Fixture):
    pass


class DarwinReleaseFixture(ReleaseTools, DarwinFixture):
    pass


class DownloadContracts:
    """Identical release/failure/configuration contracts against Linux and macOS fixtures."""

    fixture_type = ReleaseFixture

    def fixture(self, **kwargs):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        return self.fixture_type(temporary.name, **kwargs)

    def service_file(self, fixture):
        return fixture.plist if hasattr(fixture, "plist") else fixture.unit

    def assert_clean(self, fixture):
        self.assertEqual([], list(fixture.downloads.iterdir()))
        self.assertEqual([], list(fixture.home.rglob("*.tmp.*")))
        self.assertNotIn("mvn", fixture.tools())
        self.assertNotIn("git", fixture.tools())
        self.assertNotIn(TOKEN_VALUE, fixture.record.read_text())

    def curl_urls(self, fixture):
        return [args.split()[-1] for tool, args in fixture.records() if tool == "curl"]

    def test_default_install_resolves_latest_to_matching_immutable_assets(self):
        """No command plus valid options installs without a repo, Git, or Maven."""
        fixture = self.fixture()
        result = fixture.run(*fixture.install_arguments())
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(fixture.jar_body, fixture.jar.read_text())
        self.assertTrue(self.service_file(fixture).exists())
        asset = f"kk-studio-daemon-{TAG}.jar"
        self.assertEqual([
            RELEASE_BASE + "/latest",
            f"{RELEASE_BASE}/download/{TAG}/{asset}",
            f"{RELEASE_BASE}/download/{TAG}/{asset}.sha256",
        ], self.curl_urls(fixture))
        records = fixture.records()
        verify_index = next(i for i, (tool, args) in enumerate(records)
                            if tool == "java" and args.endswith(" --version"))
        switch_index = next(i for i, (tool, args) in enumerate(records)
                            if (tool == "systemctl" and "restart" in args)
                            or (tool == "launchctl" and "bootstrap" in args))
        self.assertLess(verify_index, switch_index)
        self.assert_clean(fixture)

    def test_pinned_install_skips_latest_discovery(self):
        """A requested version downloads only the two assets for exactly that tag."""
        fixture = self.fixture()
        result = fixture.install("--version", TAG)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, len(self.curl_urls(fixture)))
        self.assertTrue(all(f"/download/{TAG}/" in url for url in self.curl_urls(fixture)))
        self.assert_clean(fixture)

    def test_safe_non_numeric_tag_characters_are_supported(self):
        """Safe prerelease tags are passed literally to both assets and version verification."""
        fixture = self.fixture()
        tag = "v1.0.0-rc_1.experimental"
        java = fixture.jdk / "bin" / "java"
        java.write_text(java.read_text().replace(
            'echo "kk-studio-daemon 1.0.0"',
            'echo "kk-studio-daemon 1.0.0-rc_1.experimental"',
        ))
        result = fixture.install("--version", tag)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertTrue(all(f"/download/{tag}/" in url for url in self.curl_urls(fixture)))
        self.assert_clean(fixture)

    def test_shasum_fallback_without_sha256sum_on_path(self):
        """A macOS-shaped PATH without GNU sha256sum uses shasum -a 256, on both hosts."""
        fixture = self.fixture()
        # Supply only the required external utilities; no SHA256 GNU binary can be discovered.
        for tool in (
            "env", "uname", "stat", "id", "chmod", "mkdir",
            "rm", "mktemp", "cp", "mv", "cat", "tr", "head", "sed",
        ):
            if not (fixture.bin / tool).exists():
                (fixture.bin / tool).symlink_to(shutil.which(tool))
        # Even this intentionally minimal PATH must select Apple's Bash 3.2 on macOS.
        (fixture.bin / "bash").symlink_to("/bin/bash")
        shasum = shutil.which("shasum")
        self.assertIsNotNone(shasum, "test host needs shasum to exercise the macOS fallback")
        write_executable(
            fixture.bin / "shasum",
            '#!/usr/bin/env bash\n'
            'printf "shasum|%s\\n" "$*" >> "$FAKE_RECORD"\n'
            f'exec "{shasum}" "$@"\n',
        )
        result = fixture.install(env={"PATH": str(fixture.bin)})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertTrue(any(tool == "shasum" and args.startswith("-a 256 ")
                            for tool, args in fixture.records()))
        self.assert_clean(fixture)

    def test_install_rejects_unsafe_and_conflicting_artifact_options_before_children(self):
        """Artifact parsing precedes Java/download work and never prints supplied unknown values."""
        fixture = self.fixture()
        for arguments in (
            ["--version", "1.0.0"], ["--version", "v1;bad"],
            ["--version", "v1?bad"], ["--version", "v1/../../bad"],
            ["--version", TAG, "--version", TAG],
            ["--from-source", "--version", TAG],
            ["--from-source", "--from-source"],
        ):
            with self.subTest(arguments=arguments):
                result = fixture.install(*arguments)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual([], fixture.tools())
                self.assertFalse(fixture.jar.exists())
                self.assertFalse(self.service_file(fixture).exists())
                self.assert_clean(fixture)

    def test_upgrade_preserves_all_configuration_credentials_and_data(self):
        """Upgrade changes only JAR bytes; verify with saved Java even if caller Java is invalid."""
        fixture = self.fixture()
        config = fixture.home / "language-servers.json"
        config.write_text('{"servers": {}}')
        data = fixture.home / "custom data"
        data.mkdir()
        durable = data / "state"
        durable.write_bytes(b"durable-data")
        first = fixture.install(**{
            "note": 'trusted note $FOO 10% "quotes"',
            "data-dir": str(data),
            "lsp-config": str(config),
            "bash-executable": "/custom/bash",
        })
        self.assertEqual(0, first.returncode, first.stderr)
        service = self.service_file(fixture)
        before = service.read_bytes()
        token = fixture.token.read_bytes()
        fixture.record.write_text("")
        result = fixture.run("upgrade", env={
            "FAKE_JAR_BODY": "new-release",
            "JAVA_HOME_21": "/not/a/jdk",
            "JAVA_HOME": "/not/a/jdk",
        })
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("new-release", fixture.jar.read_text())
        self.assertEqual(before, service.read_bytes())
        self.assertEqual(token, fixture.token.read_bytes())
        self.assertEqual(b"durable-data", durable.read_bytes())
        self.assertEqual('{"servers": {}}', config.read_text())
        self.assertIn(RELEASE_BASE + "/latest", self.curl_urls(fixture))
        self.assertNotIn("Gateway URI:", result.stdout + result.stderr)
        self.assert_clean(fixture)

    def test_pinned_upgrade_and_parser_fail_closed(self):
        """Pinning works on upgrade; unsafe/duplicated/mixed artifact options cannot touch service."""
        fixture = self.fixture()
        self.assertEqual(0, fixture.install().returncode)
        result = fixture.run("upgrade", "--version", TAG)
        self.assertEqual(0, result.returncode, result.stderr)
        fixture.record.write_text("")
        pinned = fixture.run("upgrade", "--version", TAG)
        self.assertEqual(0, pinned.returncode, pinned.stderr)
        self.assertEqual(2, len(self.curl_urls(fixture)))
        self.assertNotIn(RELEASE_BASE + "/latest", self.curl_urls(fixture))
        service = self.service_file(fixture).read_bytes()
        jar = fixture.jar.read_bytes()
        for arguments in (
            ["--version", "../v1"], ["--version", "v"], ["--version", "v1/evil"],
            ["--version", "v1?bad"], ["--version", "v1\nbad"],
            ["--version", TAG, "--version", TAG],
            ["--version"], ["--from-source", "--version", TAG],
            ["--from-source", "--from-source"], ["--note", TOKEN_VALUE],
        ):
            with self.subTest(arguments=arguments):
                fixture.record.write_text("")
                failed = fixture.run("upgrade", *arguments)
                self.assertNotEqual(0, failed.returncode)
                self.assertEqual([], fixture.tools())
                self.assertEqual(service, self.service_file(fixture).read_bytes())
                self.assertEqual(jar, fixture.jar.read_bytes())
                self.assertNotIn(TOKEN_VALUE, failed.stdout + failed.stderr)

    def test_download_and_verification_failures_keep_running_service_untouched(self):
        """Fetch/hash/version errors must not replace bytes, restart/stop, or leave staging files."""
        fixture = self.fixture()
        self.assertEqual(0, fixture.install().returncode)
        service = self.service_file(fixture).read_bytes()
        jar = fixture.jar.read_bytes()
        token = fixture.token.read_bytes()
        environments = [{"FAKE_DOWNLOAD_MODE": mode} for mode in (
            "latest-fail", "jar-fail", "sha-fail", "sha-mismatch",
            "sha-filename", "sha-multiline", "sha-invalid", "interrupted",
        )]
        environments += [{"FAKE_JAVA_MODE": mode} for mode in (
            "version-fail", "version-unexpected",
        )]
        environments += [{"FAKE_EFFECTIVE_URL": url} for url in (
            RELEASE_BASE + "/latest", RELEASE_BASE + "/tag/v1/evil",
            "http://github.com/fengwk/kk-studio/releases/tag/v1.0.0",
            "https://example.invalid/tag/v1.0.0",
        )]
        for environment in environments:
            for command in ("install", "upgrade"):
                with self.subTest(environment=environment, command=command):
                    fixture.record.write_text("")
                    result = (fixture.install(env=environment) if command == "install"
                              else fixture.run("upgrade", env=environment))
                    self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
                    self.assertEqual(service, self.service_file(fixture).read_bytes())
                    self.assertEqual(jar, fixture.jar.read_bytes())
                    self.assertEqual(token, fixture.token.read_bytes())
                    for tool, args in fixture.records():
                        if tool in ("systemctl", "launchctl"):
                            self.assertFalse(any(word in args for word in (
                                "restart", "bootout", "bootstrap", "kickstart",
                            )), (tool, args))
                    self.assert_clean(fixture)

    def test_release_version_must_match_tag(self):
        """Even an executable JAR fails closed if it identifies itself as a different release."""
        fixture = self.fixture()
        result = fixture.install("--version", "v9.9.9")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("does not match", result.stderr)
        self.assertFalse(fixture.jar.exists())
        self.assertFalse(self.service_file(fixture).exists())
        self.assert_clean(fixture)

    def test_status_and_uninstall_do_not_use_release_or_source_tools(self):
        """Standalone lifecycle operations need neither repo nor download/build/Java discovery."""
        fixture = self.fixture()
        self.assertEqual(0, fixture.install().returncode)
        fixture.record.write_text("")
        for command in ("status", "uninstall"):
            result = fixture.run(command, env={"JAVA_HOME_21": "", "JAVA_HOME": ""})
            self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(fixture.jar.exists())
        self.assertTrue(fixture.token.exists())
        self.assertFalse(any(tool in ("curl", "mvn", "java") for tool in fixture.tools()))
        self.assert_clean(fixture)

    def test_failed_atomic_publish_preserves_old_jar_and_cleans_staging(self):
        """Rename failure does not stop the service or leave a half-installed artifact."""
        fixture = self.fixture()
        self.assertEqual(0, fixture.install().returncode)
        before = fixture.jar.read_bytes()
        fixture.record.write_text("")
        write_executable(fixture.bin / "mv", "#!/usr/bin/env bash\nexit 1\n")
        result = fixture.run("upgrade")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(before, fixture.jar.read_bytes())
        self.assertFalse(any("restart" in args or "kickstart" in args
                             for _, args in fixture.records()))
        self.assert_clean(fixture)

    def test_upgrade_invalid_saved_java_fails_before_download_or_service_switch(self):
        """No fallback Java is substituted for an invalid path in the saved service configuration."""
        fixture = self.fixture()
        self.assertEqual(0, fixture.install().returncode)
        service = self.service_file(fixture)
        service.write_text(service.read_text().replace(str(fixture.jdk), "/missing-jdk"))
        before = service.read_bytes()
        jar = fixture.jar.read_bytes()
        fixture.record.write_text("")
        result = fixture.run("upgrade")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(before, service.read_bytes())
        self.assertEqual(jar, fixture.jar.read_bytes())
        self.assertNotIn("curl", fixture.tools())
        self.assertFalse(any("restart" in args or "kickstart" in args
                             for _, args in fixture.records()))
        self.assert_clean(fixture)

    def test_noninteractive_missing_inputs_fail_clearly(self):
        """With no controlling terminal, missing values fail before any child/service work."""
        fixture = self.fixture()
        for arguments in ([], ["install", "--gateway-uri", GATEWAY_URI]):
            result = subprocess.run(
                ["/bin/bash", str(fixture.script), *arguments],
                env=fixture.environment(), capture_output=True, text=True,
                start_new_session=True, check=False,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("noninteractive install requires", result.stderr)
            self.assertEqual([], fixture.tools())


class TestLinuxDownload(DownloadContracts, unittest.TestCase):
    """Linux release contracts plus a real controlling-terminal prompt over piped stdin."""

    def test_upgrade_decodes_saved_java_path_without_evaluating_it(self):
        """Escaped spaces/quotes/backslashes/%/$ are decoded literally, never passed to a shell."""
        fixture = self.fixture()
        jdk = fixture.root / 'jdk spaces "quotes" \\ 10% $not_expanded'
        shutil.copytree(fixture.jdk, jdk)
        installed = fixture.install(**{"java-home": str(jdk)})
        self.assertEqual(0, installed.returncode, installed.stdout + installed.stderr)
        fixture.record.write_text("")
        result = fixture.run("upgrade", env={"JAVA_HOME_21": "/invalid"})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(2, sum(tool == "java" for tool in fixture.tools()))
        self.assert_clean(fixture)

    def test_piped_script_prompts_on_tty_and_hides_token(self):
        """The script is supplied through stdin while both answers come from the controlling tty."""
        fixture = self.fixture()
        reader, writer = os.pipe()
        pid, terminal = pty.fork()
        if pid == 0:
            os.close(writer)
            os.dup2(reader, 0)
            os.close(reader)
            os.execve("/bin/bash", ["bash", "-s", "--"], fixture.environment())
        os.close(reader)
        self.addCleanup(os.close, terminal)
        with os.fdopen(writer, "wb") as pipe:
            pipe.write(fixture.script.read_bytes())
        output = bytearray()
        deadline = time.monotonic() + 15

        def until(text):
            while text not in output:
                remaining = deadline - time.monotonic()
                self.assertGreater(remaining, 0, bytes(output))
                ready, _, _ = select.select([terminal], [], [], remaining)
                self.assertTrue(ready)
                output.extend(os.read(terminal, 65536))

        try:
            until(b"Gateway URI:")
            os.write(terminal, (GATEWAY_URI + "\n").encode())
            until(b"Registration token (hidden):")
            # Synchronize with read -s disabling ECHO before sending the secret.
            while termios.tcgetattr(terminal)[3] & termios.ECHO:
                self.assertLess(time.monotonic(), deadline)
            os.write(terminal, (TOKEN_VALUE + "\n").encode())
            while True:
                remaining = deadline - time.monotonic()
                self.assertGreater(remaining, 0, bytes(output))
                ready, _, _ = select.select([terminal], [], [], remaining)
                self.assertTrue(ready)
                try:
                    chunk = os.read(terminal, 65536)
                except OSError:
                    break
                if not chunk:
                    break
                output.extend(chunk)
            _, status = os.waitpid(pid, 0)
            pid = None
            self.assertEqual(0, os.waitstatus_to_exitcode(status), bytes(output))
            self.assertNotIn(TOKEN_VALUE.encode(), output)
            self.assertEqual(TOKEN_VALUE, fixture.token.read_text())
            self.assert_clean(fixture)
        finally:
            if pid is not None:
                os.kill(pid, 9)
                os.waitpid(pid, 0)


class TestMacosDownload(DownloadContracts, unittest.TestCase):
    """Darwin LaunchAgent fixtures share all standalone download/upgrade assertions."""

    fixture_type = DarwinReleaseFixture


if __name__ == "__main__":
    unittest.main()
