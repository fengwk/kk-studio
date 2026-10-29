package fun.fengwk.kkstudio.harness.runtime.thread;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverActivity;
import fun.fengwk.kkstudio.harness.runtime.driver.GenerationProjection;
import fun.fengwk.kkstudio.harness.runtime.driver.LockedDriverState;
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
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

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
 *
 * <p>Driver 状态按 Runtime 契约提供：真实 Driver 只按 (threadId, 当前 open turn 的 TURN_START entry id) 锁定
 * generation，无状态时给出 {@link DriverActivity#NONE} 的实现。生产实现里的 {@code NoDriverState} 是私有嵌套类、
 * 测试不可见，因此本类提供等价的本地实现；模型状态的替身由本类的 {@link #classify} 入口按本次输入的 Thread 与 open turn 显式校验身份，跨 Thread/跨
 * turn 的模型一律 fail closed，绝不进入分类器（真实 Driver 的同一行为见 {@code BuiltinAgentDriverContractTest}）。
 */
class ThreadContextClassifierTest {

  private static final UUID THREAD_ID = id(1000);
  private static final UUID SESSION_ID = id(100);
  private static final UUID ROOT_ID = id(1);
  private static final UUID TURN_START_ID = id(2);
  private static final UUID OTHER_TURN_START_ID = id(22);
  private static final UUID USER_ID = id(3);
  private static final UUID ASSISTANT_ID = id(4);
  private static final UUID ERROR_ID = id(5);
  private static final UUID TURN_END_ID = id(6);
  private static final UUID MODEL_ID = id(20);
  private static final UUID OTHER_THREAD_ID = id(999);
  private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
  private static final String CREATION_REQUEST_HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  /** 无 Driver 状态：activity 为 NONE 且没有任何 generation 身份。 */
  private static final LockedDriverState NO_DRIVER_STATE = new NoDriverState();

  private final ThreadContextClassifier classifier = new ThreadContextClassifier();

  // -----------------------------------------------------------------------------------------------
  // 规则 1：无 open Turn
  // -----------------------------------------------------------------------------------------------

  @Test
  void rootHeadWithoutOpenTurnIsIdleOrHistorical() {
    EntryPath path = rootPath();
    assertEquals(
        ThreadContext.IdleOrHistorical.class,
        classify(thread(ROOT_ID), path, NO_DRIVER_STATE, List.of()).getClass());
  }

  @Test
  void closedTurnWithoutContinuationIsIdleOrHistorical() {
    EntryPath path = closedTurnPath(false);
    assertEquals(
        ThreadContext.IdleOrHistorical.class,
        classify(thread(TURN_END_ID), path, NO_DRIVER_STATE, List.of()).getClass());
  }

  @Test
  void closedTurnWithContinuationIsContinuationDue() {
    EntryPath path = closedTurnPath(true);
    ThreadContext.ContinuationDue context =
        assertInstanceOf(
            ThreadContext.ContinuationDue.class,
            classify(thread(TURN_END_ID), path, NO_DRIVER_STATE, List.of()));
    // 携带 head TURN_END 身份，调用方无需重新发现。
    assertEquals(TURN_END_ID, context.turnEnd().id());
    assertEquals(TURN_START_ID, ((TurnEndPayload) context.turnEnd().payload()).turnStartEntryId());
  }

  /**
   * owner-aware continuation：被引用 TURN_START 的 ownerThreadId 不等于当前 Thread 时义务归另一 Thread，本 Thread
   * 视为历史。
   */
  @Test
  void foreignContinuationIsHistoricalNotContinuationDue() {
    EntryPath foreignPath =
        new EntryPath(
            List.of(
                entry(ROOT_ID, null, new RootPayload(branchSettings())),
                entry(
                    TURN_START_ID,
                    ROOT_ID,
                    new TurnStartPayload(TurnStartReason.INPUT, branchSettings(), OTHER_THREAD_ID)),
                entry(USER_ID, TURN_START_ID, userMessage()),
                entry(ASSISTANT_ID, USER_ID, assistantMessage(List.of())),
                entry(
                    TURN_END_ID,
                    ASSISTANT_ID,
                    new TurnEndPayload(
                        TURN_START_ID, TurnEndOutcome.COMPLETED, true, null, null))));
    assertEquals(
        ThreadContext.IdleOrHistorical.class,
        classifier
            .classify(thread(TURN_END_ID), foreignPath, NO_DRIVER_STATE, List.of())
            .getClass());
    // owner 精确匹配时才承担该 CONTINUATION obligation（对照组）。
    assertEquals(
        ThreadContext.ContinuationDue.class,
        classifier
            .classify(thread(TURN_END_ID), closedTurnPath(true), NO_DRIVER_STATE, List.of())
            .getClass());
  }

  @Test
  void modelInputWithoutOpenTurnIsInvariantError() {
    assertThrows(
        IllegalStateException.class,
        () ->
            classify(
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
            classify(
                thread(TURN_END_ID),
                closedTurnPath(false),
                NO_DRIVER_STATE,
                List.of(tool(id(0), "call-1", ToolInvocationStatus.READY))));
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
        classify(thread(USER_ID), path, NO_DRIVER_STATE, List.of()).getClass());
  }

  @Test
  void toolSiblingsWithoutModelAreInvariantError() {
    assertThrows(
        IllegalStateException.class,
        () ->
            classify(
                thread(USER_ID),
                openUserPath(),
                NO_DRIVER_STATE,
                List.of(tool(id(0), "call-1", ToolInvocationStatus.READY))));
  }

  /**
   * 跨 Thread 的模型不是本次输入的合法 Driver 状态：真实 Driver 按 (threadId, open turn) 锁定，分类器因此永远拿不到别人的
   * generation；真实锁定行为由 {@code BuiltinAgentDriverContractTest} 直接对真实 Driver 断言。
   */
  @Test
  void modelOfAnotherThreadIsNotAValidDriverState() {
    // 负例：模型属于另一 Thread，与本次分类输入的 Thread 不一致，必须在进入分类器前 fail closed。
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                classify(
                    thread(USER_ID),
                    openUserPath(),
                    model(
                        OTHER_THREAD_ID,
                        TURN_START_ID,
                        USER_ID,
                        ModelInvocationStatus.READY,
                        null,
                        null),
                    List.of()));
    assertTrue(failure.getMessage().contains("locked for thread " + OTHER_THREAD_ID));
  }

  /** 跨 turn 的模型同样不是合法输入：锁定键必须精确等于本次输入 path 的 open turn。 */
  @Test
  void modelOfAnotherTurnIsNotAValidDriverState() {
    // 负例：模型属于同一 Thread 的另一个 turn，与本次输入 path 的 open turn 不一致，必须 fail closed。
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                classify(
                    thread(USER_ID),
                    openUserPath(OTHER_TURN_START_ID),
                    model(
                        THREAD_ID, TURN_START_ID, USER_ID, ModelInvocationStatus.READY, null, null),
                    List.of()));
    assertTrue(failure.getMessage().contains("locked for turn " + TURN_START_ID));
  }

  // -----------------------------------------------------------------------------------------------
  // 规则 4：head == requestHead && 结果未挂载
  // -----------------------------------------------------------------------------------------------

  @Test
  void nonterminalModelAtBasisIsModelActive() {
    EntryPath path = openUserPath();
    ThreadContext.ModelActive context =
        assertInstanceOf(
            ThreadContext.ModelActive.class,
            classify(
                thread(USER_ID),
                path,
                model(THREAD_ID, TURN_START_ID, USER_ID, ModelInvocationStatus.READY, null, null),
                List.of()));
    assertEquals(MODEL_ID, context.driverState().generationId());
  }

  @Test
  void terminalModelAtBasisIsModelTerminalPending() {
    EntryPath path = openUserPath();
    ThreadContext.ModelTerminalPending context =
        assertInstanceOf(
            ThreadContext.ModelTerminalPending.class,
            classify(
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
    assertEquals(MODEL_ID, context.driverState().generationId());
  }

  @Test
  void toolSiblingsAtModelBasisAreInvariantError() {
    assertThrows(
        IllegalStateException.class,
        () ->
            classify(
                thread(USER_ID),
                openUserPath(),
                model(THREAD_ID, TURN_START_ID, USER_ID, ModelInvocationStatus.READY, null, null),
                List.of(tool(id(0), "call-1", ToolInvocationStatus.READY))));
  }

  // -----------------------------------------------------------------------------------------------
  // 规则 6：其他 head/requestHead/result 关系一律历史
  // -----------------------------------------------------------------------------------------------

  @Test
  void attachedTerminalModelRelocatedBackToBasisIsIdleOrHistorical() {
    // head 回到 requestHead（结果挂在另一 descendant）：head == requestHead 但 resultEntryId 非空，不重放、不 apply。
    assertEquals(
        ThreadContext.IdleOrHistorical.class,
        classify(
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
    // head relocation 到 requestHead 的 descendant（独立 ASSISTANT）：terminal 未挂结果但不再 applicable。
    EntryPath path = assistantPath(List.of());
    assertEquals(
        ThreadContext.IdleOrHistorical.class,
        classify(
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
        classify(
                thread(ASSISTANT_ID),
                assistantPath(List.of()),
                model(THREAD_ID, TURN_START_ID, USER_ID, ModelInvocationStatus.READY, null, null),
                List.of())
            .getClass());
  }

  @Test
  void toolSiblingsWithoutResultHeadAreInvariantError() {
    // 结果未挂载（head == requestHead）时不允许携带 siblings：siblings 只可能来自结果 head。
    assertThrows(
        IllegalStateException.class,
        () ->
            classify(
                thread(USER_ID),
                openUserPath(),
                model(
                    THREAD_ID,
                    TURN_START_ID,
                    USER_ID,
                    ModelInvocationStatus.SUCCEEDED,
                    response(List.of()),
                    null),
                List.of(tool(id(0), "call-1", ToolInvocationStatus.READY))));
  }

  @Test
  void attachedErrorHeadIsIdleOrHistorical() {
    // resultEntryId == head 但 head 不是 ASSISTANT Message（AssistantError）：历史，不校验 Tool。
    EntryPath path = errorPath();
    assertEquals(
        ThreadContext.IdleOrHistorical.class,
        classify(
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
            classify(
                thread(ERROR_ID),
                errorPath(),
                model(
                    THREAD_ID,
                    TURN_START_ID,
                    USER_ID,
                    ModelInvocationStatus.FAILED,
                    null,
                    ERROR_ID),
                List.of(tool(id(0), "call-1", ToolInvocationStatus.READY))));
  }

  // -----------------------------------------------------------------------------------------------
  // 规则 5：Tool context 一致性校验
  // （siblings 存在即 outcome 未物化：任一非 terminal -> ToolActive；全部 terminal -> ToolTerminalPending。）
  // -----------------------------------------------------------------------------------------------

  @Test
  void attachedAssistantWithoutCallsIsIdleOrHistorical() {
    // 历史 no-tools Assistant 前缀（含 attached 无调用结果）：交 normalization。
    EntryPath path = assistantPath(List.of());
    assertEquals(
        ThreadContext.IdleOrHistorical.class,
        classify(
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
  void failedGenerationAtAssistantHeadStillRequiresToolSiblings() {
    // generation 的终态与失败原因属于 Driver 私有事实，分类器只看公共 assistant 消息：
    // 带 tool call 的 assistant head 必须有匹配 siblings，否则 fail closed。
    assertThrows(
        IllegalStateException.class,
        () ->
            classify(
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
  void responseToolCallsAreDriverOwnedAndDoNotChangeToolContext() {
    // 响应里的 tool call 列表是 Driver 私有事实（物化时由 Driver 校验）：分类只依据公共 assistant 消息与
    // siblings，因此响应与消息不一致不会改变分类结果，也绝不降级为业务 kind。
    LockedDriverState state =
        model(
            THREAD_ID,
            TURN_START_ID,
            USER_ID,
            ModelInvocationStatus.SUCCEEDED,
            response(List.of("call-X")),
            ASSISTANT_ID);
    // 原负例的缺 sibling 形状仍须 fail closed。
    assertThrows(
        IllegalStateException.class,
        () -> classify(thread(ASSISTANT_ID), assistantPath(List.of("call-1")), state, List.of()));
    ThreadContext.ToolActive context =
        assertInstanceOf(
            ThreadContext.ToolActive.class,
            classify(
                thread(ASSISTANT_ID),
                assistantPath(List.of("call-1")),
                state,
                List.of(tool(id(0), "call-1", ToolInvocationStatus.READY))));
    assertEquals(List.of("call-1"), callIds(context.calls()));
  }

  @Test
  void noCallsWithSiblingsIsInvariantError() {
    assertThrows(
        IllegalStateException.class,
        () ->
            classify(
                thread(ASSISTANT_ID),
                assistantPath(List.of()),
                model(
                    THREAD_ID,
                    TURN_START_ID,
                    USER_ID,
                    ModelInvocationStatus.SUCCEEDED,
                    response(List.of()),
                    ASSISTANT_ID),
                List.of(tool(id(0), "call-1", ToolInvocationStatus.READY))));
  }

  @Test
  void callsWithoutSiblingsIsInvariantError() {
    assertThrows(
        IllegalStateException.class,
        () ->
            classify(
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
            classify(
                thread(ASSISTANT_ID),
                assistantPath(List.of("call-1", "call-2")),
                model(
                    THREAD_ID,
                    TURN_START_ID,
                    USER_ID,
                    ModelInvocationStatus.SUCCEEDED,
                    response(List.of("call-1", "call-2")),
                    ASSISTANT_ID),
                List.of(tool(id(0), "call-1", ToolInvocationStatus.READY))));
  }

  @Test
  void nonContiguousOrdinalIsInvariantError() {
    assertThrows(
        IllegalStateException.class,
        () ->
            classify(
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
                    tool(id(0), "call-1", ToolInvocationStatus.READY),
                    tool(id(2), "call-2", ToolInvocationStatus.READY))));
  }

  @Test
  void siblingOfAnotherThreadIsInvariantError() {
    assertThrows(
        IllegalStateException.class,
        () ->
            classify(
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
  void allUnattachedWithNonterminalSiblingsIsToolActive() {
    ThreadContext.ToolActive context =
        assertInstanceOf(
            ThreadContext.ToolActive.class,
            classify(
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
                    tool(id(0), "call-1", ToolInvocationStatus.SUCCEEDED),
                    tool(id(1), "call-2", ToolInvocationStatus.RUNNING))));
    assertEquals(ASSISTANT_ID, context.assistant().id());
    assertEquals(List.of("call-1", "call-2"), callIds(context.calls()));
    assertEquals(List.of(id(0), id(1)), siblingIds(context.siblings()));
  }

  @Test
  void nativeAssistantToolPhaseWithoutModelIsToolActive() {
    ThreadContext.ToolActive context =
        assertInstanceOf(
            ThreadContext.ToolActive.class,
            classify(
                thread(ASSISTANT_ID),
                assistantPath(List.of("call-1", "call-2")),
                NO_DRIVER_STATE,
                List.of(
                    tool(id(0), "call-1", ToolInvocationStatus.SUCCEEDED),
                    tool(id(1), "call-2", ToolInvocationStatus.RUNNING))));
    assertEquals(ASSISTANT_ID, context.assistant().id());
    assertEquals(List.of("call-1", "call-2"), callIds(context.calls()));
    assertEquals(List.of(id(0), id(1)), siblingIds(context.siblings()));
  }

  @Test
  void allUnattachedTerminalSiblingsIsToolTerminalPending() {
    ThreadContext.ToolTerminalPending context =
        assertInstanceOf(
            ThreadContext.ToolTerminalPending.class,
            classify(
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
                    tool(id(0), "call-1", ToolInvocationStatus.SUCCEEDED),
                    tool(id(1), "call-2", ToolInvocationStatus.FAILED))));
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
        NullPointerException.class, () -> classify(null, rootPath(), NO_DRIVER_STATE, List.of()));
    assertThrows(
        NullPointerException.class,
        () -> classify(thread(ROOT_ID), null, NO_DRIVER_STATE, List.of()));
    // Driver 状态是必填输入：无状态时也必须给出 NONE 实现，绝不接受 null。
    assertThrows(
        NullPointerException.class, () -> classify(thread(ROOT_ID), rootPath(), null, List.of()));
    assertThrows(
        NullPointerException.class,
        () -> classify(thread(ROOT_ID), rootPath(), NO_DRIVER_STATE, null));
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
    mutableSiblings.add(tool(id(0), "call-1", ToolInvocationStatus.READY));
    ThreadContext.ToolActive context =
        assertInstanceOf(
            ThreadContext.ToolActive.class,
            classify(
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
    mutableSiblings.add(tool(id(1), "call-2", ToolInvocationStatus.READY));
    assertEquals(1, context.siblings().size());
    assertThrows(
        UnsupportedOperationException.class,
        () -> context.siblings().add(tool(id(2), "call-3", ToolInvocationStatus.READY)));
    assertThrows(
        UnsupportedOperationException.class,
        () -> context.calls().add(new ToolCallMessageContent("call-9", "bash", "bash", "{}")));
  }

  // -----------------------------------------------------------------------------------------------
  // 构造 helper
  // -----------------------------------------------------------------------------------------------

  /**
   * 分类入口：模型状态的替身必须与本次输入的 Thread 与 open turn 精确一致——真实 Driver 只按 (threadId, open turn) 锁定
   * generation，跨 Thread/跨 turn 的状态不是合法输入，因此在进入分类器前显式校验并 fail closed；没有 open turn 时只校验 Thread 身份，“无
   * open turn 却带 Driver 状态”仍由分类器自身拒绝。
   */
  private ThreadContext classify(
      ThreadState thread,
      EntryPath path,
      LockedDriverState driverState,
      List<ToolInvocation> toolSiblings) {
    if (driverState instanceof TestLockedState state) {
      state.requireLockedFor(thread.id(), path.openTurnStart().map(Entry::id).orElse(null));
    }
    return classifier.classify(thread, path, driverState, toolSiblings);
  }

  private static ThreadState thread(UUID headEntryId) {
    return new ThreadState(
        THREAD_ID,
        SESSION_ID,
        null,
        headEntryId,
        CREATION_REQUEST_HASH,
        "main",
        THREAD_ID,
        false,
        SpawnTrigger.USER,
        ThreadConstraints.NONE,
        ThreadExecutionControl.RUNNABLE,
        0L,
        1,
        0,
        NOW,
        NOW);
  }

  private static Entry entry(UUID id, UUID parentId, EntryPayload payload) {
    return new Entry(id, SESSION_ID, parentId, payload, NOW);
  }

  private static EntryPath rootPath() {
    return new EntryPath(List.of(entry(ROOT_ID, null, new RootPayload(branchSettings()))));
  }

  private static EntryPath openUserPath() {
    return openUserPath(TURN_START_ID);
  }

  /** 指定 open turn 的同形 path：用于跨 turn 的负例（owner 仍是本 Thread）。 */
  private static EntryPath openUserPath(UUID turnStartEntryId) {
    return new EntryPath(
        List.of(
            entry(ROOT_ID, null, new RootPayload(branchSettings())),
            entry(
                turnStartEntryId,
                ROOT_ID,
                new TurnStartPayload(TurnStartReason.INPUT, branchSettings(), THREAD_ID)),
            entry(USER_ID, turnStartEntryId, userMessage())));
  }

  private static EntryPath assistantPath(List<String> callIds) {
    return new EntryPath(
        List.of(
            entry(ROOT_ID, null, new RootPayload(branchSettings())),
            entry(
                TURN_START_ID,
                ROOT_ID,
                new TurnStartPayload(TurnStartReason.INPUT, branchSettings(), THREAD_ID)),
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
                new TurnStartPayload(TurnStartReason.INPUT, branchSettings(), THREAD_ID)),
            entry(USER_ID, TURN_START_ID, userMessage()),
            entry(
                ERROR_ID,
                USER_ID,
                new AssistantErrorPayload(new AssistantError("MODEL_FAILED", "boom"), null))));
  }

  private static EntryPath closedTurnPath(boolean continueModel) {
    return new EntryPath(
        List.of(
            entry(ROOT_ID, null, new RootPayload(branchSettings())),
            entry(
                TURN_START_ID,
                ROOT_ID,
                new TurnStartPayload(TurnStartReason.INPUT, branchSettings(), THREAD_ID)),
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
    GenerationStopReason stopReason =
        callIds.isEmpty() ? GenerationStopReason.COMPLETE : GenerationStopReason.COMPLETE;
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.ASSISTANT, contents),
        new AssistantMessageMetadata(stopReason, usage()),
        null);
  }

  /**
   * 构造旧 ModelInvocation 形状的 Driver 状态替身。
   *
   * <p>替身只承载模型身份；它是否与本次分类输入的 (Thread, open turn) 一致，由 {@link #classify} 在进入分类器前 显式校验（见 {@link
   * TestLockedState#requireLockedFor}），因此跨 Thread/跨 turn 的模型永远到不了分类器。
   */
  private static LockedDriverState model(
      UUID modelThreadId,
      UUID modelTurnStartEntryId,
      UUID requestHeadEntryId,
      ModelInvocationStatus status,
      ProviderResponse result,
      UUID resultEntryId) {
    int attempt = status == ModelInvocationStatus.READY ? 0 : 1;
    ModelInvocationError error =
        status == ModelInvocationStatus.FAILED
            ? new ModelInvocationError(ProviderErrorKind.TRANSIENT, "boom")
            : null;
    ModelInvocation model =
        new ModelInvocation(
            MODEL_ID,
            modelThreadId,
            modelTurnStartEntryId,
            requestHeadEntryId,
            modelRequest(),
            status,
            attempt,
            null,
            result,
            error,
            resultEntryId,
            List.of(),
            NOW,
            NOW);
    return new TestLockedState(model);
  }

  /** 旧 ModelInvocation 形状的 Driver 状态：只暴露中立的 activity 与身份。 */
  private static final class TestLockedState implements LockedDriverState {

    private final ModelInvocation model;

    private TestLockedState(ModelInvocation model) {
      this.model = model;
    }

    /**
     * 显式校验本状态与本次分类输入的锁定键一致：真实 Driver 只按 (threadId, open turn) 命中自己的 generation， 跨 Thread/跨 turn
     * 的状态不是合法输入，必须 fail closed 而不是交给分类器降级处理。
     *
     * @param threadId 本次分类输入的 Thread 身份
     * @param openTurnStartEntryId 本次分类输入 path 的 open turn；没有 open turn 时为 {@code null}，此时只校验 Thread
     *     身份，“无 open turn 却带 Driver 状态”由分类器自身拒绝
     */
    private void requireLockedFor(UUID threadId, UUID openTurnStartEntryId) {
      if (!model.threadId().equals(threadId)) {
        throw new IllegalStateException(
            "driver state is locked for thread "
                + model.threadId()
                + " but the classified thread is "
                + threadId);
      }
      if (openTurnStartEntryId != null && !model.turnStartEntryId().equals(openTurnStartEntryId)) {
        throw new IllegalStateException(
            "driver state is locked for turn "
                + model.turnStartEntryId()
                + " but the open turn is "
                + openTurnStartEntryId);
      }
    }

    @Override
    public DriverActivity activity() {
      if (model.resultEntryId() != null) {
        return DriverActivity.NONE;
      }
      return model.status().isTerminal() ? DriverActivity.RESPONSE_PENDING : DriverActivity.ACTIVE;
    }

    @Override
    public UUID requestHeadEntryId() {
      return model.requestHeadEntryId();
    }

    @Override
    public UUID resultEntryId() {
      return model.resultEntryId();
    }

    @Override
    public GenerationProjection projection() {
      return null;
    }

    @Override
    public UUID generationId() {
      return model.id();
    }
  }

  /** 无 Driver 状态的本地实现：生产实现的 NoDriverState 是私有嵌套类，测试不可见。 */
  private static final class NoDriverState implements LockedDriverState {

    @Override
    public DriverActivity activity() {
      return DriverActivity.NONE;
    }

    @Override
    public UUID requestHeadEntryId() {
      return null;
    }

    @Override
    public UUID resultEntryId() {
      return null;
    }

    @Override
    public GenerationProjection projection() {
      return null;
    }
  }

  private static ToolInvocation tool(
      UUID invocationId, String callId, ToolInvocationStatus status) {
    ToolApproval approval =
        status == ToolInvocationStatus.SUCCEEDED || status == ToolInvocationStatus.RUNNING
            ? ToolApproval.notRequired()
            : null;
    int attempt =
        status == ToolInvocationStatus.READY || status == ToolInvocationStatus.FAILED ? 0 : 1;
    ToolResult result =
        status == ToolInvocationStatus.SUCCEEDED
            ? new ToolResult(callId, List.of(new TextResultContent("tool ok")), false, "{}")
            : null;
    ToolInvocationError error =
        status == ToolInvocationStatus.FAILED ? new ToolInvocationError("FAILED", "boom") : null;
    return new ToolInvocation(
        invocationId,
        THREAD_ID,
        TURN_START_ID,
        ASSISTANT_ID,
        Math.toIntExact(invocationId.getLeastSignificantBits()),
        new ToolCall(callId, "bash", "{}"),
        toolBinding(),
        status,
        attempt,
        approval,
        result,
        error,
        NOW,
        NOW);
  }

  /** 属于另一 Thread 的 sibling（ownership 校验用）。 */
  private static ToolInvocation foreignTool(int callIndex, String callId) {
    return new ToolInvocation(
        id(MODEL_ID.getLeastSignificantBits() * 100L + callIndex),
        OTHER_THREAD_ID,
        TURN_START_ID,
        ASSISTANT_ID,
        callIndex,
        new ToolCall(callId, "bash", "{}"),
        toolBinding(),
        ToolInvocationStatus.READY,
        0,
        null,
        null,
        null,
        NOW,
        NOW);
  }

  private static ModelRequestSpec modelRequest() {
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        new UUID(0L, 1L),
        modelDescriptor(),
        new ModelVariant("v1"),
        1024,
        "Test system instruction.",
        List.of(),
        List.of(),
        ProviderCacheControl.none());
  }

  private static ToolBinding toolBinding() {
    return new ToolBinding(
        new AgentToolDefinition(
            new ToolDescriptor(
                "bash",
                "description of bash",
                "bash",
                new InputSchema("arguments", Map.of(), Set.of(), false),
                ToolSideEffect.READ_ONLY,
                Duration.ofSeconds(30)),
            ToolVisibility.SELECTABLE),
        new ContributorBinding("core", "bash", List.of()),
        EnvironmentSupport.NONE,
        null,
        null);
  }

  private static ModelDescriptor modelDescriptor() {
    return new ModelDescriptor(
        "provider", "model", "model", Set.of(ModelInputModality.TEXT), true, true);
  }

  private static ProviderResponse response(List<String> callIds) {
    List<ProviderToolCall> calls = new ArrayList<>();
    for (String callId : callIds) {
      calls.add(new ProviderToolCall(callId, "bash", "{}"));
    }
    GenerationStopReason stopReason =
        callIds.isEmpty() ? GenerationStopReason.COMPLETE : GenerationStopReason.COMPLETE;
    return new ProviderResponse(
        "response text", "", calls, stopReason, usage(), "req-1", null, "{}");
  }

  private static ModelUsage usage() {
    return new ModelUsage(1L, 2L, 0L, 0L, Map.of(), 0L, 3L);
  }

  private static BranchSettings branchSettings() {
    return new BranchSettings("agent", ModelSelection.model("model", "v1"), null);
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
