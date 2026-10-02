"""Permanent contracts for the Environment Daemon host installation (``scripts/daemon/install.sh``).

Every case runs the shipped script inside a throwaway HOME and a throwaway checkout whose
``mvn``/``java``/``systemctl``/``journalctl`` are recorder fakes on PATH, so no assertion needs
the real Maven reactor, a real JDK 21 build, or the caller's user systemd instance. The fakes
record the exact argv/environment each tool received, which is what makes "Maven never sees the
gateway/token/note" and "the unit is started directly without a shell" testable.
"""

import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest

UNIT_MARKER = "# Managed by scripts/daemon/install.sh"
SERVICE_NAME = "kk-studio-daemon.service"
MAVEN_ARGUMENTS = "-B -ntp -pl harness/daemon -am clean package"
# Fixture token text: every failure path must keep this value out of stdout, stderr, argv and
# the installed files, so it is asserted against byte-for-byte.
TOKEN_VALUE = "fixture-registration-token-value"
GATEWAY_URI = "ws://gateway.example.invalid/api/harness/environment-daemon/v1"
NOTE = 'Laptop "A" 100% honest $USER'


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
INSTALL_SCRIPT = REPOSITORY_ROOT / "scripts" / "daemon" / "install.sh"


def write_executable(path, content):
    """Write one fake command with the executable bit already set."""
    # Use the test interpreter even when the fixture deliberately restricts PATH.
    content = content.replace("#!/usr/bin/env python3\n", f"#!{sys.executable}\n", 1)
    path.write_text(content, encoding="utf-8")
    path.chmod(0o755)


def systemd_escape(value):
    """systemd ExecStart word escaping: backslash, quote, specifier (`%`) and variable (`$`)."""
    value = value.replace("\\", "\\\\").replace('"', '\\"')
    return '"' + value.replace("%", "%%").replace("$", "$$") + '"'


class Fixture:
    """One isolated HOME, checkout and fake toolchain for a single install scenario."""

    source_mode = True

    def __init__(
        self,
        root,
        *,
        systemctl_mode="ok",
        mvn_mode="ok",
        java_mode="ok",
        jar_body="fixture-shaded-daemon-jar",
        jdk_tools=("java", "javac"),
        with_unit=None,
    ):
        # macOS temporary paths may traverse /var -> /private/var; match the shell's PWD.
        self.root = Path(root).resolve()
        self.home = self.root / "home"
        self.bin = self.root / "bin"
        self.jdk = self.root / "fake-jdk"
        self.repo = self.root / "checkout"
        self.record = self.root / "records.log"
        self.mvn_environment = self.root / "mvn.env"
        self.jar_body = jar_body
        self.systemctl_mode = systemctl_mode
        self.mvn_mode = mvn_mode
        self.java_mode = java_mode
        self.jdk_tools = tuple(jdk_tools)
        self.script = self.repo / "scripts" / "daemon" / "install.sh"
        self.token_dir = self.home / ".config" / "kk-studio"
        self.token = self.token_dir / "daemon.token"
        self.unit = self.home / ".config" / "systemd" / "user" / SERVICE_NAME
        self.jar = self.home / ".local" / "lib" / "kk-studio" / "kk-studio-daemon.jar"

        for directory in (self.home, self.bin, self.jdk / "bin", self.repo / "harness" / "daemon"):
            directory.mkdir(parents=True, exist_ok=True)
        # 仓库根由 worktree 标记向上解析，因此夹具必须是一个可识别的 checkout。
        (self.repo / ".git").mkdir()
        self.script.parent.mkdir(parents=True, exist_ok=True)
        self.script.write_text(INSTALL_SCRIPT.read_text(encoding="utf-8"), encoding="utf-8")
        self.script.chmod(0o755)
        self.record.write_text("", encoding="utf-8")

        self._write_toolchain(systemctl_mode, mvn_mode, java_mode, jar_body, self.jdk_tools)

        self.token_dir.mkdir(parents=True, exist_ok=True)
        self.token.write_text(TOKEN_VALUE + "\n", encoding="utf-8")
        self.token.chmod(0o600)

        if with_unit is not None:
            self.unit.parent.mkdir(parents=True, exist_ok=True)
            self.unit.write_text(with_unit, encoding="utf-8")

    def _write_toolchain(self, systemctl_mode, mvn_mode, java_mode, jar_body, jdk_tools):
        # Linux contracts also run on Darwin; never fall through to real launchd tools.
        write_executable(self.bin / "uname", "#!/bin/bash\nprintf 'Linux\\n'\n")
        write_executable(
            self.bin / "mvn",
            "#!/usr/bin/env bash\n"
            'printf \'mvn|%s|%s|%s\\n\' "$PWD" "${JAVA_HOME:-}" "$*" >> "$FAKE_RECORD"\n'
            'env > "$FAKE_MVN_ENV"\n'
            # `clean package` 会先删除既有 target/，因此夹具也必须如此，否则「构建产物缺失」无从复现。
            "rm -rf harness/daemon/target\n"
            'if [ "${FAKE_MVN_MODE:-ok}" = "fail" ]; then\n'
            '  echo "fake maven failure" >&2\n'
            "  exit 1\n"
            "fi\n"
            "mkdir -p harness/daemon/target\n"
            'if [ "${FAKE_MVN_MODE:-ok}" = "missing-jar" ]; then\n'
            "  exit 0\n"
            "fi\n"
            'if [ "${FAKE_MVN_MODE:-ok}" = "empty-jar" ]; then\n'
            "  : > harness/daemon/target/kk-studio-daemon.jar\n"
            "  exit 0\n"
            "fi\n"
            'printf \'%s\\n\' "${FAKE_JAR_BODY:-}" > harness/daemon/target/kk-studio-daemon.jar\n'
            "exit 0\n",
        )
        write_executable(
            self.jdk / "bin" / "java",
            "#!/usr/bin/env bash\n"
            'printf \'java|%s\\n\' "$*" >> "$FAKE_RECORD"\n'
            'if [ "$1" = "-version" ]; then\n'
            '  if [ "${FAKE_JAVA_MODE:-ok}" = "jdk17" ]; then\n'
            '    echo \'openjdk version "17.0.9" 2023-10-17\' >&2\n'
            "    exit 0\n"
            "  fi\n"
            '  echo \'openjdk version "21.0.1" 2023-10-17\' >&2\n'
            "  exit 0\n"
            "fi\n"
            'case "$*" in\n'
            "  *--version*)\n"
            '    if [ "${FAKE_JAVA_MODE:-ok}" = "version-fail" ]; then\n'
            '      echo "Error: Invalid or corrupt jarfile" >&2\n'
            "      exit 1\n"
            "    fi\n"
            '    if [ "${FAKE_JAVA_MODE:-ok}" = "version-unexpected" ]; then\n'
            '      echo "not-a-daemon"\n'
            "      exit 0\n"
            "    fi\n"
            '    echo "kk-studio-daemon 1.0.0"\n'
            "    exit 0 ;;\n"
            "esac\n"
            "exit 0\n",
        )
        # 只有列出的工具存在：缺少 javac/javap 的 JDK home 必须在安装前被拒绝。
        if "javac" in jdk_tools:
            write_executable(self.jdk / "bin" / "javac", "#!/usr/bin/env bash\nexit 1\n")
        if "javap" in jdk_tools:
            write_executable(self.jdk / "bin" / "javap", "#!/usr/bin/env bash\nexit 1\n")
        write_executable(
            self.bin / "systemctl",
            "#!/usr/bin/env bash\n"
            'printf \'systemctl|%s\\n\' "$*" >> "$FAKE_RECORD"\n'
            'mode=${FAKE_SYSTEMCTL_MODE:-ok}\n'
            'if [ "$mode" = "unavailable" ]; then\n'
            '  echo "System has not been booted with systemd" >&2\n'
            "  exit 1\n"
            "fi\n"
            'case "$*" in\n'
            "  *is-active*)\n"
            '    if [ "$mode" = "inactive" ]; then exit 3; fi\n'
            '    if [ "$mode" = "flapping" ]; then\n'
            # 第一次报告 active，之后立刻退出：任何「一次 active 即成功」的实现都会被抓住。
            '      counter=${FAKE_SYSTEMCTL_COUNTER:?flapping mode needs a counter path}\n'
            '      attempts=$(cat "$counter" 2>/dev/null || printf 0)\n'
            "      attempts=$((attempts + 1))\n"
            '      printf %s "$attempts" > "$counter"\n'
            '      if [ "$attempts" -gt 1 ]; then exit 3; fi\n'
            "    fi\n"
            "    exit 0 ;;\n"
            "  *\"disable --now\"*)\n"
            '    if [ "$mode" = "disable-fail" ]; then\n'
            '      echo "fake disable failure" >&2\n'
            "      exit 1\n"
            "    fi\n"
            "    exit 0 ;;\n"
            "  *restart*)\n"
            '    if [ "$mode" = "restart-fail" ]; then\n'
            '      echo "fake restart failure" >&2\n'
            "      exit 1\n"
            "    fi\n"
            "    exit 0 ;;\n"
            "esac\n"
            "exit 0\n",
        )
        write_executable(
            self.bin / "journalctl",
            "#!/usr/bin/env bash\n"
            'printf \'journalctl|%s\\n\' "$*" >> "$FAKE_RECORD"\n'
            'echo "fake journal line"\n'
            "exit 0\n",
        )

    def environment(self, **overrides):
        """Base environment: only the fake toolchain and fixture inputs are visible."""
        environment = {
            "HOME": str(self.home),
            "PATH": f"{self.bin}:{self.jdk / 'bin'}:/usr/bin:/bin",
            "JAVA_HOME": str(self.jdk),
            "JAVA_HOME_21": str(self.jdk),
            "FAKE_RECORD": str(self.record),
            "FAKE_MVN_ENV": str(self.mvn_environment),
            "FAKE_MVN_MODE": self.mvn_mode,
            "FAKE_JAVA_MODE": self.java_mode,
            "FAKE_SYSTEMCTL_MODE": self.systemctl_mode,
            "FAKE_JAR_BODY": self.jar_body,
            # 活性验证必须只做一次立即检查，失败场景才不会拖长测试。
            "DAEMON_VERIFY_TIMEOUT_SECONDS": "0",
            "DAEMON_VERIFY_STABLE_SECONDS": "0",
        }
        environment.update(overrides)
        return environment

    def run(self, *arguments, env=None, cwd=None):
        """Source-build fixtures explicitly opt into source upgrades; release fixtures do not."""
        if self.source_mode and arguments and arguments[0] == "upgrade":
            arguments = (arguments[0], "--from-source", *arguments[1:])
        return subprocess.run(
            ["/bin/bash", str(self.script), *arguments],
            cwd=str(cwd or self.repo),
            env=self.environment(**(env or {})),
            text=True,
            capture_output=True,
            check=False,
            # Missing credentials must never prompt on the test runner's actual terminal.
            start_new_session=True,
        )

    def install_arguments(self, **overrides):
        """Default valid `install` argv; overrides make one option invalid per case."""
        arguments = {
            "gateway-uri": GATEWAY_URI,
            "registration-token-file": str(self.token),
            "java-home": str(self.jdk),
            "bash-executable": "/bin/bash",
        }
        arguments.update(overrides)
        items = ["--from-source"] if self.source_mode else []
        for name, value in arguments.items():
            if value is None:
                continue
            items.extend([f"--{name}", value])
        return items

    def records(self):
        """Return the recorded tool invocations as ``(tool, payload)`` pairs."""
        lines = [line for line in self.record.read_text(encoding="utf-8").splitlines() if line]
        return [tuple(line.split("|", 1)) for line in lines]

    def tools(self):
        """Return only the recorded tool names in invocation order."""
        return [tool for tool, _ in self.records()]

    def install(self, *extra, env=None, cwd=None, **overrides):
        """Run a valid `install` plus extra arguments, option overrides and env overrides."""
        return self.run(
            "install", *self.install_arguments(**overrides), *extra, env=env, cwd=cwd
        )

    def service_invocations(self):
        """Recorded systemctl calls that address the service, without the usability probe."""
        return [
            payload
            for tool, payload in self.records()
            if tool == "systemctl" and payload != "--user --no-pager show-environment"
        ]

    def unit_text(self):
        return self.unit.read_text(encoding="utf-8")


