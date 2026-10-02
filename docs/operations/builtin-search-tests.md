# 内置检索的行为验证

`fs.grep` 与 `fs.find` 由 Daemon 内的 Java 实现遍历、解码和匹配，不启动 ripgrep/fd。
验证重点是“哪些路径真正搜索过、命中如何定位、遇到错误是否漏报”，而不是外部命令参数或退出码。
能力契约见[内置工具设计](../modules/builtin-tools-design.md)，进程与输出存储见
[Bash 行为验证](builtin-bash-tests.md)。

## 运行与证据

从仓库根目录使用 JDK 21：

```bash
env JAVA_HOME="$JAVA_HOME_21" mvn -pl harness/daemon -am test
env JAVA_HOME="$JAVA_HOME_21" mvn -pl harness/daemon -am validate
```

这些测试使用真实本地文件系统、临时文件、权限、符号链接与稀疏文件，不调用模型或部署数据库。
检索测试本身不启动外部检索二进制；上述整个 Daemon 套件还会运行 Bash/LSP 的真实子进程测试。
`validate` 只做 Checkstyle/Spotless 等静态检查，不编译、不执行检索。

| 测试类 | 负责的证据 |
| --- | --- |
| [`NativeSearchCapabilitiesTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/NativeSearchCapabilitiesTest.java) | 匹配、编码、上限、特殊文件、未搜索路径、超时与取消 |
| [`FindGrepCapabilitiesTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/FindGrepCapabilitiesTest.java) | glob、结果格式、全文落盘、`SearchFiles` 遍历 |
| [`GitIgnoreDiscoveryTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/GitIgnoreDiscoveryTest.java) | 祖先 `.gitignore`、`info/exclude`、`.git` 文件与 linked worktree 的发现 |
| `CodingCapabilitiesTest`、`CodingCapabilitiesEdgeTest` | schema 参数、有效调用预算、注入执行器与取消 |
| `WorkdirPathSemanticsTest` | 每次调用独立的路径解析基准、相对路径、缺失或不可用 workdir |

只跑检索可用上述类名筛选 `-Dtest`，同时保留 `-am -Dsurefire.failIfNoSpecifiedTests=false`，
并确认 Daemon 的 `target/surefire-reports` 有实际执行记录。`test` 生成
`harness/daemon/target/site/jacoco`；Daemon 不绑定 `jacoco:check`。
度量核心是 `GrepCapability`、`FindCapability`、`SearchFiles`、`SearchControl`、`GitIgnoreRules`、
`GlobPattern`、`TextStreams`，行覆盖率目标 ≥90%，分支作为参考，不固定某次运行数字。

## 先验证检索范围

绝对 path 不需 workdir，相对 path 必须有显式绝对 workdir，不继承 Daemon cwd。
`WorkdirPathSemanticsTest.eachInvocationUsesItsOwnWorkdir`、
`relativePathsResolveFromExplicitWorkdir`、`relativeWorkdirIsRejected`、`unusableWorkdirIsRejected`
守卫这条边界。schema 必填、类型和未知参数由
`CodingCapabilitiesEdgeTest.everyDescriptorRejectsWrongTypedAndUnknownArguments` 验证。

忽略规则从**检索目标自身**向上发现，与调用 workdir 无关；`.git` 目录或文件界定仓库上界。
`GitIgnoreDiscoveryTest` 是发现规则的直接证据，`NativeSearchCapabilitiesTest` 与
`FindGrepCapabilitiesTest` 验证规则对实际结果的影响。
符号链接的遍历行为由 `searchFilesIgnoresSymlinksAndValidatesDirectory` 固定；
缺失或不可读根不是“空结果”（`searchRejectsMissingPath`、`searchRejectsUnreadableSearchRoot`）。

## Grep 的成功、空结果与不完整结果

| 行为 | 主要断言 |
| --- | --- |
| literal/regex、ignore_case、include，不重复输出同一行 | `NativeSearchCapabilitiesTest.grepSupportsLiteralRegexIgnoreCaseAndIncludeWithoutDuplicateLines`、`FindGrepCapabilitiesTest.grepIgnoreCaseAndIncludePatternFilter` |
| 非法正则明确失败 | `grepRe2jRegexSyntaxValidAndInvalid`、`grepRejectsDirectBinarySkipsDirectoryBinaryAndReportsInvalidRegex` |
| 单文件与目录的定位输出、长行命中附近摘录 | `grepDirectFileSearchAndErrors`、`grepMatchCenteredExcerptForLongLines`、`grepFindsMatchInSecondHalfOfOverLongLine` |
| multiline 命中计数与覆盖行去重 | `grepMultilineHonorsLimit`、`grepMultilineReportsCoveredLinesOnce`、`grepMultilineSearch` |
| 结果上限与长行截断说明 | `searchLimitsStayInlineAndTruncateLongLines`、`grepMatchCenteredExcerptForLongLines` |
| 真正无匹配 | `grepReturnsNoMatchesFoundWhenEmpty`、`grepMultilineHandlesEmptyAndBinaryFiles` |
| 未搜索路径有界枚举，不能伪装成无匹配 | `grepEnumeratesAndBoundsUnsearchedPaths`、`grepReportsUnreadableDirectFile` |
| 大结果在共享输出层落盘，返回有界预览与路径 | `grepSpoolsLargeResultsToBoundedTextResultWithPath` |

定位形态是相对路径、行号与命中文本，不添加展示锚点，也不透传外部程序的杂项输出。
只支持严格 UTF-8 与 BOM 标记的 UTF-16LE/BE；直接二进制目标报错，目录中的二进制文件跳过。
非法旧编码不能静默替换成乱码或漏报匹配（`grepRejectsInvalidTextEncodingInsteadOfMissingMatches`、
`grepDetectsBinaryContentBeyondProbePrefix`）。多行扫描有独立整文件预算，不应把它误写成 read 的文件大小上限。

## Find 的路径匹配与停止条件

- `findMatchesBasenameAndPathGlobPatternsDeterministically`、
  `globPatternsArePlatformIndependentAndSegmentAware`、
  `findMatchesBasenamesAndSearchRelativePathsInDeterministicOrder`：不含 `/` 的模式匹配 basename，
  含 `/` 的模式匹配检索相对路径，结果顺序确定。
- `findPreservesTrailingSpaceInMatchedFileName`：不 trim 文件名。
- `findEarlyStopsAtLimitPlusOne`：达到结果上限后用额外一个结果确认截断，而非无界遍历。
- `findReturnsNoFilesFoundWhenNoMatches` 与 `findReportsUnsearchedPathsWhenNothingMatches`：
  空结果与不可搜索路径不同，后者不能当成功的无匹配。
- `findSpoolsLargeResultsToBoundedTextResultWithPath`：字节预算由共享输出层负责，全文发布后才交付有界预览。

全文发布的权限、原子性和存储失败降级由 `OutputSpoolTest`、`TextOutputStoreTest` 验证，
见[Bash 输出与协议终态](builtin-bash-tests.md#输出与协议终态)；不能用一条检索落盘成功测试宣称所有存储失败已覆盖。

## 取消、超时与能力边界

`searchCapabilitiesConsumeResolvedRequestTimeout` 固定有效预算进入检索。
`grepSingleLineTimesOutWhileScanningManyLines`、`grepMultilineTimesOutDuringWholeFileScan`、
`findTimesOutOnTinyDeadline` 验证扫描能截止；`deadlineCheckedSequenceEnforcesTimeoutMidScan`、
`deadlineCheckedSequenceSurfacesCancellationAsInterruption`、`searchControlActivelyChecksTimeoutAndCancellation`
验证扫描中的检查点。取消不能被误报成超时，已取消调用也不能继续遍历。

检索没有外部二进制获取、force-kill watchdog、非零退出附带部分输出或上游 fallback。
capability 注册与结果协议由 [`harness/environment`](../../harness/environment)、Daemon Runtime 负责，终端渲染由前端负责，
不属于本文的检索覆盖率口径。权限、特殊文件与 symlink 测试的跳过项需按宿主平台报告；
一个平台通过不等于全部原生文件系统已验收。
