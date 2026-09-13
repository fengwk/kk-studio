# 工具输出

## 1. 目标

所有 Host、Environment、MCP 和角色 Tool 使用同一个 terminal output contract：

- 小文本完整进入模型上下文。
- 大文本不在上下文中展示大段 head/tail；完整内容保存为不可变 Blob。
- 模型只看到很小的前缀、明确的不完整声明、精确大小和 `cloud_read`/`cloud_grep` 可访问路径。
- Tool history、重放、compaction、UI 和 Provider attempt 使用同一个 durable Blob identity。
- 完整内容没有持久化成功时，绝不返回一个看似成功但不可恢复的截断结果。

这个机制只处理**输出表示大小**。`grep limit`、超时、文件读取上限等工具自身的 semantic limit 继续由工具报告，不能声称 Artifact 包含工具没有生成的内容。

## 2. 固定限制

| 限制 | 目标值 | 语义 |
| --- | --- | --- |
| `INLINE_MAX_UTF8_BYTES` | 50 KiB | terminal textual projection 的完整内联字节上限。 |
| `INLINE_MAX_LINES` | 2000 | terminal textual projection 的完整内联行数上限。 |
| `TRUNCATED_PREVIEW_MAX_UTF8_BYTES` | 2 KiB | 大输出只展示的 UTF-8 安全前缀。 |
| `TRUNCATED_PREVIEW_MAX_LINES` | 20 | preview 最大行数。 |
| `MAX_RESOURCE_BYTES` | 16 MiB | 单次可持久化 Tool Artifact 的默认硬上限。 |
| `MAX_TERMINAL_RESULT_UTF8_BYTES` | 1 MiB | 外部化后的 canonical ToolResult JSON 上限。 |
| `MAX_PARTIAL_RESULT_UTF8_BYTES` | 256 KiB | 单条 live partial 上限；生产者还应使用更小块。 |

这些是协议常量，不做每 Agent、Project、Environment 或 Tool 配置。System Settings 只保留真正需要运维调整的硬资源上限时，也必须在一次调用开始前冻结。

超过 hard resource limit 的内容不承诺保存；工具必须返回明确 `OUTPUT_TOO_LARGE`，不能只留下前缀并称为完整输出。

## 3. Terminal 管线

```text
Tool producer
  |
  |  Text/Json/Binary/Resource + error/details
  v
Tool Gateway callback fence
  |
  v
ToolResultFinalizer
  |-- measure canonical textual projection
  |-- inline, or plan one immutable text Resource
  |-- validate complete projected result
  `-- emit canonical ToolResult + raw text-artifact metadata
  |
  v
Harness terminal commit
  |-- Global history materialization
  |-- storage_blob ingest + session_blob_ref
  |-- CFS Artifact node + independent blob retain
  `-- ResourceMessageContent(blobId, artifactPath, counts, small preview)
  |
  +--> next Provider attempt: deterministic Cloud path text
  +--> frontend: Artifact attachment/action
  `--> compaction: retain Cloud path placeholder
