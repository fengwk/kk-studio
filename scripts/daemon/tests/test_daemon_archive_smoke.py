"""Contract tests for ``scripts/daemon/smoke-archive.sh``.

These build *lightweight* fake archives with a shimmed frozen runtime so every failure
boundary of the smoke harness is exercised by the real script: name/platform rejection,
missing archive entries, a wrong frozen Node major, a manifest/version mismatch, wrong
``--version``/``--help`` output, a valid configuration the runtime rejects, an invalid or
wrapped configuration the runtime accepts, and a failing koffi probe. They also assert the
resource-release contract: on timeout and on SIGTERM the harness must terminate and reap the
daemon it started before deleting its temporary directory.

The fake runtime is a shim that only satisfies the harness's own call shapes; it does not
prove product behaviour. The real archive smoke (built by ``prepare-release.sh`` and run in
the release pipeline) is what proves the product.

``CodeServerNativeProbeTest`` closes the gap where the shim never executes JavaScript: it
extracts the smoke's exact native-probe heredoc into a temporary ``.mjs`` and runs it with a
real Node interpreter against a real (koffi) native module, so the Windows-helper exclusion
and the root-relative foreign check are exercised for real instead of being simulated.

``WindowsSmokeArchiveContractTest`` and ``WindowsSmokeNativeProbeTest`` cover the sibling
``scripts/daemon/smoke-archive.ps1``, which cannot execute on Linux/macOS runners. The
structural tests assert that the real Git Bash is captured as an absolute path before PATH is
emptied and that a missing shell fails instead of being faked; the probe test runs the smoke's
real native-probe JS against a stub ``native`` module that provides only the call shapes, to
prove the captured path is serialized into ``daemon.json`` verbatim.
"""

from __future__ import annotations

import json
import os
import platform
import re
import shutil
import signal
import subprocess
import tarfile
import tempfile
import time
import unittest
from pathlib import Path

from release_fixture import DAEMON_VERSION

def _host_os() -> str:
    system = platform.system()
    if system == "Linux":
        return "linux"
    if system == "Darwin":
        return "macos"
    return system.lower()


ARCHIVE_NAME = f"kk-studio-daemon-v{DAEMON_VERSION}-{_host_os()}-{{arch}}.tar.gz"

_SMOKE = Path(__file__).resolve().parent.parent / "smoke-archive.sh"
_WINDOWS_SMOKE = Path(__file__).resolve().parent.parent / "smoke-archive.ps1"
_SHIM_TEMPLATE = Path(__file__).resolve().parent / "fixtures" / "fake-frozen-runtime.sh.in"

# Host npm/koffi naming: the archive must carry the prebuilt for this host's architecture.
_KOFFI = {
    ("linux", "amd64"): "koffi-linux-x64",
    ("linux", "arm64"): "koffi-linux-arm64",
    ("macos", "amd64"): "koffi-darwin-x64",
    ("macos", "arm64"): "koffi-darwin-arm64",
}

# Repository root and the installed koffi prebuilt paths. The real-probe regression copies one
# loadable host native from here; CI runs ``npm --prefix daemon ci`` before these tests, so the
# prebuilt is always present and a missing dependency must fail rather than skip the test.
_REPO_ROOT = Path(__file__).resolve().parents[3]
_KOFFI_PREBUILT = {
    ("linux", "amd64"): ("koffi-linux-x64", "linux_x64"),
    ("linux", "arm64"): ("koffi-linux-arm64", "linux_arm64"),
    ("macos", "amd64"): ("koffi-darwin-x64", "darwin_x64"),
    ("macos", "arm64"): ("koffi-darwin-arm64", "darwin_arm64"),
}

# One platform marker foreign to each host, used to prove the probe judges a stable root-relative
# path instead of the absolute temporary path.
_FOREIGN_MARKER = {
    "linux-amd64": "darwin-x64",
    "linux-arm64": "darwin-arm64",
    "macos-amd64": "linux-x64",
    "macos-arm64": "linux-arm64",
}

