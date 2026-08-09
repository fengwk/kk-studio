# Canvas Resource/Function v1

## 1. Scope

Canvas 是资源组织与创作界面，不是工作流编排器。

v1 支持：

- 图片、视频、音频、Markdown 文本资源；
- 图片、视频、音频、文本 `ResourceNode`；
- 图片生成：GPT Image 2；
- 视频生成：Seedance 2.0 系列与 MiniMax-H3 Ref2VA；
- Group、节点连线、`@` 资源引用；
- PostgreSQL 持久化；
- MinIO 原件、缩略图和视频封面存储；
- Harness Agent 驱动的 MiniMax-H3 提示词增强。

v1 不支持：

- Output、Invocation 历史或运行回放；
- 自动下游执行、DAG、条件、循环或通用 Function DSL；
- 音频生成与文本生成 Function；
- MiniMax-H3 I2VA、L2VA、FL2VA；
- 多人实时协作；
- Resource 引用计数与物理 GC；
- Group 嵌套。

## 2. Core Model

所有业务节点都是 `ResourceNode`。生成节点不是另一类节点，只是在
`ResourceNode` 上增加可选 Function。

```text
Canvas
├── ResourceNode[]
│   ├── Resource[]       当前导出的有序资源
│   └── Function?        可选资源生产能力
├── Link[]
└── Group[]
```

### 2.1 ResourceNode

```text
ResourceNode
├── id
├── canvasId
├── name
├── transform
├── groupId?
├── Resource[]
├── modelKey?
└── functionConfig?
```

节点不持久化独立 `resourceKind`。普通节点的类型由当前 Resource 决定；
Function 节点的生产类型由 `modelKey` 对应的服务端模型描述决定。

不变量：

1. 同一节点的当前 Resource 必须同类型；
2. 普通节点至少包含一个 Resource；
3. Function 节点在首次成功前允许 Resource 列表为空；
4. Function 成功后原子替换整个 Resource 列表；
5. Function 失败时保持原 Resource 列表；
6. 节点名称在 Canvas 内按规范化后的值唯一。

### 2.2 Resource

Resource 表示一份已完整写入、已完成服务端校验的不可变内容：

```text
Resource
├── id
├── canvasId
├── kind              IMAGE | VIDEO | AUDIO | TEXT
├── mediaType
├── name
├── size
├── text?             TEXT 使用
└── metadata          按 kind 解析的宽高、时长、帧率、编码等
```

对象键由服务端确定性派生，不落库：

```text
canvases/{canvasId}/resources/{resourceId}/original
canvases/{canvasId}/resources/{resourceId}/preview.webp
```

图片和视频 Resource 必须具有 `preview.webp`。音频使用类型图标与时长，
文本使用 Markdown 渲染，不创建预览对象。

Resource 创建后不修改内容、媒体类型、对象键或 metadata。文本编辑创建新
Resource 并替换节点资源。

### 2.3 Function

Function 仅持久化：

```text
modelKey
configJson
```

`configJson` 只包含用户配置：

- Prompt 文档；
- `@` 引用的 `{nodeId, index}`；
- 模型允许用户配置的比例、时长等参数。

Provider endpoint、ComfyUI 节点、workflow、MinIO key、Hub resource path、
内部帧数等实现细节不得进入 `configJson`。

模型能力由服务端代码 Registry 声明，不建立 Model/Capability 数据表。

### 2.4 FunctionRun

每个 Function 节点只保留当前或最后一次 Run：

```text
FunctionRun
├── nodeId
├── requestId
├── status            RUNNING | SUCCEEDED | FAILED | CANCELLED
├── stateJson
├── error?
└── updatedAt
```

`stateJson` 是当前运行的 crash-recovery checkpoint，最少保存：

- 冻结后的 model/config；
- 冻结后的有序 Resource manifest；
- 当前 adapter stage；
- provider execution/job/thread id；
- 已预分配的目标 Resource id。

不变量：

