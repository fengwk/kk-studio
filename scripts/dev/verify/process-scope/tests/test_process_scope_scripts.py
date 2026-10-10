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
DAEMON_PACKAGE = "fun.fengwk.kkstudio.harness.daemon"
PROCESS_PACKAGE = "fun.fengwk.kkstudio.harness.daemon.process"
TERMINAL_PACKAGE = "fun.fengwk.kkstudio.harness.daemon.terminal"
# Daemon 运行时协议/生命周期与真实 server 桥接集成测试位于 daemon 根包；它们不属于 coding 子包。
DAEMON_CLASSES = frozenset(
    {
        "DaemonRuntimeTest",
        "DaemonRuntimeShellTest",
        "DaemonTerminalServerIntegrationTest",
    }
)
# 进程基座类已经整体迁到 daemon.process；终端内核与 launch 规格在 daemon.terminal；其余被选中的类仍在 daemon.coding。
PROCESS_CLASSES = frozenset(
    {
        "ProcessScopeTest",
        "ProcessScopeCrossPlatformTest",
        "ProcessScopeStateTest",
        "ProcessScopeHelperFailureTest",
        "ProcessScopePtyIntegrationTest",
        "ProcessScopeInteractivePtyTest",
        "ProcessScopeConPtyIntegrationTest",
        "PosixProcessSessionTest",
        "PosixProcessSignalOrderTest",
        "WindowsCommandLineTest",
        "WindowsJobScopeTest",
    }
)
TERMINAL_CLASSES = frozenset(
    {
        "TerminalKernelTest",
        "TerminalKernelSchedulingTest",
        "TerminalSnapshotProjectorTest",
        "HeadlessTerminalDisplayTest",
        "TerminalLaunchSpecTest",
        "TerminalViewStreamTest",
        "TerminalRuntimeDeterministicTest",
        "TerminalRuntimeRealPtyTest",
        "TerminalWriterTest",
        "TerminalCoordinatorTest",
    }
)


def package_of(class_name):
    if class_name in DAEMON_CLASSES:
        return DAEMON_PACKAGE
    if class_name in PROCESS_CLASSES:
        return PROCESS_PACKAGE
    if class_name in TERMINAL_CLASSES:
        return TERMINAL_PACKAGE
    return PACKAGE


