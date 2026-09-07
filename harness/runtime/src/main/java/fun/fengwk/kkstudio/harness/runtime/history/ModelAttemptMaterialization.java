package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionResultEvaluator;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelAttemptFailure;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.StreamCheckpoint;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 校验 terminal ModelInvocation 的 attempt 审计已经精确物化到结果 EntryPath。 */
public final class ModelAttemptMaterialization {

  private ModelAttemptMaterialization() {}

  public static void validate(
      ModelInvocation stored, ModelInvocation attached, EntryPath resultPath) {
    Objects.requireNonNull(stored, "stored");
    Objects.requireNonNull(attached, "attached");
    Objects.requireNonNull(resultPath, "resultPath");
    if (stored.resultEntryId() != null
        || attached.resultEntryId() == null
        || !attached.status().isTerminal()
        || !attached.failedAttempts().isEmpty()
        || attached.streamCheckpoint() != null
        || attached.providerReplayState() != null) {
      throw new IllegalArgumentException(
          "attempt materialization validation requires a pending terminal attach transition");
    }
    List<Entry> materialized = failuresAfterBasis(stored, resultPath);
    for (Entry failureEntry : materialized) {
      if (failureEntry.providerReplayState() != null) {
        throw new IllegalArgumentException(
            "materialized model attempt failure must not carry provider replay state");
      }
    }
    if (isCompactionInvocation(stored, resultPath)) {
      if (!materialized.isEmpty()) {
        throw new IllegalArgumentException(
            "compaction result paths must not materialize model attempt failures");
      }
    } else {
      if (materialized.size() != stored.failedAttempts().size()) {
        throw new IllegalArgumentException(
            "model result path must materialize every failed attempt exactly once");
      }
      for (int index = 0; index < materialized.size(); index++) {
        requireSameFailure(stored.failedAttempts().get(index), materialized.get(index));
      }
    }
    if (stored.status().isTerminal()) {
      requireTerminalResult(stored, resultPath);
    } else {
      requireDirectStopResult(stored, attached, resultPath.head());
    }
  }

  private static List<Entry> failuresAfterBasis(ModelInvocation invocation, EntryPath path) {
    boolean afterBasis = false;
    List<Entry> failures = new ArrayList<>();
    for (Entry entry : path.entries()) {
      if (entry.id().equals(invocation.requestHeadEntryId())) {
        afterBasis = true;
        continue;
      }
      if (afterBasis && entry.payload() instanceof ModelAttemptFailurePayload) {
        failures.add(entry);
      }
    }
    if (!afterBasis) {
      throw new IllegalArgumentException("model result path must contain requestHeadEntryId");
    }
    return List.copyOf(failures);
  }

  private static void requireSameFailure(ModelAttemptFailure expected, Entry entry) {
    ModelAttemptFailurePayload actual = (ModelAttemptFailurePayload) entry.payload();
    ModelAttemptSnapshot attempt = actual.attempt();
    if (attempt.attempt() != expected.attempt()
        || attempt.sequence() != expected.sequence()
        || !attempt.text().equals(expected.text())
        || !attempt.thinking().equals(expected.thinking())
        || !entry.createdAt().equals(expected.failedAt())
        || !actual.retryAt().equals(expected.retryAt())
        || !actual.error().code().equals(expected.error().kind().name())
        || !actual.error().message().equals(expected.error().message())) {
      throw new IllegalArgumentException(
          "materialized model attempt failure must match the invocation audit fact");
    }
  }

  /**
   * terminal 结果的严格物化校验：SUCCEEDED 必须完整等于重放的结果 payload（normal 经 {@link HistoryPayloadMapper}，
   * compaction 经 {@link CompactionSummaryAssembler}）；FAILED / CANCELLED 保持 exact error / checkpoint
   * 语义不变。
   */
  private static void requireTerminalResult(ModelInvocation invocation, EntryPath resultPath) {
    if (invocation.status() == ModelInvocationStatus.SUCCEEDED) {
      requireSuccessfulResult(invocation, resultPath);
      return;
    }
    if (resultPath.head().providerReplayState() != null) {
      throw new IllegalArgumentException(
          "materialized terminal error must not carry provider replay state");
    }
    EntryPayload resultPayload = resultPath.head().payload();
    if (!(resultPayload instanceof AssistantErrorPayload actual)
        || !sameError(invocation, actual)
        || !Objects.equals(terminalAttempt(invocation), actual.attempt())) {
      throw new IllegalArgumentException(
          "materialized terminal error must match the invocation error and checkpoint");
    }
  }

  /** 从 immutable TURN_START 事实判断 Invocation 是否属于 Compaction turn。 */
  public static boolean isCompactionInvocation(ModelInvocation invocation, EntryPath resultPath) {
    for (Entry entry : resultPath.entries()) {
      if (entry.id().equals(invocation.turnStartEntryId())
          && entry.payload() instanceof TurnStartPayload start) {
        return start.compaction() != null;
      }
    }
    throw new IllegalArgumentException(
        "model result path must contain the invocation turnStartEntryId");
  }