# The smoke's native probe is a heredoc; extract exactly that block so the tests run the real JS.
_NATIVE_PROBE_HEREDOC = re.compile(
    r"native_probe=\"\$tmp/probe-code-server-native\.mjs\"\n"
    r"cat >\"\$native_probe\" <<'PROBE'\n"
    r"(?P<body>.*?)\n"
    r"PROBE\n",
    re.DOTALL,
)

# The Windows smoke embeds its native probe as a PowerShell here-string; extract exactly that
# block so the tests run the real JS rather than matching its text.
_WINDOWS_NATIVE_PROBE_HERESTRING = re.compile(
    r"\[System\.IO\.File\]::WriteAllText\(\$probe, @'\n"
    r"(?P<body>.*?)\n"
    r"'@, \$utf8\)\n",
    re.DOTALL,
)

# Stub for the archive's Windows ``native`` module: only the call shapes the probe uses (private
# dir/file creation and an exclusive directory lock), with no real Windows ACL semantics.
_WINDOWS_PROBE_NATIVE_STUB = """\
import { mkdirSync, writeFileSync } from 'node:fs'

export async function ensurePrivateDirectory(directory) {
  mkdirSync(directory, { recursive: true, mode: 0o700 })
}

export async function writePrivateFile(file, content) {
  writeFileSync(file, content, { mode: 0o600 })
}

let held = false

export async function acquireDirectoryLock() {
  if (held) {
    throw new Error('the directory is already locked')
  }
  held = true
  return { close: async () => { held = false } }
}
"""


def _node_binary() -> str:
    node = shutil.which("node")
    if node is None:
        raise AssertionError("the native probe regression needs a real Node interpreter on PATH")
    return node


def _host_native_bytes() -> bytes:
    package, libdir = _KOFFI_PREBUILT[(_host_os(), _host_arch())]
    native = _REPO_ROOT / "daemon" / "node_modules" / "@koromix" / package / libdir / "koffi.node"
    if not native.is_file():
        raise AssertionError(
            f"missing loadable host native {native}; run `npm --prefix daemon ci` first"
        )
    return native.read_bytes()


def _extract_native_probe() -> str:
    match = _NATIVE_PROBE_HEREDOC.search(_SMOKE.read_text(encoding="utf-8"))
    if match is None:
        raise AssertionError("could not extract the native probe heredoc from smoke-archive.sh")
    return match.group("body") + "\n"


def _windows_smoke() -> str:
    return _WINDOWS_SMOKE.read_text(encoding="utf-8")


def _extract_windows_native_probe() -> str:
    match = _WINDOWS_NATIVE_PROBE_HERESTRING.search(_windows_smoke())
    if match is None:
        raise AssertionError("could not extract the native probe here-string from smoke-archive.ps1")
    return match.group("body") + "\n"


def _host_arch() -> str:
    machine = platform.machine().lower()
    if machine in ("x86_64", "amd64"):
        return "amd64"
    if machine in ("aarch64", "arm64"):
        return "arm64"
    return machine


def _absolute(tool: str) -> str:
    resolved = shutil.which(tool)
    if resolved is None:
        raise RuntimeError(f"missing command: {tool}")
    return resolved


def _shim(
    *,
    node_version: str = "v24.21.0",
    cli_version: str = f"kk-studio-daemon {DAEMON_VERSION}",
    daemon_version: str = DAEMON_VERSION,
    manifest_version: str = DAEMON_VERSION,
    help_ok: bool = True,
    valid_ok: bool = True,
    invalid_rejected: bool = True,
    wrapped_rejected: bool = True,
    probe_out: str = "koffi directory lock ok",
    probe_exit: int = 0,
    native_ok: bool = True,
    native_foreign: bool = False,
    config_mode: str = "hold",
    pid_file: str = "",
) -> str:
    """A frozen-runtime shim that answers exactly the calls the smoke harness makes."""
    sleep = _absolute("sleep")
    mkdir = _absolute("mkdir")
    touch = _absolute("touch")
    grep = _absolute("grep")
    values = {
        "@NODE_VERSION@": node_version,
        "@CLI_VERSION@": cli_version,
        "@DAEMON_VERSION@": daemon_version,
        "@MANIFEST_VERSION@": manifest_version,
        "@HELP_OK@": "1" if help_ok else "0",
        "@VALID_OK@": "1" if valid_ok else "0",
        "@INVALID_REJECTED@": "1" if invalid_rejected else "0",
        "@WRAPPED_REJECTED@": "1" if wrapped_rejected else "0",
        "@PROBE_OUT@": probe_out,
        "@PROBE_EXIT@": str(probe_exit),
        "@NATIVE_OK@": "1" if native_ok else "0",
        "@NATIVE_FOREIGN@": "1" if native_foreign else "0",
        "@CONFIG_MODE@": config_mode,
        "@PID_FILE@": pid_file,
        "@SLEEP@": sleep,
        "@MKDIR@": mkdir,
        "@TOUCH@": touch,
        "@GREP@": grep,
    }
    template = _SHIM_TEMPLATE.read_text(encoding="utf-8")
    for marker, value in values.items():
        template = template.replace(marker, value)
    return template