REQUIRED_CASES = (
    "naturalExitConvergesLiveChildren",
    "terminateConvergesNestedProcesses",
    "unpermittedStartNeverRunsTheFixture",
    "stdinEofLetsTheFixtureExitNaturally",
    "duplexStdioCarriesInputAndKeepsStderrSeparate",
    "duplexStdioConvergesLiveChildrenAfterNaturalExit",
)
SELECTED_CLASSES = (
    "ProcessScopeCrossPlatformTest",
    "ProcessScopeTest",
    "ProcessScopeStateTest",
    "ProcessScopeHelperFailureTest",
    "PosixProcessSessionTest",
    "PosixProcessSignalOrderTest",
    "ProcessScopePtyIntegrationTest",
    "ProcessScopeInteractivePtyTest",
    "ProcessScopeConPtyIntegrationTest",
    "BashCapabilityTest",
    "CodingCapabilitiesTest",
    "CodingCapabilitiesEdgeTest",
    "WindowsCommandLineTest",
    "WindowsJobScopeTest",
    "TerminalKernelTest",
    "TerminalKernelSchedulingTest",
    "TerminalSnapshotProjectorTest",
    "HeadlessTerminalDisplayTest",
    "TerminalLaunchSpecTest",
    "TerminalViewStreamTest",
    "TerminalRuntimeDeterministicTest",
    "TerminalRuntimeRealPtyTest",
    "TerminalWriterTest",
    "TerminalCoordinatorTest",
    "DaemonRuntimeTest",
    "DaemonRuntimeShellTest",
    "DaemonTerminalServerIntegrationTest",
)
WINDOWS_INAPPLICABLE = (
    "CodingCapabilitiesTest",
    "CodingCapabilitiesEdgeTest",
)
# Linux/macOS 腿必须真跑的交互式 shell 验收用例。
INTERACTIVE_CASES = (
    "interactiveBashRunsWithJobControlAndMultiProcessGroupSession",
    "controlCTerminatesOnlyTheForegroundJob",
    "controlZThenBackgroundResumesTheJobProcessGroup",
    "naturalExitConvergesBackgroundJobsIncludingTermIgnoringOnes",
)
# Windows 腿必须真跑的 ConPTY 验收用例。
CONPTY_CASES = (
    "conPtyIsUsedAndNativeWindowSizeFollowsResize",
    "conPtySessionConvergesAChildThatOutlivesTheCommand",
)
MACOS_CASES = (
    "memberEnumerationUsesRealKernelQueriesOrReportsUndecidable",
    "macEnumerationIgnoresVanishedPidsAndPreservesMemberIdentity",
    "macEnumerationPreservesKernelPermissionBoundary",
)
PTY_CASES = (
    "ptyCarriesCommandOutputAndKeepsTheExactExitCode",
    "ptyRunsAJvmFixtureAndKeepsItsExitCode",
    "ptyUnpermittedStartNeverRunsTheCommand",
    "ptyRejectsNonPositiveInitialSize",
    "ptyRejectsInvalidLaunchWithoutLeakingScopeState",
    "ptyRejectsNonPositiveResizeWithoutEndingScope",
    "pipeScopeRejectsResizeWithoutEndingScope",
)
# 发信号前的父子排序是纯逻辑：三平台都必须真跑全部用例。
SIGNAL_ORDER_CASES = (
    "ordersScrambledInputParentsBeforeChildren",
    "ordersNestedForestParentsBeforeChildren",
    "ordersWrappingNumericPidsParentsBeforeChildren",
    "emptyInputYieldsEmptyOrder",
    "duplicatePidsAreRejected",
    "parentCyclesAreRejected",
)
WINDOWS_REQUIRED_BASH_CASES = (
    "closesStdinSoCommandsWaitingForEofFinishNaturally",
    "unrepresentableTimeoutBudgetDoesNotDegradeIntoImmediateTimeout",
)
# 终端基座的核心用例：内核数值投影/模式版本、分块调度/预算/满队列/关闭、数值投影、headless Display 与 launch 规格
# 解析都是确定性用例，三平台都必须真跑且不得跳过。
TERMINAL_KERNEL_CASES = (
    "asciiProjectsNumericSlotsAndPaddedEmptyTail",
    "inputModesAndRevisionTrackOnlyModeOrSizeChanges",
    "resizingHashCollisionDimensionsStillAdvancesRevision",
    "identicalBlankScrollMovesObservedScreenIdIntoHistoryAndAllocatesNewRowId",
    "heightChangeAndBufferRoundTripBetweenCapturesStillResetIdentity",
)
TERMINAL_SCHEDULING_CASES = (
    "snapshotBeforePartialCsiSuffixThenSuffixStillApplies",
    "snapshotCompletesBeforePartialOscSuffix",
    "utf8TrailingBytesSurviveControlAndResizeBoundaries",
    "fullEventQueueRejectsExplicitly",
    "readBudgetOverrunFailsExplicitlyIncludingSynchronizedOutput",
    "closeOnEmptyReadTerminatesAndKeepsExecutorOwnedByCaller",
)
TERMINAL_PROJECTOR_CASES = (
    "styledNulStaysUnitAndMissingTailIsDefaultEmpty",
    "loneSurrogateIsPreservedAsNumericUnit",
    "dwcContinuationAndOverflowTruncationAreExplicit",
    "styleProjectionMapsColorsOptionsAndNull",
    "everyPublicDisplayModeProjectsWithoutRenamingOrDroppingValues",
    "hundredsOfBlankScrollsTrimReferencesAndNeverGuessTextOverlap",
    "alternateCaptureRetainsOnlyActiveRowsAndRestoredMainGetsNewIds",
)
HEADLESS_DISPLAY_CASES = ("recordsDisplayStateAndIgnoresHeadlessOperations",)
TERMINAL_LAUNCH_CASES = (
    "resolvesExplicitSpecAndPreservesArgvExactly",
    "unixDefaultsToResolvableShellThenSh",
    "unresolvedExecutableFailsClosedWithoutEchoingCommand",
    "windowsSelectsFirstResolvableShellWithoutRuntimeFallback",
)
TERMINAL_VIEW_STREAM_CASES = (
    "newStreamEmitsVersionOneResetAndHasNoBaselineUntilAck",
    "ackRequiresExactStreamIdAndInflightVersion",
    "inflightBlocksFurtherOffersAndIgnoresLatestView",
    "realKernelScrollProducesBoundedPatchesWithTrim",
    "decreasingInputModeRevisionIsRejected",
    "closeAfterAckReleasesBaselineAndKeepsVersionHistory",
)
TERMINAL_RUNTIME_CASES = (
    "staleInputModeIsRejectedWithoutWritingAndWithoutEndingTheSession",
    "partialWriteIsOutcomeUnknownAndEndsTheSession",
    "writeTimeoutIsOutcomeUnknownAndEndsTheSession",
    "closeIsIdempotentTerminatesPendingOperationsAndLeavesExecutorsRunning",
    "deadlineThatFiresBeforeNativeAdmissionKeepsTheFrameUnwritten",
    "cancelWinningBeforeNativeAdmissionKeepsTheFrameUnwritten",
    "closeWinningBeforeNativeAdmissionKeepsTheFrameUnwritten",
    "splitUtf8AndCsiChunksKeepSnapshotAndResizeResponsive",
)
TERMINAL_RUNTIME_PTY_CASES = (
    "userWriteReachesTheCommandAndItsOutputReturnsThroughTheKernel",
    "naturalExitPreservesFinalViewAndExitCode",
    "startDeclaresTermAndTrueColorToTheCommand",
    "repeatedCloseIsIdempotentAfterNaturalExit",
)
TERMINAL_WRITER_CASES = (
    "acceptedOnceThenPendingThenConfirmed",
    "pendingOperationMakesRotationsBusy",
    "takeoverUsesEpochCasAndLateTakeoverCannotReclaim",
    "takeoverResetsEpochDedupWatermarks",
    "recoverResetsEpochWatermarksAndOldCompletionDoesNotAffectNewEpoch",
    "expiredGrantWithPendingIsBusyThenRecoversRealOutcome",
    "grantedReplayIsRevalidatedAfterExpiry",
    "renewedReplayIsRevalidatedAndDoesNotExtendLease",
    "requestConflictDoesNotPolluteFirstRequestReplay",
    "grantedReplayAfterFreezeIsFrozenAndStateHidesAuthority",
)
# Daemon 侧单 owner 协调器的核心用例：启动/迟到 runtime 收敛、owner 失效与重绑、view credit、有界 mailbox、
# writer/输入围栏与 snapshot 失败，都是确定性或用真实 PTY 的用例，三平台都必须真跑且不得跳过。
TERMINAL_COORDINATOR_CASES = (
    "openStartsSingleSessionAndAttaches",
    "lateReturnedTerminatedRuntimeStillConverges",
    "shutdownDuringHandedInStartStopsRuntimeAndConverges",
    "ownerFatalThenLiveRuntimeReturnsStopsWithoutOwnerCallback",
    "realPtyInputReachesCommandAndOutputReturns",
    "realPtyNaturalExitPreservesExitCodeAndFinalScreen",
    "realPtyDisconnectKeepsShellAliveAndReattaches",
    "productionConstructorStartsRealRuntime",
    "ownerRejectionAfterAttachFailsCoordinatorAndReleasesStreams",
    "bindDifferentEnvironmentStopsOldSessionAndRebinds",
    "slowObserverIsRevokedByViewAppliedTimeout",
    "mailboxOverflowRejectsImmediatelyWithoutDroppingReceived",
    "mailboxByteBudgetBoundsLargeInputsBeforeCapacity",
    "takeoverAndReleaseTransferSingleWriterWithEpochCas",
    "inputAdmissionRejectsNonOwnerAndSeqGap",
    "staleGenerationReceiveHasNoSideEffect",
    "snapshotFailureEmitsFixedErrorAndRejectsLaterAttach",
)
# Daemon 运行时生命周期与真实 server 协议桥接：无平台前置条件，三平台都必须真跑且不得跳过。
DAEMON_SHELL_CASES = (
    "bindGateHoldsReadyUntilBindCompletes",
    "bindBackpressureClosesHandshakeAndRecovers",
    "staleWelcomeDoesNotOverwriteNewBinding",
    "readySendFailureClosesHandshakeAndReconnects",
    "mailboxOverflowEmitsBackpressureNotExecuted",
    "shutdownFailureIsExplicitAndConvergesResources",
    "concurrentCloseIsIdempotent",
)
DAEMON_INTEGRATION_CASES = ("fullShellLifecycleOverRealServerAndPty",)
CORE_CLASSES = (
    "ProcessScope",
    "ProcessScopeHelper",
    "PosixProcessSession",
    "ProcessScopeState",
    "WindowsJobScope",
    "WindowsCommandLine",
    "BashCapability",
)
# 协调器是独立门禁：必须出现且自身 >=90%，不并入上面的核心合计。
COORDINATOR_CLASS = "TerminalCoordinator"


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


