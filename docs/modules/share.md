# Share 模块

前端、Electron 客户端或任何脚本都要在后端 API 的 JSON 上读写；一旦字段名、可空性或 `version` 的类型由某个 Controller 临时决定，客户端就会在版本升级后静默错位。`share` 把这些对外的 JSON 形状集中成一份可独立编译、可独立测试的契约：请求体、响应体、枚举、sealed union 与它们的 Jackson 注解。它只有 `jackson-annotations` 一个生产依赖（见 [`share/pom.xml`](../../share/pom.xml)），不依赖 Spring、PostgreSQL、Flyway，也不认识 `canvas-core`、`platform` 或 `harness-*`——因此契约测试可以在毫秒级跑完，wire 改动也不会牵动领域代码。

它只描述传输契约，不承载领域行为：DTO 不代表领域状态，也不决定持久化结构。领域到 DTO 的映射在 [web](web.md) 的 [`WebDtoMapper`](../../web/src/main/java/fun/fengwk/kkstudio/web/mapper/WebDtoMapper.java)，业务语义（version CAS、幂等键、引用计数、调度）在 [platform](platform.md)、[canvas-core](canvas-core.md) 与 [canvas-infra](canvas-infra.md)。

## 严格 JSON 边界

请求侧 DTO 用 `@JsonAnySetter` 把未知字段转成 `IllegalArgumentException`，把「客户端发了个我们不认识的字段」变成显式失败而不是静默忽略。多态 command 再由 Jackson 的 `type` discriminator 解码：

```json
{ "type": "CREATE_FUNCTION_NODE", "nodeId": "...", "name": "...", "modelKey": "...", "configJson": "..." }
```

[`CanvasCommandDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/canvas/CanvasCommandDTO.java) 的 15 个子类型与 [canvas-core](canvas-core.md) 的 `CanvasCommand` 一一对应：`CREATE_TEXT_NODE`、`UPDATE_TEXT_NODE`、`CREATE_RESOURCE_NODE`、`CREATE_FUNCTION_NODE`、`UPDATE_FUNCTION`、`RENAME_NODE`、`UPDATE_NODE_TRANSFORMS`、`DELETE_NODE`、`CREATE_LINK`、`DELETE_LINK`、`CREATE_GROUP`、`MOVE_GROUP`、`UNGROUP`、`DELETE_GROUP`、`RENAME_GROUP`。Patch 用 `op` discriminator 表达 `UPSERT` / `REMOVE`，三类实体各自独立。

字符串到 UUID 与数字的解析属于 HTTP 边界：[`WebDtoMapper.parseUuid`](../../web/src/main/java/fun/fengwk/kkstudio/web/mapper/WebDtoMapper.java) 要求 canonical UUID 文本（`UUID.toString()` 的往返必须一致），[`StudioCanvasController.parseVersion`](../../web/src/main/java/fun/fengwk/kkstudio/web/controller/StudioCanvasController.java) 要求 `0|[1-9]\d*` 且能放进 `long`；共享层只承载解析后的值，整数形态的实体 id 在 wire 上永远不出现。

长的 durable 游标在 wire 上保持十进字符串：Canvas document/patch/version event 的 `version` 与 `baseVersion`、`expectedVersion`、Harness Thread 的 `sequence`、Project/Issue 的 version 都是 `String`，[`CanvasDtoContractTest.java`](../../share/src/test/java/fun/fengwk/kkstudio/share/canvas/CanvasDtoContractTest.java) 用反射断言这些字段的 Java 类型就是 `String`，避免有人图方便改回 `long` 让 JS 丢精度。

## 可空字段与安全输出

全局 Jackson 默认是 `NON_NULL`（见 [web](web.md) 的 `StrictJacksonConfiguration` 与 `STRICT_DUPLICATE_DETECTION`），因此「字段缺席」在默认情况下等于「null」。但有些字段缺席会被客户端误读——`blobId` 与 `textContent` 恰好互斥，一个是 blob 资源，一个是文本资源；`function`/`run` 为 null 表示这是普通节点或还没跑过。这类字段显式声明 `@JsonInclude(ALWAYS)`，保证即使值为 null 也出现在 JSON 里：

| DTO | required-nullable 字段 |
| --- | --- |
| [`CanvasResourceNodeDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/canvas/CanvasResourceNodeDTO.java) | `groupId`、`function`、`run` |
| [`CanvasResourceDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/canvas/CanvasResourceDTO.java) | `blobId`、`textContent`、`mediaType`、`sizeBytes`、`width`、`height`、`durationMs` |
| [`CanvasFunctionRunDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/canvas/CanvasFunctionRunDTO.java) | `error` |
| [`CanvasFunctionModelDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/canvas/CanvasFunctionModelDTO.java) | `unavailableReason` |
| [`CanvasFunctionParameterDefinitionDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/canvas/CanvasFunctionParameterDefinitionDTO.java) | `defaultValue`、`min`、`max` |
| [`StorageUploadDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/storage/StorageUploadDTO.java)、[`StoragePresignedUrlDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/storage/StoragePresignedUrlDTO.java) | `blobId`、`presignedPut`、`mediaType`、`sizeBytes` |

