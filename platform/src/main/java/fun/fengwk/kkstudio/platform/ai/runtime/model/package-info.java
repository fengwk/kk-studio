/**
 * 把 Runtime Model 端口绑定到 PostgreSQL provider 资源与注入的 {@link java.util.concurrent.ExecutorService}
 * 的生产适配器。
 *
 * <p>{@link fun.fengwk.kkstudio.platform.ai.runtime.model.PlatformModelGateway} 是 {@link
 * fun.fengwk.kkstudio.harness.runtime.port.ModelGateway} 的生产适配器：它通过 {@link
 * fun.fengwk.kkstudio.platform.ai.runtime.model.ProviderResolutionService} 解析冻结 {@link
 * fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType} 与内存 {@link
 * fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest}，按配置的 timeout 策略打开一个 {@link
 * fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider}，并以 terminal-once 语义把 SDK 流桥接到
 * Runtime listener。gateway 绝不读写 HarnessStore，且不会在 {@code start} 返回前投递任何 listener 回调。
 *
 * <p>{@link fun.fengwk.kkstudio.platform.ai.runtime.model.ModelExecutionConfiguration} 拥有共享虚拟线程
 * executor；busy 重试延迟由 {@link fun.fengwk.kkstudio.platform.ai.runtime.model.PlatformModelGateway}
 * 在每次 start 从 SystemSettingsSnapshot 现读。两个适配器的 Provider I/O 都运行在该 executor 上。
 */
package fun.fengwk.kkstudio.platform.ai.runtime.model;
