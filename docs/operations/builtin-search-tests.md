# 内置检索测试映射

`fs.grep` 与 `fs.find` 在 kk-studio 里由 Daemon 内的纯 Java 实现承担，pi-base 的同类行为则由
ripgrep / fd 子进程加一层 TypeScript 工具包装实现。两者对外契约一致，内部分层、失败面和资源边界并不同，
因此 pi-base 的测试不能按文件平移。本文件逐用例给出源用例、适用性、kk 对应用例与映射结论，供后续接手者判断
某个行为是否仍然被覆盖，以及为什么某个源用例不再存在。

## 心智模型

- **实现位置**：检索逻辑全部在 `harness/daemon` 的 `coding` 包内，测试是 JVM 内的真实文件系统测试，
  不启动外部检索二进制。
- **能力边界**：Daemon 只负责「给定显式 `workdir` 与 `path`，返回命中行或路径」。schema 校验、prompt、
  调用上下文传递、结果编解码、字节级输出预算与落盘由 harness environment / daemon runtime 承担。
  这些层的行为在 pi-base 里由 `grep` 包装器承担，因此对应源用例属于不同模块。
- **契约差异**：pi-base 依赖 ripgrep/fd 的输出协议（JSON `bytes` 字段、`--full-path`、退出码、
  非零退出时的部分输出、二进制可用性探测）。kk-studio 自己遍历与解码，协议层差异以「显式失败」替代
  「解析外部输出」，相关源用例按此改写或排除。
- **文本编码**：编码只支持 UTF-8 与 BOM 判定的 UTF-16LE/BE。旧编码（如 GBK）字节严格解码失败即按二进制
  显式报错，不产生替换字符，也不静默漏报命中。
- **忽略规则**：从检索目标自身向上解析，与调用 `workdir` 无关。

`映射结论` 一列描述承接关系：`承接` 表示该源用例的行为由右列的 kk 用例负责守住，`不适用` 表示当前没有对应契约（原因写在适用性一列）。断言内容以右列用例为准，每一行的实际通过情况以「复核方式」中的命令输出为唯一依据，本文不记录某一次运行的结论；未被承接的条目不进入回归。

## 证据来源

- [`NativeSearchCapabilitiesTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/NativeSearchCapabilitiesTest.java)：
  原生检索端到端行为，含上限、失败、超时、编码与特殊文件。
- [`FindGrepCapabilitiesTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/FindGrepCapabilitiesTest.java)：
  glob 语义、输出格式、落盘、`SearchFiles` 遍历契约。
- [`GitIgnoreDiscoveryTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/GitIgnoreDiscoveryTest.java)：
  祖先 `.gitignore`、`info/exclude`、`.git` 文件 worktree 的解析。
- [`CodingCapabilitiesTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/CodingCapabilitiesTest.java)、
  [`CodingCapabilitiesEdgeTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/CodingCapabilitiesEdgeTest.java)、
  [`WorkdirPathSemanticsTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/WorkdirPathSemanticsTest.java)：
  参数校验、workdir 语义、有效超时与取消。

## grep 原生路径（`pi-base/tests/grep-native.test.ts`）

