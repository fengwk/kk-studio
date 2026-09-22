/**
 * 跨 Environment 共享的 Capability 契约与目录。
 *
 * <p>本包定义原子 Environment Capability 的执行描述符、流式结果对象以及请求/监听上下文，独立于模型 Tool 命名与权限展示。执行位置由每次请求按 schema
 * 显式携带；需要 workdir 的能力必须提供目标系统上的绝对路径，不存在隐式执行根目录、默认目录或跨调用继承。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog} 是当前
 * catalog 版本、原子能力描述符及 workdir 要求的唯一事实源，并按稳定 canonical 顺序暴露； {@link
 * fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapability} 定义 Daemon 侧异步执行 SPI；
 * {@link fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTransport}
 * 定义调用侧传输窄端口， 严格约定发送前异常确定性（busy、unavailable 肯定未执行；uncertain 可能已被接受且禁止重放）以及 {@code PROGRESS* ->
 * exactly one terminal} 顺序与 terminal-once 契约。
 */
package fun.fengwk.kkstudio.harness.environment.capability;
