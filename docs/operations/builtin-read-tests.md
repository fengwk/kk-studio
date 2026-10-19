# 内置 Read 的行为验证

验证 `read` 时先区分文本窗口、本地文件类型分派和受管 Resource/Skill 调用链。
文本窗口由共享核心生成，本地与受管适配器各自负责打开输入、编码与截止时间；
最后还要验证完整结果能进入 durable history，而不是只确认 Daemon 返回了一段文本。
契约见[内置工具设计：文件读取](../modules/builtin-tools-design.md#文件读取)。

## 运行入口与副作用

运行测试时区分 Node Daemon 与后端 Java 模块。共享核心与本地测试使用临时文件；Platform 集成测试可能启动
Testcontainers 的 PostgreSQL，需要 Docker，不能换成生产或共享数据面。
这里的 Blob 物化测试使用内存 S3 替身，截止测试使用真实 SDK 连接本地 HTTP 服务，不启动真实 MinIO。

```bash
# Daemon：本地文本窗口、编码、图片与目录分派（Node 24）
npm --prefix daemon ci
npm --prefix daemon run check
npm --prefix daemon run build
npm --prefix daemon run test -- test/coding/files.test.ts test/coding/text.test.ts
npm --prefix daemon run coverage

# 后端：共享文本窗口核心与 Platform 受管适配（JDK 21）
env JAVA_HOME="$JAVA_HOME_21" mvn -pl harness/common -am verify
env JAVA_HOME="$JAVA_HOME_21" mvn -pl platform/runtime -am test \
  -Dtest='ReadTextWindowTest,PlatformReadToolExecutorTest,PlatformResourceContentReaderTest,PlatformSkillContentReaderTest,S3StorageDeadlineIntegrationTest,ToolResultFinalizerTest,GlobalStorageToolResultHistoryMaterializerIntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Daemon 覆盖率由 Vitest 与 `@vitest/coverage-v8` 统计（`npm --prefix daemon run coverage`）；
Java 目标模块的 `target/surefire-reports` 必须证明用例实际运行，覆盖率报告在 `target/site/jacoco`。
`harness/common` 与 `platform` 的 JaCoCo `check` 在 `verify` 阶段执行，`test` 只生成报告。筛选测试得到的报告不能代表整个模块的覆盖率。

真实审批与模型续接需要单独授权付费，并准备独立 Backend、数据库、S3 与 Daemon：

```bash
./scripts/dev/verify/e2e/run.sh --real --with-tools --with-canvas-storage --only tool.read_turn
```

这是 L4 用例，会同步真实 Provider 凭据并调用模型，不是普通单元测试。费用、端口复用和数据隔离要求见
[开发与测试](development-and-testing.md#e2e)；只有 `--list` / `--docs` 是无服务副作用的 inventory 查询。

## 文本窗口是否准确

[`TextReadWindowTest`](../../harness/common/src/test/java/fun/fengwk/kkstudio/harness/common/text/TextReadWindowTest.java)
验证共享核心；
[`test/coding/text.test.ts`](../../daemon/test/coding/text.test.ts)
与 Platform 的
[`ReadTextWindowTest`](../../platform/runtime/src/test/java/fun/fengwk/kkstudio/platform/harness/read/ReadTextWindowTest.java)
验证两个适配器。同名近似的类不要混为一层。

| 要验证的行为 | 主要证据 |
| --- | --- |
| offset/limit、准确总行数、右对齐行号、文件级 `ends_with_newline` | 核心 `windowMetadataAndRightAlignedLineNumbers`；本地 `files.test.ts` 渲染有界窗口 header 与总行数；受管 `offsetAndLimitProduceWindowAndLineLimitTruncation`、`largeStreamKeepsWindowWithAccurateLineCount` |
| CR/LF/CRLF 作为边界，正文不包含行尾 | 核心与受管 `crlfAndLoneCrAreLineBoundaries`；本地 `text.test.ts` 验证 CRLF 折叠与孤立 CR 边界 |
| 空文件与 EOF 后窗口 | 本地 `text.test.ts` 与 `files.test.ts` 验证空文件与超 EOF 偏移输出 `range: empty`；受管 `emptyInputReportsEmptyRange` |
| 长行不按 2000 字符裁剪，达到正文预算时能续读下一列 | 核心 `characterBudgetCutsMidLineAndPointsAtNextColumn`；本地 `text.test.ts` 验证码点预算耗尽报告 `character_limit`；受管 `longLineIsNotTruncated` |
| 参数错误先于文件访问，EOF 后偏移优先于列校验 | 本地 `files.test.ts` 验证相对路径先于文件访问被拒绝；受管 `invalidArgumentsAreRejected` |
| BOM 仅在输入最开始剥一次 | 核心 `withoutLeadingBomStripsOnlyTheVeryFirstCharacter`；本地 `text.test.ts` 验证 UTF-8 BOM 保留与 UTF-16LE/BE 往返；受管 `byteOrderMarkIsStrippedOnlyAtTheVeryStart` |
| 取消、中断与 I/O 失败不返回半个成功窗口 | 核心 `checkpointRunsAroundEveryBlockAndAbortsTheScan`；本地支持取消中断；受管 `streamFailuresAreMappedToPlatformReadException` |
| 可用的 LSP 状态是最后一行 header，不可用时省略 | 核心 `lspHeaderIsLastAndOmittedWhenUnavailable`；本地 `files.test.ts` 验证 lsp header 探测挂钩；受管 `lspStatusIsLastHeaderLineAndOmittedWhenUnsupported` |

文本最多展示 2000 行、60000 码点正文，截断位置包含下一行/列坐标；`column_offset` 不限于 `limit=1`。
内部按长整型计数，输出坐标超出协议范围时显式失败，不截断成错误的续读位置。
为给出准确总行数和末尾换行事实，读取必须扫描到 EOF：不存在 64 MiB 文件拒绝上限，
成本由 Daemon 调用预算（60 秒，不新增 schema 参数）、受管资源 `managedresource read` 从 effective `request.timeout` 产生的唯一 `ReadDeadline`（默认 60 秒，I/O、鉴权与流式扫描共用该预算，无 30 秒子期限；skill 扫描同 deadline）和取消/中断控制。稀疏大文件也不是无成本读取。

## 本地路径、编码与文件类型

[`test/coding/files.test.ts`](../../daemon/test/coding/files.test.ts)
直接调用本地 `fs.read`，固定以下分派边界：

- `path` 必填；空对象不是读取当前目录。
  `path` 必须是绝对路径，相对路径直接拒绝且不接受 workdir，文件名中的非 ASCII 空格保持原样。
- 本地文本只支持严格 UTF-8 与 BOM 标记的 UTF-16LE/BE。
  受管文本按严格 UTF-8；NUL、非法字节与无 BOM 的旧编码不做字符集猜测，以受控错误拒绝，有效的替换字符不能误判成二进制。
- 目录有独立 `kind: directory` 语义，默认 2000 项、48 KiB 展示预算；续读用报告中的 offset，合法 column offset 被忽略。48 KiB 不是文本窗口的上限。
- png/jpeg/gif/webp 按签名返回二进制结果，不通过文本窗口；bmp 不在支持集合。合法 column offset 同样被忽略。
- 特殊节点在读取前拒绝。非普通文件节点在 I/O 前拒绝。
- 参数校验先于文件访问：非正 `column_offset` 在目录与图片上同样被拒绝，只有合法值才被忽略。

read 不等待进程内文件变更队列，也没有成功 observer 回调；原子替换避免半文件提交，但不提供跨调用
陈旧读取保护。LSP 可用性由服务层或调用方提供，read 适配器不自行启动或猜测服务器。

## 受管结果与 durable history

`PlatformReadToolExecutorTest`、`PlatformResourceContentReaderTest`、`PlatformSkillContentReaderTest`
验证 Resource/Skill 读取进入同一窗口投影；
[`S3StorageDeadlineIntegrationTest`](../../platform/runtime/src/test/java/fun/fengwk/kkstudio/platform/storage/S3StorageDeadlineIntegrationTest.java)
验证响应头前与响应体内阻塞时存储截止时间生效及错误翻译。
`PlatformResourceContentReader` 的 `ReadDeadline` 预算（继承自请求的有效超时，默认 60 秒）与线程中断是两种约束，应分别观察超时与取消路径；I/O、鉴权、对象流与扫描共用同一截止时间，不存在独立的 30 秒子期限。
`ToolResultFinalizerTest` 验证可信 read 身份专属的 **320 KiB / 2020 物理行**内联预算：
2000 行与 60000 码点的最坏文本窗口连同行号、header 仍在预算内，因此文本不产生 resource 预览。
图片等二进制结果的外部化不是这条内联保证。

[`GlobalStorageToolResultHistoryMaterializerIntegrationTest`](../../platform/runtime/src/test/java/fun/fengwk/kkstudio/platform/harness/tool/gateway/GlobalStorageToolResultHistoryMaterializerIntegrationTest.java)
另行守卫工具结果外部化到全局 Blob、Session 引用转移与权威文件名。
E2E `tool.read_turn` 只证明非 YOLO 审批、ALLOW 后完整 canonical 文本内联进入 durable `tool_result`，
以及后续模型轮次成功；它不提供真实模型 tool→blob 的端到端证据。

相关文档：[Environment Daemon](../modules/daemon.md)、[开发与测试](development-and-testing.md)。
