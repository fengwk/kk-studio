"""macOS LaunchAgent contracts for ``scripts/daemon/install.sh``.

These cases run on Linux. ``uname``, ``launchctl`` and ``plutil`` are recorder fakes, so the
assertions cover the generated plist and the launchd lifecycle without a Darwin host. ``plutil``
parses the generated XML with the same rules Python's ``plistlib`` uses later, which is what
makes "invalid plist is not installed" testable before any real ``plutil`` is available.
"""

import os
from pathlib import Path
import plistlib
import stat
import subprocess
import tempfile
import unittest

# 测试以脚本方式从 ``scripts/daemon/tests`` 运行，因此同目录模块可直接导入。
from test_daemon_install import (
    GATEWAY_URI,
    INSTALL_SCRIPT,
    NOTE,
    TOKEN_VALUE,
    Fixture,
    write_executable,
)

LAUNCHD_LABEL = "fun.fengwk.kkstudio.environment-daemon"
PLIST_MARKER = "<!-- Managed by scripts/daemon/install.sh -->"
# A value that must survive XML escaping and come back unchanged through plistlib.
SPECIAL_NOTE = 'A & B <tag> "quoted" \'apos\' 100%'


def darwin_environment(fixture):
    """Point ``uname`` at Darwin while leaving the rest of the fixture toolchain in place."""
    return {"FAKE_UNAME": "Darwin"}


