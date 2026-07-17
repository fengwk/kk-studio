package fun.fengwk.kkstudio.harness.runtime.run;

import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageDraft;
import fun.fengwk.kkstudio.harness.tool.ToolCall;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Assistant Entry、全部 Invocation、WAITING_TOOLS 与相关 RunEvent 的原子事务端口；usageDraft 必须非空。 */
@FunctionalInterface
public interface ToolPreparationPort {
  boolean prepare(
      AgentRun claimedRun,
      MessageEntryPayload assistant,
      ModelUsageDraft usageDraft,
      List<ToolCall> toolCalls,
      List<ToolBinding> bindings,
      Path workdir,
      Path environmentRoot,
      List<RunEventDraft> assistantEvents,
      Instant now);
}