  private static TurnStartPayload requiredTurnStart(
      ModelInvocation invocation, EntryPath resultPath) {
    for (Entry entry : resultPath.entries()) {
      if (entry.id().equals(invocation.turnStartEntryId())
          && entry.payload() instanceof TurnStartPayload start) {
        return start;
      }
    }
    throw new IllegalArgumentException(
        "model result path must contain the invocation turnStartEntryId");
  }

  /**
   * SUCCEEDED model 的结果 Entry payload 必须完整等于按当前 frozen 事实重放的结果：normal（无 compaction）为 {@link
   * HistoryPayloadMapper#assistantPayload} 的 ASSISTANT payload；compaction 为 {@link
   * CompactionResultEvaluator#evaluate} 的 payload（{@code preResultPath} 是 resultPath 去掉 head
   * 结果后的前缀，保证 TURN_PREFIX / HISTORY 组装上下文与 apply 时一致）。任何字段漂移（text / thinking / tool call renderer /
   * metadata / summary text）都必须被拒。
   */
  private static void requireSuccessfulResult(ModelInvocation invocation, EntryPath resultPath) {
    Entry resultEntry = resultPath.head();
    EntryPayload resultPayload = resultEntry.payload();
    TurnStartPayload start = requiredTurnStart(invocation, resultPath);
    if (start.compaction() != null) {
      if (resultEntry.providerReplayState() != null) {
        throw new IllegalArgumentException(
            "compaction result entry must not carry provider replay state");
      }
      EntryPayload expected =
          CompactionResultEvaluator.evaluate(
              preResultPath(resultPath), start.compaction(), invocation.result());
      if (!expected.equals(resultPayload)) {
        throw new IllegalArgumentException(
            "model result must materialize the exact compaction payload");
      }
      return;
    }
    if (!Objects.equals(invocation.providerReplayState(), resultEntry.providerReplayState())) {
      throw new IllegalArgumentException(
          "materialized assistant entry must match the invocation provider replay state");
    }
    MessagePayload expected =
        new HistoryPayloadMapper()
            .assistantPayload(invocation.result(), invocation.requestSpec().toolBindings());
    if (!expected.equals(resultPayload)) {
      throw new IllegalArgumentException(
          "model result must materialize the exact assistant payload");
    }
  }

  /** compaction 组装上下文：resultPath 去掉 head 结果后的前缀（至少保留 ROOT 与 basis 两项）。 */
  private static EntryPath preResultPath(EntryPath resultPath) {
    List<Entry> entries = resultPath.entries();
    if (entries.size() < 3) {
      throw new IllegalArgumentException(
          "model result path must keep a non-trivial prefix before its head");
    }
    return new EntryPath(entries.subList(0, entries.size() - 1));
  }

  private static void requireDirectStopResult(
      ModelInvocation stored, ModelInvocation attached, Entry resultEntry) {
    if (attached.status() != ModelInvocationStatus.CANCELLED) {
      throw new IllegalArgumentException(
          "only a direct Stop may attach a result while the stored invocation is active");
    }
    if (resultEntry.providerReplayState() != null) {
      throw new IllegalArgumentException(
          "direct stop result entry must not carry provider replay state");
    }
    EntryPayload resultPayload = resultEntry.payload();
    StreamCheckpoint checkpoint = stored.streamCheckpoint();
    if (checkpoint == null) {
      if (!(resultPayload instanceof AssistantErrorPayload actual)
          || !sameError(attached, actual)
          || actual.attempt() != null) {
        throw new IllegalArgumentException(
            "a stopped model without partial output requires its exact cancellation error");
      }
      return;
    }
    AssistantAbortedPayload expected = aborted(checkpoint);
    if (!expected.equals(resultPayload)) {
      throw new IllegalArgumentException(
          "a stopped model with partial output must materialize the exact durable checkpoint");
    }
  }

  private static boolean sameError(ModelInvocation invocation, AssistantErrorPayload payload) {
    return payload.error().code().equals(invocation.error().kind().name())
        && payload.error().message().equals(invocation.error().message());
  }

  private static AssistantAbortedPayload aborted(StreamCheckpoint checkpoint) {
    List<AgentMessageContent> contents = new ArrayList<>(2);
    if (!checkpoint.thinking().isEmpty()) {
      contents.add(new ThinkingMessageContent(checkpoint.thinking()));
    }
    if (!checkpoint.text().isEmpty()) {
      contents.add(new TextMessageContent(checkpoint.text()));
    }
    return new AssistantAbortedPayload(new AgentMessage(AgentMessageRole.ASSISTANT, contents));
  }

