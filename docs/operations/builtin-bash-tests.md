# 内置 Bash 的行为验证

修改 `process.exec` 命令执行、输出捕获或终态处理时，需要分别验证进程是否收敛、输出是否保真、协议是否只提交一次终态。
这三件事由不同层负责，不能用“收到了失败结果”代替“后代进程已经退出”。
工具契约见[内置工具设计](../modules/builtin-tools-design.md)，运行与安装见
[Environment Daemon](environment-daemon.md)。

## 选择验证入口

从仓库根目录运行，使用 JDK 21。下面的测试会启动真实本地进程、创建临时文件并发送终止信号，
不调用模型、不连接部署数据库。必须使用可供测试创建和收敛子进程的工作站或 CI runner。

```bash
# Daemon：命令执行、范围收敛、输出发布与协议终态
env JAVA_HOME="$JAVA_HOME_21" mvn -pl harness/daemon -am test

# 提示词与 capability schema
env JAVA_HOME="$JAVA_HOME_21" mvn -pl harness/builtin -am test \
  -Dtest='BuiltinHarnessContributorTest,EnvironmentCapabilityCatalogTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

# 静态权限面分析，不执行被分析的命令
env JAVA_HOME="$JAVA_HOME_21" mvn -pl harness/runtime -am test \
  -Dtest='BashSurfaceAnalyzerTest,BashSurfaceAnalyzerCoverageTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Daemon 的重点类是 `BashCapabilityTest`、`ProcessScopeTest`、`ProcessScopeCrossPlatformTest`、
`ProcessScopeHelperFailureTest`、`PosixProcessSessionTest`、`ProcessScopeStateTest`、
`ProcessScopePtyIntegrationTest`（三平台：伪终端输出与退出码；POSIX：TTY、resize、会话收敛；Linux：主端描述符回收）、
`ProcessScopeInteractivePtyTest`（Linux/macOS：交互式 bash 的 monitor 模式、多进程组作业控制与终端信号）、
`ProcessScopeConPtyIntegrationTest`（Windows：ConPTY 控制台继承、原生窗口尺寸与 Job 子进程收敛）、
`WindowsCommandLineTest`、`WindowsJobScopeTest`、
`OutputSpoolTest`、`TextOutputStoreTest` 和 `DaemonRuntimeTest`。只筛选某类时仍保留 `-am` 与
`-Dsurefire.failIfNoSpecifiedTests=false`，并检查目标模块的 Surefire XML 确实执行了目标用例；
上游模块没有同名测试不应导致失败，但目标用例缺失也不能当作通过。

矩阵 `daemon.ready` 是 L4 的 capability 登记验证，不执行 Bash；当前 E2E inventory 没有 Bash
执行用例。隔离容器中的命令 smoke 由
[`NativeToolSmoke.java`](../../scripts/dev/verify/reliability/fixtures/NativeToolSmoke.java) 承担，
通过 `stack.sh tool-smoke` 运行，不经过 Agent、Provider 或 App command batch。
它需要已经启动的可靠性栈，准备与清理边界见
[开发与测试](development-and-testing.md#隔离栈与真实-agent-矩阵)，不能把它称为 Platform 调用端到端证据。

## 命令与退出结果

[`BashCapability`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/BashCapability.java)
以宿主配置的 `bash -lc <command>` 启动命令，`workdir` 每次必填，没有隐式 cwd 或 stdin 命令传输模式。
捕获模式把 stdout/stderr 合到同一流：POSIX 在 helper 内执行 `dup2(1, 2)`，Windows 在创建标准句柄时合并，
不是 Java `ProcessBuilder.redirectErrorStream`。stdin 写端立即关闭，等待 EOF 的命令必须能自然退出。

| 要验证的行为 | 主要断言 |
| --- | --- |
| 自然退出与非零退出码保真，非零退出标记错误 | `BashCapabilityTest.exitOutcomesCarryAuthoritativeExitCode`、`userExitTrapAndExactExitCodeSurviveTheScope`、`execReplacedShellKeepsTheCommandExitCode` |
| stdin 确定性 EOF、两路大输出不死锁 | `closesStdinSoCommandsWaitingForEofFinishNaturally`、`keepsPayloadBeyondPipeBufferComplete`、`mergedStreamsBeyondPipeBufferCompleteWithoutDeadlock` |
| 启动前取消或超时不运行命令、不触碰输出存储 | `preCancelledCallDoesNotStartShellOrTouchStore`、`preTimedOutCallDoesNotStartShellOrTouchStore` |
| 启动失败不留下中转文件 | `startFailureIsReportedWithoutStagingResidue` |
| 超时预算原样传递，零值无 deadline，极大值不变成立即超时 | `DaemonRuntimeTest.usesWireTimeoutVerbatimWithoutDescriptorFallback`、`zeroTimeoutMeansNoDeadlineAndIsNotAnImmediateTimeout`、`acceptsMaximumWireTimeoutWithoutOverflowingScheduler` |
| 缺 workdir 不回退、未知字段在副作用之前拒绝 | `DaemonRuntimeTest.omittedWorkdirIsRejectedWithoutDefaultFallback`、`rejectsUnknownWorkspacePathFieldInV1InvokePayloadBeforeSideEffects` |

机器事实在 `detailsJson.process` 中：`outcome` 为 `EXITED`、`TIMED_OUT` 或 `CANCELLED`，
只有自然退出报告权威 `exitCode`，不以 `128 + signal` 合成被杀进程的退出码。

## 进程范围与清理

[`ProcessScope`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/process/ProcessScope.java)
与 [`ProcessScopeHelper`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/process/ProcessScopeHelper.java)
使用 POSIX 会话或 Windows 命名 Job Object。终态通知前必须确认整个范围收敛，首个进程退出并不充分。
POSIX 覆盖会话内所有作业进程组，先发 SIGTERM、等待宽限、再强杀；Windows 显式终止 Job 并以活动成员计数确认收敛，`KILL_ON_JOB_CLOSE` 保证句柄释放时的生命周期。

- 自然退出、后台作业持有 stdout、`disown` 和嵌套 fork：由 `BashCapabilityTest` 的
  `naturalExitReapsBackgroundDescendantsBeforeTerminalCallback`、
  `naturalExitCompletesEvenWhenBackgroundJobHoldsStdout`、
  `disownedBackgroundJobIsReapedOnNaturalExit`、`nestedForkDescendantsAreReapedOnNaturalExit` 验证。
- 超时、取消、忽略温和信号和命令 trap：由 `timeoutTerminatesWholeProcessTree`、
  `cancellationTerminatesWholeProcessTree`、`termIgnoringDescendantIsForceKilledOnTimeout`、
  `commandCanTrapTerminationBeforeTheForceKill` 验证。超时夹具先等命令就绪屏障，再触发生产超时任务，
  避免把 helper 冷启动速度误当成超时语义。
- 许可前取消、许可与派生竞争、并发终止、状态损坏、身份核验和私有目录清理：由 `ProcessScopeTest`
  验证；`ProcessScopeStateTest` 验证状态文件读写与失败处理。
  检查终止后的存活状态与身份证据，无法判定收敛时报告失败。
- 不依赖平台 shell 的进程层级与 stdin/duplex 语义：由 `ProcessScopeCrossPlatformTest`
  使用真实 Java 进程和原生 pid 验证。LSP 使用同一个范围的 `startDuplex` 模式，stdin/stdout/stderr 保持独立，
  相关启动失败与关闭清理由 `LspClientTest`、`LspClientPoolConcurrencyTest` 验证。

范围不是恶意命令沙箱：命令主动 `setsid` 或交给其他 session 时可离开边界；`set -m` 创建的作业进程组仍在同一会话内，必须被收敛。
keeper 被外部强杀后仍以内核会话枚举收敛（Linux/WSL `/proc`、macOS libproc），并逐个复核存活、启动时刻和会话归属；
没有身份信息的平台不能把未确认的范围报告为已收敛，也不能盲目按旧 pid 快照发信号。

## 输出与协议终态

[`OutputSpool`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/OutputSpool.java)
负责内联预算、有界预览和全文捕获；
[`TextOutputStore`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/TextOutputStore.java)
负责私有存储与发布。
默认全文捕获预算为 1 GiB，耗尽后继续排空输出并计数。全文发布优先原子改名，不支持时使用普通改名；
文件编辑提交的原子替换要求则见[文件变更验证](builtin-mutation-tests.md#原子提交权限并发和取消)。

| 情况 | 主要证据与预期 |
| --- | --- |
| 小输出 | `OutputSpoolTest.staysInlineUnderLimitsAndPreservesErrorFlag`：正文不改写、不落盘 |
| 字节或行数超阈值 | `spillsOnByteThresholdAndPublishesTextResultContentWithPath`、`spillsOnLineThresholdAndKeepsLocalFullText`：发布全文与可读路径 |
| 预览与计数 | `previewIsBoundedAndMarksTheOmittedMiddle`、`previewCutsAtCharacterBoundaryWithoutReplacementCharacters`、`lineCountingMatchesTextStreamsForCrLfAndCrlf`：保留有界头尾，不破坏 UTF-8，计数包含 CR/LF/CRLF |
| 捕获预算耗尽 | `captureBudgetStopsFileCaptureWithoutFailingTheCall`：停止文件捕获但继续计数，不终止命令 |
| 存储或发布失败 | `localStorageFailureDegradesToBoundedPreviewWithoutThrowing`、`publishFailureDegradesToPreviewAndRemovesStagingFile`、`publishFailurePreviewStillReportsCaptureTruncation`：无路径预览、不留中转文件，分别报告捕获截断与发布失败 |
| 超时/取消后的输出 | `BashCapabilityTest.timeoutPublishesSpilledOutputAndKeepsCountsFaithful`、`cancellationKeepsCapturedOutputAndIsIdempotent`：保留已捕获文本，终态说明不写入全文或字节/行计数 |

`detailsJson.textOutput` 的 `path`、`captureTruncated`、`captureFailed`、`totalBytes` 与 `totalLines`
是判断结果是否完整的事实，不按文本里的 “limit” 等字样猜测截断。私有目录被外部删除时降级为预览；
当前调用返回降级结果；排查时同时检查预览、捕获状态和 staging 清理。
需要验证文件通道写入或 flush/close 异常时，使用能触发该分支的故障夹具并检查本次覆盖报告。

[`DaemonRuntimeTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/DaemonRuntimeTest.java)
验证超时裁决仍为 `FAILED`、取消裁决仍为 `CANCELLED`，两者在收尾窗口携带捕获输出；
能力未回调、调度器拒绝、收尾中停机和迟到回调都只能产生一个终态。
`handoffDropsCapabilityTextBeyondWireLimit` 固定超过 8 MiB 逻辑消息预算时整体丢弃能力文本，而非发送超大终态；`handoffDropsCapabilityTextWithinEnvelopeBudgetBand` 覆盖文本本身未到 8 MiB、但加上 JSON 转义和外壳后整包超限的情况。