1. 一个节点最多一个非终态 Run；
2. 相同 `requestId` 重试返回同一个 Run；
3. 不同 `requestId` 遇到 RUNNING 返回冲突；
4. 新 Run 可以覆盖上一条终态 Run；
5. provider 完成时必须同时匹配 `nodeId + requestId + RUNNING`；
6. 迟到结果不得覆盖新 Run；
7. 节点删除后结果失效；
8. `SUCCEEDED` 表示所有资源已写入 MinIO、校验、建表并完成节点资源替换。

Run 状态更新与资源替换不修改 `graphRevision`。

### 2.5 Link and References

Link 使用 `(canvasId, sourceNodeId, targetNodeId)` 作为身份，不单独分配 id。

Link 只表示 source 当前资源可以作为 target Function 的引用候选，不表示：

- 已选中资源；
- 自动执行依赖；
- 下游触发关系；
- DAG 顺序。

Prompt 编辑态保存 `{nodeId, index}`。启动 Run 时服务端重新校验：

- Link 仍存在；
- source 与 target 属于同一 Canvas；
- index 未越界；
- Resource 类型和数量符合模型限制。

校验通过后冻结具体 `resourceId`。Run 启动后的节点重跑、Link 删除或名称修改
不改变本次输入。

### 2.6 Group

Group 使用 Canvas/world 绝对坐标，不建立父子 transform 坐标系。

移动 Group 通过一个命令在同一事务中移动 Group 和成员节点。删除 Group 只清空
成员 `groupId`。v1 不允许 Group 嵌套。

## 3. Persistence

Canvas 相关表：

```text
canvas_document
canvas_group
canvas_node
canvas_node_resource
canvas_link
canvas_function_run
canvas_resource
canvas_upload
canvas_command_dedup
```

### 3.1 Minimal Columns

```text
canvas_document
  id, title, graph_revision, created_at, updated_at

canvas_group
  id, canvas_id, title, x, y, width, height

canvas_node
  id, canvas_id, name, x, y, width, height, group_id,
  model_key, function_config

canvas_node_resource
  canvas_id, node_id, resource_index, resource_id

canvas_link
  canvas_id, source_node_id, target_node_id

canvas_function_run
  node_id, request_id, status, state_json, error, updated_at

canvas_resource
  id, canvas_id, kind, media_type, name, size, text_content,
  metadata_json, created_at

canvas_upload
  id, canvas_id, kind, filename, declared_media_type,
  declared_size, expires_at, created_at

canvas_command_dedup
  canvas_id, command_id, request_hash, applied_revision, created_at
```

`canvas_upload.id` 同时作为 finalize 后的 `canvas_resource.id`。Finalize 重试先查
Resource；已存在则直接返回，避免 PostgreSQL 与 MinIO 分布式事务。

## 4. API

```text
GET    /api/canvases
POST   /api/canvases
GET    /api/canvases/{canvasId}
POST   /api/canvases/{canvasId}/commands

GET    /api/canvas-function-models
POST   /api/canvases/{canvasId}/nodes/{nodeId}/runs
GET    /api/canvases/{canvasId}/nodes/{nodeId}/run
POST   /api/canvases/{canvasId}/nodes/{nodeId}/run/cancel

POST   /api/canvases/{canvasId}/uploads
POST   /api/canvases/{canvasId}/uploads/{uploadId}/complete

POST   /api/canvases/{canvasId}/resources/{resourceId}/download-url
POST   /api/canvases/{canvasId}/resources/{resourceId}/preview-url
```

Graph Commands 使用 JSON discriminator，不再使用嵌套 `commandsJson` 字符串：

```text
CREATE_RESOURCE_NODE
CREATE_FUNCTION_NODE
CREATE_TEXT_NODE
UPDATE_TEXT_NODE
UPDATE_FUNCTION
RENAME_NODE
MOVE_NODES
DELETE_NODE
CREATE_LINK
DELETE_LINK
CREATE_GROUP
MOVE_GROUP
UNGROUP
DELETE_GROUP
```

`graphRevision` 只由成功的用户 Graph Command batch 递增一次。

## 5. Storage and Media

服务端：

```text
kk-studio -> http://vps-s3:9000
kk-studio -> http://vps-opencli-hub:8080
```

浏览器：

```text
Browser -> https://s3.fengwk.fun
```