class DarwinFixture(Fixture):
    """The shared Linux fixture plus fake launchd tools and the macOS managed paths."""

    def __init__(self, root, *, launchctl_mode="ok", plutil_mode="ok", **kwargs):
        self.launchctl_mode = launchctl_mode
        self.plutil_mode = plutil_mode
        super().__init__(root, **kwargs)
        self.plist = self.home / "Library" / "LaunchAgents" / f"{LAUNCHD_LABEL}.plist"
        self.stdout_log = (
            self.home / "Library" / "Logs" / "kk-studio" / "environment-daemon.stdout.log"
        )
        self.stderr_log = (
            self.home / "Library" / "Logs" / "kk-studio" / "environment-daemon.stderr.log"
        )
        self.domain = f"gui/{os.getuid()}"
        self.target = f"{self.domain}/{LAUNCHD_LABEL}"
        self._write_darwin_tools()

    def _write_darwin_tools(self):
        # ``uname -s`` 是宿主探测的唯一入口；夹具默认仍是 Linux，Darwin 只由环境变量打开。
        write_executable(
            self.bin / "uname",
            "#!/usr/bin/env bash\n"
            'if [ "$1" = "-s" ]; then\n'
            '  printf \'%s\\n\' "${FAKE_UNAME:-Linux}"\n'
            "  exit 0\n"
            "fi\n"
            "exit 1\n",
        )
        write_executable(
            self.bin / "id",
            "#!/usr/bin/env bash\n"
            'if [ "$1" = "-u" ]; then\n'
            '  printf \'%s\\n\' "${FAKE_UID:?}"\n'
            "  exit 0\n"
            "fi\n"
            "exit 1\n",
        )
        write_executable(
            self.bin / "launchctl",
            "#!/usr/bin/env bash\n"
            # 记录走 stderr 的副本文件，避免 `kickstart -p` 的 PID 和记录行混在同一个 stdout。
            'printf \'launchctl|%s\\n\' "$*" >> "$FAKE_RECORD"\n'
            'mode=${FAKE_LAUNCHCTL_MODE:-ok}\n'
            'if [ "$mode" = "no-domain" ] && [ "$1" = "print" ] && [ "$2" = "$FAKE_DOMAIN" ]; then\n'
            '  echo "Could not find domain" >&2\n'
            "  exit 1\n"
            "fi\n"
            'loaded_flag=${FAKE_LOADED_FLAG:?}\n'
            'case "$1" in\n'
            "  print)\n"
            '    if [ "$2" = "$FAKE_DOMAIN" ]; then exit 0; fi\n'
            '    if [ -f "${FAKE_BOOTOUT_STATE:-}" ] && [ "$(cat "$FAKE_BOOTOUT_STATE")" = "delayed" ]; then\n'
            '      rm -f "$FAKE_BOOTOUT_STATE" "$loaded_flag"\n'
            "      exit 113\n"
            "    fi\n"
            '    if [ -f "$loaded_flag" ]; then\n'
            '      echo "state = running"\n'
            "      exit 0\n"
            "    fi\n"
            "    exit 113 ;;\n"
            "  bootout)\n"
            '    if [ "$mode" = "bootout-fail" ]; then\n'
            '      echo "fake bootout failure" >&2\n'
            "      exit 1\n"
            "    fi\n"
            '    if [ "$mode" = "bootout-stuck" ]; then\n'
            "      exit 0\n"
            "    fi\n"
            '    if [ "$mode" = "bootout-delayed" ]; then\n'
            # 返回成功但先保持 loaded。下一次 print 才卸载，用来证明替换不会和 teardown 竞态。
            '      printf delayed > "$FAKE_BOOTOUT_STATE"\n'
            "      exit 0\n"
            "    fi\n"
            '    rm -f "$loaded_flag"\n'
            "    exit 0 ;;\n"
            "  bootstrap)\n"
            '    if [ "$mode" = "bootstrap-fail" ]; then\n'
            '      echo "fake bootstrap failure" >&2\n'
            "      exit 1\n"
            "    fi\n"
            '    : > "$loaded_flag"\n'
            "    exit 0 ;;\n"
            "  kickstart)\n"
            '    if [ "$2" = "-k" ]; then\n'
            '      if [ "$mode" = "kickstart-fail" ]; then\n'
            '        echo "fake kickstart failure" >&2\n'
            "        exit 1\n"
            "      fi\n"
            '      : > "$loaded_flag"\n'
            "      exit 0\n"
            "    fi\n"
            '    if [ "$2" = "-p" ]; then\n'
            '      if [ "$mode" = "pid-missing" ]; then exit 0; fi\n'
            '      if [ "$mode" = "pid-malformed" ]; then printf \'not-a-pid\\n\'; exit 0; fi\n'
            '      if [ "$mode" = "pid-dead" ]; then printf \'999999\\n\'; exit 0; fi\n'
            '      if [ "$mode" = "pid-flapping" ]; then\n'
            # 后台进程只活到第一次 kickstart：返回时 PID 仍存在，稳定窗口内它会退出。
            '        if [ ! -f "$FAKE_FLAP_PID" ]; then\n'
            "          bash -c 'sleep 0.2' >/dev/null 2>&1 & echo $! > \"$FAKE_FLAP_PID\"\n"
            "        fi\n"
            '        cat "$FAKE_FLAP_PID"\n'
            "        exit 0\n"
            "      fi\n"
            # launchctl 自己在打印后就退出，因此正常路径报一个由测试保持存活的进程。
            '      printf \'%s\\n\' "${FAKE_LIVE_PID:?}"\n'
            "      exit 0\n"
            "    fi\n"
            "    exit 1 ;;\n"
            "esac\n"
            "exit 0\n",
        )
        # lint 必须在替换前失败：真实解析由 Python 完成，失败模式直接拒绝临时文件。
        write_executable(
            self.bin / "plutil",
            "#!/usr/bin/env python3\n"
            "import os\n"
            "import plistlib\n"
            "import sys\n"
            "\n"
            "record = os.environ['FAKE_RECORD']\n"
            "with open(record, 'a', encoding='utf-8') as handle:\n"
            "    handle.write('plutil|' + ' '.join(sys.argv[1:]) + '\\n')\n"
            "if sys.argv[1:] != ['-lint', sys.argv[2]]:\n"
            "    sys.exit(2)\n"
            "if os.environ.get('FAKE_PLUTIL_MODE') == 'fail':\n"
            "    sys.stderr.write('fake plist is invalid\\n')\n"
            "    sys.exit(1)\n"
            "with open(sys.argv[2], 'rb') as handle:\n"
            "    plistlib.load(handle)\n"
            "sys.exit(0)\n",
        )

    def environment(self, **overrides):
        environment = super().environment(**overrides)
        environment.update(
            {
                "FAKE_UNAME": "Darwin",
                "FAKE_UID": str(os.getuid()),
                "FAKE_DOMAIN": self.domain,
                "FAKE_LAUNCHCTL_MODE": self.launchctl_mode,
                "FAKE_PLUTIL_MODE": self.plutil_mode,
                "FAKE_LOADED_FLAG": str(self.root / "launchd-loaded"),
                "FAKE_BOOTOUT_STATE": str(self.root / "bootout-state"),
                "FAKE_LIVE_PID": str(os.getpid()),
            }
        )
        environment.update(overrides)
        return environment

    def launchctl_invocations(self):
        """Recorded launchctl argv, without read-only ``print`` probes.

        ``print`` only answers whether the GUI domain or the job is loaded. Bootstrap, bootout
        and kickstart are the operations these assertions order.
        """
        return [
            payload
            for tool, payload in self.records()
            if tool == "launchctl" and not payload.startswith("print ")
        ]

    def plist_object(self):
        """Parse the installed plist the way launchd would, after XML unescaping."""
        return plistlib.loads(self.plist.read_bytes())


