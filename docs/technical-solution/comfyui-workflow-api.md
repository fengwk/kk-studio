# ComfyUI 工作流与 S3 直传后端

## 职责

为前端 ComfyUI 控制台提供：

- 持久化的 ComfyUI 工作流卡片 CRUD；
- 浏览器直传 / 直下载固定 bucket 的 S3 对象（详见 [s3-presign.md](s3-presign.md)）；
- 基于持久工作流配置的无状态提交 / 查询 / 取消 / job-scoped 输出下载；
- 固定 bucket 的 S3 文件输入桥，提交前按服务端上限做 HEAD + 下载后大小校验。

`kk-studio` 不持久化 ComfyUI run / task；`runId` 直接等于 ComfyUI prompt / job id。

## 模块布局

| 模块 | 角色 |
| --- | --- |
| `share/.../ComfyuiWorkflow*.java` | HTTP / DTO 边界 DTO；卡片 `id` 在边界以十进制字符串形式暴露，持久层内部仍为 `long` |
| `core/comfyui/ComfyuiConfiguration` | Spring 配置：`ComfyUIClient` bean 仅在 `kk-studio.comfyui.enabled=true` 时创建 |
| `core/comfyui/ComfyuiProperties` | `kk-studio.comfyui.*` 配置属性 |
| `core/comfyui/ComfyuiRuntimeService` | 无状态运行期：参数映射、S3 输入桥、提交、查询（按 JSONPath selector 投影规范化结果）、取消、job-scoped 输出下载 |
| `core/comfyui/workflow_api/service/ComfyuiWorkflowApiIds` | 边界 ID 严格解析；`parsePositive` / `format` |
| `core/comfyui/workflow_api/service/runtime/*` | binding 模型 + parser + selector validator + lookup service（runtime 入口） |
| `core/comfyui/workflow_api/repo/*` | MyBatis 仓储 |
| `core/storage/S3ObjectContent` | 固定 bucket 读取的对象字节 + content type |
| `core/storage/S3StorageService#download(String, long)` | HEAD-before + 后置字节复核的双层大小保护 |

## 端点

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET`    | `/api/comfyui/workflows?pageNumber&pageSize` | 卡片分页 |
| `POST`   | `/api/comfyui/workflows` | 创建卡片（写入路径校验 binding / selector） |
| `PUT`    | `/api/comfyui/workflows/{id}` | 更新卡片（`id` 路径为十进制字符串） |
| `DELETE` | `/api/comfyui/workflows/{id}` | 删除卡片 |
| `POST`   | `/api/comfyui/workflows/{apiName}/runs` | 无状态提交，返回 `{runId, status, defaultSelector}` |
| `GET`    | `/api/comfyui/runs/{runId}?select=...` | 任务查询；终态时返回规范化 `{outputs, files}`，可选 JSONPath 投影 |
| `POST`   | `/api/comfyui/runs/{runId}/cancel` | 取消任务 |
| `GET`    | `/api/comfyui/runs/{runId}/files/{nodeId}/{mediaType}/{index}` | job-scoped 输出下载；filename/subfolder/type 仅从当前 job 解析 |

S3 直传 / 直下载见 [s3-presign.md](s3-presign.md)。

## 配置

```yaml
kk-studio:
  comfyui:
    enabled: true
    base-url: http://comfyui:8188
    api-key: ${COMFYUI_API_KEY:}
    connect-timeout: 10s
    read-timeout: 30s
    websocket-timeout: 30m
    max-input-file-size: 50MB
  storage:
    s3:
      enabled: true                      # 启用 S3 桥
      endpoint: http://minio:9000
      public-endpoint: https://objects.example.com
      region: us-east-1
      bucket: kk-studio
      access-key: ${S3_ACCESS_KEY}
      secret-key: ${S3_SECRET_KEY}
