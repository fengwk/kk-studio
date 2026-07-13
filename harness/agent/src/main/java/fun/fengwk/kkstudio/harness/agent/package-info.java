/**
 * 单个 Assistant Turn 的编排边界。
 *
 * <p>Agent 只将 Model Provider 流聚合为完整 Assistant 输出和 ToolCall，不执行工具、不读写 Session，也不管理 Durable Run。
 */
package fun.fengwk.kkstudio.harness.agent;
