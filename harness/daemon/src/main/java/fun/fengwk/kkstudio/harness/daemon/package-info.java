/**
 * Environment Daemon 的独立进程边界。
 *
 * <p>Daemon 仅依赖 harness-tool 的 Tool SPI 和 wire 协议。它维护可重连连接与 invocation
 * journal，但不将连接作为执行事实源，且不得反向依赖 Model、Agent 或 Runtime。
 */
package fun.fengwk.kkstudio.harness.daemon;
