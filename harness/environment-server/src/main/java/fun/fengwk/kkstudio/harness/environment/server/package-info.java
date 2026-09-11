/**
 * Environment 会话服务端核心：纯 Java 的 {@link
 * fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer} 独占
 * HELLO/WELCOME/READY/HEARTBEAT/INVOKE 等 daemon 协议会话状态、序列号、租约围栏（{@link
 * fun.fengwk.kkstudio.harness.environment.server.DaemonLeaseStore}）以及按 invocation 维度的在途调用生命周期。
 *
 * <p>边界与职责：本包只依赖 JDK、Jackson、harness.common 与 harness.environment；连接注册解析、租约存储、传输通道与 READY
 * 事件均由调用方通过窄端口注入（{@link
 * fun.fengwk.kkstudio.harness.environment.server.DaemonRegistrationDirectory}、{@link
 * fun.fengwk.kkstudio.harness.environment.server.DaemonLeaseStore}、{@link
 * fun.fengwk.kkstudio.harness.environment.server.DaemonChannel}、{@link
 * fun.fengwk.kkstudio.harness.environment.server.EnvironmentSessionListener}），因此核心不感知 Spring
 * 与持久化实现。
 *
 * <p>并发模型：同一 Environment 允许任意数量的 invocation 并发在途，仅以 invocationId 区分；不存在按 Environment
 * 的容量、信号量或排队。每次发送前都以租约围栏复核归属，发送结果不确定时按 fail-closed 断开连接并失败在途调用。
 */
package fun.fengwk.kkstudio.harness.environment.server;
