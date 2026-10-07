# Share 模块

`share` 集中定义后端 API 的 JSON 形状：请求体、响应体、枚举、sealed union 与 Jackson 注解。客户端与 HTTP mapper 依赖这些字段名、可空性和游标类型。生产依赖为 `jackson-annotations` 与 `jackson-databind`（见 [`share/pom.xml`](../../share/pom.xml)），契约测试可独立运行。

DTO 表达 HTTP 请求与投影。领域到 DTO 的转换在 [Web](web.md) 的 [`WebDtoMapper`](../../web/src/main/java/fun/fengwk/kkstudio/web/mapper/WebDtoMapper.java)，version CAS、幂等、引用计数与调度由 [Platform](platform.md)、[Canvas Core](canvas-core.md)、[Canvas Infra](canvas-infra.md) 与 [Project](project.md) 维护。

## 严格 JSON 边界

需要严格解码的 DTO 通过自己的 `@JsonAnySetter` 拒绝未知字段，不依赖全局 Jackson 默认：请求 DTO 的 `rejectUnknownField` 抛 `IllegalArgumentException("unknown field: <字段名>")`，Project 等响应 DTO 抛 `Unknown response field`。纯响应投影如 Canvas Snapshot 不重复声明该检查，具体区分见下文。多态 command 由 Jackson 的 `type` discriminator 解码：

```json
{ "type": "CREATE_NODE", "nodeId": "...", "name": "说明", "transform": { "x": 0, "y": 0, "width": 200, "height": 100 }, "function": null, "resources": [{ "kind": "TEXT", "name": "说明.txt", "textContent": "内容" }] }
```

