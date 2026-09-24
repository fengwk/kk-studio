# Platform 模块

## 应用层边界与装配入口

`platform` 是 kk-studio 的 application layer：它把 `canvas-core`、`harness-runtime`、
`harness-tool` 和 `harness-contributor-api` 的窄 port 组合成可执行的产品用例，并把
PostgreSQL、S3、Model Provider、Environment Daemon、MCP、ComfyUI 与 OpenCLI Hub 等
外部事实适配到这些 port，向上为 [web](web.md) 提供 Catalog、Chat、Project、Issue、
Canvas、Storage、Environment、Settings 与 Harness command 的 application service。

Platform 承载这些窄 port 之间的业务事务、实时资源解析、第三方调用边界和应用级失败
分类：Session/Entry/Thread/Invocation/Work 的纯 Java 状态机由
[harness-runtime](harness-runtime.md) 拥有，Canvas domain 与 repository port 由
[canvas-core](canvas-core.md) 拥有，生产 `@SpringBootApplication`、HTTP Controller 与
WebSocket transport 由 [web](web.md) 拥有。

生产装配入口是 [PlatformAutoConfiguration](../../platform/src/main/java/fun/fengwk/kkstudio/platform/PlatformAutoConfiguration.java)：
它只做 `BaseMapperScan` 与 `ComponentScan`，并通过
[AutoConfiguration.imports](../../platform/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports)
作为 Spring Boot auto-configuration 被 web 引入、装载全部 application service 与适配器。
Harness reducer、processor 与 claim/lease 状态机留在 harness-runtime。Plugin 没有目录
扫描或外部 classloader：是否存在完全由 [web/pom.xml](../../web/pom.xml) 的 runtime
dependency 决定，选中的 Plugin JAR 用 `AutoConfiguration.imports` 自行提供 `StudioPlugin`
与 `HarnessContributor` bean。

## 子域地图

| 子域 | 主要入口 | 职责 |
| --- | --- | --- |
| [catalog](../../platform/src/main/java/fun/fengwk/kkstudio/platform/catalog) | `AgentProviderService`、`AgentModelService`、`AgentDefinitionService`、`SkillCatalogService`、`McpServerService` | 名称寻址的全局 Catalog（Provider/Model/Agent/Skill）与 MCP server/tool |
| [harness/model](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/model) | `PlatformModelGateway`、`ModelExecutionConfiguration`、`DatabaseProviderResolutionService`、`ProviderResourceMaterializer`、`ProviderInlineBlobReader` | Provider 解析、admission、资源物化与 Model I/O |
| [harness/tool](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/tool) | `RuntimeToolCatalog`、`CompositeRuntimeToolCatalog`、`ToolCatalogQueryService`、`ToolExecutionGateway`、`ToolResultFinalizer` | 静态 + 动态工具目录聚合与 Tool 执行 |
| [harness/thread/command](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/thread/command) | `DatabaseTurnResolver` | 每个 live turn 的 Agent/Model/Environment/skill/subagent 解析 |
| [harness/task](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/task) | `AgentPromptComposer`、`AgentBranchSettingsMaterializer` | system prompt 拼接与分支设置物化 |
| [harness/read](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/read) | `PlatformReadToolExecutor`、`PlatformSkillContentReader`、`PlatformResourceContentReader`、`ReadTextWindow` | 统一 `read` 的 path 路由：Skill Git cache 与 Session 授权的 Blob 文本读取，本地路径委托 `BoundEnvironment` |
| [orchestration](../../platform/src/main/java/fun/fengwk/kkstudio/platform/orchestration) | `HarnessCommandAcceptanceOrchestrator`、`SessionDeletionOrchestrator`、`PlatformCanvasCommandService`、`OwnerType` | 跨 owner 的 Harness 命令接受、深删除与 Canvas command |
| [chat](../../platform/src/main/java/fun/fengwk/kkstudio/platform/chat) | `ChatServiceImpl` | Chat CRUD 与深删除 |
| [project](../../platform/src/main/java/fun/fengwk/kkstudio/platform/project) | `ProjectServiceImpl`、`IssueServiceImpl`、`IssueRunServiceImpl`、`IssueControllerDispatcher`、`IssueReconciler`、`ProjectHarnessContributor` | Project/Issue 生命周期与确定性 Reconciler |
| [settings](../../platform/src/main/java/fun/fengwk/kkstudio/platform/settings) | `SystemSettingsServiceImpl`、`SystemSettingsSnapshot`、`SystemSettingsSchemaProvider` | 数据库单行全局设置与其内存快照 |
| plugin | `StudioPluginRegistry`、`PluginCredentialStore`、`PluginResourceGateway` | 构建期 Plugin 发现、安全管理面、加密凭据与 Session Resource 桥接 |
| [storage](../../platform/src/main/java/fun/fengwk/kkstudio/platform/storage) | `StorageUploadServiceImpl`、`StorageBlobManager`、`S3StorageServiceImpl`、`StorageMaintenance`、`StorageObjectKeys` | Blob/upload 生命周期与对象存储 |
| [environment](../../platform/src/main/java/fun/fengwk/kkstudio/platform/environment) | `EnvironmentDaemonGateway`、`EnvironmentRegistry`、`EnvironmentServerConfiguration` | Environment Card、Daemon 会话装配与宿主元数据保留 |
| [canvas](../../platform/src/main/java/fun/fengwk/kkstudio/platform/canvas) | `PlatformCanvasResourceLifecycle`、`CanvasBlobResourceMaterializer`、Canvas Function adapters | Canvas Resource 生命周期与 Function adapter |
| [comfyui](../../platform/src/main/java/fun/fengwk/kkstudio/platform/comfyui) | `ComfyuiWorkflowApiServiceImpl`、`ComfyuiRuntimeService` | Workflow 卡片与无状态运行 |
| [error](../../platform/src/main/java/fun/fengwk/kkstudio/platform/error)、[persistence](../../platform/src/main/java/fun/fengwk/kkstudio/platform/persistence) | `DomainErrorCode`、`PostgresqlIntegrityViolationClassifier` | 领域错误分类与 FK/唯一约束到领域错误的映射 |

平台把 PostgreSQL 作为 Catalog、Chat、SystemSettings、Canvas graph、Harness durable
fact、Blob owner edge 和 cleanup claim 的唯一事实源；NOTIFY、内存 registry、gateway
handle 和 executor task 都只是可重建的 transport state。

## Catalog

Catalog 是名称寻址的全局资源集合，所有变更 service 都使用 `expectedVersion` CAS：

| 资源 | durable 身份 | 运行时作用 |
| --- | --- | --- |
| Provider | `agent_provider.name` | `ProviderType`、base URL、credential、内部 timeout config |
| Model | `(provider_name, name)` | context/output limit、abilities、variants、pricing、default variant |
| Agent | `agent_definition.name` | system prompt、Model 引用、variant 覆盖、tools/skills/subagents 与子会话 Environment 继承策略 |

三类资源的 `description` 都是无长度上限的自由文本（PostgreSQL `text`），与
`system_prompt` 同属展示事实，不参与名称、可见性、CAS 或路由判定，空值按 `trimToNull`
归一化为 null。

Model 的 `name` 支持重命名，其 Provider 保持不可变；Provider 与 Agent 的名称在记录
存续期间不可修改。重命名由 `AgentModelRepository.rename` 在一个事务内完成：以
`expectedVersion` CAS 从旧行插入新行（沿用 `created_at`）、同步所有引用 Agent 的
Model 引用、再删除旧行；任一步不满足预期即抛错并整体回滚。删除由 `AgentProviderGuard`、
`AgentModelReferenceResolver`、`AgentDefinitionReferenceResolver` 拒绝仍被引用的资源，
并通过 `PostgresqlIntegrityViolationClassifier` 把外键约束映射为领域错误。

Agent 的严格 `config` 保存 `tools`、`skills`、`subagents` 与
`inheritParentEnvironment`；继承开关缺省为 `true`，显式 `null` 非法。委派图允许
自引用和环，不做静态拓扑限制；删除 Agent 时自身引用随目标行一起消失，只有其它 Agent
仍存在的入边会阻止删除。

配置 JSON 全部走 strict codec：

- [AgentProviderConfigurationCodec](../../platform/src/main/java/fun/fengwk/kkstudio/platform/catalog/provider/configuration/AgentProviderConfigurationCodec.java)
  合并 `modelCallTimeoutMillis` 与 `modelCallIdleTimeoutMillis`，未声明字段使用
  `ModelCallTimeoutPolicy.DEFAULT`，未知 Provider 扩展字段保留。
- [AgentModelRuntimeConfigParser](../../platform/src/main/java/fun/fengwk/kkstudio/platform/catalog/model/runtime/AgentModelRuntimeConfigParser.java)
  严格解析 `limit`、`abilities`、`variants`、`defaultVariant`、`pricing`；context/output、
  variant id、temperature、reasoning effort 不满足约束即拒绝。
- [AgentDefinitionConfigCodec](../../platform/src/main/java/fun/fengwk/kkstudio/platform/catalog/definition/configuration/AgentDefinitionConfigCodec.java)
  严格解析去重的 `tools`、`SkillRef(packageName, name)` 与 subagent 配置；`tools`
  必须是合法模型可见 tool name（旧 wire 字段 `toolIds` 被严格拒绝）且只能引用运行时
  目录中的 selectable entry；每个 SkillRef 必须命中对应 Package 当前 `skills` 快照。

### Git Skill Package

Skill Package 以不可变 `packageName` 键控一个不可变 repository URL。Branch 只用于检查
候选更新，当前发布内容由人工确认的 exact commit 决定；每个 Git 仓库根目录的一级子目录
对应同名 Skill：

```text
<repository>/
├── dev/
│   ├── SKILL.md
│   ├── references/
│   ├── scripts/
│   └── assets/
└── chatgpt-agent/
    └── SKILL.md
```

数据库的一行 Package 同时保存 `currentCommit`、最近检查到的
`observedHeadCommit`、检查时间/错误与从当前 commit 派生的 `skills` JSON。JSON 元素只含
name 和 description，按 name 确定性排序；正文和目录树只存在于 Git。Create 会读取并
发布当时的 branch HEAD；修改 branch 只改变后续检查来源；repository URL 创建后不可改，
更换仓库要使用新的 Package。

Card 状态只由这组事实派生：从未检查为 `UNCHECKED`，观察值等于当前值为
`UP_TO_DATE`，两者不同为 `UPDATE_AVAILABLE`，最近检查错误非空为 `CHECK_FAILED`。
检查失败始终保留 current commit、skills 与上一次成功观察值，Agent 使用不受影响。

扫描只接受仓库根目录的 `<name>/SKILL.md`。每个文件必须含可解析的 YAML frontmatter，
其中 `name` 与目录 basename 完全一致、`description` 非空且不超过 1024 字符；其它标准 frontmatter 字段不
进入 Catalog。Package 内 name 不得重复，目录和符号链接都不得越出仓库树。repository URL
不得携带 userinfo，第一阶段要求仓库可由 Platform 与 Daemon 直接读取，不设计凭据分发。

远端检查与内容发布是两个 API 语义：

```text
Check
  -> ls-remote branch
  -> 只更新 observedHeadCommit / checkedAt / bounded error

Update(targetCommit, expectedVersion)
  -> fetch exact commit
  -> 校验全部 <name>/SKILL.md 与 Agent 引用
  -> CAS 原子替换 currentCommit + skills
  -> 通知 Platform cache 与在线 Daemon
```

