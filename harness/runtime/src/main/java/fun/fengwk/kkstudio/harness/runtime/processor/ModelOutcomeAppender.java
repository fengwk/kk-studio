package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.history.ModelAttemptMaterialization;
import fun.fengwk.kkstudio.harness.runtime.history.ModelAttemptSnapshot;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorStateAccess;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorStateAccessMode;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolEffectBatch;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.port.ToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * terminal ModelInvocation 与 Tool sibling batch 的历史结果物化器。
 *
 * <p>只负责把已提交的 terminal 执行结果按既有 appender 落成不可变历史 Entry（ASSISTANT 结果 / COMPACTION 结果 / ToolResult +
 * TURN_END）并维护 Invocation 行的 attach-then-delete；返回落库事实供调用方决定下一步。绝不推进 Thread head、绝不 requestWork、绝不
 * complete Claim，也不参与 Stop 控制：这些仍由各自调用方（{@link ThreadProcessor} 的 claim 调度、Stop 的 收敛）掌握。
 *
 * <p>{@link #appendModel} 覆盖非 compaction turn 的 terminal Model（Complete / Continue / Failed /
 * ToolBatch / 非成功）；{@link #appendToolBatch} 覆盖已物化 assistant + 全部 terminal sibling 的批量结果落库。
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

  private final HistoryPayloadMapper payloadMapper = new HistoryPayloadMapper();
  private final ModelResponsePlanner responsePlanner = new ModelResponsePlanner();
  private final ToolResultHistoryMaterializer toolResultHistoryMaterializer;

  public ModelOutcomeAppender() {
    this(null);
  }

  public ModelOutcomeAppender(ToolResultHistoryMaterializer toolResultHistoryMaterializer) {
    this.toolResultHistoryMaterializer = toolResultHistoryMaterializer;
  }

  /**
   * 非 compaction turn 的 terminal Model：追加 ASSISTANT 结果（+TURN_END 或 Tool siblings）并维护 Invocation 行。
   */
  public Applied appendModel(
      HarnessStore.Transaction tx, EntryPath path, ModelInvocation model, Instant mutationNow) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(model, "model");
    UUID sessionId = path.root().sessionId();
    UUID parentId =
        ModelAttemptFailureAppender.append(tx, sessionId, path.head().id(), model, false);
    boolean succeeded = model.status() == ModelInvocationStatus.SUCCEEDED;
    ProviderResponse response = model.result();
    UUID resultEntryId = tx.nextId();
    tx.insertEntry(
        new Entry(
            resultEntryId,
            sessionId,
            parentId,
            succeeded
                ? payloadMapper.assistantPayload(response, model.requestSpec().toolBindings())
                : payloadMapper.assistantErrorPayload(model.error(), modelAttemptSnapshot(model)),
            mutationNow,
            succeeded ? model.providerReplayState() : null));
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
            turnEndId, resultEntryId, true, false, false, TurnEndOutcome.COMPLETED, List.of());
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

  /**
   * 已物化 assistant + 全部 terminal sibling 的批量结果：按 callIndex 落 ToolResult 与 COMPLETED
   * TURN_END(continueModel)。
   */
  public Applied appendToolBatch(
      HarnessStore.Transaction tx,
      EntryPath path,
      ModelInvocation model,
      List<ToolInvocation> siblings,
      Instant mutationNow) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(siblings, "siblings");
    // 删除 parent 前的严格物化校验：attached Assistant/result 与已物化失败 attempt 前缀必须与 immutable 事实一致。
    ModelAttemptMaterialization.validateAttached(model, path);
    UUID sessionId = path.root().sessionId();
    UUID parentId = path.head().id();
    for (ToolInvocation sibling : siblings) {
      ToolOutcomeAppender.Applied applied =
          ToolOutcomeAppender.append(
              tx, sessionId, parentId, sibling, mutationNow, toolResultHistoryMaterializer);
      parentId = applied.headEntryId();
    }
    UUID turnEndId =
        appendTurnEnd(
            tx,
            sessionId,
            parentId,
            model.turnStartEntryId(),
            TurnEndOutcome.COMPLETED,
            true,
            null,
            mutationNow);
    // children 先于 parent 删除（FK 顺序）。
    tx.deleteToolInvocationsByIds(siblings.stream().map(ToolInvocation::id).toList());
    tx.deleteModelInvocation(model.id());
    return new Applied(turnEndId, null, false, true, false, TurnEndOutcome.COMPLETED, List.of());
  }

  private List<ToolInvocation> materializeSiblings(
      HarnessStore.Transaction tx,
      ModelInvocation model,
      UUID resultEntryId,
      ModelResponsePlan.ToolBatch batch,
      Instant mutationNow) {
    List<ToolInvocation> materialized = new ArrayList<>(batch.tools().size());
    Map<String, ContributorStateAccessMode> seenStateAccesses = new HashMap<>();
    for (int callIndex = 0; callIndex < batch.tools().size(); callIndex++) {
      ModelResponsePlan.ToolSlot slot = batch.tools().get(callIndex);
      ToolInvocationStatus status = slot.status();
      ToolInvocationError error = slot.error();
      if (status == ToolInvocationStatus.READY) {
        // READY 槽位的 binding 由 planner 保证非空；contributor sibling 状态冲突仍在 Thread 边界确定性拒绝。
        ToolInvocationError conflict =
            siblingStateConflict(slot.binding().contributor(), seenStateAccesses);
        if (conflict != null) {
          status = ToolInvocationStatus.FAILED;
          error = conflict;
        }
      }
      UUID toolId = tx.nextId();
      materialized.add(
          new ToolInvocation(
              toolId,
              model.id(),
              resultEntryId,
              callIndex,
              slot.call(),
              slot.binding(),
              status,
              0,
              null,
              null,
              ToolEffectBatch.EMPTY,
              error,
              mutationNow,
              mutationNow));
    }
    tx.insertToolInvocations(materialized);
    return materialized;
  }

  private static UUID appendTurnEnd(
      HarnessStore.Transaction tx,
      UUID sessionId,
      UUID parentId,
      UUID turnStartEntryId,
      TurnEndOutcome outcome,
      boolean continueModel,
      TurnEndReason reason,
      Instant now) {
    UUID turnEndId = tx.nextId();
    tx.insertEntry(
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

  /** READ 后 WRITE 与不同 key 保持并发；同 key 的 WRITE 之后出现任何 access 都在 dispatch 前确定性拒绝。 */
  private static ToolInvocationError siblingStateConflict(
      ContributorBinding contributor, Map<String, ContributorStateAccessMode> seen) {
    if (contributor == null || contributor.stateAccesses().isEmpty()) {
      return null;
    }
    for (ContributorStateAccess access : contributor.stateAccesses()) {
      String key = contributor.contributorId() + ":" + access.customType();
      if (seen.get(key) == ContributorStateAccessMode.WRITE) {
        return new ToolInvocationError(
            "SIBLING_STATE_CONFLICT",
            "A previous sibling tool writes contributor state "
                + key
                + "; call this tool in the next model turn.");
      }
    }
    for (ContributorStateAccess access : contributor.stateAccesses()) {
      String key = contributor.contributorId() + ":" + access.customType();
      if (access.mode() == ContributorStateAccessMode.WRITE) {
        seen.put(key, ContributorStateAccessMode.WRITE);
      } else {
        seen.putIfAbsent(key, ContributorStateAccessMode.READ);
      }
    }
    return null;
  }
}
