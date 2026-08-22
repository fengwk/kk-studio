# Canvas Resource/Function v1

## 1. 职责

Canvas 是资源组织与创作界面，不是工作流编排器。当前模型支持：

- 图片、视频、音频与 Markdown 文本 `ResourceNode`；
- GPT Image 2、Seedance 2.0 系列、MiniMax-H3 Ref2VA 与免费 fake Function；
- Group、Link、结构化 `@` 引用；
- PostgreSQL durable graph、Redis bounded Patch Cache、PostgreSQL `NOTIFY` + 应用事件通道；
- 与独立 Chat 共用 Attachment Pill Composer；Canvas 经 `canvas_session` 持有 0..N 个 Harness Session，首条 Agent 消息与 Chat 共用 `POST /api/ai/runtime/command-batches`（NEW_SESSION target）。

Link 只声明 source Resource 是 target Function 的候选引用，不表示执行依赖。Link 可以成环；v1 不实现 DAG、自动下游执行、条件、循环、CRDT、Presence 或 WebSocket 协作协议。

## 2. 核心模型

```text
CanvasDocument
├── ResourceNode[]
│   ├── Resource[]       当前按 resourceIndex 排序的可见资源
│   ├── Function?        modelKey + canonical configJson
│   └── FunctionRun?     当前或最后一次运行
├── Group[]
└── Link[]
```

所有系统生成并持久化的 Canvas identity、commandId 与 requestId 都是 UUID。`version`（graph 版本）
在数据库是 `bigint`/Java `long` 整数，但 HTTP wire 上统一序列化为 canonical 非负十进制字符串
（convention4j 对 long 的默认输出），前端契约与比较全程保持字符串；`resourceIndex`、宽高等
非 long 字段保持整数。

### 2.1 CanvasDocument 与 version

`CanvasDocument` 持久字段为：

```text
id, title, version, createdAt, updatedAt
```

- `version` 是 Graph、Patch、事件通道和 `expectedVersion` CAS 共用的单调游标，初始 0。DTO/事件帧中的
  version 一律是 canonical 非负十进制字符串（如 `"0"`、`"42"`），与数据库 `bigint` 一一对应；
  客户端禁止转换为 JS number，比较必须使用十进制字符串长度/字典序（bigint-safe）。
- 每个成功的 typed graph command batch 恰好前进一次。
- Function Run 的新 start、checkpoint、cancel、success 或 failure 各前进一次。
- 同 commandId + 同 request hash 的精确重放返回当前版本的空 Patch，不前进。
- 不含 `threadId`：Canvas 经 `canvas_session(session_id, canvas_id)` 持有 0..N 个 Harness Session；Harness command acceptance 不前移 graph version（Canvas Graph 与 Agent Session 生命周期独立）。
- PostgreSQL UPDATE 使用 `greatest(updated_at, clock_timestamp())`，不依赖事务开始时间，保证 `updatedAt` 不回拨。

### 2.2 ResourceNode

```text
ResourceNode
├── id
├── canvasId
├── name
├── transform
├── groupId?
├── Resource[]
├── Function?
└── FunctionRun?
```

不变量：

1. 普通节点至少有一个 Resource；Function 节点首次成功前可以为空。
2. 同一节点的 Resource 必须同为文本或同为 Blob 内容。
3. 名称经 NFKC + trim 规范化、非空且不超过 256 字符；当前模型不要求 Canvas 内名称唯一。
4. Group 不嵌套，节点与 Group 都使用 world 绝对坐标。
5. `UPDATE_NODE_TRANSFORMS` 保存绝对 transform；一个 batch 内不得重复 nodeId。

### 2.3 Resource

`canvas_resource` 只保存 Canvas 归属与内容引用：

```text
id, canvasId, ownerNodeId?, resourceIndex?, blobId?, name, textContent?, createdAt
```

不变量：

