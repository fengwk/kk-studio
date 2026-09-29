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
        # 双向标准流：命令真的收到调用方写进 stdin 的字节，stderr 不被合并，且根进程自然退出后活着的子进程被收敛。
        "duplexStdioCarriesInputAndKeepsStderrSeparate",
        "duplexStdioConvergesLiveChildrenAfterNaturalExit",
    },
}

# Windows 腿仍然要有「用真正的 Git Bash 跑通命令执行」的实证：BashCapabilityTest 自己显式定位 Git Bash（runner 上就是
# workflow 的 `shell: bash` 用的那个），因此下面这两条在 Windows 上必须真跑且不得跳过——一条证明命令的 stdin 是确定性
# EOF，一条证明超时预算不会因为算术溢出退化成立即超时。它们不依赖 POSIX 的额外语义，因此在 Windows 上也没有跳过的理由。
WINDOWS_REQUIRED_CASES = {
    "fun.fengwk.kkstudio.harness.daemon.coding.BashCapabilityTest": {
        "closesStdinSoCommandsWaitingForEofFinishNaturally",
        "unrepresentableTimeoutBudgetDoesNotDegradeIntoImmediateTimeout",
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
    "WindowsCommandLineTest",
    "WindowsJobScopeTest",
)


# 这两个遗留编码能力夹具用裸名 `bash` 作为命令，Windows 上按 CreateProcess 的搜索顺序只会命中 System32 的 WSL 转发程序
# （runner 自带 Git Bash，但裸名先命中系统目录），因此它们不属于 Windows 腿的执行范围：它们仍由 Linux 完整套件与 macOS
# 覆盖。执行范围自己的原生用例与显式定位 Git Bash 的 BashCapabilityTest 照常在 Windows 上验收。
WINDOWS_INAPPLICABLE_CLASSES = (
    "CodingCapabilitiesTest",
    "CodingCapabilitiesEdgeTest",
)


def required_classes(os_label: str):
    """该平台必须真的执行过的类：Windows 上排除只依赖 POSIX shell 的编码能力夹具。"""
    if os_label.startswith("windows"):
        return tuple(
            class_name
            for class_name in SELECTED_CLASSES
            if class_name not in WINDOWS_INAPPLICABLE_CLASSES
        )
    return SELECTED_CLASSES


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
    skipped = {
        case.get("name") for case in root.iter("testcase") if case.find("skipped") is not None
    }
    return counts, cases, skipped


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

    for simple_name in required_classes(os_label):
        parsed = parse_report(reports_dir, simple_name)
        if parsed is None:
            failures.append(f"{simple_name}: missing surefire report on {os_label}")
            continue
        counts, cases, skipped = parsed
        report(simple_name, counts, cases)
        qualified = "fun.fengwk.kkstudio.harness.daemon.coding." + simple_name
        strict = REQUIRED_CASES.get(qualified)
        platform_cases = WINDOWS_REQUIRED_CASES.get(qualified) if os_label.startswith("windows") else None
        if strict is None and not platform_cases:
            continue
        if strict is not None:
            # 这一类是核心验收：它的每个用例在任何平台上都没有跳过的理由。
            if counts["skipped"]:
                failures.append(f"{simple_name}: must not skip any case on {os_label}")
            required = strict
        else:
            # 这一类整体允许跳过平台不适用用例，但被点名的那几条必须真的跑过。
            required = platform_cases
            skipped_required = sorted(platform_cases & skipped)
            if skipped_required:
                failures.append(
                    f"{simple_name}: required cases must not be skipped on {os_label}: {skipped_required}"
                )
        missing = sorted(required - set(cases))
        if missing:
            failures.append(f"{simple_name}: required cases did not run on {os_label}: {missing}")

    if failures:
        for failure in failures:
            print("FAIL " + failure)
        return 1
    print(f"PASS all selected cases really ran on {os_label}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