| 源用例 | 适用性 | kk 对应用例 | 映射结论 |
| --- | --- | --- | --- |
| `decodes real ripgrep path.bytes and lines.bytes …` | 差异：kk 不做 rg JSON 解码，编码由共享 `TextStreams` 判定（GBK 按二进制显式报错） | `NativeSearchCapabilitiesTest#grepRejectsInvalidTextEncodingInsteadOfMissingMatches`、`#grepFindsMatchInSecondHalfOfOverLongLine` | 承接 |
| `returns the required-argument error for a missing pattern` | 适用（schema 必填校验） | `CodingCapabilitiesEdgeTest#everyDescriptorRejectsWrongTypedAndUnknownArguments` | 承接 |
| `passes ignore_case through to native ripgrep` | 适用（不再有进程参数传递面） | `NativeSearchCapabilitiesTest#grepSupportsLiteralRegexIgnoreCaseAndIncludeWithoutDuplicateLines`、`FindGrepCapabilitiesTest#grepIgnoreCaseAndIncludePatternFilter` | 承接 |
| `skips unparseable ripgrep output lines and formats matches as relative locations` | 差异：无外部输出可解析；等价契约为相对定位且不输出上下文行 | `FindGrepCapabilitiesTest#grepMatchCenteredExcerptForLongLines`、`FindGrepCapabilitiesTest#findMatchesBasenameAndPathGlobPatternsDeterministically` | 承接 |
| `reads the matched line from disk when ripgrep omits line text` | 排除：rg 输出协议专属，kk 自己读文件，不存在缺失行文本的分支 | — | 不适用 |
| `reports match limits and long-line truncation from ripgrep output` | 适用（上限与长行截断语义保留） | `NativeSearchCapabilitiesTest#searchLimitsStayInlineAndTruncateLongLines`、`FindGrepCapabilitiesTest#grepMatchCenteredExcerptForLongLines` | 承接 |
| `leaves byte truncation to the shared tool-output layer` | 适用（kk 同样返回完整命中集合，字节预算在输出层） | `FindGrepCapabilitiesTest#grepSpoolsLargeResultsToBoundedTextResultWithPath` | 承接 |
| `persists complete native grep output before returning the shared truncated preview` | 适用（完整输出落盘后返回有界预览） | `FindGrepCapabilitiesTest#grepSpoolsLargeResultsToBoundedTextResultWithPath`、`OutputSpoolTest`、`TextOutputStoreTest` | 承接 |
| `returns no-match and ripgrep failure results distinctly` | 适用并加强：无匹配仍在但存在未搜索路径时为错误 | `FindGrepCapabilitiesTest#grepReturnsNoMatchesFoundWhenEmpty`、`NativeSearchCapabilitiesTest#grepEnumeratesAndBoundsUnsearchedPaths`、`#grepRejectsDirectBinarySkipsDirectoryBinaryAndReportsInvalidRegex` | 承接 |
| `reports a timeout when ripgrep does not finish before timeout_seconds` | 适用 | `NativeSearchCapabilitiesTest#grepSingleLineTimesOutWhileScanningManyLines`、`#grepMultilineTimesOutDuringWholeFileScan`、`CodingCapabilitiesTest#searchCapabilitiesConsumeResolvedRequestTimeout` | 承接 |
| `applies grep timeout while waiting for ripgrep acquisition` | 差异：无二进制获取阶段；等价契约是 deadline 覆盖整个调用（含扫描） | `NativeSearchCapabilitiesTest#deadlineCheckedSequenceEnforcesTimeoutMidScan`、`CodingCapabilitiesTest#searchCapabilitiesConsumeResolvedRequestTimeout` | 承接 |

## grep 多行行为（`pi-base/tests/grep-multiline-behavior.test.ts`）

| 源用例 | 适用性 | kk 对应用例 | 映射结论 |
| --- | --- | --- | --- |
| `renders grep calls with all optional flags` | 排除：TUI 渲染层，Daemon 不参与渲染；字段本身由 schema 与参数校验覆盖 | `CodingCapabilitiesEdgeTest#everyDescriptorRejectsWrongTypedAndUnknownArguments` | 不适用 |
| `supports multiline grep with relative paths and limit notices` | 适用（`limit` 约束命中数，同一命中的覆盖行整体输出） | `NativeSearchCapabilitiesTest#grepMultilineHonorsLimit`、`#grepMultilineReportsCoveredLinesOnce` | 承接 |
| `truncates long multiline match lines and uses the basename for single-file searches` | 适用 | `FindGrepCapabilitiesTest#grepMatchCenteredExcerptForLongLines`、`#grepDirectFileSearchAndErrors` | 承接 |
| `rejects already-aborted multiline searches` | 适用（取消必须在开始前与扫描中均可响应） | `NativeSearchCapabilitiesTest#deadlineCheckedSequenceSurfacesCancellationAsInterruption`、`#searchControlActivelyChecksTimeoutAndCancellation`、`CodingCapabilitiesEdgeTest#abstractCodingExecutionUsesInjectedExecutorAndInterruptsOnCancel` | 承接 |

## grep 多行错误路径（`pi-base/tests/grep-multiline-errors.test.ts`）

| 源用例 | 适用性 | kk 对应用例 | 映射结论 |
| --- | --- | --- | --- |
| `returns no matches for multiline searches with no results` | 适用 | `FindGrepCapabilitiesTest#grepReturnsNoMatchesFoundWhenEmpty`、`NativeSearchCapabilitiesTest#grepMultilineHandlesEmptyAndBinaryFiles` | 承接 |
| `explains ripgrep regex failures for standard and multiline searches` | 适用（只保留「正则非法即显式错误」，错误文本不再引用 rg） | `FindGrepCapabilitiesTest#grepRe2jRegexSyntaxValidAndInvalid`、`NativeSearchCapabilitiesTest#grepRejectsDirectBinarySkipsDirectoryBinaryAndReportsInvalidRegex` | 承接 |

