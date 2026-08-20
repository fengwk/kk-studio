package fun.fengwk.kkstudio.harness.runtime.compaction;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;

import java.util.List;
import java.util.Objects;

/**
 * 由冻结 {@link CompactionStart} Entry IDs 重建的摘要输入：待摘要消息与可选 previous summary。
 *
 * <p>不包含最终大字符串 prompt；调用方再用 {@link CompactionPrompts} 渲染。
 */
public record CompactionSummaryInput(List<AgentMessage> messages, String previousSummary) {

  public CompactionSummaryInput {
    messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
  }
}
