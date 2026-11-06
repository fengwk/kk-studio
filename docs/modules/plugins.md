# Plugins 模块

[`plugins`](../../plugins/pom.xml) 聚合一组基于平台插件 SPI（见 [Platform Plugin API](platform-plugin-api.md)）实现的独立扩展。
各个插件以独立 Maven 模块构建，由 [`web/pom.xml`](../../web/pom.xml) 的 runtime dependency 决定发行物中包含的集合。
插件在安装期声明身份、受控配置与认证契约，在运行期装配实例，并按需向 Harness Runtime（Agent 工具）和 Canvas（图形节点函数）两域提供能力。

平台整体的插件发现、持久化与生命周期管理见 [Platform](platform.md#构建期-plugin)。

## 架构与安装模型

每个插件是一个实现了 [`Plugin`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/Plugin.java) 的顶层组件，通过 Spring Boot 的 `AutoConfiguration.imports` 注册。

- **身份声明**：`descriptor()` 返回全局唯一的 `PluginDescriptor(id, name, version)`。
- **受控配置 Schema**：`configurationSchema()` 声明强类型普通配置（`valuesSchema`）、初始默认值 JSON（`defaultsJson`）与受保护秘密定义（`secrets`）。配置校验与存储遵循严格 JSON 规范，秘密绝不以明文回读或导出。
- **认证规范**：`authenticationSpec()` 声明插件的认证模式，支持 `None`（无认证）、`StaticKey`（静态 API Key / Token，支持声明变更时失效认证代际的上下文关联字段）和 `DeepLink`（OAuth 风格的两阶段认证，支持区域候选）。
- **实例装配**：`configure(config, context)` 根据数据库中经 CAS 校验的当前配置与只读凭据端口装配 `PluginInstance`。实例暴露两类贡献：
  - `harnessContributors()`：向 Harness Runtime 贡献 Agent 外部工具（`HarnessContributor`）；
  - `contributions(Class<T>)`：按类型交出产品域贡献，Canvas 读取 `contributions(CanvasFunctionAdapter.class)` 得到图形节点执行适配器。
  两类贡献都在 configure 期冻结、互不依赖，均可为空列表；插件因此同时依赖 platform-plugin-api、harness-runtime 与自己的产品 SPI（Canvas 为 `kk-studio-canvas-spi`）。

当前发行物包含四个内置插件：

| 插件 ID | 插件名称 | 认证模式 | Harness Contributor | Canvas Function Adapter | 领域职责 |
| --- | --- | --- | --- | --- | --- |
| `comfyui` | ComfyUI | StaticKey | 无 | 有 | 驱动 ComfyUI 工作流，提供基于 MiniMax H3 的视频生成节点 |
| `media` | Media | None | 无 | 有 | 提供本地图像裁剪（image.crop）Canvas Function，固定输出 PNG |
| `minimax-mavis` | MiniMax Mavis | DeepLink | 有（15 个模型工具） | 无 | MiniMax Mavis 网关工具集（搜索、多模态理解、语音与媒体生成） |
| `opencli` | OpenCLI | StaticKey | 无 | 有（2 个图形适配器） | 桥接 OpenCLI Hub，提供 ChatGPT 图像生成与即梦视频生成节点 |

---

## ComfyUI 插件

[`plugins/comfyui`](../../plugins/comfyui/pom.xml) 负责连接外部 ComfyUI 实例，执行图形工作流并将生成结果物化为平台 Blob 资源。

- **类与包**：[`fun.fengwk.kkstudio.plugin.comfyui`](../../plugins/comfyui/src/main/java/fun/fengwk/kkstudio/plugin/comfyui/)，安装定义为 [`ComfyuiPlugin`](../../plugins/comfyui/src/main/java/fun/fengwk/kkstudio/plugin/comfyui/ComfyuiPlugin.java)，自动配置为 [`ComfyuiAutoConfiguration`](../../plugins/comfyui/src/main/java/fun/fengwk/kkstudio/plugin/comfyui/ComfyuiAutoConfiguration.java)。
- **认证契约**：静态密钥认证（`apiKey`），当配置中的 `baseUrl` 或 `authMode`（`NONE` / `BEARER`）发生变化时，旧凭据失效以推进认证代际。
- **配置项**：
  - `enabled`：是否启用 ComfyUI 插件（布尔值，默认 `false`）；
  - `baseUrl`：ComfyUI 服务的 HTTP(S) 地址（默认 `http://127.0.0.1:8188`）；
  - `authMode`：认证头模式（`NONE` 或 `BEARER`，默认 `NONE`）；
  - `connectTimeoutMillis`、`requestTimeoutMillis`、`pollIntervalMillis`、`maxWaitMillis`：连接、请求与轮询超时控制；
  - `promptAgentName`、`promptMaxWaitMillis`：H3 策略依赖的 Prompt Agent 名称与等待超时；
  - `workflows`：受控 ComfyUI 工作流定义列表，默认包含内置的 MiniMax H3 视频生成工作流。
- **内置 H3 视频生成**：
  - 内置工作流模板：[`minimax-h3-ref2va-workflow.json`](../../plugins/comfyui/src/main/resources/fun/fengwk/kkstudio/plugin/comfyui/minimax-h3-ref2va-workflow.json)，基于 MiniMax H3 架构实现参考图加音频生成视频；
  - 执行流程：由 [`ComfyuiCanvasFunctionAdapter`](../../plugins/comfyui/src/main/java/fun/fengwk/kkstudio/plugin/comfyui/ComfyuiCanvasFunctionAdapter.java) 校验入参，前置下载或读取引用的图像/音频资源，通过 [`StandardComfyuiClient`](../../plugins/comfyui/src/main/java/fun/fengwk/kkstudio/plugin/comfyui/StandardComfyuiClient.java) 上传素材至 ComfyUI 输入目录，提交 Prompt 队列并通过 `/history/{promptId}` 轮询结果；
  - 产物物化：解析输出节点中的视频文件，下载并写入 Storage 形成不可变 `Blob`，返回 Canvas 节点执行结果。

---

## Media 插件

[`plugins/media`](../../plugins/media/pom.xml) 提供通用的本地或无凭据媒体处理能力。

- **类与包**：[`fun.fengwk.kkstudio.plugin.media`](../../plugins/media/src/main/java/fun/fengwk/kkstudio/plugin/media/)，安装定义为 [`MediaPlugin`](../../plugins/media/src/main/java/fun/fengwk/kkstudio/plugin/media/MediaPlugin.java)，自动配置为 [`MediaAutoConfiguration`](../../plugins/media/src/main/java/fun/fengwk/kkstudio/plugin/media/MediaAutoConfiguration.java)。
- **认证契约**：`PluginAuthSpec.None`，无需认证，不持有凭据，配置与执行均不联网。
- **配置项**：`enabled`（布尔值，默认 `true`）。停用时实例贡献的 Canvas 函数目录为空。
- **Canvas 适配器**：[`MediaFunctionAdapter`](../../plugins/media/src/main/java/fun/fengwk/kkstudio/plugin/media/MediaFunctionAdapter.java)，在 Canvas 域仅注册单个 `image.crop` 函数，执行本地无模型依赖的矩形裁剪并输出 PNG 格式图像。

---

## MiniMax Mavis 插件

[`plugins/minimax-mavis`](../../plugins/minimax-mavis/pom.xml) 接入 MiniMax Mavis 私有网关，为 Harness Agent 提供全套多模态感知与生成工具。

- **类与包**：[`fun.fengwk.kkstudio.plugin.minimaxmavis`](../../plugins/minimax-mavis/src/main/java/fun/fengwk/kkstudio/plugin/minimaxmavis/)，安装定义为 [`MiniMaxMavisPlugin`](../../plugins/minimax-mavis/src/main/java/fun/fengwk/kkstudio/plugin/minimaxmavis/MiniMaxMavisPlugin.java)，自动配置为 [`MiniMaxMavisAutoConfiguration`](../../plugins/minimax-mavis/src/main/java/fun/fengwk/kkstudio/plugin/minimaxmavis/MiniMaxMavisAutoConfiguration.java)。
- **认证契约**：`DeepLink` 认证，固定候选区域为 `CN`（中国大陆）与 `EN`（国际区）。
  - Prepare 阶段返回官方登录链接与单次 `authAttemptId`；
  - Complete 阶段接收回调换取 JWT 凭据并持久化；
  - 自动续期：实现 [`PluginCredentialRefresher`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginCredentialRefresher.java)，由 [`MiniMaxMavisCredentialRefresher`](../../plugins/minimax-mavis/src/main/java/fun/fengwk/kkstudio/plugin/minimaxmavis/MiniMaxMavisCredentialRefresher.java) 在凭据即将过期且租约有效时自动向网关续期。
- **配置项**：`enabled`（布尔值，默认 `true`）；受控配置 Schema 仅有此开关，传输超时与 endpoint 由内部常量固定。
- **Harness Contributor 工具集**：
  由 [`MiniMaxMavisHarnessContributor`](../../plugins/minimax-mavis/src/main/java/fun/fengwk/kkstudio/plugin/minimaxmavis/MiniMaxMavisHarnessContributor.java) 注册 15 项模型工具（能力标识为 kebab-case，面向模型的工具名均统一冠以 `mavis_` 前缀并使用下划线）：
  - **搜索与提取**：
    - `mavis_web_search`（能力 `web-search`）：公共互联网网页搜索；
    - `mavis_extract_web`（能力 `extract-web`）：网页全文正文与结构化提取；
    - `mavis_image_search`（能力 `image-search`）：图片关键词搜索；
    - `mavis_reverse_image`（能力 `reverse-image`）：以图搜图（图片反向搜索）。
  - **多模态理解**：
    - `mavis_understand_image`（能力 `understand-image`）：图片问答与视觉理解；
    - `mavis_understand_audio`（能力 `understand-audio`）：音频文件内容理解与分析；
    - `mavis_understand_video`（能力 `understand-video`）：视频内容多模态分析。
  - **语音与音色**：
    - `mavis_asr`（能力 `asr`）：自动语音识别；
    - `mavis_tts`（能力 `tts`）：单句文本转语音；
    - `mavis_tts_batch`（能力 `tts-batch`）：长文本/多段批量语音合成；
    - `mavis_list_voices`（能力 `list-voices`）：枚举网关支持的音色列表。
  - **生成类**：
    - `mavis_generate_image`（能力 `generate-image`）：文本生成图片；
    - `mavis_generate_music`（能力 `generate-music`）：音乐生成；
    - `mavis_submit_video` 与 `mavis_query_video`（能力 `submit-video` 与 `query-video`）：异步视频生成任务提交与轮询查询。

---

## OpenCLI 插件

[`plugins/opencli`](../../plugins/opencli/pom.xml) 与外部 OpenCLI Hub 服务桥接，提供图形化 Canvas Function。

- **类与包**：[`fun.fengwk.kkstudio.plugin.opencli`](../../plugins/opencli/src/main/java/fun/fengwk/kkstudio/plugin/opencli/)，安装定义为 [`OpenCliPlugin`](../../plugins/opencli/src/main/java/fun/fengwk/kkstudio/plugin/opencli/OpenCliPlugin.java)，自动配置为 [`OpenCliAutoConfiguration`](../../plugins/opencli/src/main/java/fun/fengwk/kkstudio/plugin/opencli/OpenCliAutoConfiguration.java)。
- **认证契约**：静态密钥认证（`apiKey`，上下文字段为 `baseUrl`），同时支持可选的受保护私有身份 `instanceId`。两者均保存在 secrets 中，绝不回读明文。
- **配置项**：
  - 核心连接与超时：`enabled`（默认 `false`）、`baseUrl`、`connectTimeoutMillis`、`requestTimeoutMillis`、`longPollTimeoutMillis`；响应及输出大小限制由客户端固定控制；
  - `gptImage2` 子配置：`paidEnabled`、`askTimeoutSeconds`、`hubExecutionTimeoutMillis`、`maxWaitMillis`；
  - `seedance` 子配置：`enabled`、可选 `workspaceId`、`retry`、`hubExecutionTimeoutMillis`、`statusPollIntervalMillis`、`maxWaitMillis`。
- **连接检查能力**：实现 [`PluginConnectionCheck`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginConnectionCheck.java)（[`OpenCliConnectionCheck`](../../plugins/opencli/src/main/java/fun/fengwk/kkstudio/plugin/opencli/OpenCliConnectionCheck.java)），通过 `probeConnection()` 进行用户触发的只读连通性探测，并将类型化结果投影为 `Reachability`、`Authentication` 与 `BrowserSession` 三个维度。
- **Canvas 适配器**：
  提供两个独立的 Canvas 函数适配器：
  - [`GptImage2CanvasFunctionAdapter`](../../plugins/opencli/src/main/java/fun/fengwk/kkstudio/plugin/opencli/GptImage2CanvasFunctionAdapter.java)：适配 ChatGPT 图像生成，通过 OpenCLI Hub 提交 Prompt，等待执行并收集输出图像写入 Storage；
  - [`SeedanceCanvasFunctionAdapter`](../../plugins/opencli/src/main/java/fun/fengwk/kkstudio/plugin/opencli/SeedanceCanvasFunctionAdapter.java)：适配即梦（Seedance）视频生成，由 [`OpenCliReferenceUploader`](../../plugins/opencli/src/main/java/fun/fengwk/kkstudio/plugin/opencli/OpenCliReferenceUploader.java) 预先将引用素材上传至 Hub，发起长轮询生成任务并在终态物化视频结果。

---

## 源码与测试

各插件的测试均就近组织在子模块的 `src/test/java` 下，遵循全仓测试规范，主要采用纯单元测试与本地 HTTP 模拟服务器：

- **ComfyUI**：[`plugins/comfyui/src/test/java`](../../plugins/comfyui/src/test/java/fun/fengwk/kkstudio/plugin/comfyui/)
  - [`ComfyuiPluginTest.java`](../../plugins/comfyui/src/test/java/fun/fengwk/kkstudio/plugin/comfyui/ComfyuiPluginTest.java)：安装期 Schema、默认值与认证规范校验；
  - [`StandardComfyuiClientTest.java`](../../plugins/comfyui/src/test/java/fun/fengwk/kkstudio/plugin/comfyui/StandardComfyuiClientTest.java)：HTTP/WebSocket 客户端契约与解析；
  - [`H3PromptWorkflowTest.java`](../../plugins/comfyui/src/test/java/fun/fengwk/kkstudio/plugin/comfyui/H3PromptWorkflowTest.java)：MiniMax H3 工作流生成与 JSON 映射校验。
- **Media**：[`plugins/media/src/test/java`](../../plugins/media/src/test/java/fun/fengwk/kkstudio/plugin/media/)
  - [`MediaPluginTest.java`](../../plugins/media/src/test/java/fun/fengwk/kkstudio/plugin/media/MediaPluginTest.java)：无凭据插件安装与开关装配；
  - [`MediaFunctionAdapterTest.java`](../../plugins/media/src/test/java/fun/fengwk/kkstudio/plugin/media/MediaFunctionAdapterTest.java)：函数参数校验与调用适配。
- **MiniMax Mavis**：[`plugins/minimax-mavis/src/test/java`](../../plugins/minimax-mavis/src/test/java/fun/fengwk/kkstudio/plugin/minimaxmavis/)
  - [`MiniMaxMavisPluginTest.java`](../../plugins/minimax-mavis/src/test/java/fun/fengwk/kkstudio/plugin/minimaxmavis/MiniMaxMavisPluginTest.java)：DeepLink 声明与区域参数；
  - [`MiniMaxMavisCredentialRefresherTest.java`](../../plugins/minimax-mavis/src/test/java/fun/fengwk/kkstudio/plugin/minimaxmavis/MiniMaxMavisCredentialRefresherTest.java)：凭据自动续期逻辑；
  - [`tool/MiniMaxMavisToolContractTest.java`](../../plugins/minimax-mavis/src/test/java/fun/fengwk/kkstudio/plugin/minimaxmavis/tool/MiniMaxMavisToolContractTest.java)：15 个模型工具的 Schema 与契约边界。
- **OpenCLI**：[`plugins/opencli/src/test/java`](../../plugins/opencli/src/test/java/fun/fengwk/kkstudio/plugin/opencli/)
  - [`OpenCliPluginTest.java`](../../plugins/opencli/src/test/java/fun/fengwk/kkstudio/plugin/opencli/OpenCliPluginTest.java)：Schema 校验、secrets 声明与实例配置；
  - [`OpenCliCanvasFunctionAdaptersTest.java`](../../plugins/opencli/src/test/java/fun/fengwk/kkstudio/plugin/opencli/OpenCliCanvasFunctionAdaptersTest.java)：GPT Image 与 Seedance 两个 Canvas 适配器的参数映射与执行模拟。

---

上级：[系统设计](../system-design.md)。相关文档：[Platform Plugin API](platform-plugin-api.md)、[Platform](platform.md)、[Canvas](canvas.md)、[Harness Runtime](harness-runtime.md)、[Frontend](frontend.md)。
