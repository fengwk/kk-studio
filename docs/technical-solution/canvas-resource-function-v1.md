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

`configJson` 是唯一严格用户配置契约：

```json
{
  "prompt": {
    "segments": [
      {"type": "TEXT", "text": "..."},
      {"type": "REFERENCE", "nodeId": "123", "index": 0}
    ]
  },
  "parameters": {"ratio": "16:9", "duration": 5}
}
```

所有对象层拒绝未知字段、`null`、错误类型和重复字段。`type` 只使用大写
`TEXT|REFERENCE`；引用 nodeId 是 canonical positive decimal string，index 是非负整数。
canonical freeze 删除空 TEXT、合并相邻 TEXT；最终可见文本必须非空。manifest 按
REFERENCE 首次出现顺序去重，但 prompt 中可重复 mention 同一引用。`parameters` 必须是
object，v1 只由 model descriptor 声明并校验 `ENUM|INTEGER` 参数及 required/default/
options/min/max。Create/Update Function command 必须命中 Registry 中的 model descriptor，
并在写库前完成上述校验、默认值补齐和 canonical JSON 编码；start 时再次按当前 descriptor
校验后冻结。

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

`stateJson` 是 versioned typed crash-recovery checkpoint，保存：

- 冻结后的 model identity/output kind/config；
- 冻结后的有序 Resource manifest；
- 当前 adapter stage；
- provider execution/job/thread id；
- 已预分配的目标 Resource id。

固定外层为 `version + plan + stage + adapterState object`。adapterState 最大 64KiB，
stage 使用大写 token。公开 `CanvasFunctionRunDTO` 只返回
`nodeId/requestId/status/stage/error/updatedAt`，不返回 stateJson。

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

start 在短事务中先锁 Function node，再锁当前 run。相同 requestId 无论 RUNNING 或终态
都 exact replay；不同 requestId 遇 RUNNING 冲突；终态可被新 request 覆盖。checkpoint
和 terminal transition 都使用 `nodeId + requestId + RUNNING` 条件更新。成功只在所有目标
Resource 已通过 materializer 后，重新锁 node/run，并在同一短事务中原子替换完整有序
Resource 列表与标记 SUCCEEDED。cancel 先提交数据库 CANCELLED，再 best-effort 调用
adapter hook。

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
  id, canvas_id, name, name_normalized, x, y, width, height, group_id,
  model_key, function_config_json

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

Upload reserve 请求 `{kind, filename, mediaType, size}`，其中 `size` 与所有 bigint id
一样使用规范十进制字符串。响应只返回
`{uploadId, method, url, headers, expiresAt}`；Resource URL 响应只返回
`{method, url, headers, expiresAt}`。两类 Canvas DTO 都不暴露 bucket/key，客户端也
不能提交对象 key。

Graph Commands 直接使用 JSON discriminator typed commands 数组：

```text
CREATE_RESOURCE_NODE
CREATE_FUNCTION_NODE
CREATE_TEXT_NODE
UPDATE_TEXT_NODE
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
```

`graphRevision` 只由成功的用户 Graph Command batch 递增一次。
聚合 snapshot 在读取前后同时核对 `graphRevision` 与有序 FunctionRun 列表；任一事实在
多查询装配期间变化就重读，避免返回终态 Run 与另一代 Resource/Graph 的混合结果。

`UNGROUP` 携带 `groupId + memberNodeIds`，只解除指定成员；`DELETE_GROUP`
先解除全部成员再删除 Group。

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
-> server-generated resource id/key + create-only presigned PUT
-> browser direct PUT to s3.fengwk.fun
-> POST complete
-> server stream object from vps-s3
-> ffprobe/ffmpeg validate and create preview
-> insert immutable Resource
-> create or update ResourceNode
```

Canvas reserve 返回的 PUT headers 必须包含签名覆盖的 `If-None-Match: *`，浏览器
必须原样发送。首次 PUT 创建 original 后，同一预签名 URL 在有效期内再次 PUT 会由
S3/MinIO 拒绝，避免已 finalize Resource 的 original 被覆盖，并保证并发 finalize
读取的原件与最终 preview/metadata 一致。这一条件写入是 Resource immutable 的对象
存储边界；部署时 bucket CORS 必须允许 `If-None-Match` 请求头。

服务端媒体校验不相信浏览器的 MIME、宽高、时长、帧率或编码声明。ffmpeg/ffprobe
执行必须有输入大小上限、执行超时和临时目录清理。

Runtime image 安装 ffmpeg/ffprobe。

运行时配置：

```yaml
kk-studio:
  storage:
    s3:
      endpoint: http://vps-s3:9000
      public-endpoint: https://s3.fengwk.fun
  canvas:
    resource:
      ffprobe-binary: ffprobe
      ffmpeg-binary: ffmpeg
      process-timeout: 30s
      thumbnail-max-dimension: 512
      thumbnail-quality: 80
      temp-dir: /tmp
      upload-expiry: 15m
