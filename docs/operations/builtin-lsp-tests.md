# 内置 LSP 的行为验证

LSP 链路从 `--lsp-config` 进入服务器与项目根发现，经真实 stdio JSON-RPC，最终服务
`lsp.goto-definition`、`lsp.workspace-symbols` 与 `lsp.java-decompile`。
验证时分别观察发现、协议、复用和进程清理，不能把假服务器返回成功当成真实 jdtls 已安装或可反编译。
行为定义见[Harness Daemon](../modules/harness-daemon.md)，配置入口见
[Environment Daemon 安装与运行](environment-daemon.md)。

## 运行入口

```bash
env JAVA_HOME="$JAVA_HOME_21" mvn -pl harness/daemon -am test \
  -Dtest='Lsp*Test,CodingCapabilitiesEdgeTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

测试使用自带的假语言服务器，但子进程、分帧与 JSON-RPC 是真实的；会创建临时项目、启动并终止进程，
不依赖 Docker、真实 jdtls、网络或模型。`-am` 保证共同构建依赖模块。
Surefire 结果在 `harness/daemon/target/surefire-reports`，JaCoCo 在 `target/site/jacoco`；
Daemon 不绑定 `jacoco:check`。检查目标类实际执行与平台跳过项，不只看 Maven 退出码。

## 发现正确的服务器和项目根

[`LspDiscoveryTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/LspDiscoveryTest.java)
验证扩展名大小写、JSON 配置拒绝、命令的绝对/家目录路径与安装状态：

- `prefersShallowestRootMarkerInsideGitBoundary` 证明同一 Git 边界内最浅的 root marker 胜过模块内的
  更深 root marker；该用例没有同时构造 first-match marker，不能用它宣称跨类别优先级已被断言。
- `stopsAtWorktreeGitFileBoundary` 与 `fallsBackToFirstMatchMarkerThenFileParent` 验证 `.git` 文件/目录
  是扫描上界、缺 root marker 时的回退；非 Git 项目由 `nonGitProjectUsesFileParentDirectory` 验证。
- `supportRequiresInstalledExecutable`、`resolvesAbsoluteAndHomeRelativeCommands`、
  `windowsExecutableSuffixesFollowPathext` 验证可执行命令解析；Windows 运行期分支仍需 Windows 原生测试。

Daemon 不自动选择 JDK、安装服务器或注入 jdtls `-data`；服务器命令与标记由 operator 显式配置。
`LspDaemonWiringTest.cliConfiguredExtensionsDriveTheRegisteredCapabilities` 验证 CLI 到注册能力的装配，
并断言服务器 cwd 为自动发现的项目根，不是随意的调用目录。

## 参数与请求位置

