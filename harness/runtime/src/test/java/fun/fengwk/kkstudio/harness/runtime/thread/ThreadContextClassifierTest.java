package fun.fengwk.kkstudio.harness.runtime.thread;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
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

/**
 * {@link ThreadContextClassifier} 纯单元测试：直接构造 ThreadState / EntryPath / ModelInvocation /
 * ToolInvocation 输入，覆盖全部六种上下文与每一族不变量错误（不接触 Store）。
 */
class ThreadContextClassifierTest {

  private static final UUID THREAD_ID = id(1000);
  private static final UUID SESSION_ID = id(100);
  private static final UUID ROOT_ID = id(1);
  private static final UUID TURN_START_ID = id(2);
  private static final UUID USER_ID = id(3);
  private static final UUID ASSISTANT_ID = id(4);
  private static final UUID ERROR_ID = id(5);
  private static final UUID TURN_END_ID = id(6);
  private static final UUID MODEL_ID = id(20);
  private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");

  private final ThreadContextClassifier classifier = new ThreadContextClassifier();

  // -----------------------------------------------------------------------------------------------
  // 规则 1：无 open Turn
  // -----------------------------------------------------------------------------------------------

  @Test
  void rootHeadWithoutOpenTurnIsIdleOrHistorical() {
    EntryPath path = rootPath();
    assertEquals(
        ThreadContext.IdleOrHistorical.class,
        classifier.classify(thread(ROOT_ID), path, null, List.of()).getClass());
  }

  @Test
  void closedTurnWithoutContinuationIsIdleOrHistorical() {
    EntryPath path = closedTurnPath(false);
    assertEquals(
        ThreadContext.IdleOrHistorical.class,
        classifier.classify(thread(TURN_END_ID), path, null, List.of()).getClass());
  }

  @Test
  void closedTurnWithContinuationIsContinuationDue() {
    EntryPath path = closedTurnPath(true);
    ThreadContext.ContinuationDue context =
        assertInstanceOf(
            ThreadContext.ContinuationDue.class,
            classifier.classify(thread(TURN_END_ID), path, null, List.of()));
    // 携带 head TURN_END 身份，调用方无需重新发现。
    assertEquals(TURN_END_ID, context.turnEnd().id());
    assertEquals(TURN_START_ID, ((TurnEndPayload) context.turnEnd().payload()).turnStartEntryId());
  }

  @Test
  void modelInputWithoutOpenTurnIsInvariantError() {
    assertThrows(
        IllegalStateException.class,
        () ->
            classifier.classify(
                thread(TURN_END_ID),
                closedTurnPath(false),
                model(THREAD_ID, TURN_START_ID, USER_ID, ModelInvocationStatus.READY, null, null),
                List.of()));
  }

  @Test
  void toolSiblingsWithoutOpenTurnAreInvariantError() {
    assertThrows(
        IllegalStateException.class,
        () ->
            classifier.classify(
                thread(TURN_END_ID),
                closedTurnPath(false),
                null,
                List.of(tool(id(0), "call-1", ToolInvocationStatus.READY, null))));
  }

  // -----------------------------------------------------------------------------------------------
  // 规则 2/3：open Turn 无本 Thread Model / Model 身份
  // -----------------------------------------------------------------------------------------------

  @Test
  void openTurnWithoutOwnModelIsIdleOrHistorical() {
    // 另一 Thread 共享历史 TURN_START/Assistant 时按 (threadId, open turn) 查不到本 Thread 的 Model：绝不借
    // 他人 Model 推进，也不检查 descendant 结果。
    EntryPath path = openUserPath();
    assertEquals(
        ThreadContext.IdleOrHistorical.class,
        classifier.classify(thread(USER_ID), path, null, List.of()).getClass());
  }

  @Test
  void toolSiblingsWithoutModelAreInvariantError() {
    assertThrows(
        IllegalStateException.class,
        () ->
            classifier.classify(
                thread(USER_ID),
                openUserPath(),
                null,
                List.of(tool(id(0), "call-1", ToolInvocationStatus.READY, null))));
  }

