package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.api.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.api.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.AssistantError;
import fun.fengwk.kkstudio.harness.api.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.api.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.EntryPayload;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoinOutcome;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.harness.api.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.ThreadInputDemand;
import fun.fengwk.kkstudio.harness.runtime.ThreadLifecycleCoordinator;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionResultEvaluator;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverRegistry;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinProjector;
import fun.fengwk.kkstudio.harness.spi.driver.AgentDriver;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.ClaimLostSignal;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;

import java.time.Instant;
import java.util.UUID;

/**
 * owner 自己持有的开放 Turn 的结算：把已冻结的 Join 结果写回该 Turn 并决定后续 Work。
 *
 * <p>当前唯一的 owner-settled Turn 是 COMPACTION：摘要成功则写入 COMPACTION 结果并续作 owner 模型，子执行取消 / 失败则落
 * AssistantErrorPayload。此处不含锁获取，调用方必须已持有该 Thread 行锁与 Claim。
 */
final class OwnerTurnSettlement {

  private final DriverRegistry driverRegistry;
  private final ThreadLifecycleCoordinator coordinator = new ThreadLifecycleCoordinator();

  OwnerTurnSettlement(DriverRegistry driverRegistry) {
    this.driverRegistry = driverRegistry;
  }

  /**
   * 开放 Turn 的结算票据 id：只有 owner 自身持有的 COMPACTION Turn 携带 {@link JoinDelivery#SETTLE_OWNER_TURN} 票据（由
   * {@link CompactionStart#joinInvocationId()} 冻结）；其它 Turn 返回 null。
   */
  static UUID ticketId(ThreadState thread, TurnStartPayload start) {
    if (start.reason() == TurnStartReason.COMPACTION
        && start.ownerThreadId().equals(thread.id())
        && start.compaction() != null) {
      return start.compaction().joinInvocationId();
    }
    return null;
  }

  /** SETTLE_OWNER_TURN 交付的处理：票据冻结结果后，owner 的开放 Turn 按其 reason 消费它；票据尚未冻结时只完成 claim 继续等待。 */
  void settle(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      EntryPath path,
      TurnStartPayload start,
      UUID ticketId,
      Entry openTurn,
      Instant now) {
    ThreadJoin join =
        tx.joins()
            .find(ticketId)
            .orElseThrow(
                () -> new IllegalStateException("owner ticket " + ticketId + " not found"));
    if (join.delivery() != JoinDelivery.SETTLE_OWNER_TURN) {
      throw new IllegalStateException("ticket " + ticketId + " does not settle the owner turn");
    }
    if (!join.matched()) {
      completeClaim(tx, claim);
      return;
    }
    switch (start.reason()) {
      case COMPACTION -> settleCompactionTurn(
          tx, claim, thread, path, start.compaction(), join, openTurn, now);
      default -> throw new IllegalStateException(
          "turn reason " + start.reason() + " has no owner-settled ticket");
    }
  }