class DarwinInstallTestCase(unittest.TestCase):
    """Each case gets a throwaway HOME whose fake ``uname`` reports Darwin."""

    def fixture(self, **kwargs):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        return DarwinFixture(temporary.name, **kwargs)


class TestMacosInstallContract(DarwinInstallTestCase):
    """The generated LaunchAgent is the whole macOS configuration surface."""

    def test_install_writes_the_shared_jar_and_a_direct_launch_agent(self):
        """Install must create the shared Unix JAR and a plist that execs java directly."""
        fixture = self.fixture(jar_body="shaded-jar-payload")
        result = fixture.install(
            env=darwin_environment(fixture),
            **{"note": SPECIAL_NOTE, "lsp-bridge-command": "lsp-bridge --stdio"},
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

        self.assertTrue(fixture.jar.is_file())
        self.assertEqual(0o644, stat.S_IMODE(fixture.jar.stat().st_mode))
        self.assertIn("shaded-jar-payload", fixture.jar.read_text(encoding="utf-8"))
        self.assertTrue(fixture.plist.is_file())
        self.assertEqual(0o644, stat.S_IMODE(fixture.plist.stat().st_mode))
        # Linux unit 不得被 Darwin 安装顺手创建。
        self.assertFalse(fixture.unit.exists())
        self.assertFalse((fixture.home / ".local" / "bin").exists())

        lines = fixture.plist.read_text(encoding="utf-8").splitlines()
        self.assertTrue(lines[0].startswith("<?xml"))
        self.assertEqual(PLIST_MARKER, lines[1])

        plist = fixture.plist_object()
        self.assertEqual(LAUNCHD_LABEL, plist["Label"])
        self.assertEqual(
            [
                str(fixture.jdk / "bin" / "java"),
                "-jar",
                str(fixture.jar),
                "--gateway-uri",
                GATEWAY_URI,
                "--registration-token-file",
                str(fixture.token),
                "--data-dir",
                str(fixture.home / ".kk-studio"),
                "--note",
                SPECIAL_NOTE,
                "--bash-executable",
                "/usr/bin/bash",
                "--lsp-bridge-command",
                "lsp-bridge --stdio",
                "--javap-executable",
                str(fixture.jdk / "bin" / "javap"),
            ],
            plist["ProgramArguments"],
        )
        self.assertIs(True, plist["RunAtLoad"])
        self.assertEqual({"SuccessfulExit": False}, plist["KeepAlive"])
        self.assertEqual(10, plist["ThrottleInterval"])
        self.assertEqual(63, plist["Umask"])
        self.assertEqual(str(fixture.home), plist["WorkingDirectory"])
        self.assertEqual(str(fixture.stdout_log), plist["StandardOutPath"])
        self.assertEqual(str(fixture.stderr_log), plist["StandardErrorPath"])
        # 不经过 shell，也不把 token 内容或环境变量配置写进 plist。
        rendered = fixture.plist.read_text(encoding="utf-8")
        self.assertNotIn("/bin/sh", rendered)
        self.assertNotIn("EnvironmentVariables", rendered)
        self.assertNotIn(TOKEN_VALUE, rendered)
        self.assertIn(str(fixture.token), rendered)
        self.assertNotIn(TOKEN_VALUE, result.stdout + result.stderr)

    def test_install_bootstraps_the_gui_domain_and_proves_the_process(self):
        """A fresh install loads the plist, then proves the reported PID with ``kill -0``."""
        fixture = self.fixture()
        result = fixture.install(env=darwin_environment(fixture))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

        invocations = fixture.launchctl_invocations()
        self.assertIn(f"bootstrap {fixture.domain} {fixture.plist}", invocations)
        self.assertTrue(any(item.startswith("kickstart -p ") for item in invocations))
        self.assertNotIn("enable", " ".join(invocations))
        # 首次安装没有已加载服务，因此不得先 bootout。
        self.assertNotIn("bootout", " ".join(invocations))
        self.assertLess(
            invocations.index(f"bootstrap {fixture.domain} {fixture.plist}"),
            next(index for index, item in enumerate(invocations) if item.startswith("kickstart -p ")),
        )

    def test_reinstall_boots_out_before_replacing_the_plist(self):
        """Reinstall must unload the managed job before the plist is replaced, then bootstrap."""
        fixture = self.fixture()
        first = fixture.install(env=darwin_environment(fixture), **{"note": "first-note"})
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        fixture.record.write_text("", encoding="utf-8")

        second = fixture.install(env=darwin_environment(fixture), **{"note": "second-note"})
        self.assertEqual(0, second.returncode, second.stdout + second.stderr)
        self.assertEqual("second-note", fixture.plist_object()["ProgramArguments"][
            fixture.plist_object()["ProgramArguments"].index("--note") + 1
        ])

        invocations = fixture.launchctl_invocations()
        self.assertEqual(f"bootout {fixture.target}", invocations[0])
        bootstrap = invocations.index(f"bootstrap {fixture.domain} {fixture.plist}")
        self.assertLess(0, bootstrap)
        # 构建和 lint 必须先于 bootout：校验失败时旧任务还加载着。域探测的 print 不算。
        tools = fixture.tools()
        plutil_at = tools.index("plutil")
        bootout_at = fixture.records().index(("launchctl", f"bootout {fixture.target}"))
        self.assertLess(tools.index("mvn"), plutil_at)
        self.assertLess(plutil_at, bootout_at)

    def test_reinstall_refuses_a_foreign_plist(self):
        """A plist without the ownership marker must not be replaced or booted out."""
        fixture = self.fixture()
        fixture.plist.parent.mkdir(parents=True, exist_ok=True)
        fixture.plist.write_text(
            '<?xml version="1.0" encoding="UTF-8"?>\n<plist version="1.0"><dict></dict></plist>\n',
            encoding="utf-8",
        )
        before = fixture.plist.read_text(encoding="utf-8")

        result = fixture.install(env=darwin_environment(fixture))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("unmanaged plist", result.stderr)
        self.assertEqual(before, fixture.plist.read_text(encoding="utf-8"))
        self.assertFalse(fixture.jar.exists())
        self.assertNotIn("bootout", " ".join(fixture.launchctl_invocations()))
        self.assertNotIn("mvn", fixture.tools())

    def test_invalid_generated_plist_does_not_replace_the_existing_one(self):
        """``plutil -lint`` failure must keep the previous managed plist byte-identical."""
        fixture = self.fixture(jar_body="version-one")
        first = fixture.install(env=darwin_environment(fixture), **{"note": "kept-note"})
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        before = fixture.plist.read_bytes()
        jar_before = fixture.jar.read_bytes()

        fixture.record.write_text("", encoding="utf-8")
        result = fixture.install(env={"FAKE_PLUTIL_MODE": "fail", **darwin_environment(fixture)})
        self.assertNotEqual(0, result.returncode)
        self.assertIn("failed validation", result.stderr)
        self.assertEqual(before, fixture.plist.read_bytes())
        # lint 失败发生在 bootout 之前：旧 JAR 与已加载任务都保持原样，且不留暂存文件。
        self.assertEqual(jar_before, fixture.jar.read_bytes())
        self.assertTrue((fixture.root / "launchd-loaded").is_file())
        self.assertNotIn("bootout", " ".join(fixture.launchctl_invocations()))
        self.assertNotIn("bootstrap", " ".join(fixture.launchctl_invocations()))
        self.assertEqual([], list(fixture.home.rglob("*.tmp.*")))

    def test_failed_rebuild_leaves_the_loaded_installation_untouched(self):
        """Maven or JAR verification failure must not bootout or rewrite the loaded install."""
        fixture = self.fixture(jar_body="version-one")
        first = fixture.install(env=darwin_environment(fixture), **{"note": "kept-note"})
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        before = fixture.plist.read_bytes()
        jar_before = fixture.jar.read_bytes()

        fixture.record.write_text("", encoding="utf-8")
        result = fixture.install(env={"FAKE_MVN_MODE": "fail", **darwin_environment(fixture)})
        self.assertNotEqual(0, result.returncode)
        self.assertIn("fake maven failure", result.stderr)
        self.assertEqual(before, fixture.plist.read_bytes())
        self.assertEqual(jar_before, fixture.jar.read_bytes())
        self.assertTrue((fixture.root / "launchd-loaded").is_file())
        self.assertNotIn("bootout", " ".join(fixture.launchctl_invocations()))
        self.assertEqual([], list(fixture.home.rglob("*.tmp.*")))

    def test_delayed_bootout_is_waited_out_before_bootstrap(self):
        """Bootstrap must wait until print reports unloaded, without parsing its text."""
        fixture = self.fixture(launchctl_mode="bootout-delayed")
        first = fixture.install(env={"FAKE_LAUNCHCTL_MODE": "ok"})
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        fixture.record.write_text("", encoding="utf-8")

        second = fixture.install(env={"FAKE_LAUNCHCTL_MODE": "bootout-delayed"})
        self.assertEqual(0, second.returncode, second.stdout + second.stderr)
        invocations = fixture.launchctl_invocations()
        self.assertEqual(
            [f"bootout {fixture.target}", f"bootstrap {fixture.domain} {fixture.plist}"],
            [item for item in invocations if item.startswith(("bootout ", "bootstrap "))],
        )
        records = fixture.records()
        bootout_at = records.index(("launchctl", f"bootout {fixture.target}"))
        unloaded = (
            "launchctl",
            f"print {fixture.target}",
        )
        self.assertIn(unloaded, records[bootout_at + 1 :])
        self.assertLess(
            records.index(unloaded, bootout_at + 1),
            records.index(("launchctl", f"bootstrap {fixture.domain} {fixture.plist}")),
        )

    def test_stuck_bootout_does_not_replace_the_loaded_installation(self):
        """A job that stays loaded after bootout must keep the previous plist and JAR."""
        fixture = self.fixture(launchctl_mode="bootout-stuck")
        first = fixture.install(
            env={"FAKE_LAUNCHCTL_MODE": "ok", "DAEMON_VERIFY_TIMEOUT_SECONDS": "0"}
        )
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        before = fixture.plist.read_bytes()
        jar_before = fixture.jar.read_bytes()
        fixture.record.write_text("", encoding="utf-8")

        result = fixture.install(
            env={
                "FAKE_LAUNCHCTL_MODE": "bootout-stuck",
                "DAEMON_VERIFY_TIMEOUT_SECONDS": "0",
            }
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("still loaded after bootout", result.stderr)
        self.assertEqual(before, fixture.plist.read_bytes())
        self.assertEqual(jar_before, fixture.jar.read_bytes())
        self.assertTrue((fixture.root / "launchd-loaded").is_file())
        self.assertNotIn("bootstrap", " ".join(fixture.launchctl_invocations()))
        self.assertEqual([], list(fixture.home.rglob("*.tmp.*")))

    def test_startup_pid_failures_do_not_report_success(self):
        """A missing, non-numeric or unstable PID must fail the install instead of succeeding."""
        expectations = {
            "pid-missing": "numeric process id",
            "pid-malformed": "numeric process id",
            "pid-dead": "is not running after start",
            "pid-flapping": "did not stay alive for 1s",
        }
        for mode, expected in expectations.items():
            with self.subTest(mode=mode):
                fixture = self.fixture(launchctl_mode=mode)
                result = fixture.install(
                    env={
                        "FAKE_FLAP_PID": str(fixture.root / "flap-pid"),
                        "DAEMON_VERIFY_TIMEOUT_SECONDS": "0",
                        "DAEMON_VERIFY_STABLE_SECONDS": "1",
                        **darwin_environment(fixture),
                    }
                )
                self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertIn(expected, result.stderr)
                self.assertTrue(fixture.plist.is_file())

    def test_unavailable_gui_domain_fails_before_any_write(self):
        """Without a GUI domain the installer must not create a plist or a JAR."""
        fixture = self.fixture(launchctl_mode="no-domain")
        result = fixture.install(env=darwin_environment(fixture))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("GUI domain", result.stderr)
        self.assertFalse(fixture.plist.exists())
        self.assertFalse(fixture.jar.exists())
        self.assertNotIn("mvn", fixture.tools())

    def test_maven_and_output_never_receive_the_token_value(self):
        """The token path may reach the plist; its contents must not reach Maven or output."""
        fixture = self.fixture()
        result = fixture.install(env=darwin_environment(fixture), **{"note": NOTE})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        maven_environment = fixture.mvn_environment.read_text(encoding="utf-8")
        for secret in (GATEWAY_URI, str(fixture.token), TOKEN_VALUE, NOTE):
            self.assertNotIn(secret, maven_environment)
        self.assertNotIn(TOKEN_VALUE, result.stdout + result.stderr)
        self.assertNotIn(TOKEN_VALUE, fixture.plist.read_text(encoding="utf-8"))
        self.assertNotIn(TOKEN_VALUE, fixture.record.read_text(encoding="utf-8"))


class TestMacosUpgradeStatusUninstall(DarwinInstallTestCase):
    """Upgrade, status and uninstall keep the plist stable and preserve host data."""

    def test_upgrade_replaces_only_the_jar_and_kickstarts(self):
        """Upgrade must not rewrite the plist and must restart with ``kickstart -k``."""
        fixture = self.fixture(jar_body="version-one")
        first = fixture.install(env=darwin_environment(fixture), **{"note": "upgrade-note"})
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        plist_before = fixture.plist.read_bytes()
        fixture.record.write_text("", encoding="utf-8")

        result = fixture.run("upgrade", env={"FAKE_JAR_BODY": "version-two"})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(plist_before, fixture.plist.read_bytes())
        self.assertIn("version-two", fixture.jar.read_text(encoding="utf-8"))
        invocations = fixture.launchctl_invocations()
        self.assertIn(f"kickstart -k {fixture.target}", invocations)
        self.assertNotIn("bootstrap", " ".join(invocations))
        self.assertNotIn("bootout", " ".join(invocations))
        self.assertNotIn("enable", " ".join(invocations))
        self.assertNotIn("Data dir:", result.stdout)

    def test_status_is_read_only_and_uses_distinct_exit_codes(self):
        """Status prints launchd state and existing logs, and never calls kickstart."""
        missing = self.fixture()
        absent = missing.run("status")
        self.assertEqual(1, absent.returncode)
        self.assertIn("not installed", absent.stderr)
        self.assertEqual([("launchctl", f"print {missing.domain}")], missing.records())

        foreign = self.fixture()
        foreign.plist.parent.mkdir(parents=True, exist_ok=True)
        foreign.plist.write_text("foreign\n", encoding="utf-8")
        rejected = foreign.run("status")
        self.assertEqual(1, rejected.returncode)
        self.assertIn("unmanaged plist", rejected.stderr)
        self.assertNotIn("kickstart", " ".join(foreign.launchctl_invocations()))

        installed = self.fixture()
        created = installed.install(env=darwin_environment(installed))
        self.assertEqual(0, created.returncode, created.stdout + created.stderr)
        installed.stdout_log.parent.mkdir(parents=True, exist_ok=True)
        installed.stdout_log.write_text("stdout-line\n", encoding="utf-8")
        installed.stderr_log.write_text("stderr-line\n", encoding="utf-8")
        installed.record.write_text("", encoding="utf-8")

        active = installed.run("status")
        self.assertEqual(0, active.returncode, active.stdout + active.stderr)
        self.assertIn("stdout-line", active.stdout)
        self.assertIn("stderr-line", active.stdout)
        self.assertNotIn("kickstart", " ".join(installed.launchctl_invocations()))
        self.assertNotIn("bootout", " ".join(installed.launchctl_invocations()))
        self.assertNotIn("bootstrap", " ".join(installed.launchctl_invocations()))

        # 卸载加载标记后，已安装但未加载必须是 3，并且仍然只读。
        (installed.root / "launchd-loaded").unlink()
        installed.record.write_text("", encoding="utf-8")
        inactive = installed.run("status")
        self.assertEqual(3, inactive.returncode)
        self.assertIn("not loaded", inactive.stderr)
        self.assertNotIn("kickstart", " ".join(installed.launchctl_invocations()))

    def test_uninstall_preserves_token_data_and_logs(self):
        """Uninstall removes the plist and JAR only after bootout, and keeps durable host files."""
        fixture = self.fixture()
        created = fixture.install(env=darwin_environment(fixture))
        self.assertEqual(0, created.returncode, created.stdout + created.stderr)
        data_dir = fixture.home / ".kk-studio"
        (data_dir / "resources").mkdir(parents=True)
        (data_dir / "resources" / "command.log").write_text("output", encoding="utf-8")
        fixture.stdout_log.parent.mkdir(parents=True, exist_ok=True)
        fixture.stdout_log.write_text("kept-stdout\n", encoding="utf-8")
        fixture.stderr_log.write_text("kept-stderr\n", encoding="utf-8")
        fixture.record.write_text("", encoding="utf-8")

        result = fixture.run("uninstall")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse(fixture.plist.exists())
        self.assertFalse(fixture.jar.exists())
        self.assertEqual(TOKEN_VALUE + "\n", fixture.token.read_text(encoding="utf-8"))
        self.assertTrue((data_dir / "resources" / "command.log").is_file())
        self.assertEqual("kept-stdout\n", fixture.stdout_log.read_text(encoding="utf-8"))
        self.assertEqual("kept-stderr\n", fixture.stderr_log.read_text(encoding="utf-8"))
        self.assertNotIn(TOKEN_VALUE, result.stdout + result.stderr)
        self.assertEqual(f"bootout {fixture.target}", fixture.launchctl_invocations()[0])

    def test_uninstall_removes_nothing_when_bootout_fails(self):
        """A failed bootout must leave both the managed plist and the JAR in place."""
        fixture = self.fixture(launchctl_mode="bootout-fail")
        created = fixture.install(env={"FAKE_LAUNCHCTL_MODE": "ok"})
        self.assertEqual(0, created.returncode, created.stdout + created.stderr)
        before = fixture.plist.read_bytes()

        result = fixture.run("uninstall", env={"FAKE_LAUNCHCTL_MODE": "bootout-fail"})
        self.assertNotEqual(0, result.returncode)
        self.assertIn("nothing was removed", result.stderr)
        self.assertEqual(before, fixture.plist.read_bytes())
        self.assertTrue(fixture.jar.is_file())

    def test_uninstall_refuses_a_foreign_plist(self):
        """A foreign plist is not this installation and must not be deleted."""
        fixture = self.fixture()
        fixture.plist.parent.mkdir(parents=True, exist_ok=True)
        fixture.plist.write_text("foreign\n", encoding="utf-8")
        result = fixture.run("uninstall")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("unmanaged plist", result.stderr)
        self.assertEqual("foreign\n", fixture.plist.read_text(encoding="utf-8"))
        self.assertNotIn("bootout", " ".join(fixture.launchctl_invocations()))


class TestUnsupportedHost(unittest.TestCase):
    """Anything other than Linux or Darwin fails before managed paths exist."""

    def test_unsupported_os_is_rejected_before_writes(self):
        """A fake ``uname`` of another system must fail closed for every mutating command."""
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        fixture = Fixture(temporary.name)
        write_executable(
            fixture.bin / "uname",
            "#!/usr/bin/env bash\nprintf 'FreeBSD\\n'\n",
        )
        result = fixture.install()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("unsupported operating system: FreeBSD", result.stderr)
        self.assertFalse(fixture.unit.exists())
        self.assertFalse(fixture.jar.exists())
        self.assertFalse((fixture.home / "Library").exists())
        self.assertNotIn("mvn", fixture.tools())

        upgrade = fixture.run("upgrade")
        self.assertNotEqual(0, upgrade.returncode)
        self.assertIn("unsupported operating system: FreeBSD", upgrade.stderr)


if __name__ == "__main__":
    unittest.main()