  @Test
  void modelOfAnotherThreadIsInvariantError() {
    assertThrows(
        IllegalStateException.class,
        () ->
            classifier.classify(
                thread(USER_ID),
                openUserPath(),
                model(
                    id(THREAD_ID.getLeastSignificantBits() + 1),
                    TURN_START_ID,
                    USER_ID,
                    ModelInvocationStatus.READY,
                    null,
                    null),
                List.of()));
  }

  @Test
  void modelOfAnotherTurnIsInvariantError() {
    assertThrows(
        IllegalStateException.class,
        () ->
            classifier.classify(
                thread(USER_ID),
                openUserPath(),
                model(
                    THREAD_ID,
                    id(TURN_START_ID.getLeastSignificantBits() + 1),
                    USER_ID,
                    ModelInvocationStatus.READY,
                    null,
                    null),
                List.of()));
  }

  // -----------------------------------------------------------------------------------------------
  // 规则 4：head == basis && 结果未挂载
  // -----------------------------------------------------------------------------------------------

  @Test
  void nonterminalModelAtBasisIsModelActive() {
    EntryPath path = openUserPath();
    ThreadContext.ModelActive context =
        assertInstanceOf(
            ThreadContext.ModelActive.class,
            classifier.classify(
                thread(USER_ID),
                path,
                model(THREAD_ID, TURN_START_ID, USER_ID, ModelInvocationStatus.READY, null, null),
                List.of()));
    assertEquals(MODEL_ID, context.model().id());
  }

  @Test
  void terminalModelAtBasisIsModelTerminalPending() {
    EntryPath path = openUserPath();
    ThreadContext.ModelTerminalPending context =
        assertInstanceOf(
            ThreadContext.ModelTerminalPending.class,
            classifier.classify(
                thread(USER_ID),
                path,
                model(
                    THREAD_ID,
                    TURN_START_ID,
                    USER_ID,
                    ModelInvocationStatus.SUCCEEDED,
                    response(List.of()),
                    null),
                List.of()));
    assertEquals(MODEL_ID, context.model().id());
  }

  @Test
  void toolSiblingsAtModelBasisAreInvariantError() {
    assertThrows(
        IllegalStateException.class,
        () ->
            classifier.classify(
                thread(USER_ID),
                openUserPath(),
                model(THREAD_ID, TURN_START_ID, USER_ID, ModelInvocationStatus.READY, null, null),
                List.of(tool(id(0), "call-1", ToolInvocationStatus.READY, null))));
  }

  // -----------------------------------------------------------------------------------------------
  // 规则 6：其他 head/basis/result 关系一律历史
  // -----------------------------------------------------------------------------------------------

  @Test
  void attachedTerminalModelRelocatedBackToBasisIsIdleOrHistorical() {
    // MOVE_HEAD 回 basis（结果挂在另一 descendant）：head == basis 但 resultEntryId 非空，不重放、不 apply。
    assertEquals(
        ThreadContext.IdleOrHistorical.class,
        classifier
            .classify(
                thread(USER_ID),
                openUserPath(),
                model(
                    THREAD_ID,
                    TURN_START_ID,
                    USER_ID,
                    ModelInvocationStatus.SUCCEEDED,
                    response(List.of()),
                    ASSISTANT_ID),
                List.of())
            .getClass());
  }

  @Test
  void terminalModelOnDescendantHeadIsIdleOrHistorical() {
    // head relocation 到 basis 的 descendant（独立 ASSISTANT）：terminal 未挂结果但不再 applicable。
    EntryPath path = assistantPath(List.of());
    assertEquals(
        ThreadContext.IdleOrHistorical.class,
        classifier
            .classify(
                thread(ASSISTANT_ID),
                path,
                model(
                    THREAD_ID,
                    TURN_START_ID,
                    USER_ID,
                    ModelInvocationStatus.SUCCEEDED,
                    response(List.of()),
                    null),
                List.of())
            .getClass());
  }

  @Test
  void nonterminalModelOffBasisIsIdleOrHistorical() {
    assertEquals(
        ThreadContext.IdleOrHistorical.class,
        classifier
            .classify(
                thread(ASSISTANT_ID),
                assistantPath(List.of()),
                model(THREAD_ID, TURN_START_ID, USER_ID, ModelInvocationStatus.READY, null, null),
                List.of())
            .getClass());
  }

