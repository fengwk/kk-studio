/**
 * Environment Daemon 的独立进程边界。
 *
 * <p>Daemon 仅依赖 harness-environment 的 Environment Capability SPI 和 wire 协议。它维护可重连连接与 invocation
 * journal，但不将连接作为执行事实源，且不得反向依赖 Model、Agent、Tool 或 Runtime。
 *
 * <p>Environment 作用域使用 canonical {@code environmentName}（CLI {@code --environment-name}，规范 {@link
 * fun.fengwk.kkstudio.harness.environment.EnvironmentId}）；每个 envelope 只携带该逻辑路由名称并做单字段作用域校验。
 */
package fun.fengwk.kkstudio.harness.daemon;