def _build_archive(
    path: Path,
    *,
    node: str,
    name: str | None = None,
    include_runtime: bool = True,
    include_main: bool = True,
    include_koffi: bool = True,
    include_ide: bool = True,
    include_entry: bool = True,
    include_native: bool = True,
) -> Path:
    build = path.parent / "build"
    if build.exists():
        shutil.rmtree(build)
    koffi_dir = build / "node_modules" / "@koromix" / _KOFFI[(_host_os(), _host_arch())]
    koffi_dir.mkdir(parents=True)
    (koffi_dir / "index.js").write_text("module.exports = {}\n")
    if include_ide:
        (build / "ide-extension").mkdir(parents=True)
        (build / "ide-extension" / "extension.cjs").write_text("module.exports = {}\n")
    if include_runtime:
        runtime = build / "runtime" / "code-server" / "lib" / "node"
        runtime.parent.mkdir(parents=True)
        runtime.write_text(node)
        runtime.chmod(0o755)
        if include_entry:
            entry = build / "runtime" / "code-server" / "out" / "node" / "entry.js"
            entry.parent.mkdir(parents=True, exist_ok=True)
            entry.write_text("// frozen entry\n")
        if include_native:
            native = build / "runtime" / "code-server" / "lib" / "vscode" / "pty.node"
            native.parent.mkdir(parents=True, exist_ok=True)
            native.write_bytes(b"\x7fELF")
    if include_main:
        (build / "main.js").write_text("export {}\n")
    if not include_koffi:
        shutil.rmtree(koffi_dir)
    (build / "package.json").write_text('{"name":"@kk-studio/daemon","version":"' + DAEMON_VERSION + '","type":"module"}\n')
    (build / "runtime-manifest.json").write_text('{"schemaVersion":1}\n')
    (build / "LICENSE").write_text("Apache License 2.0\n")
    (build / "THIRD_PARTY_NOTICES").write_text("notices\n")

    with tarfile.open(path, "w:gz") as archive:
        for root, dirs, files in os.walk(build):
            for directory in dirs:
                full = os.path.join(root, directory)
                archive.add(full, arcname=os.path.relpath(full, build), recursive=False)
            for name_ in files:
                full = os.path.join(root, name_)
                archive.add(full, arcname=os.path.relpath(full, build), recursive=False)
    return path


