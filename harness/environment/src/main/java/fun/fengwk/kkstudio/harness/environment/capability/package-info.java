/**
 * 跨 Environment 共享的 Capability 契约与目录。
 *
 * <p>本包定义原子 Environment Capability 的执行描述符、流式结果对象以及在 Environment Root 内执行的请求/监听上下文，独立于模型 Tool
 * 命名与权限展示。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog} 冻结
 * catalog 版本 1 的 15 项原子能力描述符（11 项模型可见 + 4 项管理专用，按稳定顺序索引）； {@link
 * fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapability} 定义 Daemon 侧异步执行 SPI；
 * {@link fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTransport}
 * 定义调用侧传输窄端口， 严格约定发送前异常确定性（busy、unavailable 肯定未执行；uncertain 可能已被接受且禁止重放）以及 {@code PARTIAL* ->
 * exactly one terminal} 顺序与 terminal-once 契约。
 */
package fun.fengwk.kkstudio.harness.environment.capability;
