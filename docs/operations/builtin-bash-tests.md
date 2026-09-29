# 内置 Bash 测试映射

这份文档回答两个问题：`bash` 工具的行为在 kk-studio 里由哪些自动化测试承接，以及 pi-base 的
bash 测试用例逐条对应到哪里、哪一条有意不迁移。

事实源是仓库里的测试代码本身；本文只记录映射关系与已验证的边界，不重复测试断言内容。

## 先建立心智模型：三层拆分

pi-base 的 bash 测试集中在单个工具的进程执行、输出裁剪与 TUI 渲染上；kk-studio 把同一批行为拆到三个层次，
每层有独立的终态与错误语义，因此一个 pi-base 测试文件会映射到多个 Java 测试类：

| 层次 | 代码 | 负责的事实 | 主要测试类 |
| --- | --- | --- | --- |
| 命令执行与捕获 | [`BashCapability`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/BashCapability.java)、[`ProcessScope`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/ProcessScope.java)、[`ProcessScopeHelper`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/ProcessScopeHelper.java) | 合并 stdout/stderr、按阈值落盘、超时/取消/退出三种收尾、OS 执行范围收敛 | `BashCapabilityTest`、`ProcessScopeTest`、`ProcessScopeCrossPlatformTest`（夹具 `ProcessScopeFixtureMain`）、`WindowsCommandLineTest`、`WindowsJobScopeTest`、`OutputSpoolTest`、`TextOutputStoreTest` |
| 输出裁剪与发布 | [`OutputSpool`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/OutputSpool.java)、[`TextOutputStore`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/TextOutputStore.java) | 内联/落盘阈值、精确字节与行计数、有界预览、中转文件发布 | `OutputSpoolTest`、`TextOutputStoreTest` |
| 协议与终态仲裁 | [`DaemonRuntime`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonRuntime.java) | 超时/取消裁决、终态唯一性、重放、收尾窗口（携带已捕获输出） | `DaemonRuntimeTest` |
| 模型可见契约 | [`bash.md`](../../harness/builtin/src/main/resources/fun/fengwk/kkstudio/harness/builtin/environment/prompts/bash.md)、`process.exec` schema | 工具提示词、workdir 必填、超时解析 | `BuiltinHarnessContributorTest`、`CodingCapabilitiesTest` |

pi-base 里属于 TUI 渲染、工具集管理与终端交互的用例在 kk-studio 没有对应层（kk-studio 的渲染与工具选择由
Backend/Frontend 承担），下表中标记为「不迁移」。

## 运行方式

从仓库根目录执行，用 JDK 21：

