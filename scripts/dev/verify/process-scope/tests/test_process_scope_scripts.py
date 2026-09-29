"""Permanent guards for the process scope verification scripts.

这些脚本是执行范围的 CI 门禁本身：断言必须按用例名核对（数量断言会被新增用例弄陈旧），覆盖率门禁必须把 Windows 实现与
bash 能力算进核心集合、并且对「合并丢了数据」显式失败。因此它们的失败路径同样需要回归，而不是只在 CI 上被偶然检验。
"""

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

PACKAGE = "fun.fengwk.kkstudio.harness.daemon.coding"
REQUIRED_CASES = (
    "naturalExitConvergesLiveChildren",
    "terminateConvergesNestedProcesses",
    "unpermittedStartNeverRunsTheFixture",
    "stdinEofLetsTheFixtureExitNaturally",
)
SELECTED_CLASSES = (
    "ProcessScopeCrossPlatformTest",
    "ProcessScopeTest",
    "ProcessScopeStateTest",
    "ProcessScopeHelperFailureTest",
    "PosixProcessGroupTest",
    "BashCapabilityTest",
    "CodingCapabilitiesTest",
    "CodingCapabilitiesEdgeTest",
    "ProcessTreeTest",
    "WindowsCommandLineTest",
    "WindowsJobScopeTest",
)
WINDOWS_INAPPLICABLE = (
    "CodingCapabilitiesTest",
    "CodingCapabilitiesEdgeTest",
)
CORE_CLASSES = (
    "ProcessScope",
    "ProcessScopeHelper",
    "PosixProcessGroup",
    "ProcessScopeState",
    "WindowsJobScope",
    "WindowsCommandLine",
    "BashCapability",
)


def repository_root():
    override = os.environ.get("KK_STUDIO_REPO_ROOT")
    if override:
        return Path(override).resolve()
    for candidate in Path(__file__).resolve().parents:
        if (candidate / ".git").exists():
            return candidate
    raise RuntimeError("cannot locate the kk-studio worktree root; set KK_STUDIO_REPO_ROOT")


REPOSITORY_ROOT = repository_root()
SCRIPTS = REPOSITORY_ROOT / "scripts" / "dev" / "verify" / "process-scope"


def run_script(name, *arguments):
    return subprocess.run(
        [sys.executable, str(SCRIPTS / name), *[str(argument) for argument in arguments]],
        capture_output=True,
        text=True,
        check=False,
    )


def write_surefire_report(directory, class_name, cases, failures=0, errors=0, skipped=0):
    body = "".join(
        '<testcase name="%s" classname="%s.%s"/>' % (case, PACKAGE, class_name)
        for case in cases
    )
    report = (
        '<testsuite name="%s.%s" tests="%d" failures="%d" errors="%d" skipped="%d">%s</testsuite>'
        % (PACKAGE, class_name, len(cases), failures, errors, skipped, body)
    )
    (directory / f"TEST-{PACKAGE}.{class_name}.xml").write_text(report, encoding="utf-8")


def write_complete_reports(directory, cross_platform_cases=REQUIRED_CASES, **overrides):
    for class_name in SELECTED_CLASSES:
        cases = (
            cross_platform_cases
            if class_name == "ProcessScopeCrossPlatformTest"
            else ("someCase",)
        )
        write_surefire_report(
            directory,
            class_name,
            cases,
            **overrides.get(class_name, {}),
        )


