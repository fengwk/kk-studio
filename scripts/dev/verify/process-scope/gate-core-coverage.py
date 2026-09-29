#!/usr/bin/env python3
"""合并报告的门禁：执行范围「父进程 + helper」核心类必须被真正合并进来，且不得低于已记录的下限。

核心类的可达分支并不集中在单个平台上：Windows Job 语义只在 Windows runner 上被执行，非 Linux 分支只在 macOS 上被执行，
Linux 的 `/proc` 收敛判定只在 Linux 上被执行。因此这里的输入必须是三平台合并后的报告：

- 核心类在报告里缺席 ⇒ 合并丢了数据（多半是各平台 class 文件不一致），必须显式失败；
- 行覆盖低于该类的下限 ⇒ 回归，必须显式失败；
- 与 90% 目标的差距每次都打印出来，缺口不会被藏起来，但它本身还不是门禁（目标由后续补齐的失败注入用例抬升）。
"""

import sys
import xml.etree.ElementTree as ElementTree

# 核心类与它们的行覆盖下限：下限是「已经做到的事实」，只能上调不能下调。
CORE_CLASSES = {
    "ProcessScope": 75.0,
    "ProcessScopeHelper": 70.0,
    "PosixProcessGroup": 76.0,
    "ProcessScopeState": 90.0,
}

# 仓库对核心路径的目标；差距每次都要打印，不能被门禁的通过掩盖。
TARGET = 90.0


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: gate-core-coverage.py <jacoco-xml>", file=sys.stderr)
        return 2
    root = ElementTree.parse(sys.argv[1]).getroot()

    coverage = {}
    for package in root.iter("package"):
        for source_file in package.iter("sourcefile"):
            name = source_file.get("name") or ""
            if not name.endswith(".java"):
                continue
            covered = missed = 0
            for line in source_file.iter("line"):
                if int(line.get("ci", "0")) > 0:
                    covered += 1
                elif int(line.get("mi", "0")) > 0:
                    missed += 1
            if missed + covered:
                coverage[name[: -len(".java")]] = (covered, missed)

    failures = []
    for name, floor in CORE_CLASSES.items():
        if name not in coverage:
            failures.append(f"{name}: missing from the merged report (merged data was dropped)")
            continue
        covered, missed = coverage[name]
        percentage = 100.0 * covered / (covered + missed)
        gap = max(0.0, (TARGET - percentage) / 100.0 * (covered + missed))
        print(
            "%-22s line %5.1f%% (%d/%d)  下限 %.0f%%  距 %.0f%% 目标还差约 %d 行"
            % (name, percentage, covered, covered + missed, floor, TARGET, round(gap))
        )
        if percentage < floor:
            failures.append(f"{name}: {percentage:.1f}% is below the recorded floor {floor:.0f}%")

    core_only = [coverage[name] for name in CORE_CLASSES if name in coverage]
    aggregate = (
        sum(covered for covered, _ in core_only),
        sum(missed for _, missed in core_only),
    )
    total = aggregate[0] + aggregate[1]
    if total == 0:
        failures.append("the merged report contains no source file at all")
    else:
        print(
            "%-22s line %5.1f%% (%d/%d)  距 %.0f%% 目标还差约 %d 行"
            % (
                "核心类合计",
                100.0 * aggregate[0] / total,
                aggregate[0],
                total,
                TARGET,
                round(max(0.0, (TARGET - 100.0 * aggregate[0] / total) / 100.0 * total)),
            )
        )

    if failures:
        for failure in failures:
            print("FAIL " + failure)
        return 1
    print("PASS every core class is present in the merged report and above its floor")
    return 0


if __name__ == "__main__":
    sys.exit(main())