- `ownerNodeId` 与 `resourceIndex` 成对存在或成对为空。
- 可见资源直接以 `(ownerNodeId, resourceIndex)` 属于 Node。
- Function 输出物化中的 target，以及已删除 source 留下的 pinned orphan，可以暂时无 owner。
- `blobId` 与 `textContent` 恰好一个非空。
- TEXT 内容内联；媒体事实不复制到 Resource 行，而从 `storage_blob` 读取。
- 每个 Blob Resource 行贡献一个 `storage_blob.ref_count` 引用；Function pin 不增加 Blob ref_count。

对象键只由 Blob ID 派生，不落库：

```text
blobs/{blobId}/original
blobs/{blobId}/preview.webp
```

图片和视频按需生成 WebP preview。浏览器只接收短期预签名 URL，不接收 bucket 或 object key。

### 2.4 Function 配置与 Run

Function 只在 Node 上持久化：

```text
modelKey
configJson
```

`configJson` 是严格 JSON object：

```json
{
  "prompt": {
    "segments": [
      {"type": "TEXT", "text": "..."},
      {"type": "REFERENCE", "nodeId": "00000000-0000-0000-0000-000000000001", "index": 0}
    ]
  },
  "parameters": {"ratio": "16:9", "duration": 5}
}
```

- `nodeId` 是 canonical UUID string；`index` 是非负整数。
- 未知字段、`null`、错误类型和未声明参数一律拒绝。
- canonical freeze 删除空 TEXT、合并相邻 TEXT，并按 REFERENCE 首次出现顺序去重 manifest。
- model descriptor 由服务端 Registry 声明 output kind、引用策略和参数，不建能力表。

每个 Function Node 只有一行当前/最后 Run：

```text
nodeId, requestId, status, stateJson, error?, updatedAt
```

状态为 `RUNNING | SUCCEEDED | FAILED | CANCELLED`。相同 requestId exact replay；不同 requestId 遇 RUNNING 冲突；终态可被新 request 覆盖。公开 DTO 只返回 `nodeId/requestId/status/stage/error/updatedAt`，不返回 `stateJson`。

`stateJson` 保存 versioned frozen plan、manifest、预分配 target Resource ID、stage 与 adapter checkpoint。Checkpoint 只按 `nodeId + requestId + RUNNING` CAS 更新，并在同一事务内前进 document version 与发布 node patch；任何 CAS/取消/节点消失都回滚且不发布。

### 2.5 INPUT/OUTPUT pin 与成功交换

`canvas_function_resource_pin` 保存 Run 生命周期 pin：

```text
canvasId, nodeId, requestId, role(INPUT|OUTPUT), resourceId
```

PK `(canvas_id, node_id, request_id, role, resource_id)`。

- start 为冻结 manifest 写 INPUT pin，为预分配 target ID 写 OUTPUT pin。
- pin 只保护 Resource 行生命周期，不参与 Blob ref_count。
- 删除 source Node 时，被 pin 的 owned Resource 只 detach owner；无 pin Resource 删除并 release Blob。
- adapter 先把 target 物化成无 owner Blob Resource。
- success 事务先处理旧 owned Resource，再把 target attach 到 `resourceIndex=0`，最后 CAS Run 为 SUCCEEDED 并前进 version。
- failure/cancel/迟到结果只删除仍无 owner 的 target；旧可见 Resource 保持不变。
- 最后一个 pin 释放时，无 owner Resource 才删除并 release Blob。

### 2.6 Link 与 Group

Link identity 是 `(canvasId, sourceNodeId, targetNodeId)`：

- source 与 target 必须不同且属于同一 Canvas；
- source 当前至少拥有一个 Resource；
- target 必须有 Function；
- Link 允许形成环；
- Run start 时重新校验 Link、source/index 与 model policy，并冻结具体 resourceId/blobId；之后 Link 删除、重命名或 source 重跑不改变本次输入。

Group 在前端渲染为位于成员下方的半透明范围，标题以通用 Header 紧贴范围左上方。移动
Group 在一个命令中按 delta 同时移动 Group 和当前成员 Node。`UNGROUP.memberNodeIds`
必须是当前成员的非空子集：只 detach 指定成员；移除最后一个成员时才删除 Group 行。
成员节点的包围框与 Group Body 不再有正面积交集时，前端在同一命令批中提交
`UPDATE_NODE_TRANSFORMS` 与该成员的 `UNGROUP`。`DELETE_GROUP` 始终先 detach 全部成员再删除
Group。