## 权限面分析

[`BashSurfaceAnalyzer`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/permission/BashSurfaceAnalyzer.java)
只分析文本，不运行 shell。`BashSurfaceAnalyzerTest` 与 `BashSurfaceAnalyzerCoverageTest` 覆盖：

- 顶层链、管道与后台分隔；引号、转义、重定向、注释、CRLF 与续行不产生伪段；
- heredoc 归属、多个 delimiter、可展开与字面量正文、未闭合正文；
- 命令/进程替换、复合语法、动态可执行名、重定向和 shell/launcher 包装器的 unsupported 原因；
- quoted/escaped 字面量、赋值前缀、绝对路径 basename，以及稳定的整段和前缀候选；
- 不完整语法按保守路径处理：unsupported 只有完整命令明确命中 DENY 才拒绝，否则 ASK，不猜内部命令；
  支持的复合命令按静态段组合权限。

## 平台证据与覆盖率

Surefire 结果在各模块 `target/surefire-reports`，JaCoCo 报告在 `target/site/jacoco`。
Daemon 不绑定 `jacoco:check`；Runtime 的固定核心类门禁不包含 `BashSurfaceAnalyzer`。
报告中未覆盖行以当前运行产物为准，不把静态检查或未执行的平台测试称为覆盖率证据。

