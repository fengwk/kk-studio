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
"""

from __future__ import annotations

import os
import platform
import shutil
import signal
import subprocess
import tarfile
import tempfile
import time
import unittest
from pathlib import Path

from daemon_fixture import DAEMON_VERSION

ARCHIVE_NAME = f"kk-studio-daemon-v{DAEMON_VERSION}-linux-{{arch}}.tar.gz"

_SMOKE = Path(__file__).resolve().parent.parent / "smoke-archive.sh"
_SHIM_TEMPLATE = Path(__file__).resolve().parent / "fixtures" / "fake-frozen-runtime.sh.in"

# Host npm/koffi naming: the archive must carry the prebuilt for this host's architecture.
_KOFFI_BY_ARCH = {"amd64": "koffi-linux-x64", "arm64": "koffi-linux-arm64"}


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
) -> Path:
    build = path.parent / "build"
    if build.exists():
        shutil.rmtree(build)
    koffi_dir = build / "node_modules" / "@koromix" / _KOFFI_BY_ARCH[_host_arch()]
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
        if platform.system() != "Linux":
            self.skipTest("smoke-archive.sh is Linux-only")
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
        )

    def test_rejects_wrong_arguments(self) -> None:
        self.assertEqual(subprocess.run(["bash", str(_SMOKE), "--help"], capture_output=True).returncode, 0)
        missing = subprocess.run(["bash", str(_SMOKE)], capture_output=True)
        self.assertEqual(missing.returncode, 2)
        absent = self._run(self.root / ARCHIVE_NAME.format(arch=self.arch))
        self.assertNotEqual(absent.returncode, 0)
        self.assertIn("readable non-empty file", absent.stderr)

    def test_rejects_non_linux_archive_name(self) -> None:
        archive = self.root / "kk-studio-daemon-v1.0.12-macos-arm64.tar.gz"
        archive.write_bytes(b"present but not a linux archive")
        result = self._run(archive)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("only runs a linux archive", result.stderr)

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

        bad_help = self._archive(name="kk-studio-daemon-v1.0.12-help-linux-amd64.tar.gz", help_ok=False)
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


if __name__ == "__main__":
    unittest.main()
