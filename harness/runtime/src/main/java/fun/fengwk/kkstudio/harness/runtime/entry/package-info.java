/**
 * 共享的 Entry 值类型：branch settings 与 turn 边界引用。
 *
 * <p>本包只保留 history 协议复用的独立值类型：{@link fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings} 保存
 * Environment route identity、Agent 名称引用与 {@link
 * fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection} 等 branch 事实；{@link
 * fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason} / {@link
 * fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome} 描述一次 turn 的打开与关闭语义。Entry payload
 * hierarchy 位于 {@code harness.runtime.history}。
 */
package fun.fengwk.kkstudio.harness.runtime.entry;