  @Test
  void toolSiblingsWithoutResultHeadAreInvariantError() {
    // 结果未挂载（head == basis）时不允许携带 siblings：siblings 只可能来自结果 head。
    assertThrows(
        IllegalStateException.class,
        () ->
            classifier.classify(
                thread(USER_ID),
                openUserPath(),
                model(
                    THREAD_ID,
                    TURN_START_ID,
                    USER_ID,
                    ModelInvocationStatus.SUCCEEDED,
                    response(List.of()),
                    null),
                List.of(tool(id(0), "call-1", ToolInvocationStatus.READY, null))));
  }

  @Test
  void attachedErrorHeadIsIdleOrHistorical() {
    // resultEntryId == head 但 head 不是 ASSISTANT Message（AssistantError）：历史，不校验 Tool。
    EntryPath path = errorPath();
    assertEquals(
        ThreadContext.IdleOrHistorical.class,
        classifier
            .classify(
                thread(ERROR_ID),
                path,
                model(
                    THREAD_ID,
                    TURN_START_ID,
                    USER_ID,
                    ModelInvocationStatus.FAILED,
                    null,
                    ERROR_ID),
                List.of())
            .getClass());
  }

  @Test
  void toolSiblingsOnErrorHeadAreInvariantError() {
    assertThrows(
        IllegalStateException.class,
        () ->
            classifier.classify(
                thread(ERROR_ID),
                errorPath(),
                model(
                    THREAD_ID,
                    TURN_START_ID,
                    USER_ID,
                    ModelInvocationStatus.FAILED,
                    null,
                    ERROR_ID),
                List.of(tool(id(0), "call-1", ToolInvocationStatus.READY, null))));
  }

  // -----------------------------------------------------------------------------------------------
  // 规则 5：Tool context 一致性校验
  // -----------------------------------------------------------------------------------------------

  @Test
  void attachedAssistantWithoutCallsIsIdleOrHistorical() {
    // 历史 no-tools Assistant 前缀（含 attached 无调用结果）：交 normalization。
    EntryPath path = assistantPath(List.of());
    assertEquals(
        ThreadContext.IdleOrHistorical.class,
        classifier
            .classify(
                thread(ASSISTANT_ID),
                path,
                model(
                    THREAD_ID,
                    TURN_START_ID,
                    USER_ID,
                    ModelInvocationStatus.SUCCEEDED,
                    response(List.of()),
                    ASSISTANT_ID),
                List.of())
            .getClass());
  }

  @Test
  void nonSucceededModelAtAssistantHeadIsInvariantError() {
    assertThrows(
        IllegalStateException.class,
        () ->
            classifier.classify(
                thread(ASSISTANT_ID),
                assistantPath(List.of("call-1")),
                model(
                    THREAD_ID,
                    TURN_START_ID,
                    USER_ID,
                    ModelInvocationStatus.FAILED,
                    null,
                    ASSISTANT_ID),
                List.of()));
  }

  @Test
  void responseToolCallsMismatchIsInvariantError() {
    assertThrows(
        IllegalStateException.class,
        () ->
            classifier.classify(
                thread(ASSISTANT_ID),
                assistantPath(List.of("call-1")),
                model(
                    THREAD_ID,
                    TURN_START_ID,
                    USER_ID,
                    ModelInvocationStatus.SUCCEEDED,
                    response(List.of("call-X")),
                    ASSISTANT_ID),
                List.of()));
  }

  @Test
  void noCallsWithSiblingsIsInvariantError() {
    assertThrows(
        IllegalStateException.class,
        () ->
            classifier.classify(
                thread(ASSISTANT_ID),
                assistantPath(List.of()),
                model(
                    THREAD_ID,
                    TURN_START_ID,
                    USER_ID,
                    ModelInvocationStatus.SUCCEEDED,
                    response(List.of()),
                    ASSISTANT_ID),
                List.of(tool(id(0), "call-1", ToolInvocationStatus.READY, null))));
  }