class AssertSurefireReportsTest(unittest.TestCase):
    """按用例名核对：数量正确但用例不对、被跳过、失败，都必须让门禁失败。"""

    def test_passes_when_every_selected_class_really_ran(self):
        with tempfile.TemporaryDirectory() as tmp:
            reports = Path(tmp)
            write_complete_reports(reports)
            result = run_script("assert-surefire-reports.py", reports, "test-os")
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("PASS all selected cases really ran on test-os", result.stdout)

    def test_windows_runner_does_not_require_the_posix_shell_only_classes(self):
        """Windows 上没有 Git Bash：只依赖 POSIX shell 的编码能力夹具不属于该平台的执行范围。"""
        with tempfile.TemporaryDirectory() as tmp:
            reports = Path(tmp)
            write_complete_reports(reports)
            for class_name in WINDOWS_INAPPLICABLE:
                (reports / f"TEST-{PACKAGE}.{class_name}.xml").unlink()
            result = run_script(
                "assert-surefire-reports.py", reports, "windows-latest"
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

            strict = run_script("assert-surefire-reports.py", reports, "macos-latest")
            self.assertEqual(1, strict.returncode, strict.stdout + strict.stderr)
            self.assertIn(
                f"{WINDOWS_INAPPLICABLE[0]}: missing surefire report on macos-latest",
                strict.stdout,
            )

    def test_fails_when_a_required_case_is_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            reports = Path(tmp)
            write_complete_reports(reports, cross_platform_cases=REQUIRED_CASES[:-1])
            result = run_script("assert-surefire-reports.py", reports, "test-os")
            self.assertEqual(1, result.returncode)
            self.assertIn(REQUIRED_CASES[-1], result.stdout)

    def test_fails_when_a_required_case_is_skipped_even_if_the_count_matches(self):
        with tempfile.TemporaryDirectory() as tmp:
            reports = Path(tmp)
            write_complete_reports(
                reports,
                **{"ProcessScopeCrossPlatformTest": {"skipped": 1}},
            )
            result = run_script("assert-surefire-reports.py", reports, "test-os")
            self.assertEqual(1, result.returncode)
            self.assertIn("must not skip any case", result.stdout)

    def test_fails_when_the_count_is_right_but_the_cases_are_not(self):
        with tempfile.TemporaryDirectory() as tmp:
            reports = Path(tmp)
            write_complete_reports(
                reports,
                cross_platform_cases=("caseA", "caseB", "caseC", "caseD"),
            )
            result = run_script("assert-surefire-reports.py", reports, "test-os")
            self.assertEqual(1, result.returncode)
            self.assertIn("required cases did not run", result.stdout)

    def test_fails_when_a_selected_class_executed_nothing_or_failed(self):
        with tempfile.TemporaryDirectory() as tmp:
            reports = Path(tmp)
            write_complete_reports(reports, **{"BashCapabilityTest": {"failures": 1}})
            result = run_script("assert-surefire-reports.py", reports, "test-os")
            self.assertEqual(1, result.returncode)
            self.assertIn("failing cases", result.stdout)

    def test_fails_when_a_selected_report_is_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            reports = Path(tmp)
            write_complete_reports(reports)
            (reports / f"TEST-{PACKAGE}.ProcessTreeTest.xml").unlink()
            result = run_script("assert-surefire-reports.py", reports, "test-os")
            self.assertEqual(1, result.returncode)
            self.assertIn("missing surefire report", result.stdout)


class CollectCoverageInputsTest(unittest.TestCase):
    """合并前必须证明三平台的类文件一致，否则 JaCoCo 会按 class id 静默丢 session。"""

    def collect(self, platforms):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "platforms"
            root.mkdir()
            for name, content in platforms.items():
                directory = root / name
                (directory / "jacoco-helper").mkdir(parents=True)
                (directory / "classes").mkdir()
                (directory / "classes" / "Sample.class").write_bytes(content)
                (directory / "jacoco.exec").write_bytes(b"parent")
                (directory / "jacoco-helper" / "helper.exec").write_bytes(b"helper")
            return run_script("collect-coverage-inputs.py", root, Path(tmp) / "inputs")

    def test_passes_when_three_platforms_agree(self):
        result = self.collect({"a": b"class", "b": b"class", "c": b"class"})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("identical on every platform", result.stdout)

    def test_fails_when_class_bytes_differ(self):
        result = self.collect({"a": b"class", "b": b"class", "c": b"other"})
        self.assertEqual(1, result.returncode)
        self.assertIn("differ across platforms", result.stdout)

    def test_fails_when_a_platform_artifact_is_incomplete(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "platforms"
            root.mkdir()
            for name in ("a", "b", "c"):
                directory = root / name
                directory.mkdir()
                (directory / "jacoco.exec").write_bytes(b"parent")
                (directory / "classes").mkdir()
            result = run_script("collect-coverage-inputs.py", root, Path(tmp) / "inputs")
            self.assertEqual(1, result.returncode)
            self.assertIn("no helper exec data", result.stdout)

    def test_fails_when_the_platform_count_is_wrong(self):
        result = self.collect({"a": b"class", "b": b"class"})
        self.assertEqual(1, result.returncode)
        self.assertIn("expected three platform artifacts", result.stdout)


def write_jacoco_report(path, coverage):
    sources = []
    for name, (covered, missed) in coverage.items():
        lines = [
            f'<line nr="{index}" mi="0" ci="1"/>' for index in range(covered)
        ] + [
            f'<line nr="{covered + index}" mi="1" ci="0"/>' for index in range(missed)
        ]
        sources.append(
            '<sourcefile name="%s.java">%s</sourcefile>' % (name, "".join(lines))
        )
    path.write_text(
        '<report name="merged"><package name="%s">%s</package></report>'
        % (PACKAGE.replace(".", "/"), "".join(sources)),
        encoding="utf-8",
    )


def core_coverage(total_per_class=200, covered_per_class=185):
    return {name: (covered_per_class, total_per_class - covered_per_class) for name in CORE_CLASSES}


class GateCoreCoverageTest(unittest.TestCase):
    """门禁必须覆盖整条核心路径：漏类即失败，合计不足 90% 即失败，且必须逐个类打印数字。"""

    def gate(self, coverage):
        with tempfile.TemporaryDirectory() as tmp:
            report = Path(tmp) / "coverage.xml"
            write_jacoco_report(report, coverage)
            return run_script("gate-core-coverage.py", report)

    def test_passes_above_the_target_and_lists_every_class(self):
        result = self.gate(core_coverage())
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        for name in CORE_CLASSES:
            self.assertIn(name, result.stdout)
        self.assertIn("PASS core path line coverage", result.stdout)

    def test_fails_below_the_target(self):
        result = self.gate(core_coverage(total_per_class=200, covered_per_class=170))
        self.assertEqual(1, result.returncode)
        self.assertIn("is below 90.0%", result.stdout)

    def test_fails_when_a_core_class_is_missing_from_the_merged_report(self):
        coverage = core_coverage()
        del coverage["WindowsJobScope"]
        result = self.gate(coverage)
        self.assertEqual(1, result.returncode)
        self.assertIn("WindowsJobScope", result.stdout)
        self.assertIn("merged data was dropped", result.stdout)

    def test_fails_when_a_perfect_class_cannot_offset_the_missing_one(self):
        coverage = core_coverage()
        coverage["WindowsCommandLine"] = (500, 0)
        del coverage["BashCapability"]
        result = self.gate(coverage)
        self.assertEqual(1, result.returncode)


if __name__ == "__main__":
    unittest.main()