def run_script(name, *arguments, cwd=None):
    return subprocess.run(
        [sys.executable, str(SCRIPTS / name), *[str(argument) for argument in arguments]],
        capture_output=True,
        text=True,
        check=False,
        cwd=cwd,
    )


def write_surefire_report(
    directory, class_name, cases, failures=0, errors=0, skipped=0, skipped_cases=()
):
    package = package_of(class_name)
    body = "".join(
        '<testcase name="%s" classname="%s.%s">%s</testcase>'
        % (
            case,
            package,
            class_name,
            "<skipped/>" if case in skipped_cases else "",
        )
        for case in cases
    )
    report = (
        '<testsuite name="%s.%s" tests="%d" failures="%d" errors="%d" skipped="%d">%s</testsuite>'
        % (package, class_name, len(cases), failures, errors, skipped, body)
    )
    (directory / f"TEST-{package}.{class_name}.xml").write_text(report, encoding="utf-8")


def write_complete_reports(
    directory, cross_platform_cases=REQUIRED_CASES, bash_cases=WINDOWS_REQUIRED_BASH_CASES, **overrides
):
    for class_name in SELECTED_CLASSES:
        if class_name == "ProcessScopeCrossPlatformTest":
            cases = cross_platform_cases
        elif class_name == "BashCapabilityTest":
            # Windows 上点名必跑的那两条在这里出现；其余用例按平台前置条件可能被跳过。
            cases = tuple(bash_cases) + ("posixOnlyCase",)
        elif class_name == "ProcessScopeInteractivePtyTest":
            cases = INTERACTIVE_CASES
        elif class_name == "ProcessScopeConPtyIntegrationTest":
            cases = CONPTY_CASES
        elif class_name == "ProcessScopePtyIntegrationTest":
            cases = PTY_CASES
        elif class_name == "PosixProcessSignalOrderTest":
            cases = SIGNAL_ORDER_CASES
        elif class_name == "WindowsJobScopeTest":
            cases = ("realKernelReportsMissingExecutableWorkdirAndJobName",)
        elif class_name == "PosixProcessSessionTest":
            cases = MACOS_CASES
        elif class_name == "TerminalKernelTest":
            cases = TERMINAL_KERNEL_CASES + ("someOtherKernelCase",)
        elif class_name == "TerminalKernelSchedulingTest":
            cases = TERMINAL_SCHEDULING_CASES + ("someOtherSchedulingCase",)
        elif class_name == "TerminalSnapshotProjectorTest":
            cases = TERMINAL_PROJECTOR_CASES + ("someOtherProjectorCase",)
        elif class_name == "HeadlessTerminalDisplayTest":
            cases = HEADLESS_DISPLAY_CASES
        elif class_name == "TerminalLaunchSpecTest":
            cases = TERMINAL_LAUNCH_CASES + ("someOtherLaunchCase",)
        elif class_name == "TerminalViewStreamTest":
            cases = TERMINAL_VIEW_STREAM_CASES
        elif class_name == "TerminalRuntimeDeterministicTest":
            cases = TERMINAL_RUNTIME_CASES
        elif class_name == "TerminalRuntimeRealPtyTest":
            cases = TERMINAL_RUNTIME_PTY_CASES
        elif class_name == "TerminalWriterTest":
            cases = TERMINAL_WRITER_CASES
        elif class_name == "TerminalCoordinatorTest":
            cases = TERMINAL_COORDINATOR_CASES + ("someOtherCoordinatorCase",)
        elif class_name == "DaemonRuntimeShellTest":
            cases = DAEMON_SHELL_CASES + ("someOtherShellCase",)
        elif class_name == "DaemonTerminalServerIntegrationTest":
            cases = DAEMON_INTEGRATION_CASES
        else:
            cases = ("someCase",)
        write_surefire_report(
            directory,
            class_name,
            cases,
            **overrides.get(class_name, {}),
        )