[`CanvasCommandDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/canvas/CanvasCommandDTO.java) 的 11 个子类型与 [canvas-core](canvas-core.md) 的 `CanvasCommand` 一一对应：`CREATE_NODE`、`RENAME_NODE`、`SET_NODE_RESOURCES`、`SET_NODE_FUNCTION`、`SET_NODE_GROUP`、`DELETE_NODE`、`UPDATE_NODE_TRANSFORM`、`CREATE_GROUP`、`RENAME_GROUP`、`UPDATE_GROUP_TRANSFORM`、`DELETE_GROUP`。每条命令只修改一个语义组，前置条件携带编辑起点的旧值（旧名称、旧资源 id 列表、旧 Function `{name,args}` 或旧几何基线）。Patch 用 `op` discriminator 表达 `UPSERT` / `REMOVE`，节点与分组实体各自独立，整图只前进一个被接受的 `revision`。

字符串到 UUID 与数字的解析属于 HTTP 边界：[`WebDtoMapper.parseUuid`](../../web/src/main/java/fun/fengwk/kkstudio/web/mapper/WebDtoMapper.java) 与 [`ProjectDtoMapper.parseUuid`](../../web/src/main/java/fun/fengwk/kkstudio/web/project/ProjectDtoMapper.java) 要求 canonical UUID 文本（`UUID.toString()` 的往返必须一致），[`HarnessRuntimeRequestMapper`](../../web/src/main/java/fun/fengwk/kkstudio/web/runtime/HarnessRuntimeRequestMapper.java) 与 Project 的 `parseNonNegativeLong` 只接受 canonical decimal（`0|[1-9]\d*`，向上另有 `[1-9]\d*` 的正数形态）且必须能放进 `long`；共享层只承载解析后的值，整数形态的实体 id 在 wire 上永远不出现。

durable 游标在 wire 上保持十进字符串：Canvas 的 revision、Harness Thread 的 sequence、Project/Issue 的 version、nextIssueNumber、number、sequence、nextActivityCursor、ordinal、budgetAfterOrdinal、usedRuns、remainingRuns、observedActivitySequence、remainingExecutionMs 及请求 expectedVersion 均为 String，以保持 JS 端整数精度。[`CanvasDtoContractTest`](../../share/src/test/java/fun/fengwk/kkstudio/share/canvas/CanvasDtoContractTest.java) 与 [`ProjectDtoContractTest`](../../share/src/test/java/fun/fengwk/kkstudio/share/project/ProjectDtoContractTest.java) 反射验证字段类型。

## 可空字段与安全输出

Web 的 HTTP mapper 全局默认是 `NON_NULL` 与 `STRICT_DUPLICATE_DETECTION`（见 [web](web.md) 的 `StrictJacksonConfiguration`），因此「字段缺席」在默认情况下等于「null」。但有些字段缺席会被客户端误读——`blobId` 与 `textContent` 恰好互斥，一个是 blob 资源，一个是文本资源；`function`/`run` 为 null 表示这是普通资源节点或尚未运行过。这类字段显式声明 `@JsonInclude(ALWAYS)`，保证即使值为 null 也出现在 JSON 里：

| DTO | required-nullable 字段 |
| --- | --- |
| [`CanvasResourceNodeDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/canvas/CanvasResourceNodeDTO.java) | `groupId`、`function`、`run` |
| [`CanvasResourceDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/canvas/CanvasResourceDTO.java) | `blobId`、`textContent`、`mediaType`、`sizeBytes`、`width`、`height`、`durationMs` |
| [`CanvasFunctionRunDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/canvas/CanvasFunctionRunDTO.java) | `error` |
| [`CanvasFunctionDefinitionDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/canvas/CanvasFunctionDefinitionDTO.java) | `unavailableReason` |
| [`StorageUploadDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/storage/StorageUploadDTO.java) | `blobId`、`presignedPut` |
| [`StoragePresignedUrlDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/storage/StoragePresignedUrlDTO.java) | `mediaType`、`sizeBytes` |
| [`ProjectDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/project/ProjectDTO.java) | `archivedAt` |
| [`ProjectWorkflowStateDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/project/ProjectWorkflowStateDTO.java) | `agent`、`environment`、`instructions`、`maxRuns` |
| [`ProjectIssueSnapshotDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/project/ProjectIssueSnapshotDTO.java) | `currentOrLatestRun` |
| [`IssueDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/project/IssueDTO.java) | `blockedFromState`、`blockReason`、`pauseReason`、`pauseDetail`、`archivedAt` |
| [`IssueDetailDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/project/IssueDetailDTO.java) | `nextActivityCursor`、`currentRun`、`latestRun` |
| [`IssueActivityDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/project/IssueActivityDTO.java) | `actorAgentName`、`runId`、`body` |
| [`IssueRunDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/project/IssueRunDTO.java) | `agentName`、`endEntryId`、`finalAnswerEntryId`、`nextState`、`error`、`endedAt` |
| [`IssueRunSummaryDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/project/IssueRunSummaryDTO.java) | `agentName`、`endedAt` |
| [`IssueEvidenceDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/project/IssueEvidenceDTO.java) | `actorAgentName`、`runId` |
| [`HarnessThreadDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/ai/runtime/HarnessThreadDTO.java) | `parentThreadId` |
| [`HarnessThreadSummaryDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/ai/runtime/HarnessThreadSummaryDTO.java) | `parentThreadId`、`headMessagePreview` |
| [`HarnessThreadSnapshotDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/ai/runtime/HarnessThreadSnapshotDTO.java) | `modelInvocation` |
| [`ToolInvocationDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/ai/runtime/ToolInvocationDTO.java) | `environmentId`、`requiredEnvironmentId`、`requiredEnvironmentName`、`environmentWaitFreshnessAt`、`approvalJson`、`resultJson`、`errorJson` |
| [`HarnessStoppedThreadReceiptDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/ai/runtime/HarnessStoppedThreadReceiptDTO.java) | `stoppedTurnEndEntryId` |
| [`HarnessSessionEntryDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/ai/runtime/HarnessSessionEntryDTO.java) | `usageCost` |
| [`EnvironmentCardDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/ai/environment/EnvironmentCardDTO.java) | `statusExpiresAt` |

请求 DTO 同受这条规则约束：[`CreateProjectRequestDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/project/CreateProjectRequestDTO.java) 的 `description`/`yoloEnabled`、[`UpdateProjectRequestDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/project/UpdateProjectRequestDTO.java) 的 `title`/`description`、[`CreateIssueRequestDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/project/CreateIssueRequestDTO.java) 与 [`UpdateIssueRequestDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/project/UpdateIssueRequestDTO.java) 的可空字段、[`PauseIssueRequestDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/project/PauseIssueRequestDTO.java)/[`StopIssueRequestDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/project/StopIssueRequestDTO.java) 的 `detail`、[`ResolveUnknownIssueRequestDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/project/ResolveUnknownIssueRequestDTO.java) 的 `verification` 与 [`AppendIssueActivityRequestDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/project/AppendIssueActivityRequestDTO.java) 的 `kind` 都用 `@JsonInclude(ALWAYS)` 显式发 null。

Storage DTO 不提供独立的 S3 bucket 与对象 key 配置字段，只给 `url`、`method`、必须原样回传的 `headers` 与 `expiresAt`，Canvas 的预签名端点同理；客户端按签发 URL 访问，不自行拼接存储地址。凭证类字段走 `WRITE_ONLY`（[`AgentProviderEditablePropertiesDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/ai/catalog/AgentProviderEditablePropertiesDTO.java) 的 `credential`），只写不读；[`EnvironmentRegistrationTokenDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/ai/environment/EnvironmentRegistrationTokenDTO.java) 仅用于创建、显式读取与轮换令牌的响应，不进通用 Card 投影，响应禁止缓存。

[`SystemSettingsDtoContractTest.java`](../../share/src/test/java/fun/fengwk/kkstudio/share/systemsettings/SystemSettingsDtoContractTest.java) 用反射扫描 `systemsettings` DTO 的字段名，拒绝 secret / bootstrap 名称（`token`、`credential`、`secret`、`apikey`、`accesskey`、`secretkey`、`bearer`、`instanceId`、`endpoint`、`region`、`bucket`、`workdir`、`environmentRoot`、`tempDir`、`binary`、`prefix`、`username`、`password`）；`compactionKeepRecentTokens` 是预算计数，显式豁免。

Plugin 投影遵循同一边界：`PluginDTO` 只有安装元数据、region、状态、到期/刷新时间和有界错误；[`PluginAuthCompleteRequestDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/ai/plugin/PluginAuthCompleteRequestDTO.java) 的 `callbackUrl` 是 `WRITE_ONLY`，不出现在任何响应、`toString` 或通用日志。`PluginAuthPrepareDTO.loginUrl` 只能是 Plugin 声明的固定公开 origin。认证交互只允许 sealed `DEEP_LINK` 类型及固定的 `{region}` / `{callbackUrl}` DTO，不承载任意 Plugin schema；所有 auth 响应由 Web 额外设置 `Cache-Control: no-store`。