```

`ToolResultFinalizer` 是唯一拥有模型展示策略的组件。Daemon 的 producer-side spool
只负责有界内存和跨 wire 搬运，不生成另一套大 preview 或终态文案。

为了让已在 Daemon spool 的完整文本与 Platform 本地文本使用相同策略，通用结果协议增加可选
`TextArtifactMetadata(totalBytes, totalLines)`：

- Daemon wire 携带完整 Base64 bytes、size、SHA-256 和该 metadata；Backend
  校验后解码为 transient `BinaryResultContent`，不保留 Daemon 本地 URI；
- `ToolResultFinalizer` 将 bytes 写入 Platform `ResourceStore` 后才产生
  `ResourceResultContent`，其 preview 必须从已校验 bytes 重新计算；
- durable `ResourceMessageContent` 额外携带 `artifactPath`；
- 普通 image/audio/binary Resource 没有该 metadata；
- finalizer 校验 producer 声明与实际可知的 size，history materializer 在摄入时复核 bytes、UTF-8 和 line count。

物理行计数固定为：空文本 0；否则为 LF 数量，加上“最后一个字节不是 LF”时的一行。CRLF
因此只计一行，内容 bytes 不被改写。

## 4. Canonical textual projection

一个 ToolResult 可以含多个 `TextResultContent` 或 `JsonResultContent`。Finalizer 按原始 content 顺序，以两个换行连接其模型可见文本，形成唯一 textual projection：

```text
text(content-1) + "\n\n" + text(content-2) + ...
```

- JSON 使用其 canonical JSON 文本。
- `detailsJson` 不进入 projection，继续独立受 1 MiB 上限保护。
- Binary/Resource 不计入 textual projection；它们保持独立 attachment 顺序。
- 第一方文本型 Tool 应只返回一个 Text 或 Json content，避免不必要的拼接。

若 projection 同时不超过 byte/line limit，则保留原 Text/Json contents。

若任一限制超出：

1. 将完整 projection 作为一个 UTF-8 `text/plain` Artifact；
2. 删除原 Text/Json contents；
3. 在第一个原文本位置放入一个带小 preview 的 `ResourceResultContent`；
4. 非文本 Resource 按原顺序保留；
5. `error` 和 `detailsJson` 原样保留。

这保证限制按整个模型可见文本计算，不能通过返回许多略小于阈值的 content 绕过。

单个明确的 `JsonResultContent` 外部化时可以保存为 `application/json` 和 `.json`；混合或多文本 projection 使用 `text/plain` 和 `.txt`。

## 5. 模型可见格式

Materializer 已得到 `blobId` 后，Platform 用统一 formatter 生成：

```text
[Output externalized. The preview below is incomplete; do not treat it as
the full result.

Full output: /.artifacts/tool-results/8628dc80-e1c1-4b7c-948b-82713534f9c0/0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01.txt
Size: 1843220 bytes, 12441 lines

Use cloud_grep to locate relevant content, then cloud_read with offset/limit.]

--- preview ---
<最多 2 KiB / 20 行的 UTF-8 安全前缀>
```

规则：

- 声明位于 preview 前，防止模型先把局部文本当完整结果。
- 只显示 prefix，不显示 tail。
- path、byte count、line count 必须来自持久化事实或 finalize 时的精确测量。
- 不要求模型从 preview 继续猜测；提示优先 grep，再读取目标窗口。
- Process exit code、Tool error flag、semantic result limit 等放在独立稳定 metadata/正文中，不能依赖 tail。
- preview 为空也是合法结果。

结构化 facts 位于 durable Resource content，不能靠正则解析上述文字：

```json
{
  "blobId": "8ccd6b82-131e-469e-b939-a59bc52a2cc5",
  "name": "bash-result.txt",
  "artifactPath": "/.artifacts/tool-results/8628dc80-e1c1-4b7c-948b-82713534f9c0/0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01.txt",
  "totalBytes": 1843220,
  "totalLines": 12441
}
```

Daemon/Host producer 不写 `blobId` 或 `artifactPath`；全局 history materialization
后再形成 durable content。`ToolResult.detailsJson` 继续属于具体 Tool，不混入
externalization 系统字段。

## 6. Artifact 路径

完整输出通过 CFS 保留的只读 Blob 挂载访问：

```text
/.artifacts/tool-results/{threadId}/{invocationId}.txt
```

- 路径不包含 S3 bucket、object key、预签 URL、Daemon `file://` URI 或本地临时目录。
- `cloud_read` 和精确文件 `cloud_grep` 通过 Artifact BLOB node 读取 ACTIVE Blob。
- `.artifacts` 不能由普通 list/find 枚举，避免把 Tool history 变成目录索引。
- Artifact 创建标准 DIRECTORY/BLOB `cloud_node`，但不复制 bytes。
- Artifact node 和 `session_blob_ref` 分别 retain；Session 删除后 Artifact 仍持久存在。
- Resource 被其它领域共同引用时，任一 owner 删除不会提前删除 Blob。
- Artifact node、history content 和两个 retain 在同一个数据库事务中提交。
- 同一 invocation 重复 finalize 只有在 path、size、sha 全部相同时幂等成功。

## 7. Producer-side spool

统一展示不等于允许 producer 无限制把完整内容放进内存。

中间 spool 必须留在**执行所在的文件系统命名空间**：

| Producer | transient temp |
| --- | --- |
| Environment `read/find/grep/bash/LSP` | 目标 Daemon 的受控 resource temp 目录。 |
| Platform Host/MCP/Cloud Tool | Platform App 节点的受控 system temp 目录。 |
| CFS BLOB read/grep | Platform App 节点的受控 system temp 目录。 |