class CollectCoverageLinksTest(unittest.TestCase):
    """收集与合并前置条件：三平台的 class 必须一致，且收集出来的 class 链接必须真的可用。"""

    @staticmethod
    def write_platforms(platforms):
        for name in ("ubuntu-latest", "macos-latest", "windows-latest"):
            platform = platforms / ("scope-coverage-" + name)
            (platform / "jacoco-helper").mkdir(parents=True)
            (platform / "jacoco.exec").write_bytes(b"parent")
            (platform / "jacoco-helper" / "helper.exec").write_bytes(b"helper")
            (platform / "classes" / "fun").mkdir(parents=True)
            (platform / "classes" / "fun" / "Sample.class").write_bytes(b"same-bytes")

    def test_collected_class_link_resolves_with_relative_arguments(self):
        """按 CI 的调用方式（相对路径 + 工作目录）收集：链接必须是可解析的真实目录，否则报告步骤会断链。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write_platforms(root / "platforms")
            result = run_script("collect-coverage-inputs.py", "platforms", "inputs", cwd=root)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            collected = root / "inputs" / "classes"
            self.assertTrue(collected.is_dir(), "class 链接必须指向真实目录：" + result.stdout)
            self.assertTrue(
                any(path.suffix == ".class" for path in collected.rglob("*.class"))
            )

    def test_fails_when_a_platform_class_tree_differs(self):
        """三个平台的 class 不一致时必须显式失败：JaCoCo 会按 class id 静默丢 session。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write_platforms(root / "platforms")
            (
                root / "platforms" / "scope-coverage-macos-latest" / "classes" / "fun" / "Sample.class"
            ).write_bytes(b"different-bytes")
            result = run_script("collect-coverage-inputs.py", "platforms", "inputs", cwd=root)
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn("class files differ across platforms", result.stdout)