  @Test
  void callsWithoutSiblingsIsInvariantError() {
    assertThrows(
        IllegalStateException.class,
        () ->
            classifier.classify(
                thread(ASSISTANT_ID),
                assistantPath(List.of("call-1")),
                model(
                    THREAD_ID,
                    TURN_START_ID,
                    USER_ID,
                    ModelInvocationStatus.SUCCEEDED,
                    response(List.of("call-1")),
                    ASSISTANT_ID),
                List.of()));
  }

  @Test
  void siblingCountMismatchIsInvariantError() {
    assertThrows(
        IllegalStateException.class,
        () ->
            classifier.classify(
                thread(ASSISTANT_ID),
                assistantPath(List.of("call-1", "call-2")),
                model(
                    THREAD_ID,
                    TURN_START_ID,
                    USER_ID,
                    ModelInvocationStatus.SUCCEEDED,
                    response(List.of("call-1", "call-2")),
                    ASSISTANT_ID),
                List.of(tool(id(0), "call-1", ToolInvocationStatus.READY, null))));
  }

  @Test
  void nonContiguousOrdinalIsInvariantError() {
    assertThrows(
        IllegalStateException.class,
        () ->
            classifier.classify(
                thread(ASSISTANT_ID),
                assistantPath(List.of("call-1", "call-2")),
                model(
                    THREAD_ID,
                    TURN_START_ID,
                    USER_ID,
                    ModelInvocationStatus.SUCCEEDED,
                    response(List.of("call-1", "call-2")),
                    ASSISTANT_ID),
                List.of(
                    tool(id(0), "call-1", ToolInvocationStatus.READY, null),
                    tool(id(2), "call-2", ToolInvocationStatus.READY, null))));
  }

  @Test
  void siblingOfAnotherModelIsInvariantError() {
    assertThrows(
        IllegalStateException.class,
        () ->
            classifier.classify(
                thread(ASSISTANT_ID),
                assistantPath(List.of("call-1")),
                model(
                    THREAD_ID,
                    TURN_START_ID,
                    USER_ID,
                    ModelInvocationStatus.SUCCEEDED,
                    response(List.of("call-1")),
                    ASSISTANT_ID),
                List.of(foreignTool(0, "call-1"))));
  }

  @Test
  void mixedAttachedUnattachedSiblingsAreInvariantError() {
    assertThrows(
        IllegalStateException.class,
        () ->
            classifier.classify(
                thread(ASSISTANT_ID),
                assistantPath(List.of("call-1", "call-2")),
                model(
                    THREAD_ID,
                    TURN_START_ID,
                    USER_ID,
                    ModelInvocationStatus.SUCCEEDED,
                    response(List.of("call-1", "call-2")),
                    ASSISTANT_ID),
                List.of(
                    tool(id(0), "call-1", ToolInvocationStatus.SUCCEEDED, id(100)),
                    tool(id(1), "call-2", ToolInvocationStatus.SUCCEEDED, null))));
  }

  @Test
  void attachedNonterminalMixedSiblingsAreInvariantError() {
    assertThrows(
        IllegalStateException.class,
        () ->
            classifier.classify(
                thread(ASSISTANT_ID),
                assistantPath(List.of("call-1", "call-2")),
                model(
                    THREAD_ID,
                    TURN_START_ID,
                    USER_ID,
                    ModelInvocationStatus.SUCCEEDED,
                    response(List.of("call-1", "call-2")),
                    ASSISTANT_ID),
                List.of(
                    tool(id(0), "call-1", ToolInvocationStatus.SUCCEEDED, id(100)),
                    tool(id(1), "call-2", ToolInvocationStatus.READY, null))));
  }

  @Test
  void allTerminalAttachedSiblingsAreIdleOrHistorical() {
    // 全部 terminal+已挂载：结果位于 relocation 后的另一 descendant，历史，不重挂载。
    assertEquals(
        ThreadContext.IdleOrHistorical.class,
        classifier
            .classify(
                thread(ASSISTANT_ID),
                assistantPath(List.of("call-1", "call-2")),
                model(
                    THREAD_ID,
                    TURN_START_ID,
                    USER_ID,
                    ModelInvocationStatus.SUCCEEDED,
                    response(List.of("call-1", "call-2")),
                    ASSISTANT_ID),
                List.of(
                    tool(id(0), "call-1", ToolInvocationStatus.SUCCEEDED, id(100)),
                    tool(id(1), "call-2", ToolInvocationStatus.SUCCEEDED, id(101))))
            .getClass());
  }

