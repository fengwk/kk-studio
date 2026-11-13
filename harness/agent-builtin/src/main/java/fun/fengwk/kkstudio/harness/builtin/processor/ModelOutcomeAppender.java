package fun.fengwk.kkstudio.harness.builtin.processor;

import fun.fengwk.kkstudio.harness.api.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.ModelAttemptSnapshot;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.builtin.history.BuiltinHistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.builtin.port.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.history.FinalAnswerEntry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * terminal ModelInvocation 的历史结果物化器。
 *
 * <p>只负责把已提交的 terminal 执行结果按既有 appender 落成不可变历史 Entry（ASSISTANT 结果 / 失败 attempt / TURN_END，或在
 * active Tool phase 仅插入 Tool siblings）并维护 Invocation 行的 attach-then-delete；返回落库事实供调用方决定下一步。绝不推进
 * Thread head、绝不 requestWork、绝不 complete Claim，也不参与 Stop 控制：这些仍由各自调用方（{@link ThreadProcessor} 的
 * claim 调度、Stop 的 收敛）掌握。
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

  private final BuiltinHistoryPayloadMapper payloadMapper = new BuiltinHistoryPayloadMapper();
  private final ModelResponsePlanner responsePlanner = new ModelResponsePlanner();

  /**
   * 非 compaction turn 的 terminal Model：追加 ASSISTANT 结果（+TURN_END 或 Tool siblings）并维护 Invocation 行。
   */
  public Applied appendModel(
      BuiltinStore.Transaction tx, EntryPath path, ModelInvocation model, Instant mutationNow) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(model, "model");
    UUID sessionId = path.root().sessionId();
    UUID parentId =
        ModelAttemptFailureAppender.append(tx, sessionId, path.head().id(), model, false);
    boolean succeeded = model.status() == ModelInvocationStatus.SUCCEEDED;
    ProviderResponse response = model.result();
    Entry resultEntry =
        new Entry(
            tx.nextId(),
            sessionId,
            parentId,
            succeeded
                ? payloadMapper.assistantPayload(
                    response,
                    model.requestSpec().toolBindings(),
                    model.actualSelection(),
                    model.requestedAt(),
                    model.actualEffort())
                : payloadMapper.assistantErrorPayload(model.error(), modelAttemptSnapshot(model)),
            mutationNow);
    tx.entries().insert(resultEntry);
    UUID resultEntryId = resultEntry.id();
    if (!succeeded) {
      UUID turnEndId =
          appendTurnEnd(
              tx,
              sessionId,
              resultEntryId,
              model.turnStartEntryId(),
              TurnEndOutcome.FAILED,
              false,
              TurnEndReason.TURN_FAILED,
              mutationNow);
      tx.updateModelInvocation(model.attachResultEntry(resultEntryId, mutationNow));
      tx.deleteModelInvocation(model.id());
      return new Applied(turnEndId, null, true, false, false, TurnEndOutcome.FAILED, List.of());
    }
    ModelResponsePlan plan = responsePlanner.plan(response, model.requestSpec().toolBindings());
    return switch (plan) {
      case ModelResponsePlan.Completed ignored -> {
        UUID turnEndId =
            appendTurnEnd(
                tx,
                sessionId,
                resultEntryId,
                model.turnStartEntryId(),
                TurnEndOutcome.COMPLETED,
                false,
                null,
                mutationNow);
        tx.updateModelInvocation(model.attachResultEntry(resultEntryId, mutationNow));
        tx.deleteModelInvocation(model.id());
        yield new Applied(
            turnEndId,
            FinalAnswerEntry.resolve(TurnEndOutcome.COMPLETED, false, resultEntry),
            true,
            false,
            false,
            TurnEndOutcome.COMPLETED,
            List.of());
      }
      case ModelResponsePlan.Continue ignored -> {
        UUID turnEndId =
            appendTurnEnd(
                tx,
                sessionId,
                resultEntryId,
                model.turnStartEntryId(),
                TurnEndOutcome.COMPLETED,
                true,
                null,
                mutationNow);
        tx.updateModelInvocation(model.attachResultEntry(resultEntryId, mutationNow));
        tx.deleteModelInvocation(model.id());
        yield new Applied(turnEndId, null, false, true, false, TurnEndOutcome.COMPLETED, List.of());
      }
      case ModelResponsePlan.Failed failed -> {
        UUID turnEndId =
            appendTurnEnd(
                tx,
                sessionId,
                resultEntryId,
                model.turnStartEntryId(),
                TurnEndOutcome.FAILED,
                false,
                failed.reason(),
                mutationNow);
        tx.updateModelInvocation(model.attachResultEntry(resultEntryId, mutationNow));
        tx.deleteModelInvocation(model.id());
        yield new Applied(turnEndId, null, true, false, false, TurnEndOutcome.FAILED, List.of());
      }
      case ModelResponsePlan.ToolBatch batch -> {
        // active Tool phase：attach resultEntryId 并保留 parent，插入全部 sibling。
        tx.updateModelInvocation(model.attachResultEntry(resultEntryId, mutationNow));
        List<ToolInvocation> invocations =
            materializeSiblings(tx, model, resultEntryId, batch, mutationNow);
        yield new Applied(resultEntryId, null, false, false, true, null, invocations);
      }
    };
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
      UUID toolId = tx.nextId();
      materialized.add(
          new ToolInvocation(
              toolId,
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