class DaemonInstallTestCase(unittest.TestCase):
    """Shared fixture plumbing: every case gets a fresh temp HOME/checkout/toolchain."""

    def fixture(self, **kwargs):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        return Fixture(temporary.name, **kwargs)

    def assert_failed_without_install(self, fixture, result):
        """A rejected invocation must not create the managed unit or the installed JAR."""
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse(fixture.unit.exists(), "rejected install must not write the unit")
        self.assertFalse(fixture.jar.exists(), "rejected install must not install a JAR")

    def assert_fixture_untouched(self, fixture, unit_text, jar_text):
        """A failed update must leave the previous managed installation byte-identical."""
        self.assertEqual(unit_text, fixture.unit_text())
        self.assertEqual(jar_text, fixture.jar.read_text(encoding="utf-8"))


class InlineTokenContracts:
    """Run identical credential security assertions with Linux and Darwin recorder tools."""

    def inline_install(self, fixture, *extra, **kwargs):
        return fixture.install(
            *extra, **{"registration-token-file": None,
                       "registration-token": TOKEN_VALUE, **kwargs}
        )

    def test_inline_creation_replacement_and_preservation(self):
        """Private creation and replacement leave only a path in service/tool output."""
        fixture = self.fixture()
        fixture.token.unlink()
        fixture.token_dir.rmdir()
        fixture.token_dir.parent.rmdir()
        child_environment = fixture.root / "children.env"
        # Record the real filesystem utilities too: an external printf/echo or exported secret
        # would otherwise evade the Maven/Java/service recorder assertions.
        for tool in ("mv", "cp", "chmod", "mktemp", "mkdir", "stat"):
            # BSD cp/mv/mkdir live in /bin, whereas Linux commonly uses /usr/bin.
            native_tool = shutil.which(tool, path="/usr/bin:/bin")
            write_executable(
                fixture.bin / tool,
                "#!/usr/bin/env bash\n"
                f'printf \'{tool}|%s\\n\' "$*" >> "$FAKE_RECORD"\n'
                f'/usr/bin/env >> "{child_environment}"\n'
                f'exec "{native_tool}" "$@"\n',
            )
        for replacement in (False, True):
            if replacement:
                fixture.token.chmod(0o644)
                fixture.token.write_text("old-value")
                previous_inode = fixture.token.stat().st_ino
            fixture.record.write_text("")
            result = self.inline_install(fixture, env={"INLINE_TOKEN": "inherited-marker"})
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual(TOKEN_VALUE, fixture.token.read_text())
            self.assertEqual(0o600, stat.S_IMODE(fixture.token.stat().st_mode))
            self.assertEqual(0o700, stat.S_IMODE(fixture.token_dir.stat().st_mode))
            self.assertEqual(0o700, stat.S_IMODE(fixture.token_dir.parent.stat().st_mode))
            if replacement:
                self.assertNotEqual(previous_inode, fixture.token.stat().st_ino)
            self.assertEqual(["daemon.token"], [p.name for p in fixture.token_dir.iterdir()])
            service = fixture.plist if hasattr(fixture, "plist") else fixture.unit
            for text in (result.stdout + result.stderr, service.read_text(),
                         fixture.record.read_text(), fixture.mvn_environment.read_text(),
                         child_environment.read_text()):
                self.assertNotIn(TOKEN_VALUE, text)
            self.assertNotIn("INLINE_TOKEN=", fixture.mvn_environment.read_text())
            self.assertIn(str(fixture.token), service.read_text())
            records = fixture.records()
            token_publish = next(
                i for i, (tool, args) in enumerate(records)
                if tool == "mv" and args.endswith(f" {fixture.token}")
            )
            jar_publish = next(
                i for i, (tool, args) in enumerate(records)
                if tool == "mv" and args.endswith(f" {fixture.jar}")
            )
            self.assertLess(token_publish, jar_publish)
            if hasattr(fixture, "plist"):
                self.assertLess(fixture.tools().index("plutil"), token_publish)
                if replacement:
                    self.assertLess(token_publish, records.index(
                        ("launchctl", f"bootout {fixture.target}")
                    ))
        removed = fixture.run("uninstall")
        self.assertEqual(0, removed.returncode, removed.stderr)
        self.assertEqual(TOKEN_VALUE, fixture.token.read_text())

    def test_inline_token_is_written_literally(self):
        """Shell metacharacters in the value are data, never evaluated or treated as printf format."""
        fixture = self.fixture()
        value = "fixture-$HOME-`id`-%s-\"quote\"-'quote'-\\-;end"
        result = self.inline_install(fixture, **{"registration-token": value})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(value, fixture.token.read_text())
        for output in (result.stdout, result.stderr, fixture.record.read_text(),
                       fixture.mvn_environment.read_text()):
            self.assertNotIn(value, output)

    def test_inline_invalid_inputs_have_no_side_effects(self):
        """Reject ambiguous, repeated, empty and whitespace credentials without echoing values."""
        cases = [
            (["--registration-token-file", "not-absolute"], {}),
            (["--registration-token", TOKEN_VALUE], {}),
            ([TOKEN_VALUE], {}),
            (["--unknown", TOKEN_VALUE], {}),
        ]
        cases.extend(([], {"registration-token": value})
                     for value in ("", " ", "a b", "\tbad", "bad\n", "bad\r", "bad\x01"))
        for extra, overrides in cases:
            with self.subTest(extra=extra, overrides=overrides):
                fixture = self.fixture()
                before = fixture.token.read_bytes()
                result = self.inline_install(fixture, *extra, **overrides)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(before, fixture.token.read_bytes())
                self.assertEqual([], fixture.tools())
                self.assertNotIn(TOKEN_VALUE, result.stdout + result.stderr)

    def test_inline_build_and_preflight_failures_preserve_token(self):
        """Failures before publication cannot create or replace a credential."""
        for present in (False, True):
            for env in ({"FAKE_MVN_MODE": "fail"}, {"FAKE_MVN_MODE": "missing-jar"},
                        {"FAKE_JAVA_MODE": "version-fail"}, {"FAKE_JAVA_MODE": "jdk17"}):
                with self.subTest(present=present, env=env):
                    fixture = self.fixture()
                    before = fixture.token.read_bytes()
                    if not present:
                        fixture.token.unlink()
                        fixture.token_dir.rmdir()
                    result = self.inline_install(fixture, env=env)
                    self.assertNotEqual(0, result.returncode)
                    if present:
                        self.assertEqual(before, fixture.token.read_bytes())
                    else:
                        self.assertFalse(fixture.token_dir.exists())
                    self.assertNotIn(TOKEN_VALUE, result.stdout + result.stderr)

    def test_inline_rejects_unsafe_targets_and_parents(self):
        """Metadata preflight rejects links, wrong types, foreign owners and writable parents."""
        for kind in ("file-link", "parent-link", "directory", "parent-file", "writable-home",
                     "writable", "foreign-parent", "foreign-file"):
            with self.subTest(kind=kind):
                fixture = self.fixture()
                before = fixture.token.read_bytes()
                if kind == "file-link":
                    fixture.token.unlink()
                    fixture.token.symlink_to(fixture.root / "missing")
                elif kind == "directory":
                    fixture.token.unlink()
                    fixture.token.mkdir()
                elif kind in ("parent-link", "parent-file"):
                    fixture.token.unlink()
                    fixture.token_dir.rmdir()
                    if kind == "parent-link":
                        fixture.token_dir.symlink_to(fixture.root)
                    else:
                        fixture.token_dir.write_text("not-directory")
                elif kind == "writable":
                    fixture.token_dir.parent.chmod(0o777)
                elif kind == "writable-home":
                    fixture.home.chmod(0o777)
                else:
                    # Fake stat changes only owner metadata, not caller uid or permissions.
                    target = fixture.token_dir if kind == "foreign-parent" else fixture.token
                    write_executable(
                        fixture.bin / "stat",
                        '#!/usr/bin/env bash\n'
                        f'if [ "$2" = "%u" ] && [ "$3" = "{target}" ]; then\n'
                        f'  printf "%s\\n" "{os.getuid() + 1}"; exit 0\nfi\n'
                        'exec /usr/bin/stat "$@"\n',
                    )
                result = self.inline_install(
                    fixture, env={"HOME": str(fixture.home) + ("/" if kind == "writable-home" else "")}
                )
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn("mvn", fixture.tools())
                self.assertNotIn(TOKEN_VALUE, result.stdout + result.stderr)
                if kind in ("writable", "foreign-parent", "foreign-file"):
                    self.assertEqual(before, fixture.token.read_bytes())

    def test_inline_unmanaged_service_and_unknown_input_never_create_token(self):
        """Neither a foreign service nor parser rejection may publish an absent default file."""
        for present in (False, True):
            for foreign in (False, True):
                fixture = self.fixture()
                before = fixture.token.read_bytes()
                if not present:
                    fixture.token.unlink()
                    fixture.token_dir.rmdir()
                service = fixture.plist if hasattr(fixture, "plist") else fixture.unit
                if foreign:
                    service.parent.mkdir(parents=True, exist_ok=True)
                    service.write_text("foreign-service\n")
                result = self.inline_install(fixture, *([] if foreign else [TOKEN_VALUE]))
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn("mvn", fixture.tools())
                if present:
                    self.assertEqual(before, fixture.token.read_bytes())
                else:
                    self.assertFalse(fixture.token_dir.exists())
                if foreign:
                    self.assertEqual("foreign-service\n", service.read_text())

    def test_inline_failed_rename_cleans_staging_and_preserves_token(self):
        """A publication failure keeps the old file and removes the secret staging file."""
        fixture = self.fixture()
        before = fixture.token.read_bytes()
        write_executable(fixture.bin / "mv", "#!/usr/bin/env bash\nexit 1\n")
        result = self.inline_install(fixture)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(before, fixture.token.read_bytes())
        self.assertEqual(["daemon.token"], [p.name for p in fixture.token_dir.iterdir()])
        self.assertEqual([], list(fixture.home.rglob("*.tmp.*")))
        self.assertNotIn(TOKEN_VALUE, result.stdout + result.stderr)


