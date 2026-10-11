"""Contract tests for ``scripts/daemon/prepare-release.sh``.

The fixtures build a tiny repository and shim only curl/npm: the script still performs its
real tag/version checks, real per-platform ``npm ci --os/--cpu`` dependency selection, real
SHA256 verification and real tar staging. This proves the archive is genuinely
platform-specific instead of a Linux ``node_modules`` relabelled as another platform.
"""

from __future__ import annotations

import json
import tarfile
import unittest

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