```bash
# bash 内核：进程执行、终止、输出裁剪
env JAVA_HOME=$JAVA_HOME_21 mvn -o -pl harness/daemon -am test \
  -Dtest='BashCapabilityTest,ProcessScopeTest,ProcessScopeCrossPlatformTest,WindowsCommandLineTest,WindowsJobScopeTest,OutputSpoolTest,TextOutputStoreTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

# 协议与终态仲裁（含超时/取消收尾窗口）
env JAVA_HOME=$JAVA_HOME_21 mvn -o -pl harness/daemon -am test \
  -Dtest='DaemonRuntimeTest' -Dsurefire.failIfNoSpecifiedTests=false

# 模型可见契约（bash 提示词、workdir、超时）
env JAVA_HOME=$JAVA_HOME_21 mvn -o -pl harness/builtin -am test \
  -Dtest='BuiltinHarnessContributorTest' -Dsurefire.failIfNoSpecifiedTests=false

# 权限面分析（命令分段、候选与 unsupported 原因）
env JAVA_HOME=$JAVA_HOME_21 mvn -o -pl harness/runtime -am test \
  -Dtest='BashSurfaceAnalyzerTest,BashSurfaceAnalyzerCoverageTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

`bash` 的端到端运行路径（真实 Daemon + Platform 调用）由 E2E 矩阵的 L1 API 用例覆盖，入口见
[开发与测试](development-and-testing.md)。

## tests/bash-operations.test.ts（7 例）

| pi-base 用例 | kk-studio 承接 | 说明 |
| --- | --- | --- |
| `captures stdout and stderr while returning the shell exit code` | `BashCapabilityTest.exitOutcomesCarryAuthoritativeExitCode` | kk-studio 用 `redirectErrorStream` 合并两路到同一捕获流，因此不存在单独的 stderr 通道；退出码仍是权威事实 |
| `reports signal terminations as 128 + signal number`（POSIX） | 不迁移 | 有意差异：kk-studio 不用 `128+N` 合成退出码，而是用 `detailsJson.process.outcome` 的 `EXITED`/`TIMED_OUT`/`CANCELLED` 显式表达去向，被杀进程不报告 `exitCode` |
| `rejects with a timeout marker when the shell exceeds the requested timeout` | `BashCapabilityTest.timeoutKeepsCapturedOutputWithoutLeavingStagingResidue`、`DaemonRuntimeTest.timeoutHandoffDeliversCapturedOutputInFailedTerminal` | 超时同时是能力内的进程收尾与运行时侧的 `FAILED` 终态 |
| `rejects timer-overflow timeouts before launching a shell` | `DaemonRuntimeTest.acceptsMaximumWireTimeoutWithoutOverflowingScheduler`、`usesWireTimeoutVerbatimWithoutDescriptorFallback`、`zeroTimeoutMeansNoDeadlineAndIsNotAnImmediateTimeout` | `deadlineNanos` 溢出收敛为「实际上无 deadline」，绝不退化成立即超时 |
| `rejects when the caller aborts a running shell command` | `BashCapabilityTest.cancellationKeepsCapturedOutputAndIsIdempotent`、`DaemonRuntimeTest.cancelHandoffDeliversCapturedOutputInCancelledTerminal` | 取消同样保留已捕获输出，且只回调一次 |
| `does not launch a shell when the caller is already aborted` | `BashCapabilityTest.preCancelledCallDoesNotStartShellOrTouchStore`、`preTimedOutCallDoesNotStartShellOrTouchStore` | 取消与超时在启动前就已生效时不启动 shell、不创建任何本地文件，也不产生命令副作用；协议层另有 `DaemonRuntimeTest` 拦截陈旧调用 |
| `writes commands to stdin when the shell configuration requires stdin transport` | 不迁移 | kk-studio 只以 `bash -lc <command>` 传递命令，`CodingToolsConfig` 没有 stdin 传输模式；能力不写 stdin，只立即关闭写端，该语义由 `BashCapabilityTest.closesStdinSoCommandsWaitingForEofFinishNaturally` 覆盖 |

## tests/process-termination.test.ts（4 例）

bash 命令的终止语义现在由 OS 执行范围承接（POSIX 进程组 / Windows Job Object），因此这几例主要映射到
`BashCapabilityTest`、`ProcessScopeTest` 与平台无关的 `WindowsCommandLineTest`。LSP 客户端生命周期使用同一个执行范围（双向标准
流模式），终止顺序与幂等性因此共享同一份事实，没有第二套实现。

| pi-base 用例 | kk-studio 承接 | 说明 |
| --- | --- | --- |
| `lets child processes handle SIGTERM before a force kill` | `BashCapabilityTest.termIgnoringDescendantIsForceKilledOnTimeout`、`BashCapabilityTest.commandCanTrapTerminationBeforeTheForceKill`、`ProcessScopeTest.terminateLetsTheCommandRunItsTerminationTrap`、`ProcessScopeTest.liveScopeIsNotReportedAsConverged` | bash 与 LSP 都走同一条顺序：先对整组发 `SIGTERM`，宽限窗口后才 `SIGKILL`；命令自己的 trap 必须真的执行过 |
| `sends SIGTERM once, force-kills later, and cleanup cancels pending force kill` | `ProcessScopeTest.concurrentTerminationIsIdempotentAndConverges`、`ProcessScopeTest.closeIsIdempotent` | 幂等由「一次收敛 + 其余调用等待同一次收敛」保证：并发终止、重复终止与已收敛范围都是空操作；等待收敛失败时返回未收敛，不假装成功 |
| `keeps process-tree escalation armed after the leader exits`（POSIX） | `BashCapabilityTest.naturalExitReapsBackgroundDescendantsBeforeTerminalCallback`、`timeoutTerminatesWholeProcessTree`、`ProcessScopeTest.liveScopeIsNotReportedAsConverged` | kk-studio 不再枚举后代：命令与普通后代一开始就在同一个进程组里，leader 退出后升级仍然覆盖整组 |
| `does not signal a child that already exited` | `ProcessScopeTest.naturalExitKeepsTheExactCommandExitCode`、`publishedScopeIdIsTheHelperProcessGroup`、`ProcessScopeTest.killVerifiedMemberNeverSignalsAnUnverifiedProcess`、`BashCapabilityTest.startFailureIsReportedWithoutStagingResidue` | 范围收敛直接问内核（组里是否还有活着的成员，僵尸不算），已收敛时是空操作；发信号只针对刚校验过身份、且仍然属于本次调用范围的成员 |

## tests/process-termination-windows.test.ts（2 例）

| pi-base 用例 | kk-studio 承接 | 说明 |
| --- | --- | --- |
| `keeps Windows process-tree escalation armed after the leader exits` | `ProcessScopeTest`（`cmd /c` 路线）与 `WindowsJobScope` 的命名 Job 语义 | 差异：kk-studio 不用 `taskkill /T`，而是用带 `KILL_ON_JOB_CLOSE` 的命名 Job 覆盖整组，并用 `ActiveProcesses == 0` 证明整组结束（首个进程退出不算证据）；Windows 侧的本机证据来自 CI 矩阵，见「已知缺口」 |
| `falls back to SIGKILL for single-process force termination` | `WindowsCommandLineTest`（argv 拼装）与 `BashCapabilityTest` 的取消/超时用例 | Windows 没有 `SIGKILL` 对应物：终止 helper 即让系统按 Job 收敛整组 |

## tests/bash-renderer-behavior.test.ts（5 例）

这 5 例断言的是 TUI 渲染层的呈现细节（渲染器选择、计时文案、折叠与展开提示）。kk-studio 的渲染由
Frontend 承担，daemon 只交付终态结果文本，因此逐条不迁移；其中与内核相关的部分（终态说明、有界预览）
由下表右列的内核测试承接，不用 Java 断言伪造渲染文案。

| pi-base 用例 | kk-studio 承接 | 说明 |
| --- | --- | --- |
| `renders concise calls without default workdir noise and with timeout` | 不迁移 | 调用行渲染属于 TUI；workdir 在 kk-studio 是每次调用必填参数，没有「默认 workdir」需要隐藏 |
| `falls back to the pi-base renderer when an injected builtin renderer throws` | 不迁移 | 渲染器回退链是 TUI 装配；daemon 侧没有渲染器注入点 |
| `shows elapsed timing in partial renders and final timing in completed renders` | 不迁移（内核事实：`BashCapabilityTest.timeoutTerminatesWholeProcessTree` 只证明 live partial 与带终态说明的终态结果存在） | 计时文案本身由 Frontend 生成：能力不产出「已耗时/总耗时」这类呈现文本 |
| `shows a bounded error tail when successful Bash previews are disabled` | 不迁移（承接内核事实：`OutputSpoolTest.previewIsBoundedAndMarksTheOmittedMiddle`） | 折叠行数策略属于 TUI；「有界预览 + 省略说明」的内核事实由 `OutputSpool` 测试承接 |
| `does not offer expansion when a disabled Bash preview shows the complete error` | 不迁移 | 展开提示是否出现是渲染决策，不是结果内容 |

## tests/tool-output.test.ts（18 例）

| pi-base 用例 | kk-studio 承接 | 说明 |
| --- | --- | --- |
| `returns small text output unchanged` | `OutputSpoolTest.staysInlineUnderLimitsAndPreservesErrorFlag` | 阈值内不落盘、不改写正文 |
| `leaves non-text outputs unchanged` | 不迁移 | `OutputSpool` 只承载文本；二进制/资源内容由各自 capability 的结果形状保证，不流经 bash 捕获路径 |
| `truncates large text output, preserves attachments, and writes the full output` | `OutputSpoolTest.spillsOnByteThresholdAndPublishesTextResultContentWithPath`、`spillsOnLineThresholdAndKeepsLocalFullText`、`closeRemovesUnpublishedStagingFileOnly` | 「附件保留」不迁移：kk-studio 的截断只发生在单一合并文本流上 |
| `recreates private storage if its cached directory is removed externally` | 不迁移（已知差异） | `TextOutputStore` 在构造期创建并收紧权限的私有目录；目录被外部删除时 kk-studio 降级为有界预览，不自我修复。降级路径由 `OutputSpoolTest.localStorageFailureDegradesToBoundedPreviewWithoutThrowing` 覆盖 |
| `keeps a bounded preview when temporary full-output storage is unavailable` | `OutputSpoolTest.localStorageFailureDegradesToBoundedPreviewWithoutThrowing`、`storageFailureLeavesNoResidualFilesAndNeverThrows`、`publishFailureDegradesToPreviewAndRemovesStagingFile`、`publishFailurePreviewStillReportsCaptureTruncation` | 中转文件创建失败与 durable 发布失败都返回无路径的有界预览，绝不因此让调用失败，也不残留中转文件 |
| `retries private directory creation after a transient failure in the same TMPDIR` | 不迁移（已知差异） | 单次调用内不重试：捕获失败即降级为预览，下一次调用重新构造存储 |
| `preserves original item order when truncation happens in a later text block` | 不迁移 | 没有多段 content 的顺序模型；bash 只产生一条合并文本 |
| `respects already-truncated upstream output without writing pi-base-truncation files` | `OutputSpoolTest.spillsOnByteThresholdAndPublishesTextResultContentWithPath` | 差异：kk-studio 没有「上游已截断」概念，落盘与内联预览由同一套阈值一次性决定，不存在二次截断 |
| `preserves upstream bash truncation fields so renderer warnings stay accurate` | `BashCapabilityTest.timeoutPublishesSpilledOutputAndKeepsCountsFaithful`、`OutputSpoolTest.captureBudgetStopsFileCaptureWithoutFailingTheCall` | 机器可判定事实改为 `detailsJson.textOutput`（`path`/`captureTruncated`/`totalBytes`/`totalLines`）；渲染层告警不迁移 |
| `recognizes structured bash truncation for an oversized single-line preview` | `OutputSpoolTest.previewIsBoundedAndMarksTheOmittedMiddle`、`handlesSingleByteAndBoundsChecks`、`previewCutsAtCharacterBoundaryWithoutReplacementCharacters` | 单行超长的有界预览与 UTF-8 边界 |
| `counts CR-only output toward the final line limit` | `OutputSpoolTest.lineCountingMatchesTextStreamsForCrLfAndCrlf`、`countsPhysicalLinesAccurately` | 行计数与 Java 文本流一致 |
| `marks already-truncated long-line output even when below pi-base size limits` | 不迁移 | 无渲染层推断 |
| `reapplies the final limit to oversized upstream-truncated previews` | `OutputSpoolTest.previewIsBoundedAndMarksTheOmittedMiddle` | 预览始终受内联预算约束，但不存在「对已截断预览再截断」的两段式流程 |
| `recognizes grep's native truncation metadata as upstream truncation` | 不迁移到 bash | grep 自己的截断语义由 `FindGrepCapabilitiesTest.grepMatchCenteredExcerptForLongLines`、`grepSpoolsLargeResultsToBoundedTextResultWithPath` 承接 |
| `respects find's own truncation metadata instead of truncating the truncated preview again` | 不迁移到 bash | 由 `FindGrepCapabilitiesTest.findSpoolsLargeResultsToBoundedTextResultWithPath` 承接 |
| `does not infer read/grep truncation from ordinary content without explicit metadata` | 不迁移 | kk-studio 不按文本字面量推断截断，因此没有对应实现 |
| `does not treat ordinary text as upstream truncation just because it mentions generic limit words` | 不迁移 | 同上 |
| `tool_result truncation applies to tools outside pi-base registrations` | 不迁移 | pi-base 的 `tool_result` 全局钩子层在 kk-studio 不存在：截断由每个 capability 自己决定 |

