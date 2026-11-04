package fun.fengwk.kkstudio.harness.runtime.compaction;

import fun.fengwk.kkstudio.harness.api.SpawnRequest;
import fun.fengwk.kkstudio.harness.api.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.codec.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.AgentSpawner;
import fun.fengwk.kkstudio.harness.spi.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.spi.driver.CompactionResolution;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 压缩子执行的启动：自动压缩与手动压缩共用，经统一的 {@link AgentSpawner} 创建子 Thread 与 {@link
 * JoinDelivery#SETTLE_OWNER_TURN} 票据。
 *
 * <p>压缩子 Thread 以 {@link SpawnTrigger#RUNTIME} 创建，不计入子任务并发额度；其冻结约束为：输出上限等于父压缩 turn 冻结的
 * outputBudget、禁用 prompt cache、不允许自动压缩（后代经约束合并同样不允许）。调用方已持有父执行树的锁（含父 Thread）。
 */
public final class CompactionChildSpawn {

  private CompactionChildSpawn() {}

  /** 在调用方事务内创建压缩子执行，返回写入父 TURN_START 的冻结事实。 */
  public static CompactionStart start(
      HarnessStore.Transaction tx,
      ThreadState parentThread,
      CompactionResolution resolved,
      CompactionPreparation preparation) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(parentThread, "parentThread");
    Objects.requireNonNull(resolved, "resolved");
    Objects.requireNonNull(preparation, "preparation");

    UUID sessionId = tx.nextId();
    UUID threadId = tx.nextId();
    UUID ticketId = tx.nextId();
    BranchSettings childSettings = resolved.childSettings();

    String prompt;
    if (preparation.phase() == CompactionPhase.TURN_PREFIX) {
      prompt = CompactionPrompts.turnPrefixUserPrompt(preparation.messagesToSummarize());
    } else {
      prompt =
          CompactionPrompts.summaryUserPrompt(
              preparation.messagesToSummarize(), preparation.previousSummary());
    }
    CustomMessageCommandPayload payload =
        new CustomMessageCommandPayload(AgentMessage.user(prompt));
    String requestHash = ThreadCommandPayloadJsonCodec.requestHash(payload);
    NewThreadCommand command = new NewThreadCommand(payload, ticketId, requestHash);

    int outputCap = (int) Math.min(resolved.outputBudget(), Integer.MAX_VALUE);
    AgentSpawner.spawn(
        tx,
        new SpawnRequest(
            threadId,
            sessionId,
            childSettings,
            List.of(command),
            parentThread.id(),
            null,
            null,
            false,
            new ThreadConstraints(outputCap, true, false),
            SpawnTrigger.RUNTIME,
            JoinDelivery.SETTLE_OWNER_TURN,
            SpawnRequest.Budget.unlimited(),
            ticketId,
            requestHash),
        AcceptancePreflight.IDENTITY);

    return new CompactionStart(
        preparation.phase(),
        preparation.trigger(),
        resolved.execution(),
        resolved.outputBudget(),
        preparation.cutEntryId(),
        preparation.turnPrefixStartEntryId(),
        preparation.historyCompactionEntryId(),
        threadId,
        ticketId);
  }
}