[`LspCapabilitiesTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/LspCapabilitiesTest.java)
固定能力入口：

| 输入边界 | 证据 |
| --- | --- |
| 绝对 path 不需要 workdir；相对 path 必须有显式绝对 workdir | `absolutePathsNeedNoWorkdir`、`relativePathsRequireAnExplicitWorkdir`，拒绝时不启动服务器 |
| line 必填且正整数，character 缺省为 0、不可为负 | `argumentValidationRunsBeforeAnyServerProcess` |
| symbol limit 不可为负或超过 500，target 不可空白 | 同上；`CodingCapabilitiesEdgeTest.lspCapabilitiesRequireValidAbsoluteWorkdirAndFile` 固定上界 |
| 相对 class target 只按显式 workdir 解析，绝不回退 Daemon cwd | `workspaceSymbolsAndDecompileShareOneClient`、`LspClientProtocolTest.relativeClassTargetWithoutWorkdirIsRejected` |

schema 的 required 不恒要求 workdir，但能力层仍校验相对路径所需的解析基准。
`path` 是文件系统路径；不把 goto-definition 的 `file://` 输入当作普通路径归一化。
反编译可接受提取出的 `jdt://`、`file://` 或本地 class 目标，相关转换和失败由
`localClassTargetVariantsAndEmptySource`、`javaDecompileLocalClassUsesExecuteCommand` 验证。

`LspClientTest.initializeDeclaresProtocolFactsAndRoot` 固定初始化、位置编码、workspaceFolders 和 jdtls 扩展能力；
`definitionSyncsDocumentAndFormatsLocations` 验证默认位置与请求形态。
UTF-8 的非 BMP 偏移由 `utf8PositionEncodingConvertsCodePointOffsets` 断言，
CRLF、末行和 UTF-32 由 `LspClientProtocolTest.positionBoundariesCoverCrlfLastLineAndUtf32` 断言。
**当前没有非 BMP 字符的码点→UTF-16 单元差异断言**，不能把 ASCII 或 UTF-32 用例算成该分支覆盖。

## 文档同步、能力与结果

`LspClientTest` 和
[`LspClientProtocolTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/LspClientProtocolTest.java)
承担 wire 证据：

- `languageIdFollowsExtensionMapping`、`bomMarkedSourceIsDecodedBeforeOpenAndChange`：
  languageId 按扩展名映射，BOM 文本先解码；二进制由 `binaryDocumentIsRejected` 在 didOpen 前拒绝。
- `didChangeAndDidSaveAfterExternalWrite`：已打开文档变化时全量 didChange + didSave，不重复 didOpen。
  文件消失拒绝同步，不发 didClose；文档状态在客户端关闭时整体清空。
- `unsupportedDefinitionIsReportedWithoutRequest`、`unsupportedWorkspaceSymbolsAreReportedWithoutRequest`、
  `serverWithoutCapabilitiesAdvertisesNothing`：未声明能力不发请求并给可读错误。
  实现接受 boolean true 与 options object，但假服务器只提供布尔形态，options-object 分支尚无测试证据。
- `formatsLocationLinksAndEmptyResults`、`formatsWorkspaceSymbolShapes`、
  `oddSymbolsAndLocationsAreFormattedDeterministically`：Location/LocationLink、两种 symbol 形态、空结果、
  种类与 limit，`jdt://` 结果不改写。
- `javaDecompileUsesJdtClassFileContentsForJdtUris`、`javaDecompileRejectsJdtUriOnNonJdtlsServer`、
  `jdtlsCommandDetectionCoversWrappersAndSeparators`：仅 jdtls 路线处理对应请求，不用 javap 伪造源码。
- `answersServerRequestsAndUnknownMethods`、`answersClientFacingServerMessages`：
  应答服务器 workspace 请求和未知方法；`toleratesStrayOutputBeforeTheFirstMessage` 验证协议前杂项输出。
  独立的服务器请求 id 夹具不证明“恰好复用客户端 id”的碰撞分支已覆盖。

read header 的可用态由 `LspCapabilitiesTest.readReportsConfiguredLspStatus` 固定；
服务器支持状态的发现不启动进程，诊断通知不被当作模型可见文档状态。

## 复用、取消与清理

`LspServiceTest`、`LspServiceLifecycleTest` 与 `LspClientPoolConcurrencyTest` 验证同项目复用、
并发启动去重、失败共享、空闲回收、启动中关闭与崩溃后重建。
池的实现键包含 root、server id 与配置指纹；`differentWorkspaceRootsUseDifferentClients`
**只固定不同 root 的隔离**，当前没有“同 root/id、不同配置”的直接断言。

调用方超时/取消只结束请求，发送 `$/cancelRequest`，不关闭共享客户端：
`slowRequestIsCancelledAtTheCallerDeadline`、`timeoutFailsAtTheCallerDeadlineWithoutClosingTheSharedClient`、
`cancellationKeepsTheSharedClientAlive` 是直接证据。有效请求预算来自 capability deadline，
不额外增加隐藏客户端默认超时。

初始化失败不重试；并发等待者共享同一失败，进程范围随后收敛。
`initializeTimeoutTerminatesTheProcess`、`crashedServerReportsExitCodeAndBoundedDiagnostics`、
`crashDuringRequestFailsClearlyAndMarksTheClientDead` 固定失败与有界诊断。
正常关闭走 shutdown/exit，顽固服务器及后代由 `stubbornServerIsForceKilledWithItsDescendants`、
`launchFailureReapsTheServersStubbornChildren` 验证强制清理。
进程范围与平台限制见[Bash 验证](builtin-bash-tests.md#进程范围与清理)。

协议测试不覆盖所有偶发 I/O 失败，也不代替真实语言服务器的集成验收；
跨平台收敛须查看 [process-scope.yml](../../.github/workflows/process-scope.yml) 的原生矩阵结果。