## tests/bash-index.test.ts（28 例）

| pi-base 用例 | kk-studio 承接 | 说明 |
| --- | --- | --- |
| `describes shell selection and host shell startup options` | `BuiltinHarnessContributorTest.bashPromptDescribesConfiguredShellWithoutTemplatePlaceholders` | 提示词必须说明 shell 由宿主配置、不可按调用选择；per-platform shell 标签与 rc 前缀不迁移 |
| `maps timeout_seconds to builtin bash timeout` | `DaemonRuntimeTest.usesWireTimeoutVerbatimWithoutDescriptorFallback` | platform 解析后的有效超时随 INVOKE 原样传给能力 |
| `applies the default bash timeout when timeout_seconds is omitted` | `DaemonRuntimeTest.explicitTimeoutMayExceedDescriptorDefault`、`BuiltinHarnessContributorTest.catalogFreezesExactInventoryOf12ToolsAndAssociatedCapabilities` | 默认超时来自 capability descriptor，并由 contributor 目录测试冻结 |
| `defaults bash workdir to the current cwd` | `DaemonRuntimeTest.omittedWorkdirIsRejectedWithoutDefaultFallback`、`rejectsUnknownWorkspacePathFieldInV1InvokePayloadBeforeSideEffects` | 有意的语义差异：kk-studio 要求每次调用显式提供 `workdir`，缺省即拒绝，绝不回落到某个隐式目录 |
| `surfaces bash execution errors` | `BashCapabilityTest.startFailureIsReportedWithoutStagingResidue`、`DaemonRuntimeTest.convertsToolErrorsAndMismatchedResultsToFailedTerminal` | shell 无法启动与结果编码失败都收敛为明确失败终态 |
| `executes through the default builtin bash tool` | E2E 矩阵（L1 API） | 单测层不重复真实 shell 的端到端路径 |
| `executes through the default builtin bash tool in an explicit workdir` | E2E 矩阵（L1 API）、`DaemonRuntimeTest.rejectsUnknownWorkspacePathFieldInV1InvokePayloadBeforeSideEffects` | workdir 契约由 schema 与预检共同保证 |
| `truncates huge bash output and saves the full output to a temp file` | `BashCapabilityTest.timeoutPublishesSpilledOutputAndKeepsCountsFaithful`、`OutputSpoolTest.spillsOnLineThresholdAndKeepsLocalFullText` | 超阈值即发布 durable 全文，并在内联结果里给出路径 |
| `uses the built-in bash result renderer when available` | 不迁移 | TUI 渲染层 |
| `uses the pi-base bash result renderer when collapsed result lines are configured` | 不迁移 | TUI 渲染层 |
| `adds a leading blank line to bash result text` | 不迁移 | TUI 渲染层 |
| `adds a leading blank line to collapsed bash result text` | 不迁移 | TUI 渲染层 |
| `tracks bash execution timing state from renderCall` | 不迁移 | TUI 渲染层 |
| `shows 20 trailing lines in collapsed bash results` | `OutputSpoolTest.previewIsBoundedAndMarksTheOmittedMiddle`（仅承接「保留尾部」内核） | 折叠行数配置不迁移 |
| `supports zero-line collapsed bash previews when configured` | 不迁移 | TUI 渲染层 |
| `renders built-in bash truncation metadata without duplicating the upstream footer` | 不迁移 | TUI 渲染层 |
| `renders byte-limit bash truncation warnings` | `OutputSpoolTest.captureBudgetStopsFileCaptureWithoutFailingTheCall` | 只承接机器可判定事实 `captureTruncated`；告警文案不迁移 |
| `starts and clears bash elapsed-time refresh intervals` | 不迁移 | TUI 计时刷新 |
| `repairs lost isError flags in tool_result handlers` | `BashCapabilityTest.exitOutcomesCarryAuthoritativeExitCode` | 非零退出在能力层就标记 `error=true`，因此不需要按文本特征事后修补 |
| `enables the default base tool set and create_goal without injecting an empty base guide` | 不迁移 | 工具集装配属于 Contributor/Branch settings 层，由 `BuiltinHarnessContributorTest` 冻结目录 |
| `preserves an explicit active tool set` | 不迁移 | Branch settings 层 |
| `removes retired task from explicit active tool sets` | 不迁移 | Branch settings 层 |
| `falls back to the default tool set and create_goal when only retired task was active` | 不迁移 | Branch settings 层 |
| `removes built-in tools from explicit active tool sets` | 不迁移 | Branch settings 层 |
| `removes built-ins before cleaning the automatic task tool` | 不迁移 | Branch settings 层 |
| `leaves no active tools when only a built-in tool was explicitly active` | 不迁移 | Branch settings 层 |
| `preserves tools supplied by other extensions, including built-in name overrides` | 不迁移 | Branch settings 层 |
| `syncs LSP after successful write` | 不迁移到 bash | LSP 同步由 edit capability 承担，不属于 bash 映射 |
| `isolates LSP server config across two project settings` | 不迁移到 bash | LSP 配置隔离由 LSP capability 承担 |