  @Test
  void allUnattachedWithNonterminalSiblingsIsToolActive() {
    ThreadContext.ToolActive context =
        assertInstanceOf(
            ThreadContext.ToolActive.class,
            classifier.classify(
                thread(ASSISTANT_ID),
                assistantPath(List.of("call-1", "call-2")),
                model(
                    THREAD_ID,
                    TURN_START_ID,
                    USER_ID,
                    ModelInvocationStatus.SUCCEEDED,
                    response(List.of("call-1", "call-2")),
                    ASSISTANT_ID),
                List.of(
                    tool(id(0), "call-1", ToolInvocationStatus.SUCCEEDED, null),
                    tool(id(1), "call-2", ToolInvocationStatus.RUNNING, null))));
    assertEquals(MODEL_ID, context.model().id());
    assertEquals(ASSISTANT_ID, context.assistant().id());
    assertEquals(List.of("call-1", "call-2"), callIds(context.calls()));
    assertEquals(List.of(id(0), id(1)), siblingIds(context.siblings()));
  }

  @Test
  void allUnattachedTerminalSiblingsIsToolTerminalPending() {
    ThreadContext.ToolTerminalPending context =
        assertInstanceOf(
            ThreadContext.ToolTerminalPending.class,
            classifier.classify(
                thread(ASSISTANT_ID),
                assistantPath(List.of("call-1", "call-2")),
                model(
                    THREAD_ID,
                    TURN_START_ID,
                    USER_ID,
                    ModelInvocationStatus.SUCCEEDED,
                    response(List.of("call-1", "call-2")),
                    ASSISTANT_ID),
                List.of(
                    tool(id(0), "call-1", ToolInvocationStatus.SUCCEEDED, null),
                    tool(id(1), "call-2", ToolInvocationStatus.FAILED, null))));
    assertEquals(MODEL_ID, context.model().id());
    assertEquals(ASSISTANT_ID, context.assistant().id());
    assertEquals(List.of("call-1", "call-2"), callIds(context.calls()));
    assertEquals(List.of(id(0), id(1)), siblingIds(context.siblings()));
  }

  // -----------------------------------------------------------------------------------------------
  // 输入与结果对象契约
  // -----------------------------------------------------------------------------------------------

  @Test
  void nullInputsAreRejected() {
    assertThrows(
        NullPointerException.class, () -> classifier.classify(null, rootPath(), null, List.of()));
    assertThrows(
        NullPointerException.class,
        () -> classifier.classify(thread(ROOT_ID), null, null, List.of()));
    assertThrows(
        NullPointerException.class,
        () -> classifier.classify(thread(ROOT_ID), rootPath(), null, null));
  }

  @Test
  void continuationDueRejectsNonContinuationTurnEnd() {
    Entry nonContinuation =
        entry(
            TURN_END_ID,
            ASSISTANT_ID,
            new TurnEndPayload(TURN_START_ID, TurnEndOutcome.COMPLETED, false, null, null));
    assertThrows(
        IllegalArgumentException.class, () -> new ThreadContext.ContinuationDue(nonContinuation));
    assertThrows(IllegalArgumentException.class, () -> new ThreadContext.ContinuationDue(null));
  }

  @Test
  void toolContextListsAreImmutableCopies() {
    List<ToolInvocation> mutableSiblings = new ArrayList<>();
    mutableSiblings.add(tool(id(0), "call-1", ToolInvocationStatus.READY, null));
    ThreadContext.ToolActive context =
        assertInstanceOf(
            ThreadContext.ToolActive.class,
            classifier.classify(
                thread(ASSISTANT_ID),
                assistantPath(List.of("call-1")),
                model(
                    THREAD_ID,
                    TURN_START_ID,
                    USER_ID,
                    ModelInvocationStatus.SUCCEEDED,
                    response(List.of("call-1")),
                    ASSISTANT_ID),
                mutableSiblings));
    // 结果列表是独立不可变拷贝：修改输入不影响上下文。
    mutableSiblings.add(tool(id(1), "call-2", ToolInvocationStatus.READY, null));
    assertEquals(1, context.siblings().size());
    assertThrows(
        UnsupportedOperationException.class,
        () -> context.siblings().add(tool(id(2), "call-3", ToolInvocationStatus.READY, null)));
    assertThrows(
        UnsupportedOperationException.class,
        () -> context.calls().add(new ToolCallMessageContent("call-9", "bash", "bash", "{}")));
  }

