# builtin-read 测试迁移映射

本文记录 pi-base 的 `read` 行为用例在 KK 中的逐项落点：源用例、适用性判断与 KK 对应用例。契约事实源是
[内置工具与异步委派](../modules/builtin-tools-design.md#文件读取)，本文不重复契约内容，只回答“某个源用例现在由谁承接、
哪些不再适用”。本文只维护映射关系，每一行的实际通过与覆盖率以本文「覆盖落点与运行方式」中的命令输出为准。

## 覆盖落点与运行方式

| 层级 | 测试 | 守护的行为 |
| --- | --- | --- |
| 共享窗口核心 | [`common.text.TextReadWindow`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/text/TextReadWindow.java) + [`TextReadWindowTest`](../../harness/common/src/test/java/fun/fengwk/kkstudio/harness/common/text/TextReadWindowTest.java) | 分页窗口、60000 码点正文预算、行边界与代理项、截断元数据、检查点中止、只剥首字符的 BOM 包装、越界位置拒绝 |
| 本地适配器 | [`LocalTextReadWindow`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/LocalTextReadWindow.java) + [`LocalTextReadWindowTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/LocalTextReadWindowTest.java) | 本地编码打开、中断到 `InterruptedException` 的映射、不重复剥离 BOM |
| 本地 `fs.read` 端到端 | [`ReadCapabilityTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/ReadCapabilityTest.java) | 参数校验顺序、header 字段、目录/图片/二进制/特殊文件、编码、图片与目录的独立语义 |
| 受管适配器 | [`platform.harness.read.ReadTextWindow`](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/read/ReadTextWindow.java) + [`ReadTextWindowTest`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/read/ReadTextWindowTest.java) | 严格 UTF-8、首个字符 BOM、参数校验、超时/中断/IO 失败映射，以及受管入口的端到端窗口结果 |
| 受管调用链 | `PlatformReadToolExecutorTest`、`PlatformResourceContentReaderTest`、`PlatformSkillContentReaderTest`、`S3StorageDeadlineIntegrationTest` | Resource/Skill 文本读取如何进入窗口投影并返回文本结果，读取截止如何翻译 |
| 真实 E2E | [`scripts/dev/verify/e2e/cases/real.mjs`](../../scripts/dev/verify/e2e/cases/real.mjs) 的 `tool.read_turn` | 非 YOLO tool turn 的审批链路，以及 durable `tool_result` 内联完整 `path`/`ends_with_newline`/`range` header 与编号正文（需 `--real --with-tools --with-canvas-storage`） |

```bash
env JAVA_HOME=$JAVA_HOME_21 mvn -o -pl harness/common verify
env JAVA_HOME=$JAVA_HOME_21 mvn -o -pl harness/daemon -am test \
  -Dtest='ReadCapabilityTest,LocalTextReadWindowTest' -Dsurefire.failIfNoSpecifiedTests=false
env JAVA_HOME=$JAVA_HOME_21 mvn -o -pl platform -am test \
  -Dtest='ReadTextWindowTest,PlatformReadToolExecutorTest,PlatformResourceContentReaderTest,PlatformSkillContentReaderTest,S3StorageDeadlineIntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
./scripts/dev/verify/e2e/run.sh --real --with-tools --with-canvas-storage --only tool.read_turn
```

运行结果不在这里固定：上表各层测试的通过与失败以对应模块 `target/surefire-reports` 为准，覆盖率以
`target/site/jacoco` 为准；覆盖率是否作为门禁取决于模块自身——[`harness/common`](../../harness/common) 与
[`platform`](../../platform) 绑定 JaCoCo `check`，`harness/daemon` 只产出报告。真实 E2E 的结论与产物在
`reports/e2e/latest/`。本文不记录某一次运行的用例数或覆盖率数字，这些数字会随实现漂移。

## `read.test.ts` 逐项映射

| pi-base 源用例 | 适用性 | KK 对应用例 |
| --- | --- | --- |
| reads text files with numbered lines and offset/limit | 适用，尾部文案按新契约改写 | `ReadCapabilityTest.numberedLinesRespectOffsetAndLimitAndReportLineLimitTruncation`、`ReadTextWindowTest.offsetAndLimitProduceWindowAndLineLimitTruncation` |
| does not emit TAG header | 适用 | `numberedLinesRespectOffsetAndLimitAndReportLineLimitTruncation`（header 与编号正文断言）、`ReadTextWindowTest.lspStatusIsLastHeaderLineAndOmittedWhenUnsupported`（header 前缀精确匹配） |
| reports factual metadata while keeping a normalized body view | 适用，改用“行边界不进入正文”的等价断言 | `ReadCapabilityTest.mixedLineEndingsAreExactAndPreserveContent`、`bomEncodedTextIsDecodedWithoutBomInBody`、`ReadTextWindowTest.crlfAndLoneCrAreLineBoundaries`、`ReadTextWindowTest.byteOrderMarkIsStrippedOnlyAtTheVeryStart` |
| detects utf-16le text files and preserves a normal text view | 适用 | `ReadCapabilityTest.bomEncodedTextIsDecodedWithoutBomInBody` |
| detects legacy windows-1252 text files | 不适用（有意偏离）：KK 只按 BOM 与严格 UTF-8 判定编码，非 UTF-8 字节按二进制拒绝，不做字符集嗅探 | `ReadCapabilityTest.binaryContentIsRejected` 的 legacy 分支 |
| right-aligns read line numbers to the file width | 适用 | `common.text.TextReadWindowTest.windowMetadataAndRightAlignedLineNumbers`（第 9 行起读 3 行得到 ` 9|line-9`）、`ReadCapabilityTest.largeTextIsStreamedWithAccurateTotals` |
| marks read results whose displayed lines were truncated | 不适用（有意偏离）：不再有逐行 2000 字符截断，也无需向上游标记“只显示了预览” | 反向断言：`ReadCapabilityTest.longLineIsReturnedWithoutPerLineTruncation`、`ReadTextWindowTest.longLineIsNotTruncated`（受管）、`common.text.TextReadWindowTest.characterBudgetCutsMidLineAndPointsAtNextColumn`（核心不截断整行） |
| waits for in-flight file mutations before reading file contents | 不适用：pi-base 的进程内 per-file 队列不存在；KK 侧同一进程的并发修改由原子替换与调用超时约束 | 失败模式证据：`common.text.TextReadWindowTest.checkpointRunsAroundEveryBlockAndAbortsTheScan`（检查点中止）、`LocalTextReadWindowTest.interruptedScanFailsInsteadOfReturningPartialWindow`（中断映射）、`ReadTextWindowTest.interruptedScanFailsInsteadOfReturningPartialWindow`（受管中断） |
| treats an empty file as having zero body lines | 适用（observer 部分除外） | `ReadCapabilityTest.emptyFileAndOffsetBeyondEofReportEmptyRange`、`ReadTextWindowTest.emptyInputReportsEmptyRange` |
| keeps a successful read result when its observer throws | 不适用：KK 没有 `onSuccessfulRead` 观测回调，不存在回调失败覆盖成功结果的路径 | — |
| uses the target file directory when building the LSP resolver for absolute paths outside cwd | 部分适用：解析基目录与服务器可用性判定归 LSP 生命周期，read 侧只守“仅可用时输出 `lsp` 行” | `common.text.TextReadWindowTest.lspHeaderIsLastAndOmittedWhenUnavailable`（核心：可用时输出且为最后一行）、`ReadCapabilityTest.lspHeaderReflectsAvailableServerAndIsLast`（当前不猜测可用性）、`ReadTextWindowTest.lspStatusIsLastHeaderLineAndOmittedWhenUnsupported` |
| reads directories | 适用：目录保持独立返回语义（`kind: directory` 与 48 KiB 展示上界不变），分页上限提升到 2000 | `ReadCapabilityTest.directoryPaginationDefaultsToTwoThousandAndRejectsColumnOffset`、`directoryListingKeepsByteCeilingAndContinuesFromReportedOffset` |
| treats only an empty argument object as a current-directory read | 不适用（有意偏离）：`path` 是必填参数，schema 与提示词都不把 `{}` 解释为当前目录 | `EnvironmentCapabilityCatalogTest`（schema 结构）与 `ReadCapabilityTest.relativePathRequiresExplicitWorkdir` |
| preserves non-ASCII spaces inside file names | 适用 | `ReadCapabilityTest.nonAsciiSpacesInFileNamesArePreserved` |
| truncates very long lines | 不适用（有意偏离）：见“marks read results whose displayed lines were truncated” | `ReadCapabilityTest.longLineIsReturnedWithoutPerLineTruncation` |
| delegates supported image `image.png` to the built-in read tool | 改写：KK 直接把图片字节作为二进制结果返回，由终态编码阶段直传对象存储，没有文本委托层 | `ReadCapabilityTest.imageResultsStayBinaryAndRejectColumnOffset` |
| delegates supported image `image.bmp` to the built-in read tool | 不适用：KK 支持的图片签名为 png/jpeg/gif/webp（既有行为，未作改动），bmp 不在其中 | 同上（签名集合由 `ReadCapabilityTest` 的图片用例与 `detectImageMediaType` 共同体现） |
| calls onSuccessfulRead only for text file reads | 不适用：无 observer 回调 | — |
| rejects binary non-image files | 适用，错误文案沿用 KK 既有措辞 | `ReadCapabilityTest.binaryContentIsRejected`、`ReadTextWindowTest.binaryAndMalformedUtf8AreRejected` |
| validates offset and limit before touching the requested filesystem path | 适用，覆盖 `offset`/`limit`/`column_offset` 三个参数与“文件不存在不参与判定” | `ReadCapabilityTest.windowArgumentsAreValidatedBeforeFileSystemAccess`、`offsetBeyondEofWinsOverColumnValidation`、`ReadTextWindowTest.invalidArgumentsAreRejected` |
| rejects a sparse text candidate above 64 MiB before entering the full-read queue | 不适用（有意偏离）：取消 64 MiB 拒绝上限，改为流式扫描 + 正文预算；“先用 grep”之类的引导也随之移除 | `ReadCapabilityTest.largeTextIsStreamedWithAccurateTotals`（40000 行）、`ReadTextWindowTest.largeStreamKeepsWindowWithAccurateLineCount`（9 MiB 流） |

## 关联文件中的 read 适用用例

| 源用例 | 适用性 | KK 对应用例 |
| --- | --- | --- |
| `special-file-tools.test.ts`：rejects non-regular nodes before read, grep, edit, or write performs file I/O | read 部分适用（grep/edit/write 归各自测试映射文档） | `ReadCapabilityTest.nonRegularFileNodesAreRejectedBeforeIo`（`/dev/null`，错误含 `not a regular file`，POSIX 限定） |
| `line-endings.test.ts`：混合行尾的分类与 LF 归一化 | read 侧只适用“三种行尾都作为行边界”；序列化与写回归 write/edit 切片 | `ReadCapabilityTest.mixedLineEndingsAreExactAndPreserveContent`、`ReadTextWindowTest.crlfAndLoneCrAreLineBoundaries` |
| `tool-output.test.ts`：上游截断元数据与全局 50 KiB / 2000 行输出边界 | 不适用：read 不再产生 `upstreamTextTruncated`；read 文本投影恒在终态加宽内联预算内，不触发资源化 | 反向证据：`ToolResultFinalizerTest`（read 身份 320 KiB / 2020 行内联预算）、E2E `tool.read_turn`（durable `tool_result` 内联完整投影） |

## 有意偏离清单

以下旧契约断言在原样照搬时会与当前契约冲突，因此一律改写为正向断言，而不是保留占位：

- 单行超过 2000 字符时截断为 `line truncated to 2000 chars`。
- 含 header 与尾注的完整输出受控于 48 KiB（文本上限），未截断时也输出 `[Showing lines a-b of c. Re-run read with offset=n to continue.]`。
- `column_offset` 只在 `limit=1` 时可用。
- 文本候选超过 64 MiB 先拒绝并引导改用 `grep`。
- `{}` 空参数视为读取当前目录。
- `read` 对外提供 `onSuccessfulRead` 观测回调与 `details.upstreamTextTruncated`。

## 已知限制

- **BOM 只剥一次且只在首位**：核心不自行剥离 BOM，本地侧依赖字节层剥离、受管侧由 `withoutLeadingBom` 严格只处理第 1 个字符，因此换行、代理项对或解码块边界之后的 `\uFEFF` 都是正文内容。
- **位置溢出显式拒绝**：行号与列号内部按 `long` 计数；报告位置超出参数协议的 `int` 范围时直接失败（`text read position exceeds the supported integer range`），不会截断成伪造的 `next`。
- **LSP 行接入**：`lsp:` 只在有对应可用服务器时输出；当前的本地适配器不自行猜测可用性，受管侧由调用方传入状态。

- **终态内联体积**：read 最多返回 2000 行、60000 码点正文（最坏约 240 KB 正文加行号与 header），恒在终态链路为 read 身份加宽的内联预算（320 KiB / 2020 行）内，因此文本结果保持内联、不产生 resource 预览；只有图片等二进制结果才外部化为 managed resource。
- **大文件读取成本**：为给出准确的总行数与文件级 `ends_with_newline`，一次读取必须扫描到 EOF；成本由调用超时/取消（daemon 调用预算、受管侧 30 秒扫描预算）与线程中断约束，而不是文件大小上限。
- **无字符集嗅探**：没有 BOM 的 8 位旧编码按二进制拒绝，不会给出“看起来正确”的乱码文本。
- **外置链路证据边界**：工具结果外部化到全局 Blob、session 引用转移与权威文件名由 platform 侧 [`GlobalStorageToolResultHistoryMaterializerIntegrationTest`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/tool/gateway/GlobalStorageToolResultHistoryMaterializerIntegrationTest.java) 与 [`ToolResultFinalizerTest`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/tool/gateway/ToolResultFinalizerTest.java) 守卫；E2E 侧 [`real.mjs`](../../scripts/dev/verify/e2e/cases/real.mjs) 的 `tool.read_turn` 只断言当前 inline 契约，没有真实模型 tool→blob 端到端证据。

上级：[系统设计](../system-design.md)。相关文档：[内置工具与异步委派](../modules/builtin-tools-design.md)、[Harness Daemon](../modules/harness-daemon.md)、[开发与测试](development-and-testing.md)。
