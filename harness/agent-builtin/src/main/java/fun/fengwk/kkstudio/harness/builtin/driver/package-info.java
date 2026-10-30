/**
 * Builtin Agent Driver：{@link fun.fengwk.kkstudio.harness.builtin.driver.BuiltinAgentDriver} 实现
 * Kernel 的 {@link fun.fengwk.kkstudio.harness.runtime.driver.AgentDriver}，拥有 Builtin 模型状态、Turn
 * 解析、响应物化与 ToolBatch 收尾。
 *
 * <p>所有方法都在 Runtime 已开启的事务内执行，不开启新事务、不执行 Provider I/O；模型状态经 {@link
 * fun.fengwk.kkstudio.harness.builtin.store.BuiltinStore} 与公共历史写入共享同一物理事务。组合根直接装配本类，Runtime 只经
 * {@code AgentDriver} 语义方法调用。
 */
package fun.fengwk.kkstudio.harness.builtin.driver;
