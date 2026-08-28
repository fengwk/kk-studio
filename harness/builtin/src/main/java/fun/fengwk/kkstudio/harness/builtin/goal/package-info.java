/**
 * Goal 领域声明式 Tool 与 branch snapshot 协议。
 *
 * <p>本包将 Goal 表达为当前 branch 的 {@code CUSTOM} 全量快照（{@code customType="goal.state"}），并通过三个同步 {@link
 * fun.fengwk.kkstudio.harness.contributor.api.DeclarativeTool} 和一个 {@link
 * fun.fengwk.kkstudio.harness.contributor.api.ContextProjector} 接入 Harness Core。
 */
package fun.fengwk.kkstudio.harness.builtin.goal;
