# Platform Plugin API 模块

`platform-plugin-api` 是 Plugin JAR 与宿主共享的纯契约模块：构建期 Plugin 通过它声明安装身份、认证交互、凭据刷新与资源访问端口，宿主把这些端口接到数据库、加密主密钥、HTTP 与 Storage 实现上。宿主实现与具体装配位于 [Platform](platform.md) 的 runtime 叶；本模块不引入 Spring、数据库或 HTTP 客户端，Mavis 通过它使用宿主端口，无需依赖完整宿主实现。

模块唯一生产依赖是 [Harness Common](harness-common.md) 的 `JsonValues` 与 `ResourceRef` 值契约，JUnit 位于 test scope（见 [`platform/plugin-api/pom.xml`](../../platform/plugin-api/pom.xml)）。[`PluginApiArchitectureTest.java`](../../platform/plugin-api/src/test/java/fun/fengwk/kkstudio/platform/plugin/PluginApiArchitectureTest.java) 扫描全部主源码，禁止 JDK 与 harness-common 之外的包前缀，并断言 POM 的生产依赖精确只是 harness-common。

## 安装身份与 SPI

[`StudioPlugin`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/StudioPlugin.java) 由 Plugin JAR 自己的 auto-configuration 注册为 bean，声明 [`PluginDescriptor`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginDescriptor.java)（全局唯一且不可变的 `pluginId`、展示名、版本与固定 region 候选）以及两个可选能力：[`PluginAuthHandler`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginAuthHandler.java) 提供封闭的 `DEEP_LINK` 认证交互，[`PluginCredentialRefresher`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginCredentialRefresher.java) 用当前快照换取新凭据材料。两者缺席时对应管理动作与后台刷新都不开放。

[`StudioPluginRegistry`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/StudioPluginRegistry.java) 是当前 classpath 的安装目录：构造期按 `pluginId` 升序冻结全部 `StudioPlugin`，重复身份即启动失败；目录构造完成后不可变，没有运行时安装或刷新入口。

## 凭据契约

[`PluginCredentialMaterial`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginCredentialMaterial.java) 是 Plugin 交还的待加密材料（region 路由元数据加 opaque `payloadJson`），`toString` 有意省略秘密载荷；[`PluginCredentialSnapshot`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginCredentialSnapshot.java) 是本次调用所需的解密快照，携带行版本与原始密文供调用期 CAS，数组访问做防御性拷贝。

[`PluginCredentialStore`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/credential/PluginCredentialStore.java) 是唯一的凭据存取端口：管理面只看到 [`PluginCredentialProjection`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/credential/PluginCredentialProjection.java) 安全投影，执行端取得含秘密的调用快照，不把它投影到管理面。密码学、CAS、租约与状态投影由宿主实现，读写都 fail closed。

刷新语义由类型化异常区分：[`PluginAuthRejectedException`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginAuthRejectedException.java) 表示服务端确定性拒绝，[`PluginRenewalNotSentException`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginRenewalNotSentException.java) 表示可以证明请求根本没有发出，其它异常都收敛为结果未知；[`PluginCredentialUnavailableException`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginCredentialUnavailableException.java) 携带 [`PluginCredentialUnavailableReason`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/PluginCredentialUnavailableReason.java) 表达 Tool 侧的确定性不可用。

## 资源端口

[`PluginResourceGateway`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/resource/PluginResourceGateway.java) 是 Plugin 与 Storage 之间唯一的窄接口：`resolveSessionResource` 按本次调用显式给出的 threadId 解析 Session 引用并签发受控短期 HTTPS 下载地址，`stageRemoteMedia` 把远端媒体按声明的 [`PluginMediaFamily`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/resource/PluginMediaFamily.java) 有界流式暂存为全局 Blob 上传。授权、HTTPS、重定向与私网校验以及 MIME 嗅探都由宿主实现，任何不满足约束的输入以 [`PluginResourceUnavailableException`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/resource/PluginResourceUnavailableException.java) 终结。

[`SessionResourceUri`](../../platform/plugin-api/src/main/java/fun/fengwk/kkstudio/platform/plugin/resource/SessionResourceUri.java) 定义平台托管资源 URI 的规范形态 `kkstudio:/resources/<canonical-uuid>`，只接受完整规范形态，并在自由文本中按边界识别内嵌 URI；URI 只是指向全局 Blob 的标识，不是权限凭据。

---

上级：[系统设计](../system-design.md)。相关文档：[Platform](platform.md)、[Web](web.md)、[Harness Common](harness-common.md)。
