# 存储模型

PostgreSQL 是 Catalog、Chat、Harness 与 Canvas 的 durable truth。唯一 schema 基线是
[`V1__schema.sql`](../../schema/src/main/resources/db/migration/V1__schema.sql)（`schema` 模块，
profile seeds 位于 `db/seed/**`）；不存在任何 schema mirror。PostgreSQL `LISTEN/NOTIFY`
承载 Work/version/realtime 的低延迟提示，通知丢失由 periodic poll 或 snapshot 恢复；S3 保存
全局 Blob 与 ComfyUI 临时对象。

## 1. 身份规则

- kk-studio 生成并持久化的实体、commandId、requestId 使用 PostgreSQL `uuid`。
- `version`、`version`、`sequence`、`attempt`、`ordinal`、`resource_index`、`ref_count`、
  `size_bytes`、宽高和时长保持整数。
- Catalog 的稳定自然键保持不变：Provider/Agent 使用 `name`，Model 使用
  `(provider_name, name)`，ComfyUI Workflow 使用唯一 `api_name`。
- Provider response ID、Tool call ID、ComfyUI prompt ID 等外部 opaque ID 保持字符串。
- V1 是唯一 schema 基线；profile seeds 与 schema 分离，缺失事实使用 NULL，实体身份不使用全局 bigint sequence。

## 2. Catalog 与 ComfyUI

| 表 | 身份与职责 |
| --- | --- |
| `agent_provider` | `name` 主键；Provider 连接事实与结构化 config；`version` 是 CRUD CAS token |
| `agent_model` | `(provider_name, name)` 主键；结构化 Model config |
| `agent_definition` | `name` 主键；引用 Model 自然键；config 保存 tools/skills/subagents |
| `comfyui_workflow_api` | `id uuid` 主键、唯一 `api_name`、workflow/input bindings、selector、enabled、version |

Provider/Model/Agent 都使用带 `expectedVersion` 的硬删除。记录存续期间自然键不可修改；删除后
同名可重建，重建行 version 从 0 开始。

## 3. Chat

| 表 | 字段与职责 |
| --- | --- |
| `chat` | `id uuid`、标题、`agent_name`、可空 `environment_name`、`yolo_enabled`、version 与时间 |
| `chat_session` | PK `session_id`；`chat_id`；Chat↔Session 归属关系（真实 FK） |

Chat 设置不复制到 Thread。Thread 的完整 BranchSettings 来自 ROOT/TURN_START Entry。不存在 `chat_thread`；用户 Session 恰好归属于一个 Chat 或 Canvas（插入时以 `not exists(canvas_session)` 保证单 owner 原子性）。

删除 Chat 时，应用先枚举关联 Session，显式释放 `session_blob_ref`，再深删除 Harness 执行事实。Blob 引用不能由 FK cascade 隐式维护。

## 4. Harness Runtime 执行表（精确 7 张）

| 表 | 关键字段与约束 |
| --- | --- |
| `harness_session` | `id uuid`、`created_at`；Entry Tree 边界 |
| `harness_entry` | `id uuid`、session/parent、entry_type、payload、时间；每 Session 唯一 ROOT |
| `harness_thread` | `id uuid`、`session_id`、非空 `head_entry_id`（同 Session FK）、`materialization_hash char(64)`、YOLO、next sequence、version、时间 |
| `harness_thread_command` | PK `(thread_id, sequence)`；`client_command_id uuid`、`request_hash`、payload 与消费/取消事实 |
| `harness_model_invocation` | `id uuid`、`basis_head_entry_id` + compact `ModelRequestSpec`、status、attempt、checkpoint、append-only `failed_attempts`、terminal result/error（`result_entry_id` 只属于 open Tool phase） |
| `harness_tool_invocation` | `id uuid`、ordinal、`call` + 可空 `binding`、approval、result/effects/error（无 `result_entry_id`） |
| `harness_work` | PK `(target_type, target_id uuid)`；available_at、wake_version、lease |

