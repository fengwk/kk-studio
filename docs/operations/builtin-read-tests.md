# 内置 Read 的行为验证

验证 `read` 时先区分文本窗口、本地文件类型分派和受管 Resource/Skill 调用链。
文本窗口由共享核心生成，本地与受管适配器各自负责打开输入、编码与截止时间；
最后还要验证完整结果能进入 durable history，而不是只确认 Daemon 返回了一段文本。
契约见[内置工具设计：文件读取](../modules/builtin-tools-design.md#文件读取)。

## 运行入口与副作用

从仓库根目录使用 JDK 21。共享核心与本地测试使用临时文件；Platform 集成测试可能启动
Testcontainers 的 PostgreSQL，需要 Docker，不能换成生产或共享数据面。
这里的 Blob 物化测试使用内存 S3 替身，截止测试使用真实 SDK 连接本地 HTTP 服务，不启动真实 MinIO。

```bash
env JAVA_HOME="$JAVA_HOME_21" mvn -pl harness/common -am verify
env JAVA_HOME="$JAVA_HOME_21" mvn -pl harness/daemon -am test \
  -Dtest='ReadCapabilityTest,LocalTextReadWindowTest,ReadWriteEditCapabilitiesTest,EnvironmentCapabilityCatalogTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
env JAVA_HOME="$JAVA_HOME_21" mvn -pl platform -am test \
  -Dtest='ReadTextWindowTest,PlatformReadToolExecutorTest,PlatformResourceContentReaderTest,PlatformSkillContentReaderTest,S3StorageDeadlineIntegrationTest,ToolResultFinalizerTest,GlobalStorageToolResultHistoryMaterializerIntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

目标模块的 `target/surefire-reports` 必须证明用例实际运行；覆盖率报告在 `target/site/jacoco`。
`harness/common` 与 `platform` 的 JaCoCo `check` 在 `verify` 阶段执行，`test` 只生成报告；
Daemon 没有绑定该门禁。筛选测试得到的报告不能代表整个模块的覆盖率。

真实审批与模型续接需要单独授权付费，并准备独立 Backend、数据库、S3 与 Daemon：

```bash
./scripts/dev/verify/e2e/run.sh --real --with-tools --with-canvas-storage --only tool.read_turn
```

这是 L4 用例，会同步真实 Provider 凭据并调用模型，不是普通单元测试。费用、端口复用和数据隔离要求见
[开发与测试](development-and-testing.md#e2e)；只有 `--list` / `--docs` 是无服务副作用的 inventory 查询。

## 文本窗口是否准确

[`TextReadWindowTest`](../../harness/common/src/test/java/fun/fengwk/kkstudio/harness/common/text/TextReadWindowTest.java)
验证共享核心；
[`LocalTextReadWindowTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/LocalTextReadWindowTest.java)
与 Platform 的
[`ReadTextWindowTest`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/read/ReadTextWindowTest.java)
验证两个适配器。同名近似的类不要混为一层。

| 要验证的行为 | 主要证据 |
| --- | --- |
| offset/limit、准确总行数、右对齐行号、文件级 `ends_with_newline` | 核心 `windowMetadataAndRightAlignedLineNumbers`；本地 `ReadCapabilityTest.largeTextIsStreamedWithAccurateTotals`；受管 `offsetAndLimitProduceWindowAndLineLimitTruncation`、`largeStreamKeepsWindowWithAccurateLineCount` |
| CR/LF/CRLF 作为边界，正文不包含行尾 | 核心与受管 `crlfAndLoneCrAreLineBoundaries`；本地 `mixedLineEndingsAreExactAndPreserveContent` |
| 空文件与 EOF 后窗口 | 本地 `emptyFileAndOffsetBeyondEofReportEmptyRange`；受管 `emptyInputReportsEmptyRange` |
| 长行不按 2000 字符裁剪，达到正文预算时能续读下一列 | 核心 `characterBudgetCutsMidLineAndPointsAtNextColumn`；本地 `longLineIsReturnedWithoutPerLineTruncation`；受管 `longLineIsNotTruncated` |
| 参数错误先于文件访问，EOF 后偏移优先于列校验 | 本地 `windowArgumentsAreValidatedBeforeFileSystemAccess`、`offsetBeyondEofWinsOverColumnValidation`；受管 `invalidArgumentsAreRejected` |
| BOM 仅在输入最开始剥一次 | 核心 `withoutLeadingBomStripsOnlyTheVeryFirstCharacter`；本地 `localEncodingReaderIsUsedAndBomIsNotStrippedTwice`；受管 `byteOrderMarkIsStrippedOnlyAtTheVeryStart` |
| 取消、中断与 I/O 失败不返回半个成功窗口 | 核心 `checkpointRunsAroundEveryBlockAndAbortsTheScan`；本地与受管 `interruptedScanFailsInsteadOfReturningPartialWindow`；受管 `streamFailuresAreMappedToPlatformReadException` |
| 可用的 LSP 状态是最后一行 header，不可用时省略 | 核心 `lspHeaderIsLastAndOmittedWhenUnavailable`；本地 `lspHeaderReflectsAvailableServerAndIsLast`；受管 `lspStatusIsLastHeaderLineAndOmittedWhenUnsupported` |

