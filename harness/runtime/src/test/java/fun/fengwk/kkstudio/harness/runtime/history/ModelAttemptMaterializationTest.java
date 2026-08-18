package fun.fengwk.kkstudio.harness.runtime.history;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.EnvironmentBindings;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionSummaryAssembler;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.CompactionRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelAttemptFailure;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.StreamCheckpoint;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/** terminal Model attempt 状态清空前的 EntryPath 精确物化契约。 */
class ModelAttemptMaterializationTest {
  private static final UUID OWNER_THREAD_ID = new UUID(0L, 1L);

  private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");
  private static final Instant T1 = T0.plusSeconds(1);
  private static final Instant T2 = T0.plusSeconds(2);
  private static final Instant T3 = T0.plusSeconds(3);
  private static final Instant T4 = T0.plusSeconds(4);
  private static final Instant T6 = T0.plusSeconds(6);
  private static final EnvironmentBinding ENVIRONMENT = EnvironmentBindings.binding("env-1");
  private static final BranchSettings SETTINGS =
      new BranchSettings(
          ENVIRONMENT, "agent", new ModelSelection("provider", "model", "v1"), List.of());

  @Test
  void acceptsExactFailedAttemptAndTerminalPartial() {
    ModelAttemptFailure failure = failure();
    ModelInvocation stored = failedInvocation(List.of(failure));
    Entry failureEntry = failureEntry(id(4L), id(3L), failure, failure.failedAt(), failure.text());
    Entry result =
        assistantError(
            id(5L),
            failureEntry.id(),
            stored.error(),
            new ModelAttemptSnapshot(2, 7, "terminal partial", "terminal thinking"),
            T6);
    EntryPath path = inputPath(List.of(failureEntry, result));

    assertDoesNotThrow(
        () ->
            ModelAttemptMaterialization.validate(
                stored, stored.attachResultEntry(result.id(), T6), path));
  }

  @Test
  void rejectsMissingTamperedOrMisdatedFailedAttempt() {
    ModelAttemptFailure failure = failure();
    ModelInvocation stored = failedInvocation(List.of(failure));
    Entry exactResult =
        assistantError(
            id(5L),
            id(4L),
            stored.error(),
            new ModelAttemptSnapshot(2, 7, "terminal partial", "terminal thinking"),
            T6);

    Entry missingResult =
        assistantError(
            id(5L),
            id(3L),
            stored.error(),
            new ModelAttemptSnapshot(2, 7, "terminal partial", "terminal thinking"),
            T6);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelAttemptMaterialization.validate(
                stored,
                stored.attachResultEntry(missingResult.id(), T6),
                inputPath(missingResult)));

