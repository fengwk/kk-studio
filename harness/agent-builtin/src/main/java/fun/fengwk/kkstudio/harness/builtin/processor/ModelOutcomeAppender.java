package fun.fengwk.kkstudio.harness.builtin.processor;

import fun.fengwk.kkstudio.harness.api.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.EntryPayload;
import fun.fengwk.kkstudio.harness.api.history.ModelAttemptSnapshot;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.builtin.history.BuiltinHistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.builtin.port.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.history.FinalAnswerEntry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * terminal ModelInvocation 的历史结果物化器。
 *
 * <p>只负责把已提交的 terminal 执行结果落成不可变历史 Entry（ASSISTANT 结果 / 失败 attempt / TURN_END，或在 active Tool phase
 * 仅插入 Tool siblings）并维护 Invocation 行的 attach-then-delete；返回落库事实供调用方决定下一步。绝不推进 Thread head、绝不
 * requestWork、绝不 complete Claim，也不参与 Stop 控制：这些仍由 Runtime 的 claim 调度与 Stop 收敛掌握。
 *
 * <p>{@link #appendModel} 覆盖非 compaction turn 的 terminal Model（Complete / Continue / Failed /
 * ToolBatch / 非成功）。
 */
public final class ModelOutcomeAppender {

  /**
   * 一次 terminal 结果物化后的事实。
   *
   * @param headEntryId 物化后本 Thread 应推进到的 head
   * @param finalAnswerEntryId 冻结的最终回答入口（仅 Complete），否则 null
   * @param terminal 本次执行是否已达不可继续的终止边界（可结算 Join）
   * @param continueModel 关闭的 turn 是否携带 continueModel 义务
   * @param toolPhase 是否进入 active Tool phase（保留 Model 行与 READY siblings）
   * @param outcome 实际写入的 TURN_END outcome（toolPhase 时为 null）
   * @param toolInvocations active Tool phase 新物化的 siblings（供调用方按 READY 槽位请求 Work）
   */
  public record Applied(
      UUID headEntryId,
      UUID finalAnswerEntryId,
      boolean terminal,
      boolean continueModel,
      boolean toolPhase,
      TurnEndOutcome outcome,
      List<ToolInvocation> toolInvocations) {

    public Applied {
      Objects.requireNonNull(headEntryId, "headEntryId");
      toolInvocations = List.copyOf(Objects.requireNonNull(toolInvocations, "toolInvocations"));
    }
  }

  /** 如何关闭本次 turn：写入的 TURN_END 事实、是否已到不可继续的终止边界、是否携带 continueModel 义务，以及结果是否为最终回答。 */
  private record Closing(
      boolean terminal,
      boolean continueModel,
      TurnEndOutcome outcome,
      TurnEndReason reason,
      boolean finalAnswer) {

    static Closing completed() {
      return new Closing(true, false, TurnEndOutcome.COMPLETED, null, true);
    }

    static Closing continued() {
      return new Closing(false, true, TurnEndOutcome.COMPLETED, null, false);
    }

    static Closing failed(TurnEndReason reason) {
      return new Closing(true, false, TurnEndOutcome.FAILED, reason, false);
    }
  }

  private final BuiltinHistoryPayloadMapper payloadMapper = new BuiltinHistoryPayloadMapper();
  private final ModelResponsePlanner responsePlanner = new ModelResponsePlanner();

  /**
   * 非 compaction turn 的 terminal Model：追加 ASSISTANT 结果（+TURN_END 或 Tool siblings）并维护 Invocation 行。
   */
  public Applied appendModel(
      BuiltinStore.Transaction tx,
      EntryPath path,
      ModelInvocation model,
      Instant mutationNow,
      boolean compactionThread) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(model, "model");
    Entry resultEntry = appendResultEntry(tx, path, model, mutationNow);
    if (model.status() != ModelInvocationStatus.SUCCEEDED) {
      return closeTurn(
          tx, model, resultEntry, Closing.failed(TurnEndReason.TURN_FAILED), mutationNow);
    }
    return switch (responsePlanner.plan(
        model.result(), model.requestSpec().toolBindings(), compactionThread)) {
      case ModelResponsePlan.Completed ignored -> closeTurn(
          tx, model, resultEntry, Closing.completed(), mutationNow);
      case ModelResponsePlan.Continue ignored -> closeTurn(
          tx, model, resultEntry, Closing.continued(), mutationNow);
      case ModelResponsePlan.Failed failed -> closeTurn(
          tx, model, resultEntry, Closing.failed(failed.reason()), mutationNow);
      case ModelResponsePlan.ToolBatch batch -> enterToolPhase(
          tx, model, resultEntry, batch, mutationNow);
    };
  }

  /** 先物化失败 attempt 前缀与本次结果 Entry；resultEntryId 仍是 invocation 上未链接的状态。 */
  private Entry appendResultEntry(
      BuiltinStore.Transaction tx, EntryPath path, ModelInvocation model, Instant mutationNow) {
    UUID sessionId = path.root().sessionId();
    UUID parentId =
        ModelAttemptFailureAppender.append(tx, sessionId, path.head().id(), model, false);
    Entry resultEntry =
        new Entry(tx.nextId(), sessionId, parentId, resultPayload(model), mutationNow);
    tx.entries().insert(resultEntry);
    return resultEntry;
  }

  private EntryPayload resultPayload(ModelInvocation model) {
    if (model.status() != ModelInvocationStatus.SUCCEEDED) {
      return payloadMapper.assistantErrorPayload(model.error(), modelAttemptSnapshot(model));
    }
    return payloadMapper.assistantPayload(
        model.result(),
        model.requestSpec().toolBindings(),
        model.actualSelection(),
        model.requestedAt(),
        model.actualEffort());
  }

  /** 关闭 turn：追加 TURN_END，链接结果 Entry 后删除临时 invocation 行。 */
  private Applied closeTurn(
      BuiltinStore.Transaction tx,
      ModelInvocation model,
      Entry resultEntry,
      Closing closing,
      Instant mutationNow) {
    UUID resultEntryId = resultEntry.id();
    UUID turnEndId =
        appendTurnEnd(
            tx,
            resultEntry.sessionId(),
            resultEntryId,
            model.turnStartEntryId(),
            closing.outcome(),
            closing.continueModel(),
            closing.reason(),
            mutationNow);
    tx.updateModelInvocation(model.attachResultEntry(resultEntryId, mutationNow));
    tx.deleteModelInvocation(model.id());
    return new Applied(
        turnEndId,
        closing.finalAnswer()
            ? FinalAnswerEntry.resolve(closing.outcome(), closing.continueModel(), resultEntry)
            : null,
        closing.terminal(),
        closing.continueModel(),
        false,
        closing.outcome(),
        List.of());
  }

  /** active Tool phase：attach resultEntryId 并保留 parent，插入全部 sibling。 */
  private Applied enterToolPhase(
      BuiltinStore.Transaction tx,
      ModelInvocation model,
      Entry resultEntry,
      ModelResponsePlan.ToolBatch batch,
      Instant mutationNow) {
    tx.updateModelInvocation(model.attachResultEntry(resultEntry.id(), mutationNow));
    List<ToolInvocation> invocations =
        materializeSiblings(tx, model, resultEntry.id(), batch, mutationNow);
    return new Applied(resultEntry.id(), null, false, false, true, null, invocations);
  }

  private List<ToolInvocation> materializeSiblings(
      BuiltinStore.Transaction tx,
      ModelInvocation model,
      UUID resultEntryId,
      ModelResponsePlan.ToolBatch batch,
      Instant mutationNow) {
    List<ToolInvocation> materialized = new ArrayList<>(batch.tools().size());
    for (int callIndex = 0; callIndex < batch.tools().size(); callIndex++) {
      ModelResponsePlan.ToolSlot slot = batch.tools().get(callIndex);
      materialized.add(
          new ToolInvocation(
              tx.nextId(),
              model.threadId(),
              model.turnStartEntryId(),
              resultEntryId,
              callIndex,
              slot.call(),
              slot.binding(),
              slot.status(),
              0,
              null,
              null,
              slot.error(),
              mutationNow,
              mutationNow));
    }
    tx.tools().insert(materialized);
    return materialized;
  }

  private static UUID appendTurnEnd(
      BuiltinStore.Transaction tx,
      UUID sessionId,
      UUID parentId,
      UUID turnStartEntryId,
      TurnEndOutcome outcome,
      boolean continueModel,
      TurnEndReason reason,
      Instant now) {
    UUID turnEndId = tx.nextId();
    tx.entries()
        .insert(
            new Entry(
                turnEndId,
                sessionId,
                parentId,
                new TurnEndPayload(turnStartEntryId, outcome, continueModel, reason, null),
                now));
    return turnEndId;
  }

  private static ModelAttemptSnapshot modelAttemptSnapshot(ModelInvocation model) {
    if (model.failedAttempts().size() == model.attempt()) {
      return null;
    }
    if (model.streamCheckpoint() == null) {
      return new ModelAttemptSnapshot(model.attempt(), 0, "", "");
    }
    return new ModelAttemptSnapshot(
        model.streamCheckpoint().attempt(),
        model.streamCheckpoint().sequence(),
        model.streamCheckpoint().text(),
        model.streamCheckpoint().thinking());
  }
}