## find 原生路径（`pi-base/tests/find-tool-native.test.ts`）

| 源用例 | 适用性 | kk 对应用例 | 映射结论 |
| --- | --- | --- | --- |
| `uses full-path matching for slash patterns and relativizes fd output` | 适用（`--full-path` 等价为含 `/` 的 pattern 全路径匹配） | `FindGrepCapabilitiesTest#findMatchesBasenameAndPathGlobPatternsDeterministically`、`NativeSearchCapabilitiesTest#globPatternsArePlatformIndependentAndSegmentAware`、`#findMatchesBasenamesAndSearchRelativePathsInDeterministicOrder`、`FindGrepCapabilitiesTest#findEarlyStopsAtLimitPlusOne` | 承接 |
| `preserves trailing spaces in matched file names` | 适用 | `NativeSearchCapabilitiesTest#findPreservesTrailingSpaceInMatchedFileName` | 承接 |
| `distinguishes empty results from fd execution failures` | 差异：无 fd 退出码；等价契约为无匹配仍是结果、不可搜索路径是错误 | `FindGrepCapabilitiesTest#findReturnsNoFilesFoundWhenNoMatches`、`#searchFilesIgnoresSymlinksAndValidatesDirectory`、`NativeSearchCapabilitiesTest#findReportsUnsearchedPathsWhenNothingMatches` | 承接 |
| `reports fd availability failures before spawning a search process` | 排除：kk 不依赖外部二进制，不存在可用性探测；等价失败面是路径与 workdir 校验 | `NativeSearchCapabilitiesTest#searchRejectsMissingPath`、`#searchRejectsUnreadableSearchRoot`、`WorkdirPathSemanticsTest#relativeWorkdirIsRejected`、`#unusableWorkdirIsRejected` | 不适用（失败面改写） |
| `keeps partial fd output from non-zero exits` | 排除：不启动子进程，不存在「非零退出附带部分输出」状态；不完整结果的对应处理是未搜索路径枚举 | `NativeSearchCapabilitiesTest#findReportsUnsearchedPathsWhenNothingMatches` | 不适用（状态不存在） |
| `leaves byte truncation to the shared tool-output layer` | 适用 | `FindGrepCapabilitiesTest#findSpoolsLargeResultsToBoundedTextResultWithPath` | 承接 |
| `aborts before and during fd execution` | 适用（取消在遍历前与遍历中都必须及时终止） | `NativeSearchCapabilitiesTest#findTimesOutOnTinyDeadline`、`#searchControlActivelyChecksTimeoutAndCancellation`、`#deadlineCheckedSequenceSurfacesCancellationAsInterruption` | 承接 |
| `keeps the default force-kill watchdog after cancellation rejects` | 排除：检索不启动子进程；强制终止契约由承载子进程的能力（命令执行、LSP）验证 | `ProcessScopeTest.terminateLetsTheCommandRunItsTerminationTrap` | 不适用（层次不同） |

## grep/find 工具包装（`pi-base/tests/search-tools.test.ts`）