## 3. 持久化

Canvas 自有表：

```text
canvas_document
canvas_group
canvas_node
canvas_link
canvas_resource
canvas_function_run
canvas_function_resource_pin
canvas_command_dedup
```

共享表：

```text
storage_blob
storage_upload
session_blob_ref
harness_session / harness_entry / harness_thread / ...
```

关键列：

```text
canvas_document
  id uuid, title, version bigint

canvas_node
  id uuid, canvas_id, name, x, y, width, height, group_id?,
  model_key?, function_config_json?

canvas_resource
  id uuid, canvas_id, owner_node_id?, resource_index?,
  blob_id?, name, text_content?, created_at

canvas_function_run
  node_id uuid PK, request_id uuid, status, state_json, error?, updated_at

canvas_function_resource_pin
  canvas_id, node_id, request_id, role, resource_id

canvas_command_dedup
  canvas_id, command_id, request_hash

canvas_session
  session_id uuid PK, canvas_id, created_at
```

Canvas ownership FK 都是 `ON DELETE RESTRICT`。深删除由应用显式按生命周期顺序执行，不能用 cascade 绕过 Blob release。

## 4. Typed commands、Patch 与 API

命令类型：

```text
CREATE_TEXT_NODE
UPDATE_TEXT_NODE
CREATE_RESOURCE_NODE
CREATE_FUNCTION_NODE
UPDATE_FUNCTION
RENAME_NODE
UPDATE_NODE_TRANSFORMS
DELETE_NODE
CREATE_LINK
DELETE_LINK
CREATE_GROUP
MOVE_GROUP
UNGROUP
DELETE_GROUP
RENAME_GROUP
```

聚合 Snapshot 在装配前后同时核对 `canvas_document` 与有序 FunctionRun 列表；任一 version 或 Run
事实在多查询装配期间变化就重读，避免返回某一代 Run 与另一代 Graph/Resource 的混合结果。

请求：

```json
{
  "expectedVersion": "3",
  "commandId": "00000000-0000-0000-0000-000000000010",
  "commands": []
}
```

响应是实体 Patch：

```text
baseVersion -> version      # 都是 canonical 非负十进制字符串
groups[] = UPSERT | REMOVE
nodes[]  = UPSERT | REMOVE
links[]  = UPSERT | REMOVE
```

同 `(canvasId, commandId)` 的 canonical request hash 相同即精确重放；不同内容返回 409。stale `expectedVersion` 返回 409。

当前端点：

```text
GET    /api/canvases
POST   /api/canvases
GET    /api/canvases/{canvasId}
DELETE /api/canvases/{canvasId}
POST   /api/canvases/{canvasId}/commands
GET    /api/canvases/{canvasId}/changes?afterVersion=N
GET    /api/canvases/{canvasId}/sessions
WebSocket /api/events/v1        -> kind=canvas 版本事件订阅（version/resync）

GET    /api/canvas-function-models
POST   /api/canvases/{canvasId}/nodes/{nodeId}/runs
GET    /api/canvases/{canvasId}/nodes/{nodeId}/run
POST   /api/canvases/{canvasId}/nodes/{nodeId}/run/cancel

POST   /api/canvases/{canvasId}/resources/{resourceId}/download-url
POST   /api/canvases/{canvasId}/resources/{resourceId}/preview-url

POST   /api/storage/uploads
POST   /api/storage/uploads/{uploadId}/complete
DELETE /api/storage/uploads/{uploadId}
GET    /api/storage/blobs/{blobId}/presigned-original
GET    /api/storage/blobs/{blobId}/presigned-preview
```

不存在 `POST /api/canvases/{canvasId}/thread/messages`：Canvas 首条 Agent 消息与 Chat 共用 `POST /api/ai/runtime/command-batches`（NEW_SESSION target 原子创建 Session + ROOT + Thread + `canvas_session` relation + Commands + Work）。

