/**
 * Environment Daemon 的独立进程边界。
 *
 * <p>Daemon 仅依赖 harness-tool 的 Tool SPI 和 wire 协议。它维护可重连连接与 invocation
 * journal，但不将连接作为执行事实源，且不得反向依赖 Model、Agent 或 Runtime。每个连接 generation
 * 的首个入站 sequence 建立基线；该 generation 内只接受紧邻的新 sequence 或最新 sequence 的重复，
 * 重连后基线重置。
 */
package fun.fengwk.kkstudio.harness.daemon;
