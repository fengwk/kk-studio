"""Contract tests for ``scripts/daemon/prepare-release.sh``.

The fixtures shim curl/npm while retaining real tag/version checks, SHA256 verification,
archive parsing and staging. They verify selection arguments and archive layout, not
native ABI compatibility; the host-matched CI smoke tests cover runnable platform binaries.
"""

from __future__ import annotations

import json
import subprocess
import tarfile
import unittest

from pathlib import Path

from daemon_fixture import (
    DAEMON_VERSION,
    InstallFixture,  # noqa: F401  (re-exported for sibling suites)
    PLATFORMS,
    RELEASE_TAG,
    ReleaseFixture,
    TempDirTestCase,
    sha256_of,
)


class PrepareReleaseTest(TempDirTestCase):
    def setUp(self) -> None:
        super().setUp()
        self.fixture = ReleaseFixture(self.tmp)

    def test_stages_exactly_five_assets_for_linux(self) -> None:
        result = self.fixture.run(RELEASE_TAG, "linux", "amd64")
        self.assertEqual(result.returncode, 0, result.stderr)
        release = self.fixture.release_dir("linux-amd64")
        names = sorted(path.name for path in release.iterdir())
        prefix = f"kk-studio-daemon-{RELEASE_TAG}-linux-amd64"
        self.assertEqual(
            names,
            sorted([f"{prefix}.json", f"{prefix}.tar.gz", f"{prefix}.tar.gz.sha256", "LICENSE", "THIRD_PARTY_NOTICES"]),
        )
        archive = release / f"{prefix}.tar.gz"
        self.assertEqual((release / f"{prefix}.tar.gz.sha256").read_text().split()[0], sha256_of(archive))
        metadata = json.loads((release / f"{prefix}.json").read_text())
        self.assertEqual(metadata["tag"], RELEASE_TAG)
        self.assertEqual(metadata["projectVersion"], DAEMON_VERSION)
        self.assertEqual(metadata["os"], "linux")
        self.assertEqual(metadata["arch"], "amd64")
        self.assertEqual(metadata["artifactSha256"], sha256_of(archive))
        self.assertEqual(metadata["entrypoint"], "main.js")

    def test_rejects_an_asset_version_that_differs_from_the_manifest(self) -> None:
        # 运行时版本来自清单；URL 版本不同必须在下载和安装依赖前拒绝。
        manifest = self.fixture.repo / "daemon" / "runtime-manifest.json"
        value = json.loads(manifest.read_text())
        value["codeServerVersion"] = "9.9.9-kk.2"
        manifest.write_text(json.dumps(value))
        result = self.fixture.run(RELEASE_TAG, "linux", "amd64")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("not the frozen fork release asset", result.stderr)
        self.assertFalse(self.fixture.release_dir("linux-amd64").exists())

    def test_archive_contains_platform_prebuilt_runtime_and_extension(self) -> None:
        self.assertEqual(self.fixture.run(RELEASE_TAG, "linux", "amd64").returncode, 0)
        archive = self.fixture.release_dir("linux-amd64") / f"kk-studio-daemon-{RELEASE_TAG}-linux-amd64.tar.gz"
        with tarfile.open(archive) as tar:
            names = [name.lstrip("./") for name in tar.getnames()]
        for expected in (
            "main.js",
            "package.json",
            "runtime-manifest.json",
            "node_modules/@koromix/koffi-linux-x64/index.js",
            "runtime/code-server/lib/node",
            "runtime/code-server/out/node/entry.js",
            "ide-extension/extension.js",
            "LICENSE",
            "THIRD_PARTY_NOTICES",
        ):
            self.assertIn(expected, names)
        self.assertNotIn("node_modules/@koromix/koffi-darwin-x64", names)

    def test_selects_target_optional_prebuilt_per_platform(self) -> None:
        expected = {
            ("linux", "amd64"): "koffi-linux-x64",
            ("linux", "arm64"): "koffi-linux-arm64",
            ("macos", "amd64"): "koffi-darwin-x64",
            ("macos", "arm64"): "koffi-darwin-arm64",
            ("windows", "amd64"): "koffi-win32-x64",
        }
        for (os_name, arch), koffi in expected.items():
            with self.subTest(platform=f"{os_name}-{arch}"):
                result = self.fixture.run(RELEASE_TAG, os_name, arch)
                self.assertEqual(result.returncode, 0, result.stderr)
                archive = (
                    self.fixture.release_dir(f"{os_name}-{arch}")
                    / f"kk-studio-daemon-{RELEASE_TAG}-{os_name}-{arch}.tar.gz"
                )
                with tarfile.open(archive) as tar:
                    names = [name.lstrip("./") for name in tar.getnames()]
                self.assertIn(f"node_modules/@koromix/{koffi}/index.js", names)
                self.assertIn("runtime/code-server/out/node/entry.js", names)
                node_name = "node.exe" if os_name == "windows" else "node"
                self.assertIn(f"runtime/code-server/lib/{node_name}", names)
                self.assertFalse(any("koffi-freebsd" in name for name in names))

    def test_installs_each_platform_lockfile_without_running_lifecycle_scripts(self) -> None:
        # 跨平台暂存只安装锁定清单里的预编译成品：每个平台都必须按目标 --os/--cpu 选择 optional
        # 包并忽略生命周期脚本，否则异架构 install script 会在宿主上失败（旧实现即由此回归捕获）。
        expected = {
            ("linux", "amd64"): ("linux", "x64"),
            ("linux", "arm64"): ("linux", "arm64"),
            ("macos", "amd64"): ("darwin", "x64"),
            ("macos", "arm64"): ("darwin", "arm64"),
            ("windows", "amd64"): ("win32", "x64"),
        }
        for (os_name, arch), (npm_os, npm_cpu) in expected.items():
            with self.subTest(platform=f"{os_name}-{arch}"):
                self.fixture.npm_log.unlink(missing_ok=True)
                result = self.fixture.run(RELEASE_TAG, os_name, arch)
                self.assertEqual(result.returncode, 0, result.stderr)
                invocations = [json.loads(line) for line in self.fixture.npm_log.read_text().splitlines()]
                self.assertEqual(len(invocations), 1)
                args = invocations[0]
                self.assertIn("ci", args)
                self.assertIn("--prefix", args)
                self.assertIn("--omit=dev", args)
                self.assertIn("--ignore-scripts", args)
                self.assertIn(f"--os={npm_os}", args)
                self.assertIn(f"--cpu={npm_cpu}", args)

    def test_fake_npm_rejects_installs_that_would_run_lifecycle_scripts(self) -> None:
        # fixture 自身必须真的拒绝缺失 --ignore-scripts 的安装，否则跨平台回归无法捕获旧实现。
        prefix = self.tmp / "deps"
        prefix.mkdir()
        result = subprocess.run(
            [
                str(self.fixture.bindir / "npm"),
                "--prefix",
                str(prefix),
                "ci",
                "--omit=dev",
                "--os=linux",
                "--cpu=arm64",
            ],
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--ignore-scripts", result.stderr)
        self.assertFalse((prefix / "node_modules").exists())

    def test_prunes_foreign_koffi_prebuilts(self) -> None:
        result = self.fixture.run(RELEASE_TAG, "linux", "amd64", extra_env={"FAKE_NPM_FOREIGN_PREBUILT": "1"})
        self.assertEqual(result.returncode, 0, result.stderr)
        archive = self.fixture.release_dir("linux-amd64") / f"kk-studio-daemon-{RELEASE_TAG}-linux-amd64.tar.gz"
        with tarfile.open(archive) as tar:
            names = [name.lstrip("./") for name in tar.getnames()]
        self.assertIn("node_modules/@koromix/koffi-linux-x64/index.js", names)
        self.assertFalse(any("koffi-freebsd" in name for name in names))

    def test_refuses_to_package_before_the_runtime_sha_is_frozen(self) -> None:
        manifest_path = self.fixture.repo / "daemon" / "runtime-manifest.json"
        manifest = json.loads(manifest_path.read_text())
        del manifest["codeServer"]["linux-amd64"]["sha256"]
        manifest_path.write_text(json.dumps(manifest))
        result = self.fixture.run(RELEASE_TAG, "linux", "amd64")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("not frozen yet", result.stderr)
        self.assertFalse(self.fixture.release_dir("linux-amd64").exists())

    def test_rejects_missing_target_prebuilt(self) -> None:
        result = self.fixture.run(RELEASE_TAG, "linux", "amd64", extra_env={"FAKE_NPM_OMIT_PREBUILT": "1"})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("prebuilt", result.stderr)
        self.assertFalse(self.fixture.release_dir("linux-amd64").exists())

    def test_rejects_windows_arm64(self) -> None:
        result = self.fixture.run(RELEASE_TAG, "windows", "arm64")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("windows supports amd64 only", result.stderr)

    def test_rejects_tag_version_mismatch(self) -> None:
        result = self.fixture.run("v9.9.9", "linux", "amd64")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("must equal the daemon version", result.stderr)

    def test_rejects_unsafe_tag_and_platform(self) -> None:
        self.assertNotEqual(self.fixture.run("1.0.12", "linux", "amd64").returncode, 0)
        self.assertNotEqual(self.fixture.run(RELEASE_TAG, "plan9", "amd64").returncode, 0)
        self.assertNotEqual(self.fixture.run(RELEASE_TAG, "linux", "sparc").returncode, 0)

    def test_rejects_a_code_server_link_that_escapes_the_archive(self) -> None:
        platform = "linux-amd64"
        name = f"code-server-4.141.0-kk.1-{platform}.tar.gz"
        runtime = self.fixture.http_dir / name
        work = self.tmp / "escape"
        root = work / f"code-server-4.141.0-kk.1-{platform}"
        (root / "lib").mkdir(parents=True)
        node = root / "lib" / "node"
        node.write_text("#!/bin/sh\n")
        node.chmod(0o755)
        entry = root / "out" / "node" / "entry.js"
        entry.parent.mkdir(parents=True)
        entry.write_text("// entry\n")
        (root / "lib" / "escape").symlink_to(Path("..") / ".." / "outside")
        runtime.unlink()
        with tarfile.open(runtime, "w:gz") as archive:
            archive.add(root, arcname=root.name)
        manifest_path = self.fixture.repo / "daemon" / "runtime-manifest.json"
        manifest = json.loads(manifest_path.read_text())
        manifest["codeServer"][platform]["sha256"] = sha256_of(runtime)
        manifest_path.write_text(json.dumps(manifest))
        result = self.fixture.run(RELEASE_TAG, "linux", "amd64")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("escapes the extraction root", result.stderr)
        self.assertFalse(self.fixture.release_dir("linux-amd64").exists())

    def test_rejects_code_server_hash_mismatch(self) -> None:
        manifest_path = self.fixture.repo / "daemon" / "runtime-manifest.json"
        manifest = json.loads(manifest_path.read_text())
        manifest["codeServer"]["linux-amd64"]["sha256"] = "0" * 64
        manifest_path.write_text(json.dumps(manifest))
        result = self.fixture.run(RELEASE_TAG, "linux", "amd64")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("SHA256", result.stderr)
        self.assertFalse(self.fixture.release_dir("linux-amd64").exists())

    def test_help_and_usage(self) -> None:
        self.assertEqual(self.fixture.run("--help").returncode, 0)
        self.assertEqual(self.fixture.run().returncode, 2)

    def test_platform_matrix_is_five_platforms(self) -> None:
        self.assertEqual(len(PLATFORMS), 5)


if __name__ == "__main__":
    unittest.main()
