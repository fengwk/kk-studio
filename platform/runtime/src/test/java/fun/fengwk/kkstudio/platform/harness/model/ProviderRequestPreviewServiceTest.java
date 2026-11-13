package fun.fengwk.kkstudio.platform.harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.SetExecutionCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryCurrentState;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.EntrySlice;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.RootPayload;
import fun.fengwk.kkstudio.harness.api.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.api.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadKind;
import fun.fengwk.kkstudio.harness.api.thread.ThreadLiveState;
import fun.fengwk.kkstudio.harness.api.thread.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelGroupExecution;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelGroupMemberExecution;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.harness.builtin.query.ProviderRequestPreviewService;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessProviderRequestPreviewDTO;
import fun.fengwk.kkstudio.platform.harness.model.ProviderResolutionService.ResolvedExecution;
import fun.fengwk.kkstudio.platform.harness.thread.command.DatabaseTurnResolver;
import fun.fengwk.kkstudio.platform.harness.thread.command.LiveTurnPlan;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link ProviderRequestPreviewService} 的历史重建失败矩阵与只读契约。
 *
 * <p>测试意图：历史请求前缀必须严格止于目标输出的 parent，压缩子执行必须通过冻结 join 定位；规划拒绝与 adapter 不支持的详情绝不外泄。规划/物化/编码本身由集成测试与
 * provider slice 的逐字节回归负责，这里配合受控快照确保拒绝发生在任何 Provider I/O 之前。
 */
class ProviderRequestPreviewServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
  private static final String REQUEST_HASH = "0".repeat(64);
  private static final int CONTEXT_WINDOW = 100_000;
  private static final int MAX_OUTPUT_TOKENS = 16_384;

  /** 阈值压缩触发事实：90_002 + 可见尾部 token 必然越过 softThreshold(CONTEXT_WINDOW)。 */
  private static final ModelUsage OVER_THRESHOLD_USAGE =
      new ModelUsage(90_000L, 2L, 0L, 0L, Map.of(), 0L, 90_002L);

  private static final ModelUsage BELOW_THRESHOLD_USAGE =
      new ModelUsage(1_000L, 2L, 0L, 0L, Map.of(), 0L, 1_002L);
  private static final BranchSettings SETTINGS =
      new BranchSettings("agent", BuiltinExecution.of(new ModelSelection("model", "v1")), null);

  private static final UUID SESSION_ID = id(1L);
  private static final UUID THREAD_ID = id(2L);
  private static final UUID ROOT_ID = id(3L);
  private static final UUID TURN_ONE_START_ID = id(4L);
  private static final UUID TURN_ONE_USER_ID = id(5L);
  private static final UUID TURN_ONE_ASSISTANT_ID = id(6L);
  private static final UUID TURN_ONE_END_ID = id(7L);
  private static final UUID TURN_TWO_START_ID = id(8L);
  private static final UUID TURN_TWO_USER_ID = id(9L);
  private static final UUID TURN_TWO_ASSISTANT_ID = id(10L);
  private static final UUID TURN_TWO_END_ID = id(11L);
  private static final UUID TOOL_RESULT_ID = id(16L);
  private static final UUID INVOCATION_ID = id(17L);
  private static final UUID LATER_USER_ID = id(18L);
  private static final UUID TURN_THREE_START_ID = id(19L);
  private static final UUID TURN_THREE_ASSISTANT_ID = id(20L);
  private static final UUID COMPACTION_ENTRY_ID = id(21L);
  private static final UUID COMPACTION_TURN_START_ID = id(22L);
  private static final UUID COMPACTION_END_ID = id(23L);
  private static final UUID CHILD_SESSION_ID = id(40L);
  private static final UUID CHILD_THREAD_ID = id(41L);
  private static final UUID COMPACTION_JOIN_ID = id(42L);
  private static final UUID CHILD_ROOT_ID = id(43L);
  private static final UUID CHILD_INPUT_START_ID = id(44L);
  private static final UUID CHILD_PROMPT_ID = id(45L);
  private static final UUID CHILD_TOOL_CALL_ID = id(46L);
  private static final UUID CHILD_TOOL_RESULT_ID = id(47L);
  private static final UUID CHILD_INPUT_END_ID = id(48L);
  private static final UUID CHILD_FINAL_START_ID = id(49L);
  private static final UUID CHILD_FINAL_ANSWER_ID = id(50L);
  private static final UUID CHILD_TERMINAL_END_ID = id(51L);
  private static final UUID CHILD_LATER_START_ID = id(52L);
  private static final UUID CHILD_LATER_USER_ID = id(53L);
  private static final UUID CHILD_LATER_ASSISTANT_ID = id(54L);
  private static final UUID CHILD_LATER_END_ID = id(55L);
  private static final int COMPACTION_OUTPUT_BUDGET = 4_096;
  private static final ModelSelection COMPACTION_MODEL = ModelSelection.model("compactor", "v1");
  private static final BranchSettings CHILD_SETTINGS =
      new BranchSettings("Compaction", BuiltinExecution.of(COMPACTION_MODEL), null);
  private static final byte[] BODY = "{\"model\":\"wire-model\"}".getBytes(StandardCharsets.UTF_8);

  /** 历史输出的记录时间：与固定 clock 明显不同，用来证明历史预览用的是记录时间而不是「现在」。 */
  private static final Instant TOOL_CALL_TIME = NOW.plusSeconds(60);

  private static final Instant TOOL_RESULT_TIME = NOW.plusSeconds(120);
  private static final Instant TURN_TWO_ASSISTANT_TIME = NOW.plusSeconds(300);
  private static final Instant LATER_USER_TIME = NOW.plusSeconds(420);
  private static final Instant LATER_ASSISTANT_TIME = NOW.plusSeconds(480);

  /** 压缩结果的记录时间：历史预览必须用它而非「现在」。 */
  private static final Instant COMPACTION_RESULT_TIME = NOW.plusSeconds(240);

  /** 压缩子最终答复（真实最后一次摘要调用）的记录时间，严格早于父压缩结果提交时间。 */
  private static final Instant CHILD_FINAL_ANSWER_TIME = NOW.plusSeconds(200);

  private HarnessRuntime runtime;
  private DatabaseTurnResolver turnResolver;
  private DatabaseProviderResolutionService providerResolution;
  private ProviderRequestPreviewService service;

  @BeforeEach
  void setUp() {
    runtime = mock(HarnessRuntime.class);
    // 预览的只读规划事实由 Runtime 门面承担：mock 直接执行其真实实现（纯委托，不读取 Runtime 状态），
    // 因此预览仍与正式发送同一算法，测试也不复制估算或路径装配逻辑。
    when(runtime.previewInputCandidatePath(
            any(), any(), anyLong(), any(), any(), anyBoolean(), nullable(String.class)))
        .thenCallRealMethod();
    when(runtime.findCompactionTurnStart(any(), any())).thenCallRealMethod();
    turnResolver = mock(DatabaseTurnResolver.class);
    providerResolution = mock(DatabaseProviderResolutionService.class);
    service = new ProviderRequestPreviewService(runtime, turnResolver, providerResolution);
  }

  @AfterEach
  void verifyReadOnlyBoundaries() {
    // 测试意图：成功及各类拒绝都只允许读取，不触达 Session 之外的写入口。
    verify(runtime, atLeast(0)).getThreadSnapshot(any());
    verify(runtime, atLeast(0)).getSessionEntries(any());
    verify(runtime, atLeast(0)).findJoin(any());
    verify(runtime, atLeast(0)).findCompactionTurnStart(any(), any());
    verifyNoMoreInteractions(runtime);
    // 预览只调用历史冻结规划与 Provider 解析，绝不经过接受/游标/压缩等其它 resolver 能力。
    verify(turnResolver, atLeast(0)).planHistorical(any(), any(), any());
    verifyNoMoreInteractions(turnResolver);
    verify(providerResolution, atLeast(0)).resolve(any(), any());
    verifyNoMoreInteractions(providerResolution);
  }

  /**
   * 测试意图：历史预览的请求前缀严格是「该输出的 parent」，因此同一用户可见回合里更早的模型调用与合法工具结果必须保留，而本次输出与其后的 （含未来回合的）历史绝不进入请求体，也绝不用整个
   * Turn 甚至整个 Session 冒充前缀。
   */
  @Test
  void previewsHistoricalOutputsAgainstTheirOwnRequestPrefix() {
    EntryPath sessionTree = toolRoundPath();
    when(runtime.getSessionEntries(SESSION_ID)).thenReturn(sessionTree.entries());
    when(turnResolver.planHistorical(any(), any(), any()))
        .thenReturn(new LiveTurnPlan.Planned(spec(), CONTEXT_WINDOW, List.of(), List.of()));
    when(providerResolution.resolve(eq(ProviderType.OPENAI), any()))
        .thenAnswer(
            invocation ->
                new ResolvedExecution(
                    invocation.getArgument(1),
                    new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofSeconds(1)),
                    policy -> null,
                    request -> BODY));

    // 第一次模型调用（带工具调用）：前缀是它自己的输入段，工具结果与后续回合都不在其中。
    HarnessProviderRequestPreviewDTO toolCallDto =
        service.previewHistorical(SESSION_ID, TURN_ONE_ASSISTANT_ID);
    assertEquals(
        HarnessProviderRequestPreviewDTO.Kind.HISTORICAL_REQUEST_PREVIEW, toolCallDto.getKind());
    assertEquals(HarnessProviderRequestPreviewDTO.HISTORICAL_NOTICE, toolCallDto.getNotice());
    assertEquals(TURN_ONE_USER_ID.toString(), toolCallDto.getSourceHeadEntryId());
    // 历史规划时间显式取该输出的记录时间，绝不用「现在」冒充。
    assertEquals(TOOL_CALL_TIME, toolCallDto.getGeneratedAt());

    // 同一回合的最终输出：前缀保留更早的模型调用与工具结果，只排除输出自身及其后的历史。
    HarnessProviderRequestPreviewDTO finalDto =
        service.previewHistorical(SESSION_ID, TURN_TWO_ASSISTANT_ID);
    assertEquals(
        HarnessProviderRequestPreviewDTO.Kind.HISTORICAL_REQUEST_PREVIEW, finalDto.getKind());
    assertEquals(HarnessProviderRequestPreviewDTO.HISTORICAL_NOTICE, finalDto.getNotice());
    assertEquals(TURN_TWO_START_ID.toString(), finalDto.getSourceHeadEntryId());
    assertEquals(TURN_TWO_ASSISTANT_TIME, finalDto.getGeneratedAt());

    ArgumentCaptor<EntryPath> pathCaptor = ArgumentCaptor.forClass(EntryPath.class);
    verify(turnResolver, times(2)).planHistorical(any(), eq(SESSION_ID), pathCaptor.capture());
    List<EntryPath> paths = pathCaptor.getAllValues();
    assertEquals(List.of(ROOT_ID, TURN_ONE_START_ID, TURN_ONE_USER_ID), ids(paths.get(0)));
    assertEquals(
        List.of(
            ROOT_ID,
            TURN_ONE_START_ID,
            TURN_ONE_USER_ID,
            TURN_ONE_ASSISTANT_ID,
            TOOL_RESULT_ID,
            TURN_ONE_END_ID,
            TURN_TWO_START_ID),
        ids(paths.get(1)));
    // 反向断言：被预览的输出及其后的历史（含未来回合）绝不进入它自己的请求前缀。
    assertFalse(
        ids(paths.get(1)).contains(TURN_TWO_ASSISTANT_ID),
        "the previewed output must not enter its own request prefix");

    // 历史预览只读 Session 全树，也不需要任何 Thread。
    verify(runtime, never()).getThreadSnapshot(any());
  }

  /**
   * 测试意图：父 COMPACTION 结果通过冻结的 child Thread / join 票据链接到真正的子执行，并以 join 冻结的 finalAnswer parent
   * 作为请求前缀；工具 loop 历史保留，child 后续续跑与最终答复本身不进入请求。child 约束必须在规划后收紧预算并关闭 cache。
   */
  @Test
  void previewsParentCompactionOutputAgainstFrozenChildFinalInvocation() {
    EntryPath parentPath = compactionOutputPath();
    EntryPath childPath = compactionChildPath();
    when(runtime.getSessionEntries(SESSION_ID)).thenReturn(parentPath.entries());
    when(runtime.findJoin(COMPACTION_JOIN_ID))
        .thenReturn(Optional.of(compactionJoin(CHILD_THREAD_ID)));
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID))
        .thenReturn(snapshot(childThread(CHILD_LATER_END_ID), childPath, List.of()));
    when(runtime.getSessionEntries(CHILD_SESSION_ID)).thenReturn(childPath.entries());
    when(turnResolver.planHistorical(eq(CHILD_THREAD_ID), eq(SESSION_ID), any()))
        .thenReturn(
            new LiveTurnPlan.Planned(
                spec(
                    8_192,
                    ProviderCacheControl.session(PromptCacheRetention.SHORT, "child-session")),
                CONTEXT_WINDOW,
                List.of(),
                List.of()));
    when(providerResolution.resolve(eq(ProviderType.OPENAI), any()))
        .thenAnswer(
            invocation ->
                new ResolvedExecution(
                    invocation.getArgument(1),
                    new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofSeconds(1)),
                    policy -> null,
                    request -> BODY));

    HarnessProviderRequestPreviewDTO dto =
        service.previewHistorical(SESSION_ID, COMPACTION_ENTRY_ID);

    assertEquals(HarnessProviderRequestPreviewDTO.Kind.HISTORICAL_REQUEST_PREVIEW, dto.getKind());
    assertEquals(HarnessProviderRequestPreviewDTO.HISTORICAL_NOTICE, dto.getNotice());
    assertEquals(CHILD_FINAL_START_ID.toString(), dto.getSourceHeadEntryId());
    assertEquals(CHILD_FINAL_ANSWER_TIME, dto.getGeneratedAt());
    assertEquals(new String(BODY, StandardCharsets.UTF_8), dto.getBodyJson());

    ArgumentCaptor<EntryPath> pathCaptor = ArgumentCaptor.forClass(EntryPath.class);
    verify(turnResolver).planHistorical(eq(CHILD_THREAD_ID), eq(SESSION_ID), pathCaptor.capture());
    assertEquals(
        List.of(
            CHILD_ROOT_ID,
            CHILD_INPUT_START_ID,
            CHILD_PROMPT_ID,
            CHILD_TOOL_CALL_ID,
            CHILD_TOOL_RESULT_ID,
            CHILD_INPUT_END_ID,
            CHILD_FINAL_START_ID),
        ids(pathCaptor.getValue()));
    // 冻结 finalAnswer 自身以及同一 child Thread 的后续续跑都不进入旧压缩请求。
    assertFalse(ids(pathCaptor.getValue()).contains(CHILD_FINAL_ANSWER_ID));
    assertFalse(ids(pathCaptor.getValue()).contains(CHILD_LATER_START_ID));
    verify(runtime).getSessionEntries(CHILD_SESSION_ID);
    verify(turnResolver, never()).planLive(any(), any(), any());

    // 规划 spec 的输出预算被 child Thread 约束收紧，cache 仍遵循 variant。
    ArgumentCaptor<ProviderRequest> requestCaptor = ArgumentCaptor.forClass(ProviderRequest.class);
    verify(providerResolution).resolve(eq(ProviderType.OPENAI), requestCaptor.capture());
    assertEquals(COMPACTION_OUTPUT_BUDGET, requestCaptor.getValue().outputTokens());
    assertEquals(
        ProviderCacheControl.session(PromptCacheRetention.SHORT, CHILD_SESSION_ID.toString()),
        requestCaptor.getValue().cacheControl());
  }

  /**
   * 测试意图：缺失、错误子身份或不在冻结执行里的 finalAnswer 都在任何规划之前以 typed unavailable 拒绝，绝不回退到 child 当前 head 或父的零工具请求。
   */
  @Test
  void rejectsCompactionPreviewWhenFrozenChildExecutionCannotBeProven() {
    EntryPath parentPath = compactionOutputPath();
    when(runtime.getSessionEntries(SESSION_ID)).thenReturn(parentPath.entries());

    when(runtime.findJoin(COMPACTION_JOIN_ID)).thenReturn(Optional.empty());
    assertFrozenCompactionRejected();

    when(runtime.findJoin(COMPACTION_JOIN_ID)).thenReturn(Optional.of(compactionJoin(id(99L))));
    assertFrozenCompactionRejected();

    when(runtime.findJoin(COMPACTION_JOIN_ID))
        .thenReturn(Optional.of(compactionJoin(CHILD_THREAD_ID)));
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID))
        .thenReturn(snapshot(childThread(CHILD_LATER_END_ID), compactionChildPath(), List.of()));
    // child Session 中不存在 join 冻结的 finalAnswer：关联不完整，不得猜测。
    when(runtime.getSessionEntries(CHILD_SESSION_ID))
        .thenReturn(List.of(childEntries().getFirst()));
    assertFrozenCompactionRejected();

    verify(turnResolver, never()).planHistorical(any(), any(), any());
    verify(turnResolver, never()).planLive(any(), any(), any());
    verify(providerResolution, never()).resolve(any(), any());
  }

  @Test
  void frozenCompactionOnlyTranslatesDomainNotFound() {
    // 迁移后子线程缺失仍是预览不可用；其他领域失败不能被掩盖。
    when(runtime.getSessionEntries(SESSION_ID)).thenReturn(compactionOutputPath().entries());
    when(runtime.findJoin(COMPACTION_JOIN_ID))
        .thenReturn(Optional.of(compactionJoin(CHILD_THREAD_ID)));
    DomainException missing = DomainException.notFound("thread", "child missing");
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID)).thenThrow(missing);
    DomainException rejected =
        assertThrows(
            DomainException.class,
            () -> service.previewHistorical(SESSION_ID, COMPACTION_ENTRY_ID));
    assertEquals(DomainErrorCode.CONFLICT, rejected.code());
    assertEquals("PREVIEW_UNSUPPORTED", rejected.reason());
    assertSame(missing, rejected.getCause());

    DomainException other = DomainException.validation("thread", "invalid");
    doThrow(other).when(runtime).getThreadSnapshot(CHILD_THREAD_ID);
    assertSame(
        other,
        assertThrows(
            DomainException.class,
            () -> service.previewHistorical(SESSION_ID, COMPACTION_ENTRY_ID)));
    DomainException unrelatedMissing = DomainException.notFound("session", "gone");
    doThrow(unrelatedMissing).when(runtime).getThreadSnapshot(CHILD_THREAD_ID);
    assertSame(
        unrelatedMissing,
        assertThrows(
            DomainException.class,
            () -> service.previewHistorical(SESSION_ID, COMPACTION_ENTRY_ID)));
  }

  /**
   * 测试意图：join 指向的 finalAnswer 必须是真实 ASSISTANT MESSAGE，而不是“恰好带 metadata”的其它 payload；错误结果以 typed
   * unavailable 拒绝，绝不强转成 500。
   */
  @Test
  void rejectsCompactionPreviewWhenFrozenFinalAnswerIsNotAnAssistantMessage() {
    when(runtime.getSessionEntries(SESSION_ID)).thenReturn(compactionOutputPath().entries());
    when(runtime.findJoin(COMPACTION_JOIN_ID))
        .thenReturn(Optional.of(compactionJoin(CHILD_THREAD_ID)));
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID))
        .thenReturn(snapshot(childThread(CHILD_LATER_END_ID), compactionChildPath(), List.of()));
    List<Entry> wrongFinalAnswer = new ArrayList<>(childEntries());
    for (int index = 0; index < wrongFinalAnswer.size(); index++) {
      if (wrongFinalAnswer.get(index).id().equals(CHILD_FINAL_ANSWER_ID)) {
        wrongFinalAnswer.set(
            index,
            new Entry(
                CHILD_FINAL_ANSWER_ID,
                CHILD_SESSION_ID,
                CHILD_FINAL_START_ID,
                new CompactionPayload("wrong result type", compactionMetadata()),
                CHILD_FINAL_ANSWER_TIME));
      }
    }
    when(runtime.getSessionEntries(CHILD_SESSION_ID)).thenReturn(wrongFinalAnswer);

    assertFrozenCompactionRejected();
    verify(turnResolver, never()).planHistorical(any(), any(), any());
    verify(providerResolution, never()).resolve(any(), any());
  }

  /** 测试意图：child 约束必须是正且不超过父冻结预算；null 表示无上限，比冻结预算更宽，不能当作可信冻结执行。更宽上限同样拒绝。 */
  @Test
  void rejectsCompactionPreviewWhenChildOutputCapIsMissingOrWiderThanFrozenBudget() {
    when(runtime.getSessionEntries(SESSION_ID)).thenReturn(compactionOutputPath().entries());
    when(runtime.findJoin(COMPACTION_JOIN_ID))
        .thenReturn(Optional.of(compactionJoin(CHILD_THREAD_ID)));

    when(runtime.getThreadSnapshot(CHILD_THREAD_ID))
        .thenReturn(
            snapshot(
                childThread(CHILD_LATER_END_ID, new ThreadConstraints(null, false)),
                compactionChildPath(),
                List.of()));
    assertFrozenCompactionRejected();

    when(runtime.getThreadSnapshot(CHILD_THREAD_ID))
        .thenReturn(
            snapshot(
                childThread(
                    CHILD_LATER_END_ID, new ThreadConstraints(COMPACTION_OUTPUT_BUDGET + 1, false)),
                compactionChildPath(),
                List.of()));
    assertFrozenCompactionRejected();

    verify(turnResolver, never()).planHistorical(any(), any(), any());
    verify(providerResolution, never()).resolve(any(), any());
  }

  /** 测试意图：child 约束比父冻结预算更紧时接受，并让实际请求沿用更紧的上限。 */
  @Test
  void acceptsTighterChildOutputCapForCompactionPreview() {
    stubPreviewableCompactionChild(
        new ThreadConstraints(COMPACTION_OUTPUT_BUDGET / 2, false), childEntries(), 8_192);

    service.previewHistorical(SESSION_ID, COMPACTION_ENTRY_ID);

    ArgumentCaptor<ProviderRequest> requestCaptor = ArgumentCaptor.forClass(ProviderRequest.class);
    verify(providerResolution).resolve(eq(ProviderType.OPENAI), requestCaptor.capture());
    assertEquals(COMPACTION_OUTPUT_BUDGET / 2, requestCaptor.getValue().outputTokens());
  }

  /** 测试意图：压缩历史预览在应用 child 约束时保留模型组，并按首个实际成员物化。 */
  @Test
  void compactionPreviewRetainsGroupAndSelectedVariantUnderConstraints() {
    ModelRequestSpec base =
        spec(8_192, ProviderCacheControl.session(PromptCacheRetention.SHORT, "child-session"));
    ModelDescriptor actual = base.model();
    ModelDescriptor logical =
        new ModelDescriptor(
            actual.providerName(),
            "group",
            actual.modelId(),
            actual.inputModalities(),
            actual.tools(),
            actual.reasoning());
    var member = new ModelGroupMemberExecution(base.providerType(), actual, base.variant());
    var group = new ModelGroupExecution("group", 1L, List.of(member), Set.of(429), 0);
    var groupSpec =
        new ModelRequestSpec(
            base.providerType(),
            logical,
            base.variant(),
            base.outputTokens(),
            base.systemInstruction(),
            base.toolBindings(),
            base.subagentBindings(),
            group);

    when(runtime.getSessionEntries(SESSION_ID)).thenReturn(compactionOutputPath().entries());
    when(runtime.findJoin(COMPACTION_JOIN_ID))
        .thenReturn(Optional.of(compactionJoin(CHILD_THREAD_ID)));
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID))
        .thenReturn(
            snapshot(
                childThread(
                    CHILD_LATER_END_ID, new ThreadConstraints(COMPACTION_OUTPUT_BUDGET / 2, false)),
                compactionChildPath(),
                List.of()));
    when(runtime.getSessionEntries(CHILD_SESSION_ID)).thenReturn(childEntries());
    when(turnResolver.planHistorical(eq(CHILD_THREAD_ID), eq(SESSION_ID), any()))
        .thenReturn(new LiveTurnPlan.Planned(groupSpec, CONTEXT_WINDOW, List.of(), List.of()));
    when(providerResolution.resolve(eq(ProviderType.OPENAI), any()))
        .thenAnswer(
            invocation ->
                new ResolvedExecution(
                    invocation.getArgument(1),
                    new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofSeconds(1)),
                    policy -> null,
                    request -> BODY));

    var dto = service.previewHistorical(SESSION_ID, COMPACTION_ENTRY_ID);

    ArgumentCaptor<ProviderRequest> requestCaptor = ArgumentCaptor.forClass(ProviderRequest.class);
    verify(providerResolution).resolve(eq(ProviderType.OPENAI), requestCaptor.capture());
    assertEquals(COMPACTION_OUTPUT_BUDGET / 2, requestCaptor.getValue().outputTokens());
    assertEquals(
        ProviderCacheControl.session(PromptCacheRetention.SHORT, CHILD_SESSION_ID.toString()),
        requestCaptor.getValue().cacheControl());
    assertEquals(base.variant(), requestCaptor.getValue().variant());
    assertEquals("model", requestCaptor.getValue().model().modelName());
    assertEquals("group", dto.getModelName());
  }

  private void stubPreviewableCompactionChild(
      ThreadConstraints constraints, List<Entry> entries, int plannedOutputTokens) {
    when(runtime.getSessionEntries(SESSION_ID)).thenReturn(compactionOutputPath().entries());
    when(runtime.findJoin(COMPACTION_JOIN_ID))
        .thenReturn(Optional.of(compactionJoin(CHILD_THREAD_ID)));
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID))
        .thenReturn(
            snapshot(
                childThread(CHILD_LATER_END_ID, constraints), compactionChildPath(), List.of()));
    when(runtime.getSessionEntries(CHILD_SESSION_ID)).thenReturn(entries);
    when(turnResolver.planHistorical(eq(CHILD_THREAD_ID), eq(SESSION_ID), any()))
        .thenReturn(
            new LiveTurnPlan.Planned(
                spec(
                    plannedOutputTokens,
                    ProviderCacheControl.session(PromptCacheRetention.SHORT, "child-session")),
                CONTEXT_WINDOW,
                List.of(),
                List.of()));
    when(providerResolution.resolve(eq(ProviderType.OPENAI), any()))
        .thenAnswer(
            invocation ->
                new ResolvedExecution(
                    invocation.getArgument(1),
                    new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofSeconds(1)),
                    policy -> null,
                    request -> BODY));
  }

  private void assertFrozenCompactionRejected() {
    DomainException rejected =
        assertThrows(
            DomainException.class,
            () -> service.previewHistorical(SESSION_ID, COMPACTION_ENTRY_ID));
    assertEquals(DomainErrorCode.CONFLICT, rejected.code());
    assertEquals("PREVIEW_UNSUPPORTED", rejected.reason());
  }

  /** ROOT + 一个关闭 INPUT turn + 其 parent 为 COMPACTION TURN_START 的压缩结果。 */
  private static EntryPath compactionOutputPath() {
    UUID inputStart = id(31L);
    UUID user = id(32L);
    UUID assistant = id(33L);
    UUID end = id(34L);
    CompactionStart start =
        new CompactionStart(
            CompactionPhase.FULL,
            CompactionTrigger.THRESHOLD,
            BuiltinExecution.of(COMPACTION_MODEL),
            (long) COMPACTION_OUTPUT_BUDGET,
            assistant,
            null,
            null,
            CHILD_THREAD_ID,
            COMPACTION_JOIN_ID);
    return new EntryPath(
        List.of(
            new Entry(ROOT_ID, SESSION_ID, null, new RootPayload(SETTINGS), NOW),
            new Entry(inputStart, SESSION_ID, ROOT_ID, inputTurnStart(), NOW),
            new Entry(
                user,
                SESSION_ID,
                inputStart,
                new MessagePayload(AgentMessage.user("compaction source"), null, null),
                NOW.plusSeconds(1)),
            new Entry(
                assistant,
                SESSION_ID,
                user,
                assistantMessage("prior reply", BELOW_THRESHOLD_USAGE),
                NOW.plusSeconds(2)),
            new Entry(
                end,
                SESSION_ID,
                assistant,
                new TurnEndPayload(inputStart, TurnEndOutcome.COMPLETED, false, null, null),
                NOW.plusSeconds(3)),
            new Entry(
                COMPACTION_TURN_START_ID,
                SESSION_ID,
                end,
                new TurnStartPayload(
                    TurnStartReason.COMPACTION,
                    SETTINGS,
                    THREAD_ID,
                    CONTEXT_WINDOW,
                    MAX_OUTPUT_TOKENS,
                    start,
                    null),
                COMPACTION_RESULT_TIME.minusSeconds(1)),
            new Entry(
                COMPACTION_ENTRY_ID,
                SESSION_ID,
                COMPACTION_TURN_START_ID,
                new CompactionPayload("compaction summary", compactionMetadata()),
                COMPACTION_RESULT_TIME),
            new Entry(
                COMPACTION_END_ID,
                SESSION_ID,
                COMPACTION_ENTRY_ID,
                new TurnEndPayload(
                    COMPACTION_TURN_START_ID, TurnEndOutcome.COMPLETED, false, null, null),
                COMPACTION_RESULT_TIME.plusSeconds(1))));
  }

  /**
   * 冻结压缩子 Session：一次工具 loop 后产生最终摘要，随后同一 child Thread 又发生了一次后续续跑。finalAnswer 的 parent 是续写 turn 的
   * TURN_START，即最后那次摘要调用的 request head。
   */
  private static EntryPath compactionChildPath() {
    return new EntryPath(childEntries());
  }

  private static List<Entry> childEntries() {
    return List.of(
        new Entry(CHILD_ROOT_ID, CHILD_SESSION_ID, null, new RootPayload(CHILD_SETTINGS), NOW),
        new Entry(
            CHILD_INPUT_START_ID,
            CHILD_SESSION_ID,
            CHILD_ROOT_ID,
            childTurnStart(TurnStartReason.INPUT),
            NOW.plusSeconds(10)),
        new Entry(
            CHILD_PROMPT_ID,
            CHILD_SESSION_ID,
            CHILD_INPUT_START_ID,
            new MessagePayload(AgentMessage.user("summarize"), null, null),
            NOW.plusSeconds(20)),
        new Entry(
            CHILD_TOOL_CALL_ID,
            CHILD_SESSION_ID,
            CHILD_PROMPT_ID,
            new MessagePayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(new ToolCallMessageContent("call-1", "read", "builtin:read", "{}"))),
                new AssistantMessageMetadata(
                    GenerationStopReason.COMPLETE, BELOW_THRESHOLD_USAGE, null),
                null),
            NOW.plusSeconds(30)),
        new Entry(
            CHILD_TOOL_RESULT_ID,
            CHILD_SESSION_ID,
            CHILD_TOOL_CALL_ID,
            new MessagePayload(
                new AgentMessage(
                    AgentMessageRole.TOOL,
                    List.of(
                        new ToolResultMessageContent(
                            "call-1",
                            "read",
                            "builtin:read",
                            List.of(new TextMessageContent("body")),
                            false,
                            "{}"))),
                null,
                new ToolResultMetadata(
                    INVOCATION_ID,
                    CHILD_TOOL_CALL_ID,
                    "call-1",
                    0,
                    ToolResultStatus.SUCCEEDED,
                    false,
                    null,
                    null,
                    null)),
            NOW.plusSeconds(40)),
        new Entry(
            CHILD_INPUT_END_ID,
            CHILD_SESSION_ID,
            CHILD_TOOL_RESULT_ID,
            new TurnEndPayload(CHILD_INPUT_START_ID, TurnEndOutcome.COMPLETED, true, null, null),
            NOW.plusSeconds(50)),
        new Entry(
            CHILD_FINAL_START_ID,
            CHILD_SESSION_ID,
            CHILD_INPUT_END_ID,
            childTurnStart(TurnStartReason.CONTINUATION),
            CHILD_FINAL_ANSWER_TIME.minusSeconds(1)),
        new Entry(
            CHILD_FINAL_ANSWER_ID,
            CHILD_SESSION_ID,
            CHILD_FINAL_START_ID,
            new MessagePayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(new TextMessageContent("compaction summary"))),
                compactionMetadata(),
                null),
            CHILD_FINAL_ANSWER_TIME),
        new Entry(
            CHILD_TERMINAL_END_ID,
            CHILD_SESSION_ID,
            CHILD_FINAL_ANSWER_ID,
            new TurnEndPayload(CHILD_FINAL_START_ID, TurnEndOutcome.COMPLETED, false, null, null),
            CHILD_FINAL_ANSWER_TIME.plusSeconds(1)),
        new Entry(
            CHILD_LATER_START_ID,
            CHILD_SESSION_ID,
            CHILD_TERMINAL_END_ID,
            childTurnStart(TurnStartReason.INPUT),
            CHILD_FINAL_ANSWER_TIME.plusSeconds(2)),
        new Entry(
            CHILD_LATER_USER_ID,
            CHILD_SESSION_ID,
            CHILD_LATER_START_ID,
            new MessagePayload(AgentMessage.user("later child run"), null, null),
            CHILD_FINAL_ANSWER_TIME.plusSeconds(3)),
        new Entry(
            CHILD_LATER_ASSISTANT_ID,
            CHILD_SESSION_ID,
            CHILD_LATER_USER_ID,
            assistantMessage("later child answer", BELOW_THRESHOLD_USAGE),
            CHILD_FINAL_ANSWER_TIME.plusSeconds(4)),
        new Entry(
            CHILD_LATER_END_ID,
            CHILD_SESSION_ID,
            CHILD_LATER_ASSISTANT_ID,
            new TurnEndPayload(CHILD_LATER_START_ID, TurnEndOutcome.COMPLETED, false, null, null),
            CHILD_FINAL_ANSWER_TIME.plusSeconds(5)));
  }

  private static TurnStartPayload childTurnStart(TurnStartReason reason) {
    return new TurnStartPayload(
        reason,
        CHILD_SETTINGS,
        CHILD_THREAD_ID,
        CONTEXT_WINDOW,
        COMPACTION_OUTPUT_BUDGET,
        null,
        null);
  }

  private static AssistantMessageMetadata compactionMetadata() {
    return new AssistantMessageMetadata(GenerationStopReason.COMPLETE, BELOW_THRESHOLD_USAGE, 50L);
  }

  private static ThreadJoin compactionJoin(UUID childThreadId) {
    return new ThreadJoin(
        COMPACTION_JOIN_ID,
        REQUEST_HASH,
        THREAD_ID,
        childThreadId,
        1L,
        "Compaction",
        null,
        0L,
        CHILD_TERMINAL_END_ID,
        CHILD_FINAL_ANSWER_ID,
        null,
        NOW,
        NOW,
        JoinDelivery.SETTLE_OWNER_TURN,
        null);
  }

  private static ThreadState childThread(UUID headEntryId) {
    return childThread(headEntryId, new ThreadConstraints(COMPACTION_OUTPUT_BUDGET, false));
  }

  private static ThreadState childThread(UUID headEntryId, ThreadConstraints constraints) {
    return new ThreadState(
        CHILD_THREAD_ID,
        CHILD_SESSION_ID,
        THREAD_ID,
        headEntryId,
        REQUEST_HASH,
        "compaction-child",
        THREAD_ID,
        false,
        ThreadKind.COMPACTION,
        constraints,
        ThreadExecutionControl.RUNNABLE,
        0L,
        2L,
        1L,
        NOW,
        NOW);
  }

  /** 测试意图：历史预览只接受模型输出本身；非模型输出与不属于该 Session 的 id 都在任何规划之前拒绝。 */
  @Test
  void rejectsHistoricalPreviewForEntriesThatAreNotModelOutputs() {
    EntryPath sessionTree = toolRoundPath();
    when(runtime.getSessionEntries(SESSION_ID)).thenReturn(sessionTree.entries());

    DomainException notAnOutput =
        assertThrows(
            DomainException.class, () -> service.previewHistorical(SESSION_ID, TURN_ONE_USER_ID));
    assertEquals(DomainErrorCode.VALIDATION, notAnOutput.code());
    assertTrue(notAnOutput.getMessage().contains("assistant model output"));

    DomainException foreign =
        assertThrows(DomainException.class, () -> service.previewHistorical(SESSION_ID, id(99L)));
    assertEquals(DomainErrorCode.VALIDATION, foreign.code());
    assertTrue(foreign.getMessage().contains("does not belong to the session"));
    verify(turnResolver, never()).planLive(any(), any(), any());
  }

  /** 便捷：以固定 scope 构造命令批（idempotencyKey 显式给定，避免随机 UUID 影响断言）。 */
  private static AcceptCommandsCommand command(
      AcceptCommandsTarget target, NewThreadCommand... commands) {
    return new AcceptCommandsCommand(target, List.of(commands));
  }

  private static NewThreadCommand userMessage(String text) {
    return new NewThreadCommand(
        new UserMessageCommandPayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)))),
        id(200L));
  }

  private enum ThreadCommandKind {
    SET_EXECUTION,
    SET_AGENT
  }

  private static NewThreadCommand setting(ThreadCommandKind kind) {
    return switch (kind) {
      case SET_EXECUTION -> new NewThreadCommand(
          new SetExecutionCommandPayload(BuiltinExecution.of(new ModelSelection("model", "v1"))),
          id(201L));
      case SET_AGENT -> new NewThreadCommand(new SetAgentCommandPayload("agent"), id(202L));
    };
  }

  private static ModelRequestSpec spec() {
    return spec(1024, ProviderCacheControl.none());
  }

  private static ModelRequestSpec spec(int outputTokens, ProviderCacheControl cacheControl) {
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        new ModelDescriptor(
            "provider", "model", "wire-model", Set.of(ModelInputModality.TEXT), false, false),
        new ModelVariant("v1", null, cacheControl.retention(), null),
        outputTokens,
        "system instruction",
        List.of(),
        List.of());
  }

  private static ThreadSnapshot snapshot(
      ThreadState thread, EntryPath path, List<ThreadCommand> queued) {
    return snapshot(thread, path, queued, ThreadLiveState.IDLE_OR_HISTORICAL);
  }

  private static ThreadSnapshot snapshot(
      ThreadState thread, EntryPath path, List<ThreadCommand> queued, ThreadLiveState liveState) {
    return new ThreadSnapshot(
        thread,
        EntryCurrentState.from(path),
        new EntrySlice(null, path.entries()),
        queued,
        null,
        List.of(),
        liveState,
        List.of(),
        0,
        false,
        List.of(),
        false);
  }

  private static ThreadState thread(UUID headEntryId, long nextCommandSequence) {
    return new ThreadState(
        THREAD_ID,
        SESSION_ID,
        null,
        headEntryId,
        REQUEST_HASH,
        "thread",
        THREAD_ID,
        false,
        ThreadKind.USER,
        ThreadConstraints.NONE,
        ThreadExecutionControl.RUNNABLE,
        0L,
        nextCommandSequence,
        1L,
        NOW,
        NOW);
  }

  /** 两个已关闭 INPUT turn 的完整历史：head 为第二个 TURN_END，usage 决定阈值压缩是否触发。 */
  private static EntryPath closedTurnPath(ModelUsage usage) {
    // 只有最新 turn 携带被测用量：更早 turn 若同为高压会被判定为 overflow-recovery retry，从而掩盖阈值判定本身。
    List<Entry> entries = new ArrayList<>();
    entries.add(new Entry(ROOT_ID, SESSION_ID, null, new RootPayload(SETTINGS), NOW));
    entries.add(new Entry(TURN_ONE_START_ID, SESSION_ID, ROOT_ID, inputTurnStart(), NOW));
    entries.add(
        new Entry(
            TURN_ONE_USER_ID,
            SESSION_ID,
            TURN_ONE_START_ID,
            new MessagePayload(AgentMessage.user("history user " + "h".repeat(50_000)), null, null),
            NOW));
    entries.add(
        new Entry(
            TURN_ONE_ASSISTANT_ID,
            SESSION_ID,
            TURN_ONE_USER_ID,
            assistantMessage("history assistant " + "a".repeat(50_000), BELOW_THRESHOLD_USAGE),
            NOW));
    entries.add(
        new Entry(
            TURN_ONE_END_ID,
            SESSION_ID,
            TURN_ONE_ASSISTANT_ID,
            new TurnEndPayload(TURN_ONE_START_ID, TurnEndOutcome.COMPLETED, false, null, null),
            NOW));
    entries.add(new Entry(TURN_TWO_START_ID, SESSION_ID, TURN_ONE_END_ID, inputTurnStart(), NOW));
    entries.add(
        new Entry(
            TURN_TWO_USER_ID,
            SESSION_ID,
            TURN_TWO_START_ID,
            // 最新 turn 的 USER 必须足够长：否则 cut 会落在首条 USER 上，使可摘要前缀为空（真实运行的形状是长 USER + 短
            // ASSISTANT）。
            new MessagePayload(AgentMessage.user("second user " + "u".repeat(50_000)), null, null),
            NOW));
    entries.add(
        new Entry(
            TURN_TWO_ASSISTANT_ID,
            SESSION_ID,
            TURN_TWO_USER_ID,
            assistantMessage("second assistant", usage),
            NOW));
    entries.add(
        new Entry(
            TURN_TWO_END_ID,
            SESSION_ID,
            TURN_TWO_ASSISTANT_ID,
            new TurnEndPayload(TURN_TWO_START_ID, TurnEndOutcome.COMPLETED, false, null, null),
            NOW));
    return new EntryPath(entries);
  }

  /** head 为 continueModel 的 TURN_END：分类器判定 CONTINUATION_DUE，预览必须拒绝。 */
  private static EntryPath continuationDuePath() {
    List<Entry> entries = new ArrayList<>(closedTurnPath(BELOW_THRESHOLD_USAGE).entries());
    entries.set(
        entries.size() - 1,
        new Entry(
            TURN_TWO_END_ID,
            SESSION_ID,
            TURN_TWO_ASSISTANT_ID,
            new TurnEndPayload(TURN_TWO_START_ID, TurnEndOutcome.COMPLETED, true, null, null),
            NOW));
    return new EntryPath(entries);
  }

  private static TurnStartPayload inputTurnStart() {
    return new TurnStartPayload(
        TurnStartReason.INPUT, SETTINGS, THREAD_ID, CONTEXT_WINDOW, MAX_OUTPUT_TOKENS, null, null);
  }

  private static TurnStartPayload continuationTurnStart() {
    return new TurnStartPayload(
        TurnStartReason.CONTINUATION,
        SETTINGS,
        THREAD_ID,
        CONTEXT_WINDOW,
        MAX_OUTPUT_TOKENS,
        null,
        null);
  }

  /**
   * 一个「工具调用 → 工具结果 → 续写模型调用」的完整历史，外加此后第三个回合：用来固定「请求前缀只到该输出的 parent」这一事实。
   *
   * <p>ASSISTANT 在同一个 open Turn 内只能出现一次，因此同一次用户输入下的多次模型调用表现为 INPUT turn 后的 CONTINUATION turn；前缀
   * 必须跨越这两个 turn，而不是从续写 turn 的 TURN_START 开始。
   */
  private static EntryPath toolRoundPath() {
    List<Entry> entries = new ArrayList<>();
    entries.add(new Entry(ROOT_ID, SESSION_ID, null, new RootPayload(SETTINGS), NOW));
    entries.add(new Entry(TURN_ONE_START_ID, SESSION_ID, ROOT_ID, inputTurnStart(), NOW));
    entries.add(
        new Entry(
            TURN_ONE_USER_ID,
            SESSION_ID,
            TURN_ONE_START_ID,
            new MessagePayload(AgentMessage.user("first user"), null, null),
            NOW));
    entries.add(
        new Entry(
            TURN_ONE_ASSISTANT_ID,
            SESSION_ID,
            TURN_ONE_USER_ID,
            toolCallMessage(),
            TOOL_CALL_TIME));
    entries.add(
        new Entry(
            TOOL_RESULT_ID,
            SESSION_ID,
            TURN_ONE_ASSISTANT_ID,
            toolResultMessage(),
            TOOL_RESULT_TIME));
    entries.add(
        new Entry(
            TURN_ONE_END_ID,
            SESSION_ID,
            TOOL_RESULT_ID,
            new TurnEndPayload(TURN_ONE_START_ID, TurnEndOutcome.COMPLETED, true, null, null),
            TOOL_RESULT_TIME));
    entries.add(
        new Entry(
            TURN_TWO_START_ID,
            SESSION_ID,
            TURN_ONE_END_ID,
            continuationTurnStart(),
            TURN_TWO_ASSISTANT_TIME));
    entries.add(
        new Entry(
            TURN_TWO_ASSISTANT_ID,
            SESSION_ID,
            TURN_TWO_START_ID,
            assistantMessage("final answer", BELOW_THRESHOLD_USAGE),
            TURN_TWO_ASSISTANT_TIME));
    entries.add(
        new Entry(
            TURN_TWO_END_ID,
            SESSION_ID,
            TURN_TWO_ASSISTANT_ID,
            new TurnEndPayload(TURN_TWO_START_ID, TurnEndOutcome.COMPLETED, false, null, null),
            LATER_USER_TIME));
    entries.add(
        new Entry(
            TURN_THREE_START_ID, SESSION_ID, TURN_TWO_END_ID, inputTurnStart(), LATER_USER_TIME));
    entries.add(
        new Entry(
            LATER_USER_ID,
            SESSION_ID,
            TURN_THREE_START_ID,
            new MessagePayload(AgentMessage.user("later user"), null, null),
            LATER_USER_TIME));
    entries.add(
        new Entry(
            TURN_THREE_ASSISTANT_ID,
            SESSION_ID,
            LATER_USER_ID,
            assistantMessage("later answer", BELOW_THRESHOLD_USAGE),
            LATER_ASSISTANT_TIME));
    return new EntryPath(entries);
  }

  /** ASSISTANT 工具调用：结构上必须由配对的 ToolResult 紧跟。 */
  private static MessagePayload toolCallMessage() {
    return new MessagePayload(
        new AgentMessage(
            AgentMessageRole.ASSISTANT,
            List.of(new ToolCallMessageContent("call-1", "read", "builtin:read", "{}"))),
        new AssistantMessageMetadata(GenerationStopReason.COMPLETE, BELOW_THRESHOLD_USAGE, null),
        null);
  }

  /** 合法的真实工具结果：callIndex/toolCallId/assistantEntryId 与上一 Assistant 的调用严格配对。 */
  private static MessagePayload toolResultMessage() {
    return new MessagePayload(
        new AgentMessage(
            AgentMessageRole.TOOL,
            List.of(
                new ToolResultMessageContent(
                    "call-1",
                    "read",
                    "builtin:read",
                    List.of(new TextMessageContent("body")),
                    false,
                    "{}"))),
        null,
        new ToolResultMetadata(
            INVOCATION_ID,
            TURN_ONE_ASSISTANT_ID,
            "call-1",
            0,
            ToolResultStatus.SUCCEEDED,
            false,
            null,
            null,
            null));
  }

  private static MessagePayload assistantMessage(String text, ModelUsage usage) {
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent(text))),
        new AssistantMessageMetadata(GenerationStopReason.COMPLETE, usage, null),
        null);
  }

  private static MessagePayload assistantWithModel(String text, String model, String variant) {
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent(text))),
        new AssistantMessageMetadata(
            GenerationStopReason.COMPLETE,
            BELOW_THRESHOLD_USAGE,
            null,
            ModelSelection.model(model, variant),
            null,
            null),
        null);
  }

  /** 取路径上最后一条 USER 消息的 Entry：预览在草稿起点之后追加的输入边界。 */
  private static Entry lastUserEntry(EntryPath path) {
    for (int i = path.entries().size() - 1; i >= 0; i--) {
      Entry entry = path.entries().get(i);
      if (entry.payload() instanceof MessagePayload message
          && message.message().role() == AgentMessageRole.USER) {
        return entry;
      }
    }
    throw new AssertionError("candidate path has no user message");
  }

  /** 取路径上最后一条 USER 消息：预览追加的输入边界。 */
  private static MessagePayload lastUserMessage(EntryPath path) {
    return (MessagePayload) lastUserEntry(path).payload();
  }

  private static List<UUID> ids(EntryPath path) {
    return path.entries().stream().map(Entry::id).toList();
  }

  private static UUID id(long value) {
    return new UUID(0L, value);
  }
}