文本最多展示 2000 行、60000 码点正文，截断位置包含下一行/列坐标；`column_offset` 不限于 `limit=1`。
内部按 long 计数，输出坐标超出 int 协议范围时显式失败，不截断成错误的续读位置。
为给出准确总行数和末尾换行事实，读取必须扫描到 EOF：不存在 64 MiB 文件拒绝上限，
成本由 Daemon 调用预算、Resource reader 的 30 秒预算和取消/中断控制。稀疏大文件也不是无成本读取。

## 本地路径、编码与文件类型

[`ReadCapabilityTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/ReadCapabilityTest.java)
直接调用本地 `fs.read`，固定以下分派边界：

- `path` 必填，由 `EnvironmentCapabilityCatalogTest` 冻结；空对象不是读取当前目录。
  绝对路径无需 workdir，相对路径必须提供本次调用的绝对 workdir（`relativePathRequiresExplicitWorkdir`），
  文件名中的非 ASCII 空格保持原样（`nonAsciiSpacesInFileNamesArePreserved`）。
- 本地文本只支持严格 UTF-8 与 BOM 标记的 UTF-16LE/BE（`bomEncodedTextIsDecodedWithoutBomInBody`）。
  受管文本按严格 UTF-8；NUL、非法字节与无 BOM 的旧编码不做字符集猜测（`binaryContentIsRejected`、
  受管 `binaryAndMalformedUtf8AreRejected`），有效的替换字符不能误判成二进制。
- 目录有独立 `kind: directory` 语义，默认 2000 项、48 KiB 展示预算；续读用报告中的 offset，
  合法 column offset 被忽略（`directoryPaginationDefaultsToTwoThousandAndIgnoresColumnOffset`、
  `directoryListingKeepsByteCeilingAndContinuesFromReportedOffset`）。48 KiB 不是文本窗口的上限。
- png/jpeg/gif/webp 按签名返回二进制结果，不通过文本窗口；bmp 不在支持集合。
  合法 column offset 同样被忽略（`imageResultsStayBinaryAndIgnoreColumnOffset`）。
- 特殊节点在读取前拒绝。`nonRegularFileNodesAreRejectedBeforeIo` 用 `/dev/null` 验证这条边界，
  该证据是 POSIX 限定，不能宣称所有宿主文件类型都已原生验收。
- 参数校验先于文件访问：非正 `column_offset` 在目录与图片上同样被拒绝，只有合法值才被忽略
  （`readColumnOffsetValidationAndNonTextTargets`）。

read 不等待进程内文件变更队列，也没有成功 observer 回调；原子替换避免半文件提交，但不提供跨调用
陈旧读取保护。LSP 可用性由服务层或调用方提供，read 适配器不自行启动或猜测服务器。

## 受管结果与 durable history

`PlatformReadToolExecutorTest`、`PlatformResourceContentReaderTest`、`PlatformSkillContentReaderTest`
验证 Resource/Skill 读取进入同一窗口投影；
[`S3StorageDeadlineIntegrationTest`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/storage/S3StorageDeadlineIntegrationTest.java)
验证响应头前与响应体内阻塞时存储截止时间生效及错误翻译。
`PlatformResourceContentReader` 的 30 秒预算与线程中断是两种约束，应分别观察超时与取消路径。
`ToolResultFinalizerTest` 验证可信 read 身份专属的 **320 KiB / 2020 物理行**内联预算：
2000 行与 60000 码点的最坏文本窗口连同行号、header 仍在预算内，因此文本不产生 resource 预览。
图片等二进制结果的外部化不是这条内联保证。

[`GlobalStorageToolResultHistoryMaterializerIntegrationTest`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/tool/gateway/GlobalStorageToolResultHistoryMaterializerIntegrationTest.java)
另行守卫工具结果外部化到全局 Blob、Session 引用转移与权威文件名。
E2E `tool.read_turn` 只证明非 YOLO 审批、ALLOW 后完整 canonical 文本内联进入 durable `tool_result`，
以及后续模型轮次成功；它不提供真实模型 tool→blob 的端到端证据。

相关文档：[Harness Daemon](../modules/harness-daemon.md)、[开发与测试](development-and-testing.md)。
