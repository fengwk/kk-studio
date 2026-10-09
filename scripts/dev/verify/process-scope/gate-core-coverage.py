#!/usr/bin/env python3
"""合并报告的门禁：执行范围的核心路径必须整体达到 90% 行覆盖。

核心路径不是一两个类，而是「让命令在 OS 级执行范围里跑起来并在各种去向都收敛」的整条链路：父进程侧的编排、helper 侧的
派生与收敛、两侧共用的原语、Windows Job 实现、Windows 命令行拼装，以及 bash 能力本身。把 Windows 实现或 bash 能力留在
集合外，等于用「最关键的平台没有数字」换取门禁通过，因此这里把它们一并纳入。

门禁是**核心集合的合计**（仓库对核心路径的目标），不是每个类各自 90%：同类内不同文件的可达性差异很大，逐类阈值会把
不可达分支当成失败信号。每个类的数字都会打印出来，缺口不会被藏起来。

只有平台专属分支会在对应平台上被执行（Windows Job 语义、macOS 的非 Linux 分支、Linux 的 `/proc` 判定），因此输入必须是
三平台合并后的报告：核心类在报告里缺席就意味着合并丢了数据，必须显式失败，而不是当成「没有这个类」。

Daemon 侧单 owner 协调器另有一条独立门禁：它必须在合并报告里出现，且自身行覆盖达到同一目标；它不并入核心合计，避免
协调器的高覆盖稀释原七类门禁的信号，反之亦然。
"""

import sys
import xml.etree.ElementTree as ElementTree

# 核心路径：执行范围编排、helper 派生/收敛、共用原语、Windows 实现、命令行拼装与 bash 能力。
CORE_CLASSES = (
    "ProcessScope",
    "ProcessScopeHelper",
    "PosixProcessSession",
    "ProcessScopeState",
    "WindowsJobScope",
    "WindowsCommandLine",
    "BashCapability",
)

# Daemon 侧单 owner 协调器独立门禁：它必须出现在合并报告里且自身行覆盖达标，但不并入上面的核心合计，
# 否则协调器会稀释原七类门禁的信号。
COORDINATOR_CLASS = "TerminalCoordinator"

# 仓库对核心路径的目标。
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
            if covered + missed:
                coverage[name[: -len(".java")]] = (covered, missed)

    failures = []
    aggregate_covered = aggregate_total = 0
    for name in CORE_CLASSES:
        if name not in coverage:
            failures.append(f"{name}: missing from the merged report (merged data was dropped)")
            continue
        covered, missed = coverage[name]
        total = covered + missed
        aggregate_covered += covered
        aggregate_total += total
        print(
            "%-22s line %5.1f%% (%d/%d)"
            % (name, 100.0 * covered / total, covered, total)
        )

    if aggregate_total == 0:
        print("FAIL the merged report contains no core class at all")
        return 1

    # 协调器独立门禁：缺失、零计数与低于目标都显式失败，且不并入下面的核心合计。
    coordinator = coverage.get(COORDINATOR_CLASS)
    if coordinator is None:
        failures.append(
            f"{COORDINATOR_CLASS}: missing from the merged report (merged data was dropped)"
        )
    else:
        coordinator_covered, coordinator_missed = coordinator
        coordinator_total = coordinator_covered + coordinator_missed
        coordinator_percentage = 100.0 * coordinator_covered / coordinator_total
        print(
            "%-22s line %5.1f%% (%d/%d)"
            % (
                COORDINATOR_CLASS,
                coordinator_percentage,
                coordinator_covered,
                coordinator_total,
            )
        )
        if coordinator_covered == 0:
            failures.append(
                f"{COORDINATOR_CLASS}: no lines were executed in the merged report"
            )
        elif coordinator_percentage < TARGET:
            failures.append(
                "%s line coverage %.1f%% is below %.1f%%"
                % (COORDINATOR_CLASS, coordinator_percentage, TARGET)
            )

    percentage = 100.0 * aggregate_covered / aggregate_total
    summary = "核心路径合计            line %5.1f%% (%d/%d)" % (
        percentage,
        aggregate_covered,
        aggregate_total,
    )
    if percentage < TARGET:
        # 达标时不打印缺口：那会读成「还差一点」。
        summary += "  距 %.1f%% 目标还差约 %d 行" % (
            TARGET,
            round((TARGET - percentage) / 100.0 * aggregate_total),
        )
    print(summary)
    if failures:
        for failure in failures:
            print("FAIL " + failure)
        return 1
    if percentage < TARGET:
        print("FAIL core path line coverage %.1f%% is below %.1f%%" % (percentage, TARGET))
        return 1
    print("PASS core path line coverage %.1f%% >= %.0f%%" % (percentage, TARGET))
    return 0


if __name__ == "__main__":
    sys.exit(main())