## 5. 全局 Blob 上传与消费

浏览器先在 Web Worker 计算 SHA-256，再预约全局 Upload：

```text
POST /api/storage/uploads
  {filename, mediaType, sizeBytes, sha256}
-> PENDING + presignedPut
-> PUT uploads/{uploadId}/original
   headers: Content-Type + If-None-Match:* + x-amz-checksum-sha256
-> POST complete
-> checksum-mode HEAD 校验 size/checksum
-> 媒体探针
-> 按 (sha256,sizeBytes) 去重并返回 READY(blobId)
```

`storage_upload.blob_id IS NULL` 表示 PENDING，非空表示 READY。READY Upload 持有一个 Blob 引用，直到被删除、过期或消费。

`CREATE_RESOURCE_NODE` 在同一事务中逐个：

```text
lockReady(uploadId)
-> retain(blobId) 为 Resource 取得引用
-> delete(uploadId) 释放 Upload 引用
-> insert canvas_resource(ownerNodeId, resourceIndex, blobId, authoritative filename)
```

`lockReady` 使用 `PROPAGATION_MANDATORY`；调用方没有外层事务时直接失败，保证 upload 行锁覆盖 retain + delete。客户端文件名不进入消费决策，Resource name 取上传行的权威 filename。

## 6. Canvas Chat 与附件

Canvas 和独立 Chat 共用 Attachment Pill Composer。Wire `USER_MESSAGE` 只允许有序：

```text
TEXT(text)
ATTACHMENT(uploadId)
```

`ATTACHMENT` 只存在于请求与提交事务内；`StudioCommandAcceptanceService` 在 `acceptCommands` 事务内消费 READY Upload，写成 durable：

```text
RESOURCE(blobId, name, preview?)
```

并通过 `session_blob_ref(sessionId, blobId)` 持有 Session 级引用。`harness_thread_command.request_hash` 对 raw 请求（含 uploadId 与顺序）计算，所以 Upload 已消费后相同 commandId + raw hash 仍可精确重放，不会二次消费。

Canvas 首次发送：

```text
POST /api/ai/runtime/command-batches（NEW_SESSION target）
  -> 原子创建 Harness Session + ROOT + Thread
  -> 写 canvas_session relation（insertIfNotOwnedByOther 保证单 owner）
  -> 消费附件并入队 Commands + Work
```

Canvas 已绑定 Thread（同一 Session 的 THREAD target）时，只按 `clientCommandId + raw requestHash` 重放；缺失或不同内容返回 `COMMAND_ID_REUSED`。Canvas 经 `canvas_session` 持有多个 Session（多 Thread 共享 Canvas 历史是核心能力）。

## 7. Realtime 与恢复

PostgreSQL 实体与 `canvas_document.version` 是唯一事实源。

- 每次 command 或 Run start/checkpoint/terminal 状态前进产生一个 `baseVersion -> version` Patch。
- Patch 只在事务 `afterCommit` 写 Redis Stream。
- 每个 Canvas 一个 bounded Stream：`kk-studio:canvas:{canvasId}:changes`，默认 exact max length 5000。
- `/changes?afterVersion=N` 只在缓存覆盖全部连续版本时返回 patches；初次读取、Redis 不可用、损坏或任意 gap 都返回权威 Snapshot。
- PostgreSQL trigger 只在 document insert/version 变化后 `NOTIFY canvas_version`，不修改 version。
- 浏览器经应用事件通道（`/api/events/v1`，见 [application-event-channel.md](application-event-channel.md)）
  订阅 canvas：`version` 事件携带 canonical 非负十进制 graph version（`data {"version":"N"}`，顶层
  `cursor` 与之相等），收到后按最后已知版本拉 `/changes`；`resync` 要求整体替换 Snapshot；
  `subscribed`（首次与每次重连重订阅）同样触发 changes 同步，关闭快照 GET 与 wire 建立之间及
  断线窗口内的版本缺口。数字/前导零/负数/畸形 version 事件一律忽略。