  /** 压缩 Turn 的结算：评估冻结的摘要并推进父 Thread 历史；子执行失败则落 AssistantErrorPayload。 */
  private void settleCompactionTurn(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      EntryPath path,
      CompactionStart start,
      ThreadJoin join,
      Entry openTurn,
      Instant now) {
    ThreadJoinReceipt receipt =
        ThreadJoinProjector.INSTANCE
            .project(tx, join)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "matched compaction join produced no receipt: " + join.invocationId()));
    EntryPayload resultPayload = resultPayload(tx, join, receipt, start);
    TurnEndOutcome outcome = outcomeOf(resultPayload, receipt);
    boolean continueModel = outcome == TurnEndOutcome.COMPLETED;
    UUID resultEntryId = tx.nextId();
    tx.entries()
        .insert(
            new Entry(
                resultEntryId, path.root().sessionId(), path.head().id(), resultPayload, now));
    UUID turnEndId = tx.nextId();
    tx.entries()
        .insert(
            new Entry(
                turnEndId,
                path.root().sessionId(),
                resultEntryId,
                turnEnd(openTurn, join, outcome, continueModel),
                now));
    ThreadState advanced = thread.advanceHead(turnEndId, now);
    tx.threads().update(advanced);
    requestNextWork(tx, claim, thread, advanced, turnEndId, outcome, continueModel, now);
  }

  /** 把票据结果折成一个结果 payload：取消 / 失败落 AssistantError，成功落压缩评估结果。 */
  private static EntryPayload resultPayload(
      HarnessStore.Transaction tx,
      ThreadJoin join,
      ThreadJoinReceipt receipt,
      CompactionStart start) {
    if (receipt.outcome() == ThreadJoinOutcome.CANCELLED) {
      return new AssistantErrorPayload(
          new AssistantError(AssistantError.CANCELLED_CODE, "compaction branch was stopped"), null);
    }
    if (receipt.outcome() != ThreadJoinOutcome.COMPLETED) {
      String errorMessage =
          receipt.error() != null ? receipt.error() : "compaction child thread failed";
      return new AssistantErrorPayload(new AssistantError("COMPACTION_FAILED", errorMessage), null);
    }
    return CompactionResultEvaluator.evaluate(
        receipt.report(), finalAnswerMetadata(tx, join), start.outputBudget());
  }

  private static TurnEndOutcome outcomeOf(EntryPayload resultPayload, ThreadJoinReceipt receipt) {
    if (receipt.outcome() == ThreadJoinOutcome.CANCELLED) {
      return TurnEndOutcome.STOPPED;
    }
    return resultPayload instanceof CompactionPayload
        ? TurnEndOutcome.COMPLETED
        : TurnEndOutcome.FAILED;
  }

  /** 子执行最终答案的 assistant 元数据；任一侧缺失时为 null。 */
  private static AssistantMessageMetadata finalAnswerMetadata(
      HarnessStore.Transaction tx, ThreadJoin join) {
    if (join.terminalEntryId() == null || join.finalAnswerEntryId() == null) {
      return null;
    }
    EntryPath childPath = tx.entries().loadPath(join.terminalEntryId());
    for (Entry entry : childPath.entries()) {
      if (entry.id().equals(join.finalAnswerEntryId())
          && entry.payload() instanceof MessagePayload message) {
        return message.assistantMetadata();
      }
    }
    return null;
  }

  private static TurnEndPayload turnEnd(
      Entry openTurn, ThreadJoin join, TurnEndOutcome outcome, boolean continueModel) {
    return new TurnEndPayload(
        openTurn.id(),
        outcome,
        continueModel,
        outcome == TurnEndOutcome.FAILED
            ? TurnEndReason.TURN_FAILED
            : outcome == TurnEndOutcome.STOPPED ? TurnEndReason.USER_STOP : null,
        outcome == TurnEndOutcome.STOPPED ? join.invocationId() : null);
  }

  /** 结算后的 Work 决策：STOPPED 父 Thread 只固化压缩结果，不请求任何 Work、不续作（恢复仍由显式输入驱动，与已停止父的 task 回执交付一致）。 */
  private void requestNextWork(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      ThreadState advanced,
      UUID turnEndId,
      TurnEndOutcome outcome,
      boolean continueModel,
      Instant now) {
    boolean stopped = thread.executionControl().isStopped();
    boolean hasQueuedDemand =
        ThreadInputDemand.hasQueuedDemand(tx.commands().loadQueued(thread.id()));
    EntryPath endPath = tx.entries().loadPath(turnEndId);
    AgentDriver driver = driverRegistry.of(endPath.baseSettings());
    boolean compactionCancelled = outcome == TurnEndOutcome.STOPPED;
    boolean mechanicalWake = !stopped && outcome == TurnEndOutcome.COMPLETED && continueModel;
    boolean requestThread =
        !compactionCancelled && ((hasQueuedDemand && !stopped) || mechanicalWake);
    if (mechanicalWake) {
      coordinator.remindSoftBudgetIfDue(tx, advanced, endPath, now);
    }
    if (outcome == TurnEndOutcome.FAILED && !requestThread) {
      ThreadLifecycleCoordinator.matchAndDeliverTerminalJoins(
          tx, advanced, turnEndId, null, now, false);
    }
    completeClaim(tx, claim);
    if (requestThread) {
      tx.work().request(new WorkTarget(WorkTargetType.THREAD, thread.id()));
    }
  }

  /** 无待做动作的 claim 完成：final fence 后 completeWork。 */
  private static void completeClaim(HarnessStore.Transaction tx, ClaimedWork claim) {
    if (tx.work().lockClaimed(claim).isEmpty()) {
      throw new ClaimLostSignal();
    }
    tx.work().complete(claim);
  }
}