class TestLinuxInlineToken(InlineTokenContracts, DaemonInstallTestCase):
    """Unix credential contracts against the fake systemd host."""


class TestDaemonInstallInterface(DaemonInstallTestCase):
    """Entry-point contract: syntax, executable bit, help and usage failures."""

    def test_script_is_executable_shell(self):
        """CI runs this file directly, so the executable bit and `bash -n` are both required."""
        self.assertTrue(INSTALL_SCRIPT.is_file())
        mode = INSTALL_SCRIPT.stat().st_mode
        self.assertTrue(mode & stat.S_IXUSR, "install.sh must be executable")
        result = subprocess.run(
            ["/bin/bash", "-n", str(INSTALL_SCRIPT)], text=True, capture_output=True, check=False
        )
        self.assertEqual(0, result.returncode, result.stderr)

    def test_help_documents_commands_and_required_options(self):
        """`--help` is the only discovery surface, so it must list commands and required flags."""
        fixture = self.fixture()
        for arguments in (["--help"], ["-h"], ["install", "--help"]):
            result = fixture.run(*arguments)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            for expected in ("install", "upgrade", "status", "uninstall"):
                self.assertIn(expected, result.stdout)
            self.assertIn("--gateway-uri", result.stdout)
            self.assertIn("--registration-token-file", result.stdout)
            self.assertIn("--registration-token <token>", result.stdout)
            self.assertIn("argv/shell history", result.stdout)
            self.assertIn("bash -x", result.stdout)
        # 帮助是纯信息命令：不得构建、不得写任何安装路径。
        self.assertEqual([], fixture.tools())
        self.assertFalse(fixture.unit.exists())

    def test_help_states_platform_specific_status_semantics(self):
        """status is `active` on Linux and `loaded` on macOS, never a plain `running` claim."""
        fixture = self.fixture()
        result = fixture.run("--help")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("when the unit is active", result.stdout)
        self.assertIn("when the LaunchAgent is loaded", result.stdout)
        self.assertIn("Loaded is not a", result.stdout)
        self.assertNotIn("Exit 0 when running", result.stdout)

    def test_missing_command_prompts_and_unknown_command_fails_closed(self):
        """Default install without a terminal requires explicit credentials; unknown commands fail."""
        fixture = self.fixture()
        empty = fixture.run()
        self.assertNotEqual(0, empty.returncode)
        self.assertIn("noninteractive install requires", empty.stderr)

        unknown = fixture.run("reinstall")
        self.assertNotEqual(0, unknown.returncode)
        self.assertIn("unknown command", unknown.stderr)
        misplaced_secret = fixture.run(TOKEN_VALUE)
        self.assertNotEqual(0, misplaced_secret.returncode)
        self.assertNotIn(TOKEN_VALUE, misplaced_secret.stdout + misplaced_secret.stderr)
        self.assertEqual([], fixture.tools())

    def test_install_help_requires_the_complete_argument_list(self):
        """Help must never mask an unknown/duplicate option or a second argument."""
        fixture = self.fixture()
        complete = fixture.run("install", "--help")
        self.assertEqual(0, complete.returncode, complete.stdout + complete.stderr)
        self.assertIn("--gateway-uri", complete.stdout)

        trailing_unknown = fixture.install("--help", "--unknown")
        self.assertNotEqual(0, trailing_unknown.returncode)
        # 帮助不能吞掉任何其它参数：第一个无法识别的参数就是失败原因。
        self.assertIn("unknown option:", trailing_unknown.stderr)

        leading_unknown = fixture.install("--unknown", "--help")
        self.assertNotEqual(0, leading_unknown.returncode)
        self.assertIn("unknown option:", leading_unknown.stderr)

        mixed = fixture.run("install", "--help", "--gateway-uri", GATEWAY_URI)
        self.assertNotEqual(0, mixed.returncode)
        self.assertIn("unknown option:", mixed.stderr)

        # 帮助与失败都不得构建或写入任何安装路径。
        self.assertFalse(fixture.unit.exists())
        self.assertFalse(fixture.jar.exists())
        self.assertEqual([], fixture.tools())