  // -----------------------------------------------------------------------------------------------
  // 构造 helper
  // -----------------------------------------------------------------------------------------------

  private static ThreadState thread(UUID headEntryId) {
    return new ThreadState(THREAD_ID, headEntryId, false, 1, 0, NOW, NOW);
  }

  private static Entry entry(UUID id, UUID parentId, EntryPayload payload) {
    return new Entry(id, SESSION_ID, parentId, payload, NOW);
  }

  private static EntryPath rootPath() {
    return new EntryPath(List.of(entry(ROOT_ID, null, new RootPayload(branchSettings()))));
  }

  private static EntryPath openUserPath() {
    return new EntryPath(
        List.of(
            entry(ROOT_ID, null, new RootPayload(branchSettings())),
            entry(
                TURN_START_ID,
                ROOT_ID,
                new TurnStartPayload(TurnStartReason.INPUT, branchSettings())),
            entry(USER_ID, TURN_START_ID, userMessage())));
  }

  private static EntryPath assistantPath(List<String> callIds) {
    return new EntryPath(
        List.of(
            entry(ROOT_ID, null, new RootPayload(branchSettings())),
            entry(
                TURN_START_ID,
                ROOT_ID,
                new TurnStartPayload(TurnStartReason.INPUT, branchSettings())),
            entry(USER_ID, TURN_START_ID, userMessage()),
            entry(ASSISTANT_ID, USER_ID, assistantMessage(callIds))));
  }

  private static EntryPath errorPath() {
    return new EntryPath(
        List.of(
            entry(ROOT_ID, null, new RootPayload(branchSettings())),
            entry(
                TURN_START_ID,
                ROOT_ID,
                new TurnStartPayload(TurnStartReason.INPUT, branchSettings())),
            entry(USER_ID, TURN_START_ID, userMessage()),
            entry(
                ERROR_ID,
                USER_ID,
                new AssistantErrorPayload(new AssistantError("MODEL_FAILED", "boom")))));
  }

  private static EntryPath closedTurnPath(boolean continueModel) {
    return new EntryPath(
        List.of(
            entry(ROOT_ID, null, new RootPayload(branchSettings())),
            entry(
                TURN_START_ID,
                ROOT_ID,
                new TurnStartPayload(TurnStartReason.INPUT, branchSettings())),
            entry(USER_ID, TURN_START_ID, userMessage()),
            entry(ASSISTANT_ID, USER_ID, assistantMessage(List.of())),
            entry(
                TURN_END_ID,
                ASSISTANT_ID,
                new TurnEndPayload(
                    TURN_START_ID, TurnEndOutcome.COMPLETED, continueModel, null, null))));
  }