后端绝不知道 S3 的 bucket 与对象 key：Storage DTO 只给 `url`、`method`、必须原样回传的 `headers` 与 `expiresAt`，[canvas 的预签名端点](canvas-core.md)同理。凭证类字段走 `WRITE_ONLY`（[`AgentProviderEditablePropertiesDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/ai/catalog/AgentProviderEditablePropertiesDTO.java) 的 `credential`），只写不读；[`EnvironmentRegistrationTokenDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/ai/environment/EnvironmentRegistrationTokenDTO.java) 是唯一的显式读取端点，不进通用投影，响应禁止缓存。[`SystemSettingsDtoContractTest.java`](../../share/src/test/java/fun/fengwk/kkstudio/share/systemsettings/SystemSettingsDtoContractTest.java) 用反射扫描各 DTO 的字段名，任何 secret / bootstrap 名称（key、token、instanceId、endpoint、文件系统路径）出现即失败。

## DTO 组织

源码按公共领域分包，包名就是客户端的领域划分：

| 包 | 当前 wire |
| --- | --- |
| [ai.catalog](../../share/src/main/java/fun/fengwk/kkstudio/share/ai/catalog/) | Provider、Model、Agent、ModelRef、Tool catalog |
| [ai.skill](../../share/src/main/java/fun/fengwk/kkstudio/share/ai/skill/) | Platform 全局 Skill、不可变 package 版本与完整替换请求 |
| [ai.mcp](../../share/src/main/java/fun/fengwk/kkstudio/share/ai/mcp/) | MCP Server 安全 DTO、全量配置 DTO、创建/更新与统一发现 DTO |
| [ai.chat](../../share/src/main/java/fun/fengwk/kkstudio/share/ai/chat/) | Chat 与 Chat defaults |
| [ai.environment](../../share/src/main/java/fun/fengwk/kkstudio/share/ai/environment/) | Environment Card CRUD、registration token、最近一次 READY runtime 投影与通用管理操作 DTO |
| [ai.runtime](../../share/src/main/java/fun/fengwk/kkstudio/share/ai/runtime/) | Session、Entry、Thread Snapshot、Command batch、Invocation、approval、stop、compaction |
| [canvas](../../share/src/main/java/fun/fengwk/kkstudio/share/canvas/) | Canvas document、Snapshot、Patch、typed command、Resource、Function 与 Run |
| [comfyui](../../share/src/main/java/fun/fengwk/kkstudio/share/comfyui/) | Workflow API 与运行请求/结果 |
| [project](../../share/src/main/java/fun/fengwk/kkstudio/share/project/) | Project/Issue Snapshot、Issue 详情、Run 与各类操作请求 |
| [storage](../../share/src/main/java/fun/fengwk/kkstudio/share/storage/) | Upload、Blob signed URL、S3 presign |
| [systemsettings](../../share/src/main/java/fun/fengwk/kkstudio/share/systemsettings/) | 六个 settings section、schema 与 update request |

只有出现在 HTTP 边界上的值才进 DTO。Canvas Snapshot/Patch 把 document version、实体 UPSERT/REMOVE 与 Function Run 投影组成前端可渲染的聚合；Harness Snapshot 包含 root-to-head entries、queued commands、活跃 invocation、tool siblings 与未物化的 attempt failure；Settings DTO 包含完整六 section 与 `expectedVersion`，[`SystemSettingsSchemaDTO`](../../share/src/main/java/fun/fengwk/kkstudio/share/systemsettings/SystemSettingsSchemaDTO.java) 提供 UI 的 ordered sections/groups/fields。

严格程度按用途区分：请求体与对外投影显式拒绝未知字段，纯响应投影（如 `CanvasSnapshotDTO`）与内部嵌套结构不需要重复声明。领域身份使用 sealed interface / record 表达封闭集合（命令、patch 项），普通数据用 Lombok `@Data`。

## 不变量