class AssertSurefireReportsTest(unittest.TestCase):
    """按用例名核对：数量正确但用例不对、被跳过、失败，都必须让门禁失败。"""

    def test_passes_when_every_selected_class_really_ran(self):
        with tempfile.TemporaryDirectory() as tmp:
            reports = Path(tmp)
            write_complete_reports(reports)
            result = run_script("assert-surefire-reports.py", reports, "test-os")
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("PASS all selected cases really ran on test-os", result.stdout)

    def test_windows_runner_does_not_require_the_bare_bash_fixture_classes(self):
        """这两个遗留编码能力夹具用裸名 bash（Windows 上命中 WSL 转发程序），不属于 Windows 腿的执行范围。"""
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

    def test_windows_requires_the_git_bash_backed_cases_to_really_run(self):
        """Windows 腿必须留下「用真正的 Git Bash 跑通命令执行」的实证：点名的两条被跳过即失败。"""
        for skipped_cases in ((), (WINDOWS_REQUIRED_BASH_CASES[0],)):
            with self.subTest(skipped_cases=skipped_cases):
                with tempfile.TemporaryDirectory() as tmp:
                    reports = Path(tmp)
                    write_complete_reports(reports)
                    write_surefire_report(
                        reports,
                        "BashCapabilityTest",
                        WINDOWS_REQUIRED_BASH_CASES + ("posixOnlyCase",),
                        skipped=len(skipped_cases),
                        skipped_cases=skipped_cases,
                    )
                    result = run_script("assert-surefire-reports.py", reports, "windows-latest")
                    if skipped_cases:
                        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                        self.assertIn(
                            "BashCapabilityTest: required cases must not be skipped on "
                            "windows-latest: ['%s']" % skipped_cases[0],
                            result.stdout,
                        )
                    else:
                        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_non_windows_platforms_do_not_require_the_git_bash_cases(self):
        """点名的实证只在 Windows 腿生效：其它平台不因缺少这条要求而报错。"""
        with tempfile.TemporaryDirectory() as tmp:
            reports = Path(tmp)
            write_complete_reports(reports, bash_cases=())
            result = run_script("assert-surefire-reports.py", reports, "macos-latest")
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_posix_requires_the_interactive_shell_cases_to_really_run(self):
        """Linux/macOS 腿必须留下交互式 job control 的实证：点名的用例被跳过即失败。"""
        with tempfile.TemporaryDirectory() as tmp:
            reports = Path(tmp)
            write_complete_reports(reports)
            write_surefire_report(
                reports,
                "ProcessScopeInteractivePtyTest",
                INTERACTIVE_CASES,
                skipped=1,
                skipped_cases=(INTERACTIVE_CASES[0],),
            )
            result = run_script("assert-surefire-reports.py", reports, "macos-latest")
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn(
                "ProcessScopeInteractivePtyTest: required cases must not be skipped on "
                "macos-latest: ['%s']" % INTERACTIVE_CASES[0],
                result.stdout,
            )

    def test_windows_requires_the_conpty_cases_to_really_run(self):
        """Windows 腿必须留下 ConPTY 的实证：点名的用例被跳过即失败。"""
        with tempfile.TemporaryDirectory() as tmp:
            reports = Path(tmp)
            write_complete_reports(reports)
            write_surefire_report(
                reports,
                "ProcessScopeConPtyIntegrationTest",
                CONPTY_CASES,
                skipped=len(CONPTY_CASES),
                skipped_cases=CONPTY_CASES,
            )
            result = run_script("assert-surefire-reports.py", reports, "windows-latest")
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn(
                "ProcessScopeConPtyIntegrationTest: required cases must not be skipped on "
                "windows-latest",
                result.stdout,
            )

    def test_macos_requires_native_enumeration_cases(self):
        """Darwin 枚举、退出竞态和权限边界必须真实运行；缺失、跳过和重复都不能通过。"""
        for required in MACOS_CASES:
            for cases, skipped_cases in (
                (tuple(case for case in MACOS_CASES if case != required), ()),
                (MACOS_CASES, (required,)),
                (MACOS_CASES + (required,), ()),
            ):
                with self.subTest(required=required, cases=cases, skipped_cases=skipped_cases):
                    with tempfile.TemporaryDirectory() as tmp:
                        reports = Path(tmp)
                        write_complete_reports(reports)
                        write_surefire_report(
                            reports,
                            "PosixProcessSessionTest",
                            cases,
                            skipped=len(skipped_cases),
                            skipped_cases=skipped_cases,
                        )
                        result = run_script("assert-surefire-reports.py", reports, "macos-latest")
                        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                        self.assertIn(required, result.stdout)

    def test_fails_when_a_required_case_is_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            reports = Path(tmp)
            write_complete_reports(reports, cross_platform_cases=REQUIRED_CASES[:-1])
            result = run_script("assert-surefire-reports.py", reports, "test-os")
            self.assertEqual(1, result.returncode)
            self.assertIn(REQUIRED_CASES[-1], result.stdout)

    def test_every_platform_requires_the_portable_pty_cases(self):
        """PTY 的通用用例在三平台都必须真跑，缺失或跳过都不能通过门禁。"""
        for os_label in ("ubuntu-latest", "macos-latest", "windows-latest"):
            for missing in (False, True):
                with self.subTest(os_label=os_label, missing=missing):
                    with tempfile.TemporaryDirectory() as tmp:
                        reports = Path(tmp)
                        write_complete_reports(reports)
                        write_surefire_report(
                            reports,
                            "ProcessScopePtyIntegrationTest",
                            PTY_CASES[:-1] if missing else PTY_CASES,
                            skipped=0 if missing else 1,
                            skipped_cases=() if missing else (PTY_CASES[-1],),
                        )
                        result = run_script("assert-surefire-reports.py", reports, os_label)
                        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                        self.assertIn(PTY_CASES[-1], result.stdout)

    def test_every_platform_requires_the_signal_order_cases(self):
        """发信号前的父子排序是纯逻辑用例，三平台都必须真跑，缺失或跳过都不能通过门禁。"""
        for os_label in ("ubuntu-latest", "macos-latest", "windows-latest"):
            for missing in (False, True):
                with self.subTest(os_label=os_label, missing=missing):
                    with tempfile.TemporaryDirectory() as tmp:
                        reports = Path(tmp)
                        write_complete_reports(reports)
                        write_surefire_report(
                            reports,
                            "PosixProcessSignalOrderTest",
                            SIGNAL_ORDER_CASES[:-1] if missing else SIGNAL_ORDER_CASES,
                            skipped=0 if missing else 1,
                            skipped_cases=() if missing else (SIGNAL_ORDER_CASES[-1],),
                        )
                        result = run_script("assert-surefire-reports.py", reports, os_label)
                        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                        self.assertIn(SIGNAL_ORDER_CASES[-1], result.stdout)

    def test_fails_when_a_required_case_is_duplicated(self):
        """重复的用例不能代替「每项只执行一次」的验收事实。"""
        with tempfile.TemporaryDirectory() as tmp:
            reports = Path(tmp)
            write_complete_reports(
                reports, cross_platform_cases=REQUIRED_CASES + (REQUIRED_CASES[0],)
            )
            result = run_script("assert-surefire-reports.py", reports, "ubuntu-latest")
            self.assertEqual(1, result.returncode)
            self.assertIn("required cases ran more than once", result.stdout)

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
            (reports / f"TEST-{package_of('WindowsJobScopeTest')}.WindowsJobScopeTest.xml").unlink()
            result = run_script("assert-surefire-reports.py", reports, "test-os")
            self.assertEqual(1, result.returncode)
            self.assertIn("missing surefire report", result.stdout)

    def test_every_platform_requires_the_terminal_reports(self):
        """每条矩阵腿都必须包含终端基座报告，视图流也不能漏跑。"""
        for class_name in sorted(TERMINAL_CLASSES):
            for os_label in ("ubuntu-latest", "macos-latest", "windows-latest"):
                with self.subTest(class_name=class_name, os_label=os_label):
                    with tempfile.TemporaryDirectory() as tmp:
                        reports = Path(tmp)
                        write_complete_reports(reports)
                        (reports / f"TEST-{package_of(class_name)}.{class_name}.xml").unlink()
                        result = run_script("assert-surefire-reports.py", reports, os_label)
                        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                        self.assertIn(
                            f"{class_name}: missing surefire report on {os_label}",
                            result.stdout,
                        )

    def test_every_platform_requires_the_daemon_root_package_reports(self):
        """daemon 根包（运行时生命周期与真实 server 桥接）报告必须在每条腿出现，且 package_of 不能落 coding。"""
        for class_name in sorted(DAEMON_CLASSES):
            self.assertEqual(DAEMON_PACKAGE, package_of(class_name))
            for os_label in ("ubuntu-latest", "macos-latest", "windows-latest"):
                with self.subTest(class_name=class_name, os_label=os_label):
                    with tempfile.TemporaryDirectory() as tmp:
                        reports = Path(tmp)
                        write_complete_reports(reports)
                        (reports / f"TEST-{package_of(class_name)}.{class_name}.xml").unlink()
                        result = run_script("assert-surefire-reports.py", reports, os_label)
                        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                        self.assertIn(
                            f"{class_name}: missing surefire report on {os_label}",
                            result.stdout,
                        )

    def test_every_platform_requires_line_identity_and_stream_cases(self):
        """行身份、credit、native 与 writer 围栏用例缺失、跳过或重复时都必须失败。"""
        selected = (
            ("TerminalKernelTest", TERMINAL_KERNEL_CASES, TERMINAL_KERNEL_CASES[-2:]),
            ("TerminalSnapshotProjectorTest", TERMINAL_PROJECTOR_CASES, TERMINAL_PROJECTOR_CASES[-2:]),
            ("TerminalViewStreamTest", TERMINAL_VIEW_STREAM_CASES, TERMINAL_VIEW_STREAM_CASES),
            ("TerminalRuntimeDeterministicTest", TERMINAL_RUNTIME_CASES, TERMINAL_RUNTIME_CASES),
            ("TerminalRuntimeRealPtyTest", TERMINAL_RUNTIME_PTY_CASES, TERMINAL_RUNTIME_PTY_CASES),
            ("TerminalWriterTest", TERMINAL_WRITER_CASES, TERMINAL_WRITER_CASES),
            ("TerminalCoordinatorTest", TERMINAL_COORDINATOR_CASES, TERMINAL_COORDINATOR_CASES[-2:]),
        )
        for os_label in ("ubuntu-latest", "macos-latest", "windows-latest"):
            for class_name, all_cases, required_cases in selected:
                for required in required_cases:
                    for cases, skipped_cases in (
                        (tuple(case for case in all_cases if case != required), ()),
                        (all_cases, (required,)),
                        (all_cases + (required,), ()),
                    ):
                        with self.subTest(
                            os_label=os_label, class_name=class_name,
                            required=required, cases=cases, skipped_cases=skipped_cases,
                        ):
                            with tempfile.TemporaryDirectory() as tmp:
                                reports = Path(tmp)
                                write_complete_reports(reports)
                                write_surefire_report(
                                    reports, class_name, cases,
                                    skipped=len(skipped_cases), skipped_cases=skipped_cases,
                                )
                                result = run_script("assert-surefire-reports.py", reports, os_label)
                                self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                                self.assertIn(class_name, result.stdout)
                                if not skipped_cases:
                                    self.assertIn(required, result.stdout)

    def test_fails_when_a_terminal_kernel_case_is_skipped(self):
        """终端基座核心用例没有平台前置条件，被跳过即失败。"""
        for os_label in ("ubuntu-latest", "macos-latest", "windows-latest"):
            with self.subTest(os_label=os_label):
                with tempfile.TemporaryDirectory() as tmp:
                    reports = Path(tmp)
                    write_complete_reports(reports)
                    write_surefire_report(
                        reports,
                        "TerminalKernelSchedulingTest",
                        TERMINAL_SCHEDULING_CASES + ("someOtherSchedulingCase",),
                        skipped=1,
                        skipped_cases=(TERMINAL_SCHEDULING_CASES[0],),
                    )
                    result = run_script("assert-surefire-reports.py", reports, os_label)
                    self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                    self.assertIn(
                        "TerminalKernelSchedulingTest: must not skip any case on " + os_label,
                        result.stdout,
                    )

    def test_fails_when_a_terminal_critical_name_is_wrongly_reordered(self):
        """数量对但关键用例名被改成乱序变体时必须失败：门禁核对的是精确用例名集合。"""
        with tempfile.TemporaryDirectory() as tmp:
            reports = Path(tmp)
            write_complete_reports(reports)
            scrambled = TERMINAL_SCHEDULING_CASES[1:] + (
                TERMINAL_SCHEDULING_CASES[0][::-1],
            )
            write_surefire_report(
                reports,
                "TerminalKernelSchedulingTest",
                scrambled + ("someOtherSchedulingCase",),
            )
            result = run_script("assert-surefire-reports.py", reports, "ubuntu-latest")
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn("required cases did not run", result.stdout)
            self.assertIn(TERMINAL_SCHEDULING_CASES[0], result.stdout)

    def test_every_platform_requires_the_coordinator_to_run_without_skips(self):
        """协调器是三平台都必须真跑的核心：报告缺失、漏跑、跳过或重复都不能通过门禁。"""
        for os_label in ("ubuntu-latest", "macos-latest", "windows-latest"):
            for cases, skipped_cases in (
                (TERMINAL_COORDINATOR_CASES[:-1], ()),
                (TERMINAL_COORDINATOR_CASES, (TERMINAL_COORDINATOR_CASES[0],)),
                (TERMINAL_COORDINATOR_CASES + (TERMINAL_COORDINATOR_CASES[0],), ()),
            ):
                with self.subTest(os_label=os_label, cases=cases, skipped_cases=skipped_cases):
                    with tempfile.TemporaryDirectory() as tmp:
                        reports = Path(tmp)
                        write_complete_reports(reports)
                        write_surefire_report(
                            reports,
                            "TerminalCoordinatorTest",
                            cases,
                            skipped=len(skipped_cases),
                            skipped_cases=skipped_cases,
                        )
                        result = run_script("assert-surefire-reports.py", reports, os_label)
                        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                        self.assertIn("TerminalCoordinatorTest", result.stdout)
            with self.subTest(os_label=os_label, missing_report=True):
                with tempfile.TemporaryDirectory() as tmp:
                    reports = Path(tmp)
                    write_complete_reports(reports)
                    (reports / f"TEST-{TERMINAL_PACKAGE}.TerminalCoordinatorTest.xml").unlink()
                    result = run_script("assert-surefire-reports.py", reports, os_label)
                    self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                    self.assertIn(
                        f"TerminalCoordinatorTest: missing surefire report on {os_label}",
                        result.stdout,
                    )

    def test_coordinator_required_methods_exist_in_the_real_source_without_os_assume(self):
        """点名的方法必须真的出现在协调器测试源码里，且源码不得用 OS assume 把用例按平台跳过。"""
        source = (
            REPOSITORY_ROOT
            / "harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/terminal/TerminalCoordinatorTest.java"
        ).read_text(encoding="utf-8")
        for case in TERMINAL_COORDINATOR_CASES:
            self.assertIn("void " + case + "(", source, case)
        for forbidden in ("assumeTrue(", "assumeFalse(", "Assumptions.", "@Disabled"):
            self.assertNotIn(forbidden, source, forbidden)


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


