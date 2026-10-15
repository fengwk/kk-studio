# Platform Plugin API 模块

`platform-plugin-api` 定义插件壳：安装身份、配置与认证声明，以及配置后的运行实例。运行实例按需贡献独立的 Harness / Canvas SPI，并通过作用域上下文使用宿主凭据与资源端口。[Platform](platform.md) 的 runtime 叶负责宿主实现，[Web](web.md) 负责完整应用装配；插件不编译依赖宿主实现。

模块只依赖 [Harness Common](harness-common.md)、[Harness Contributor API](harness-contributor-api.md)、[Canvas Plugin API](canvas-plugin-api.md) 与 Jackson 3 值模型（见 [`pom.xml`](../../platform/plugin-api/pom.xml)）。[`PluginApiArchitectureTest`](../../platform/plugin-api/src/test/java/fun/fengwk/kkstudio/platform/plugin/PluginApiArchitectureTest.java) 锁定该依赖集合，并禁止 Spring、数据库、HTTP 客户端与 Platform runtime 实现进入本模块。

## 定义与配置实例

[`StudioPlugin`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/StudioPlugin.java) 是安装期定义，由插件 JAR 的 auto-configuration 注册。它声明唯一 [`PluginDescriptor`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginDescriptor.java)（pluginId、展示名、代码版本）、配置 Schema、认证形态，并通过 `configure(config, context)` 构建 [`PluginInstance`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginInstance.java)。

实例分别返回冻结的 Harness Contributor 与 Canvas Adapter 列表，两域都可为空；实例只关闭自己创建的资源，不关闭注入的共享客户端或宿主端口。插件贡献不另注册为 Spring bean，因此一个定义只产生一份配置实例和能力来源。Mavis 的 enabled 配置控制是否贡献工具，启用实例保留完整工具集合。

[`StudioPluginRegistry`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/StudioPluginRegistry.java) 是当前 classpath 的安装目录：构造期按 `pluginId` 升序冻结全部 `StudioPlugin`，重复身份即启动失败；目录构造完成后不可变，没有运行时安装或刷新入口。

## 配置与认证声明

[`PluginConfigSchema`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginConfigSchema.java) 复用受控 `InputSchema` 类型，普通字段、唯一默认值与秘密字段分离。每层对象拒绝未知字段；只为缺失字段递归补默认，显式 null 不视作缺失，数字和布尔字符串不做容错，用户数组不与默认数组逐项合并。原始、默认和归一化 JSON 均受 64 KiB UTF-8 预算约束，严格拒绝重复键与尾随内容，数值按 BigDecimal / BigInteger 保真。

[`PluginConfig`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginConfig.java) 只保存普通值和非负修订号；秘密不进入普通配置。宿主在全新库以 Schema 默认值初始化为 revision 1，已有库按完整 manifest 指定的持久 revision 恢复；revision 取该插件最后一次写入的全局 epoch。目录仍在启动时冻结，存取与身份校验见 [Plugin 配置存储](platform.md#plugin-配置存储)。

[`PluginAuthSpec`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginAuthSpec.java) 的封闭形态为 None、StaticKey 和 DeepLink。只有 DeepLink 声明 region 候选并提供 [`PluginAuthHandler`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginAuthHandler.java)；StaticKey 必须引用 Schema 声明的秘密字段。当前 HTTP 交互入口采用 DeepLink。[`PluginCredentialRefresher`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginCredentialRefresher.java) 是独立可选能力，宿主只领取实际声明刷新器的插件凭据。

## 凭据契约

[`PluginCredentialMaterial`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginCredentialMaterial.java) 是 Plugin 交还的待加密材料（region 路由元数据加 opaque `payloadJson`），`toString` 有意省略秘密载荷；[`PluginCredentialSnapshot`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginCredentialSnapshot.java) 是本次调用所需的解密快照，携带行版本、认证代际与原始密文供调用期 CAS，数组访问做防御性拷贝。

[`PluginContext`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginContext.java) 只暴露本插件的 [`PluginCredentialAccess`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/credential/PluginCredentialAccess.java)、可选资源网关与 Prompt 任务/媒体端口。凭据访问没有 pluginId 参数，只能取得本插件快照或作废本次已用凭据；宿主拒绝跨插件快照。

[`PluginCredentialStore`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/credential/PluginCredentialStore.java) 是宿主的唯一凭据存取端口，不通过 Context 交给插件。管理面只看到 [`PluginCredentialProjection`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/credential/PluginCredentialProjection.java)，调用快照不投影到管理面。密码学、CAS、租约与状态投影由宿主实现，读写都 fail closed。

刷新语义由类型化异常区分：[`PluginAuthRejectedException`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginAuthRejectedException.java) 表示服务端确定性拒绝，[`PluginRenewalNotSentException`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginRenewalNotSentException.java) 表示可以证明请求根本没有发出，其它异常都收敛为结果未知；[`PluginCredentialUnavailableException`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginCredentialUnavailableException.java) 携带 [`PluginCredentialUnavailableReason`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginCredentialUnavailableReason.java) 表达 Tool 侧的确定性不可用。

## 资源端口

[`PluginResourceGateway`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/resource/PluginResourceGateway.java) 是 Plugin 与 Storage 之间唯一的窄接口：`resolveSessionResource` 按本次调用显式给出的 threadId 解析 Session 引用并签发受控短期 HTTPS 下载地址，`stageRemoteMedia` 把远端媒体按声明的 [`PluginMediaFamily`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/resource/PluginMediaFamily.java) 有界流式暂存为全局 Blob 上传。授权、HTTPS、重定向与私网校验以及 MIME 嗅探都由宿主实现，任何不满足约束的输入以 [`PluginResourceUnavailableException`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/resource/PluginResourceUnavailableException.java) 终结。

[`SessionResourceUri`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/resource/SessionResourceUri.java) 定义平台托管资源 URI 的规范形态 `kkstudio:/resources/<canonical-uuid>`，只接受完整规范形态，并在自由文本中按边界识别内嵌 URI；URI 只是指向全局 Blob 的标识，不是权限凭据。

## Prompt 任务与媒体端口

[`PluginPromptTasks`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/prompt/PluginPromptTasks.java) 让插件提交一次受信 system 文本与有序内容的 Prompt 任务：`submit` 返回可持久化的任务句柄，`await` 在调用方仍活跃且未超时前观察终态报告，`stop` 结束任务。宿主负责创建 root Session/Thread、把 system 文本包成前导提醒、与任务的固定回执在同一事务内接受源 Prompt；任务句柄在重启后仍指向同一次 join 与同一份冻结结果。

[`PluginPromptResources`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/prompt/PluginPromptResources.java) 是本次调用独有的媒体暂存端口：`stage` 把插件持有的字节有界暂存为受校验的上传并返回 `StagedResource` 句柄，`discard` 主动放弃句柄。句柄不跨调用复用；宿主只按句柄的持久身份在接受事务内把它物化为 durable RESOURCE，插件无权构造存储引用。

[`PluginPromptContent`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/prompt/PluginPromptContent.java) 是 Prompt 内容的封闭形态：可信文本，或引用已暂存媒体的句柄。端口只使用 JDK 类型，Harness 消息结构、存储上传与 blob 引用都不出现在插件契约里。

---

上级：[系统设计](../system-design.md)。相关文档：[Platform](platform.md)、[Web](web.md)、[Harness Common](harness-common.md)。