class SmokeArchiveTest(unittest.TestCase):
    def setUp(self) -> None:
        if platform.system() not in ("Linux", "Darwin"):
            self.skipTest("smoke-archive.sh runs on Linux and macOS")
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)
        self.arch = _host_arch()
        self.other_arch = "arm64" if self.arch == "amd64" else "amd64"

    def _run(self, archive: Path, *, tmpdir: Path | None = None, timeout: float = 60.0) -> subprocess.CompletedProcess:
        env = {**os.environ}
        if tmpdir is not None:
            env["TMPDIR"] = str(tmpdir)
        return subprocess.run(
            ["bash", str(_SMOKE), str(archive)],
            env=env,
            capture_output=True,
            text=True,
            timeout=timeout,
        )

    def _archive(
        self,
        name: str | None = None,
        *,
        include_runtime: bool = True,
        include_main: bool = True,
        include_koffi: bool = True,
        include_ide: bool = True,
        include_entry: bool = True,
        include_native: bool = True,
        **shim_kwargs,
    ) -> Path:
        name = name or ARCHIVE_NAME.format(arch=self.arch)
        return _build_archive(
            self.root / name,
            node=_shim(**shim_kwargs),
            include_runtime=include_runtime,
            include_main=include_main,
            include_koffi=include_koffi,
            include_ide=include_ide,
            include_entry=include_entry,
            include_native=include_native,
        )

    def test_rejects_wrong_arguments(self) -> None:
        self.assertEqual(subprocess.run(["bash", str(_SMOKE), "--help"], capture_output=True).returncode, 0)
        missing = subprocess.run(["bash", str(_SMOKE)], capture_output=True)
        self.assertEqual(missing.returncode, 2)
        absent = self._run(self.root / ARCHIVE_NAME.format(arch=self.arch))
        self.assertNotEqual(absent.returncode, 0)
        self.assertIn("readable non-empty file", absent.stderr)

    def test_rejects_windows_archive_name(self) -> None:
        archive = self.root / "kk-studio-daemon-v1.0.14-windows-amd64.tar.gz"
        archive.write_bytes(b"present but not a unix archive")
        result = self._run(archive)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("linux or macos archive", result.stderr)

    def test_rejects_cross_arch_archive(self) -> None:
        archive = self._archive(name=ARCHIVE_NAME.format(arch=self.other_arch))
        result = self._run(archive)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("cross-platform archives cannot run here", result.stderr)

    def test_rejects_corrupt_archive(self) -> None:
        corrupt = self.root / ARCHIVE_NAME.format(arch=self.arch)
        corrupt.write_bytes(b"not a gzip archive")
        result = self._run(corrupt)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("cannot extract the archive", result.stderr)

    def test_rejects_missing_archive_entries(self) -> None:
        cases = {
            "missing runtime": {"include_runtime": False},
            "missing entrypoint": {"include_main": False},
            "missing koffi": {"include_koffi": False},
            "missing IDE extension": {"include_ide": False},
            "missing code-server entry": {"include_entry": False},
        }
        for label, flags in cases.items():
            with self.subTest(label=label):
                archive = self._archive(**flags)
                result = self._run(archive)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("archive is missing", result.stderr)
                archive.unlink()

    def test_rejects_wrong_frozen_node_major(self) -> None:
        archive = self._archive(node_version="v22.0.0")
        result = self._run(archive)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("must be Node 24.x", result.stderr)

    def test_rejects_manifest_version_mismatch(self) -> None:
        archive = self._archive(manifest_version="9.9.9")
        result = self._run(archive)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("runtime-manifest daemonVersion", result.stderr)

    def test_rejects_wrong_cli_version_and_help(self) -> None:
        wrong_version = self._archive(cli_version="kk-studio-daemon 9.9.9")
        result = self._run(wrong_version)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--version mismatch", result.stderr)

        bad_help = self._archive(name=f"kk-studio-daemon-v1.0.14-help-{_host_os()}-{self.arch}.tar.gz", help_ok=False)
        result = self._run(bad_help)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--help", result.stderr)

    def test_rejects_config_boundaries(self) -> None:
        rejected_valid = self._archive(valid_ok=False)
        result = self._run(rejected_valid)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("rejected a valid configuration", result.stderr)

        accepted_invalid = self._archive(invalid_rejected=False)
        result = self._run(accepted_invalid)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("invalid configuration must be rejected", result.stderr)

        accepted_wrapped = self._archive(wrapped_rejected=False)
        result = self._run(accepted_wrapped)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("wrapped daemon object must be rejected", result.stderr)

    def test_rejects_a_native_module_built_for_another_platform(self) -> None:
        archive = self._archive(native_foreign=True)
        result = self._run(archive)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("native modules failed to load", result.stderr)

    def test_rejects_code_server_native_module_that_does_not_load(self) -> None:
        archive = self._archive(native_ok=False)
        result = self._run(archive)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("native modules failed to load", result.stderr)

    def test_rejects_failing_koffi_probe(self) -> None:
        archive = self._archive(probe_exit=4, probe_out="boom")
        result = self._run(archive)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("koffi directory lock probe failed", result.stderr)

    def test_success_path_terminates_and_reaps_the_daemon(self) -> None:
        pid_file = self.root / "daemon.pid"
        tmpdir = self.root / "tmp"
        tmpdir.mkdir()
        archive = self._archive(config_mode="hold", pid_file=str(pid_file))
        result = self._run(archive, tmpdir=tmpdir)
        self.assertEqual(result.returncode, 0, f"{result.stdout}\n{result.stderr}")
        self.assertIn("archive smoke ok", result.stdout)
        self._assert_pid_reaped(int(pid_file.read_text()))
        self.assertFalse(list(tmpdir.glob("kk-studio-daemon-smoke.*")), "temporary directory must be removed")

    def test_lock_timeout_is_bounded_and_terminates_the_daemon(self) -> None:
        # The fixture never creates a lock; the harness must give up and still reap the daemon.
        pid_file = self.root / "timeout.pid"
        tmpdir = self.root / "tmp-timeout"
        tmpdir.mkdir()
        archive = self._archive(config_mode="sleep-no-lock", pid_file=str(pid_file))
        result = self._run(archive, tmpdir=tmpdir, timeout=60.0)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("did not acquire its data lock", result.stderr)
        self._assert_pid_reaped(int(pid_file.read_text()))
        self.assertFalse(list(tmpdir.glob("kk-studio-daemon-smoke.*")))

    def test_sigterm_is_forwarded_and_the_daemon_is_reaped(self) -> None:
        pid_file = self.root / "signal.pid"
        tmpdir = self.root / "tmp-signal"
        tmpdir.mkdir()
        archive = self._archive(config_mode="hold", pid_file=str(pid_file))
        process = subprocess.Popen(
            ["bash", str(_SMOKE), str(archive)],
            env={**os.environ, "TMPDIR": str(tmpdir)},
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        try:
            for _ in range(200):
                if list(tmpdir.glob("kk-studio-daemon-smoke.*/run/daemon.lock")):
                    break
                if process.poll() is not None:
                    self.fail(f"harness exited early: {process.communicate()}")
                time.sleep(0.05)
            else:
                self.fail("the fixture daemon never acquired its lock")
            process.send_signal(signal.SIGTERM)
            stdout, stderr = process.communicate(timeout=30)
            self.assertEqual(process.returncode, 143, f"{stdout}\n{stderr}")
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
        self._assert_pid_reaped(int(pid_file.read_text()))
        self.assertFalse(list(tmpdir.glob("kk-studio-daemon-smoke.*")))

    def test_a_daemon_that_ignores_sigterm_is_not_reported_as_success(self) -> None:
        # A daemon that requires SIGKILL must not produce a green smoke: the success path only
        # accepts a graceful SIGTERM exit, while the process is still forcibly reaped.
        pid_file = self.root / "ignore.pid"
        tmpdir = self.root / "tmp-ignore"
        tmpdir.mkdir()
        archive = self._archive(config_mode="ignore-term", pid_file=str(pid_file))
        result = self._run(archive, tmpdir=tmpdir, timeout=60.0)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("must exit gracefully on SIGTERM", result.stderr)
        self.assertNotIn("archive smoke ok", result.stdout)
        self._assert_pid_reaped(int(pid_file.read_text()))
        self.assertFalse(list(tmpdir.glob("kk-studio-daemon-smoke.*")))

    def _assert_pid_reaped(self, pid: int) -> None:
        for _ in range(100):
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                return
            time.sleep(0.1)
        self.fail(f"the daemon process {pid} was not terminated")


class CodeServerNativeProbeTest(unittest.TestCase):
    """Execute the smoke's real native-probe JS with a real Node and a real host native.

    The shim-based ``SmokeArchiveTest`` never runs JavaScript, so it cannot prove the probe's
    Windows-helper exclusion or its root-relative foreign check. Here the exact heredoc from
    ``smoke-archive.sh`` is written to a temporary ``.mjs`` and run against a fixture code-server
    tree whose only loadable native is the repository's installed koffi prebuilt.
    """

    def setUp(self) -> None:
        if platform.system() not in ("Linux", "Darwin"):
            self.skipTest("smoke-archive.sh runs on Linux and macOS")
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)
        self.node = _node_binary()
        self.native_bytes = _host_native_bytes()
        self.host = f"{_host_os()}-{_host_arch()}"
        self.probe = self.root / "probe-code-server-native.mjs"
        self.probe.write_text(_extract_native_probe(), encoding="utf-8")
        self.code_server = self.root / "code-server"

    def _write_native(
        self, relative: str, data: bytes | None = None, *, root: Path | None = None
    ) -> Path:
        target = (root or self.code_server) / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(self.native_bytes if data is None else data)
        return target

    def _write_windows_helpers(self, root: Path | None = None) -> None:
        vendor = (root or self.code_server) / "lib/vscode/extensions/ms-vscode.js-debug/src"
        vendor.mkdir(parents=True, exist_ok=True)
        # Both universal js-debug Windows helpers, deliberately with empty contents: on a Unix
        # host they are never required, so their payload must not matter.
        for name in (
            "win32-app-container-tokens.win32-x64-msvc-VCQE7GJP.node",
            "win32-app-container-tokens.win32-arm64-msvc-4ZJZ3U55.node",
        ):
            (vendor / name).write_bytes(b"")

    def _run_probe(self, root: Path | None = None) -> subprocess.CompletedProcess:
        return subprocess.run(
            [self.node, str(self.probe), str(root or self.code_server), self.host],
            capture_output=True,
            text=True,
            timeout=60,
        )

    def test_ignores_both_windows_helpers_next_to_a_host_native(self) -> None:
        self._write_native("lib/vscode/node_modules/node-pty/build/Release/pty.node")
        self._write_windows_helpers()
        result = self._run_probe()
        self.assertEqual(result.returncode, 0, result.stderr)
        # Only the host native is counted; the two excluded helpers are not loaded.
        self.assertEqual(result.stdout.strip(), "code-server native modules ok 1")

    def test_rejects_a_windows_stem_outside_the_vendor_path(self) -> None:
        self._write_native("lib/vscode/node_modules/node-pty/build/Release/pty.node")
        # Same Windows stem as a helper, but not the exact vendored js-debug path: still foreign.
        self._write_native(
            "lib/vscode/win32-app-container-tokens.win32-x64-msvc-OTHER.node", b"\x7fELF"
        )
        result = self._run_probe()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("not for", result.stderr)

    def test_fails_when_only_the_excluded_helpers_exist(self) -> None:
        self._write_windows_helpers()
        result = self._run_probe()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("code-server native modules are missing", result.stderr)

    def test_fails_when_the_host_native_cannot_load(self) -> None:
        self._write_native(
            "lib/vscode/node_modules/node-pty/build/Release/pty.node", b"not a native module"
        )
        result = self._run_probe()
        self.assertNotEqual(result.returncode, 0)

    def test_foreign_marker_in_the_absolute_path_does_not_false_fail(self) -> None:
        # The fixture root's absolute path contains a marker foreign to this host; the probe
        # must decide on the root-relative path, so a valid host native still passes.
        carrier = self.root / f"carrier-{_FOREIGN_MARKER[self.host]}"
        root = carrier / "code-server"
        self._write_native("lib/vscode/node_modules/node-pty/build/Release/pty.node", root=root)
        result = self._run_probe(root)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "code-server native modules ok 1")


