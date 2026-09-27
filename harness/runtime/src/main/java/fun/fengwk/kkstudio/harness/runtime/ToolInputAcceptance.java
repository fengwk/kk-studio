package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInputReceipt;

import java.util.Objects;
import java.util.UUID;

/**
 * 一次人工输入提交被接受后的权威事实。
 *
 * <p>人工输入接受后，ToolInvocation 行会在结果物化进 Entry 历史时被物理删除；统一返回本记录让「刚接受」与「结果已物化的重试」 呈现同一份事实：owning
 * Thread、原 ToolInvocation ID、durable 回执（submissionId / actor / acceptedAt）。{@code materialized}
 * 区分本次是否由已物化的历史回执 replay（true）还是由当前等待调用接受或 replay（false）。
 */
public record ToolInputAcceptance(
    UUID threadId, UUID toolInvocationId, ToolInputReceipt receipt, boolean materialized) {

  public ToolInputAcceptance {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(toolInvocationId, "toolInvocationId");
    Objects.requireNonNull(receipt, "receipt");
  }
}
