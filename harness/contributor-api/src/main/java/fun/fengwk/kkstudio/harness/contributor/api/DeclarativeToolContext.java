package fun.fengwk.kkstudio.harness.contributor.api;

import java.time.Instant;
import java.util.Objects;

/**
 * Declarative Tool 的只读执行上下文。
 *
 * <p>{@link BranchView} 精确冻结在产生本次 ToolCall 的 Assistant Entry；Tool 只能返回声明式 intent，不能接触 Store。
 */
public record DeclarativeToolContext(BranchView branch, Instant executedAt) {

  public DeclarativeToolContext {
    branch = Objects.requireNonNull(branch, "branch");
    executedAt = Objects.requireNonNull(executedAt, "executedAt");
  }
}