def core_coverage(total_per_class=200, covered_per_class=185, coordinator=(185, 15)):
    coverage = {
        name: (covered_per_class, total_per_class - covered_per_class) for name in CORE_CLASSES
    }
    # 协调器与核心合计是两条独立门禁：默认给健康值，负向用例单独压低或删除它。
    coverage[COORDINATOR_CLASS] = coordinator
    return coverage


class GateCoreCoverageTest(unittest.TestCase):
    """门禁必须覆盖整条核心路径：漏类即失败，合计不足 90% 即失败，且必须逐个类打印数字；协调器另有独立门禁。"""

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
        self.assertIn(COORDINATOR_CLASS, result.stdout)
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

    def test_fails_when_the_coordinator_is_missing_from_the_merged_report(self):
        coverage = core_coverage()
        del coverage[COORDINATOR_CLASS]
        result = self.gate(coverage)
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn(COORDINATOR_CLASS, result.stdout)
        self.assertIn("merged data was dropped", result.stdout)

    def test_fails_when_the_coordinator_is_below_the_target(self):
        result = self.gate(core_coverage(coordinator=(170, 30)))
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn(f"{COORDINATOR_CLASS} line coverage", result.stdout)
        self.assertIn("is below 90.0%", result.stdout)

    def test_fails_when_the_coordinator_has_no_executed_lines(self):
        result = self.gate(core_coverage(coordinator=(0, 200)))
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn(f"{COORDINATOR_CLASS}: no lines were executed", result.stdout)

    def test_coordinator_does_not_dilute_the_core_aggregate(self):
        """协调器即使完美，也不能掩盖原核心合计不足：两条门禁必须彼此独立。"""
        coverage = core_coverage(total_per_class=200, covered_per_class=170, coordinator=(500, 0))
        result = self.gate(coverage)
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("FAIL core path line coverage", result.stdout)


if __name__ == "__main__":
    unittest.main()
