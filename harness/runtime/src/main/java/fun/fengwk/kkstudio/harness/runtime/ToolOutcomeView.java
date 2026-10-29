package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInputReceipt;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次工具调用已经稳定的 terminal outcome 视图，不区分它来自尚存的 terminal Invocation 还是已物化的 ToolResult Entry。
 *
 * @param invocationId 原工具调用 ID
 * @param threadId 严格归属校验通过的 Thread
 * @param assistantEntryId 发起调用的 Assistant Entry
 * @param toolCallId Assistant ToolCall ID
 * @param callIndex 该 Assistant response 内的调用下标
 * @param status durable 生命周期终态；SUCCEEDED 表示执行正常返回，不代表工具业务结果成功
 * @param resultError 模型可见结果的错误标志，与生命周期 status 正交，语义同 ToolResultMessageContent.error
 * @param contents 稳定的 Text / JSON / Session Resource 结果内容
 * @param error 非成功状态的结构化错误；成功状态为 null
 * @param inputReceipt 人工输入回答的 durable 回执；普通工具为 null
 * @param resultEntryId 已物化 ToolResult Entry ID；Invocation 尚未进入 batch 时为 null
 */
public record ToolOutcomeView(
    UUID invocationId,
    UUID threadId,
    UUID assistantEntryId,
    String toolCallId,
    int callIndex,
    ToolResultStatus status,
    boolean resultError,
    List<AgentMessageContent> contents,
    ToolInvocationError error,
    ToolInputReceipt inputReceipt,
    UUID resultEntryId) {

  public ToolOutcomeView {
    invocationId = Objects.requireNonNull(invocationId, "invocationId");
    threadId = Objects.requireNonNull(threadId, "threadId");
    assistantEntryId = Objects.requireNonNull(assistantEntryId, "assistantEntryId");
    if (toolCallId == null || toolCallId.isBlank()) {
      throw new IllegalArgumentException("toolCallId must not be blank");
    }
    if (callIndex < 0) {
      throw new IllegalArgumentException("callIndex must not be negative");
    }
    status = Objects.requireNonNull(status, "status");
    contents = List.copyOf(Objects.requireNonNull(contents, "contents"));
    if (contents.isEmpty()) {
      throw new IllegalArgumentException("contents must not be empty");
    }
    if (status == ToolResultStatus.SUCCEEDED) {
      if (error != null) {
        throw new IllegalArgumentException("succeeded tool outcome must not carry an error");
      }
    } else if (error == null) {
      throw new IllegalArgumentException("non-succeeded tool outcome requires an error");
    } else if (!resultError) {
      throw new IllegalArgumentException("non-succeeded tool outcome requires resultError");
    }
  }

  /** 是否已经物化为 ToolResult Entry。 */
  public boolean materialized() {
    return resultEntryId != null;
  }
}