浏览器上传和下载只接收 kk-studio 下发的短期预签名 URL，不通过 kk-studio 或
`kk1.fun` 代理媒体字节。

上传流程：

```text
POST uploads
-> server-generated resource id/key + presigned PUT
-> browser direct PUT to s3.fengwk.fun
-> POST complete
-> server stream object from vps-s3
-> ffprobe/ffmpeg validate and create preview
-> insert immutable Resource
-> create or update ResourceNode
```

服务端媒体校验不相信浏览器的 MIME、宽高、时长、帧率或编码声明。ffmpeg/ffprobe
执行必须有输入大小上限、执行超时和临时目录清理。

Runtime image 安装 ffmpeg/ffprobe。

## 6. Function Models

### 6.1 Seedance 2.0

v1 模型：

```text
seedance2.0
seedance2.0fast
seedance2.0_vip
seedance2.0fast_vip
```

不暴露 `seedance2.0mini`。

参数：

- ratio：`1:1`、`3:4`、`16:9`、`4:3`、`9:16`、`21:9`；
- duration：4–15 秒整数；
- 图片、视频、音频引用；
- 总参考素材数不超过 12；
- 视频不超过 3 个，每个 2–15 秒，总时长不超过 15 秒；
- 音频不超过 3 个，每个 2–15 秒，总时长不超过 15 秒。

Adapter：

```text
MinIO Resource
-> stream multipart to vps-opencli-hub /api/resources/uploads
-> POST /api/opencli/execute jimeng-agent video
-> GET /api/executions/{id}
-> submitted 时保存 assetId
-> 间隔轮询 jimeng-agent status
-> ready 后读取 Hub execution resources
-> stream into MinIO Resource materializer
```

自动化测试只允许：

```text
model=seedance2.0fast
duration=4
submit=0
```

### 6.2 GPT Image 2

通过 `vps-opencli-hub` 的 `chatgpt-agent ask` 实现，不再使用旧 Base64
`GptImage2Service`。

参数：

- prompt 非空；
- ratio：`auto`、`1:1`、`3:4`、`9:16`、`4:3`、`16:9`；
- 仅接受图片引用。

Adapter 将参考图片流式上传到 Hub，执行 ChatGPT Agent，并将 Hub 收集的图片
流式导入 MinIO。v1 默认请求一张图片。

付费真实生成不进入自动化回归。

### 6.3 MiniMax-H3 Ref2VA

v1 仅支持本地开源 Ref2VA，固定 768P 档位，比例：

```text
21:9, 16:9, 4:3, 1:1, 3:4, 9:16
```

输出时长 4–15 秒整数。Adapter 内部按 24fps 与 `17k+5` 规则计算帧数。

参考素材规则：

- 图片：最多 9；
- 视频：最多 3；
- 音频：最多 3；
- 混合素材总数最多 12；
- 至少有一张图片或一个视频；
- 音频不能作为唯一参考模态；
- 视频每个 2–15 秒，总时长不超过 15 秒；
- 音频每个 2–15 秒，总时长不超过 15 秒。

应用层格式规则：

- 图片：JPG/JPEG/PNG/WEBP/HEIC/HEIF，≤30MB，宽高 256–5760，
  宽高比 0.4–2.5；
- 视频：MP4/MOV，H.264/H.265，内嵌音频 AAC/MP3，≤50MB，
  23.976–60fps，宽高 256–5760，宽高比 0.4–2.5；
- 音频：WAV/MP3，≤15MB。

运行顺序：

```text
冻结唯一有序 Resource manifest
-> Harness Prompt Agent 使用同一 manifest 生成 H3 prompt
-> 动态构造 ComfyUI workflow
-> 提交并轮询 ComfyUI job
-> 下载视频
-> Resource materializer
-> 原子替换 node Resource[]
```

Prompt Agent 与 ComfyUI workflow builder 必须共享同一份编号 manifest，避免
`<Picture N>`、`<Video N>`、`<Audio N>` 错位。

Prompt Agent 使用专用 Agent Definition，系统提示词基于：

