#!/usr/bin/env python3
"""按用例名核对执行范围测试在指定平台上真的跑过。

数量断言会被新增用例弄陈旧（漏跑和多跑都看不出来），因此这里核对的是「必须执行的用例名集合」：每个用例都必须出现且只
出现一次，核心验收用例还不允许被跳过。
"""

import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path

# 核心验收：只用 JDK 夹具造真实进程层级，任何平台都没有跳过它们的理由。
REQUIRED_CASES = {
    "fun.fengwk.kkstudio.harness.daemon.coding.ProcessScopeCrossPlatformTest": {
        "naturalExitConvergesLiveChildren",
        "terminateConvergesNestedProcesses",
        "unpermittedStartNeverRunsTheFixture",
        "stdinEofLetsTheFixtureExitNaturally",
    },
}

# 其余被选中的用例各自带平台前置条件（POSIX shell、Windows Job 语义），因此只要求真的执行过且没有失败。
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


def parse_report(reports_dir: Path, class_name: str):
    report = reports_dir / ("TEST-fun.fengwk.kkstudio.harness.daemon.coding." + class_name + ".xml")
    if not report.is_file():
        return None
    root = ElementTree.parse(report).getroot()
    counts = {
        "tests": int(root.get("tests", "0")),
        "failures": int(root.get("failures", "0")),
        "errors": int(root.get("errors", "0")),
        "skipped": int(root.get("skipped", "0")),
    }
    cases = [case.get("name") for case in root.iter("testcase")]
    return counts, cases


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: assert-surefire-reports.py <surefire-reports-dir> <os-label>", file=sys.stderr)
        return 2
    reports_dir = Path(sys.argv[1])
    os_label = sys.argv[2]
    failures = []

    def report(class_name: str, counts, cases) -> None:
        print(
            "%-32s tests=%d failures=%d errors=%d skipped=%d"
            % (class_name, counts["tests"], counts["failures"], counts["errors"], counts["skipped"])
        )
        if counts["tests"] == 0:
            failures.append(f"{class_name}: no case really ran on {os_label}")
        if counts["failures"] or counts["errors"]:
            failures.append(f"{class_name}: failing cases on {os_label}")

    for simple_name in SELECTED_CLASSES:
        parsed = parse_report(reports_dir, simple_name)
        if parsed is None:
            failures.append(f"{simple_name}: missing surefire report on {os_label}")
            continue
        counts, cases = parsed
        report(simple_name, counts, cases)
        required = REQUIRED_CASES.get("fun.fengwk.kkstudio.harness.daemon.coding." + simple_name)
        if required is None:
            continue
        if counts["skipped"]:
            failures.append(f"{simple_name}: must not skip any case on {os_label}")
        missing = sorted(required - set(cases))
        if missing:
            failures.append(f"{simple_name}: required cases did not run on {os_label}: {missing}")
        duplicated = sorted({name for name in cases if cases.count(name) > 1})
        if duplicated:
            failures.append(f"{simple_name}: required cases ran more than once on {os_label}: {duplicated}")

    if failures:
        for failure in failures:
            print("FAIL " + failure)
        return 1
    print(f"PASS all selected cases really ran on {os_label}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