## DTO 组织

源码按公共领域分包，包名就是客户端的领域划分：

| 包 | 当前 wire |
| --- | --- |
| [ai.catalog](../../share/src/main/java/fun/fengwk/kkstudio/share/ai/catalog/) | Provider、Model、Agent、ModelRef、Tool catalog |
| [ai.skill](../../share/src/main/java/fun/fengwk/kkstudio/share/ai/skill/) | Git Skill Package、branch 更新检查、exact commit 发布与派生 Skill 列表 |
| [ai.mcp](../../share/src/main/java/fun/fengwk/kkstudio/share/ai/mcp/) | MCP Server 安全投影、显式配置 DTO 与显式 HTTP 创建/更新请求 |
| [ai.plugin](../../share/src/main/java/fun/fengwk/kkstudio/share/ai/plugin/) | 已安装构建期 Plugin、安全认证状态、续期运维投影与 prepare/complete 请求和响应 |
| [ai.chat](../../share/src/main/java/fun/fengwk/kkstudio/share/ai/chat/) | Chat 的创建、更新与投影 |
| [ai.environment](../../share/src/main/java/fun/fengwk/kkstudio/share/ai/environment/) | Environment Card CRUD、registration token、保存的安装设置（`installConfig` 与共享 Daemon 配置模型）、最近一次 READY 的 OS/user/HOME 投影与有界运维事件 |
| [ai.interaction](../../share/src/main/java/fun/fengwk/kkstudio/share/ai/interaction/) | 统一交互（问卷等待与工具审批等待）DTO、交互 owner、分页与人工输入提交回执 |
| [ai.runtime](../../share/src/main/java/fun/fengwk/kkstudio/share/ai/runtime/) | Session、Entry 与读取时 `usageCost`、Thread Snapshot、Command batch（创建型与 owner-free Thread 续写）、请求预览（草稿与历史）、Invocation、approval、Stop 回执、compaction |
| [canvas](../../share/src/main/java/fun/fengwk/kkstudio/share/canvas/) | Canvas document、Snapshot、Patch、typed command、Resource 输入、Function 定义/运行/未决决议、冲突与引用投影 |
| [project](../../share/src/main/java/fun/fengwk/kkstudio/share/project/) | Project/Issue Snapshot、Issue 详情、工作流配置、阶段预算、Agent Thread、Run、时间线 Activity、公开 Evidence 与操作请求 |
| [storage](../../share/src/main/java/fun/fengwk/kkstudio/share/storage/) | Upload、Blob signed URL、S3 presign |
| [systemsettings](../../share/src/main/java/fun/fengwk/kkstudio/share/systemsettings/) | 七个 settings section、schema 与 update request |
| [configsync](../../share/src/main/java/fun/fengwk/kkstudio/share/configsync/) | 七类配置引用、依赖清单、YAML 导入/导出与跳过原因 |