## tests/bash-command-analyzer.test.ts（25 例）

pi-base 的这份用例不启动 shell，只断言命令文本的静态分析结果。kk-studio 把同一职责放在权限核心
[`BashSurfaceAnalyzer`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/permission/BashSurfaceAnalyzer.java)：
[`PermissionEvaluator`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/permission/PermissionEvaluator.java)
先对带文本 `command` 字段的调用做 `analyze`，再用每个静态段的 `buildCandidates` 结果匹配 wildcard 规则，因此命令文本的
错误分段会直接变成权限误判。

对应关系：pi-base 的 `kind: "supported" / "unsupported"` 与 `reason` 对应 Java 的 `Analysis.supported()` /
`reason()` / `segments()`；pi-base 测试里的 `segments()` helper 对应测试基座中的 `staticSegments()`（先要求
`supported`，再比较静态段）。`unsupported` 在权限层的语义——只有完整命令被明确的 `DENY` 规则命中才拒绝，否则一律
`ASK`，绝不猜测内部命令——由 `BashSurfaceAnalyzerTest.unsupportedIsDeniedOnlyByCompleteCommandMatch`、
`combinesCompositeSegmentActions`、`appliesCommandSurfaceAnalysisWheneverCommandFieldIsTextual`、
`handlesMalformedSurfaceConservatively` 守住，本节不重复。

25 例全部适用：静态面分析只依赖命令文本，不涉及 shell、终端与渲染，没有因产品协议差异需要改写的用例。下表左列沿用
pi-base 用例名，右列是承接它的 Java 用例（`Analyzer` 即 `BashSurfaceAnalyzerTest`，`Coverage` 即
`BashSurfaceAnalyzerCoverageTest`），标注「补」的断言是按同一语义补上的漏测。

最近一次 `mvn -o -pl harness/runtime -am test`（1574 个用例，0 失败）下 `BashSurfaceAnalyzer` 的 JaCoCo 行覆盖为
454/464 = 97.8%，分支覆盖 209/270 = 77.4%；未覆盖的 10 行集中在无法由命令文本稳定触发的防御性分支（空段提前返回、
若干转义回退与注释内的 heredoc 消费）。