class WindowsSmokeArchiveContractTest(unittest.TestCase):
    """Contract tests for ``scripts/daemon/smoke-archive.ps1``.

    The smoke empties PATH for the frozen Node checks, so the daemon config must carry an
    absolute Git Bash path captured beforehand. These tests assert that capture ordering and
    that the frozen empty-PATH checks are untouched.
    """

    def setUp(self) -> None:
        self.smoke = _windows_smoke()

    def test_captures_real_git_bash_before_emptying_the_path(self) -> None:
        # 清空 PATH 之前用 Get-Command 解析真实 bash.exe，取 .Source 绝对路径并校验 leaf。
        capture = self.smoke.index("$bashCommand = Get-Command bash.exe -CommandType Application")
        empty_path = self.smoke.index("$env:PATH = Join-Path $root 'empty-path'")
        self.assertLess(capture, empty_path, "bash must be captured while the real PATH is intact")
        self.assertIn("$bashExecutable = $bashCommand.Source", self.smoke)
        self.assertIn("(Split-Path -Leaf $bashExecutable) -ne 'bash.exe'", self.smoke)
        # 缺 Git Bash 必须显式失败；不接受伪 shell 或 PATH 回退。
        self.assertIn(
            "Fail 'Git Bash is required for the archive smoke but bash.exe was not found on PATH'",
            self.smoke,
        )
        self.assertIn("Test-Path -LiteralPath $bashExecutable -PathType Leaf", self.smoke)

    def test_the_captured_bash_reaches_the_native_probe_config(self) -> None:
        # 捕获的绝对路径必须真的经 native probe 写进 daemon.json，而不是只被赋值后忽略。
        self.assertIn("& $node $probe $root $bashExecutable", self.smoke)
        probe = _extract_windows_native_probe()
        self.assertIn("const [root, bashExecutable] = process.argv.slice(2)", probe)
        self.assertIn("bashExecutable })", probe)

    def test_frozen_checks_still_run_under_the_empty_path(self) -> None:
        # 收紧：--check-config 必须在空 PATH 下运行，通过显式 bashExecutable 通过，而不是把真实 PATH 还回来。
        empty_path = self.smoke.index("$env:PATH = Join-Path $root 'empty-path'")
        valid_check = self.smoke.index("& $node $main --check-config $config\n")
        restore = self.smoke.index("$env:PATH = $previousPath")
        self.assertLess(empty_path, valid_check, "the frozen checks must run after PATH is emptied")
        self.assertLess(valid_check, restore, "the real PATH must only be restored in the finally block")
        for marker in ("--version", "--help", "--check-config"):
            self.assertIn(marker, self.smoke)
        # 无效配置用例必须保留，不得放宽。
        for document in ('{"studioUrl":"not-a-url"}', '{"daemon":{"studioUrl":"http://127.0.0.1:59999"}}'):
            self.assertIn(document, self.smoke)