`harness_thread_command` 没有代理 id。`request_hash` 是 raw command（含 ordered contents 与 uploadId）
的 canonical SHA-256；同一 `(thread_id, client_command_id)` 只有 hash 相同才是 exact replay。

Harness EntryType：

```text
ROOT, TURN_START, MESSAGE, CUSTOM, MODEL_ATTEMPT_FAILURE, CUSTOM_MESSAGE,
ASSISTANT_ERROR, ASSISTANT_ABORTED, COMPACTION, TURN_END
```

普通 Model 的自动 retry 只把 `TRANSIENT` 失败按连续 attempt 前缀写入 `harness_model_invocation.failed_attempts`；active snapshot 从该列投影。终态/Stop 时这些事实按序物化为 `MODEL_ATTEMPT_FAILURE` Entry，并在结果 Entry attach 时从 Invocation 清空；失败 attempt Entry 不参与 Provider context，compaction 不物化该审计。

ThreadCommandType：

```text
USER_MESSAGE, CUSTOM_MESSAGE, SET_ENVIRONMENT, SET_AGENT, SET_MODEL,
SET_ACTIVE_TOOLS
```

## 5. 全局 Blob Storage

| 表 | 身份与职责 |
| --- | --- |
| `storage_blob` | `id uuid`；不可变内容与媒体事实；`ACTIVE/DELETING`；唯一 ACTIVE `(sha256,size_bytes)`；唯一一级 `ref_count` |
| `storage_upload` | `id uuid` 一次性 Handle；`blob_id IS NULL` 为 PENDING，非空为 READY；保存权威 filename/声明/checksum/expiry |
| `session_blob_ref` | PK `(session_id, blob_id)`；Session 对 Blob 的显式引用边，每行贡献一次 Blob retain |

对象键不落库：

```text
uploads/{uploadId}/original
blobs/{blobId}/original
blobs/{blobId}/preview.webp
```

### 5.1 Upload

Reserve 请求为 `{filename, mediaType, sizeBytes, sha256}`：

1. 命中 ACTIVE `(sha256,size)` 时 retain Blob 并直接创建 READY Handle。
2. 未命中时创建 PENDING Handle，返回包含 `If-None-Match: *` 与
   `x-amz-checksum-sha256` 的预签名 PUT。
3. complete 使用 checksum-mode HEAD 校验真实大小与 SHA-256，执行媒体探针，复制到候选 Blob
   key，再在短事务内解决去重并绑定 READY。
4. READY Handle 持有一个 Blob 引用；delete/expiry/消费删行并 release。

`StorageUploadService.lockReady` 使用 `PROPAGATION_MANDATORY`，返回锁定行中的权威
`blobId + filename`。消费方必须在同一外层事务完成 retain 新 owner、删除 Upload 与写入 owner 行。

### 5.2 Blob 生命周期

`storage_blob.ref_count` 只统计直接 owner：

- READY Upload；
- Canvas Blob Resource 行；
- `session_blob_ref` 行。

Function INPUT/OUTPUT pin 不修改 ref_count，只决定无 owner Canvas Resource 是否保留。

`retain/release` 都要求调用方事务。release 减到 0 时同一 SQL 将行从
`ACTIVE(ref_count>0)` 切换为 `DELETING(ref_count=0)`；DELETING 不可复活。提交后当前线程按
`preview -> original -> row` 清理。机会式小批次与应用启动恢复处理过期 Upload 和崩溃留下的
DELETING 行；没有独立 GC worker。

## 6. Durable Harness Resource

Wire `USER_MESSAGE` 的 `ATTACHMENT(uploadId)` 是瞬时内容，不能进入 durable codec。Chat/Canvas
命令提交事务消费 READY Upload 后写成：

```text
ResourceMessageContent(blobId, name, preview?)
```

并通过 `session_blob_ref` retain。Tool 边界仍可产生瞬时
`ResourceRef(uri, mediaType, name, size, sha256)`；在 Tool Result Entry 写入前，
`GlobalStorageToolResultHistoryMaterializer` 有界读取 data/file/http/https/s3 内容，摄入全局 Blob，
把 durable history 转换为同一 `ResourceMessageContent`。因此 Entry/Command durable JSON 不保存
URI、bucket、object key、mediaType、size 或长期 URL。