| pi-base 用例 | kk-studio 承接 | 说明 |
| --- | --- | --- |
| `keeps simple commands as one static surface segment` | `Coverage.keepsPlainCommandsAsSingleStaticSegment`（补） | 没有控制运算符的命令就是一个静态段，`reason` 为空 |
| `splits top-level command chains and pipelines` | `Coverage.handlesGroupingAndOperatorVariants`（补 `\|\|`、`;`、`\|&` 断言） | `&&`/`\|\|`/`;` 与 `\|`/`\|&` 都在顶层拆分 |
| `splits background command separators without splitting fd redirections` | `Coverage.handlesGroupingAndOperatorVariants`（补 `2>&1` 断言）、`Analyzer.splitsOnlyStaticTopLevelSegments` | `&>`、`>\|`、`2>&1` 是重定向而不是后台分隔符 |
| `does not split separators inside single or double quotes` | `Analyzer.splitsOnlyStaticTopLevelSegments`（补双引号断言） | 单引号与双引号内的 `&&`、`;`、`\|` 都是数据 |
| `does not split escaped separators` | `Analyzer.splitsOnlyStaticTopLevelSegments`（补反斜杠转义断言） | 反斜杠转义的 `;` / `\|` / `&&` 都不分段 |
| `marks command substitutions as unsupported instead of trusting their static wrapper` | `Coverage.reportsMalformedSyntaxReasons`、`Coverage.reportsReasonForWrapperAndSubstitutionSurfaces` | 原因固定为 `command_substitution`，段为空，绝不把外层命令当成执行面 |
| `keeps quoted or escaped command-substitution markers literal` | `Coverage.keepsQuotedAndEscapedMarkersLiteral`（补）、`Analyzer.unsupportedIsDeniedOnlyByCompleteCommandMatch` | 单引号内容、`\$`、转义反引号与 `$((` 算术展开都保持 literal |
| `marks process substitutions as unsupported but preserves literal markers` | `Coverage.reportsReasonForWrapperAndSubstitutionSurfaces`（补）、`Coverage.keepsQuotedAndEscapedMarkersLiteral`（补）、`Coverage.reportsMalformedSyntaxReasons` | 原因固定为 `process_substitution`；`'<(...)'`、`"<(...)"`、`\<(` 是字面量 |
| `marks compound shell syntax unsupported instead of trusting its outer segment` | `Coverage.reportsReasonForCompoundDynamicAndRedirectionSurfaces`（补原因断言）、`Coverage.handlesGroupingAndOperatorVariants`、`Coverage.detectsDynamicExecutableForms` | 子 shell、brace group、函数、控制流与 `!` 统一归为 `compound_shell_syntax` |
| `does not split separators inside double-bracket tests` | `Coverage.keepsDoubleBracketTestsAndWordHashesLiteral`（补）、`Coverage.handlesGroupingAndOperatorVariants` | `[[ ... ]]` 内的 `&&`/`\|\|` 与带引号的分隔符都不分段 |
| `ignores comments and treats non-comment hashes as ordinary characters` | `Coverage.keepsDoubleBracketTestsAndWordHashesLiteral`（补）、`Analyzer.splitsOnlyStaticTopLevelSegments` | 行内注释被丢弃，`foo#bar` 里的 `#` 是普通字符 |
| `handles line continuations and CRLF newlines` | `Coverage.normalizesContinuationsAndCrlfBeforeSegmenting`（补）、`Coverage.tokenizesQuotedEscapedNestedAndCommentedInput` | 归一化先于分段：反斜杠换行与 `\r\n` 都不产生伪命令段 |
| `keeps heredoc bodies with the command that owns them` | `Coverage.keepsHeredocBodiesWithTheirOwningSegment`（补）、`Coverage.handlesHeredocDelimiterVariants` | 正文跟随拥有它的命令段，跨管道、`&&` 与多 delimiter 都不外泄成独立段 |
| `rejects command substitution in expanding heredocs but preserves quoted delimiters` | `Coverage.handlesHeredocDelimiterVariants`（补段与算术展开断言） | 只有可展开 delimiter 才把命令替换当动态内容；quoted/escaped delimiter 的正文是字面量 |
| `rejects nested command substitutions before interpreting their inner comments` | `Coverage.reportsReasonForWrapperAndSubstitutionSurfaces`（补） | 替换内部的注释不能恢复静态允许，原因与空段按替换归类 |
| `reports unterminated heredocs instead of treating the body as commands` | `Coverage.keepsHeredocBodiesWithTheirOwningSegment`（补段断言）、`Coverage.handlesHeredocDelimiterVariants` | 原因固定为 `unterminated_heredoc`，未闭合正文不被当成命令 |
| `marks executable names with runtime expansion as unsupported` | `Coverage.reportsReasonForCompoundDynamicAndRedirectionSurfaces`（补原因断言）、`Coverage.detectsDynamicExecutableForms`、`Coverage.keepsQuotedAndEscapedMarkersLiteral` | 赋值前缀、拼接与引号包裹的展开都归为 `dynamic_command_name`；静态引号是字面量 |
| `marks redirections attached to or preceding the executable as unsupported` | `Coverage.reportsReasonForCompoundDynamicAndRedirectionSurfaces`（补原因断言）、`Coverage.detectsDynamicExecutableForms`、`Coverage.keepsQuotedAndEscapedMarkersLiteral` | 可执行位置前后的重定向归为 `command_redirection`，引号或转义的重定向符号是字面量 |
| `marks shells, launchers, eval, and source wrappers as unsupported` | `Coverage.reportsReasonForWrapperAndSubstitutionSurfaces`（补原因与混淆可执行名断言）、`Coverage.detectsDynamicExecutableForms`、`Coverage.keepsQuotedAndEscapedMarkersLiteral` | shell、`b'a'sh`、`/bin/b"a"sh`、`env`/`command`/`exec`/`nohup`、`eval`/`source`/`.` 都归为 `dynamic_shell_wrapper`，引号内的包装器字样仍是字面量 |
| `reports unsupported malformed surface syntax instead of guessing` | `Coverage.reportsMalformedSyntaxReasons`（补空段断言）、`Analyzer.handlesMalformedSurfaceConservatively` | 未闭合引号/括号与多余右括号给出具体原因，且不产生静态段 |
| `tokenizes quoted and nested surface words without expanding them` | `Coverage.tokenizesQuotedEscapedNestedAndCommentedInput`（补） | tokenizer 保留引号与嵌套括号，不做任何展开 |
| `builds prefix candidates for ordinary commands` | `Coverage.buildsStableCandidateListsWithoutExpandingRuntimeContent`（补精确列表） | 候选按「整段 + 逐层前缀」稳定排序，规则匹配不依赖顺序 |
| `builds executable candidates after environment assignment prefixes` | `Analyzer.buildsAssignmentAndStaticExecutableCandidates`（补多 token quoted assignment 断言） | 赋值前缀保留，同时给出剥离赋值后的 executable 候选 |
| `adds normalized executable candidates for static quoting, escaping, and paths` | `Analyzer.buildsAssignmentAndStaticExecutableCandidates`（补 `rm -rf tmp` 断言） | 静态 quoting、escaping 与绝对路径都归一化到 basename 候选 |
| `keeps direct candidate generation lexical without expanding runtime content` | `Coverage.buildsStableCandidateListsWithoutExpandingRuntimeContent`（补） | 候选只来自静态字面量，包装器参数与替换内容都进不了候选 |

## kk-studio 侧新增的测试

以下行为在 pi-base 没有对应用例，但同样是 bash 契约的一部分：