class WindowsSmokeNativeProbeTest(unittest.TestCase):
    """Run the Windows smoke's native-probe JS with a stub ``native`` module.

    The stub provides only the call shapes the probe uses (private dir/file creation and an
    exclusive directory lock); it is not a Windows ACL implementation. Running the extracted
    probe asserts the captured absolute bash path is serialized into ``daemon.json`` verbatim.
    """

    def setUp(self) -> None:
        if platform.system() not in ("Linux", "Darwin"):
            self.skipTest("this probe regression runs on Linux and macOS")
        self.node = _node_binary()
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)
        native = self.root / "native"
        native.mkdir()
        (native / "index.js").write_text(_WINDOWS_PROBE_NATIVE_STUB, encoding="utf-8")
        self.probe = self.root / "native-probe.mjs"
        self.probe.write_text(_extract_windows_native_probe(), encoding="utf-8")

    def test_probe_writes_the_captured_bash_path_into_the_config(self) -> None:
        # Windows Git Bash 路径含反斜杠，必须原样往返，不能被 probe 丢失或改写。
        for bash in ("/usr/bin/bash", r"C:\Program Files\Git\usr\bin\bash.exe"):
            with self.subTest(bash=bash):
                result = subprocess.run(
                    [self.node, str(self.probe), str(self.root), bash],
                    capture_output=True,
                    text=True,
                    timeout=60,
                )
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout.strip(), "native directory lock ok")
                config = json.loads((self.root / "config" / "daemon.json").read_text(encoding="utf-8"))
                self.assertEqual(
                    config,
                    {"studioUrl": "http://127.0.0.1:59999", "bashExecutable": bash},
                )


if __name__ == "__main__":
    unittest.main()