- MiniMax-H3 Base Prompt Writing Guide；
- MiniMax-H3 Full Reference Prompt Writing Guide；
- `comfyui-minimax-h3-prompt-enhancer-T8` 中经过验证的核心规则。

Harness 需要新增 `VideoMessageContent`，并把 HTTP/runtime message contract 扩展为
结构化内容。已有 `ImageMessageContent`、`AudioMessageContent` 和
`ProviderVideoBlock` 继续复用。

H3 workflow 以官方 `minimax_h3_r2v_官流.json` 为事实源：

- 删除示例 Prompt 与固定素材节点；
- 保留模型加载、双 VAE、采样、解码、CreateVideo、SaveVideo；
- 保留 SageAttention、FirstBlockCache、Spectrum 优化链；
- 按本次 manifest 动态增加 LoadImage、LoadAudio；
- 参考视频使用 `LoadVideo -> GetVideoComponents`，视频帧接
  `ref_videos.ref_video_N`，音轨接同序号
  `ref_video_audios.ref_video_audio_N`；
- 保持 `ref_audios.ref_audio_N` 的独立编号；
- Prompt、比例、时长、seed 与 filename prefix 动态注入。

## 7. UI

选中 Function ResourceNode 后，在节点附近显示无标题、无模式 Tab 的输入面板：

```text
┌─────────────────────────────────────────────┐
│ [reference thumbnails, horizontal scroll]  │
│                                             │
│ prompt text with @ resource mentions       │
│                                             │
│ [model] [ratio] [duration] ...      [send] │
└─────────────────────────────────────────────┘
```

- 第一行展示所有 incoming Link 提供、且当前模型可接受的 Resource；
- 缩略图点击或输入 `@` 均可插入结构化引用；
- 生成节点内禁止上传，上传必须先创建 ResourceNode 再连线；
- 切换模型后按模型能力过滤引用并显示相应参数；
- 后续视频编辑、延长、首尾帧等能力通过新的 Function Node 拍平，不在面板增加模式
  Tabs。

节点 Renderer 只保留：

```text
ResourceNodeRenderer
GroupRenderer
```

ResourceNodeRenderer 按当前 Resource 或 model 描述渲染图片、视频、音频、文本。
节点内最多展示前四个缩略图和 `+N`，避免大批资源撑大 DOM。

## 8. Performance

- Canvas snapshot 不包含媒体字节、Base64 或长期 URL；
- snapshot 只返回 Resource metadata 和是否有 preview；
- preview/original URL 按需预签并由 React Query 缓存；
- 图片使用 `loading=lazy` 与 `decoding=async`；
- 视频节点首屏只加载 WebP 封面，用户显式播放时才签名并加载原视频；
- React Flow 启用 `onlyRenderVisibleElements`；
- Node Renderer 使用稳定 props 与 `memo`；
- MiniMap 只用纯色节点；
- 上传进度保存在本地，不持续改写 React Flow nodes；
- 参考素材条使用横向滚动和可见项渲染；
- URL 到期前刷新，组件卸载时释放 object URL；
- Markdown 禁止原始 HTML，Mermaid/代码块沿用现有安全 Renderer。

## 9. Verification

后端：

- Domain invariant tests；
- PostgreSQL repository/service integration tests；
- command CAS/dedup/finalize idempotency tests；
- FunctionRun 幂等、迟到结果、失败保留旧资源、成功原子替换测试；
- MinIO 使用 mock/S3-compatible test double；
- ffprobe/ffmpeg fixture tests；
- OpenCLI Hub client contract tests；
- ComfyUI dynamic workflow snapshot tests；
- Harness VideoMessageContent codec/projector/provider tests。

前端：

- snapshot/command client；
- projection 与 visible rendering；
- ResourceNode 四种渲染；
- Group；
- reference candidate/filter/mention；
- Function panel model parameters；
- upload progress/finalize；
- run polling、失败/重试；
- 页面刷新后持久化恢复。

集成：

- 默认 E2E 使用 fake Function model；
- Seedance 可选 smoke 仅 `seedance2.0fast + 4s + submit=0`；
- GPT Image 2 与 Seedance 正式提交必须显式人工开关；
- MiniMax-H3 真实远端提交不进入默认回归。