| 测试 | 覆盖的事实 |
| --- | --- |
| `DaemonRuntimeTest.timeoutHandoffDeliversCapturedOutputInFailedTerminal` | 超时终态仍是 `FAILED`，且正文携带能力已捕获的输出 |
| `DaemonRuntimeTest.cancelHandoffDeliversCapturedOutputInCancelledTerminal` | 取消终态仍是 `CANCELLED`，`reason` 携带已捕获输出，绝不报告为 `COMPLETED` |
| `DaemonRuntimeTest.handoffFallbackConvergesWithoutCapabilityCompletion` | 能力未在收尾预算内提交终态时由运行时兜底收敛，迟到回调被终态仲裁拦截 |
| `DaemonRuntimeTest.handoffDropsCapabilityTextBeyondWireLimit` | 超过 16 MiB 载荷上限的能力输出被整体丢弃，终态仍然有界送达 |
| `DaemonRuntimeTest.handoffKeepsRuntimeVerdictWhenCapabilityFailsInsideWindow` | 收尾窗口内能力失败时同时保留裁决原因与能力失败说明 |
| `DaemonRuntimeTest.handoffCommitsImmediatelyWhenFallbackCannotBeScheduled` | 调度器已停机时立即以自己的终态收敛，不把调用悬在收尾窗口里 |
| `DaemonRuntimeTest.shutdownDuringHandoffStillConvergesTerminal` | 收尾窗口内的停机立即收敛，关闭不等待能力配合 |
| `BashCapabilityTest.runtimeTimeoutTerminationReportsTimedOutOutcome` | 运行时的超时收尾不会被报告成取消 |
| `BashCapabilityTest.runtimeCancelTerminationReportsCancelledOutcome` | 运行时的取消收尾按取消语义产出说明 |
| `BashCapabilityTest.naturalExitReapsBackgroundDescendantsBeforeTerminalCallback` | 自然退出时终态通知发生在整组收敛之后，后台后代不会在调用结束后继续存活 |
| `BashCapabilityTest.naturalExitCompletesEvenWhenBackgroundJobHoldsStdout` | 后台作业持有 stdout 时自然退出仍然立刻结束，排空循环不会等到作业自己退出 |
| `BashCapabilityTest.userExitTrapAndExactExitCodeSurviveTheScope` | 用户 EXIT trap 的输出与精确退出码原样穿过执行范围 |
| `BashCapabilityTest.execReplacedShellKeepsTheCommandExitCode` | `exec` 替换 shell 后进程仍在范围内，退出码保真 |
| `BashCapabilityTest.disownedBackgroundJobIsReapedOnNaturalExit` | `disown` 只影响 shell 作业表，被摘掉的同组作业仍随自然退出收敛 |
| `BashCapabilityTest.nestedForkDescendantsAreReapedOnNaturalExit` | 嵌套 fork 的后代仍在同一执行范围内并被收敛 |
| `BashCapabilityTest.termIgnoringDescendantIsForceKilledOnTimeout` | 忽略温和信号的后代必须在强杀阶段收敛，不得在终态之后继续写入 |
| `BashCapabilityTest.privateScopeStateIsRemovedAfterEveryOutcome` | 调用私有的进程范围状态目录在调用结束时被删除 |
| `ProcessScopeTest.naturalExitKeepsTheExactCommandExitCode` | 命令自然退出码来自 helper 的原子发布，且 helper 在退出前已经发布整组收敛事实 |
| `ProcessScopeTest.missingExecutableIsReportedAsScopeFailure` | 命令无法启动时在已建立范围内失败关闭，并发布失败原因 |
| `ProcessScopeTest.liveScopeIsNotReportedAsConverged` | 收敛判定必须看见活着的命令：发过终止信号不等于已经收敛 |
| `ProcessScopeTest.concurrentTerminationIsIdempotentAndConverges` | 并发终止只执行一次收敛，且都在内核确认范围消失之后返回 |
| `ProcessScopeTest.closeRemovesThePrivateStateDirectory` | 私有状态目录只在调用期间存在，`close` 必须删除它 |
| `ProcessScopeTest.outputIsForwardedVerbatim` | 输出原样穿过执行范围，不额外增加内容也不截断 |
| `ProcessScopeTest.missingDaemonClasspathFailsClosedWithoutLaunchingAHelper` | 无法定位类路径时不启动无法承载 helper 的 JVM |
| `ProcessScopeTest.helperThatDiesBeforePublishingTheScopeIsReportedAsStartupFailure` | helper 未发布 scope 就退出时必须失败关闭，不退化成无范围执行 |
| `ProcessScopeTest.corruptedExitStateIsRejected` | 状态文件被破坏时显式失败，绝不把不可读内容当成退出码 |
| `ProcessScopeTest.closeIsIdempotent` | 重复 close 不抛出，也不改变已收敛的范围 |
| `ProcessScopeTest.cancelledBeforeThePermitLeavesNoSideEffect` | 许可之前取消：命令从未启动，没有副作用，也不留状态目录 |
| `ProcessScopeTest.publishedScopeIdIsTheHelperProcessGroup` | 发布的 scope id 必须等于 helper 自己的进程组，父进程只在此基础上发信号 |
| `ProcessScopeTest.cancelBeforeTheReleaseKeepsTheCommandUnspawned` | 收敛开始时命令还没有派生：命令绝不会被派生（pid 文件不存在），且收敛结论由 helper 自己发布 |
| `ProcessScopeTest.cancelRacingWithTheSpawnStillConvergesRootAndDescendants` | 取消与派生真的交叉时，无论谁先拿到锁，根进程与忽略温和信号的后代都必须由内核确认收敛 |
| `ProcessScopeStateTest` | 握手面的真实失败形态：目录不可写时发布显式失败且不留半文件、失败报告本身发布失败只能被吞掉、文件读不到按未发布处理、成员删不掉时清理无副作用、清理幂等 |
| `ProcessScopeTest.helperDiagnosticsStayOutOfTheCommandOutput` | helper 自己的诊断（含 JVM 启动提示）只进诊断文件，命令输出一个字节都不多 |
| `WindowsCommandLineTest` | Windows 命令行 argv 拼装规则（空白、空参数、引号与尾部反斜杠转义） |
| `WindowsJobScopeTest` | Job 相关结构的原生布局（64/144/48 字节）与 Job 名从状态目录派生（跨平台可执行，Windows 上是真实布局校验） |
| `ProcessScopeCrossPlatformTest.naturalExitConvergesLiveChildren` | 根进程自然退出时：子进程自己写下原生 pid（夹具存活 600 秒）后必须被整组收敛，与平台 shell 无关 |
| `ProcessScopeCrossPlatformTest.terminateConvergesNestedProcesses` | 终止必须覆盖孙进程：三层真实 Java 进程的原生 pid 在收敛后都不再存活 |
| `ProcessScopeCrossPlatformTest.unpermittedStartNeverRunsTheFixture` | 许可之前取消：夹具连自己的 pid 文件都不会写出，即从未执行过任何指令 |
| `ProcessScopeCrossPlatformTest.stdinEofLetsTheFixtureExitNaturally` | stdin 关掉写端后命令必须读到 EOF 并自然退出：夹具读 `System.in` 到 EOF 再以退出码 42 结束，不经过任何平台 shell |
| `BashCapabilityTest.preCancelledCallDoesNotStartShellOrTouchStore` | 启动前已取消的调用不启动 helper、不创建中转/durable 文件，也不留下命令副作用 |
| `BashCapabilityTest.preTimedOutCallDoesNotStartShellOrTouchStore` | 启动前已超时的调用共享同一条 fail-closed 检查，且终态仍是超时而非取消 |
| `BashCapabilityTest.closesStdinSoCommandsWaitingForEofFinishNaturally` | 命令以参数传入、不读 stdin：等待 EOF 的命令自然退出，而不是阻塞到超时 |
| `BashCapabilityTest.keepsPayloadBeyondPipeBufferComplete` | 超过管道缓冲与内联阈值的大输出完整捕获并发布全文，字节与行计数忠实 |
| `BashCapabilityTest.mergedStreamsBeyondPipeBufferCompleteWithoutDeadlock` | 合并流两路同时写满管道缓冲时仍必须完成，不能互相等待 |
| `BashCapabilityTest.commandCanTrapTerminationBeforeTheForceKill` | 命令必须能自行处理温和信号：脚本的 `SIGTERM` trap 必须真的执行，优雅收尾不能静默退化成强杀 |
| `BashCapabilityTest.timeoutTerminatesWholeProcessTree` | 超时收尾收敛整个执行范围（含后台后代），收尾后不得继续写入 |
| `BashCapabilityTest.cancellationTerminatesWholeProcessTree` | 取消收尾同样收敛整个执行范围，且不把 kill 退出码当成命令事实 |
| `BashCapabilityTest.rejectedTimeoutSchedulingConvergesBeforeTerminalCallback` | 超时调度被拒（运行时已停机）时，整个执行范围（主进程与后台子进程）在终态通知时已经不存活 |
| `BashCapabilityTest.listenerFailureConvergesAndKeepsCapturedOutput` | 监听器抛错时提交唯一失败终态、发布已捕获输出，且整个执行范围在终态通知前收敛（读端直到收敛后才关闭，后台后代不会因 SIGPIPE 提前脱离可达范围） |
| `BashCapabilityTest.timeoutPublishesSpilledOutputAndKeepsCountsFaithful` | 终态说明不进入 durable 全文，也不计入 `totalBytes`/`totalLines` |
| `OutputSpoolTest.captureBudgetStopsFileCaptureWithoutFailingTheCall` | 达捕获预算只停止文件捕获并继续计数，绝不终止进程 |
| `BuiltinHarnessContributorTest.bashPromptDescribesConfiguredShellWithoutTemplatePlaceholders` | 环境工具提示词不做模板渲染，不得残留占位符 |
| `OutputSpoolTest.publishFailureDegradesToPreviewAndRemovesStagingFile` | durable 发布失败降级为无路径预览，`captureFailed` 为真且不残留中转文件 |
| `OutputSpoolTest.publishFailurePreviewStillReportsCaptureTruncation` | 捕获截断与发布失败同时发生时，预览同时报告两种降级并保留终态说明 |
| `OutputSpoolTest.finishAppendsTerminalNoteOnlyWhenProvided` | 终态说明只在提供时追加，且内联正文不以换行结尾时说明自成一行 |
| `ProcessScopeTest.killVerifiedMemberNeverSignalsAnUnverifiedProcess` | 只有身份被核验过、且确实属于本次调用范围的 pid 才允许收到信号（对应 pi-base 的同名用例） |
| `ProcessScopeTest.terminateLetsTheCommandRunItsTerminationTrap` | 先温和后强制：命令自己的 `SIGTERM` 处理必须真的执行完 |
| `ProcessScopeCrossPlatformTest.duplexStdioCarriesInputAndKeepsStderrSeparate` | 双向标准流：命令真的收到写进 stdin 的字节，stderr 不被合并，EOF 之后自然退出 |
| `ProcessScopeCrossPlatformTest.duplexStdioConvergesLiveChildrenAfterNaturalExit` | 双向标准流下根进程自然退出时，它留下的活着的子进程同样被收敛 |
| `LspClientTest.launchFailureReapsTheServersStubbornChildren` | LSP 启动失败时，服务器留下的、忽略温和信号的子进程也必须一起消失 |