  private static ModelAttemptSnapshot terminalAttempt(ModelInvocation invocation) {
    if (invocation.error() == null || invocation.failedAttempts().size() == invocation.attempt()) {
      return null;
    }
    if (invocation.streamCheckpoint() == null) {
      return new ModelAttemptSnapshot(invocation.attempt(), 0, "", "");
    }
    return new ModelAttemptSnapshot(
        invocation.streamCheckpoint().attempt(),
        invocation.streamCheckpoint().sequence(),
        invocation.streamCheckpoint().text(),
        invocation.streamCheckpoint().thinking());
  }

  /**
   * 校验已 attach 的 terminal ModelInvocation 与 immutable EntryPath 事实一致：Tool batch apply / Stop 删除
   * parent 前的最小严格校验，绝不绕过物化校验直接 delete。
   *
   * <p>attach 转换本身（{@code stored.resultEntryId == null -> non-null}）已经通过 {@link #validate} 完成失败
   * attempt 与 terminal 结果的逐条物化校验，且 Entry 与 terminal invocation 事实不可变；本方法只重放当前 durable 事实：
   *
   * <ul>
   *   <li>model 必须 terminal、resultEntryId 非空、failedAttempts 已清空、streamCheckpoint 已清空（attach 形状）；
   *   <li>resultEntryId 必须是 resultPath 的 head（Assistant 结果恰在 head 才构成活跃 Tool phase）；
   *   <li>resultPath 必须包含 requestHeadEntryId 且严格位于 head 之前（result 必须是 request head 的严格 descendant，
   *       不允许 attach 到 basis 自身）；
   *   <li>非压缩 model：basis 之后 head 之前的 ModelAttemptFailurePayload 必须精确等于已确认的 attempt 前缀（attempt 从 1
   *       连续递增且数量恰为 {@code attached.attempt() - 1}，不允许遗漏）；压缩 model：不允许出现任何失败条目；
   *   <li>SUCCEEDED model 的 result 必须按值等价于 Assistant head：通过与 {@link
   *       HistoryPayloadMapper#assistantPayload} 映射结果（thinking / text / tool calls renderer /
   *       metadata usage / cost / stop reason）完整一致， 不能只比对 tool calls。
   * </ul>
   */
  public static void validateAttached(ModelInvocation attached, EntryPath resultPath) {
    Objects.requireNonNull(attached, "attached");
    Objects.requireNonNull(resultPath, "resultPath");
    if (attached.resultEntryId() == null
        || !attached.status().isTerminal()
        || !attached.failedAttempts().isEmpty()
        || attached.streamCheckpoint() != null) {
      throw new IllegalArgumentException(
          "attached model validation requires a terminal model invocation with a materialized"
              + " result entry");
    }
    List<Entry> entries = resultPath.entries();
    if (entries.isEmpty()
        || !entries.get(entries.size() - 1).id().equals(attached.resultEntryId())) {
      throw new IllegalArgumentException(
          "attached model resultEntryId must be the result path head");
    }
    if (attached.resultEntryId().equals(attached.requestHeadEntryId())) {
      throw new IllegalArgumentException(
          "attached model result must be a strict descendant of its basis head entry");
    }
    boolean afterBasis = false;
    boolean foundBasis = false;
    boolean compaction = isCompactionInvocation(attached, resultPath);
    int expectedAttempt = 1;
    for (Entry entry : entries) {
      if (entry.id().equals(attached.requestHeadEntryId())) {
        afterBasis = true;
        foundBasis = true;
        continue;
      }
      if (!afterBasis || entry.id().equals(attached.resultEntryId())) {
        continue;
      }
      if (entry.payload() instanceof ModelAttemptFailurePayload failure) {
        if (compaction) {
          throw new IllegalArgumentException(
              "compaction result paths must not materialize model attempt failures");
        }
        if (failure.attempt().attempt() != expectedAttempt) {
          throw new IllegalArgumentException(
              "materialized model attempt failures must form a consecutive prefix starting at"
                  + " attempt 1");
        }
        expectedAttempt++;
      }
    }
    if (!foundBasis) {
      throw new IllegalArgumentException("model result path must contain requestHeadEntryId");
    }
    if (!compaction && expectedAttempt - 1 != attached.attempt() - 1) {
      throw new IllegalArgumentException(
          "materialized model attempt failures must be exactly the confirmed attempt prefix:"
              + " expected "
              + (attached.attempt() - 1)
              + " but found "
              + (expectedAttempt - 1));
    }
    Entry head = entries.get(entries.size() - 1);
    if (attached.status() != ModelInvocationStatus.SUCCEEDED || attached.result() == null) {
      throw new IllegalArgumentException(
          "an attached tool-phase model invocation must be SUCCEEDED with a result");
    }
    if (!(head.payload() instanceof MessagePayload message)
        || message.message().role() != AgentMessageRole.ASSISTANT) {
      throw new IllegalArgumentException(
          "an attached tool-phase model result head must be an ASSISTANT message entry");
    }
    requireSuccessfulResult(attached, resultPath);
  }
}
