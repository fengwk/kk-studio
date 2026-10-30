/**
 * Builtin 自动压缩策略：按 owned {@code HISTORY} -&gt; hard overflow -&gt; eligible threshold 的顺序计算下一次自动压缩。
 *
 * <p>阈值与规划纯函数复用 {@link fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPlanner} 与 {@link
 * fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig}；压缩回合的父子结构、子 Thread 创建与结算仍由
 * Runtime 掌握。本包只在 {@link
 * fun.fengwk.kkstudio.harness.runtime.driver.AgentDriver#prepareAutomaticCompaction} 内被调用。
 */
package fun.fengwk.kkstudio.harness.builtin.compaction;
