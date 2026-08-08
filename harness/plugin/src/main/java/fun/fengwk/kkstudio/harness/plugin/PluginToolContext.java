package fun.fengwk.kkstudio.harness.plugin;

import java.time.Instant;
import java.util.Objects;

/**
 * 插件 Tool 的只读执行上下文。
 *
 * <p>{@link BranchView} 精确冻结在产生本次 ToolCall 的 Assistant Entry；插件只能返回声明式 intent，不能接触 Store。
 */
public record PluginToolContext(BranchView branch, Instant executedAt) {

  public PluginToolContext {
    branch = Objects.requireNonNull(branch, "branch");
    executedAt = Objects.requireNonNull(executedAt, "executedAt");
  }
}