它们都应使用运行时 temp API 创建随机私有文件，NOFOLLOW、owner-only，并在
publish/cancel/error/process exit 后 best-effort 清理。不得把 Environment 中间 bytes
先发送到 Platform temp，也不得把 Platform 中间 bytes 写入 Environment `workdir`。
只有需要跨调用恢复的 terminal full output 才进入 CFS durable Artifact。

### 7.1 Environment

Daemon 输出生成器使用共享 `OutputSpool`：

```text
write chunk
  -> <= inline threshold: bounded memory
  -> threshold crossed: create private local resource temp，写入已有 bytes
  -> subsequent chunks: stream to resource
  -> hard limit + 1: stop capture，mark OUTPUT_TOO_LARGE
  -> complete: inline bytes or immutable Daemon ResourceRef + exact counts/raw prefix
```

- temp 只用于 transport，不是 durable 或模型可见路径；Daemon 本地 URI
  到 Backend 解码边界即终止，不能作为 Backend 取数地址。
- publish 继续使用 content-addressed、NOFOLLOW、size/sha 验证的本地 ResourceStore。
- `bash` 不再使用无界 `ByteArrayOutputStream`。
- read/find/grep 自己生成低于 inline limit 的有界 page/result，正常情况下不触发 spool。
- Daemon terminal result 不返回“50 KiB preview + full resource”两个 content。
- Daemon 只计算 bytes 和 lines；preview、外部化声明、Cloud path 和 prompt
  formatter 都由 Platform 决定。
- Daemon wire 直接把有界 Resource bytes 编码传给 Backend；Backend 必须保留并消费这些
  bytes，不能回读跨节点 `file://` URI。超过协商 hard limit 明确失败，不引入第二套上传协议。

### 7.2 Host/MCP

- Host Tool 已有完整 String 时，Finalizer先做 bounded UTF-8/line measurement，再决定是否编码。
- 可流式生成的 Host Tool 应直接使用 Platform ResourceStore sink。
- MCP SDK 返回的文本仍经过同一 Finalizer；MCP 自己声明 truncated 时保留 semantic marker，Platform 不声称 Artifact 是远端未返回的内容。

## 8. `read` 不递归外部化

Environment `read` 和 `cloud_read` 是恢复 Artifact 的基础工具，因此必须满足：

```text
最大 read response < INLINE_MAX_UTF8_BYTES
```

- 读取循环同时遵守 line limit 和 page byte budget。
- 达到 page budget 时在完整物理行边界停止，并返回下一 `offset`。
- 目录 listing 使用相同分页规则。
- 单行上限 2000 code points，避免一个物理行突破 page budget。
- ToolResultFinalizer 的测试断言 read 系列不会生成另一个 Tool Artifact。

本轮不提供超长单行横向分页。Artifact 含极长单行时，`cloud_grep` 可以返回命中附近 excerpt，但不能承诺由 `cloud_read` 完整重建该行。

## 9. Partial 与 UI streaming

Partial 是瞬态进度，不是 terminal full output：

- 只允许 Text/Json，不允许 Binary/Resource。
- 生产者按小块发送，单条和总缓冲均有界。
- 前端只把 partial 放在 live overlay；Snapshot 以 terminal ToolResult 为准。
- partial 不写入最终 Artifact，也不能作为丢失 terminal 字节的恢复来源。
- reconnect 后无需重放所有 partial。
- terminal projection 不因为此前已显示 partial 而删除 prefix；Provider 模型只依赖 durable history。

## 10. Binary 与媒体

- Binary 一律外部化为 Resource，不进入文本截断。
- 支持的 image/audio/video 按模型 input modality 在 attempt 时物化。
- 普通 binary 对模型返回 Resource metadata/attachment，不在 Tool output 文本中生成
  presigned URL。
- 文本媒体必须严格验证 UTF-8 后才允许 `cloud_read`/`cloud_grep`。
- Preview 是文本摘录，不是 URL、HTML 或媒体 source。

## 11. History、Provider 与 Compaction

### 11.1 Durable history

- Tool terminal commit 前只通过 Platform `ResourceStore` 读取其拥有的 Resource，
  完成 size/SHA-256 复核后摄入全局 Blob；不得自行解引用 data/file/http/https/s3 URI。
- Durable `ResourceMessageContent` 只保存 `blobId`、name、Artifact path、小 preview 和
  必要的 bytes/lines metadata。
