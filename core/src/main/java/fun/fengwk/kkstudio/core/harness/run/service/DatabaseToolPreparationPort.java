package fun.fengwk.kkstudio.core.harness.run.service;

import fun.fengwk.kkstudio.harness.runtime.run.AgentRun;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventDraft;
import fun.fengwk.kkstudio.harness.runtime.run.ToolPreparationPort;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** T05 barrier 实现；不创建或伪造 Invocation，T06 将在此事务边界补齐它们。 */
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
      List<ToolCall> toolCalls,
      List<RunEventDraft> barrierEvents,
      Instant now) {
    if (toolCalls == null || toolCalls.isEmpty()) {
      throw new IllegalArgumentException("tool preparation requires at least one call");
    }
    return transactions.prepareTools(claimedRun, assistant, barrierEvents, now);
  }
}