```

`kk-studio.comfyui.max-input-file-size` 控制 S3 输入桥的最大对象字节数；超出大小的对象在 HEAD 或下载阶段都会被拒绝，避免把超大文件加载到 ComfyUI。

## 边界约束

- 卡片 `id` 在 DTO 与 `@PathVariable` 上都是十进制字符串，由 `ComfyuiWorkflowApiIds.parsePositive` 校验后转换为 `long` 访问数据库。
- 工作流 JSON 必须可被 `Workflow.fromApiJson` 解析；binding JSON 必须是 JSON 数组，每项必须含 `name`、`kind`、`nodeId`、`inputName`；`kind=file` 不允许 `valueType` / `defaultValue`；`kind=parameter` 可选 `valueType ∈ {string, integer, number, boolean, json}` 与对应 `defaultValue`。
- `defaultSelector` 静态校验：长度 ≤ 1024 字符；禁止 `..` 递归下降与 `=~` 过滤；必须能被 Jayway `JsonPath.compile` 编译。
- 文件输入只接受固定 bucket 的 S3 key（经 `S3ObjectKeyNormalizer.normalize` 校验）；提交前按 `max-input-file-size` 做 HEAD + 下载后字节双重校验。
- 输出下载 URL 只携带 `{runId}/{nodeId}/{mediaType}/{index}`，真实 `filename/subfolder/type` 必须从当前 job 重新解析，调用方不能传任意 ComfyUI 文件路径。

## Maven 依赖

- `fun.fengwk.convention4j:convention4j-comfyui:1.2.2`：SDK 直接依赖。
- `com.jayway.jsonpath:json-path:2.9.0`：selector 解析与校验。

两个依赖都已加入根 `pom.xml` 的 `dependencyManagement` 与 `core/pom.xml` 的 `<dependencies>`。根 `pom.xml` 的 `<parent>` 与 `convention4j-comfyui` 同批对齐到 `1.2.2`，所有 convention4j 依赖（包含 `convention4j-common`、`convention4j-spring-boot-starter` 等）随同一发布批次解析，避免出现 `convention4j-comfyui:1.2.2` 自身 `common` 仍被旧 parent 管理降级的情况。

**远程发布前置条件**：`convention4j:1.2.2` 发布批次在 22 个模块上的本地 `~/.m2` 安装仅用于开发期验证。远程环境必须把 `fun.fengwk.convention4j` 全套 `1.2.2` 构件（不止 `convention4j-comfyui`，还需 `convention4j-parent` 及所有 starter / tracer / oauth2 等 1.2.2 发布批次构件）发布到内网 Maven 仓库，依赖解析才可复现。

## 目标环境验收

部署验收使用实际的浏览器 origin、对象存储和 ComfyUI 实例完成以下闭环：

1. 目标构建环境只能从远程 Maven 仓库解析完整的 `fun.fengwk.convention4j:1.2.2` 发布批次，不依赖开发机的 `~/.m2`。
2. `kk-studio.storage.s3.endpoint` 可由服务端访问，`public-endpoint` 可由浏览器访问，且两者指向同一个固定 bucket；对象存储 CORS 允许应用 origin 使用 `PUT`、`GET`、`HEAD` 及预签名响应中的请求头。
3. 浏览器通过原生 `fetch` 对预签名响应的 `url` 执行 `PUT`，只设置响应 `headers` 中的头；不得设置 `Host`，也不得将对象字节发送给 kk-studio。
4. 使用上传后的 key 提交带 file binding 的工作流。kk-studio 必须从固定 bucket 完成 HEAD、大小校验、下载和转交，ComfyUI 返回的 prompt/job id 必须原样作为 `runId`。
5. 对该 `runId` 完成运行状态轮询、取消和 job-scoped 输出下载；输出下载只能解析该 job 返回的 filename、subfolder 和 type，不能接受调用方指定的 ComfyUI 路径。
6. 分别验证关闭 `kk-studio.storage.s3.enabled` 或 `kk-studio.comfyui.enabled` 时，依赖相应运行期能力的 API 显式返回不可用，而不会降级为本地文件代理或持久化 ComfyUI job。

## 实现位置

| 关注点 | 文件 |
| --- | --- |
| 配置 | `core/src/main/java/fun/fengwk/kkstudio/core/comfyui/Comfyui{Configuration,Properties}.java` |
| 运行服务 | `core/src/main/java/fun/fengwk/kkstudio/core/comfyui/ComfyuiRuntimeService.java` |
| 卡片实体 | `core/src/main/java/fun/fengwk/kkstudio/core/comfyui/workflow_api/service/model/ComfyuiWorkflowApi.java` |
| 仓储 | `core/src/main/java/fun/fengwk/kkstudio/core/comfyui/workflow_api/repo/impl/MysqlComfyuiWorkflowApiRepository.java` 与 mapper |
| 服务 | `core/src/main/java/fun/fengwk/kkstudio/core/comfyui/workflow_api/service/impl/ComfyuiWorkflowApiServiceImpl.java` |
| ID 解析 | `core/src/main/java/fun/fengwk/kkstudio/core/comfyui/workflow_api/service/ComfyuiWorkflowApiIds.java` |
| 运行时 binding / selector / lookup | `core/src/main/java/fun/fengwk/kkstudio/core/comfyui/workflow_api/service/runtime/*` |
| S3 输入桥 | `core/src/main/java/fun/fengwk/kkstudio/core/storage/S3StorageService(Impl).java` |
| HTTP 入口 | `web/src/main/java/fun/fengwk/kkstudio/web/controller/StudioComfyui{WorkflowApi,Runtime}Controller.java` |
| DTO | `share/src/main/java/fun/fengwk/kkstudio/share/model/ComfyuiWorkflow*.java` |
