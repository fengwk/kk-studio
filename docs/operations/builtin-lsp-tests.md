# 内置 LSP 能力的测试映射

Daemon 的内置 LSP 能力是一条跨进程、跨协议的链路：CLI 的 `--lsp-config` → 项目根与语言服务器发现 → stdio JSON-RPC 客户端 → `lsp.goto-definition`、`lsp.workspace-symbols`、`lsp.java-decompile` 三个 capability。这份文档回答两个问题：

- 这条链路的哪些行为已被自动化测试固定下来；
- 上游 [pi-base](https://github.com/fengwk/pi-base) 的 LSP 测试逐项落在本仓的哪个用例，哪些行为被有意改成不同语义。

行为本身的定义见 [Harness Daemon](../modules/harness-daemon.md)；安装与 CLI 取值见 [Environment Daemon 安装与运行](environment-daemon.md)。

## 运行入口与报告位置

全部 LSP 用例都是快速测试：使用自带的假 stdio 语言服务器（真实子进程、真实分帧、真实 JSON-RPC），不依赖 Docker、真实 jdtls 或网络。

```bash
env JAVA_HOME=$JAVA_HOME_21 mvn -pl harness/daemon -am test
env JAVA_HOME=$JAVA_HOME_21 mvn -pl harness/daemon test -Dtest='Lsp*Test'
```

用例清单、通过与失败以 [`harness/daemon`](../../harness/daemon) 的 `target/surefire-reports` 为准，覆盖率报告在
`target/site/jacoco`。`harness/daemon` 不绑定 JaCoCo `check`，覆盖率只是参考指标。下表只维护「测试类 → 覆盖的链路环节」，
不记录用例数与覆盖率数字。

| 测试类 | 覆盖的链路环节 |
| --- | --- |
| `LspDiscoveryTest` | 服务器选择、`--lsp-config` JSON 解析与校验、项目根发现、命令解析、支持状态 |
| `LspClientTest` | 初始化、文档同步、位置编码、三种请求、服务端请求、崩溃、超时、关闭与进程树终止 |
| `LspClientProtocolTest` | 结果形态（Location、LocationLink、符号）、错误应答、启动失败、诊断尾部、能力判定、位置边界 |
| `LspServiceTest` | 客户端复用、并发去重、空闲回收、关闭、`fileChanged`、多项目隔离 |
| `LspServiceLifecycleTest` | 启动中关闭、崩溃后重建、非法生命周期取值、服务器缺失、失败共享 |
| `LspCapabilitiesTest` | 三个 capability 的参数校验、绝对路径结果、共享客户端、调用方超时与取消、read header 状态 |
| `LspDaemonWiringTest` | Daemon CLI 与注册表装配：CLI JSON 配置驱动已注册能力 |

已知未被固定的是不可确定复现的竞态：`exit` 通知自身写失败、`ExecutionException` 无 cause、进程管道排空的 IO 异常。
它们不构成门禁差异，因为 `harness/daemon` 没有覆盖率门禁。

## 上游 pi-base 逐项映射

### `tests/lsp-discovery.test.ts`

| pi-base 用例 | 本仓覆盖 | 说明 |
| --- | --- | --- |
| prefers the topmost build root over a closer first-match marker | `LspDiscoveryTest.prefersShallowestRootMarkerInsideGitBoundary` | 同一优先级：最浅的 `rootMarkers` 优先于更近的 `firstMatchMarkers` |
| does not let markers outside a git worktree hijack its project root | `LspDiscoveryTest.stopsAtWorktreeGitFileBoundary`、`fallsBackToFirstMatchMarkerThenFileParent`、`nonGitProjectUsesFileParentDirectory` | `.git`（目录或文件）是上界：扫描含 `gitRoot` 本身，但不越过它的父目录 |
| selects the highest configured existing JDK and ignores missing paths | `LspDiscoveryTest.resolvesAbsoluteAndHomeRelativeCommands` | 无对应能力：Daemon 不推断 JDK，服务器命令与 `JAVA_HOME` 展开由配置显式给出 |
| requires explicit Windows executable suffixes | `LspDiscoveryTest.windowsExecutableSuffixesFollowPathext` | 只固定后缀表与分隔符解析；运行期 `PATHEXT` 分支需要 Windows 宿主 |

### `tests/lsp-client.test.ts`

| pi-base 用例 | 本仓覆盖 | 说明 |
| --- | --- | --- |
| builds the stable default / process-scoped jdtls workspace data directory；can disable automatic jdtls workspace data injection | — | 不适用：Daemon 不注入 `-data`，工作区根由配置标记自动发现 |
| encodes utf16 character offsets from code point offsets | `LspClientTest.definitionSyncsDocumentAndFormatsLocations`、`LspClientProtocolTest.positionBoundariesCoverCrlfLastLineAndUtf32` | 非 BMP 字符按码点/UTF-16 单元差异断言 |
| encodes utf8 character offsets from code point offsets | `LspClientTest.utf8PositionEncodingConvertsCodePointOffsets` | 服务器声明 `utf-8` 位置编码时按字节偏移换算 |
| recovers from a malformed protocol header before the next valid message | `LspClientTest.toleratesStrayOutputBeforeTheFirstMessage` | 协议消息之前的非 LSP 输出被丢弃而不是终止会话 |
| uses the correct didOpen languageId for file.mts/cts/mjs/cjs/pyi | `LspClientTest.languageIdFollowsExtensionMapping` | 同一组扩展名，并额外固定未映射扩展名回退服务器 id |
| reports workspace/symbol supported when capability is boolean true / options object / missing or false | `LspClientProtocolTest.formatsWorkspaceSymbolShapes`、`unsupportedWorkspaceSymbolsAreReportedWithoutRequest` | 服务器能力为 `true`、对象或缺省时分别判定 |
| reports definition supported when capability is boolean true or options object | `LspClientTest.unsupportedDefinitionIsReportedWithoutRequest`、`LspCapabilitiesTest.gotoDefinitionReturnsAbsoluteLocationsFromDiscoveredRoot` | 缺省时不发请求并给出可读错误 |
| reports java/classFileContents only for jdtls；treats jdtls wrapper executables as jdtls | `LspClientTest.javaDecompileRejectsJdtUriOnNonJdtlsServer`、`LspClientProtocolTest.jdtlsCommandDetectionCoversWrappersAndSeparators` | 按可执行文件基名识别包装脚本与 Windows 后缀 |
| returns true for unknown methods (no pre-check) | `LspClientProtocolTest.answersClientFacingServerMessages` | 语义变更：只对真正调用的三类方法做能力判定，不再为未知方法保留「恒真」捷径 |
| defaults to 60000ms when not configured；honors a configured timeout | `LspCapabilitiesTest.timeoutFailsAtTheCallerDeadlineWithoutClosingTheSharedClient` | 语义变更：客户端没有隐藏默认值，有效超时只来自 capability 调用的 deadline |
| rejects with a helpful hint when the request times out | `LspClientTest.slowRequestIsCancelledAtTheCallerDeadline` | 超时后发送 `$/cancelRequest`，错误文本包含 `timed out` |
| clears the request timer once a response arrives | `LspServiceTest.closeStopsEveryClientAndIsIdempotent` | 计时器由 LSP4J 持有；等价事实是无悬挂状态，由关闭与回收断言承担 |
| does not consume a pending client request when a server request reuses its id | `LspClientTest.answersServerRequestsAndUnknownMethods` | 服务端请求从 900 起的独立 id 空间，客户端应答按 1 起的 id 匹配 |
| answers workspace/workspaceFolders server requests | `LspClientTest.answersServerRequestsAndUnknownMethods` | `workspace/workspaceFolders` 返回当前项目根 |
| rejects send promptly when the caller aborts | `LspCapabilitiesTest.cancellationKeepsTheSharedClientAlive`、`LspClientTest.slowRequestIsCancelledAtTheCallerDeadline` | capability 取消产生 `Operation cancelled` 终态且不关闭共享客户端 |
| sends didChange/didSave for changed open files and closes missing files | `LspClientTest.didChangeAndDidSaveAfterExternalWrite`、`LspClientProtocolTest.unexecutableCommandAndMissingFileFailClearly` | 差异：内容变化发全量 `didChange` + `didSave`；文件消失不发送 `didClose`，而是拒绝同步 |
| decodes BOM-marked source before didOpen and didChange | `LspClientTest.bomMarkedSourceIsDecodedBeforeOpenAndChange` | didOpen/didChange 发送解码后的正文 |
| rejects binary files without opening an LSP document | `LspClientTest.binaryDocumentIsRejected` | 二进制文件在打开文档之前被拒绝 |
| closes an open file and clears all per-file client state | `LspClientTest.gracefulShutdownUsesShutdownAndExit` | 差异：文档状态只在客户端关闭时整体清空 |
| maps high-level helper methods to the expected JSON-RPC requests | `LspClientTest.definitionSyncsDocumentAndFormatsLocations`、`workspaceSymbolsFormatsKindsAndHonoursLimit`、`javaDecompileUsesJdtClassFileContentsForJdtUris`、`javaDecompileLocalClassUsesExecuteCommand` | 三个方法族各自的请求与参数 |
| sends standard capabilities plus jdtls extended capabilities and records server capabilities | `LspClientTest.initializeDeclaresProtocolFactsAndRoot`、`javaDecompileUsesJdtClassFileContentsForJdtUris` | 声明位置编码、同步、`workspaceFolders` 与 jdtls `classFileContentsSupport` |
| retries initialize failures and reports the final error | `LspClientProtocolTest.initializeTimeoutTerminatesTheProcess`、`LspServiceLifecycleTest.concurrentWaitersShareTheSameFailedBoot` | 语义变更：初始化失败不重试，终止进程树并把同一个失败交给所有等待者 |
| deduplicates concurrent client boot for the same workspace and server | `LspServiceTest.deduplicatesConcurrentBoots` | 同一 `root + serverId + 配置指纹` 只启动一个进程 |
| does not reuse a client when the same root and server id resolve to different configurations | `LspServiceTest.differentWorkspaceRootsUseDifferentClients`、`LspClientTest.initializeDeclaresProtocolFactsAndRoot` | 复用键含配置指纹，不同项目根不共享进程 |
| rejects requests instead of crashing when the server closes stdin | `LspClientProtocolTest.crashedServerReportsExitCodeAndBoundedDiagnostics`、`LspClientTest.crashDuringRequestFailsClearlyAndMarksTheClientDead` | 崩溃带退出码与有界诊断尾部 |
| force-stops descendants of an unresponsive LSP wrapper | `LspClientTest.stubbornServerIsForceKilledWithItsDescendants` | 忽略 `SIGTERM` 的服务器及其后代都被强制终止 |

### `tests/lsp-start.test.ts`

| pi-base 用例 | 本仓覆盖 | 说明 |
| --- | --- | --- |
| injects process-scoped jdtls workspace data when no explicit -data is present；does not override explicit -data；can disable automatic injection | — | 不适用：Daemon 不改写服务器命令，工作区隔离交给项目根发现与 operator 配置 |
| force-terminates a server that ignores SIGTERM | `LspClientTest.stubbornServerIsForceKilledWithItsDescendants` | 关闭超时后强制终止 |
| throws a clear startup error for a missing LSP executable | `LspClientProtocolTest.unexecutableCommandAndMissingFileFailClearly`、`LspServiceLifecycleTest.notInstalledServerFailsWithoutStartingAProcess`、`LspDiscoveryTest.supportRequiresInstalledExecutable` | 未安装、不可执行、启动即退出三种失败都给出命令与目录 |

### `tests/lsp-tools.test.ts`

| pi-base 用例 | 本仓覆盖 | 说明 |
| --- | --- | --- |
| handles resolved, rejected, and pre-aborted LSP work | `LspCapabilitiesTest.cancellationKeepsTheSharedClientAlive`、`LspClientProtocolTest.serverErrorFailsTheRequest` | 成功、错误、取消三条终态 |
| supports goto_definition with explicit line | `LspCapabilitiesTest.gotoDefinitionReturnsAbsoluteLocationsFromDiscoveredRoot` | 1-based 行直接进入请求 |
| rejects non-positive goto_definition lines；defaults character to zero；requires line | `LspCapabilitiesTest.argumentValidationRunsBeforeAnyServerProcess` | 缺 `line` 在 inputSchema 层拒绝，`line = 0` 与负 `character` 在 capability 内给出可读错误，省略 `character` 用第 0 列 |
| returns a friendly error when the server does not advertise go-to-definition | `LspClientTest.unsupportedDefinitionIsReportedWithoutRequest` | 不发请求并说明未声明该能力 |
| returns no definition results when nothing is found | `LspClientProtocolTest.formatsLocationLinksAndEmptyResults` | 空结果给出明确文案 |
| normalizes file:// paths before requesting goto_definition | — | 差异：`path` 是文件系统路径，URI 由绝对路径推导，不做 `file://` 归一化 |
| returns raw jdt URIs from goto_definition results | `LspClientProtocolTest.oddSymbolsAndLocationsAreFormattedDeterministically` | `jdt://` URI 原样返回 |
| formats workspace symbols | `LspClientTest.workspaceSymbolsFormatsKindsAndHonoursLimit`、`LspClientProtocolTest.formatsWorkspaceSymbolShapes` | 符号种类、`SymbolInformation` 与 `WorkspaceSymbol` 两种形态、limit |
| rejects negative workspace symbol limits | `LspCapabilitiesTest.argumentValidationRunsBeforeAnyServerProcess`、`CodingCapabilitiesEdgeTest.lspCapabilitiesRequireValidAbsoluteWorkdirAndFile` | 负值与超出上界都失败 |
| surfaces workspace symbol errors；surfaces lsp_java_decompile client errors | `LspClientProtocolTest.serverErrorFailsTheRequest` | 服务器错误应答转成带上下文的失败 |
| returns no symbols when workspaceSymbols is empty | `LspClientProtocolTest.formatsWorkspaceSymbolShapes` | 缺省与空数组都给出明确文案 |
| returns a friendly error when the server does not advertise workspace/symbol | `LspClientProtocolTest.unsupportedWorkspaceSymbolsAreReportedWithoutRequest` | 同 definition |
| extracts jdt URI for java decompile | `LspClientTest.javaDecompileUsesJdtClassFileContentsForJdtUris` | 含 `jdt://` URI、符号行与带前缀目标的提取 |
| reports decompile failure when no source is returned；passes file:// targets to decompileClass | `LspClientProtocolTest.localClassTargetVariantsAndEmptySource` | 空源码、`file://` 目标与非法目标各自的终态 |
| converts local .class paths to file:// before decompiling | `LspClientTest.javaDecompileLocalClassUsesExecuteCommand` | 本地 class 走 `workspace/executeCommand` |
| resolves relative .class targets against the tool cwd | `LspCapabilitiesTest.workspaceSymbolsAndDecompileShareOneClient` | 相对目标按调用 `workdir` 解析，且与符号查询共享同一客户端 |
| returns a friendly error when decompile is requested on a non-jdtls server | `LspClientTest.javaDecompileRejectsJdtUriOnNonJdtlsServer` | 非 jdtls 服务器上的 `jdt://` 目标明确失败 |

### `tests/lsp-tools-render-behavior.test.ts`

| pi-base 用例 | 本仓覆盖 | 说明 |
| --- | --- | --- |
| renders call previews without default workdir noise | — | 不适用：工具调用渲染归 Studio 前端与工具契约，不在 Daemon 模块 |
| supports workspace symbol errors and java decompile fallbacks | `LspClientProtocolTest.serverErrorFailsTheRequest` | 只迁移失败语义：服务器错误应答与「未声明能力」都转成带上下文的错误，调用行渲染不迁移 |
| normalizes file:// java decompile targets before asking the client | `LspClientProtocolTest.localClassTargetVariantsAndEmptySource`、`LspClientTest.javaDecompileLocalClassUsesExecuteCommand` | 语义差异：不做 `file://` 归一化，`file://` 目标按原样交给客户端，本地 `.class` 目标走 `workspace/executeCommand` |

### `tests/index-lifecycle.test.ts`

| pi-base 用例 | 本仓覆盖 | 说明 |
| --- | --- | --- |
| shuts down root LSP clients on session shutdown and reload | `LspServiceTest.closeStopsEveryClientAndIsIdempotent`、`LspServiceLifecycleTest.closeDuringBootCancelsTheStartupAndTerminatesTheProcess`、`LspDaemonWiringTest.cliConfiguredExtensionsDriveTheRegisteredCapabilities` | 会话关闭与重载在本仓对应 Daemon 服务关闭：关闭覆盖全部客户端（含启动中的客户端），重复关闭幂等；「重载」对应 Daemon 重启——进程退出即回收全部语言服务器 |
| keeps root-owned LSP clients alive when a subagent session shuts down | `LspCapabilitiesTest.cancellationKeepsTheSharedClientAlive`、`LspServiceTest.reusesTheClientAcrossCalls`、`reclaimsIdleClients`、`fileChangedRefreshesOnlyOpenedDocuments` | 等价的共享生命周期契约：单次调用取消后 `activeClientCount` 保持 1，多次调用复用同一进程，只有零在途请求且空闲超时才回收，主 Agent 的文档同步（`fileChanged`）不重启客户端 |
| relays child diagnostics through the root UI until that root shuts down | `LspClientProtocolTest.crashedServerReportsExitCodeAndBoundedDiagnostics`、`LspClientTest.toleratesStrayOutputBeforeTheFirstMessage` | 语义差异：Daemon 没有 UI 中继，也没有子会话宿主；服务器推送的通知与诊断不进入模型可见状态，stderr 只保留有界诊断用于崩溃报告 |
| renders the custom find call format and rejects missing paths | `FindGrepCapabilitiesTest` | 检索工具的调用格式与路径校验，与 LSP 链路无关 |
| uses the cached resolver factory for repeated reads in the same project | `LspServiceTest.reusesTheClientAcrossCalls`、`deduplicatesConcurrentBoots`、`differentWorkspaceRootsUseDifferentClients` | 同一项目内重复调用复用同一客户端，并发启动去重，不同项目根不共享进程 |
| settles goal state before deciding whether to send completion notifications；wires project compaction settings into the session compaction hook | — | 不适用：会话、goal 与压缩不在 Daemon 模块 |

## workdir 契约

三个 LSP 能力都以“文件在目标项目里的绝对路径”为主输入，`workdir` 只是解析相对路径的基准：

| 契约 | 覆盖 |
| --- | --- |
| 绝对 `path` 不需要 `workdir`，三个能力都能在没有调用方目录的情况下完成真实查询与执行 | `LspCapabilitiesTest.absolutePathsNeedNoWorkdir` |
| 相对 `path` 必须由本次调用显式给出绝对 `workdir`；缺失时拒绝且不启动服务器 | `LspCapabilitiesTest.relativePathsRequireAnExplicitWorkdir`、`LspCapabilitiesTest.gotoDefinitionReturnsAbsoluteLocationsFromDiscoveredRoot` |
| `lsp.java-decompile` 的 `target` 推荐 `jdt://` URI 或绝对 class 路径；相对 class 路径只在显式 `workdir` 下解析，缺失即拒绝，且客户端绝不回退到守护进程 cwd | `LspCapabilitiesTest.absolutePathsNeedNoWorkdir`、`LspClientProtocolTest.relativeClassTargetWithoutWorkdirIsRejected`、`LspClientTest.javaDecompileLocalClassUsesExecuteCommand` |
| 服务器进程目录始终是自动发现的项目根，与调用是否给出 `workdir` 无关 | `LspCapabilitiesTest.gotoDefinitionReturnsAbsoluteLocationsFromDiscoveredRoot`、`LspDaemonWiringTest.cliConfiguredExtensionsDriveTheRegisteredCapabilities` |

三个能力与 `fs.read`、`fs.write`、`fs.edit`、`fs.grep`、`fs.find` 的 arguments schema 都把 `workdir` 声明为
“仅在 `path` 为相对路径时必填”，只有 `process.exec` 在 `required` 中恒要求 `workdir`。是否必填完全由
schema 的 `required` 决定，没有额外的服务端校验：带绝对 `path` 的调用可以省略 `workdir` 直达能力层。

## 有意保留的行为差异

这些差异是当前契约的一部分，测试按现状固定：

- **服务器直连、无 `javap` 回退**：语言服务器必须预先安装在目标主机并由 `--lsp-config` 声明；Daemon 直接以 stdio 连接已安装的服务器，不插入中间进程，也不合成反编译源码。
- **服务器命令不被改写**：不注入 `-data`，不自动发现 JDK，不自动安装服务器；命令与项目根标记完全由配置决定。
- **失败即关闭**：初始化失败不重试，终止整棵进程树后把同一个失败交给所有等待者；崩溃的客户端在下一次调用时重建。
- **取消与关闭分离**：取消只结束当前请求（发送 `$/cancelRequest`），客户端保持可复用；进程树终止只发生在关闭与回收路径。
- **文档状态只在关闭时清空**：文件消失时拒绝同步，不发送 `didClose`。
- **能力判定只覆盖真实调用的三类方法**：没有「未知方法恒真」的捷径。
- **read 状态只展示可用态**：`LspSupport` 保留三态判定，展示层只在服务器可用时输出 `lsp:` 行。
- **workdir 可选**：绝对 `path`（以及 `jdt://` 或绝对 class `target`）不需要任何调用方目录；只有相对路径要求显式 `workdir`，且它始终是路径解析基准而不是项目根。
- **不重写路径**：`path`、结果位置与源码正文都不做 `file://` 归一化或相对化。
