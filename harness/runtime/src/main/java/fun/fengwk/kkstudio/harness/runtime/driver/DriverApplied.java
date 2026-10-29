package fun.fengwk.kkstudio.harness.runtime.driver;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Driver 响应物化后的公共事实；Runtime 据此推进 Thread、唤醒与结算 Join。 */
public record DriverApplied(
    UUID headEntryId,
    UUID finalAnswerEntryId,
    boolean terminal,
    boolean continueModel,
    boolean toolPhase,
    TurnEndOutcome outcome,
    List<ToolInvocation> toolInvocations) {

  public DriverApplied {
    headEntryId = Objects.requireNonNull(headEntryId, "headEntryId");
    toolInvocations = List.copyOf(Objects.requireNonNull(toolInvocations, "toolInvocations"));
  }
}