    Entry tampered = failureEntry(id(4L), id(3L), failure, failure.failedAt(), "different partial");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelAttemptMaterialization.validate(
                stored,
                stored.attachResultEntry(exactResult.id(), T6),
                inputPath(List.of(tampered, exactResult))));

    Entry misdated =
        failureEntry(id(4L), id(3L), failure, failure.failedAt().plusMillis(1), failure.text());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelAttemptMaterialization.validate(
                stored,
                stored.attachResultEntry(exactResult.id(), T6),
                inputPath(List.of(misdated, exactResult))));
  }

  @Test
  void rejectsTerminalErrorOrPartialThatDoesNotMatchInvocation() {
    ModelInvocation stored = failedInvocation(List.of(failure()));
    Entry failureEntry =
        failureEntry(
            id(4L),
            id(3L),
            stored.failedAttempts().get(0),
            stored.failedAttempts().get(0).failedAt(),
            stored.failedAttempts().get(0).text());
    Entry wrongError =
        assistantError(
            id(5L),
            failureEntry.id(),
            new ModelInvocationError(ProviderErrorKind.AUTHENTICATION, "wrong error"),
            new ModelAttemptSnapshot(2, 7, "terminal partial", "terminal thinking"),
            T6);
    Entry wrongPartial =
        assistantError(
            id(6L),
            failureEntry.id(),
            stored.error(),
            new ModelAttemptSnapshot(2, 7, "different partial", "terminal thinking"),
            T6);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelAttemptMaterialization.validate(
                stored,
                stored.attachResultEntry(wrongError.id(), T6),
                inputPath(List.of(failureEntry, wrongError))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelAttemptMaterialization.validate(
                stored,
                stored.attachResultEntry(wrongPartial.id(), T6),
                inputPath(List.of(failureEntry, wrongPartial))));
  }

  @Test
  void acceptsOnlyTheExactDirectStopBarrier() {
    StreamCheckpoint checkpoint = new StreamCheckpoint(1, 4, "partial", "thinking");
    ModelInvocation running =
        invocation(request(null), ModelInvocationStatus.RUNNING, 1, checkpoint, null, List.of());
    ModelInvocationError cancellation =
        new ModelInvocationError(ProviderErrorKind.CANCELLED, "cancelled");
    Entry aborted =
        new Entry(
            id(4L),
            id(100L),
            id(3L),
            new AssistantAbortedPayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(
                        new ThinkingMessageContent("thinking"),
                        new TextMessageContent("partial")))),
            T6);
    ModelInvocation attached = running.cancel(cancellation, T6).attachResultEntry(aborted.id(), T6);
    assertDoesNotThrow(
        () -> ModelAttemptMaterialization.validate(running, attached, inputPath(aborted)));

    Entry droppedPartial = assistantError(id(4L), id(3L), cancellation, null, T6);
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelAttemptMaterialization.validate(running, attached, inputPath(droppedPartial)));

    ModelInvocation ready =
        invocation(request(null), ModelInvocationStatus.READY, 0, null, null, List.of());
    Entry cancellationBarrier = assistantError(id(4L), id(3L), cancellation, null, T6);
    ModelInvocation stopped =
        ready.cancel(cancellation, T6).attachResultEntry(cancellationBarrier.id(), T6);
    assertDoesNotThrow(
        () -> ModelAttemptMaterialization.validate(ready, stopped, inputPath(cancellationBarrier)));
  }

  @Test
  void compactionRetriesRemainInvocationOnlyUntilTerminalErrorMaterialization() {
    ModelAttemptFailure failure = failure();
    ModelInvocationError terminalError =
        new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, "summarization failed");
    ModelInvocation stored =
        invocation(
            request(
                new CompactionRequest(
                    CompactionPhase.FULL, CompactionTrigger.THRESHOLD, 100, id(1L), id(1L), null)),
            ModelInvocationStatus.FAILED,
            2,
            null,
            terminalError,
            List.of(failure));
    Entry result =
        assistantError(id(3L), id(2L), terminalError, new ModelAttemptSnapshot(2, 0, "", ""), T6);
    EntryPath path =
        new EntryPath(
            List.of(
                root(),
                new Entry(
                    id(2L),
                    id(100L),
                    id(1L),
                    new TurnStartPayload(TurnStartReason.COMPACTION, SETTINGS, OWNER_THREAD_ID),
                    T1),
                result));

    assertDoesNotThrow(
        () ->
            ModelAttemptMaterialization.validate(
                stored, stored.attachResultEntry(result.id(), T6), path));
  }

  /** validateAttached：Tool batch apply / Stop 删除 parent 前重放 attached durable 事实的最小严格校验。 */
  @Test
  void acceptsValidAttachedToolPhaseModel() {
    ModelInvocation attached = succeededToolPhase();
    EntryPath path = attachedToolPath(assistantToolMessage(id(4L), id(3L)));

    assertDoesNotThrow(() -> ModelAttemptMaterialization.validateAttached(attached, path));
  }

  @Test
  void rejectsAttachedPreconditionViolations() {
    ModelInvocation base = succeededToolPhase();
    EntryPath path = attachedToolPath(assistantToolMessage(id(4L), id(3L)));
    // 非 terminal：RUNNING attached 形状被 precondition 拒绝。
    ModelInvocation running =
        new ModelInvocation(
            base.id(),
            base.threadId(),
            base.turnStartEntryId(),
            base.basisHeadEntryId(),
            base.request(),
            ModelInvocationStatus.RUNNING,
            1,
            new StreamCheckpoint(1, 0, "partial", ""),
            null,
            null,
            null,
            List.of(),
            base.createdAt(),
            base.updatedAt());
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelAttemptMaterialization.validateAttached(running, path));
    // resultEntryId 为 null 的 SUCCEEDED 形状也是 precondition 拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelAttemptMaterialization.validateAttached(
                new ModelInvocation(
                    base.id(),
                    base.threadId(),
                    base.turnStartEntryId(),
                    base.basisHeadEntryId(),
                    base.request(),
                    ModelInvocationStatus.SUCCEEDED,
                    1,
                    null,
                    base.result(),
                    null,
                    null,
                    List.of(),
                    base.createdAt(),
                    base.updatedAt()),
                path));
  }

  /** basis 与 result 相同（attach 到 basis 自身）是非法的真实形状，必须被拒。 */
  @Test
  void rejectsAttachedResultEqualToBasis() {
    ModelInvocation attached = succeededToolPhase(1, id(4L));
    EntryPath path = attachedToolPath(assistantToolMessage(id(4L), id(3L)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelAttemptMaterialization.validateAttached(attached, path));
  }

  @Test
  void rejectsAttachedHeadNotResultEntryOrMissingBasis() {
    ModelInvocation attached = succeededToolPhase();
    // head != resultEntryId：assistant 结果后已追加匹配的 TOOL result，head 前进到 ToolResult Entry。
    EntryPath extraHead =
        new EntryPath(
            List.of(
                root(),
                turnStart(),
                userMessage(),
                assistantToolMessage(id(4L), id(3L)),
                toolResultMessage(id(5L), id(4L))));
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelAttemptMaterialization.validateAttached(attached, extraHead));

    // basisHeadEntryId 不在 path 中。
    ModelInvocation missingBasis =
        new ModelInvocation(
            attached.id(),
            attached.threadId(),
            attached.turnStartEntryId(),
            id(99L),
            attached.request(),
            attached.status(),
            attached.attempt(),
            attached.streamCheckpoint(),
            attached.result(),
            attached.error(),
            attached.resultEntryId(),
            attached.failedAttempts(),
            attached.createdAt(),
            attached.updatedAt());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelAttemptMaterialization.validateAttached(
                missingBasis, attachedToolPath(assistantToolMessage(id(4L), id(3L)))));
  }

  @Test
  void rejectsAttachedNonSucceededOrMissingResult() {
    // FAILED terminal（有 error、无 result）挂在 ASSISTANT head 上不构成 tool phase。
    ModelInvocationError termination =
        new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, "boom");
    ModelInvocation failed =
        new ModelInvocation(
            id(10L),
            id(20L),
            id(2L),
            id(4L),
            request(null),
            ModelInvocationStatus.FAILED,
            1,
            null,
            null,
            termination,
            id(4L),
            List.of(),
            T2,
            T6);
    EntryPath path = attachedToolPath(assistantToolMessage(id(4L), id(3L)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelAttemptMaterialization.validateAttached(failed, path));

    // head 不是 ASSISTANT 消息（turn head 为 USER 消息）时被拒。
    ModelInvocation notAssistant =
        new ModelInvocation(
            id(10L),
            id(20L),
            id(2L),
            id(2L),
            request(null),
            ModelInvocationStatus.SUCCEEDED,
            1,
            null,
            toolResponse(),
            null,
            id(3L),
            List.of(),
            T2,
            T6);
    EntryPath userHead = new EntryPath(List.of(root(), turnStart(), userMessage()));
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelAttemptMaterialization.validateAttached(notAssistant, userHead));
  }

  @Test
  void rejectsAttachedToolCallMismatch() {
    ModelInvocation attached = succeededToolPhase();
    EntryPath path = attachedToolPath(assistantToolMessage(id(4L), id(3L)));

    // response 只声明 1 个 call，assistant 消息声明 2 个 call -> size mismatch。
    EntryPath twoCalls =
        attachedToolPath(
            new Entry(
                id(4L),
                id(100L),
                id(3L),
                new MessagePayload(
                    new AgentMessage(
                        AgentMessageRole.ASSISTANT,
                        List.of(
                            new ToolCallMessageContent("call-1", "bash", "bash", "{}"),
                            new ToolCallMessageContent("call-2", "bash", "bash", "{}"))),
                    new AssistantMessageMetadata(GenerationStopReason.COMPLETE, usage(), cost()),
                    null),
                T2));
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelAttemptMaterialization.validateAttached(attached, twoCalls));

    // response 与 assistant 逐字段不一致：same count 但 argumentsJson 不同。
    ModelInvocation mismatchedArgs =
        new ModelInvocation(
            attached.id(),
            attached.threadId(),
            attached.turnStartEntryId(),
            attached.basisHeadEntryId(),
            attached.request(),
            attached.status(),
            attached.attempt(),
            attached.streamCheckpoint(),
            new ProviderResponse(
                "",
                "",
                List.of(new ProviderToolCall("call-1", "bash", "{\"a\":1}")),
                GenerationStopReason.COMPLETE,
                usage(),
                cost(),
                null,
                null,
                "{}"),
            attached.error(),
            attached.resultEntryId(),
            attached.failedAttempts(),
            attached.createdAt(),
            attached.updatedAt());
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelAttemptMaterialization.validateAttached(mismatchedArgs, path));
  }

  /** 遗漏失败 attempt 被拒：attempt=3 期待两次失败审计，但只物化了 attempt=1（数量 1 != 2）。 */
  @Test
  void rejectsAttachedMissingFailureAttempt() {
    ModelInvocation attached = succeededToolPhase(3, id(3L));
    // attempt=3 期待两次失败审计，但只物化了 attempt=1（真实可构造的短前缀）：数量 1 != 2 必须被拒。
    EntryPath omitted =
        retryAttachedToolPath(
            List.of(attemptFailureEntry(1, id(5L), id(3L))),
            assistantToolMessageAt(id(4L), id(5L), T6));
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelAttemptMaterialization.validateAttached(attached, omitted));
  }

  /** 合法 retry tool phase 被接受：attempt=3 已在 basis 与 head 之间物化连续 failure(1)、failure(2)。 */
  @Test
  void acceptsAttachedRetryToolPhase() {
    ModelInvocation attached = succeededToolPhase(3, id(3L));
    EntryPath path =
        retryAttachedToolPath(
            List.of(attemptFailureEntry(1, id(5L), id(3L)), attemptFailureEntry(2, id(6L), id(5L))),
            assistantToolMessageAt(id(4L), id(6L), T6));
    assertDoesNotThrow(() -> ModelAttemptMaterialization.validateAttached(attached, path));
  }

  /** tool calls 与 result 一致但完整 payload 不同（text / metadata / renderer 任一）必须被拒。 */
  @Test
  void rejectsAttachedPayloadMismatchDespiteMatchingToolCalls() {
    ModelInvocation attached = succeededToolPhase();
    // text 差异：head 出现映射（text 为空）中没有的文本内容，tool call 仍与 result 一致。
    Entry textDrifted =
        assistantToolMessageAt(
            id(4L),
            id(3L),
            T2,
            new MessagePayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(
                        new ToolCallMessageContent("call-1", "bash", "bash", "{}"),
                        new TextMessageContent("unexpected text"))),
                new AssistantMessageMetadata(GenerationStopReason.COMPLETE, usage(), cost()),
                null));
    // metadata 差异：tool call 相同但 stop reason / usage 不同。
    Entry metadataDrifted =
        assistantToolMessageAt(
            id(4L),
            id(3L),
            T2,
            new MessagePayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(new ToolCallMessageContent("call-1", "bash", "bash", "{}"))),
                new AssistantMessageMetadata(GenerationStopReason.LENGTH, usage(), cost()),
                null));
    // renderer 差异：冻结 binding 的 rendererKey=bash，head 却使用 fallback renderer=tool。
    Entry rendererDrifted =
        assistantToolMessageAt(
            id(4L),
            id(3L),
            T2,
            new MessagePayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(new ToolCallMessageContent("call-1", "bash", "tool", "{}"))),
                new AssistantMessageMetadata(GenerationStopReason.COMPLETE, usage(), cost()),
                null));
    for (Entry drifted : List.of(textDrifted, metadataDrifted, rendererDrifted)) {
      assertThrows(
          IllegalArgumentException.class,
          () -> ModelAttemptMaterialization.validateAttached(attached, attachedToolPath(drifted)),
          "attached assistant payload must match the mapper exactly");
    }
  }

  /** attach 转换的普通成功结果不再直接 return：必须完整等于 mapper 重放的 assistant payload。 */
  @Test
  void acceptsExactSuccessResultAndRejectsDrift() {
    ModelInvocation stored =
        new ModelInvocation(
            id(10L),
            id(20L),
            id(2L),
            id(3L),
            toolPhaseRequest(),
            ModelInvocationStatus.SUCCEEDED,
            1,
            null,
            toolResponse(),
            null,
            null,
            List.of(),
            T2,
            T6);
    Entry exactResult = assistantToolMessage(id(4L), id(3L));
    assertDoesNotThrow(
        () ->
            ModelAttemptMaterialization.validate(
                stored,
                stored.attachResultEntry(exactResult.id(), T6),
                attachedToolPath(exactResult)));

    // 相同类型但 text / metadata / renderer 任一漂移必须被拒（result 与 Assistant 完全一致才算成功物化）。
    Entry textDrifted =
        assistantToolMessageAt(
            id(4L),
            id(3L),
            T2,
            new MessagePayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(
                        new ToolCallMessageContent("call-1", "bash", "bash", "{}"),
                        new TextMessageContent("unexpected text"))),
                new AssistantMessageMetadata(GenerationStopReason.COMPLETE, usage(), cost()),
                null));
    Entry metadataDrifted =
        assistantToolMessageAt(
            id(4L),
            id(3L),
            T2,
            new MessagePayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(new ToolCallMessageContent("call-1", "bash", "bash", "{}"))),
                new AssistantMessageMetadata(GenerationStopReason.LENGTH, usage(), cost()),
                null));
    Entry rendererDrifted =
        assistantToolMessageAt(
            id(4L),
            id(3L),
            T2,
            new MessagePayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(new ToolCallMessageContent("call-1", "bash", "tool", "{}"))),
                new AssistantMessageMetadata(GenerationStopReason.COMPLETE, usage(), cost()),
                null));
    for (Entry drifted : List.of(textDrifted, metadataDrifted, rendererDrifted)) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              ModelAttemptMaterialization.validate(
                  stored, stored.attachResultEntry(drifted.id(), T6), attachedToolPath(drifted)),
          "success result must match the mapper-exact assistant payload");
    }
  }

  /** attach 转换的 compaction 成功结果：必须完整等于装配 result payload（preResultPath 去 head 重放 apply 上下文）。 */
  @Test
  void acceptsExactCompactionSuccessResultAndRejectsSummaryDrift() {
    CompactionRequest compaction =
        new CompactionRequest(
            CompactionPhase.FULL, CompactionTrigger.THRESHOLD, 100, id(1L), id(1L), null);
    // compaction 结果必须位于 COMPACTION turn 内（TurnPathValidator），basis = TURN_START id(2)。
    EntryPath preResult = new EntryPath(List.of(root(), compactionTurnStart(id(2L), id(1L), T1)));
    ModelInvocation stored =
        new ModelInvocation(
            id(10L),
            id(20L),
            id(2L),
            id(2L),
            request(compaction),
            ModelInvocationStatus.SUCCEEDED,
            1,
            null,
            compactionResponse("final summary"),
            null,
            null,
            List.of(),
            T2,
            T6);
    CompactionPayload exact =
        CompactionSummaryAssembler.resultPayload(compaction, "final summary", preResult);
    Entry result = new Entry(id(4L), id(100L), id(2L), exact, T6);
    EntryPath path =
        new EntryPath(List.of(root(), compactionTurnStart(id(2L), id(1L), T1), result));
    assertDoesNotThrow(
        () ->
            ModelAttemptMaterialization.validate(
                stored, stored.attachResultEntry(result.id(), T6), path));

    // summaryText 漂移（同一 phase/trigger/tokens，仅文本不同）必须被拒。
    Entry drifted =
        new Entry(
            id(4L),
            id(100L),
            id(2L),
            new CompactionPayload(
                CompactionPhase.FULL,
                CompactionTrigger.THRESHOLD,
                100,
                true,
                "drifted summary",
                compaction.firstKeptEntryId(),
                compaction.cutEntryId(),
                compaction.turnPrefixStartEntryId()),
            T6);
    EntryPath driftedPath =
        new EntryPath(List.of(root(), compactionTurnStart(id(2L), id(1L), T1), drifted));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelAttemptMaterialization.validate(
                stored, stored.attachResultEntry(drifted.id(), T6), driftedPath),
        "compaction result must match the assembled summary exactly");
  }

  private static ModelInvocation failedInvocation(List<ModelAttemptFailure> failures) {
    return invocation(
        request(null),
        ModelInvocationStatus.FAILED,
        2,
        new StreamCheckpoint(2, 7, "terminal partial", "terminal thinking"),
        new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, "provider exploded"),
        failures);
  }

  private static ModelInvocation invocation(
      ModelRequestSpec request,
      ModelInvocationStatus status,
      int attempt,
      StreamCheckpoint checkpoint,
      ModelInvocationError error,
      List<ModelAttemptFailure> failures) {
    return new ModelInvocation(
        id(10L),
        id(20L),
        id(2L),
        status == ModelInvocationStatus.FAILED && request.compaction() != null ? id(2L) : id(3L),
        request,
        status,
        attempt,
        checkpoint,
        null,
        error,
        null,
        failures,
        T2,
        T6);
  }

  private static ModelAttemptFailure failure() {
    return new ModelAttemptFailure(
        1,
        3,
        "retry partial",
        "retry thinking",
        new ModelInvocationError(ProviderErrorKind.TRANSIENT, "temporary outage"),
        T3,
        T4);
  }

  private static Entry failureEntry(
      UUID entryId, UUID parentId, ModelAttemptFailure failure, Instant createdAt, String text) {
    return new Entry(
        entryId,
        id(100L),
        parentId,
        new ModelAttemptFailurePayload(
            new ModelAttemptSnapshot(
                failure.attempt(), failure.sequence(), text, failure.thinking()),
            new AssistantError(failure.error().kind().name(), failure.error().message()),
            failure.retryAt()),
        createdAt);
  }

  private static Entry assistantError(
      UUID entryId,
      UUID parentId,
      ModelInvocationError error,
      ModelAttemptSnapshot attempt,
      Instant createdAt) {
    return new Entry(
        entryId,
        id(100L),
        parentId,
        new AssistantErrorPayload(
            new AssistantError(error.kind().name(), error.message()), attempt),
        createdAt);
  }

  private static EntryPath inputPath(Entry suffix) {
    return inputPath(List.of(suffix));
  }

  private static EntryPath inputPath(List<Entry> suffix) {
    return new EntryPath(
        Stream.concat(
                List.of(
                    root(),
                    new Entry(
                        id(2L),
                        id(100L),
                        id(1L),
                        new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, OWNER_THREAD_ID),
                        T1),
                    new Entry(
                        id(3L),
                        id(100L),
                        id(2L),
                        new MessagePayload(
                            new AgentMessage(
                                AgentMessageRole.USER, List.of(new TextMessageContent("hello"))),
                            null,
                            null),
                        T2))
                    .stream(),
                suffix.stream())
            .toList());
  }

  private static Entry root() {
    return new Entry(id(1L), id(100L), null, new RootPayload(SETTINGS), T0);
  }

  private static ModelRequestSpec request(CompactionRequest compaction) {
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        modelDescriptor(),
        new ModelVariant("v1", null, null, null, null, null, null, List.of(), null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        ProviderCacheControl.none(),
        compaction);
  }

  private static ModelDescriptor modelDescriptor() {
    return new ModelDescriptor(
        "provider",
        "model",
        Set.of(ModelInputModality.TEXT),
        true,
        true,
        new ModelPricing(
            "USD",
            "standard",
            "standard",
            BigDecimal.ONE,
            "1",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO));
  }

  /**
   * 活跃 Tool phase 的 attached SUCCEEDED ModelInvocation：basis == USER head id(3)，result == ASSISTANT
   * id(4)（result 是 basis 的严格 descendant，真实合法形状）。
   */
  private static ModelInvocation succeededToolPhase() {
    return succeededToolPhase(1, id(3L));
  }

  /** attached SUCCEEDED tool phase，可显式配置 attempt 与 basisHeadEntryId 以构造 retry 审计场景。 */
  private static ModelInvocation succeededToolPhase(int attempt, UUID basisHeadEntryId) {
    return new ModelInvocation(
        id(10L),
        id(20L),
        id(2L),
        basisHeadEntryId,
        toolPhaseRequest(),
        ModelInvocationStatus.SUCCEEDED,
        attempt,
        null,
        toolResponse(),
        null,
        id(4L),
        List.of(),
        T2,
        T6);
  }

  /** SUCCEEDED tool phase 的冻结请求：携带 rendererKey=bash 的 bash binding，使映射的 assistant payload 可复现。 */
  private static ModelRequestSpec toolPhaseRequest() {
    ToolBinding binding =
        new ToolBinding(
            new ToolDescriptor(
                "bash",
                "1.0",
                ToolType.PLATFORM,
                "description of bash",
                "bash",
                new ToolParamsSchema("arguments", Map.of(), Set.of(), false),
                ToolSideEffect.READ_ONLY,
                Duration.ofSeconds(30)),
            ToolType.PLATFORM,
            null);
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        modelDescriptor(),
        new ModelVariant("v1", null, null, null, null, null, null, List.of(), null),
        List.of(),
        List.of(binding),
        List.of(),
        List.of(),
        ProviderCacheControl.none(),
        null);
  }

  /** [root, TURN_START, USER, assistant] 且 basis == assistant == head 的 tool phase path。 */
  private static EntryPath attachedToolPath(Entry assistant) {
    return new EntryPath(List.of(root(), turnStart(), userMessage(), assistant));
  }

  /** retry 结构 path：[root, TURN_START, USER, failures..., assistant]，失败 entry 连续挂链、basis = USER。 */
  private static EntryPath retryAttachedToolPath(List<Entry> failures, Entry assistant) {
    List<Entry> entries = new ArrayList<>(List.of(root(), turnStart(), userMessage()));
    entries.addAll(failures);
    entries.add(assistant);
    return new EntryPath(entries);
  }

  /** 第 {@code attempt} 个尝试失败的 ModelAttemptFailure entry；本校验只核对 attempt 前缀序号，详情字段不参与。 */
  private static Entry attemptFailureEntry(int attempt, UUID entryId, UUID parentId) {
    ModelAttemptFailure failure =
        new ModelAttemptFailure(
            attempt,
            1,
            "retry partial",
            "retry thinking",
            new ModelInvocationError(ProviderErrorKind.TRANSIENT, "temporary outage"),
            T3,
            T4);
    return failureEntry(entryId, parentId, failure, failure.failedAt(), failure.text());
  }

  /** call-1 的 assistant head（payload 与映射一致），可指定 created_at 以满足 retry 链的 parent 时间顺序。 */
  private static Entry assistantToolMessageAt(UUID entryId, UUID parentId, Instant createdAt) {
    return assistantToolMessageAt(
        entryId,
        parentId,
        createdAt,
        new MessagePayload(
            new AgentMessage(
                AgentMessageRole.ASSISTANT,
                List.of(new ToolCallMessageContent("call-1", "bash", "bash", "{}"))),
            new AssistantMessageMetadata(GenerationStopReason.COMPLETE, usage(), cost()),
            null));
  }

  /** call-1 的 assistant head，允许自定义完整 payload（供 payload 漂移场景）。 */
  private static Entry assistantToolMessageAt(
      UUID entryId, UUID parentId, Instant createdAt, MessagePayload payload) {
    return new Entry(entryId, id(100L), parentId, payload, createdAt);
  }

  private static Entry userMessage() {
    return new Entry(
        id(3L),
        id(100L),
        id(2L),
        new MessagePayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello"))),
            null,
            null),
        T2);
  }

  /** 与 assistant 的 call-1 匹配的 TOOL result（用于让 head 前进到 ToolResult Entry 的合法形状）。 */
  private static Entry toolResultMessage(UUID entryId, UUID parentId) {
    return new Entry(
        entryId,
        id(100L),
        parentId,
        new MessagePayload(
            new AgentMessage(
                AgentMessageRole.TOOL,
                List.of(
                    new ToolResultMessageContent(
                        "call-1",
                        "bash",
                        "bash",
                        List.of(new TextMessageContent("done")),
                        false,
                        "{}"))),
            null,
            new ToolResultMetadata(id(4L), "call-1", 0, ToolResultStatus.SUCCEEDED, false, null)),
        T3);
  }

  private static Entry turnStart() {
    return new Entry(
        id(2L),
        id(100L),
        id(1L),
        new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, OWNER_THREAD_ID),
        T1);
  }

  /** COMPACTION turn 的 TURN_START（compaction 结果 Entry 必须位于 compaction turn 内）。 */
  private static Entry compactionTurnStart(UUID entryId, UUID parentId, Instant createdAt) {
    return new Entry(
        entryId,
        id(100L),
        parentId,
        new TurnStartPayload(TurnStartReason.COMPACTION, SETTINGS, OWNER_THREAD_ID),
        createdAt);
  }

  private static Entry assistantToolMessage(UUID entryId, UUID parentId) {
    return new Entry(
        entryId,
        id(100L),
        parentId,
        new MessagePayload(
            new AgentMessage(
                AgentMessageRole.ASSISTANT,
                List.of(new ToolCallMessageContent("call-1", "bash", "bash", "{}"))),
            new AssistantMessageMetadata(GenerationStopReason.COMPLETE, usage(), cost()),
            null),
        T2);
  }

  private static ProviderResponse toolResponse() {
    return new ProviderResponse(
        "",
        "",
        List.of(new ProviderToolCall("call-1", "bash", "{}")),
        GenerationStopReason.COMPLETE,
        usage(),
        cost(),
        null,
        null,
        "{}");
  }

  private static ProviderResponse compactionResponse(String text) {
    return new ProviderResponse(
        text, "", List.of(), GenerationStopReason.COMPLETE, usage(), cost(), null, null, "{}");
  }

  private static ModelUsage usage() {
    return new ModelUsage(1, 1, 0, 0, 0, 0, 2);
  }

  private static ModelCost cost() {
    return new ModelCost(
        "USD",
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO);
  }
}