class TestDaemonInstallSuccess(DaemonInstallTestCase):
    """The managed layout, the unit contents and the build contract of a successful install."""

    def test_install_writes_only_the_managed_layout(self):
        """Exactly one JAR and one unit are created, with the documented paths and modes."""
        fixture = self.fixture(jar_body="shaded-jar-payload")
        result = fixture.install()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

        self.assertTrue(fixture.jar.is_file())
        self.assertEqual(0o644, stat.S_IMODE(fixture.jar.stat().st_mode))
        self.assertIn("shaded-jar-payload", fixture.jar.read_text(encoding="utf-8"))

        self.assertTrue(fixture.unit.is_file())
        self.assertEqual(0o644, stat.S_IMODE(fixture.unit.stat().st_mode))

        # 没有 `~/.local/bin` wrapper，没有 daemon 环境/配置文件：`.config/kk-studio` 只有 token。
        self.assertFalse((fixture.home / ".local" / "bin").exists())
        self.assertEqual(["daemon.token"], sorted(p.name for p in fixture.token_dir.iterdir()))
        self.assertEqual(
            ["kk-studio-daemon.jar"],
            sorted(p.name for p in fixture.jar.parent.iterdir()),
        )
        self.assertEqual([SERVICE_NAME], sorted(p.name for p in fixture.unit.parent.iterdir()))
        # 临时替换文件不得残留。
        self.assertFalse(list(fixture.jar.parent.glob(".*tmp*")))

    def test_unit_marks_ownership_and_keeps_hardening(self):
        """The unit is recognizably managed and retains the documented hardening/lifecycle set."""
        fixture = self.fixture()
        result = fixture.install()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

        lines = fixture.unit_text().splitlines()
        self.assertEqual(UNIT_MARKER, lines[0])
        self.assertNotIn("Environment=", fixture.unit_text())
        for expected in (
            "Wants=network-online.target",
            "After=network-online.target",
            "StartLimitIntervalSec=300",
            "StartLimitBurst=5",
            "Type=simple",
            "WorkingDirectory=%h",
            "Restart=on-failure",
            "RestartSec=10",
            "TimeoutStopSec=30",
            "KillMode=mixed",
            "UMask=0077",
            "NoNewPrivileges=yes",
            "WantedBy=default.target",
        ):
            self.assertIn(expected, fixture.unit_text())
        # 单元不得经过 shell 求值。
        exec_line = next(line for line in lines if line.startswith("ExecStart="))
        self.assertNotIn("sh -c", exec_line)
        self.assertNotIn("/bin/sh", exec_line)

    def test_exec_start_passes_daemon_arguments_directly_with_escaping(self):
        """ExecStart must call java -jar directly and keep the daemon CLI values verbatim."""
        fixture = self.fixture()
        lsp_config = fixture.home / "lsp-config.json"
        lsp_config.write_text('{"servers": {}}', encoding="utf-8")
        result = fixture.install(**{"note": NOTE, "lsp-config": str(lsp_config)})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

        exec_line = next(
            line
            for line in fixture.unit_text().splitlines()
            if line.startswith("ExecStart=")
        )
        tokens = re.findall(r'"(?:[^"\\]|\\.)*"', exec_line.removeprefix("ExecStart="))
        self.assertEqual(
            [
                systemd_escape(str(fixture.jdk / "bin" / "java")),
                systemd_escape("-jar"),
                systemd_escape(str(fixture.jar)),
                systemd_escape("--gateway-uri"),
                systemd_escape(GATEWAY_URI),
                systemd_escape("--registration-token-file"),
                systemd_escape(str(fixture.token)),
                systemd_escape("--data-dir"),
                systemd_escape(str(fixture.home / ".kk-studio")),
                systemd_escape("--note"),
                systemd_escape(NOTE),
                systemd_escape("--bash-executable"),
                systemd_escape("/bin/bash"),
                systemd_escape("--lsp-config"),
                systemd_escape(str(lsp_config)),
            ],
            tokens,
        )
        # 逐字确认 `%` 与 `$` 的转义（systemd 会先做说明符与变量展开，再做引号解析）。
        self.assertIn('"Laptop \\"A\\" 100%% honest $$USER"', exec_line)

    def test_optional_lsp_config_is_omitted_by_default(self):
        """Omitting --lsp-config must leave LSP disabled instead of passing a blank value."""
        fixture = self.fixture()
        result = fixture.install()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertNotIn("--lsp-config", fixture.unit_text())
        self.assertNotIn("--lsp-bridge-command", fixture.unit_text())
        self.assertNotIn("--javap-executable", fixture.unit_text())

    def test_maven_receives_only_build_arguments_and_the_selected_java_home(self):
        """Config/token/note must never reach Maven's argv or environment."""
        fixture = self.fixture()
        result = fixture.install(**{"note": NOTE})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

        maven = [payload for tool, payload in fixture.records() if tool == "mvn"]
        self.assertEqual(
            [f"{fixture.repo}|{fixture.jdk}|{MAVEN_ARGUMENTS}"],
            maven,
        )
        maven_environment = fixture.mvn_environment.read_text(encoding="utf-8")
        for secret in (GATEWAY_URI, str(fixture.token), TOKEN_VALUE, NOTE):
            self.assertNotIn(secret, maven_environment)

    def test_built_artifact_is_verified_with_the_selected_java(self):
        """The build must be followed by a real `java -jar <jar> --version` on the selected JDK."""
        fixture = self.fixture()
        result = fixture.install()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn(
            ("java", f"-jar {fixture.repo}/harness/daemon/target/kk-studio-daemon.jar --version"),
            fixture.records(),
        )

    def test_service_is_reloaded_enabled_restarted_and_verified(self):
        """Install must reload, enable, restart and only then report success."""
        fixture = self.fixture()
        result = fixture.install()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        invocations = [payload for _, payload in fixture.records()]
        self.assertIn("--user --no-pager show-environment", invocations)
        self.assertIn("--user daemon-reload", invocations)
        self.assertIn(f"--user enable {SERVICE_NAME}", invocations)
        self.assertIn(f"--user restart {SERVICE_NAME}", invocations)
        self.assertIn(f"--user is-active --quiet {SERVICE_NAME}", invocations)
        self.assertLess(
            invocations.index(f"--user restart {SERVICE_NAME}"),
            invocations.index(f"--user is-active --quiet {SERVICE_NAME}"),
        )

    def test_token_value_and_secrets_never_appear_in_output(self):
        """Stdout/stderr and the installed files must never carry the token text."""
        fixture = self.fixture()
        result = fixture.install(**{"note": NOTE})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        combined = result.stdout + result.stderr
        self.assertNotIn(TOKEN_VALUE, combined)
        self.assertNotIn(TOKEN_VALUE, fixture.unit_text())
        # token 路径是单元的必要输入，但值不是。
        self.assertIn(str(fixture.token), fixture.unit_text())
        self.assertNotIn(TOKEN_VALUE, fixture.record.read_text(encoding="utf-8"))
        # 卸载输出同样不得回显 token 内容或路径以外的秘密。
        uninstall = fixture.run("uninstall")
        self.assertEqual(0, uninstall.returncode, uninstall.stdout + uninstall.stderr)
        self.assertNotIn(TOKEN_VALUE, uninstall.stdout + uninstall.stderr)

    def test_reinstall_updates_the_managed_unit(self):
        """`install` over a managed unit must atomically update the stored configuration."""
        fixture = self.fixture()
        first = fixture.install(**{"note": "first-note"})
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        second = fixture.install(**{"note": "second-note"})
        self.assertEqual(0, second.returncode, second.stdout + second.stderr)
        self.assertIn("second-note", fixture.unit_text())
        self.assertNotIn("first-note", fixture.unit_text())

    def test_repository_root_override_is_honored(self):
        """`KK_STUDIO_REPO_ROOT` must select the built checkout instead of the script's own."""
        fixture = self.fixture()
        override = fixture.root / "other-checkout"
        (override / "harness" / "daemon").mkdir(parents=True)
        result = fixture.install(env={"KK_STUDIO_REPO_ROOT": str(override)})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        maven = [payload for tool, payload in fixture.records() if tool == "mvn"]
        self.assertEqual([f"{override}|{fixture.jdk}|{MAVEN_ARGUMENTS}"], maven)

    def test_java_home_resolution_order(self):
        """Explicit --java-home wins; otherwise JAVA_HOME_21, then JAVA_HOME, then PATH."""
        fixture = self.fixture()
        second_jdk = fixture.root / "second-jdk"
        (second_jdk / "bin").mkdir(parents=True)
        write_executable(
            second_jdk / "bin" / "java",
            "#!/usr/bin/env bash\n"
            'if [ "$1" = "-version" ]; then\n'
            '  echo \'openjdk version "21.0.7" 2025-01-21\' >&2\n'
            "  exit 0\n"
            "fi\n"
            'echo "kk-studio-daemon 1.0.0"\n'
            "exit 0\n",
        )
        for tool in ("javac", "javap"):
            # 第二个 JDK home 也必须是完整 JDK：缺 javac 会在构建前被拒绝。
            write_executable(second_jdk / "bin" / tool, "#!/usr/bin/env bash\nexit 1\n")

        # JAVA_HOME_21 优先于 JAVA_HOME。
        result = fixture.install(
            env={"JAVA_HOME_21": str(second_jdk), "JAVA_HOME": str(fixture.jdk)},
            **{"java-home": None},
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn(str(second_jdk), fixture.unit_text())

        # 显式 --java-home 优先于两个环境变量。
        result = fixture.install(
            env={"JAVA_HOME_21": str(second_jdk), "JAVA_HOME": str(second_jdk)},
            **{"java-home": str(fixture.jdk)},
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn(str(fixture.jdk), fixture.unit_text())

    def test_default_bash_executable_is_resolved(self):
        """Without --bash-executable the unit must carry the resolved absolute bash path."""
        fixture = self.fixture()
        result = fixture.install(**{"bash-executable": None})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        exec_line = next(
            line
            for line in fixture.unit_text().splitlines()
            if line.startswith("ExecStart=")
        )
        bash_token = exec_line.split('"--bash-executable" ', 1)[1].split(" ", 1)[0]
        resolved = bash_token.strip('"')
        self.assertTrue(resolved.startswith("/"), resolved)
        self.assertTrue(os.access(resolved, os.X_OK), resolved)
        self.assertTrue(resolved.endswith("bash"), resolved)


class TestDaemonInstallValidation(DaemonInstallTestCase):
    """Fail-closed input contracts: everything is rejected before any path is touched."""

    def test_required_options_are_mandatory(self):
        """install without the gateway or the token file must fail with usage-level clarity."""
        fixture = self.fixture()
        missing_uri = fixture.run(
            "install",
            "--registration-token-file",
            str(fixture.token),
            "--java-home",
            str(fixture.jdk),
        )
        self.assert_failed_without_install(fixture, missing_uri)
        self.assertIn("--gateway-uri", missing_uri.stderr)

        missing_token = fixture.run(
            "install", "--gateway-uri", GATEWAY_URI, "--java-home", str(fixture.jdk)
        )
        self.assert_failed_without_install(fixture, missing_token)
        self.assertIn("--registration-token-file", missing_token.stderr)
        self.assertEqual([], fixture.tools())

    def test_invalid_gateway_uris_are_rejected(self):
        """Only absolute ws/wss URLs with a host are acceptable gateway values."""
        for uri in (
            "http://gateway.example.invalid/x",
            "/api/harness/environment-daemon/v1",
            "ws://",
            "ws://gateway example.invalid/x",
            "wss:/gateway.example.invalid/x",
            "gateway.example.invalid",
        ):
            with self.subTest(uri=uri):
                fixture = self.fixture()
                self.assert_failed_without_install(fixture, fixture.install(**{"gateway-uri": uri}))
                self.assertEqual([], fixture.tools())

    def test_token_file_requirements_are_enforced(self):
        """Absolute, regular, non-symlink, owner-only and non-empty are all mandatory."""
        cases = {}

        fixture = self.fixture()
        cases["relative path"] = (fixture, "daemon.token", "absolute path")
        cases["missing file"] = (fixture, str(fixture.root / "missing.token"), "regular file")

        fixture = self.fixture()
        cases["directory"] = (fixture, str(fixture.token_dir), "regular file")

        fixture = self.fixture()
        link = fixture.root / "token-link"
        link.symlink_to(fixture.token)
        cases["symlink"] = (fixture, str(link), "symbolic link")

        fixture = self.fixture()
        fixture.token.write_text("", encoding="utf-8")
        cases["empty"] = (fixture, str(fixture.token), "must not be empty")

        for mode in (0o640, 0o604, 0o666):
            fixture = self.fixture()
            fixture.token.chmod(mode)
            cases[f"mode {mode:o}"] = (fixture, str(fixture.token), "group or other permissions")

        fixture = self.fixture()
        fixture.token.chmod(0o200)
        cases["not owner-readable"] = (fixture, str(fixture.token), "readable by its owner")

        for name, (fixture, token, expected) in cases.items():
            with self.subTest(case=name):
                result = fixture.install(**{"registration-token-file": token})
                self.assert_failed_without_install(fixture, result)
                self.assertIn(expected, result.stderr)
                self.assertNotIn(TOKEN_VALUE, result.stdout + result.stderr)
                self.assertEqual([], fixture.tools())

    def test_invalid_java_homes_are_rejected(self):
        """A non-JDK-21 home, a missing home or a relative home must all fail before building."""
        fixture = self.fixture()
        result = fixture.install(**{"java-home": str(fixture.root / "missing-jdk")})
        self.assert_failed_without_install(fixture, result)
        self.assertIn("bin/java", result.stderr)

        jdk17 = self.fixture(java_mode="jdk17")
        result = jdk17.install()
        self.assert_failed_without_install(jdk17, result)
        self.assertIn("JDK 21 is required", result.stderr)

        relative = self.fixture()
        result = relative.install(**{"java-home": "relative-jdk"})
        self.assert_failed_without_install(relative, result)
        self.assertIn("absolute path", result.stderr)
        self.assertNotIn("mvn", relative.tools())

    def test_unknown_duplicate_and_valueless_options_fail(self):
        """Unknown, repeated and value-less options fail closed instead of guessing."""
        fixture = self.fixture()
        unknown = fixture.install("--token", "value")
        self.assert_failed_without_install(fixture, unknown)
        self.assertIn("unknown option:", unknown.stderr)

        duplicate = fixture.install("--note", "second", **{"note": "first"})
        self.assert_failed_without_install(fixture, duplicate)
        self.assertIn("duplicate option: --note", duplicate.stderr)

        valueless = fixture.run("install", "--gateway-uri")
        self.assert_failed_without_install(fixture, valueless)
        self.assertIn("missing value for --gateway-uri", valueless.stderr)

        dashed = fixture.install(**{"note": "--not-a-note"})
        self.assert_failed_without_install(fixture, dashed)
        self.assertIn("missing value for --note", dashed.stderr)

        self.assertEqual([], fixture.tools())

    def test_control_characters_and_relative_paths_are_rejected(self):
        """Newlines/control characters and relative data-dir/note inputs must not reach the unit."""
        fixture = self.fixture()
        newline_note = fixture.install(**{"note": "line1\nline2"})
        self.assert_failed_without_install(fixture, newline_note)
        self.assertIn("control characters", newline_note.stderr)

        long_note = fixture.install(**{"note": "x" * 513})
        self.assert_failed_without_install(fixture, long_note)
        self.assertIn("512", long_note.stderr)

        padded_note = fixture.install(**{"note": " padded"})
        self.assert_failed_without_install(fixture, padded_note)
        self.assertIn("surrounding whitespace", padded_note.stderr)

        relative_data_dir = fixture.install(**{"data-dir": "data"})
        self.assert_failed_without_install(fixture, relative_data_dir)
        self.assertIn("--data-dir must be an absolute path", relative_data_dir.stderr)

    def test_home_must_be_set_absolute_and_free_of_control_characters(self):
        """HOME feeds every managed path, so it must be absolute, present and free of controls."""
        fixture = self.fixture()
        missing = fixture.install(env={"HOME": ""})
        self.assertNotEqual(0, missing.returncode)
        self.assertIn("HOME must be set", missing.stderr)

        relative = fixture.install(env={"HOME": "relative-home"})
        self.assertNotEqual(0, relative.returncode)
        self.assertIn("HOME must be an absolute path", relative.stderr)

        controlled = fixture.install(env={"HOME": f"{fixture.home}\tpad"})
        self.assertNotEqual(0, controlled.returncode)
        self.assertIn("HOME must not contain control characters", controlled.stderr)

        self.assertFalse(fixture.unit.exists())
        self.assertFalse(fixture.jar.exists())
        self.assertEqual([], fixture.tools())

    def test_verification_windows_must_be_nonnegative_integers(self):
        """The verification windows feed arithmetic, so non-integers must fail before building."""
        for name, value in (
            ("DAEMON_VERIFY_TIMEOUT_SECONDS", "abc"),
            ("DAEMON_VERIFY_TIMEOUT_SECONDS", "-1"),
            ("DAEMON_VERIFY_TIMEOUT_SECONDS", "1.5"),
            ("DAEMON_VERIFY_STABLE_SECONDS", "abc"),
            ("DAEMON_VERIFY_STABLE_SECONDS", "-3"),
            ("DAEMON_VERIFY_STABLE_SECONDS", "3s"),
        ):
            with self.subTest(setting=name, value=value):
                fixture = self.fixture()
                result = fixture.install(env={name: value})
                self.assert_failed_without_install(fixture, result)
                self.assertIn(f"{name} must be a non-negative decimal integer", result.stderr)
                self.assertNotIn("mvn", fixture.tools())

    def test_jdk_home_must_provide_javac(self):
        """A JRE-only home fails the build; bin/javac is required while bin/javap is not."""
        no_javac = self.fixture(jdk_tools=("java", "javap"))
        result = no_javac.install()
        self.assert_failed_without_install(no_javac, result)
        self.assertIn("bin/javac", result.stderr)
        self.assertNotIn("mvn", no_javac.tools())

        no_javap = self.fixture(jdk_tools=("java", "javac"))
        result = no_javap.install()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertTrue(no_javap.unit.is_file())

    def test_lsp_config_validation(self):
        """--lsp-config must be an absolute path to an existing readable file with no ~ or placeholders."""
        fixture = self.fixture()

        relative = fixture.install(**{"lsp-config": "relative/config.json"})
        self.assert_failed_without_install(fixture, relative)
        self.assertIn("--lsp-config must be an absolute path", relative.stderr)

        tilde = fixture.install(**{"lsp-config": "~/config.json"})
        self.assert_failed_without_install(fixture, tilde)
        self.assertIn("~", tilde.stderr)

        env_var = fixture.install(**{"lsp-config": "/path/$FOO/config.json"})
        self.assert_failed_without_install(fixture, env_var)
        self.assertIn("environment-variable placeholders", env_var.stderr)

        missing = fixture.install(**{"lsp-config": "/nonexistent/config.json"})
        self.assert_failed_without_install(fixture, missing)
        self.assertIn("existing readable regular file", missing.stderr)

        bridge = fixture.install(**{"lsp-bridge-command": "cmd"})
        self.assert_failed_without_install(fixture, bridge)
        self.assertIn("unknown option:", bridge.stderr)

        javap = fixture.install(**{"javap-executable": "/bin/javap"})
        self.assert_failed_without_install(fixture, javap)
        self.assertIn("unknown option:", javap.stderr)

    def test_java_home_environment_values_are_validated(self):
        """Env-provided JDK homes are validated like the explicit option, not silently skipped."""
        controlled = self.fixture()
        result = controlled.install(
            env={"JAVA_HOME_21": f"{controlled.jdk}\tpad"}, **{"java-home": None}
        )
        self.assert_failed_without_install(controlled, result)
        self.assertIn("control characters", result.stderr)

        relative = self.fixture()
        result = relative.install(
            env={"JAVA_HOME_21": "relative-jdk", "JAVA_HOME": ""}, **{"java-home": None}
        )
        self.assert_failed_without_install(relative, result)
        self.assertIn("absolute path", result.stderr)
        self.assertNotIn("mvn", relative.tools())

    def test_unusable_systemd_fails_before_any_write(self):
        """A host without a usable `systemctl --user` must not receive a JAR or a unit."""
        fixture = self.fixture(systemctl_mode="unavailable")
        result = fixture.install()
        self.assert_failed_without_install(fixture, result)
        self.assertIn("systemctl --user", result.stderr)
        self.assertIn("--user --no-pager show-environment", f"{fixture.records()}")
        self.assertNotIn("mvn", fixture.tools())


class TestDaemonInstallFailureSafety(DaemonInstallTestCase):
    """A failed install/upgrade must never degrade an installation that already works."""

    def test_failed_build_keeps_the_existing_installation(self):
        """Maven failure happens before the managed paths are touched."""
        fixture = self.fixture(jar_body="version-one")
        first = fixture.install()
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        unit_text = fixture.unit_text()
        jar_text = fixture.jar.read_text(encoding="utf-8")

        failure = fixture.install(env={"FAKE_MVN_MODE": "fail"})
        self.assertNotEqual(0, failure.returncode)
        self.assertIn("fake maven failure", failure.stderr)
        self.assert_fixture_untouched(fixture, unit_text, jar_text)

    def test_broken_build_output_keeps_the_existing_installation(self):
        """A built JAR that cannot run `--version` must be rejected before replacement."""
        fixture = self.fixture(jar_body="version-one")
        first = fixture.install()
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        unit_text = fixture.unit_text()
        jar_text = fixture.jar.read_text(encoding="utf-8")

        for mode in ("missing-jar", "empty-jar"):
            with self.subTest(mode=mode):
                empty = fixture.install(env={"FAKE_MVN_MODE": mode})
                self.assertNotEqual(0, empty.returncode)
                self.assertIn("not found or empty", empty.stderr)
                self.assert_fixture_untouched(fixture, unit_text, jar_text)

        for mode, expected in (
            ("version-fail", "not executable"),
            ("version-unexpected", "unexpected daemon --version output"),
        ):
            with self.subTest(mode=mode):
                failure = fixture.install(env={"FAKE_JAVA_MODE": mode})
                self.assertNotEqual(0, failure.returncode)
                self.assertIn(expected, failure.stderr)
                self.assert_fixture_untouched(fixture, unit_text, jar_text)

    def test_restart_failure_reports_failure_after_installing_valid_artifacts(self):
        """A restart failure is reported (no false success) once validated artifacts are in place."""
        fixture = self.fixture(systemctl_mode="restart-fail")
        result = fixture.install()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("fake restart failure", result.stderr)
        # 产物本身是校验过的：失败不会伪装成成功，也不会删除刚安装的单元。
        self.assertTrue(fixture.unit.is_file())
        self.assertTrue(fixture.jar.is_file())

    def test_inactive_service_fails_the_install(self):
        """`restart` returning 0 is not enough; the service must still be active afterwards."""
        fixture = self.fixture(systemctl_mode="inactive")
        result = fixture.install()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("is not active after restart", result.stderr)
        self.assertIn(f"--user is-active --quiet {SERVICE_NAME}", f"{fixture.records()}")

    def test_flapping_service_is_rejected(self):
        """One transient active result must not pass: the stability window has to hold."""
        fixture = self.fixture()
        result = fixture.install(
            env={
                "FAKE_SYSTEMCTL_MODE": "flapping",
                "FAKE_SYSTEMCTL_COUNTER": str(fixture.root / "systemctl-is-active-counter"),
                "DAEMON_VERIFY_STABLE_SECONDS": "1",
            }
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("did not stay active for 1s", result.stderr)
        # 可执行性已由稳定窗口判定，产物本身仍在（失败原因不是安装路径被破坏）。
        self.assertTrue(fixture.unit.is_file())


class TestDaemonUpgrade(DaemonInstallTestCase):
    """`upgrade` rebuilds and replaces only the JAR, reusing the stored configuration."""

    def test_upgrade_requires_a_managed_unit(self):
        """Upgrading a host without a managed unit (or with a foreign one) must fail closed."""
        fixture = self.fixture()
        missing = fixture.run("upgrade")
        self.assertNotEqual(0, missing.returncode)
        self.assertIn("no managed installation found", missing.stderr)

        unmanaged = self.fixture(with_unit="[Unit]\nDescription=hand written\n")
        result = unmanaged.run("upgrade")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("unmanaged unit", result.stderr)
        self.assertEqual("[Unit]\nDescription=hand written\n", unmanaged.unit_text())
        self.assertNotIn("mvn", unmanaged.tools())

    def test_upgrade_summary_reports_only_known_values(self):
        """Upgrade stores no configuration, so its summary must not print an empty data dir."""
        fixture = self.fixture()
        first = fixture.install()
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        self.assertIn(f"Data dir:  {fixture.home / '.kk-studio'}", first.stdout)

        result = fixture.run("upgrade")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn(f"Service:   {SERVICE_NAME}", result.stdout)
        self.assertIn(f"Unit:      {fixture.unit}", result.stdout)
        self.assertNotIn("Data dir:", result.stdout)

    def test_upgrade_replaces_only_the_jar(self):
        """Configuration is not repeated: the unit stays byte-identical, the JAR is replaced."""
        fixture = self.fixture(jar_body="version-one")
        first = fixture.install(**{"note": "upgrade-note"})
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        unit_text = fixture.unit_text()
        fixture.record.write_text("", encoding="utf-8")

        result = fixture.run("upgrade", env={"FAKE_JAR_BODY": "version-two"})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(unit_text, fixture.unit_text())
        self.assertIn("version-two", fixture.jar.read_text(encoding="utf-8"))

        invocations = fixture.service_invocations()
        self.assertEqual(f"--user restart {SERVICE_NAME}", invocations[1])
        self.assertIn("--user daemon-reload", invocations)
        self.assertIn(f"--user is-active --quiet {SERVICE_NAME}", invocations)
        # 升级不重新 enable：单元本身没有被改写。
        self.assertNotIn(f"--user enable {SERVICE_NAME}", invocations)
        # 升级不接受任何选项，避免复制安装配置。
        rejected = fixture.run("upgrade", "--note", "another")
        self.assertNotEqual(0, rejected.returncode)
        self.assertIn("upgrade accepts no options", rejected.stderr)

    def test_failed_upgrade_keeps_the_previous_jar(self):
        """A failed rebuild must leave the running installation untouched."""
        fixture = self.fixture(jar_body="version-one")
        first = fixture.install()
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        unit_text = fixture.unit_text()
        jar_text = fixture.jar.read_text(encoding="utf-8")

        failure = fixture.run("upgrade", env={"FAKE_MVN_MODE": "fail"})
        self.assertNotEqual(0, failure.returncode)
        self.assert_fixture_untouched(fixture, unit_text, jar_text)
        self.assertTrue(fixture.token.is_file())


class TestDaemonUninstall(DaemonInstallTestCase):
    """`uninstall` removes only what this script installed and preserves durable host data."""

    def test_uninstall_removes_managed_artifacts_and_preserves_data(self):
        """Unit and JAR go away; the token file and the daemon data directory stay."""
        fixture = self.fixture()
        installed = fixture.install()
        self.assertEqual(0, installed.returncode, installed.stdout + installed.stderr)
        data_dir = fixture.home / ".kk-studio"
        (data_dir / "resources" / "text").mkdir(parents=True)
        (data_dir / "daemon.lock").write_text("", encoding="utf-8")
        (data_dir / "resources" / "text" / "command.log").write_text("output", encoding="utf-8")
        fixture.record.write_text("", encoding="utf-8")

        result = fixture.run("uninstall")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse(fixture.unit.exists())
        self.assertFalse(fixture.jar.exists())
        self.assertTrue(fixture.token.is_file())
        self.assertEqual(TOKEN_VALUE + "\n", fixture.token.read_text(encoding="utf-8"))
        self.assertTrue((data_dir / "daemon.lock").is_file())
        self.assertTrue((data_dir / "resources" / "text" / "command.log").is_file())
        self.assertIn("Preserved", result.stdout)
        self.assertNotIn(TOKEN_VALUE, result.stdout + result.stderr)

        invocations = fixture.service_invocations()
        self.assertEqual(f"--user disable --now {SERVICE_NAME}", invocations[0])
        self.assertIn("--user daemon-reload", invocations)
        self.assertIn(f"--user reset-failed {SERVICE_NAME}", invocations)

    def test_uninstall_rejects_unmanaged_or_missing_units(self):
        """Only a unit carrying the managed marker may be deleted."""
        missing = self.fixture()
        result = missing.run("uninstall")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("no managed installation found", result.stderr)

        unmanaged = self.fixture(with_unit="[Unit]\nDescription=hand written\n")
        result = unmanaged.run("uninstall")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("unmanaged unit", result.stderr)
        self.assertTrue(unmanaged.unit.is_file())
        self.assertNotIn("disable", f"{unmanaged.records()}")

    def test_uninstall_stops_before_removing_artifacts(self):
        """If the service cannot be stopped, nothing may be deleted."""
        fixture = self.fixture(systemctl_mode="disable-fail")
        installed = fixture.install()
        self.assertEqual(0, installed.returncode, installed.stdout + installed.stderr)

        result = fixture.run("uninstall", env={"FAKE_SYSTEMCTL_MODE": "disable-fail"})
        self.assertNotEqual(0, result.returncode)
        self.assertIn("nothing was removed", result.stderr)
        self.assertTrue(fixture.unit.is_file())
        self.assertTrue(fixture.jar.is_file())


class TestDaemonStatus(DaemonInstallTestCase):
    """`status` is read-only, non-interactive and encodes installed/active state in its exit code."""

    def test_status_reports_active_service_with_journal_tail(self):
        """An active installation exits 0 and shows both service state and journal lines."""
        fixture = self.fixture()
        installed = fixture.install()
        self.assertEqual(0, installed.returncode, installed.stdout + installed.stderr)
        fixture.record.write_text("", encoding="utf-8")

        result = fixture.run("status")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("fake journal line", result.stdout)
        invocations = [payload for _, payload in fixture.records()]
        self.assertIn(f"--user --no-pager status {SERVICE_NAME}", invocations)
        self.assertIn(f"--user --no-pager -n 20 -u {SERVICE_NAME}", invocations)

    def test_status_returns_nonzero_when_not_installed(self):
        """An absent unit is reported as not installed and must not query the service."""
        fixture = self.fixture()
        result = fixture.run("status")
        self.assertEqual(1, result.returncode)
        self.assertIn("not installed", result.stderr)
        # 只做 systemd 可用性探测：未安装时既不查服务状态也不读 journal。
        self.assertEqual([("systemctl", "--user --no-pager show-environment")], fixture.records())

    def test_status_returns_three_when_inactive(self):
        """An installed but inactive service is distinguishable from a missing installation."""
        fixture = self.fixture()
        installed = fixture.install()
        self.assertEqual(0, installed.returncode, installed.stdout + installed.stderr)

        result = fixture.run("status", env={"FAKE_SYSTEMCTL_MODE": "inactive"})
        self.assertEqual(3, result.returncode)
        self.assertIn("is not active", result.stderr)
        self.assertIn("fake journal line", result.stdout)

    def test_status_accepts_no_options(self):
        """status carries no configuration of its own, so extra arguments fail closed."""
        fixture = self.fixture()
        result = fixture.run("status", "--gateway-uri", GATEWAY_URI)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("status accepts no options", result.stderr)
        self.assertEqual([], fixture.records())

    def test_status_rejects_unmanaged_unit(self):
        """A foreign unit is not this script's installation and must not be reported as one."""
        fixture = self.fixture(with_unit="[Unit]\nDescription=hand written\n")
        result = fixture.run("status")
        self.assertEqual(1, result.returncode)
        self.assertIn("unmanaged unit", result.stderr)
        self.assertNotIn("--no-pager status", f"{fixture.records()}")


if __name__ == "__main__":
    unittest.main()