```

Upload reserve 只接受 `IMAGE`、`VIDEO`、`AUDIO`，并在签名前校验文件扩展名与声明
大小。Finalize 以 S3 HEAD/实际流长度和 ffprobe 结果为准：图片最多 30MiB
（JPG/JPEG/PNG/WEBP/HEIC/HEIF），视频最多 50MiB（MP4/MOV），音频最多
15MiB（WAV/MP3）。图片和视频 preview 使用 ffmpeg 生成最长边不超过 512px 的
WebP；任何超时、非零退出、非法数值或输出缺失都拒绝入库。整个过程中只使用有界
buffer 和临时文件，不把媒体整体加载进 JVM heap。

## 6. Function Models

Registry 由 `CanvasFunctionAdapter` 声明一个或多个 `CanvasFunctionModel`。descriptor 只含
key、label、output kind、reference policy 与参数定义；availability 由 adapter enabled
状态决定，provider endpoint/workflow 不进入 share DTO。重复 model key 在应用启动时失败。

Foundation 向 adapter 只暴露 checkpoint、isRunning、打开 frozen Resource original 的
must-close 流，以及向唯一预分配 target id 物化输出。original 使用 deterministic S3 key
并校验对象长度等于 frozen size；输出统一复用 `CanvasResourceMaterializer` 的
ffprobe/ffmpeg/preview 路径。

异步 dispatcher 使用可配置有界 executor，并在本进程按 nodeId/requestId 去重；新建或
重复 RUNNING start 都可补 dispatch，ApplicationReady 扫描 durable RUNNING 恢复。adapter
根据 typed stage/adapterState 自行决定供应商不确定阶段的恢复行为，foundation 不自动
重试付费提交。

只有同时启用 S3 与 `kk-studio.canvas.function.fake-enabled=true` 时才注册免费
`fake-image`/`fake-video`。两者从 main resources 读取极小合法 PNG/MP4，并仍经过同一
materializer、ffprobe 与 preview 路径；普通环境不暴露 fake models。

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

冻结 manifest 按首次引用顺序逐个上传，checkpoint 中保存
`uploads=[{resourceId,resourcePath}]`；图片、视频、音频分别从 1 编号，结构化 prompt
渲染为 `@图片N`、`@视频N`、`@音频N`。视频/音频 metadata 在提交前再次严格校验：
单条 2–15 秒、各自总时长不超过 15 秒；视频只允许 MP4/MOV、h264/hevc，内嵌音轨
只允许 aac/mp3。

首次付费提交前写 `SEEDANCE_SUBMITTING`。恢复时若该 stage 没有 Hub execution id 或
assetId，则提交结果不可判定，Run 必须失败且禁止自动重提。提交 stdout 只接受单 object
或单元素 array；Hub 统一注入 JSON 格式与 managed output 参数，调用方不得传递
`--format`、`-f`、`--profile` 或 `--op`。提交结果要求 `submitted=true`、
`status=submitted` 与 16 位小写 hex assetId。
后续 status 查询是无付费调用；每次 execution id 都 checkpoint，查询之间按配置间隔等待，
`generating/not_found` 继续，`failed/cancelled` 终止，`ready+downloaded` 必须恰有一个
`video/*` Hub Resource。

自动化测试只允许：

```text
model=seedance2.0fast
duration=4
submit=0
```

### 6.2 GPT Image 2

通过 `vps-opencli-hub` 的 `chatgpt-agent ask` 实现，不再使用旧 Base64 图片 API。

参数：

- prompt 非空；
- ratio：`auto`、`1:1`、`3:4`、`9:16`、`4:3`、`16:9`；
- 仅接受图片引用。

Adapter 将参考图片流式上传到 Hub，执行 ChatGPT Agent，并将 Hub 收集的图片
流式导入 MinIO。v1 默认请求一张图片。

图片引用最多 20 个且每个不超过 20MiB。附件顺序与 manifest 一致，prompt 中的结构化
引用渲染为 `Reference image N`，零上下文指令明确要求 GPT Image 2、指定比例、恰好一张
可下载图片 artifact。调用 `chatgpt-agent ask` 时不传 Hub 托管的 `--op`。

首次付费提交前写 `GPT_IMAGE_SUBMITTING`。恢复时若该 stage 没有 Hub execution id，
提交结果不可判定，Run 必须失败且禁止自动重提。成功 execution 只接受恰好一个
`image/*` Hub Resource，并通过 must-close 流交给统一 Resource materializer。

付费真实生成不进入自动化回归。

### 6.3 OpenCLI Hub client and configuration

OpenCLI Hub client 只使用 JDK `HttpClient`，redirect 固定为 `NEVER`。base URL 必须是纯
HTTP(S) origin，不允许 userinfo、path、query 或 fragment。上传固定使用单文件 multipart
part `files` 与已知 `Content-Length` 的流式 BodyPublisher；JSON response、错误摘要与
stdout/stderr 都有本地小型上限，媒体不进入 JVM heap。Hub Resource URL 只允许配置 origin
下的 `/api/resources/`，下载响应必须提供正 `Content-Length`。

默认配置全部禁用真实提交：

```yaml
kk-studio:
  opencli-hub:
    enabled: false
    base-url: http://vps-opencli-hub:8080
    instance-id: null
    connect-timeout: 5s
    request-timeout: 2m
    long-poll-timeout: 130s
    stream-buffer-bytes: 16384
    max-json-response-bytes: 524288
    max-error-response-bytes: 4096
    max-output-chars: 65535
  canvas:
    function:
      gpt-image-2:
        paid-enabled: false
        ask-timeout-seconds: 900
        hub-execution-timeout: 16m
        max-wait: 20m
      seedance:
        enabled: false
        workspace-id: null
        retry: 0
        hub-execution-timeout: 10m
        status-poll-interval: 30s
        max-wait: 30m
```

即使 provider 开关关闭，五个真实模型仍进入 Registry，但 `available=false`，公开 reason
只说明功能未启用，不暴露 workspace、instance 或其他内部配置值。Function `configJson`
仍只包含公开的 ratio/duration 和结构化 prompt，所有 endpoint、workspace、Hub path 与
execution/asset id 只存在服务端配置或私有 checkpoint。

### 6.4 MiniMax-H3 Ref2VA

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

H3 Adapter 默认关闭，但模型仍进入 registry 并以 unavailable 状态返回。启用配置前缀为
`kk-studio.canvas.function.minimax-h3`。启用时必须提供 Prompt Agent 名称、Prompt
Environment 名称和 ComfyUI origin；Prompt Thread 强制 `activeTools=[]`。Foundation 只向
Adapter 暴露冻结 manifest 成员的 original 短期 presign，且仅在 Run 仍为 RUNNING 时允许
签名，Adapter 不直接依赖 Canvas mapper 或 S3 key 细节。

执行 checkpoint 固定为：

```text
H3_INITIALIZED
-> H3_PROMPT_SUBMITTING
-> H3_PROMPT_WAITING
-> H3_PROMPT_READY
-> H3_COMFY_UPLOADING
-> H3_COMFY_SUBMITTING
-> H3_COMFY_WAITING
-> H3_COMFY_READY
-> H3_COMPLETE
```

`H3_PROMPT_SUBMITTING` 没有 durable `harnessThreadId`、或
`H3_COMFY_SUBMITTING` 没有 durable `promptId` 时，恢复必须拒绝自动重提，避免外部作业
重复创建。每个素材上传成功后立即在 `H3_COMFY_UPLOADING` 保存 upload descriptor，恢复
时复用。取消先 best-effort stop Harness Thread，再只删除仍处于 ComfyUI pending queue
中的 prompt；不调用全局 `/interrupt`。

ComfyUI 客户端只接受无 path/query/fragment/userinfo 的 HTTP(S) origin，禁止 redirect，
JSON 请求和响应均有大小上限。素材通过固定长度 multipart 流上传到
`kk-studio/{canvasId}`；结果只接受 `outputs."92".videos[]` 恰好一个 descriptor，并要求
`/view` 返回正 `Content-Length` 后流式导入 Resource materializer。

手工 H3 smoke 属于付费/高成本显式验证，不进入自动回归，也不得在常规 CI 中运行：

1. 准备隔离的 Prompt Agent/Environment，确认 Agent 可处理 IMAGE/VIDEO/AUDIO
   structured message，且 smoke 期间不配置工具。
2. 准备测试专用 ComfyUI，安装官方 H3 workflow 所需节点与模型；先只读检查
   `/object_info` 和 `/system_stats`。
3. 设置 `KK_STUDIO_CANVAS_H3_ENABLED=true`、Prompt Agent/Environment、ComfyUI origin
   和可选 Bearer；使用测试 S3 bucket，启动 backend。
4. 创建带最小合法图片引用的 Function Node，选择 `minimax-h3-ref2va`、`ratio=16:9`、
   `duration=4`，只提交一次。
5. 观察 checkpoint 依次进入 Prompt waiting、Comfy uploading/waiting、ready/complete；
   验证 Prompt Thread 的 `activeTools` 为空、structured USER 标签与附件编号一致。
6. 验证 ComfyUI upload subfolder、动态 workflow 的 node 136/115/132/129/92、输出视频
   Resource 和 preview；停止另一个 pending Run，确认只删除对应 pending prompt。
7. smoke 完成后立即关闭 H3 开关并清理测试素材、Thread、ComfyUI output 和对象存储。

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

隔离容器 smoke 使用内置 fake Hub 完整执行 GPT Image 与 Seedance adapter，再把极小
PNG/MP4 流式 materialize 到 MinIO；该环境虽打开 provider 开关，但 Hub origin 固定为
容器内 mock，不会访问真实站点。真实 Seedance prepare-only smoke 由
`scripts/seedance-prepare-smoke.sh --confirm-prepare-only` 直接调用 Hub，另要求
`RUN_REAL_SEEDANCE_PREPARE_SMOKE=1`，硬编码 `seedance2.0fast + 4s + submit=0`，不创建
FunctionRun，也不导入视频。
