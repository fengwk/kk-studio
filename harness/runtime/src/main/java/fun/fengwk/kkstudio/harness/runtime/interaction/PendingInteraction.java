package fun.fengwk.kkstudio.harness.runtime.interaction;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一个等待人工输入（{@code WAITING_INPUT}）或工具审批（{@code WAITING_APPROVAL}）的 ToolInvocation 的只读投影。
 *
 * <p>它是待处理列表的唯一事实源：携带定位 Interaction 所需的 invocation / thread / session 坐标、冻结的 ToolCall 身份 （{@code
 * toolCallId} / {@code toolName} / {@code argumentsJson}），以及审批等待时的 durable approval JSON。这里只暴露
 * Invocation 自身事实，产品 owner 与 Pane 跳转由上层按 Session/Thread 解析，不在此复制产品归属。
 */
public record PendingInteraction(
    UUID invocationId,
    UUID threadId,
    UUID sessionId,
    ToolInvocationStatus status,
    String toolCallId,
    String toolName,
    String argumentsJson,
    String approvalJson,
    Instant createdAt) {

  public PendingInteraction {
    Objects.requireNonNull(invocationId, "invocationId");
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(status, "status");
    if (status != ToolInvocationStatus.WAITING_INPUT
        && status != ToolInvocationStatus.WAITING_APPROVAL) {
      throw new IllegalArgumentException(
          "pending interaction status must be a waiting status: " + status);
    }
    Objects.requireNonNull(toolCallId, "toolCallId");
    Objects.requireNonNull(toolName, "toolName");
    Objects.requireNonNull(argumentsJson, "argumentsJson");
    if ((status == ToolInvocationStatus.WAITING_APPROVAL) != (approvalJson != null)) {
      throw new IllegalArgumentException(
          "only WAITING_APPROVAL interactions carry an approval payload");
    }
    Objects.requireNonNull(createdAt, "createdAt");
  }
}