| 源用例 | 适用性 | kk 对应用例 | 映射结论 |
| --- | --- | --- | --- |
| `returns matching lines from builtin output without adding anchors` | 适用（输出契约：`path:line: text`，不加锚点） | `FindGrepCapabilitiesTest#grepMatchCenteredExcerptForLongLines`、`#grepRe2jRegexSyntaxValidAndInvalid` | 承接 |
| `reports timeout guidance` | 适用 | `NativeSearchCapabilitiesTest#grepSingleLineTimesOutWhileScanningManyLines`、`CodingCapabilitiesTest#searchCapabilitiesConsumeResolvedRequestTimeout` | 承接 |
| `does not misreport parent cancellation as a timeout` | 适用（取消必须区别于超时） | `NativeSearchCapabilitiesTest#deadlineCheckedSequenceSurfacesCancellationAsInterruption`、`CodingCapabilitiesEdgeTest#abstractCodingExecutionUsesInjectedExecutorAndInterruptsOnCancel` | 承接 |
| `reports binary file guidance` | 适用并加强：直接目标是显式错误，目录扫描中的二进制静默跳过 | `NativeSearchCapabilitiesTest#grepRejectsDirectBinarySkipsDirectoryBinaryAndReportsInvalidRegex`、`#grepDetectsBinaryContentBeyondProbePrefix`、`#grepRejectsInvalidTextEncodingInsteadOfMissingMatches` | 承接 |
| `falls through to upstream grep when the search path is missing` | 排除：kk 没有上游工具回退，缺失路径必须显式失败 | `NativeSearchCapabilitiesTest#searchRejectsMissingPath` | 不适用（回退不存在） |
| `preserves no-match output` | 适用 | `FindGrepCapabilitiesTest#grepReturnsNoMatchesFoundWhenEmpty` | 承接 |
| `returns builtin result when no text block is present` | 排除：结果编解码属于 harness environment / daemon codec 层 | `EnvironmentCapabilityContractTest` | 不适用（层次不同） |
| `preserves passthrough lines` | 排除：kk 的 grep 只产生命中行，不存在上游透传行 | — | 不适用 |
| `preserves builtin truncation text and details` | 适用（上限/截断提示保留） | `NativeSearchCapabilitiesTest#searchLimitsStayInlineAndTruncateLongLines` | 承接 |
| `surfaces non-timeout builtin errors` | 适用 | `FindGrepCapabilitiesTest#grepDirectFileSearchAndErrors`、`NativeSearchCapabilitiesTest#grepReportsUnreadableDirectFile` | 承接 |
| `validates required path` | 适用（schema 必填） | `CodingCapabilitiesEdgeTest#everyDescriptorRejectsWrongTypedAndUnknownArguments` | 承接 |
| `passes include to builtin grep as glob` | 适用 | `FindGrepCapabilitiesTest#grepIgnoreCaseAndIncludePatternFilter` | 承接 |
| `passes toolCallId and ctx to builtin grep` | 排除：调用身份与上下文传递属于 daemon runtime / environment，不在检索能力内 | `DaemonRuntimeTest` | 不适用（层次不同） |
| `does not reject legacy-encoded text files as binary before delegating grep` | 差异：kk 只支持 UTF-8 与 UTF-16，GBK 等旧编码按二进制显式报错，不静默替换 | `NativeSearchCapabilitiesTest#grepRejectsInvalidTextEncodingInsteadOfMissingMatches` | 承接（契约改写） |
| `passes multiline to builtin grep when a custom factory is provided` | 适用 | `FindGrepCapabilitiesTest#grepMultilineSearch` | 承接 |
| `supports multiline matches and prefixes every matched line` | 适用 | `NativeSearchCapabilitiesTest#grepMultilineReportsCoveredLinesOnce`、`#grepMultilineHonorsLimit` | 承接 |
| `is registered when piBaseExtension is loaded` | 排除：注册属于 capability catalog | `CodingCapabilitiesTest#registersEnvironmentDescriptorsAndRejectsMalformedArguments`、`EnvironmentCapabilityCatalogTest` | 不适用（层次不同） |
| `uses the current execution cwd` | 差异：kk 要求每次调用显式传 `workdir`，不继承调用方当前目录 | `WorkdirPathSemanticsTest#eachInvocationUsesItsOwnWorkdir`、`#relativePathsResolveFromExplicitWorkdir`、`#omittedWorkdirIsRejectedWithoutDefaultFallback` | 承接（契约改写） |
| `applies timeout_seconds without passing it to the built-in find` | 适用 | `NativeSearchCapabilitiesTest#findTimesOutOnTinyDeadline`、`CodingCapabilitiesTest#searchCapabilitiesConsumeResolvedRequestTimeout` | 承接 |
| `rethrows non-timeout find errors` | 适用 | `NativeSearchCapabilitiesTest#searchRejectsMissingPath`、`#findReportsUnsearchedPathsWhenNothingMatches` | 承接 |
| `uses pi-base raw renderer for find even when collapsed result lines are not configured` | 排除：TUI 渲染层 | — | 不适用 |

## 复核方式

```bash
env JAVA_HOME=$JAVA_HOME_21 mvn -pl harness/daemon -am test
env JAVA_HOME=$JAVA_HOME_21 mvn -pl harness/daemon -am validate   # Checkstyle + Spotless
```

JaCoCo 报告由 `test` 阶段生成到 `harness/daemon/target/site/jacoco`；daemon 模块没有绑定 `jacoco:check`，因此覆盖率是
参考指标而不是构建门禁。核心检索路径（`GrepCapability`、`FindCapability`、`SearchFiles`、`SearchControl`、
`GitIgnoreRules`、`GlobPattern`、`TextStreams`）按仓库「核心路径行覆盖率 ≥ 90%、分支覆盖率作为参考」的约定度量，
达成度以报告为准，本文不固定某次运行的百分比。检索行为属于本机平台能力：需要在真实文件系统
权限、符号链接与稀疏文件上执行，因此没有独立于 JVM 的等价验证入口。