只有出现在 HTTP 边界上的值才进 DTO。Canvas Snapshot/Patch 把 document revision、实体 UPSERT/REMOVE、Function Run 投影与派生引用组成前端可渲染的聚合；Harness Snapshot 包含 root-to-head entries（每条带读取时 `usageCost`；金额是精确十进制字符串，无法计价显式为 null）、queued commands、活跃 invocation、tool siblings、未物化的 attempt failure 与该 Thread 自己的 Stop 回执；Project Snapshot 包含项目资料、未归档 Issue 与当前/最近 Run 摘要；Issue Detail 组合 Issue 资料、当前 Run、最新 Run、阶段预算、Agent 绑定 Thread、活动时间线与已发布证据。Settings DTO 包含完整七 section 与 `expectedVersion`，[`SystemSettingsSchemaDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/systemsettings/SystemSettingsSchemaDTO.java) 提供 UI 的 ordered sections/groups/fields。

Thread Debug 使用结构化 `HarnessModelRequestDebugDTO`，携带下一次请求预览、候选 Tool 的发送/过滤状态、Skill 稳定路径与可空的活动 frozen request；完整 schema/request JSON 按字符串展示，secret 与 Base64 正文在输出边界去除。

工具行的环境等待是批量读取时投影，不是 Invocation 状态：`requiredEnvironmentId` 来自
Work 冻结路由，`requiredEnvironmentName` 沿此身份读取环境目录，不取 Thread 当前选择。
`waitingForEnvironment` 表达读取时是否待环境；`environmentWaitFreshnessAt` 是该 READY
环境调用未来有效 READY 连接租约、Work 可领取时间、执行租约的最小边界。
非 READY、server-side 或没有未来边界时为 null；日期沿用 HTTP mapper 的既有 Instant
语义。这些字段可在同一 Thread version 下因时间推移而变化，重新 GET 不产生持久写入。

`ProjectSnapshotDTO.referencedStateCodes` 是非 null 的去重、有序阶段集合，
包含活动及归档 Issue 的 `state` 与非 null `blockedFromState`。引用集合不改变正常 Issue 列表，
供工作流编辑保护删除与改码；保存仍由领域约束和版本 CAS 最终裁决。

严格程度按用途区分：请求体与对外投影显式拒绝未知字段，纯响应投影（如 `CanvasSnapshotDTO`）与内部嵌套结构不需要重复声明。领域身份使用 sealed interface / record 表达封闭集合（命令、patch 项、资源输入、冲突载荷），普通数据用 Lombok `@Data`。

## 不变量

- 未知字段、重复 JSON 键、错误的 union `type`/`op`、非 canonical UUID 或非规范数字都在 wire 边界失败，不会进入领域服务。
- required-nullable 字段即使为 `null` 也必须序列化，避免客户端把「未返回」当成「无值」。
- durable 版本与序号在 wire 上永远是十进字符串；`ModelRef` 是 `providerName/modelName`，只在第一个 `/` 处分割，因此模型名本身可以包含斜杠而 provider 名不能。
- Tool catalog 与 Debug 使用同一个 `environmentSupport = NONE | OPTIONAL | REQUIRED` 枚举，不用两个布尔字段组合出非法状态；Skill 引用精确为 `{packageName, name}`。
- Canvas 连线与引用从 Function args 派生为只读投影；CanvasSnapshotDTO 包含 document、nodes、groups、references，CanvasPatchDTO 携带被接受的 revision 及节点/分组变化。
- 命令 batch 只表达已解析的边界值；集合是否可变由具体 DTO 与调用方契约决定。创建型 batch 携带 owner 与 target，owner-free 续写面以 path `threadId` + 精确 cursor 定位且不携带二者。
- 费用不是 durable wire：Entry 的 `payloadJson` 与 `assistantMetadata` 只保存真实用量（`stopReason` / `usage` / 可选 `decodeDurationMillis`），不保存金额或单价；`usageCost` 在读取时按当前 catalog 现算，未计价显式为 null。
- Stop 回执以 `(threadId, stopRequestId)` 为身份并逐 Thread 输出；可恢复人工输入只含 `USER_MESSAGE` / `GOAL`。取消的 `CUSTOM_MESSAGE` 与配置命令只计入取消数量；`NOTIFICATION` 保留为历史，不计入取消数量。
- 常规 DTO 不回显 credential、secret 或对象存储内部标识；配置同步的显式 YAML 导出包含所需凭据，响应禁止缓存。Blob URL 由服务端按请求重新签发，断连或过期后客户端重新读取 Snapshot/URL。

## 从哪里改

- 改 Canvas wire：[share/canvas/](../../share/src/main/java/fun/fengwk/kkstudio/share/canvas/)，同时改 [core 的 `CanvasCommand`](canvas-core.md)（若命令集合变化）、[`WebDtoMapper`](../../web/src/main/java/fun/fengwk/kkstudio/web/mapper/WebDtoMapper.java) 的映射 switch（编译器会强制穷尽）与 [frontend](frontend.md) 的 [shared/api/contracts](../../frontend/src/shared/api/contracts) 类型。
- 改 Project wire：[share/project/](../../share/src/main/java/fun/fengwk/kkstudio/share/project/)，同时同步 Web Controller、`ProjectDtoMapper` 与前端 [features/projects/types.ts](../../frontend/src/features/projects/types.ts) 及 codec。
- 改某个领域的输出去敏或字段可见性：先在对应 DTO 上加注解，再补该领域的 `*DtoContractTest`；`SystemSettingsDtoContractTest`、`CanvasDtoContractTest` 与 `ProjectDtoContractTest` 的字段清单是这类改动的直接守卫。
- 判断一个字段该不该进 share：它是否出现在 HTTP 请求/响应上。领域内部的值、数据库列、S3 key 一律不进。

测试入口（全部是纯 Jackson/反射单元测试，不需要 Spring 或 PostgreSQL），测试目录按上面的 DTO 包组织；`ai/interaction` 不单独建包级契约测试，其 wire 由 platform 的交互服务测试与 web 的 Controller 测试共同覆盖：

| 领域 | 测试目录 | 代表测试 |
| --- | --- | --- |
| Canvas | [canvas/](../../share/src/test/java/fun/fengwk/kkstudio/share/canvas/) | [`CanvasDtoContractTest.java`](../../share/src/test/java/fun/fengwk/kkstudio/share/canvas/CanvasDtoContractTest.java)：十进 revision、required-nullable 发射、独立 document 字段、派生引用投影，以及与 Core 一致的 11 个 typed command |
| Harness Runtime | [ai/runtime/](../../share/src/test/java/fun/fengwk/kkstudio/share/ai/runtime/) | [`HarnessRuntimeDtoContractTest.java`](../../share/src/test/java/fun/fengwk/kkstudio/share/ai/runtime/HarnessRuntimeDtoContractTest.java)：严格字段、字符串游标与 nullable 发射 |
| Project | [project/](../../share/src/test/java/fun/fengwk/kkstudio/share/project/) | [`ProjectDtoContractTest.java`](../../share/src/test/java/fun/fengwk/kkstudio/share/project/ProjectDtoContractTest.java)：Project/Issue wire 的严格字段、decimal version 与全包 33 个 DTO 字段集合精确匹配 |
| Catalog | [ai/catalog/](../../share/src/test/java/fun/fengwk/kkstudio/share/ai/catalog/) | [`ModelRefTest.java`](../../share/src/test/java/fun/fengwk/kkstudio/share/ai/catalog/ModelRefTest.java)：canonical 身份解析；[`ToolCatalogDtoContractTest.java`](../../share/src/test/java/fun/fengwk/kkstudio/share/ai/catalog/ToolCatalogDtoContractTest.java)：工具目录契约 |
| Storage | [storage/](../../share/src/test/java/fun/fengwk/kkstudio/share/storage/) | [`StorageDtoContractTest.java`](../../share/src/test/java/fun/fengwk/kkstudio/share/storage/StorageDtoContractTest.java)：upload/presign 的 nullable 字段与请求侧 `sizeBytes` 类型 |
| Settings | [systemsettings/](../../share/src/test/java/fun/fengwk/kkstudio/share/systemsettings/) | [`SystemSettingsDtoContractTest.java`](../../share/src/test/java/fun/fengwk/kkstudio/share/systemsettings/SystemSettingsDtoContractTest.java)：每一层嵌套的未知字段拒绝与秘密字段扫描 |
| Chat、MCP、Environment、Plugin、Skill | [ai/chat/](../../share/src/test/java/fun/fengwk/kkstudio/share/ai/chat/)、[ai/mcp/](../../share/src/test/java/fun/fengwk/kkstudio/share/ai/mcp/)、[ai/environment/](../../share/src/test/java/fun/fengwk/kkstudio/share/ai/environment/)、[ai/plugin/](../../share/src/test/java/fun/fengwk/kkstudio/share/ai/plugin/)、[ai/skill/](../../share/src/test/java/fun/fengwk/kkstudio/share/ai/skill/) | [`ChatDtoContractTest.java`](../../share/src/test/java/fun/fengwk/kkstudio/share/ai/chat/ChatDtoContractTest.java)、[`McpServerDtoContractTest.java`](../../share/src/test/java/fun/fengwk/kkstudio/share/ai/mcp/McpServerDtoContractTest.java)、[`EnvironmentDtoContractTest.java`](../../share/src/test/java/fun/fengwk/kkstudio/share/ai/environment/EnvironmentDtoContractTest.java)、[`PluginDtoContractTest.java`](../../share/src/test/java/fun/fengwk/kkstudio/share/ai/plugin/PluginDtoContractTest.java)、[`SkillPackageDtoContractTest.java`](../../share/src/test/java/fun/fengwk/kkstudio/share/ai/skill/SkillPackageDtoContractTest.java) |

---

上级：[系统设计](../system-design.md)。相关文档：[Canvas Core](canvas-core.md)、[Schema](schema.md)、[Web](web.md)、[Platform](platform.md)、[Project](project.md)。
