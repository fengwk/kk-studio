/**
 * Entry 值类型：branch settings 快照与 turn 边界语义。
 *
 * <p>{@link BranchSettings} 冻结 Agent 引用、{@link ExecutionSelection} 与可空 Environment 名；{@link
 * ModelSelection} 是逻辑模型身份。
 *
 * <p>{@link TurnStartReason} 与 {@link TurnEndOutcome} 描述一次 turn 的打开与关闭语义。
 *
 * <p>Entry payload 层级位于 {@code api.history}，本包不引用 Store、Driver 或运行时控制类型。
 */
package fun.fengwk.kkstudio.harness.api.entry;