- URI、S3 key 和预签 URL 不进入 Entry payload。
- 多 content 摄入保持 all-or-nothing；任一项失败，history transaction 回滚。

### 11.2 Provider

- Text Artifact 始终投影为包含 Cloud path 的 `ProviderTextBlock`，不把 S3 URL 当 document 传给 Provider。
- 支持的媒体才按 modality 物化为 media block。
- 每个 attempt 重新取得所需短期 URL；URL 不写回历史。

### 11.3 Compaction

Compaction 对 Resource 至少保留：

```text
[Resource: bash-result.txt, path:
/.artifacts/tool-results/{threadId}/{invocationId}.txt]
```

它不把完整 Artifact 注入 summarization prompt，也不丢掉之后读取所需的 path。Blob 引用跟随原 Session 生命周期，不因 compaction 重复 retain。

## 12. 失败语义

| 场景 | 终态 |
| --- | --- |
| 小结果 | 原样成功/错误 ToolResult。 |
| 大结果，Artifact 成功持久化 | 保留原 `error` flag，正文变为小 preview + Cloud path。 |
| Tool 在执行前返回非法 oversized result | `INVALID_RESULT`，无存储副作用。 |
| Tool 已完成，但 Artifact store/ingest/CFS node transaction 失败 | `UNKNOWN / RESOURCE_STORE_FAILED`；不伪造截断成功。 |
| 内容超过 hard resource limit | `OUTPUT_TOO_LARGE`；说明没有完整 Artifact，要求缩小范围或由命令自行重定向。 |
| Daemon Resource size/hash 不匹配 | 协议失败；不摄入、不显示不可信 preview。 |
| Blob 后续缺失/DELETING | `cloud_read` 明确资源不可用；不返回旧预签 URL。 |
| Tool 自己达到 `grep limit` | `resultLimited=true`；Artifact 仅代表已生成结果。 |

对有副作用 Tool，output persistence 失败不证明 Tool 未执行，因此不得自动 retry。对纯读 Tool，是否允许按现有 retry policy 重试仍由 Harness side-effect 和 failure classification 决定。

## 13. 代码收敛

目标职责：

| 组件 | 职责 |
| --- | --- |
| Daemon `OutputSpool` | 有界 capture、private temp、精确 bytes/lines；不决定模型 preview。 |
| Platform `ToolResultFinalizer` | 唯一 inline/externalize policy、精确 projection、受管 Resource 与 all-or-nothing plan。 |
| `GlobalStorageToolResultHistoryMaterializer` | 受管 Resource bytes → storage_blob + session ref + durable Resource content。 |
| `ToolArtifactPath` | `threadId + invocationId + media kind` 的 canonical CFS path formatter/parser。 |
| `ProviderResourceMaterializer` | Resource → text path或 attempt-only media。 |
| Frontend Resource renderer | 小 preview、大小、打开/下载 Artifact。 |

删除：

- Daemon 面向模型的 `OutputLimiter` preview/hint。
- Platform 8 KiB externalize、16 KiB preview 的双阈值语义。
- “preview 自己再被外部化为另一个 Resource”的路径。
- 依赖解析提示文字推断 upstream truncation 的逻辑。
- OS temp output path进入模型历史的做法。

## 14. 验收

- 50 KiB/2000 行边界两侧有 UTF-8、CRLF、emoji 和空行测试。
- 大输出只摄入一个 full textual Artifact，preview 不超过 2 KiB/20 行。
- 多 Text/Json content 不能绕过 aggregate limit，projection 顺序确定。
- Error ToolResult 外部化后仍为 error，exit/result-limit metadata 不丢失。
- Store plan 任一非法项在第一个 put 前失败；put contract mismatch 收敛 UNKNOWN。
- Environment 大输出不产生 preview Resource + full Resource 双份 Blob。
- `cloud_read`/`cloud_grep` 可以读取 history 中给出的
  `/.artifacts/tool-results/...` 路径；无 Environment Agent 同样可用。
- read page 永远保持 inline，不发生 Artifact 套娃。
- Compaction 后 Cloud path 仍存在于模型上下文。
- Provider 请求和数据库 history 均不包含 presigned URL、bucket、object key 或 Daemon temp path。
- Session 删除后 Artifact node 的 ref 仍保证 Blob 和 path 可用；没有任意一个
  transaction 只创建 node、history 或 ref 的一部分。