## 已知缺口与平台限制

- **Windows 与 macOS 的原生证据来自 CI 矩阵，本地（Linux）没有证据。** 不带平台 shell 的核心事实由
  `ProcessScopeCrossPlatformTest` 承担：它在三个平台上都用真实 Java 进程层级（含两层嵌套）与原生 pid 说话，且矩阵会断言这组
  用例在每台 runner 上都真跑、一个都不跳过。平台 shell 语义（`bash -lc`、`cmd /c`）与 `WindowsJobScope` 的 kernel32 路径
  仍只能在各自平台上由 `BashCapabilityTest`、`ProcessScopeTest`、`WindowsCommandLineTest` 给出结论，CI 矩阵
  （`.github/workflows/process-scope.yml`，ubuntu/macos/windows）负责这件事。LSP 客户端使用同一个执行范围（双向标准流），
  因此它在 Windows 上的收敛同样由 Job 语义承担、由该矩阵验收（`LspClientTest` 与 `LspClientPoolConcurrencyTest` 的收敛断言）。
- **命令的 stdin 与信号处置是范围的一部分。** 命令的 stdin 是一条只由 keeper 持有写端的空管道（keeper 启动后立刻关闭写端），
  因此「等待 EOF 的命令自然退出」不依赖调用方何时关闭写端，也不依赖 Windows 的句柄继承是否干净；POSIX 上 keeper 保留 JVM 默认的
  `SIGTERM` 处置，`exec` 因此把它复位为默认，命令可以注册自己的 `trap`；keeper 在命令 fork 完成之后才忽略 `SIGTERM`（忽略状态
  绝不进入命令），并由发布范围之前注册的收敛 hook 承担「温和信号 → 宽限 → 强杀 → 发布收敛结论」，与自然退出主路径 CAS 竞争。
  「命令有没有被派生过」与「不再派生」在同一把锁内成立，因此「从未派生」是一条确定性的「本次调用没有任何成员」证明：许可超时、
  工作目录不存在这类启动失败直接发布 `cleanup=true`，既不扫描也不向整组广播强杀（广播会打到 keeper 自己，让父进程把真实失败原因
  看成「被信号杀掉」）。
- **两种标准流模式共用同一个范围。** 默认（捕获）模式里命令的 stdin 是一条只由 keeper 持有写端的空管道、stderr 与 stdout 合并进
  捕获流，命令的输出就是调用结果；LSP 这类常驻双向协议走双向模式（`ProcessScope.startDuplex`），命令的 stdin/stdout/stderr 直接
  继承调用方持有的三条流、stderr 保持独立，EOF 只在调用方关闭自己那一端时到达，helper 自己的 Java 诊断仍然只写调用私有的诊断文件。
  两种模式的差异是真实的平台事实而不只是参数：POSIX 侧双向模式不做 `dup2(1, 2)`，Windows 侧双向模式把三条标准句柄（去重后）与 Job
  一起写进创建属性，而不是新建一条 stdin 管道。收敛、退出码与失败关闭两条模式完全一致。
- **Windows 上必须显式指定 Git Bash。** 命令解析遵循 `CreateProcess` 的搜索顺序，系统目录永远先于 `PATH`，因此系统里存在
  WSL 时裸名 `bash` 会命中 `System32\bash.exe`（打印「no installed distributions」并以退出码 1 结束），命令一行都不会执行。
  Daemon 侧由 operator 用 `--bash-executable` 指向 Git Bash；`BashCapabilityTest` 显式挑选 Git Bash，找不到时以「缺少环境前置
  条件」跳过，而不是把 WSL 的失败伪装成能力行为。CI 的 Windows runner 自带 Git Bash（矩阵自己的 `shell: bash` 用的就是它），
  因此该平台照常验收命令执行；只有那两个用**裸名** `bash` 的遗留编码能力夹具（`CodingCapabilitiesTest`、
  `CodingCapabilitiesEdgeTest`）不参与 Windows 腿——它们不是执行范围的用例，仍由 Linux 完整套件与 macOS 覆盖。
