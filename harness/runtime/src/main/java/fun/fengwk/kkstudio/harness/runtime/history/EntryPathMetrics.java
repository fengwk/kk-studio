package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;

import java.util.Objects;

/**
 * 一条 root-to-head 路径的精确计数。
 *
 * <p>{@code INPUT} 与 {@code CONTINUATION} 计入轮次，{@code COMPACTION} 与 {@code STOP} 不计；工具调用只计 {@code
 * ASSISTANT MESSAGE} 中的 {@link ToolCallMessageContent}。
 */
public record EntryPathMetrics(int turnCount, int toolCallCount) {

  public EntryPathMetrics {
    if (turnCount < 0 || toolCallCount < 0) {
      throw new IllegalArgumentException("path metrics must be non-negative");
    }
  }

  /** 内存参考算法；与 {@code ThreadSnapshot} 的现有计数规则相同。 */
  public static EntryPathMetrics from(Iterable<Entry> entries) {
    Objects.requireNonNull(entries, "entries");
    int turns = 0;
    int toolCalls = 0;
    for (Entry entry : entries) {
      Objects.requireNonNull(entry, "entry");
      if (entry.payload() instanceof TurnStartPayload start
          && start.reason() != TurnStartReason.COMPACTION
          && start.reason() != TurnStartReason.STOP) {
        turns++;
      }
      if (entry.payload() instanceof MessagePayload message
          && message.message().role() == AgentMessageRole.ASSISTANT) {
        for (var content : message.message().contents()) {
          if (content instanceof ToolCallMessageContent) {
            toolCalls++;
          }
        }
      }
    }
    return new EntryPathMetrics(turns, toolCalls);
  }
}
