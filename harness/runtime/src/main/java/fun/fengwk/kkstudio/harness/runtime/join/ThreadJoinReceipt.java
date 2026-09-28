package fun.fengwk.kkstudio.harness.runtime.join;

import java.util.Objects;
import java.util.UUID;

/**
 * 从已匹配的不可变 ThreadJoin 投影出的只读结果凭据。
 *
 * <p>包含本次委派的调用身份、子线程 ID、目标 Agent、终态、从源命令提取的 prompt 原文， 以及本次执行在子线程中产出的最终报告、部分结果或错误详情。
 */
public record ThreadJoinReceipt(
    UUID invocationId,
    UUID childThreadId,
    String agent,
    ThreadJoinOutcome outcome,
    String prompt,
    String report,
    String partialResult,
    String error) {

  public ThreadJoinReceipt {
    Objects.requireNonNull(invocationId, "invocationId");
    Objects.requireNonNull(childThreadId, "childThreadId");
    if (agent == null || agent.isBlank() || agent.length() > 256) {
      throw new IllegalArgumentException("agent must be nonblank and at most 256 characters");
    }
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(prompt, "prompt");
  }

  /** 将本凭据渲染为符合 subagent completion 契约的 XML 消息。 */
  public String renderCompletionXml() {
    return ThreadJoinCompletionRenderer.render(this);
  }
}
