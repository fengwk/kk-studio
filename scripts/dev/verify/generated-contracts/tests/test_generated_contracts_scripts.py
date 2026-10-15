#!/usr/bin/env python3
"""独立 write/check 入口的契约回归：不得真正调用 Maven，只用 PATH 上的 stub mvn 记录实际命令。

这两个脚本是稳定传输契约生成的开发者入口；它们的失败路径与参数同样属于 CI 门禁本身，因此必须回归。
"""

import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path


def repository_root() -> Path:
    override = os.environ.get("KK_STUDIO_REPO_ROOT")
    if override:
        return Path(override).resolve()
    for directory in Path(__file__).resolve().parents:
        if (directory / ".git").exists():
            return directory
    raise RuntimeError("cannot locate the kk-studio repository root; set KK_STUDIO_REPO_ROOT")


REPOSITORY_ROOT = repository_root()
CAPABILITY_DIR = REPOSITORY_ROOT / "scripts" / "dev" / "verify" / "generated-contracts"
CHECK_SCRIPT = CAPABILITY_DIR / "check.sh"
WRITE_SCRIPT = CAPABILITY_DIR / "write.sh"
ARTIFACT_TEST = "-Dtest=StableTsContractArtifactTest"
UPDATE_PROPERTY = "-Dkkstudio.contract.generation.update=true"


class GeneratedContractsScriptsTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory(prefix="kk-studio-contracts-")
        self.addCleanup(self._tmp.cleanup)
        self.stub_dir = Path(self._tmp.name)
        self.log = self.stub_dir / "mvn.log"
        self._install_mvn_stub()
        self.environment = dict(os.environ)
        self.environment["PATH"] = f"{self.stub_dir}{os.pathsep}{self.environment['PATH']}"
        self.environment["JAVA_HOME_21"] = "/stub/jdk21"
        self.environment.pop("JAVA_HOME", None)
        self.environment["KK_STUDIO_GENERATED_CONTRACTS_LOG"] = str(self.log)
        self.environment["KK_STUDIO_REPO_ROOT"] = str(REPOSITORY_ROOT)

    def _install_mvn_stub(self) -> None:
        stub = self.stub_dir / "mvn"
        stub.write_text(
            "#!/usr/bin/env bash\n"
            "{\n"
            '  printf "JAVA_HOME=%s\\n" "${JAVA_HOME:-}"\n'
            '  printf "ARGS=%s\\n" "$*"\n'
            '} > "${KK_STUDIO_GENERATED_CONTRACTS_LOG:?}"\n',
            encoding="utf-8",
        )
        stub.chmod(stub.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)

    def _logged(self) -> str:
        return self.log.read_text(encoding="utf-8")

    def test_scripts_exist_and_are_executable(self) -> None:
        for script in (CHECK_SCRIPT, WRITE_SCRIPT):
            self.assertTrue(script.is_file(), f"{script} must exist")
            self.assertTrue(os.access(script, os.X_OK), f"{script} must be executable")

    def test_check_runs_the_targeted_read_only_reactor_test(self) -> None:
        result = subprocess.run(
            [str(CHECK_SCRIPT)], env=self.environment, capture_output=True, text=True
        )

        self.assertEqual(0, result.returncode, result.stderr)
        logged = self._logged()
        self.assertIn("JAVA_HOME=/stub/jdk21", logged)
        self.assertIn("-pl web -am", logged)
        self.assertIn(ARTIFACT_TEST, logged)
        self.assertTrue(logged.rstrip().endswith("test"), logged)
        self.assertNotIn(UPDATE_PROPERTY, logged, "check must never rewrite the artifact")

    def test_write_enables_the_explicit_update_property(self) -> None:
        result = subprocess.run(
            [str(WRITE_SCRIPT)], env=self.environment, capture_output=True, text=True
        )

        self.assertEqual(0, result.returncode, result.stderr)
        logged = self._logged()
        self.assertIn(ARTIFACT_TEST, logged)
        self.assertIn(UPDATE_PROPERTY, logged)

    def test_missing_jdk21_fails_before_invoking_maven(self) -> None:
        environment = dict(self.environment)
        environment.pop("JAVA_HOME_21", None)
        environment.pop("JAVA_HOME", None)

        for script in (CHECK_SCRIPT, WRITE_SCRIPT):
            with self.subTest(script=script.name):
                result = subprocess.run(
                    [str(script)], env=environment, capture_output=True, text=True
                )
                self.assertNotEqual(0, result.returncode)
                self.assertIn("JAVA_HOME_21", result.stderr)
                self.assertFalse(self.log.exists(), "maven must not run without a JDK 21")


if __name__ == "__main__":
    unittest.main()