- 未知字段、重复 JSON 键、错误的 union `type`/`op`、非 canonical UUID 或非规范数字都在 wire 边界失败，不会进入领域服务。
- required-nullable 字段即使为 `null` 也必须序列化，避免客户端把「未返回」当成「无值」。
- durable 版本与序号在 wire 上永远是十进字符串；`ModelRef` 是 `providerName/modelName`，只在第一个 `/` 处分割，因此模型名本身可以包含斜杠。
- 命令 batch 只表达已解析的边界值；集合是否可变由具体 DTO 与调用方契约决定。
- DTO 不回显 credential、secret 或对象存储内部标识；Blob URL 由服务端按请求重新签发，断连或过期后客户端重新读取 Snapshot/URL。

## 从哪里改

- 改 Canvas wire：[share/canvas/](../../share/src/main/java/fun/fengwk/kkstudio/share/canvas/)，同时改 [core 的 `CanvasCommand`](canvas-core.md)（若命令集合变化）、[`WebDtoMapper`](../../web/src/main/java/fun/fengwk/kkstudio/web/mapper/WebDtoMapper.java) 的映射 switch（编译器会强制穷尽）与 [frontend](frontend.md) 的 [shared/api/contracts](../../frontend/src/shared/api/contracts) 类型。
- 改某个领域的输出去敏或字段可见性：先在对应 DTO 上加注解，再补该领域的 `*DtoContractTest`；`SystemSettingsDtoContractTest` 与 `CanvasDtoContractTest` 的字段清单是这类改动的直接守卫。
- 判断一个字段该不该进 share：它是否出现在 HTTP 请求/响应上。领域内部的值、数据库列、S3 key 一律不进。

测试入口（全部是纯 Jackson/反射单元测试，不需要 Spring 或 PostgreSQL），测试目录与上面的 DTO 包一一对应：

| 领域 | 测试目录 | 代表测试 |
| --- | --- | --- |
| Canvas | [canvas/](../../share/src/test/java/fun/fengwk/kkstudio/share/canvas/) | [`CanvasDtoContractTest.java`](../../share/src/test/java/fun/fengwk/kkstudio/share/canvas/CanvasDtoContractTest.java)：version 字段类型必须是十进字符串、required-nullable 字段必须显式发 null、`CanvasDocumentDTO` 不得出现 `threadId` |
| Harness Runtime | [ai/runtime/](../../share/src/test/java/fun/fengwk/kkstudio/share/ai/runtime/) | [`HarnessRuntimeDtoContractTest.java`](../../share/src/test/java/fun/fengwk/kkstudio/share/ai/runtime/HarnessRuntimeDtoContractTest.java)：严格字段、字符串游标与 nullable 发射 |
| Project | [project/](../../share/src/test/java/fun/fengwk/kkstudio/share/project/) | [`ProjectDtoContractTest.java`](../../share/src/test/java/fun/fengwk/kkstudio/share/project/ProjectDtoContractTest.java)：Project/Issue wire 的严格字段与 decimal version |
| Catalog | [ai/catalog/](../../share/src/test/java/fun/fengwk/kkstudio/share/ai/catalog/) | [`ModelRefTest.java`](../../share/src/test/java/fun/fengwk/kkstudio/share/ai/catalog/ModelRefTest.java)：canonical 身份解析 |
| Storage | [storage/](../../share/src/test/java/fun/fengwk/kkstudio/share/storage/) | [`StorageDtoContractTest.java`](../../share/src/test/java/fun/fengwk/kkstudio/share/storage/StorageDtoContractTest.java)：upload/presign 的 nullable 字段与请求侧 `sizeBytes` 类型 |
| Settings | [systemsettings/](../../share/src/test/java/fun/fengwk/kkstudio/share/systemsettings/) | [`SystemSettingsDtoContractTest.java`](../../share/src/test/java/fun/fengwk/kkstudio/share/systemsettings/SystemSettingsDtoContractTest.java)：每一层嵌套的未知字段拒绝与秘密字段扫描 |
| Chat、MCP、Environment | [ai/chat/](../../share/src/test/java/fun/fengwk/kkstudio/share/ai/chat/)、[ai/mcp/](../../share/src/test/java/fun/fengwk/kkstudio/share/ai/mcp/)、[ai/environment/](../../share/src/test/java/fun/fengwk/kkstudio/share/ai/environment/) | [`ChatDtoContractTest.java`](../../share/src/test/java/fun/fengwk/kkstudio/share/ai/chat/ChatDtoContractTest.java)、[`McpServerDtoContractTest.java`](../../share/src/test/java/fun/fengwk/kkstudio/share/ai/mcp/McpServerDtoContractTest.java)、[`EnvironmentDtoContractTest.java`](../../share/src/test/java/fun/fengwk/kkstudio/share/ai/environment/EnvironmentDtoContractTest.java) |

---

上级：[系统设计](../system-design.md)。相关文档：[Canvas Core](canvas-core.md)、[Schema](schema.md)、[Web](web.md)、[Platform](platform.md)。
