/**
 * Environment Daemon 的独立进程边界。
 *
 * <p>Daemon 仅依赖 harness-tool 的 Tool SPI 和 wire 协议；连接、路径校验与本地 Coding Tool 实现 由后续任务提供，不得反向依赖
 * Model、Agent 或 Runtime。
 */
package fun.fengwk.kkstudio.harness.daemon;