- Function run 生命周期不再使用前端固定间隔轮询：每次 checkpoint/terminal 都随 node patch 前进
  version，前端以 version 事件驱动的 changes/snapshot 收敛；start/cancel 本地响应只做即时投影。

Redis 不是事实源，不建立 consumer group，也不承担恢复。

## 8. 深删除

Canvas 深删除在一个应用事务中显式执行：

```text
lock document
-> release Canvas Function pins
-> delete every Canvas Resource + release Blob ref
-> delete Function runs
-> delete links
-> delete nodes
-> delete groups
-> delete command dedup
-> delete canvas_document
-> deep delete owned Harness Sessions（HarnessSessionDeletionService）
   -> per Session：delete work/invocations/commands/thread/entries
   -> release all session_blob_ref
   -> delete session
```

先删 `canvas_document` 再删 Session，是因为 `canvas_session.canvas_id -> canvas_document` 使用 RESTRICT FK；整个顺序仍在同一外层事务中，任一步失败整体回滚。

Blob `release` 减到 0 时在事务内转为 `DELETING`，提交后当前线程按 `preview -> original -> row` 删除。应用启动恢复与机会式小批量清扫处理崩溃窗口；不运行独立 GC worker。

## 9. Function adapters

Registry 当前包含：

- `fake-image` / `fake-video`：仅启动快照 `storageMedia.s3Enabled=true` 且部署测试开关
  `kk-studio.canvas.function.fake-enabled=true` 时注册，用于免费回归；
- `gpt-image-2`；
- `seedance2.0`、`seedance2.0fast`、`seedance2.0_vip`、`seedance2.0fast_vip`；
- `minimax-h3-ref2va`。

Foundation 只向 adapter 暴露 checkpoint、RUNNING 检查、frozen Resource 原件流/短期签名和唯一 target 物化。付费提交前必须 checkpoint `SUBMITTING`；处于“已可能提交但没有 durable provider id”的崩溃窗口时确定性失败，禁止自动重提。

MiniMax-H3 的 Prompt Agent 也使用 durable Blob Resource：Canvas manifest 媒体在提交事务内摄入全局 Blob，Harness history 只持久化 `resource(blobId,name,preview)`；Provider attempt 才生成新鲜预签名 URL。

## 10. UI 与性能

- Canvas Editor 使用沉浸式 AppShell；桌面 Chat 并排且可调宽，窄屏覆盖。
- 媒体节点按原始比例自适应，整张卡片非交互区域可拖动；交互控件使用 `nodrag`。
- 图片/视频节点使用低 chrome 呈现，preview/original URL 按需预签。
- 为保证 Chat 面板开关后 React Flow 可见区正确，不启用 `onlyRenderVisibleElements`。
- Snapshot/Patch 不包含媒体字节、Base64、对象 key 或长期 URL。
- 视频首屏使用 WebP preview，显式播放时才加载原件。
- Markdown 禁止原始 HTML，继续使用安全 renderer。

## 11. 验证

后端覆盖：

- UUID schema 与 Harness schema byte-identity；
- Upload checksum、去重、并发 complete、READY 消费、过期恢复；
- Blob retain/release、ACTIVE/DELETING 与两阶段删除；
- Resource nullable owner pair、INPUT/OUTPUT pin、成功交换和失败清理；
- command version CAS/dedup、Link 成环、Patch gap/Snapshot、事件通道重连；
- Canvas 首发附件物化、raw request hash 重放与 Canvas/Session/Blob 深删除。

前端覆盖：

- Attachment Pill、IME、光标/Backspace/Delete、同名/重复附件、失败重试；
- Chat 与 Canvas ordered contents；
- Patch projection、事件通道 changes 恢复、Resource URL 渲染；
- Function run 生命周期（start/cancel 即时投影、version 事件驱动的 changes/snapshot 收敛、start 失败 authoritative fallback）与 config debounce/flush；RUNNING node 无固定间隔轮询；
- 节点尺寸、整卡拖拽、Chat 面板与窄屏布局。

默认自动化不得访问付费模型；真实 GPT Image、Seedance 和 H3 提交必须显式人工开关。