- **命令不存在时的失败形态与平台有关。** POSIX 上命令进程在范围建立之后才 `exec`，失败发生在已建立的范围内；Windows 上首个
  进程必须先创建并归属 Job，命令不存在意味着这一步无法完成，于是表现为范围建立失败。两条去向都是失败关闭、都带上命令名，
  调用方（`BashCapability`）对两者的终态都按失败处理。
- **Windows 侧首个进程在放行之前一直挂起，且归属与创建是同一件事。** 命令进程由 `PROC_THREAD_ATTRIBUTE_JOB_LIST` 在创建时就
  直接进入 Job（不再有「创建后归属」的间隙，helper 在归属之前被打死也不会留下无主挂起进程），并保持 suspended 直到父进程持有
  同一个 Job 的句柄；因此取消（即使在启动阶段）只需要终止整组，不会留下任何执行过命令逻辑的进程。helper 在许可之前失败时自己结束
  这个从未运行过的挂起进程，父进程也持有同一个 Job 的句柄作为第二重保证。
- **范围不是恶意命令沙箱。** 命令主动 `setsid`/`set -m` 重新分组、或把进程交给其它 session 时不受这条边界约束；本能力
  不承诺阻止自我再分组，也不对这类逃逸做伪装。
- **helper 的覆盖数据默认不进主报告，但可以测量。** helper 在独立 JVM 中执行，父进程的 JaCoCo 报告天然看不到它；需要 helper
  覆盖率时显式打开开关（`-Dkk-studio.process-scope.helper-coverage=true`），helper 会被同一个代理插桩，数据收集到
  `target/jacoco-helper/*.exec`，用 `mvn org.jacoco:jacoco-maven-plugin:merge -DdestFile=... fileSets=...` 或 JaCoCo CLI
  合并后即可在报告里看到 `ProcessScopeHelper`/`PosixProcessGroup` 的 helper 侧行覆盖。默认关闭是因为被插桩的 helper 冷启动更慢，
  会把短超时用例的时序推向边界。`WindowsJobScope` 与 `WindowsCommandLine` 依旧只能由 Windows runner 覆盖。
- **三平台合并覆盖率是 CI 的固定门禁入口，且门禁是核心路径合计而非逐类。** 执行范围的核心路径是七类：`ProcessScope`、
  `ProcessScopeHelper`、`PosixProcessGroup`、`ProcessScopeState`、`WindowsJobScope`、`WindowsCommandLine`、`BashCapability`。
  把 Windows 实现或 bash 能力留在集合外，等于用「最关键的平台没有数字」换门禁通过，因此它们一并纳入，门禁只要求这七类的
  **合计**行覆盖达到 90%（逐类差异很大，逐类阈值会把不可达分支当成失败信号），但每一类的数字都打印出来。
  矩阵每台 runner 都带 `-Dkk-studio.process-scope.helper-coverage=true` 运行并总是上传 `jacoco.exec`、`jacoco-helper/*.exec`
  与 `target/classes`；合并作业先核对三份 class 文件逐字节一致（不一致会让 JaCoCo 按 class id 静默丢 session），再合并出
  报告并核对「七类必须全部出现在报告里」。
  跨平台数据不能与单平台数据混着报数：类文件一变，同一份 `jacoco.exec` 就不再对应同一个 class id，因此门禁数字只能来自同一
  次矩阵的三份产物。矩阵按平台裁剪要跑的类（用裸名 `bash` 的遗留编码能力夹具只在 Linux/macOS 上跑），断言脚本的平台要求与
  这条裁剪一致；Windows 腿另外点名要求 `BashCapabilityTest` 的「命令的 stdin 是确定性 EOF」与「超时预算溢出不退化成立即超时」
  两条必须真跑且不得跳过，这样「Windows 的命令执行由真正的 Git Bash 承担」是被锁住的实证而不是默认假设。
  单平台与合并后的数字必须分开看，因为它们测的不是同一件事：本地（Linux 完整套件，同一套 helper 覆盖收集）可复现的是单平台
  合计 **86.9% (873/1005)**，与门禁要求之间的差额正是只有对应平台才会执行的分支——Windows 的 Job 路径（helper 的
  `runWindows` 与 Windows 分派、`ProcessScope` 的 Job 分支、`WindowsJobScope` 的句柄路径）与 macOS 上的非 Linux 判定；
  合并后的合计数字由门禁在同一次矩阵的三份产物上算出并要求达到 90%。剩下的未覆盖行是「信号被内核拒绝、调用线程被中断、
  helper 拒绝退出」这类只在异常时序到达的失败关闭分支，门禁不去掩盖它们，也不通过放宽阈值换绿色。
- **保活与身份核验是两件事。** 「keeper 先死、后代还在」时不能照着快照直接发信号：快照与强杀之间存在时间差，pid 可能已被复用。
  `ProcessScope` 因此对每个成员重新核验「仍然存活、启动时刻与快照一致、此刻仍属于本次进程组」之后才强杀，任一不成立就只报告未收敛。
  这条性质由 `ProcessScope` 的「keeper 被杀之后仍收敛」与「绝不向未核验进程发信号」两条用例守卫。
  识别「组里还有谁」依赖 `/proc`，因此这条兜底只在 Linux/WSL 上成立：没有成员枚举能力时父进程只能如实报告未收敛，不可能在
  没有身份信息的前提下逐个核验并强杀。这不是本能力承诺的场景——命令自然退出（含超时与取消）时收敛由 keeper 自己完成，只有
  keeper 被外部强杀才轮到这条兜底；不可判定也绝不等于收敛（那会让收尾跳过强杀阶段）。
- **本地存储自愈与重试不迁移。** 私有目录被外部删除或本地存储暂时不可用时，kk-studio 只降级为有界预览，
  在下一次调用重建存储；输出内容与失败终态都不受影响。
- **渲染层（折叠行、行首空行、计时刷新、截断告警文案）在 kk-studio 没有对应实现**，因此这些用例以「不迁移」
  记录，而不是以 Java 断言伪造覆盖。
- **I/O 失败分支未覆盖。** `OutputSpool` 中「文件通道 flush/close/写入抛 IOException」的分支
  （`closeFileStreams` 与 `captureBytes` 的 catch）需要注入可失败的 `FileChannel`，当前没有确定性构造手段，
  因此保持未覆盖；同一降级语义已由「中转文件创建失败」与「durable 发布失败」两条路径覆盖。