HTTP 面保持同一 Package 资源：

```text
POST   /api/ai/catalog/skill-packages
GET    /api/ai/catalog/skill-packages
GET    /api/ai/catalog/skill-packages/{name}
PUT    /api/ai/catalog/skill-packages/{name}         # description / branch + expectedVersion
DELETE /api/ai/catalog/skill-packages/{name}         # expectedVersion
POST   /api/ai/catalog/skill-packages/{name}/check
POST   /api/ai/catalog/skill-packages/{name}/update  # targetCommit + expectedVersion
```

Create 请求只接收 name、description、repositoryUrl 与 branch，并把当时解析出的 exact HEAD
作为首个 current commit 发布。Update 必须使用 Card 已展示的完整 target commit；即使
branch 随后又前进，也不会暗中切换到用户未确认的 HEAD。删除 Package 或发布移除 Skill
的 commit 前必须拒绝仍被 Agent `SkillRef` 引用的目标。服务端要求 target commit 等于
该 `expectedVersion` 快照中的 `observedHeadCommit`；后台 Check、编辑、删除和发布都只在
实际事实变化时推进同一个 Package version，陈旧 Card 统一返回 version conflict。

Platform 启动只确保 bare cache 包含每个 `currentCommit`，并异步执行 Check；不会把
observed HEAD 自动发布。Platform cache 使用 `<skill-cache-root>/<package>.git`，稳定 URI
`kkstudio:/skills/<package>/<skill>/...` 每次都读取 Package 当前 commit。Agent Prompt
使用稳定 path，不携带 commit，也没有 per-Turn Skill binding。

Package 发布事务发送 `skill_package_changed` PostgreSQL 提示；每个 App 节点收到后回读
Package 权威行、补齐自身 exact commit cache，并只同步连接在本节点的 Daemons。通知只作
唤醒：listener 建连/重连时全量对账，Platform `read` 发现 current commit 尚未物化时也
只按该 exact commit 补 cache，绝不解析或发布 branch HEAD。

Provider type 的唯一 runtime enum 是
[ProviderType](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderType.java)，
wire value 分别是 `openai`、`openai_response`、`anthropic`、`google`；`fromWireValue`
不 trim、不折叠大小写，未支持的 wire value 直接失败。Catalog API 只暴露结构化 config、
名称和版本，不暴露 credential。

## 构建期 Plugin

`plugins` 是独立于 `platform` 的 Maven aggregator，每个子模块是一个可选的 Spring Boot
auto-configuration JAR。Plugin implementation 可以依赖 Platform 与
`harness-contributor-api`，反向依赖禁止；`web` 只以 runtime scope 选择 JAR，不在源码中
import Plugin implementation。每个 JAR 只通过
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
声明入口，不依赖 Web component scan：

```text
platform <- plugins/minimax-mavis <-runtime- web
                    |
                    +-> StudioPlugin
                    +-> HarnessContributor
```

`StudioPluginRegistry` 收集 Spring 容器中的 `StudioPlugin` bean，以全局唯一、不可变的
`pluginId` 冻结安装目录；相同 id 启动失败。安全投影只包含 id、名称、版本、认证状态、
到期/刷新时间与有界错误。Plugin 是否安装完全取决于 classpath：没有对应 JAR 就没有
descriptor、管理动作、后台任务或 Tool，不在数据库维护第二个 enabled 开关，也不支持
运行时安装与 classloader 热更新。

### 新增 Plugin

本节是仓库内新增构建期 Plugin 的接入事实源。参考实现是
[`plugins/minimax-mavis`](../../plugins/minimax-mavis)，但新实现只复制它的边界和装配方式，
不复制与 MiniMax 协议相关的代码。完整接入顺序如下：

1. **建立独立模块**：创建 `plugins/<plugin-id>/pom.xml`，父 POM 使用
   `kk-studio-plugins`，artifact 命名为 `kk-studio-plugin-<plugin-id>`；源码包使用独立的
   `fun.fengwk.kkstudio.plugin.<name>`。只声明直接使用的依赖，允许依赖 Platform 暴露的
   Plugin 窄端口和 Harness 契约，禁止依赖 `web` 或其它 Plugin implementation。
2. **加入 Reactor 与版本管理**：把子模块加入
   [`plugins/pom.xml`](../../plugins/pom.xml) 的 `modules`，并在根
   [`pom.xml`](../../pom.xml) 的 `dependencyManagement` 中以 `${kk-studio.version}` 管理新
   artifact。前者只让 Maven 构建模块，后者只提供依赖版本；两者都不表示 Plugin 已安装。
