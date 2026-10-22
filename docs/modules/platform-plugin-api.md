# Platform Plugin API

`platform-plugin-api` 是插件看到的全部宿主契约：安装身份、配置与认证声明、配置后的运行实例，以及实例可以贡献的两类能力——Harness Contributor（见 [Harness Runtime](harness-runtime.md#contributor-与目录)）与本模块定义的 Canvas Function SPI。实例通过作用域上下文使用宿主凭据与资源端口。[Platform](platform.md) 负责宿主实现，[Canvas](canvas.md) 执行 Function，[Web](web.md) 负责完整应用装配。

模块只依赖 [Common](common.md)、[Harness Runtime](harness-runtime.md) 的 Contributor / Tool 契约与 Jackson 3 值模型（见 [`pom.xml`](../../platform/plugin-api/pom.xml)）。插件源码只能 import 本模块、Contributor / Tool 契约与 Common；这条边界与模块依赖方向一起由仓库检查 [`check.mjs`](../../scripts/dev/verify/repository/check.mjs) 校验。

## 定义与配置实例

[`StudioPlugin`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/StudioPlugin.java) 是安装期定义，由插件 JAR 的 auto-configuration 注册。它声明唯一 [`PluginDescriptor`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginDescriptor.java)（pluginId、展示名、代码版本）、配置 Schema、认证形态，并通过 `configure(config, context)` 构建 [`PluginInstance`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginInstance.java)。

实例分别返回冻结的 Harness Contributor 与 Canvas Function Adapter 列表，两域都可为空；实例只关闭自己创建的资源，不关闭注入的共享客户端或宿主端口。插件贡献不另注册为 Spring bean，因此一个定义只产生一份配置实例和能力来源。Mavis 的 enabled 配置控制是否贡献工具，启用实例保留完整工具集合。

[`StudioPluginRegistry`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/StudioPluginRegistry.java) 是当前 classpath 的安装目录：构造期按 `pluginId` 升序冻结全部 `StudioPlugin`，重复身份即启动失败；目录构造完成后不可变，没有运行时安装或刷新入口。

## Canvas Function SPI

Canvas Function 是插件向 [Canvas](canvas.md) 贡献的显式执行能力。SPI 只描述函数长什么样、Run 冻结了什么、adapter 能做什么；Run 的调度、持久化与资源挂接由 Canvas 应用负责。

宿主在快照装配时用 [`CanvasFunctionCatalog.from(...)`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionCatalog.java) 把内置 adapter 与插件实例的 adapter 冻结成唯一目录：每个 adapter 的 `enabled()`、`unavailableReason()`、`functions()` 各读取一次；function name 重复即失败，集合按字典序排列；enabled adapter 不得声明 unavailable reason，disabled adapter 必须有非空原因。每项目录暴露版本身份 `definitionFingerprint`：按固定字段顺序对完整定义（name、description、argsSchema、outputs、referencePolicy）取 64 位小写 SHA-256，与对象键序、集合顺序无关，只随定义内容变化。

[`CanvasFunctionAdapter`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionAdapter.java) 提供 `functions/enabled/unavailableReason/preflight/submit/execute/cancel`。[`CanvasFunctionDefinition`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionDefinition.java) 声明小写 canonical token 形式的 `name`、`description`、[`CanvasFunctionArgsSchema`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionArgsSchema.java)、输出计划与 [`CanvasFunctionReferencePolicy`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionReferencePolicy.java)：

- 输出计划是 1..8 个 [`CanvasFunctionOutputSpec`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionOutputSpec.java) 槽位，每个槽位声明 `kind`（`TEXT|IMAGE|VIDEO|AUDIO`，见 [`CanvasResourceKind`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasResourceKind.java)）与可选显式资源名：`TEXT` 以内联文本发布，其余以 Blob 发布；显式名在函数内唯一，缺省名由节点名加类型后缀推导。
- 参数 schema 是严格 JSON Schema 子集：根必须是 object，每层 object 都显式 `additionalProperties: false`；关键字支持 `type/description/properties/required/additionalProperties/enum/default/minimum/maximum/items/minItems/maxItems`，类型支持 `object|string|integer|number|boolean|array|resourceReference`；嵌套深度上限 8，array 必须声明 `items` 且 `maxItems <= 32`。
- 参数与 schema 都用 [`CanvasJson`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasJson.java) 表示：严格解析（拒绝重复键、尾随内容与超限嵌套）与规范序列化，JSON 语义相等的值得到相同文本，`MAX_LENGTH` 约束原文长度。

Run 启动时冻结的事实写在 [`CanvasFunctionFrozenRun`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionFrozenRun.java)：canvas/node 身份、保存完整函数定义的 [`CanvasFunctionBinding`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionBinding.java)、args、引用 manifest、按槽位预分配的 [`CanvasFunctionFrozenOutput`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionFrozenOutput.java)、[`CanvasFunctionSubmitState`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionSubmitState.java)、stage 与 adapter state。[`CanvasFunctionFrozenReference`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionFrozenReference.java) 保存启动瞬间的 source node、resource、blob 身份与权威媒体事实，图中的引用关系或源资源之后的变化不会改写它们。

Adapter 两阶段执行：`submit` 提交外部异步任务并返回初始状态；`execute` 查询并按计划顺序物化输出、返回 `run.outputResourceIds()`。[`CanvasFunctionExecutionContext`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionExecutionContext.java) 只给最小能力：`checkpoint(stage, adapterState)`、`isRunning()`、只读冻结原件的 `openOriginal`/`presignOriginal`，以及只接受计划内槽位的 `materializeOutput`（媒体 Blob）与 `materializeTextOutput`（内联文本）；槽位身份或类型与冻结计划不一致时立即拒绝。外部结果无法确认时抛 [`CanvasFunctionUnknownException`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionUnknownException.java)，Run 转入人工决议而不是重试提交。adapter 看不到数据库、对象 key 或 pin 表。

## 配置与认证声明

[`PluginConfigSchema`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginConfigSchema.java) 复用受控 `InputSchema` 类型，普通字段、唯一默认值与秘密字段分离。每层对象拒绝未知字段；只为缺失字段递归补默认，显式 null 不视作缺失，数字和布尔字符串不做容错，用户数组不与默认数组逐项合并。原始、默认和归一化 JSON 均受 64 KiB UTF-8 预算约束，严格拒绝重复键与尾随内容，数值按 BigDecimal / BigInteger 保真。

[`PluginConfig`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginConfig.java) 只保存普通值和非负修订号；秘密不进入普通配置。宿主为新安装的 Plugin 以 Schema 默认值写入 revision 1，此后每次保存推进修订；修订变化时宿主重建插件快照，存取见 [Plugin 配置存储](platform.md#plugin-配置存储)。

[`PluginAuthSpec`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginAuthSpec.java) 的封闭形态为 None、StaticKey 和 DeepLink。只有 DeepLink 声明 region 候选并提供 [`PluginAuthHandler`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginAuthHandler.java)；StaticKey 必须引用 Schema 声明的秘密字段。当前 HTTP 交互入口采用 DeepLink。[`PluginCredentialRefresher`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginCredentialRefresher.java) 是独立可选能力，宿主只领取实际声明刷新器的插件凭据。

## 连接检查能力

[`PluginConnectionCheck`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginConnectionCheck.java) 是实例的可选能力：`PluginInstance.connectionCheck()` 默认返回 `Optional.empty()`，只有显式声明的实例才提供该入口。`check()` 只执行一次用户显式触发的只读探测，复用本实例已有的作用域认证与有界超时，不修改配置、不会录入或更新凭据、不建立浏览器会话，也不做任何自动重试或后台探测；远端确定性拒绝本次凭据（401/403）时按凭据契约调用 `rejectUsed`。

结果是 [`PluginConnectionCheckResult`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginConnectionCheckResult.java) 的三个封闭维度，只陈述能证明的事实：

| 维度 | 取值 | 语义 |
| --- | --- | --- |
| `Reachability` | `REACHABLE` / `UNREACHABLE` / `NOT_CHECKED` / `INDETERMINATE` | 远端是否确实应答；未启用或未发出请求时为 `NOT_CHECKED` |
| `Authentication` | `ACCEPTED` / `REJECTED` / `UNAVAILABLE` / `NOT_CHECKED` | 只有远端确定性拒绝本次凭据才是 `REJECTED`，只有成功合法响应才是 `ACCEPTED`；`UNAVAILABLE` 仅表示本地没有可用凭据，其它故障一律 `NOT_CHECKED` |
| `BrowserSession` | `UNKNOWN` | 浏览器登录态不由本能力核验 |

实现只把已类型化的失败投影为维度，绝不从异常文本猜分类；未定义的运行时失败与进程级 `Error` 原样上抛，既不降级为维度也不伪装成健康结果。结果不携带异常文本、远端 URI、远端正文或凭据。宿主入口是 `POST /api/plugins/{pluginId}/connection-check`，请求体给出 `expectedEpoch` 与 `expectedRevision`，响应只回已应用坐标与上述维度并显式 `no-store`：未安装是 404，实例未声明能力是 400，期望坐标与当前已应用坐标不一致（含尚未应用）是 409，检查本身在数据库事务外、一次已应用租用内执行。管理面的 `connectionCheckSupported` 如实投影本节点已应用实例是否声明该能力。

## 凭据契约

[`PluginCredentialMaterial`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginCredentialMaterial.java) 是 Plugin 交还的凭据材料（region 路由元数据加 opaque `payloadJson`），`toString` 有意省略秘密载荷；[`PluginCredentialSnapshot`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginCredentialSnapshot.java) 是本次调用读取到的凭据快照，携带行版本供调用期按该版本作废（`rejectUsed`）。

[`PluginContext`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginContext.java) 只暴露本插件的 [`PluginCredentialAccess`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/credential/PluginCredentialAccess.java)、可选资源网关与 Prompt 任务/媒体端口。凭据访问没有 pluginId 参数，只能取得本插件快照或作废本次已用凭据；宿主拒绝跨插件快照。

[`PluginCredentialStore`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/credential/PluginCredentialStore.java) 是宿主的唯一凭据存取端口，不通过 Context 交给插件。管理面只看到 [`PluginCredentialProjection`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/credential/PluginCredentialProjection.java)，调用快照不投影到管理面。密码学、CAS、租约与状态投影由宿主实现，读写都 fail closed。

刷新语义由类型化异常区分：[`PluginAuthRejectedException`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginAuthRejectedException.java) 表示服务端确定性拒绝，[`PluginRenewalNotSentException`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginRenewalNotSentException.java) 表示可以证明请求根本没有发出，其它异常都收敛为结果未知；[`PluginCredentialUnavailableException`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginCredentialUnavailableException.java) 携带 [`PluginCredentialUnavailableReason`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginCredentialUnavailableReason.java) 表达 Tool 侧的确定性不可用。

## 资源端口

[`PluginResourceGateway`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/resource/PluginResourceGateway.java) 是 Plugin 与 Storage 之间唯一的窄接口：`resolveSessionResource` 按本次调用显式给出的 threadId 解析 Session 引用并签发受控短期 HTTPS 下载地址，`stageRemoteMedia` 把远端媒体按声明的 [`PluginMediaFamily`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/resource/PluginMediaFamily.java) 有界流式暂存为全局 Blob 上传。授权、HTTPS、重定向与私网校验以及 MIME 嗅探都由宿主实现，任何不满足约束的输入以 [`PluginResourceUnavailableException`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/resource/PluginResourceUnavailableException.java) 终结。

[`SessionResourceUri`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/resource/SessionResourceUri.java) 定义平台托管资源 URI 的规范形态 `kkstudio:/resources/<canonical-uuid>`，只接受完整规范形态，并在自由文本中按边界识别内嵌 URI；URI 只是指向全局 Blob 的标识，不是权限凭据。

## Prompt 任务与媒体端口

[`PluginPromptTasks`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/prompt/PluginPromptTasks.java) 让插件提交一次受信 system 文本与有序内容的 Prompt 任务：`submit` 返回可持久化的任务句柄，`await` 在调用方仍活跃且未超时前观察终态报告，`stop` 结束任务。宿主负责创建 root Session/Thread、把 system 文本包成前导提醒、与任务的固定回执在同一事务内接受源 Prompt；任务句柄在重启后仍指向同一次 join 与同一份冻结结果。

[`PluginPromptResources`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/prompt/PluginPromptResources.java) 提供媒体暂存：`stage` 把插件持有的字节有界暂存为受校验的上传并返回 `StagedResource` 句柄，输入流仍由插件关闭；准备失败且尚未尝试提交时，用 `discard` 放弃句柄。宿主在接受事务内锁定并校验上传身份，为 Session 建立引用后释放上传归属。提交结果不明确时，插件不得推断拒绝并清理或重新提交。

[`PluginPromptContent`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/prompt/PluginPromptContent.java) 是 Prompt 内容的封闭形态：可信文本，或引用已暂存媒体的句柄。端口只使用 JDK 类型，Harness 消息结构、存储上传与 blob 引用都不出现在插件契约里。

---

上级：[系统设计](../system-design.md)。相关文档：[Platform](platform.md)、[Canvas](canvas.md)、[Harness Runtime](harness-runtime.md)、[Web](web.md)。