跨平台入口是 [process-scope.yml](../../.github/workflows/process-scope.yml) 的 Linux/macOS/Windows 矩阵。
Windows 必须在 Daemon 配置的 `bashExecutable`（Web 安装设置或 `daemon.json`）指定 Git Bash：裸名 `bash` 可能命中 `System32\bash.exe` 的 WSL 转发器。
`BashCapabilityTest` 找不到 Git Bash 会跳过；矩阵额外要求 stdin EOF 与超时溢出两条必须真跑。
用裸名 bash 的 `CodingCapabilitiesTest`、`CodingCapabilitiesEdgeTest` 不参与 Windows 腿。
本机单平台测试不能代替其他平台的原生证据。

helper 是独立 JVM，默认不进入主覆盖数据。矩阵启用
`-Dkk-studio.process-scope.helper-coverage=true`，收集 `target/jacoco-helper/*.exec`。
合并作业先校验三平台 class 文件一致，再规范化、合并数据：被终止的 helper 可能留下一条只写了一半的执行数据记录，
[NormalizeCoverage.java](../../scripts/dev/verify/process-scope/NormalizeCoverage.java) 用官方 Reader 定位最后一条完整记录的边界，
只裁掉那一条残缺尾部，其余输入逐字节保留；父 exec 与任何其它格式损坏都让作业失败。核心类清单、合计行覆盖率计算与阈值由
[gate-core-coverage.py](../../scripts/dev/verify/process-scope/gate-core-coverage.py) 定义；
单平台数字与同一次矩阵的合并数字分别报告。

终端折叠、计时文案与渲染回退不是 Daemon 的职责，以上测试不提供前端展示证据。
