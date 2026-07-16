package fun.fengwk.kkstudio.core.harness.run.service;

import fun.fengwk.kkstudio.harness.runtime.run.AgentRun;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventDraft;
import fun.fengwk.kkstudio.harness.runtime.run.ToolPreparationPort;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageDraft;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 数据库 Assistant -> Tool 原子事务适配器；返回前禁止任何工具副作用。 */
@Component
public class DatabaseToolPreparationPort implements ToolPreparationPort {
  private final HarnessRunTransactionService transactions;

  public DatabaseToolPreparationPort(HarnessRunTransactionService transactions) {
    this.transactions = Objects.requireNonNull(transactions, "transactions");
  }

  @Override
  public boolean prepare(
      AgentRun claimedRun,
      MessageEntryPayload assistant,
      ModelUsageDraft usageDraft,
      List<ToolCall> toolCalls,
      List<ToolBinding> bindings,
      Path workdir,
      Path environmentRoot,
      List<RunEventDraft> assistantEvents,
      Instant now) {
    return transactions.prepareTools(
        claimedRun,
        assistant,
        usageDraft,
        toolCalls,
        bindings,
        workdir,
        environmentRoot,
        assistantEvents,
        now);
  }
}