Provider attempt 才从 `storage_blob` 读取媒体事实并生成新鲜预签名 URL；前端渲染也只通过
`/api/storage/blobs/{blobId}/presigned-original|presigned-preview` 临时解析地址。原件响应额外携带
权威 `mediaType/sizeBytes`（sizeBytes 是 Java long，wire 为十进制字符串或 null，前端 adapter
归一化为 number|null）；preview 与 upload 签名允许这两个字段为 null。

## 7. Canvas

| 表 | 职责 |
| --- | --- |
| `canvas_document` | `id uuid`、标题、单调 `version`、时间；不含 thread_id，经 `canvas_session` 持有 0..N 个 Session |
| `canvas_group` | 不嵌套的 world transform Group |
| `canvas_node` | 唯一 Node 形态；name/transform/group；可选 `model_key + function_config_json` |
| `canvas_link` | PK `(canvas_id, source_node_id, target_node_id)`；候选引用边，允许成环 |
| `canvas_resource` | `id uuid`；nullable owner pair；`blob_id` 与 `text_content` 恰好互斥 |
| `canvas_function_run` | PK `node_id`；当前/最后 Run，`request_id uuid` |
| `canvas_function_resource_pin` | PK `(canvas_id, node_id, request_id, role, resource_id)`；INPUT/OUTPUT pin；只保护 Resource 生命周期 |
| `canvas_command_dedup` | PK `(canvas_id, command_id)`；仅 request hash（无 applied version、无 created_at） |

`canvas_resource(owner_node_id, resource_index)` 成对可空：可见资源必须有 owner；Function target
物化中与 pinned orphan 可以暂时无 owner。Canvas Resource 行贡献一个 Blob 引用；删除行必须显式
release。

`canvas_document.version` 是 command 响应 Patch、事件通道、Snapshot 与 Function 可见状态的公共坐标，初始 0：

- 成功 graph command batch +1；
- Function start/cancel/checkpoint/success/failure 状态前进 +1；
- exact replay 不增加；Harness command acceptance 不前移 graph version（Canvas 与 Agent Session 生命周期独立，无 Thread 绑定版本变化）。

Canvas 不持久化或缓存 Patch Stream；PostgreSQL trigger 只发 version NOTIFY 提示，不修改实体。

## 8. FK 与删除

- Harness Runtime 七表保持 Runtime schema 原始 FK 规则。
- `storage_upload -> storage_blob`、`session_blob_ref -> session/blob`、
  `canvas_resource -> storage_blob` 都是 RESTRICT。
- Canvas ownership FK 使用 RESTRICT；应用按
  `pins -> resources/runs -> links -> nodes -> groups -> dedup -> document` 显式删除。
- Canvas/Chat 深删除在删除 Session 前逐行删除 `session_blob_ref` 并 release Blob。
- 归属关系表 `chat_session` / `canvas_session` 的 `session_id` 主键 + owner FK（RESTRICT）；删除 Chat/Canvas 时应用先列 Session 深删（`HarnessSessionDeletionService`），再删 owner relation 与 owner 行。

## 9. 时间与事务

- Harness 锁序由 [harness-runtime-contracts.md](harness-runtime-contracts.md) 定义：
  `Session KEY SHARE -> Thread -> Commands -> Model -> Tool siblings -> Work`（删除/归属独占用 Session FOR UPDATE）。
- Harness Store 加入调用方事务（`PROPAGATION_REQUIRED`）；引用管理器与 `lockReady` 使用
  `MANDATORY`，防止行锁提前释放。
- `canvas_document` 写入先锁 document，再锁 node/resource/run；Function 使用
  `document -> node -> run`。
- PostgreSQL `current_timestamp` 是事务开始时间，不能用于可能等待锁后提交的单调更新时间；
  Canvas UPDATE 使用 `clock_timestamp()` 与 `greatest(...)`。