3. **按职责实现两个独立 SPI**：
   - 需要出现在统一管理面、使用认证或刷新凭据时，实现
     [`StudioPlugin`](../../platform/src/main/java/fun/fengwk/kkstudio/platform/plugin/StudioPlugin.java)。
     `PluginDescriptor.pluginId` 必须全局唯一、稳定且符合 canonical 语法；认证只通过
     `PluginAuthHandler` 交还 opaque `PluginCredentialMaterial`，刷新只通过
     `PluginCredentialRefresher` 处理本次快照。Plugin 不直接访问 credential repository、
     lease、主密钥或密文格式。
   - 需要向模型提供 Tool、自定义状态或上下文投影时，实现
     [`HarnessContributor`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessContributor.java)，
     只通过 `HarnessRegistrar` 注册能力。Tool 必须准确声明 visibility、side effect、
     timeout 和 `EnvironmentSupport`；不得另建 Tool、审批、历史或结果协议。详细契约见
     [Harness Contributor API](harness-contributor-api.md#扩展一个-contributor)。
   - 同时需要管理面和模型能力的远端集成通常同时提供两个 bean；纯 Tool 集成可以只提供
     `HarnessContributor`，此时不会出现在 `/api/plugins` 或 Settings 的 Plugins 页签。
4. **提供唯一自动装配入口**：在 Plugin JAR 内定义一个 `@AutoConfiguration`，由它创建
   `StudioPlugin`、`HarnessContributor` 及本 Plugin 自己的 client、executor 和 lifecycle
   bean。启动期只加载静态 descriptor/schema 并构造 bean，不登录、不解密凭据、不调用远端
   capability；网络访问和 credential resolution 延迟到管理动作、刷新任务或 Tool 调用。
   executor 与后台任务必须有明确并发上限、关闭方法和失败边界。
5. **声明 Boot 元数据**：创建
   `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`，
   每行填写一个 AutoConfiguration 全限定类名。不要依赖 Web component scan，也不要引入
   `ServiceLoader`、外部 classloader 或 Plugin 目录扫描。
6. **确认前端管理形态**：提供 `StudioPlugin` 后，通用管理 API 会把 descriptor 与凭据安全
   投影到 `/settings` 的静态 Plugins 页签。现有前端只完整实现封闭的 `DEEP_LINK` 交互：
   descriptor 给出固定 region，`prepare` 返回官方登录地址，`complete` 只提交
   `callbackUrl`。使用这一交互的新 Plugin 不需要新增专属路由或组件。需要其它认证类型或
   Plugin 专属设置时，必须先显式扩展 Share sealed DTO、Platform 管理用例、静态前端
   contract/UI、i18n 与测试；禁止由 Plugin JAR 下发 HTML、脚本或任意表单 schema。后端允许
   `authHandler()` 为空并投影 `authKind: null`，但当前通用卡片没有定义无认证 Plugin 的
   只读状态语义，因此这种管理形态在补齐前端契约前不能直接发布。具体入口见
   [Frontend：Plugin 设置](frontend.md#plugin-设置)。
7. **选择是否进入发行物**：需要安装时，在 [`web/pom.xml`](../../web/pom.xml) 添加该
   artifact 的 `runtime` dependency；这是唯一安装开关。Web Java/TypeScript 源码不得 import
   Plugin implementation。移除依赖并重新构建后，Plugin JAR、bean、后台任务和模型工具必须
   全部消失。
8. **复用平台持久化与资源边界**：不要为“已安装/启用”新增数据库行或前端开关。认证凭据使用
   共用 `plugin_credential` 与 `PluginCredentialStore`；会话输入和远端媒体输出使用
   `PluginResourceGateway`。只有 Plugin 自身确有独立 durable domain state 时才设计新的
   schema，不能把安装状态或明文 secret 放入业务表。
9. **补齐验证**：至少覆盖 descriptor/工具身份与 schema、AutoConfiguration bean 装配和
   `AutoConfiguration.imports`、认证去敏与失败状态、Tool side effect/取消/超时、资源预算，
   以及模块依赖方向。架构测试应验证仓库其它模块不 import Plugin implementation，且 Web
   只以 runtime scope 选择 artifact；可参考
   [`MiniMaxMavisAutoConfigurationTest`](../../plugins/minimax-mavis/src/test/java/fun/fengwk/kkstudio/plugin/minimaxmavis/MiniMaxMavisAutoConfigurationTest.java)
   和
   [`MavisPluginModuleArchitectureTest`](../../plugins/minimax-mavis/src/test/java/fun/fengwk/kkstudio/plugin/minimaxmavis/MavisPluginModuleArchitectureTest.java)。

最小检查入口：

```bash
env JAVA_HOME="$JAVA_HOME_21" mvn -B -ntp -pl plugins/<plugin-id> -am test
env JAVA_HOME="$JAVA_HOME_21" mvn -B -ntp -pl web -am test
node scripts/dev/verify/repository/check.mjs
python3 scripts/dev/verify/repository/check-sensitive-data.py
git diff --check
```

修改前端 contract/UI 时，还必须执行 `npm --prefix frontend run test`、`run lint` 与
`run build`。发行物验收使用 `-Pdistribution -pl web -am package`，并确认选中的 artifact
位于 Fat JAR 的 `BOOT-INF/lib`；仅加入 `plugins/pom.xml` 不能满足安装验收。

统一管理 API 为：

```text
GET    /api/plugins
GET    /api/plugins/{pluginId}
POST   /api/plugins/{pluginId}/auth/prepare
POST   /api/plugins/{pluginId}/auth/complete
DELETE /api/plugins/{pluginId}/auth
```

Registry 只路由到已安装 Plugin；未安装 id 返回 not found。认证响应统一设置
`Cache-Control: no-store`，不含 access token、callback URL、加密正文或签名参数。
管理面不接受任意 Plugin JSON schema：当前唯一认证类型是 `DEEP_LINK`，descriptor 只声明
固定 region 候选；`prepare` 请求精确为 `{region}`，`complete` 请求精确为
`{callbackUrl}`。无该能力的 Plugin 不开放 auth 动作。新增认证交互必须先扩展 sealed auth
type、共享 DTO 与静态前端，不能从 JAR 下载或执行动态 UI 代码。

### Plugin credential

`PluginCredentialStore` 是唯一凭据持久化入口。它用部署级 owner-only 32-byte 主密钥对
Plugin opaque JSON 做 AES-256-GCM 认证加密；每次写入使用新的 96-bit 随机 nonce，二进制
envelope 保存格式版本、nonce 与 ciphertext，AAD 绑定 `pluginId + region + formatVersion`，
防止密文被换行复用。主密钥不进 PostgreSQL、SystemSettings、DTO 或日志，多节点必须挂载
同一份 key file。Plugin 未认证时没有行；删除认证即删除行。密文读写、CAS 与 refresh
lease 属于 Platform，Plugin 只能拿到本次调用需要的解密快照，不能取得 repository 或加密
主密钥。key file 缺失、长度错误或认证解密失败时状态投影为 `KEY_UNAVAILABLE`，读取和写入
fail closed；没有 credential 行时仍允许应用启动。

刷新 dispatcher 启动后立即扫描，随后默认每小时 fixed-delay 扫描
`next_refresh_at`，且只领取当前 JVM 已安装 Plugin 的行；未安装 Plugin 的密文保持 dormant。
每行通过短事务 claim `refresh_lease_token / refresh_lease_until`，外部 renewal 在事务外
只发送一次，终态再以 lease token 与 version 围栏写回：

```text
due row
  -> short transaction: claim refresh lease
  -> external refresh exactly once
  -> short transaction:
       success -> replace encrypted payload + expiry + nextRefreshAt
       auth rejection -> REAUTH_REQUIRED
       not sent, or a definitive but unusable renewal result -> REFRESH_FAILED + delayed retry
       sent but no definitive response -> REFRESH_UNCERTAIN
expired in-flight lease -> REFRESH_UNCERTAIN, never reclaim-and-send
```

lease 只解决多节点互斥，不承诺外部 exactly-once：节点在 HTTP 前后崩溃时，其他节点无法
证明请求是否已经发送，因此过期的 in-flight lease 必须收敛为 `REFRESH_UNCERTAIN`，不能
重新 claim。claim 后新的 Tool credential resolution 暂停或返回可重试的
`AUTH_REFRESHING`，不与可能使旧 token 失效的 renewal 并发；已经取得快照的调用允许自然
结束。确定性认证拒绝、token 过期、`REFRESH_UNCERTAIN` 都 fail closed 并要求重新登录。
请求发送后的网络断开或超时不能证明服务端未签发新 token，因此当前 claim 和以后定时扫描
都不重放该 credential 的 renewal。

### Plugin 资源端口

`PluginResourceGateway` 是 Plugin 访问当前 Session Resource 与暂存远端媒体的受控端口。
Platform 在 `fun.fengwk.kkstudio.platform.plugin.resource` 包中提供了开箱即用的生产实现
[`StoragePluginResourceGateway`](../../platform/src/main/java/fun/fengwk/kkstudio/platform/plugin/resource/StoragePluginResourceGateway.java)，
由 [`PluginConfiguration`](../../platform/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginConfiguration.java)
通过 `@ConditionalOnMissingBean` 自动装配。它依赖 `HarnessStore`、`SessionBlobRefManager`、
`StorageBlobManager`、`StorageUploadService` 与 `PluginProperties`，属于 Platform 自带的基础设施，
不依赖任何具体 Plugin（没有 Plugin 时只是没有调用方）。

端口由两条互不信任的窄路径组成：

1. **会话资源受控下载（`resolveSessionResource`）**：调用方必须显式传入 Harness Thread id
   （来自 Tool invocation context）。实现经 `HarnessStore.transaction` 执行
   `findThread(threadId).sessionId()` 解析 Session，只用 `SessionBlobRefManager.contains(sessionId, blobId)`
   授权当前 Session 是否确实持有该 blob 的引用，并通过 `StorageBlobManager.presignOriginalUrl`
   返回短期受控 GET 地址（绝对 HTTPS URI 且无 userinfo）。资源 URI 形态严格匹配
   `kkstudio:/resources/<小写规范 UUID>`；任何缺少 threadId、Thread 不存在、Session 未引用该 blob
   或已被删除的情况，确定性抛出 `PluginResourceUnavailableException`，绝不降级为未鉴权下载。
2. **远端媒体安全校验与有界暂存（`stageRemoteMedia`）**：
   - **地址准入与 SSRF 防护**：远端媒体地址必须是绝对 HTTPS URI，拒绝 userinfo、fragment 与空 host。
     `PublicAddressPolicy` 逐个校验解析出的所有 IPv4/IPv6 地址：私有网段（RFC 1918）、保留网段（0/8、240/4）、
     环回（127/8）、链路本地（169.254/16、fe80::/10）、组播、运营商级 NAT（100.64/10）、测试网段，
     以及 IPv6 ULA（fc00::/7）、文档段（2001:db8::/32）、Teredo（2001::/32）、6to4（2002::/16）、
     NAT64（0064:ff9b::/32）和内嵌内网 IPv4 的 IPv4-mapped/compatible 地址全部拒绝；解析出的地址中
     任一条不属于公网即整体失败，杜绝部分公网放行。
   - **传输与预算控制**：底层基于 JDK `HttpClient` 的 `JdkRemoteMediaTransport` 固定
     `Redirect.NEVER`（3xx 直接判定失败，杜绝跳转绕过地址准入）；单次连接超时受 `connect-timeout`
     约束，整体响应与读取期限受 `request-timeout` 约束。`Content-Length` 与实际读取字节数双向校验，
     声明超出 `max-bytes`（默认 256 MiB，硬上限 1 GiB）或实际读取超限均立即失败。
   - **流式落盘与权威嗅探**：内容边流式写临时文件（默认位于 `java.io.tmpdir`，可通过 `temp-directory`
     配置绝对路径）边计算 SHA-256 摘要，不把大媒体读入堆内存。媒体类型由 `MediaTypeSniffer` 优先
     按头部 magic 特征判定，无法判定时回退到响应规范化 `Content-Type`（小写 `type/subtype`）；且权威类型
     必须属于调用方声明的 `PluginMediaFamily`（`IMAGE`、`AUDIO`、`VIDEO`、`DOCUMENT`），否则拒绝并清理临时文件。
   - **暂存落库**：调用 `StorageUploadService.reserve` 生成上传记录；仅当状态为 `PENDING` 时，才用
     预签名 PUT 流式上传临时文件（原样回传 signed headers，但过滤 `Host`、`Content-Length` 等客户端受限头，
     受 `upload-timeout` 约束，要求返回 2xx）；上传完成后调用 `StorageUploadService.complete`；
     只有确认为 `READY` 且生成 `blobId` 后，才返回 `blob-upload:<uploadId>` 的 `ResourceRef`
     （含权威 size、SHA-256、mediaType 与清洗后的文件名）。
   - **失败清理与审计**：任何步骤失败均以 `PluginResourceUnavailableException` 终结，在 finally 中删除
     临时文件，并 best-effort 调用 `StorageUploadService.delete(uploadId)` 回收孤儿上传。日志只记录
     uploadId、字节数、媒体类型与族，绝不记录预签名 URL 或响应头。

运维配置位于 `kk-studio.plugins.resource.*`，包含连接超时 `connect-timeout`（默认 `5s`）、请求与读取整体期限
`request-timeout`（默认 `30s`）、预签名 PUT 上传期限 `upload-timeout`（默认 `5m`）、单次暂存字节上限
`max-bytes`（默认 `256MiB`，硬上限 `1GiB`）以及临时目录 `temp-directory`（默认空）。在 Tool 执行面，除凭据缺失的
`KEY_UNAVAILABLE` 外，资源相关失败统一收敛为插件资源不可用（如 Mavis 映射为 `MAVIS_RESOURCE_UNAVAILABLE`），
作为确定性失败不自动重放。

### MiniMax Mavis

`plugins/minimax-mavis` 注册 `minimax-mavis` Studio Plugin 与同名
`HarnessContributor`。它固定使用 CN/EN 两个官方 origin，不接受自定义 base URL。配置页
先按 region 返回 SSO URL；用户完成登录后把 deep link 粘贴给 `auth/complete`。完成端点：

1. 限制 callback 总长与 query 字段数；
2. 只接受 scheme 为 `minimax-cn` / `minimax` 且 authority 精确为 `auth-callback` 的 URI，
   拒绝 path、fragment、重复/空 `accessToken` 与未知 region；
3. 从 JWT 读取数值 `exp` 并拒绝已过期或即将过期的 token；未验签 claim 只用于本地上限，
   不能作为身份事实；
4. 用只读 Mavis capability catalog 在线验证；
5. 生成稳定 client UUID，把 token、client UUID 与取得时间作为一个加密 payload 原子保存。

MiniMax 没有 OAuth `refresh_token`。刷新使用当前 access token 调一次 renewal，严格校验
HTTP、业务状态与新 JWT 后才替换原密文。默认
`nextRefreshAt = min(lastSuccess + 7d, token lifetime midpoint)`，dispatcher 默认每小时检查
一次；`401` / `1004` 进入 `REAUTH_REQUIRED`。callback 与 renewal URL 可能直接携带秘密，
HTTP diagnostics 必须先按敏感 query/header 名去敏，禁止打印原始 request URI、request
body 或响应 token。

模型只看到 15 个静态、可选择的 Tool：

```text
mavis_web_search        mavis_extract_web
mavis_image_search      mavis_reverse_image
mavis_understand_image  mavis_understand_audio  mavis_understand_video
mavis_asr               mavis_list_voices
mavis_tts               mavis_tts_batch
mavis_generate_image    mavis_generate_music
mavis_submit_video      mavis_query_video
```

它们都使用 `EnvironmentSupport.NONE`。认证、capability catalog、临时 CDN 上传与兼容的
同步视频端点不进入模型工具面；媒体准备由调用工具内部完成。理解/生成类输入接受公开
HTTP(S) URL 或当前 Tool invocation 所属 Session 有权访问的
`kkstudio:/resources/<blobId>`。Plugin 通过 `PluginResourceGateway` 把 Resource 解析为
受控短期下载或流，把 Mavis 结果按硬上限流式暂存为既有
`blob-upload:<uploadId>` ResourceRef；统一 ToolResult finalizer 再校验并把 upload owner
原子转移为当前 Session 的 Blob 引用。成功历史只保存稳定 Resource 与有界 JSON，不返回
本地 path 或临时 CDN URL；取消、失败或 finalization 回滚留下的 upload 由既有 Storage
maintenance 回收。

15 个 Tool 在 Catalog 中静态注册，应用启动不访问 Mavis。调用时以短 TTL 缓存的 capability
catalog 把稳定 Tool 映射到当前 provider endpoint；能力缺失返回确定性的
`MAVIS_CAPABILITY_UNAVAILABLE`，不能动态增删 Tool 或改变 schema，以保持 Agent 配置和
Prompt Cache 稳定。

搜索、提取、理解、ASR、音色列表与视频查询声明 `READ_ONLY`；TTS、图片/音乐生成和视频
提交声明 `NON_IDEMPOTENT`。Studio Plugin 为后者提供 baseline `ASK`；PermissionEvaluator
按「Plugin baseline → SystemSettings global rules → SystemSettings tool rules」覆盖，
因此用户显式 DENY/ASK/ALLOW 仍具有最终优先级。批量结果逐项保留 success/failure，部分
成功不能伪装成整体成功；任何生成调用的断连或超时都返回 uncertain failure，不自动重放。

## MCP server 与运行时工具目录

MCP 配置与发现结果保存于 `mcp_server`、`mcp_tool`，唯一身份是 server 的不可变 `name`：
它同时是主键与 HTTP 路径身份，也是模型可见工具名 `mcp_<name>_<tool>` 的组成段。创建与
更新只接受显式 HTTP 字段（URL、headers、enabled、timeout），并严格拒绝任何未知字段，把
状态置为 `UNVERIFIED`；标准列表、详情与更新响应只返回安全投影（name、enabled、
timeoutMillis、discoveryStatus、toolCount、version、时间），URL 与 headers 只有显式
`GET /{name}/config`（强制 `Cache-Control: no-store`）才返回，且 headers 里的
`${VAR}` 占位符保持原样、不回显解析后的凭据。

显式发现在请求线程同步完成：事务外通过 `harness-mcp` Streamable HTTP client 完成握手、
`tools/list` 与 schema/name 校验，再在短事务中 `SELECT ... FOR UPDATE` 锁 server、校验
CAS version 并整体物理替换目录——先清空该 server 的全部工具行，再写入本次发现结果，没有
tombstone、修订号或 `available` 标记。任一步失败都完整保留旧工具行：

- 版本 CAS 不一致抛出 version conflict；
- 发现请求失败或工具名/schema/description 非法时拒绝整批结果，不写任何行；
- 被某个 Agent 引用的工具名将从目录中消失时 fail closed 并抛出 in-use 错误。

因此目录内容与 `AVAILABLE` 状态总是一次发现结果的原子快照。Server 删除同样先做引用校验，
再用 CAS 删除，工具行随 `fk_mcp_tool_server` 级联清理。

动态目录的贡献身份由模型可见工具名派生：`ContributionId` 的 contributor 是
`platform.mcp`，localName 由工具名把 `_` 换成 `-` 得到（模型可见名只含 `[a-z0-9_]`，
该转换一一对应，不引入隐藏 UUID 或修订号）。Agent 侧的唯一身份就是
`mcp_tool.name`；[`McpToolNameNormalizer`](../../platform/src/main/java/fun/fengwk/kkstudio/platform/catalog/mcp/McpToolNameNormalizer.java)
固定命名为 `mcp_<server>_<normalized_source>`，normalize 规则是 lowercase、非
`[a-z0-9]` 字符替换为 `_`、合并连续 `_`、去首尾 `_`，结果必须满足 `ToolDescriptor`
name 语法且 ≤64 字符，超长或同批发现内冲突都直接拒绝，绝不追加 hash 后缀。

[RuntimeToolCatalogConfiguration](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/tool/RuntimeToolCatalogConfiguration.java)
装配三个 bean：包装静态 `HarnessCatalog` 的 `HarnessToolCatalogAdapter`、读库的
动态 `McpToolCatalog`，以及唯一 `@Primary` 的 `CompositeRuntimeToolCatalog`。复合
目录不缓存动态工具，按静态在前、动态在后的固定顺序返回可选项，任何重复模型可见
name（无论 `ContributionId` 是否一致）都 fail closed；`ToolCatalogQueryService`、
`AgentDefinitionConfigValidator`、`DatabaseTurnResolver` 和 `ToolExecutionGateway`
只通过它列出或查找工具。历史投影使用的 `CatalogToolHistoryActionResolver` 只通过
`HarnessToolCatalogAdapter` 按冻结 `ContributionId` 读取静态贡献，以便在持久化前冻结
Tool-owned action；动态 MCP 没有 renderer，该路径不访问数据库。

[McpToolCatalog](../../platform/src/main/java/fun/fengwk/kkstudio/platform/catalog/mcp/runtime/McpToolCatalog.java)
每次调用现读 DB，并把「可选面」与「查找面」分开：`selectableTools()` 是 UI/配置选择入口，只选拔
`enabled=true` 且 `AVAILABLE` 的 server 行与它们的工具行，按模型可见名升序返回；`findTool(name)`
是规划/查找入口，只要求 `mcp_tool` 行与其所属 `mcp_server` 行存在，不按 `enabled` 或发现状态过滤。
因此 server 可用性不会把已持久化定义提前变成 planning 期的 tool-not-found：被禁用、`UNVERIFIED` 或
`FAILED` 的 server 只是不再出现在可选面，其工具行仍是可规划、可冻结的完整定义，失败被推迟到调用期。
[`RemoteMcpExecutableTool`](../../platform/src/main/java/fun/fengwk/kkstudio/platform/catalog/mcp/runtime/RemoteMcpExecutableTool.java)
在受管 `toolGatewayExecutor` 中以 per-call client 执行，`ToolRequirements` 为 none
（不绑定任何 Environment），side effect 是 `NON_IDEMPOTENT`。发送前按工具名重读当前行
并重新校验 server 可选拔状态与工具归属，一次绝对 deadline 覆盖 client 初始化和调用，
取消只作用于当前调用；超时、取消、配置漂移与执行失败只向 Tool result 暴露稳定通用文本，
不泄漏 URL、headers 或内部异常。

## Chat、Project 与 Issue

[ChatServiceImpl](../../platform/src/main/java/fun/fengwk/kkstudio/platform/chat/service/impl/ChatServiceImpl.java)
提供 Chat CRUD，保存 title、agentName、`yoloEnabled`、version 和时间；
Chat 本身不持有 Environment；具体 branch 的环境身份由该 branch 的 `BranchSettings.environmentName` 在每轮 turn 解析，Agent definition 的 `config` 不含任何 Environment 字段。
`deleteChat` 先排他锁定 Chat，再调用
`SessionDeletionOrchestrator.deleteSessionsByOwner(OwnerType.CHAT, chatId)` 深删除
全部 Session，最后删除 Chat 行；`session_owner` 只表达 owner relation，不绕过 Session
的 Harness/Blob 清理。

[ProjectServiceImpl](../../platform/src/main/java/fun/fengwk/kkstudio/platform/project/service/impl/ProjectServiceImpl.java)
与 [IssueServiceImpl](../../platform/src/main/java/fun/fengwk/kkstudio/platform/project/service/impl/IssueServiceImpl.java)
提供 Project/Issue 的事务边界。Project 持有 `yoloEnabled`、`maxReviewRejections`、项目内单调 Issue
编号、CAS `version` 与归档状态，没有 Coordinator Agent；Issue 持有七态生命周期、可空
assignee/reviewer Agent、`version` 与归档状态。状态迁移白名单由
[IssueStatusTransition](../../platform/src/main/java/fun/fengwk/kkstudio/platform/project/model/IssueStatusTransition.java)
单点维护，action 与状态的对应关系以该类为准：

```text
需求池 --READY--> 待处理 --START_EXECUTION--> 执行中 --SUBMIT--> 评审中 --APPROVE--> 完成
待处理 <--DEFER-- 需求池         评审中 --REQUEST_CHANGES--> 待处理 / 阻塞
阻塞 --RECOVER--> 待处理         阻塞 --RECOVER_TO_BACKLOG--> 需求池
非终态 --CANCEL--> 已取消         完成/已取消 --REOPEN--> 待处理
```

`BLOCKED`（阻塞）是七态里的独立状态：自动推进已停止、等待人处理，既不参与自动派发，也不等于「待处理
但依赖未满足」。正式打回 `REQUEST_CHANGES` 始终是同一个业务动作，项目阈值只决定它的目标是
待处理还是阻塞；已阻塞的 Issue 不能被自动流程重新唤醒，只能由人恢复或取消。

依赖边只能连接同一 Project 的未归档 Issue，添加时在 Project 图锁下按 UUID 顺序锁两端
并通过递归 CTE 拒绝环；增删依赖推进目标 Issue 的 `version`。
`project_issue_activity` 是每 Issue 单调追加的唯一有序事实流（建单/改要求、定向指示、评论、人工
输入、审查决定、恢复、重试与系统指令），可用 `idempotencyKey` 精确重放，并作为 Agent 的投递游标；
正文约束 `body = btrim(body)`，只对非 `SPEC_CHANGE`/`REVIEW_DECISION` 要求非空。打回次数不落库，
按「最近一次 `RECOVERY`/`SPEC_CHANGE` 之后、`REVIEW_DECISION` 且 `REQUEST_CHANGES` 的不同提交 Run」
实时推导。

每个 `(Issue, Agent)` 由
[IssueAgentSession](../../platform/src/main/java/fun/fengwk/kkstudio/platform/project/model/IssueAgentSession.java)
绑定唯一 Session 与工作 Branch，并以 `OwnerType.ISSUE_AGENT_SESSION` 拥有 Harness Session：
归属随 Issue 稳定并跨多次 Run 复用，权限始终随当前 Run 变化。
[ProjectHarnessSessionBootstrapService](../../platform/src/main/java/fun/fengwk/kkstudio/platform/project/session/ProjectHarnessSessionBootstrapService.java)
按 `Project -> Issue -> IssueAgentSession` 锁序，在同一物理事务内原子创建 Session、ROOT、
Thread、首条 Command、Work 与 owner relation；`project_issue_agent_session` 的 Session/Thread
外键是 `DEFERRABLE INITIALLY DEFERRED`，让「先登记归属再接受命令」的引导期自引用在同一事务内闭合，
而 `ON DELETE RESTRICT` 仍立即生效。数据库 `session_owner.session_id` 主键与排他弧 check 保证
Chat、Canvas、IssueAgentSession 三类 owner 全局互斥。

[IssueEvidenceServiceImpl](../../platform/src/main/java/fun/fengwk/kkstudio/platform/project/service/impl/IssueEvidenceServiceImpl.java)
管理 Issue **自有的**公开证据：`project_issue_evidence` 一行是 Issue 持有的一个已发布 Blob 引用
（`origin = EXECUTOR | HUMAN`，PK `(issue_id, blob_id)`），与 Session 引用各自独立计数，不由 Session
生命周期决定。公开只有两条入口，都发生在调用方业务事务内且都不复制字节：

- **执行者 final**：最终答复里出现规范 `kkstudio:/resources/<blobId>`（严格小写、无查询参数；近似形态
  一律不解析——`<blobId>/suffix`、`?query`、`#fragment` 或更长的 token 都整体不算引用，绝不从中截断出一个
  文本里并不存在的标识）且**来源 Run 的 Session 在提交时确实持有该引用**时才发布；任一引用不被持有就让整个
  `issue_submit` 失败，不产生部分证据，URI 本身不是权限凭据。
- **人工附件**：人经 Issue 入口提交已 READY 的 `uploadId`，服务端在同一事务内完成
  `lockReady -> retain Issue 引用 -> delete upload` 的引用转移，展示名取自上传行而不是客户端，并追加一条
  HUMAN `COMMENT` 事实。

发布与授权都是幂等的：证据行已存在时不再 retain（重复交付不双计），授权对当时已绑定的参与者 Session，
以及在**发布之后**才引导的 Session（`ProjectHarnessSessionBootstrapService` 在命令接受后调用
`grantPublishedEvidence`）都只补差集；没有任何通配可见性，未获授权的历史附件仍然不可读。授权失败让引导
整体回滚，不会留下已接受命令却看不到公开证据的 Session。`issue_read` 以规范 URI 与元数据（来源、发布
Run、展示名、发布时间）暴露最新的有界证据窗口；`SessionDeletionOrchestrator` 深删除 Session 只释放该
Session 自己的引用，撤回标记也不回收过去已披露的内容，只有 Issue/Project 深删除才按
`project_issue_evidence -> Issue 引用释放` 的顺序释放 Issue 持有的引用。

[IssueControllerDispatcher](../../platform/src/main/java/fun/fengwk/kkstudio/platform/project/controller/IssueControllerDispatcher.java)
只负责 `project_issue_work` 的短事务 claim、bounded handoff、合并 wake 与 poll；
[IssueReconciler](../../platform/src/main/java/fun/fengwk/kkstudio/platform/project/controller/IssueReconciler.java)
统一按 `Project(FOR UPDATE) -> Issue(FOR UPDATE) -> active Run -> work lease` 锁序与 fencing
每次推进一个有界动作，处理依赖阻塞、Agent executor/reviewer Run、Harness bootstrap/inspection、
输入 continuation、人工等待、deadline、continuation budget、retry/cancel 与人工阻塞/恢复；复用工作
Branch 前先把 Harness Thread 的 YOLO 状态对齐到 Project 的 `yoloEnabled`；`(Issue, Agent)` 归属已
存在时绝不重建 Session，只把本次 Run 的初始消息作为 Run 级幂等命令投递到既有 Thread，且投递前必须
确认该 Branch 已收尾旧模型/工具调用与未消费命令。对齐失败、Branch 未收尾或命令游标过期都让本次
reconcile 整体回滚重试：既不按与项目策略不符的 YOLO 启动 Run，也不把输入排到未收尾的 Branch 上。
通知与 poll 都只是唤醒，
数据库 work 行才是可恢复事实；claim/reconcile 由 lease token 与 claimed wake version 双重围栏，
worker 拒绝、处理失败、节点退出或通知丢失都由归还、延迟重试、lease 过期和 periodic
poll 收敛。

[ProjectHarnessContributor](../../platform/src/main/java/fun/fengwk/kkstudio/platform/project/tool/ProjectHarnessContributor.java)
注册 3 个 INTERNAL 角色工具，按 [ProjectRoleToolType](../../platform/src/main/java/fun/fengwk/kkstudio/platform/project/tool/ProjectRoleToolType.java)
划分归属：Executor 与 Reviewer 都获得 `issue_read`、`issue_request_input`，只有 Reviewer
获得 `issue_review`；[ProjectThreadOwnerResolver](../../platform/src/main/java/fun/fengwk/kkstudio/platform/project/tool/ProjectThreadOwnerResolver.java)
从 Thread 的唯一 owner relation 解析角色与 Issue，不依赖模型自报；
[ProjectRoleContextProjector](../../platform/src/main/java/fun/fengwk/kkstudio/platform/project/tool/ProjectRoleContextProjector.java)
只把可信 Run 元数据（issue/run/role/agent）注入系统指令，Issue 正文与 Activity 一律作为数据读取，
绝不提升为指令。Project 深删除先锁 Project、Issues、Runs 并拒绝活动或 UNKNOWN Run，再按
work -> Agent Session（`deleteSessionsByOwner`）-> Evidence（删证据行并逐行 release Issue 持有的
Blob 引用，必须先于 Run 行，因为 `project_issue_evidence.run_id` 是 RESTRICT FK）-> Runs（先
reviewer 后 executor）-> Activities/Dependencies/Issues -> Project 顺序清理，每个 CAS 删除都检查受影响行数。
这些工具通过
[ProjectHistoryRenderers](../../platform/src/main/java/fun/fengwk/kkstudio/platform/project/tool/ProjectHistoryRenderers.java)
提供历史语义动作：只保留动作与相关 issue/status/dependency 身份，省略
`expected_version` 与 `observed_*` 并发游标、`description`/`summary` 等长正文；无法形成
有意义动作时返回 absent，由 Runtime 中性回退。

## SystemSettings

`system_setting` 恰好一行（存在 `id = 1` 的 check 约束），`config` 是六个必填 section
的 canonical JSON：`tool`、`aiRuntime`、`environment`、`integrations`、`storageMedia`、
`advanced`。[SystemSettings](../../platform/src/main/java/fun/fengwk/kkstudio/platform/settings/SystemSettings.java)
的 canonical constructor 校验字段范围、跨字段关系与启用前提；
`SystemSettingsCodec` 拒绝未知字段、尾随 token、错误类型和缺失 section；
`SystemSettingsSchemaProvider` 是 HTTP 编辑 schema 的唯一 metadata 来源，字段路径与
record component path 对齐。

[SystemSettingsServiceImpl.update](../../platform/src/main/java/fun/fengwk/kkstudio/platform/settings/SystemSettingsServiceImpl.java)
的顺序是：解析 expected version → 严格解码并校验完整聚合 → 行 CAS update → 事务提交
后通知 `SystemSettingsChangeHandler` 权威回读。`SystemSettingsSnapshot` 以 version CAS
替换内存快照，较旧回读不能覆盖较新值，事务回滚不改变快照；数据库 trigger 通过
`system_settings_changed` 通道广播给其它节点。

## Storage、Blob 与 Resource

Storage 把内容身份、owner 引用和对象物理存储分开：

- `storage_blob` 按 `(sha256, size_bytes)` 对 ACTIVE 内容去重，状态为 `ACTIVE` 或
  `DELETING`，`ref_count` 由 `StorageBlobManager` 维护；
- `session_blob_ref` 是 Harness Session 对 Blob 的显式 owner edge，`canvas_resource`
  和 Tool history ingest 通过各自的 owner/service 维护引用；
- `storage_upload` 记录 PENDING/READY 上传、candidate blob、过期时间和 cleanup lease；
- 对象 key 由 [StorageObjectKeys](../../platform/src/main/java/fun/fengwk/kkstudio/platform/storage/StorageObjectKeys.java)
  集中生成：`uploads/{id}/original`、`blobs/{id}/original`、`blobs/{id}/preview.webp`，
  调用方不能选择 bucket。

`StorageBlobManager.retain/release` 与 `SessionBlobRefManager` 都要求
`PROPAGATION_MANDATORY`，因此引用变更必须属于调用方已有事务。引用减到零时，数据库
同一条条件 update 把 Blob 切为 `DELETING`；提交后只唤醒 maintenance，删除顺序是
preview → original → 条件删除 Blob 行。

[StorageUploadServiceImpl](../../platform/src/main/java/fun/fengwk/kkstudio/platform/storage/service/impl/StorageUploadServiceImpl.java)
的上传协议是：

1. `reserve` 在短事务内按 hash/size 命中 ACTIVE 或创建 PENDING upload，未命中再生成
   checksum PUT presign；
2. `complete` 在数据库事务外 HEAD、校验 size/SHA-256、probe 媒体事实并复制 candidate
   object，随后短事务锁 upload 行、做 ACTIVE dedup、设置 blobId；并发 complete 只有
   一个绑定成功；
3. `delete` 在短事务记录 cleanup request，READY upload 同时 release upload owner；
4. `expireOnce` 先用 `SKIP LOCKED` claim 有界批次，事务外幂等删除临时/candidate
   object，再用 cleanup token 做 fenced finalize；删除或 finalize 失败时保留 lease，
   下一次 maintenance 重试。

服务端内容统一调用 `stage(InputStream, maxBytes)`：入口显式拒绝活动事务，先在本地做有界
spool 并单遍计算 size/SHA-256，再以短事务登记 PENDING upload 和 candidate；随后在事务外
执行 PUT、checksum HEAD、probe 与 copy，最后复用 complete 的去重绑定。
因此对象写入后的 crash、媒体校验失败、去重落败和业务消费回滚都保留可由既有
cleanup request/lease 回收的 upload 证据。

[StorageMaintenance](../../platform/src/main/java/fun/fengwk/kkstudio/platform/storage/StorageMaintenance.java)
是 `SmartLifecycle`，拥有单一 daemon scheduled executor，启动立即 wake 并合并并发
wake，同时以 fixed-delay poll 驱动 upload expire 与 DELETING blob sweep；所有 S3 I/O
都在事务外，数据库清理事实是唯一可恢复依据。

Tool 终态结果由
[ToolResultFinalizer](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/tool/gateway/ToolResultFinalizer.java)
统一对所有工具的完整投影做无副作用 plan 与 hard-limit 校验，不按工具名建立旁路。超过
50 KiB 或 2000 行的 Platform 文本结果转为有界 preview 加全局 Blob；模型投影给出
`kkstudio:/resources/<blobId>`，后续由 `read` 分页读取。普通 Backend Tool 的 Binary/Resource
在 plan 阶段从受管 `ResourceStore` 读取并复核 size/SHA-256；全部确定性校验通过后，Gateway
在投递 listener 之前通过统一 stage 转为 `blob-upload`。Daemon Binary 则在终态前通过 invocation-scoped
`RESOURCE_UPLOAD_REQUEST/TICKET/COMMIT` 取得预签名 PUT 并由 Daemon 直传 S3，WebSocket
只携带 `uploadId/mediaType/name/size/sha256/preview`，绝不携带 Base64、二进制帧、
预签名 URL 或 Daemon 本地 URI。

[GlobalStorageToolResultHistoryMaterializer](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/tool/gateway/GlobalStorageToolResultHistoryMaterializer.java)
在调用方 mandatory transaction 中只消费数据库事实：所有 `blob-upload:<uploadId>` 瞬时
引用先 `lockReady`，以权威 `storage_blob` 复核媒体类型/大小/SHA-256，再
`retain session_blob_ref -> delete upload owner` 原子转移引用，durable 名称取上传行。
宿主已验证的文本 metadata 与有界 preview 随转换后的引用进入 history；物化器不读取
`ResourceStore`、不执行 S3 I/O。任何一步失败都使调用方事务回滚，未消费 upload 由过期清理回收。

Platform Plugin 不借 `ResourceStore` 的 `byte[]` 接口搬运大媒体。
`PluginResourceGateway` 用 Thread 解析当前 Session，并在签发输入前校验
`session_blob_ref`；远端输出以有界流写入临时文件，边传输边做
MIME sniff、size budget 与 SHA-256，落库走 `StorageUploadService.reserve` → 预签名 PUT 直传 → `complete`，Tool terminal 返回 `blob-upload:<uploadId>`。之后仍由
同一个 `ToolResultFinalizer` 和
`GlobalStorageToolResultHistoryMaterializer` 完成全量校验与 owner 原子转移，不建立
Plugin 旁路。第三方下载固定禁用自动 redirect；每一跳都必须是 HTTPS、通过 DNS/IP
private-network 拒绝并受 response/time/size budget 约束。这样 2K 视频和长音频不进入 JVM
大数组，也不会把短期第三方 URL 写进历史。

## Environment

Environment 的持久化 Card 保存于 `environment` 表（UUID `id` 为路由主键，全局唯一且
不可变的 `name` 为对外身份）；跨节点 route ownership 由 `environment_connection` 租约保存。每个 JVM 共享
一个 `nodeInstanceId`，[EnvironmentRegistry](../../platform/src/main/java/fun/fengwk/kkstudio/platform/environment/registry/EnvironmentRegistry.java)
以 `(environmentId, ownerNodeId, leaseToken)` 围栏读写数据库权威路由，并实现会话核心
的 `DaemonLeaseStore`。

[Harness Environment Server](harness-environment-server.md) 的
[EnvironmentDaemonServer](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServer.java)
唯一拥有本节点 daemon 会话
状态：连接代际、HELLO/WELCOME/READY/HEARTBEAT 握手推进、在途 invocation 与终态所有权。
[EnvironmentServerConfiguration](../../platform/src/main/java/fun/fengwk/kkstudio/platform/environment/server/EnvironmentServerConfiguration.java)
装配它并提供窄端口实现：`EnvironmentRegistry`（租约围栏）、`EnvironmentRepository`
解析的注册凭据、`EnvironmentSessionListener`、`StorageDaemonResourceTicketService`
（把会话核心的票据端口映射到 `StorageUploadService.reserve/complete/delete`）与
`SystemSettingsSnapshot`（每次判定现读的心跳超时与资源上限）。

Platform 侧的产品适配器只做映射，不持有会话状态：[EnvironmentDaemonGateway](../../platform/src/main/java/fun/fengwk/kkstudio/platform/environment/gateway/EnvironmentDaemonGateway.java)
暴露会话核心与租约实现，供 Web 层 WebSocket transport 与产品查询复用；
[EnvironmentServiceImpl](../../platform/src/main/java/fun/fengwk/kkstudio/platform/environment/service/EnvironmentServiceImpl.java)
在 Product CRUD 上执行 CAS 与引用校验，唯一的可变状态是 `registrationToken` 轮换
（`name` 是不可变身份，不存在改名入口）。每次 READY 在同一条 owner/lease 围栏 SQL 中
推进连接状态并覆盖连接行保留的宿主 metadata（`environment_connection.runtime_info`）；
断开或重新 CONNECTING 只回退状态、绝不清空该保留事实，因此 Daemon 离线后系统提示词
和管理页面仍可读取最后一次已接受的 OS、时区、进程用户、HOME 与可选备注。

每次 READY 与 Package 发布都会异步调用内部 `skill.sync` capability。Daemon 返回每个
Package 的 installed commit 与稳定本地根目录；结果按本次请求严格校验：只接受恰好
`packageName`、`installedCommit`、`localPath` 三个字段的 object，Package 名必须一致、
commit 必须等于该 Package 的 `currentCommit` 且是 canonical 形状、`localPath` 必须是目标
Daemon OS 上的显式绝对路径且以该 Package 根目录结尾，任何缺失、多余或形状不符的值都收敛为
固定的去敏失败摘要。Platform 只在 installed commit 等于 Package `currentCommit` 时向
Prompt 注入 `<data-dir>/skills/<package>/<skill>/SKILL.md`，否则注入 Platform URI。同步
失败不阻塞 Environment 的其它能力，也不回退或覆盖 Daemon 已安装的旧包。
同步按 Package 独立调用并使用固定 deadline：HTTP 发布在数据库提交后立即返回，不等待
任何 Daemon；离线 Environment 跳过，单包失败继续其它包，下次 READY 或显式重试再次
收敛。编排器本身是 Platform 组件，但它的 Spring 组合属于 web 组合根（它依赖 Environment
会话核心的 capability 传输），因此 Platform-only 上下文不会因为缺少该传输而启动失败。

同步结果按 owner/lease fence 写入 `environment_connection.skill_state`。连接、READY、
断开与同步开始/成功/失败同时追加到该行有界的 `recent_events`，只记录结构化、去敏的
运维事实，不转发 Daemon stdout。Environment Card 读取最近一条 WARN/ERROR，详情端点
`GET /api/harness/environments/{id}/events` 返回最近 200 条；打开详情时轮询即可，不为
低频诊断引入独立实时协议。

每个 Environment 的调用只按 `invocationId` 关联：同一 Environment 允许任意数量
capability 并发在途，不同能力之间没有共享槽位，也不存在环境级容量或排队，唯一拒绝
重复的规则是同一 Environment 内重用相同的活动 `invocationId`。发送前按该连接 READY
中冻结的目标 Daemon OS 对 `arguments.workdir` 做纯词法校验，真实存在性、目录类型与
可访问性由 Daemon 判定。物理连接失效不终结在途 invocation：同一 `daemonInstanceId`
重连时以相同 `invocationId` 重放在途 INVOKE；只有身份不同的 Daemon 进程接管或调用方
deadline 才收敛为 uncertain，绝不重发可能已产生副作用的请求。资源上传控制消息同样
绑定 `invocationId`，并以 `transferId` 在调用内幂等关联。

## Model 与 Tool 执行

### Provider 解析与 Model I/O

[ModelExecutionConfiguration](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/model/ModelExecutionConfiguration.java)
为四个 `ProviderType` 注册稳定命名的 `ProviderFactory`，Model I/O 使用 Java 21
virtual-thread-per-task executor。

[DatabaseProviderResolutionService](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/model/DatabaseProviderResolutionService.java)
在每次 Model attempt 按 frozen `providerName` 读取当前 `agent_provider`：

1. 当前 Provider 必须存在，且 `providerType` 等于 durable request 中冻结的 type；
2. 以当前 factory 读取 endpoint、credential、timeout policy 并创建 adapter；
3. 根据当前 Provider cache capability 规范化 durable cache control；
4. 通过 `ProviderResourceMaterializer` 物化当前 attempt 的 Resource：模型输入模态、
   adapter 用户/工具结果能力与 Blob MIME 同时匹配时，图片、音频、视频和 PDF 统一转成
   Base64 data URI；非 PDF 文档、缺失或非 ACTIVE Blob、能力不匹配及 ASSISTANT
   资源使用确定性文本回退，不读取内容、不生成预签名 URL。

持久化只保存自己的 `ResourceMessageContent` / `ProviderResourceBlock`（Blob ID、名称、
有界预览与外部化事实），Base64 只存在于 attempt 的有效请求，durable codec 拒绝
Image/Audio/Video/Document 媒体块。物化保留 assistant 的原生 `replayState`，不改写
签名、加密回放数据、affinity 或源前缀 hash。

[ProviderInlineBlobReader](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/model/ProviderInlineBlobReader.java)
经 `StorageBlobContentService` 读取权威 Blob：短事务 retain/release，事务外通过内部 S3
endpoint 下载原始字节，不使用面向浏览器的 public endpoint。应用侧安全上限为单文件
原始 **100 MiB**、每请求累计 data URI **160 MiB** ASCII 字符，重复引用与嵌套工具结果
均逐次计入预算，超限明确失败而不截断。不可变 Blob 内容的 Base64 使用 Caffeine 缓存：
TTL **5 分钟**、总记账上限 **64 MiB**、单条超过 **8 MiB** 不缓存，所有下载共享
**2 个并发许可**；缓存命中前仍逐次检查 ACTIVE 状态，失败不缓存。以上是应用侧资源
保护，不代表具体模型或供应商允许的附件大小。

四个 Provider（OpenAI Chat、OpenAI Responses、Anthropic、Google）都使用
[`harness-provider`](harness-provider.md) 的原生协议适配器，共享基于 JDK 21
`HttpClient` 的 `modelExecutionTransport`。

### Gateway admission 与两阶段激活

[PlatformModelGateway](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/model/PlatformModelGateway.java)
的 `start` 顺序与结果语义是：

| 阶段 | 结果 |
| --- | --- |
| Provider resolution 抛确定性 `IllegalArgumentException` | `Rejected(INVALID_REQUEST)`，不提交、不重试 |
| Model admission 无 permit | `Busy(busyRetryDelay)` |
| executor 明确 `RejectedExecutionException` | `Busy`，释放 lease |
| executor 抛其它提交异常 | `Indeterminate(TRANSIENT)`，无法证明 transport 是否启动 |
| 成功提交 | `Started(handle)`，callback gate 仍关闭 |

`Handle.activate` 只在 Runtime attach handle 且 durable invocation 已标记 `RUNNING`
后打开 gate；激活前 cancel 会唤醒等待任务、释放 permit 且不触碰 Provider，构造时拒绝
inline executor、`CallerRunsPolicy` 和静默丢弃 policy。Model 与 Tool gateway 共用
[GatewayExecutorSafety](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/GatewayExecutorSafety.java)
作为该构造约束的唯一实现。Provider callback 经单一 FIFO drainer 进入，队列上限
`256`：第一个 terminal 胜出，terminal 后的迟到/重复信号全部丢弃，队列溢出、未知
transport 异常或非 terminal listener 异常统一以一次 `UNKNOWN` 收敛。

[ToolExecutionGateway](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/tool/gateway/ToolExecutionGateway.java)
的 `preflight` 先用 frozen definition 的模型可见 `name` 从 `RuntimeToolCatalog` 恢复
ToolContribution，再要求目录中的完整 definition、contributor provenance 与
requirements 相等；贡献缺失或 definition/requirements 漂移直接生成确定性的
`TOOL_NOT_FOUND` / `TOOL_DEFINITION_MISMATCH` Deny，不进入 permission evaluator。
正常路径按模型可见 tool name、arguments 与单次调用的 `arguments.workdir` 生成 `ALLOW`、
`ASK` 或 `DENY`，不改写 binding/arguments，也不感知 YOLO。`start` 先获取 tool
admission（默认 `kk-studio.harness.execution-admission.tool=64`），再经单一执行路径
校验冻结定义与 requirements、构建隔离所属 Contributor 的只读 `BranchView`（含该 branch 的用户 Goal）与可选
`BoundEnvironment`、提交异步执行并返回两阶段门控 Handle，最后在门控桥校验 effects
归属与声明、完成 managed Resource 外部化并一次性投递。

执行前只解析一次有效执行超时（`Tool.resolveTimeout`），解析结果原样进入 Tool 请求：
缺省时为 definition 的 `defaultTimeout`（`0` 表示该 tool 没有 deadline），拥有 arguments
级超时契约的工具（环境工具族）则用正数 `timeout_seconds` 严格覆盖默认值；下游不再回落
默认值、不做 min clamp、也没有上限。非正数的 arguments 级超时或无法按 descriptor 构造
的请求直接返回 `Rejected(INVALID_REQUEST)`，既不提交 executor 也不重试。

Tool admission 无 permit 或 executor 明确拒绝时返回 `RetryLater`，其它无法证明是否
提交的异常返回 `Indeterminate(EXECUTION_FAILED)`。`Started` 后的 partial 必须非空、
toolCallId 精确匹配、不能携带 Binary/Resource，且 canonical JSON 不超过 `256 KiB`；
terminal result 在 externalize 前校验，成功结果采用 all-or-nothing Resource
externalization，第一个 terminal 后任何迟到信号、其余 Resource 写入和第二个 terminal
都被禁止。`AppendCustomEntry` intent 必须属于自身 Contributor、命中已注册 custom type
且存在声明的 WRITE access。

### turn 解析与 prompt 物化

[DatabaseTurnResolver](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/thread/command/DatabaseTurnResolver.java)
只以 candidate `EntryPath` 的最新 `BranchSettings` 为输入，在每个 live turn 解析：

1. 当前 Agent、Model、Provider、Variant 与 ProviderFactory，并把 Variant 未显式声明的
   输出上限补齐为 Model 全局 `limit.output`；
2. 当前 Environment context：只按 `BranchSettings.environmentName()` 查全局唯一且不可变的
   name 得到路由身份，name 无法解析时确定性返回 `PLANNING_FAILED`；
3. Agent config 中的每个工具 name 都通过 `RuntimeToolCatalog.findTool(name)` 查找并
   校验 selectable；`NONE` 与 `OPTIONAL` 始终保留，`REQUIRED` 在 Branch 未选择
   Environment 时从最终模型工具列表过滤；`requiredEnvironmentId` 与已选环境冲突时在
   planning 阶段确定性返回 `AssistantError.code=PLANNING_FAILED`；
4. SkillRefs、subagents 与内部 `task`；配置 Skill 时确定性注入 `read`；
5. Contributor context projector、system instruction、cache control、context window 与
   output budget。

每个 Tool 都从 `RuntimeToolCatalog` 精确恢复 descriptor。SkillRef 按
`(packageName, name)` 从 Package 当前 JSON 快照解析；缺失引用返回 `PLANNING_FAILED`。
根据当前 Environment 的 installed commit 选择本地稳定路径或 Platform URI，并构造仅供
Prompt 渲染的 name、description、path 三元组。
[AgentPromptComposer](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/task/AgentPromptComposer.java)
拼接正文、当前 Environment、Skill 和 Subagent sections，并只替换已知的 `${date}`
placeholder，其余 `${...}` 与未闭合形式保持原文。Platform 将其与 Project 角色上下文、
Contributor context projector 片段用空行确定性拼接为单条非空 `systemInstruction` 冻结进
`ModelRequestSpec`，会话历史中绝不作为系统消息注入。
Skill 内容由统一 `read` 按稳定 path 读取当前安装版本；ModelRequestSpec 不再保存重复的
Skill binding。

统一 `read` 根据 `path` 路由：`kkstudio:/skills/<package>/<skill>/...` 从 Platform
bare Git cache 的 Package 当前 commit 读取，`kkstudio:/resources/<blobId>` 在校验当前
Session 引用后经 S3 流式读取 Blob 文本并格式化有界行窗口（该引用既可能来自该 Session 自己消费的附件，也
可能来自 Issue 已发布证据的幂等授予，因此 Blob 实际可用时规范形态就是活动形态，不可用时确定性拒绝）（无 8 MiB 源文件上限，单次输出最多 48 KiB），本地绝对路径委托当前 `BoundEnvironment.fs.read`。相对
路径要求显式 `workdir`；`workdir` 只参与同一地址空间内的相对解析，不提供隐藏默认值。
Platform URI 不接受任意 HTTP(S) 透传。

`kkstudio:/resources/<blobId>` 的读取预算在读取入口冻结一次（30 秒），覆盖 S3 `getObject`
握手与响应体消费：剩余预算写入请求级 `apiCallTimeout`（SDK 定时器在响应头返回后即停止，
只能约束握手），响应体侧由读取边界在到点时 abort 响应流，因此在 S3 长期不返回响应头或
缓慢/停滞地发送响应体时都会以明确的读取超时结束，而不是等待 socket 空闲超时或把剩余响应
体读完。读取被取消（线程中断）按取消而非超时失败，并保留中断状态。尚未建立连接的阻塞
（例如 TCP 建连）无法由应用层中止，只能依赖 HTTP 客户端自身的连接超时。

Debug 预览与正式 Turn 复用相同的 Agent/Environment/Tool/Skill 解析纯函数。结构化投影
同时返回最终 systemInstruction、有效与过滤 Tool、Skill 交付路径和 planning error；
活动 ModelInvocation 还可从冻结 Spec 与 request head 物化 canonical ProviderRequest。
已结束 Turn 的 Invocation 行会被删除，因此重新计算的当前预览必须明确标记，不能冒充
历史请求。

`GET /api/harness/threads/{threadId}/model-request-debug` 返回
`HarnessModelRequestDebugDTO`：`NEXT_REQUEST_PREVIEW`、生成时间、model/environment、
systemInstruction、全部候选 Tool（`SENT` / `FILTERED` 与原因）、Skills、Subagents、
cache control、planning error，以及可空的活动 `FROZEN_INVOCATION` canonical request。
该查询不检查 branch HEAD、不发布 Package、不触发 Daemon sync，也不回显 credential 或
Base64 正文。

[AgentBranchSettingsMaterializer](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/task/AgentBranchSettingsMaterializer.java)
为普通 root 按最新 Agent/Model catalog 物化 `agentName` 与 model，并固定
`environmentName=null`；为新建或恢复的 subagent 额外读取被调用 Agent 的
`inheritParentEnvironment`，决定是否采用父 Model invocation 已冻结的
`environmentName`。恢复会话以 `SET_AGENT -> SET_MODEL -> SET_ENVIRONMENT -> USER`
命令前缀收敛完整目标设置。工具、Skill Prompt 输入和 subagent binding 在每个 live turn
重新解析；
非空 `subagents` 始终声明 `task` 并冻结 allowlist，递归深度上限只在实际调用时返回稳定
Tool error，不动态裁剪工具面。Task 的 agent name、description、parent/root/depth 和
invocation 归属在 durable binding 中冻结，执行期间不因 Catalog 变更扩权。Compaction
resolver 是窄路径：只解析 `CompactionPreparation.executionModel`，返回零
tools/subagents 的 ModelRequestSpec。

## Canvas 与 ComfyUI 适配

[PlatformCanvasCommandService](../../platform/src/main/java/fun/fengwk/kkstudio/platform/orchestration/PlatformCanvasCommandService.java)
实现 Canvas application command：`createCanvas/applyCommands/deleteCanvas` 都在事务内；
`applyCommands` 先锁 `canvas_document` 行，再以 `(canvasId, idempotencyKey, requestHash)`
做精确 replay/conflict 并以 `expectedVersion` 推进 graph version；
`CREATE_RESOURCE_NODE` 在同一事务锁定 READY upload、retain Canvas Blob 引用、标记 upload
cleanup 并创建 `canvas_resource`，上传对象由提交后的 Storage Maintenance 清理；Function
run、node、group、link、resource 的状态变化通过 Canvas core port 写入并以 patch 返回。

[PlatformCanvasResourceLifecycle](../../platform/src/main/java/fun/fengwk/kkstudio/platform/canvas/resource/PlatformCanvasResourceLifecycle.java)
的核心不变量是：Resource row 对其 Blob 贡献一个引用，Function pin 只保护无 owner
Resource 而不增加 `ref_count`；run/node/canvas pin 释放后只回收不再被 pin 且无 owner 的
Resource，Function success 用 target 替换 owner resource，失败/cancel/迟到结果只丢弃
unowned target。这使 Resource row、Session ref 和 upload owner 各自只维护一条明确引用
边，任何 owner 删除都必须经过对应 manager。

[CanvasBlobResourceMaterializer](../../platform/src/main/java/fun/fengwk/kkstudio/platform/canvas/resource/CanvasBlobResourceMaterializer.java)
把 Function 输出 spool 到临时目录（上限 512 MiB），在事务外写 `blobs/{blobId}/original`
并 probe 媒体事实，然后在事务内锁 Canvas、确认恰好一个 RUNNING output pin、做 Blob
dedup 和 `resourceId` 幂等 insert；并发落败方释放自身刚创建的 Blob 引用，预览生成在
提交后 best-effort 执行。
[CanvasBlobPreviewService](../../platform/src/main/java/fun/fengwk/kkstudio/platform/canvas/resource/CanvasBlobPreviewService.java)
通过不经 shell 的 `MediaProcessRunner` 调用 ffmpeg 生成 webp，输入上限 512 MiB，超时与
缩略图参数来自 SystemSettings。

Function adapter 只实现 `CanvasFunctionAdapter`：

| adapter | 运行边界 |
| --- | --- |
| [FakeCanvasFunctionAdapter](../../platform/src/main/java/fun/fengwk/kkstudio/platform/canvas/function/fake/FakeCanvasFunctionAdapter.java) | 读取 classpath 的 tiny image/video fixture，仍通过真实 materializer，受 `fake-enabled` property 控制 |
| [GptImage2CanvasFunctionAdapter](../../platform/src/main/java/fun/fengwk/kkstudio/platform/canvas/function/opencli/GptImage2CanvasFunctionAdapter.java) | OpenCLI Hub + `chatgpt-agent`，image reference 每项最多 20 MiB，checkpoint 覆盖 upload/submit/poll/materialize |
| [SeedanceCanvasFunctionAdapter](../../platform/src/main/java/fun/fengwk/kkstudio/platform/canvas/function/opencli/SeedanceCanvasFunctionAdapter.java) | OpenCLI Hub，冻结 reference policy、上传和有界 polling，checkpoint 恢复同一 execution |
| [MiniMaxH3CanvasFunctionAdapter](../../platform/src/main/java/fun/fengwk/kkstudio/platform/canvas/function/h3/MiniMaxH3CanvasFunctionAdapter.java) | Platform one-shot Harness prompt + Environment + ComfyUI，状态阶段覆盖 prompt、Comfy upload/submit/poll 和 Blob ingest |

[PlatformCanvasFunctionBlobAccess](../../platform/src/main/java/fun/fengwk/kkstudio/platform/canvas/function/PlatformCanvasFunctionBlobAccess.java)
是 Function runtime 读取 Blob facts、打开 original stream 和获取 presign 的唯一 Platform
storage adapter；Canvas adapter 不直接拼 S3 key，也不直接管理 Canvas 引用计数。当前
Function 扩展点是 `CanvasFunctionAdapter` + `CanvasFunctionCatalog`，Catalog 在启动装配
时冻结能力快照。

[ComfyuiWorkflowApiServiceImpl](../../platform/src/main/java/fun/fengwk/kkstudio/platform/comfyui/workflow_api/service/impl/ComfyuiWorkflowApiServiceImpl.java)
管理 `comfyui_workflow_api` 卡片；bindings parser 严格验证 workflow JSON、
parameter/file binding、node/input 存在性、value type 和 blobId，只有 enabled workflow
才能进入运行服务。[ComfyuiRuntimeService](../../platform/src/main/java/fun/fengwk/kkstudio/platform/comfyui/ComfyuiRuntimeService.java)
是无状态 runtime：读取 enabled binding 并复制 Workflow、校验并按 JsonPath selector 写入
参数、对 file binding 从全局 Storage 按 blobId 受
`integrations.comfyui.maxInputFileBytes` 限制地读取后上传 ComfyUI、submit 后以 202 返回
`runId == prompt/job id`、get/cancel/download 直接查询 ComfyUI job 且运行状态不落本地表。
ComfyUI client 是否装配由启动时的 `SystemSettings.integrations.comfyui.enabled` 决定。

## 关键流程

### Model attempt

```text
ThreadProcessor
  -> DatabaseTurnResolver -> frozen ModelRequestSpec
  -> DatabaseProviderResolutionService
       -> current agent_provider row + ProviderType adapter
       -> attempt-local Resource materialization
  -> PlatformModelGateway.start -> admission lease -> Started(handle), gate closed
  -> Runtime markRunning + handle.activate
  -> virtual-thread Provider stream
  -> FIFO bridge: delta / thinking / tool call / terminal
  -> Runtime terminal CAS + Work wake
```

### Tool attempt

```text
ToolProcessor
  -> ToolExecutionGateway.preflight -> PermissionEvaluator + arguments.workdir
  -> admission + exact catalog/binding route
  -> Started(handle), gate closed
  -> Runtime markRunning + handle.activate
  -> unified Tool.execute (with BranchView / BoundEnvironment)
  -> bounded FIFO bridge
  -> partial realtime or terminal externalization
  -> Runtime terminal CAS + owning Thread Work
```

### Chat/Canvas 首条带附件消息

```text
HTTP command-batch
  -> HarnessCommandAcceptanceOrchestrator.accept (single physical transaction)
       -> owner KEY SHARE authorization
       -> NEW_SESSION owner relation atomic insert
       -> lock READY upload + retain SessionBlobRef + mark upload cleanup
       -> HarnessRuntime.acceptCommands
  -> 202 durable acceptance -> Work dispatcher / ThreadProcessor
```

owner authorization、Session relation、attachment materialization、Session blob ref 和
Runtime command acceptance 属于同一物理事务，任一失败整体回滚；Runtime replay 不重复
消费 upload，但仍执行 owner authorization。正常接受使用 owner `KEY SHARE`，删除使用
owner 排他锁，深删除统一按 `Owner -> Session -> Thread` 锁序并对跨 Session 的 Thread
按 UUID 排序，避免锁序回退。

### Project Issue 调谐

```text
Project/Issue mutation or controller poll
  -> project_issue_work request / PostgreSQL wake hint
  -> IssueControllerDispatcher claim (short transaction)
  -> bounded worker handoff
  -> IssueReconciler
       -> fenced Project/Issue/Run/work locks
       -> one bounded transition
       -> optional atomic IssueRun Session bootstrap or Harness command
       -> complete/reschedule work
```

### Canvas Function 输出

```text
Function dispatcher claim + RUNNING lease
  -> adapter checkpoint / third-party execution
  -> CanvasBlobResourceMaterializer
       -> spool + S3 put + media probe outside DB transaction
       -> Canvas row lock + Blob dedup + resource insert in transaction
  -> commit -> preview generation best-effort -> Canvas version/patch notification
```

## 配置

配置分三层，边界不可混用：**SystemSettings（数据库单行，可在线修改）**、**部署级
`@ConfigurationProperties`（进程启动边界，不进数据库/DTO/前端）**、**部署 secret
（只在 deployment property）**。

### SystemSettings section

| section | 主要字段与默认值 | 应用时点 |
| --- | --- | --- |
| `tool` | permission 默认 `write`/`edit`/`bash` 各 `* -> ask`，`defaultYolo=false`，Model Busy retry 5s、Tool Busy retry 1s、Tool overload retry 5s | admission/permission 读取点 live |
| `aiRuntime` | retry 3 次、EXPONENTIAL、base 2s、max 60s、compaction keep 20000 tokens、subagent depth 2 / per-parent concurrency 10 / maxTurns 50 | retry、resolver、subagent 配置读取点 |
| `environment` | resource 16 MiB、heartbeat 60s | Environment 单项/聚合上传资源上限与心跳超时读取点 |
| `integrations.comfyui` | disabled；connect 10s、read 30s、WebSocket 1800s、input 50 MiB | client topology 由启动快照决定 |
| `integrations.openCliHub` | disabled、base URL 未配置；connect 5s、request 120s、long poll 130s、JSON 512 KiB、error 4 KiB | adapter 创建与执行参数 |
| `integrations.seedance` / `gptImage2` / `minimaxH3` | 各自 enabled/paid 开关、workspace、prompt 与 ComfyUI timeout、polling 约束 | adapter 的启动快照与执行读取点 |
| `storageMedia` | upload 3600s、presign 默认 600s / 上限 3600s、media process 30s、thumbnail 512 / quality 80 | 上传与预签名生命周期、媒体处理预算 |
| `advanced` | resource 16 MiB；processor lease/heartbeat 30s/10s、失败与回退各 1s；event queue 512 / 2 MiB / 10s、heartbeat 20s；notification poll 5s、reconnect 1s | 组合根装配的 restart-required 软策略 |

SystemSettings 永不承载 Dispatcher 容量与调度节奏、数据库连接、filesystem root、
ffmpeg binary、Daemon token/identity、Provider credential、ComfyUI API key、H3 bearer
token 或 OpenCLI instance identity。

### 部署级 `@ConfigurationProperties`

| key | owner | 边界 |
| --- | --- | --- |
| `kk-studio.harness.dispatcher.*` | platform | Work claim/handoff 租约、轮询、拒绝退避与 bounded worker 容量；默认 `64/30s/1s/1s/16/64`（maxDispatchTasks/lease/poll/rejection/worker/queue） |
| `kk-studio.harness.execution-admission.{model,tool,subagent,skill-sync}` | platform | 进程级容量，默认 `16/64/10/8`；`skill-sync` 约束 Environment Skill Package 同步的并发（单 Environment 串行） |
| `kk-studio.harness.runtime.{workers-enabled,resource-root,skill-cache-root}` | platform | worker 开关、内容寻址 Resource 存储根与 bare Skill Git cache 根（默认位于 `<cwd>/.kkstudio/`） |
| `kk-studio.project.controller.*` | platform | Issue Controller lease 30s、poll 1s、retry 5s、blocked 60s、run 30m、continuation 10、worker `8 + queue 64` |
| `kk-studio.storage.s3.{endpoint,public-endpoint,region,bucket,access-key,secret-key}` | platform | S3/MinIO 服务端与 presign endpoint；bucket 只能由服务端配置 |
| `kk-studio.storage.maintenance.{poll-delay,cleanup-lease}` | platform | maintenance 轮询与 cleanup lease，默认 `30s/5m` |
| `kk-studio.plugins.credential-key-file` | platform | Plugin credential AES-256-GCM 主密钥的 owner-only 绝对文件；各 App 节点内容必须一致，不进入数据库 |
| `kk-studio.plugins.refresh.{poll-delay,lease-duration}` | platform | Plugin credential refresh 扫描与互斥 lease，默认 `1h/2m` |
| `kk-studio.plugins.resource.{connect-timeout,request-timeout,upload-timeout,max-bytes,temp-directory}` | platform | Plugin 资源端口受控下载与暂存边界；默认连接 `5s`、请求与读取整体期限 `30s`、PUT 直传超时 `5m`、单次暂存上限 `256MiB`（硬上限 `1GiB`）、临时目录留空为 `java.io.tmpdir` |
| `kk-studio.canvas.resource.{ffprobe-binary,ffmpeg-binary,temp-dir}` | platform | 媒体处理本地路径 |
| `kk-studio.comfyui.api-key` | platform | ComfyUI secret，非 SystemSettings |
| `kk-studio.opencli-hub.instance-id` | platform | OpenCLI Hub 部署身份 |
| `kk-studio.canvas.function.minimax-h3.comfy-bearer-token` | platform | H3 ComfyUI bearer secret；启用/路由/timeout 仍由 SystemSettings |
| `kk-studio.canvas.function.runtime.*` | canvas-infra | Canvas Function 调度容量、lease、heartbeat 与 poll |
| `kk-studio.harness.environment-gateway.{max-message-bytes,queue-capacity,max-bytes,send-timeout}` | web | Daemon WebSocket 传输安全边界，默认 `16MiB/256/16MiB/10s`；不是 Environment 并发配额 |

S3、Provider、ComfyUI、OpenCLI Hub 和 Environment Daemon 都是明确的
third-party/deployment boundary。Platform 对外只传 domain DTO、稳定错误 kind、签名 URL
和 frozen binding，不会把 bucket、对象 key、credential 或完整上游异常作为产品协议的
一部分。

## 测试入口

先看架构守卫，再按子域定位 integration test：

- [`PlatformPackageArchitectureTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/PlatformPackageArchitectureTest.java)、
  [`PlatformArchitectureTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/PlatformArchitectureTest.java)：
  包依赖方向，Platform 不得引用 `canvas-infra`、`harness-infra`、`web`、`harness-daemon`
  的生产实现。
- [`ProviderTypeArchitectureTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/ProviderTypeArchitectureTest.java)、
  [`HarnessExecutionAdmissionArchitectureTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/HarnessExecutionAdmissionArchitectureTest.java)：
  ProviderType 唯一来源与 admission 构造约束。
- 测试基座：[`PlatformTestApplication.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/PlatformTestApplication.java)、
  [`PostgresSpringTestSupport.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/persistence/test/PostgresSpringTestSupport.java)、
  [`PostgresSchemaSupport.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/persistence/postgresql/PostgresSchemaSupport.java)、
  [`StorageS3TestConfiguration.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/storage/StorageS3TestConfiguration.java)。

按子域定位测试，每个目录都按上面的子域地图组织：

| 子域 | 测试目录 | 代表测试 |
| --- | --- | --- |
| Catalog、MCP | [catalog/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/catalog/)、[catalog/mcp/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/catalog/mcp/) | [`McpServerServiceTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/catalog/mcp/service/McpServerServiceTest.java)、[`AgentDefinitionConfigCodecTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/catalog/definition/configuration/AgentDefinitionConfigCodecTest.java) |
| Chat、跨 owner 事务 | [chat/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/chat/)、[orchestration/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/orchestration/) | [`ChatServiceIntegrationTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/chat/ChatServiceIntegrationTest.java)、[`HarnessCommandAcceptanceOrchestratorTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/orchestration/HarnessCommandAcceptanceOrchestratorTest.java) |
| Project、Issue | [project/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/project/)、[project/controller/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/project/controller/)、[project/session/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/project/session/) | [`IssueReconcilerTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/project/controller/IssueReconcilerTest.java)、[`ProjectHarnessSessionBootstrapServiceTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/project/session/ProjectHarnessSessionBootstrapServiceTest.java) |
| Model、Tool 执行 | [harness/model/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/model/)、[harness/tool/gateway/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/tool/gateway/) | [`PlatformModelGatewayTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/model/PlatformModelGatewayTest.java)、[`ToolExecutionGatewayPreflightTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/tool/gateway/ToolExecutionGatewayPreflightTest.java) |
| turn 解析与 prompt 物化 | [harness/thread/command/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/thread/command/)、[harness/task/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/task/)、[harness/read/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/read/) | [`DatabaseTurnResolverTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/thread/command/DatabaseTurnResolverTest.java)、[`AgentPromptComposerTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/task/AgentPromptComposerTest.java) |
| Environment | [environment/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/environment/)、[environment/registry/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/environment/registry/) | [`EnvironmentRegistryTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/environment/registry/EnvironmentRegistryTest.java)、[`EnvironmentServiceImplTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/environment/service/EnvironmentServiceImplTest.java) |
| Canvas 与 ComfyUI 适配 | [canvas/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/canvas/)、[canvas/function/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/canvas/function/)、[comfyui/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/comfyui/) | [`PlatformCanvasResourceLifecycleTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/canvas/resource/PlatformCanvasResourceLifecycleTest.java)、[`ComfyuiRuntimeServiceTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/comfyui/ComfyuiRuntimeServiceTest.java) |
| Storage、Blob | [storage/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/storage/)、[storage/service/impl/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/storage/service/impl/) | [`StorageUploadServiceIntegrationTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/storage/StorageUploadServiceIntegrationTest.java)、[`StorageUploadCleanupLeaseIntegrationTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/storage/StorageUploadCleanupLeaseIntegrationTest.java) |
| Settings | [settings/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/settings/) | [`SystemSettingsServiceImplTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/settings/SystemSettingsServiceImplTest.java)、[`SystemSettingsServiceIntegrationTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/settings/SystemSettingsServiceIntegrationTest.java) |
| PostgreSQL schema | [harness/persistence/postgresql/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/persistence/postgresql/) | [`PostgresqlSchemaStructureTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/persistence/postgresql/PostgresqlSchemaStructureTest.java)、[`PostgresqlBusinessSchemaTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/persistence/postgresql/PostgresqlBusinessSchemaTest.java) |

这些测试覆盖的是当前 application layer 的可观察 contract：CAS、owner lock order、
Project/Issue 调谐、Blob ref 对账、S3 cleanup lease、Provider/Tool admission、
terminal-once、Environment 路由冻结、Canvas resource pin 与 strict codec。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Common](harness-common.md)、
[Harness Runtime](harness-runtime.md)、[Canvas Core](canvas-core.md)、
[Share](share.md)、[Web](web.md)。
