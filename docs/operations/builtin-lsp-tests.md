# 内置 LSP 的行为验证

LSP 链路从共享 `DaemonConfiguration` 的内联 `lsp.servers` 进入服务器与项目根发现，经真实 stdio
JSON-RPC，最终服务 `lsp.goto-definition`、`lsp.workspace-symbols` 与 `lsp.java-decompile`。
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
验证扩展名大小写、结构化配置解析、命令的绝对/家目录路径与安装状态：

- `prefersShallowestRootMarkerInsideGitBoundary` 验证同一 Git 边界内最浅的 root marker
  优先于更深的 root marker。
- `stopsAtWorktreeGitFileBoundary` 与 `fallsBackToFirstMatchMarkerThenFileParent` 验证 `.git` 文件/目录
  是扫描上界、缺 root marker 时的回退；非 Git 项目由 `nonGitProjectUsesFileParentDirectory` 验证。
- `supportRequiresInstalledExecutable`、`resolvesAbsoluteAndHomeRelativeCommands`、
  `expandsHomeShortcuts`、`windowsExecutableSuffixesFollowPathext` 验证可执行命令解析；
  Windows 运行期分支仍需 Windows 原生测试。
- `readsServersFromJsonConfiguration` 与 `rejectsDuplicateRuntimeServerIds` 验证共享 JSON 配置到
  运行时服务器的映射和重复 id 拒绝；未知字段与非法取值由共享 codec 测试覆盖。

Daemon 不自动选择 JDK、安装服务器或注入 jdtls `-data`；服务器命令与标记由 operator 显式配置。
`LspDaemonWiringTest.inlineConfiguredExtensionsDriveTheRegisteredCapabilities` 验证内联配置到注册能力的装配，
并断言服务器 cwd 为自动发现的项目根，不是随意的调用目录。

## 参数与请求位置

[`LspCapabilitiesTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/LspCapabilitiesTest.java)
固定能力入口：

| 输入边界 | 证据 |
| --- | --- |
| 本地 `path` 必须绝对，相对 path 直接拒绝、不接受 workdir | `absolutePathsNeedNoWorkdir`、`relativePathsRequireAnExplicitWorkdir`，拒绝时不启动服务器 |
| line 必填且正整数，character 缺省为 0、不可为负 | `argumentValidationRunsBeforeAnyServerProcess` |
| symbol limit 不可为负或超过 500，target 不可空白 | `argumentValidationRunsBeforeAnyServerProcess`；`CodingCapabilitiesEdgeTest.lspCapabilitiesRequireValidAbsolutePathAndFile` 固定上界 |
| 相对 class target 直接拒绝，绝不回退 Daemon cwd | `workspaceSymbolsAndDecompileShareOneClient`、`LspClientProtocolTest.relativeClassTargetIsRejected` |

`lsp.*` schema 不含 `workdir`；`path` 与相对 class target 由能力层校验为绝对路径。
`path` 是文件系统路径；不把 goto-definition 的 `file://` 输入当作普通路径归一化。
反编译可接受提取出的 `jdt://`、`file://` 或本地 class 目标，相关转换和失败由
`localClassTargetVariantsAndEmptySource`、`javaDecompileLocalClassUsesExecuteCommand` 验证。

`LspClientTest.initializeDeclaresProtocolFactsAndRoot` 固定初始化、位置编码、workspaceFolders 和 jdtls 扩展能力；
`definitionSyncsDocumentAndFormatsLocations` 验证默认位置与请求形态。
UTF-8 的非 BMP 偏移由 `utf8PositionEncodingConvertsCodePointOffsets` 断言，
CRLF、末行和 UTF-32 由 `LspClientProtocolTest.positionBoundariesCoverCrlfLastLineAndUtf32` 断言。
涉及非 BMP 字符时，分别检查协商位置编码下的请求坐标；ASCII 用例不提供 UTF-16 单元差异的证据。

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
  能力声明可使用 boolean true 或 options object；验证 options object 时需让夹具真实返回该形态。
- `formatsLocationLinksAndEmptyResults`、`formatsWorkspaceSymbolShapes`、
  `oddSymbolsAndLocationsAreFormattedDeterministically`：Location/LocationLink、两种 symbol 形态、空结果、
  种类与 limit，`jdt://` 结果不改写。
- `javaDecompileUsesJdtClassFileContentsForJdtUris`、`javaDecompileRejectsJdtUriOnNonJdtlsServer`、
  `jdtlsCommandDetectionCoversWrappersAndSeparators`：仅 jdtls 路线处理对应请求，不用 javap 伪造源码。
- `answersServerRequestsAndUnknownMethods`、`answersClientFacingServerMessages`：
  应答服务器 workspace 请求和未知方法；`toleratesStrayOutputBeforeTheFirstMessage` 验证协议前杂项输出。
  双向请求 id 的冲突应使用同值 id 的夹具单独验证。

read header 的可用态由 `LspCapabilitiesTest.readReportsConfiguredLspStatus` 固定；
服务器支持状态的发现不启动进程，诊断通知不被当作模型可见文档状态。

## 复用、取消与清理

`LspServiceTest`、`LspServiceLifecycleTest` 与 `LspClientPoolConcurrencyTest` 验证同项目复用、
并发启动去重、失败共享、空闲回收、启动中关闭与崩溃后重建。
池的实现键包含 root、server id 与配置指纹；`differentWorkspaceRootsUseDifferentClients`
验证不同 root 的隔离。配置指纹变化的复用行为应使用同 root/id 的夹具观察。

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