  private static EntryPayload userMessage() {
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello"))),
        null,
        null);
  }

  private static EntryPayload assistantMessage(List<String> callIds) {
    List<AgentMessageContent> contents = new ArrayList<>();
    for (String callId : callIds) {
      contents.add(new ToolCallMessageContent(callId, "bash", "bash", "{}"));
    }
    contents.add(new TextMessageContent("assistant reply"));
    ProviderStopReason stopReason =
        callIds.isEmpty() ? ProviderStopReason.COMPLETED : ProviderStopReason.TOOL_CALLS;
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.ASSISTANT, contents),
        new AssistantMessageMetadata(stopReason, usage(), cost()),
        null);
  }

  private static ModelInvocation model(
      UUID threadId,
      UUID turnStartEntryId,
      UUID basisHeadEntryId,
      ModelInvocationStatus status,
      ProviderResponse result,
      UUID resultEntryId) {
    int attempt = status == ModelInvocationStatus.READY ? 0 : 1;
    ModelInvocationError error =
        status == ModelInvocationStatus.FAILED
            ? new ModelInvocationError(ProviderErrorKind.TRANSIENT, "boom")
            : null;
    return new ModelInvocation(
        MODEL_ID,
        threadId,
        turnStartEntryId,
        basisHeadEntryId,
        modelRequest(),
        status,
        attempt,
        null,
        result,
        error,
        resultEntryId,
        NOW,
        NOW);
  }

  private static ToolInvocation tool(
      UUID invocationId, String callId, ToolInvocationStatus status, UUID resultEntryId) {
    ToolApproval approval =
        status == ToolInvocationStatus.SUCCEEDED || status == ToolInvocationStatus.RUNNING
            ? ToolApproval.notRequired()
            : null;
    int attempt =
        status == ToolInvocationStatus.READY || status == ToolInvocationStatus.FAILED ? 0 : 1;
    ToolResult result =
        status == ToolInvocationStatus.SUCCEEDED
            ? new ToolResult(callId, List.of(new TextToolContent("tool ok")), false, "{}", false)
            : null;
    ToolInvocationError error =
        status == ToolInvocationStatus.FAILED ? new ToolInvocationError("FAILED", "boom") : null;
    return new ToolInvocation(
        invocationId,
        MODEL_ID,
        ASSISTANT_ID,
        Math.toIntExact(invocationId.getLeastSignificantBits()),
        new ToolInvocationRequest(new ToolCall(callId, "bash", "{}"), toolBinding()),
        status,
        attempt,
        approval,
        result,
        error,
        resultEntryId,
        NOW,
        NOW);
  }

  /** 属于另一 Model 的 sibling（ownership 校验用）。 */
  private static ToolInvocation foreignTool(int ordinal, String callId) {
    return new ToolInvocation(
        id(MODEL_ID.getLeastSignificantBits() * 100L + ordinal),
        id(MODEL_ID.getLeastSignificantBits() + 1),
        ASSISTANT_ID,
        ordinal,
        new ToolInvocationRequest(new ToolCall(callId, "bash", "{}"), toolBinding()),
        ToolInvocationStatus.READY,
        0,
        null,
        null,
        null,
        null,
        NOW,
        NOW);
  }

  private static ModelInvocationRequest modelRequest() {
    return new ModelInvocationRequest(
        new EnvironmentName("env-1"),
        new ProviderRequest(
            modelDescriptor(),
            new ModelVariant("v1", null, null, null, null, null, null, List.of(), null),
            List.of(),
            List.of(),
            ProviderCacheControl.none()),
        List.of(),
        List.of(),
        false,
        100_000,
        null);
  }

  private static ToolBinding toolBinding() {
    return new ToolBinding(
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
  }

  private static ModelDescriptor modelDescriptor() {
    return new ModelDescriptor(
        "provider",
        "model",
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

  private static ProviderResponse response(List<String> callIds) {
    List<ProviderToolCall> calls = new ArrayList<>();
    for (String callId : callIds) {
      calls.add(new ProviderToolCall(callId, "bash", "{}"));
    }
    ProviderStopReason stopReason =
        callIds.isEmpty() ? ProviderStopReason.COMPLETED : ProviderStopReason.TOOL_CALLS;
    return new ProviderResponse(
        "response text", "", calls, stopReason, usage(), cost(), "req-1", null, "{}");
  }

  private static ModelUsage usage() {
    return new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L);
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

  private static BranchSettings branchSettings() {
    return new BranchSettings(
        new EnvironmentName("env-2"),
        "agent",
        new ModelSelection("provider", "model", "v1"),
        List.of());
  }

  private static List<String> callIds(List<ToolCallMessageContent> calls) {
    List<String> ids = new ArrayList<>();
    for (ToolCallMessageContent call : calls) {
      ids.add(call.toolCallId());
    }
    return ids;
  }

  private static List<UUID> siblingIds(List<ToolInvocation> siblings) {
    List<UUID> ids = new ArrayList<>();
    for (ToolInvocation sibling : siblings) {
      ids.add(sibling.id());
    }
    return ids;
  }
}
