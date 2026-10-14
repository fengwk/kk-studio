package fun.fengwk.kkstudio.platform.harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfigProvider;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadYoloPolicy;
import fun.fengwk.kkstudio.harness.runtime.thread.command.GoalCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.platform.harness.model.ProviderRequestPreviewUnavailableException.Reason;
import fun.fengwk.kkstudio.platform.harness.model.ProviderResolutionService.ResolvedExecution;
import fun.fengwk.kkstudio.platform.harness.thread.command.DatabaseTurnResolver;
import fun.fengwk.kkstudio.platform.harness.thread.command.LiveTurnPlan;
import fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessProviderRequestPreviewDTO;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * {@link ProviderRequestPreviewService} 的失败矩阵与只读契约。
 *
 * <p>测试意图：预览必须在 cursor/idle/queued 判定、压缩判定、附件 READY 判定与资源归属上与正式发送同源且 fail-closed；附件只读 peek（绝不
 * lock/delete/retain），命令形状只接受 SET_* 前缀 + 末尾 USER_MESSAGE，规划拒绝与 adapter 不支持的 详情绝不外泄。规划/物化/编码本身由集成测试与
 * provider slice 的逐字节回归负责，这里用真实 planner/classifier/preparer 配合 受控快照，确保拒绝发生在任何 Provider I/O 之前。
 */
class ProviderRequestPreviewServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
  private static final String REQUEST_HASH = "0".repeat(64);
  private static final int CONTEXT_WINDOW = 100_000;
  private static final int MAX_OUTPUT_TOKENS = 16_384;

  /** 阈值压缩触发事实：90_002 + 可见尾部 token 必然越过 softThreshold(CONTEXT_WINDOW)。 */
  private static final ModelUsage OVER_THRESHOLD_USAGE =
      new ModelUsage(90_000L, 2L, 0L, 0L, 0L, 0L, 90_002L);

  private static final ModelUsage BELOW_THRESHOLD_USAGE =
      new ModelUsage(1_000L, 2L, 0L, 0L, 0L, 0L, 1_002L);
  private static final BranchSettings SETTINGS =
      new BranchSettings("agent", new ModelSelection("provider", "model", "v1"), null);

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
  private static final UUID UPLOAD_ID = id(12L);
  private static final UUID ATTACHMENT_BLOB_ID = id(13L);
  private static final UUID OWNED_BLOB_ID = id(14L);
  private static final UUID GENERATION_ID = id(15L);
  private static final UUID TOOL_RESULT_ID = id(16L);
  private static final UUID INVOCATION_ID = id(17L);
  private static final UUID LATER_USER_ID = id(18L);
  private static final UUID TURN_THREE_START_ID = id(19L);
  private static final UUID TURN_THREE_ASSISTANT_ID = id(20L);
  private static final UUID COMPACTION_ENTRY_ID = id(21L);
  private static final UUID COMPACTION_TURN_START_ID = id(22L);
  private static final byte[] BODY = "{\"model\":\"wire-model\"}".getBytes(StandardCharsets.UTF_8);

  /** 历史输出的记录时间：与固定 clock 明显不同，用来证明历史预览用的是记录时间而不是「现在」。 */
  private static final Instant TOOL_CALL_TIME = NOW.plusSeconds(60);

  private static final Instant TOOL_RESULT_TIME = NOW.plusSeconds(120);
  private static final Instant TURN_TWO_ASSISTANT_TIME = NOW.plusSeconds(300);
  private static final Instant LATER_USER_TIME = NOW.plusSeconds(420);
  private static final Instant LATER_ASSISTANT_TIME = NOW.plusSeconds(480);

  /** 压缩结果的记录时间：历史预览必须用它而非「现在」。 */
  private static final Instant COMPACTION_RESULT_TIME = NOW.plusSeconds(240);

  private HarnessRuntime runtime;
  private DatabaseTurnResolver turnResolver;
  private DatabaseProviderResolutionService providerResolution;
  private StorageUploadService uploadService;
  private SessionBlobRefManager refManager;
  private StorageBlobManager blobManager;
  private ProviderRequestPreviewService service;

  @BeforeEach
  void setUp() {
    runtime = mock(HarnessRuntime.class);
    turnResolver = mock(DatabaseTurnResolver.class);
    providerResolution = mock(DatabaseProviderResolutionService.class);
    uploadService = mock(StorageUploadService.class);
    refManager = mock(SessionBlobRefManager.class);
    blobManager = mock(StorageBlobManager.class);
    CompactionConfigProvider compactionConfigProvider = () -> CompactionConfig.DEFAULT;
    service =
        new ProviderRequestPreviewService(
            runtime,
            turnResolver,
            providerResolution,
            compactionConfigProvider,
            refManager,
            blobManager,
            uploadService,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @AfterEach
  void verifyReadOnlyBoundaries() {
    // 测试意图：成功及各类拒绝都只允许读取——既不接受命令、消费附件或改变资源引用，也不触达 Session 之外的写入口。
    verify(runtime, atLeast(0)).getThreadSnapshot(any());
    verify(runtime, atLeast(0)).getSessionEntries(any());
    verify(uploadService, atLeast(0)).peekReady(any());
    verify(refManager, atLeast(0)).contains(any(), any());
    verify(blobManager, atLeast(0)).getBlob(any());
    verifyNoMoreInteractions(runtime, uploadService, refManager, blobManager);
    // 预览只调用冻结规划与 Provider 解析，绝不经过接受/游标/压缩等任何其它 resolver 能力。
    verify(turnResolver, atLeast(0)).planLive(any(), any(), any());
    verify(turnResolver, atLeast(0)).planHistorical(any(), any(), any());
    verifyNoMoreInteractions(turnResolver);
    verify(providerResolution, atLeast(0)).resolve(any(), any(), any());
    verifyNoMoreInteractions(providerResolution);
  }

  @Test
  void exceptionRequiresReasonAndSafeMessageAndRetainsOptionalCause() {
    // 测试意图：禁止无 reason 的拒绝，cause 只用于内部诊断，不改变公开的安全消息。
    assertThrows(
        NullPointerException.class,
        () -> new ProviderRequestPreviewUnavailableException(null, "safe"));
    assertThrows(
        NullPointerException.class,
        () -> new ProviderRequestPreviewUnavailableException(Reason.PREVIEW_ENCODING_FAILED, null));
    RuntimeException cause = new RuntimeException("private detail");
    ProviderRequestPreviewUnavailableException error =
        new ProviderRequestPreviewUnavailableException(
            Reason.PREVIEW_ENCODING_FAILED, "safe", cause);
    assertEquals(cause, error.getCause());
    assertEquals("safe", error.getMessage());
    assertEquals(Reason.PREVIEW_ENCODING_FAILED, error.reason());
  }

  /** 测试意图：preview 只覆盖既有 THREAD（path 与 target 必须一致），命令形状只允许 SET_* + 末尾 USER_MESSAGE；不再有 owner 门禁。 */
  @Test
  void rejectsForeignTargetAndNonUserMessageShape() {
    // target 不是 THREAD：草稿预览不接受新建语义。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.preview(
                THREAD_ID,
                new AcceptCommandsCommand(
                    new AcceptCommandsTarget.NewRootSession(SESSION_ID, THREAD_ID, SETTINGS, false),
                    List.of(userMessage("hi")))));
    // target threadId 与 path 不一致。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.preview(
                THREAD_ID,
                command(new AcceptCommandsTarget.Thread(id(99L), ROOT_ID, 1L), userMessage("hi"))));
    // GOAL 终止输入属于 Goal 专属功能，不是草稿消息预览。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.preview(
                THREAD_ID,
                command(
                    new AcceptCommandsTarget.Thread(THREAD_ID, ROOT_ID, 1L),
                    setting(ThreadCommandKind.SET_MODEL),
                    new NewThreadCommand(new GoalCommandPayload("goal"), id(22L)))));
    // 多于一条 USER_MESSAGE 或 USER_MESSAGE 不在末尾一律拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.preview(
                THREAD_ID,
                command(
                    new AcceptCommandsTarget.Thread(THREAD_ID, ROOT_ID, 1L),
                    userMessage("first"),
                    userMessage("second"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.preview(
                THREAD_ID,
                command(
                    new AcceptCommandsTarget.Thread(THREAD_ID, ROOT_ID, 1L),
                    userMessage("first"),
                    setting(ThreadCommandKind.SET_MODEL))));
    // 形状拒绝必须发生在任何快照读取之前。
    verify(runtime, never()).getThreadSnapshot(any());
  }

  /** 测试意图：cursor 漂移与非空 queued 都在任何规划之前 fail closed。 */
  @Test
  void rejectsStaleCursorAndQueuedCommands() {
    ThreadState thread = thread(TURN_TWO_END_ID, 1L);
    EntryPath idlePath = closedTurnPath(OVER_THRESHOLD_USAGE);
    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot(thread, idlePath, List.of()));

    // head 漂移（调用方 cursor 与快照不一致）。
    ProviderRequestPreviewUnavailableException staleHead =
        assertThrows(
            ProviderRequestPreviewUnavailableException.class,
            () ->
                service.preview(
                    THREAD_ID,
                    command(
                        new AcceptCommandsTarget.Thread(THREAD_ID, ROOT_ID, 1L),
                        userMessage("hi"))));
    assertTrue(staleHead.getMessage().contains("head/next command sequence"));
    assertEquals(Reason.PREVIEW_STALE_CURSOR, staleHead.reason());
    // sequence 漂移。
    ProviderRequestPreviewUnavailableException staleSequence =
        assertThrows(
            ProviderRequestPreviewUnavailableException.class,
            () ->
                service.preview(
                    THREAD_ID,
                    command(
                        new AcceptCommandsTarget.Thread(THREAD_ID, TURN_TWO_END_ID, 2L),
                        userMessage("hi"))));
    assertEquals(Reason.PREVIEW_STALE_CURSOR, staleSequence.reason());

    // cursor 一致但仍有 queued 命令：预览不会替调用方消费它们。
    when(runtime.getThreadSnapshot(THREAD_ID))
        .thenReturn(
            snapshot(
                thread,
                idlePath,
                List.of(
                    new ThreadCommand(
                        THREAD_ID,
                        1L,
                        userMessage("queued").payload(),
                        id(31L),
                        REQUEST_HASH,
                        null,
                        null,
                        null,
                        NOW))));
    ProviderRequestPreviewUnavailableException queued =
        assertThrows(
            ProviderRequestPreviewUnavailableException.class,
            () ->
                service.preview(
                    THREAD_ID,
                    command(
                        new AcceptCommandsTarget.Thread(THREAD_ID, TURN_TWO_END_ID, 1L),
                        userMessage("hi"))));
    assertTrue(queued.getMessage().contains("queued commands"));
    assertEquals(Reason.PREVIEW_QUEUED_COMMANDS, queued.reason());
    verify(turnResolver, never()).planLive(any(), any(), any());
    verify(providerResolution, never()).resolve(any(), any(), any());
    // 预览只读取快照，不触达接受路径的其它任何能力。
  }

  /**
   * 测试意图：Thread 非空闲（此例 head 是 continueModel 的 TURN_END，下一步必然是 continuation）时拒绝，而不是预览一个 INPUT 请求。
   */
  @Test
  void rejectsThreadThatIsNotIdle() {
    EntryPath continuationDue = continuationDuePath();
    when(runtime.getThreadSnapshot(THREAD_ID))
        .thenReturn(snapshot(thread(TURN_TWO_END_ID, 1L), continuationDue, List.of()));

    ProviderRequestPreviewUnavailableException error =
        assertThrows(
            ProviderRequestPreviewUnavailableException.class,
            () ->
                service.preview(
                    THREAD_ID,
                    command(
                        new AcceptCommandsTarget.Thread(THREAD_ID, TURN_TWO_END_ID, 1L),
                        userMessage("hi"))));
    assertTrue(error.getMessage().contains("not idle"));
    assertEquals(Reason.PREVIEW_THREAD_BUSY, error.reason());
    verify(turnResolver, never()).planLive(any(), any(), any());
  }

  /** 测试意图：下一步必定是自动压缩时明确拒绝（不运行压缩模型）；阈值以下的历史则继续走到真实规划边界。 */
  @Test
  void rejectsWhenNextStepIsAutomaticCompaction() {
    ThreadState thread = thread(TURN_TWO_END_ID, 1L);

    when(runtime.getThreadSnapshot(THREAD_ID))
        .thenReturn(snapshot(thread, closedTurnPath(OVER_THRESHOLD_USAGE), List.of()));
    ProviderRequestPreviewUnavailableException compaction =
        assertThrows(
            ProviderRequestPreviewUnavailableException.class,
            () ->
                service.preview(
                    THREAD_ID,
                    command(
                        new AcceptCommandsTarget.Thread(THREAD_ID, TURN_TWO_END_ID, 1L),
                        userMessage("hi"))));
    assertTrue(compaction.getMessage().contains("compaction"));
    assertEquals(Reason.PREVIEW_COMPACTION_REQUIRED, compaction.reason());
    verify(turnResolver, never()).planLive(any(), any(), any());

    // 阴性对照：同一形状但 usage 远低于阈值时压缩门放行，拒绝只来自真实的规划结果（证明这是一个真实判定而非恒真拒绝）。
    when(runtime.getThreadSnapshot(THREAD_ID))
        .thenReturn(snapshot(thread, closedTurnPath(BELOW_THRESHOLD_USAGE), List.of()));
    when(turnResolver.planLive(eq(THREAD_ID), any(), eq(NOW)))
        .thenReturn(new LiveTurnPlan.Rejected("PLANNING_FAILED", "provider detail leaked"));
    ProviderRequestPreviewUnavailableException planning =
        assertThrows(
            ProviderRequestPreviewUnavailableException.class,
            () ->
                service.preview(
                    THREAD_ID,
                    command(
                        new AcceptCommandsTarget.Thread(THREAD_ID, TURN_TWO_END_ID, 1L),
                        userMessage("hi"))));
    assertEquals(Reason.PREVIEW_PLANNING_FAILED, planning.reason());
    assertEquals("request cannot be planned for preview", planning.getMessage());
    assertFalse(planning.getMessage().contains("leaked"));
  }

  /**
   * 测试意图：附件只做 READY 的只读 peek（权威 blobId/文件名进入候选请求），不 lock/delete/retain；同一份共享 transform 也必须让 已持有的
   * RESOURCE 与设置前缀进入最终请求；响应只暴露请求体与稳定 cursor。
   */
  @Test
  void peeksAttachmentWithoutConsumingAndProjectsFinalBody() {
    EntryPath idle = closedTurnPath(BELOW_THRESHOLD_USAGE);
    when(runtime.getThreadSnapshot(THREAD_ID))
        .thenReturn(snapshot(thread(TURN_TWO_END_ID, 3L), idle, List.of()));
    when(uploadService.peekReady(UPLOAD_ID))
        .thenReturn(new StorageUploadService.ReadyUpload(ATTACHMENT_BLOB_ID, "authoritative.png"));
    when(refManager.contains(SESSION_ID, OWNED_BLOB_ID)).thenReturn(true);
    ModelRequestSpec spec = spec();
    when(turnResolver.planLive(eq(THREAD_ID), any(), eq(NOW)))
        .thenReturn(new LiveTurnPlan.Planned(spec, CONTEXT_WINDOW, List.of(), List.of()));
    when(providerResolution.resolve(eq(ProviderType.OPENAI), eq(GENERATION_ID), any()))
        .thenAnswer(
            invocation ->
                new ResolvedExecution(
                    invocation.getArgument(2),
                    new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofSeconds(1)),
                    policy -> null,
                    request -> BODY));

    HarnessProviderRequestPreviewDTO dto =
        service.preview(
            THREAD_ID,
            command(
                new AcceptCommandsTarget.Thread(THREAD_ID, TURN_TWO_END_ID, 3L),
                setting(ThreadCommandKind.SET_MODEL),
                setting(ThreadCommandKind.SET_AGENT),
                new NewThreadCommand(
                    new UserMessageCommandPayload(
                        new AgentMessage(
                            AgentMessageRole.USER,
                            List.of(
                                new TextMessageContent("preview please"),
                                new AttachmentMessageContent(UPLOAD_ID, null),
                                ResourceMessageContent.media(
                                    OWNED_BLOB_ID, "owned.pdf", null, null)))),
                    id(61L))));

    assertEquals(HarnessProviderRequestPreviewDTO.DRAFT_REQUEST_PREVIEW, dto.getKind());
    assertEquals(NOW, dto.getGeneratedAt());
    assertEquals("OPENAI", dto.getProviderType());
    assertEquals("model", dto.getModelName());
    assertEquals(BODY.length, dto.getBodyByteSize());
    assertEquals(new String(BODY, StandardCharsets.UTF_8), dto.getBodyJson());
    assertEquals(TURN_TWO_END_ID.toString(), dto.getSourceHeadEntryId());
    assertEquals(HarnessProviderRequestPreviewDTO.DRAFT_NOTICE, dto.getNotice());

    // 候选历史由同一 transform 构造：新 USER 消息同时携带文本、权威附件事实与已持有 RESOURCE，且顺序不变。
    ArgumentCaptor<EntryPath> pathCaptor = ArgumentCaptor.forClass(EntryPath.class);
    verify(turnResolver).planLive(eq(THREAD_ID), pathCaptor.capture(), eq(NOW));
    MessagePayload frozen = lastUserMessage(pathCaptor.getValue());
    List<AgentMessageContent> contents = frozen.message().contents();
    assertEquals(3, contents.size());
    assertInstanceOf(TextMessageContent.class, contents.get(0));
    ResourceMessageContent attachment =
        assertInstanceOf(ResourceMessageContent.class, contents.get(1));
    assertEquals(ATTACHMENT_BLOB_ID, attachment.blobId());
    assertEquals("authoritative.png", attachment.name());
    assertNull(attachment.imageTier());
    ResourceMessageContent owned = assertInstanceOf(ResourceMessageContent.class, contents.get(2));
    assertEquals(OWNED_BLOB_ID, owned.blobId());
    assertEquals("owned.pdf", owned.name());
    // 设置前缀进入同一 TURN_START 快照（SET_MODEL 生效）。
    assertEquals("model", pathCaptor.getValue().baseSettings().model().modelName());

    // 预览绝不消费、绝不 retain、绝不写任何 owner 关系。
    verify(uploadService).peekReady(UPLOAD_ID);
    verify(uploadService, never()).lockReady(any());
    verify(uploadService, never()).delete(any());
    verify(refManager, never()).retainRef(any(), any());
    verify(refManager, never()).releaseRef(any(), any());
  }

  /** 测试意图：未 READY 的附件与跨 Session 资源分别以 409（不可预览）与 400（越权）拒绝，绝不先物化再失败。 */
  @Test
  void rejectsPendingUploadAndCrossSessionResource() {
    when(runtime.getThreadSnapshot(THREAD_ID))
        .thenReturn(
            snapshot(
                thread(TURN_TWO_END_ID, 1L), closedTurnPath(BELOW_THRESHOLD_USAGE), List.of()));
    AcceptCommandsTarget.Thread target =
        new AcceptCommandsTarget.Thread(THREAD_ID, TURN_TWO_END_ID, 1L);

    when(uploadService.peekReady(UPLOAD_ID)).thenThrow(new StorageVerificationException("PENDING"));
    ProviderRequestPreviewUnavailableException pending =
        assertThrows(
            ProviderRequestPreviewUnavailableException.class,
            () ->
                service.preview(
                    THREAD_ID,
                    command(
                        target,
                        new NewThreadCommand(
                            new UserMessageCommandPayload(
                                new AgentMessage(
                                    AgentMessageRole.USER,
                                    List.of(new AttachmentMessageContent(UPLOAD_ID, null)))),
                            id(71L)))));
    assertTrue(pending.getMessage().contains("not READY"));
    assertEquals(Reason.PREVIEW_ATTACHMENT_NOT_READY, pending.reason());

    when(refManager.contains(SESSION_ID, OWNED_BLOB_ID)).thenReturn(false);
    IllegalArgumentException foreign =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.preview(
                    THREAD_ID,
                    command(
                        target,
                        new NewThreadCommand(
                            new UserMessageCommandPayload(
                                new AgentMessage(
                                    AgentMessageRole.USER,
                                    List.of(
                                        ResourceMessageContent.media(
                                            OWNED_BLOB_ID, "foreign.pdf", null, null)))),
                            id(72L)))));
    assertTrue(foreign.getMessage().contains("not owned"));
    verify(turnResolver, never()).planLive(any(), any(), any());
  }

  /** 测试意图：adapter 未实现预览能力时明确拒绝（绝不伪装成空体），Provider 编码失败只回显稳定类别而不外泄原因文本。 */
  @Test
  void rejectsUnsupportedAdapterAndSanitizesProviderFailure() {
    when(runtime.getThreadSnapshot(THREAD_ID))
        .thenReturn(
            snapshot(
                thread(TURN_TWO_END_ID, 1L), closedTurnPath(BELOW_THRESHOLD_USAGE), List.of()));
    AcceptCommandsCommand command =
        command(new AcceptCommandsTarget.Thread(THREAD_ID, TURN_TWO_END_ID, 1L), userMessage("hi"));

    doAnswer(
            invocation ->
                new ResolvedExecution(
                    invocation.getArgument(2),
                    new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofSeconds(1)),
                    policy -> null,
                    request -> {
                      throw new UnsupportedOperationException("no preview");
                    }))
        .when(providerResolution)
        .resolve(any(), any(), any());
    when(turnResolver.planLive(eq(THREAD_ID), any(), eq(NOW)))
        .thenReturn(new LiveTurnPlan.Planned(spec(), CONTEXT_WINDOW, List.of(), List.of()));
    ProviderRequestPreviewUnavailableException unsupported =
        assertThrows(
            ProviderRequestPreviewUnavailableException.class,
            () -> service.preview(THREAD_ID, command));
    assertTrue(unsupported.getMessage().contains("does not support request body preview"));
    assertEquals(Reason.PREVIEW_UNSUPPORTED, unsupported.reason());

    doAnswer(
            invocation ->
                new ResolvedExecution(
                    invocation.getArgument(2),
                    new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofSeconds(1)),
                    policy -> null,
                    request -> {
                      throw new ProviderException(
                          ProviderErrorKind.INVALID_REQUEST,
                          "internal url https://provider.internal.example/v1 rejected");
                    }))
        .when(providerResolution)
        .resolve(any(), any(), any());
    ProviderRequestPreviewUnavailableException invalid =
        assertThrows(
            ProviderRequestPreviewUnavailableException.class,
            () -> service.preview(THREAD_ID, command));
    assertEquals(Reason.PREVIEW_ENCODING_FAILED, invalid.reason());
    assertEquals("request body cannot be encoded for preview", invalid.getMessage());
    assertFalse(invalid.getMessage().contains("provider.internal.example"));
  }

  /** 测试意图：Provider 解析漂移（generation/type 变化）与缺失 factory 一律确定性拒绝，绝不越过解析直接编码。 */
  @Test
  void rejectsProviderResolutionDrift() {
    when(runtime.getThreadSnapshot(THREAD_ID))
        .thenReturn(
            snapshot(
                thread(TURN_TWO_END_ID, 1L), closedTurnPath(BELOW_THRESHOLD_USAGE), List.of()));
    when(turnResolver.planLive(eq(THREAD_ID), any(), eq(NOW)))
        .thenReturn(new LiveTurnPlan.Planned(spec(), CONTEXT_WINDOW, List.of(), List.of()));
    when(providerResolution.resolve(any(), any(), any()))
        .thenThrow(new IllegalArgumentException("provider connection generation drift"));

    ProviderRequestPreviewUnavailableException drift =
        assertThrows(
            ProviderRequestPreviewUnavailableException.class,
            () ->
                service.preview(
                    THREAD_ID,
                    command(
                        new AcceptCommandsTarget.Thread(THREAD_ID, TURN_TWO_END_ID, 1L),
                        userMessage("hi"))));
    assertTrue(drift.getMessage().contains("cannot resolve the current provider"));
    assertEquals(Reason.PREVIEW_PROVIDER_UNAVAILABLE, drift.reason());
  }

  /**
   * 测试意图：本地分支草稿在 Session 事实边界上预览——不创建 Thread、不做任何 Thread cursor/queued/压缩判定，候选历史延续该 Session 到草稿起点
   * 为止的全部历史；附件仍只是 READY 的只读 peek。
   */
  @Test
  void previewsLocalBranchDraftWithoutCreatingAnyThread() {
    EntryPath sessionTree = closedTurnPath(BELOW_THRESHOLD_USAGE);
    when(runtime.getSessionEntries(SESSION_ID)).thenReturn(sessionTree.entries());
    when(uploadService.peekReady(UPLOAD_ID))
        .thenReturn(new StorageUploadService.ReadyUpload(ATTACHMENT_BLOB_ID, "draft.png"));
    when(turnResolver.planLive(any(), any(), eq(NOW)))
        .thenReturn(new LiveTurnPlan.Planned(spec(), CONTEXT_WINDOW, List.of(), List.of()));
    when(providerResolution.resolve(eq(ProviderType.OPENAI), eq(GENERATION_ID), any()))
        .thenAnswer(
            invocation ->
                new ResolvedExecution(
                    invocation.getArgument(2),
                    new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofSeconds(1)),
                    policy -> null,
                    request -> BODY));

    HarnessProviderRequestPreviewDTO dto =
        service.previewDraft(
            SESSION_ID,
            TURN_TWO_END_ID,
            List.of(
                setting(ThreadCommandKind.SET_MODEL),
                new NewThreadCommand(
                    new UserMessageCommandPayload(
                        new AgentMessage(
                            AgentMessageRole.USER,
                            List.of(
                                new TextMessageContent("draft input"),
                                new AttachmentMessageContent(UPLOAD_ID, null)))),
                    id(203L))));

    assertEquals(HarnessProviderRequestPreviewDTO.DRAFT_REQUEST_PREVIEW, dto.getKind());
    assertEquals(HarnessProviderRequestPreviewDTO.DRAFT_NOTICE, dto.getNotice());
    assertEquals(NOW, dto.getGeneratedAt());
    assertEquals("OPENAI", dto.getProviderType());
    assertEquals("model", dto.getModelName());
    assertEquals(BODY.length, dto.getBodyByteSize());
    // 草稿的 source head 就是分支起点，且它自己已经存在于 durable 历史里。
    assertEquals(TURN_TWO_END_ID.toString(), dto.getSourceHeadEntryId());

    ArgumentCaptor<EntryPath> pathCaptor = ArgumentCaptor.forClass(EntryPath.class);
    verify(turnResolver).planLive(any(), pathCaptor.capture(), eq(NOW));
    EntryPath candidate = pathCaptor.getValue();
    assertEquals(
        sessionTree.entries(), candidate.entries().subList(0, sessionTree.entries().size()));
    Entry appended = lastUserEntry(candidate);
    // 草稿接在分支起点之后：新增 TURN_START 的父才是分支起点，追加的 USER 挂在它下面。
    Entry appendedStart =
        candidate.entries().stream()
            .filter(entry -> entry.payload() instanceof TurnStartPayload)
            .reduce((first, second) -> second)
            .orElseThrow();
    assertEquals(TURN_TWO_END_ID, appendedStart.parentEntryId());
    assertEquals(appendedStart.id(), appended.parentEntryId());
    // SET_MODEL 作为设置前缀冻结进 candidate 的 TURN_START，不产生模型可见消息。
    assertEquals("model", candidate.baseSettings().model().modelName());
    List<AgentMessageContent> contents = lastUserMessage(candidate).message().contents();
    assertEquals(2, contents.size());
    assertEquals("draft input", ((TextMessageContent) contents.getFirst()).text());
    ResourceMessageContent attachment =
        assertInstanceOf(ResourceMessageContent.class, contents.get(1));
    assertEquals(ATTACHMENT_BLOB_ID, attachment.blobId());
    assertEquals("draft.png", attachment.name());

    // 草稿没有 Thread：既不读 Thread 快照，也不接受命令、消费附件或建立资源归属。
    verify(runtime, never()).getThreadSnapshot(any());
    verify(uploadService).peekReady(UPLOAD_ID);
    verify(uploadService, never()).lockReady(any());
    verify(uploadService, never()).delete(any());
    verify(refManager, never()).retainRef(any(), any());
    verify(refManager, never()).releaseRef(any(), any());
  }

  /** 测试意图：ROOT 上的全新 Session 也能预览首个输入；候选历史只有 ROOT，绝不因为「还没有任何 Turn」而拒绝。 */
  @Test
  void previewsLocalBranchDraftFromTheRootOfAFreshSession() {
    EntryPath emptySession =
        new EntryPath(
            List.of(new Entry(ROOT_ID, SESSION_ID, null, new RootPayload(SETTINGS), NOW)));
    when(runtime.getSessionEntries(SESSION_ID)).thenReturn(emptySession.entries());
    when(turnResolver.planLive(any(), any(), eq(NOW)))
        .thenReturn(new LiveTurnPlan.Planned(spec(), CONTEXT_WINDOW, List.of(), List.of()));
    when(providerResolution.resolve(eq(ProviderType.OPENAI), eq(GENERATION_ID), any()))
        .thenAnswer(
            invocation ->
                new ResolvedExecution(
                    invocation.getArgument(2),
                    new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofSeconds(1)),
                    policy -> null,
                    request -> BODY));

    HarnessProviderRequestPreviewDTO dto =
        service.previewDraft(SESSION_ID, ROOT_ID, List.of(userMessage("first input")));

    assertEquals(ROOT_ID.toString(), dto.getSourceHeadEntryId());
    ArgumentCaptor<EntryPath> pathCaptor = ArgumentCaptor.forClass(EntryPath.class);
    verify(turnResolver).planLive(any(), pathCaptor.capture(), eq(NOW));
    EntryPath candidate = pathCaptor.getValue();
    assertEquals(ROOT_ID, candidate.root().id());
    assertEquals(ROOT_ID, candidate.entries().get(1).parentEntryId());
    assertEquals(
        "first input",
        ((TextMessageContent) lastUserMessage(candidate).message().contents().getFirst()).text());
  }

  /** 测试意图：草稿起点必须与 NEW_THREAD 的合法落点一致，且必须属于目标 Session；两者都在任何规划之前拒绝。 */
  @Test
  void rejectsDraftStartEntriesThatCannotCarryANewTurn() {
    EntryPath sessionTree = closedTurnPath(BELOW_THRESHOLD_USAGE);
    when(runtime.getSessionEntries(SESSION_ID)).thenReturn(sessionTree.entries());

    IllegalArgumentException midTurn =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.previewDraft(SESSION_ID, TURN_TWO_USER_ID, List.of(userMessage("hi"))));
    assertTrue(midTurn.getMessage().contains("ROOT or TURN_END"), midTurn.getMessage());

    IllegalArgumentException foreign =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.previewDraft(SESSION_ID, id(99L), List.of(userMessage("hi"))));
    assertTrue(foreign.getMessage().contains("does not belong to the session"));
    verify(turnResolver, never()).planLive(any(), any(), any());
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
    when(providerResolution.resolve(eq(ProviderType.OPENAI), eq(GENERATION_ID), any()))
        .thenAnswer(
            invocation ->
                new ResolvedExecution(
                    invocation.getArgument(2),
                    new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofSeconds(1)),
                    policy -> null,
                    request -> BODY));

    // 第一次模型调用（带工具调用）：前缀是它自己的输入段，工具结果与后续回合都不在其中。
    HarnessProviderRequestPreviewDTO toolCallDto =
        service.previewHistorical(SESSION_ID, TURN_ONE_ASSISTANT_ID);
    assertEquals(
        HarnessProviderRequestPreviewDTO.HISTORICAL_REQUEST_PREVIEW, toolCallDto.getKind());
    assertEquals(HarnessProviderRequestPreviewDTO.HISTORICAL_NOTICE, toolCallDto.getNotice());
    assertEquals(TURN_ONE_USER_ID.toString(), toolCallDto.getSourceHeadEntryId());
    // 历史规划时间显式取该输出的记录时间，绝不用「现在」冒充。
    assertEquals(TOOL_CALL_TIME, toolCallDto.getGeneratedAt());

    // 同一回合的最终输出：前缀保留更早的模型调用与工具结果，只排除输出自身及其后的历史。
    HarnessProviderRequestPreviewDTO finalDto =
        service.previewHistorical(SESSION_ID, TURN_TWO_ASSISTANT_ID);
    assertEquals(HarnessProviderRequestPreviewDTO.HISTORICAL_REQUEST_PREVIEW, finalDto.getKind());
    assertEquals(HarnessProviderRequestPreviewDTO.HISTORICAL_NOTICE, finalDto.getNotice());
    assertEquals(TURN_TWO_START_ID.toString(), finalDto.getSourceHeadEntryId());
    assertEquals(TURN_TWO_ASSISTANT_TIME, finalDto.getGeneratedAt());

    ArgumentCaptor<EntryPath> toolCallPath = ArgumentCaptor.forClass(EntryPath.class);
    verify(turnResolver).planHistorical(any(), toolCallPath.capture(), eq(TOOL_CALL_TIME));
    assertEquals(
        List.of(ROOT_ID, TURN_ONE_START_ID, TURN_ONE_USER_ID), ids(toolCallPath.getValue()));

    ArgumentCaptor<EntryPath> finalPath = ArgumentCaptor.forClass(EntryPath.class);
    verify(turnResolver).planHistorical(any(), finalPath.capture(), eq(TURN_TWO_ASSISTANT_TIME));
    assertEquals(
        List.of(
            ROOT_ID,
            TURN_ONE_START_ID,
            TURN_ONE_USER_ID,
            TURN_ONE_ASSISTANT_ID,
            TOOL_RESULT_ID,
            TURN_ONE_END_ID,
            TURN_TWO_START_ID),
        ids(finalPath.getValue()));
    // 反向断言：被预览的输出及其后的历史（含未来回合）绝不进入它自己的请求前缀。
    assertFalse(
        ids(finalPath.getValue()).contains(TURN_TWO_ASSISTANT_ID),
        "the previewed output must not enter its own request prefix");

    // 历史预览只读 Session 全树，也不需要任何 Thread。
    verify(runtime, never()).getThreadSnapshot(any());
  }

  /**
   * 测试意图：父 COMPACTION turn 自身不创建 ModelInvocation，其 provider 请求属于压缩子 Thread；父侧历史预览绝不伪造一份零工具请求，
   * 而是在任何规划与 provider 解析之前明确 typed 拒绝并指向子的真实调用。
   */
  @Test
  void rejectsHistoricalPreviewOfParentCompactionOutput() {
    EntryPath sessionTree = compactionOutputPath();
    when(runtime.getSessionEntries(SESSION_ID)).thenReturn(sessionTree.entries());

    ProviderRequestPreviewUnavailableException rejected =
        assertThrows(
            ProviderRequestPreviewUnavailableException.class,
            () -> service.previewHistorical(SESSION_ID, COMPACTION_ENTRY_ID));

    assertEquals(Reason.PREVIEW_UNSUPPORTED, rejected.reason());
    assertTrue(rejected.getMessage().contains("compaction child thread"), rejected.getMessage());
    verify(turnResolver, never()).planHistorical(any(), any(), any());
    verify(turnResolver, never()).planLive(any(), any(), any());
    verify(runtime, never()).getThreadSnapshot(any());
    verify(providerResolution, never()).resolve(any(), any(), any());
  }

  /** ROOT + 一个关闭 INPUT turn + 其 parent 为 COMPACTION TURN_START 的压缩结果。 */
  private static EntryPath compactionOutputPath() {
    UUID inputStart = id(31L);
    UUID user = id(32L);
    UUID assistant = id(33L);
    UUID end = id(34L);
    CompactionStart start =
        CompactionStart.pending(
            CompactionPhase.FULL, CompactionTrigger.THRESHOLD, assistant, null, null);
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
                    start),
                COMPACTION_RESULT_TIME.minusSeconds(1)),
            new Entry(
                COMPACTION_ENTRY_ID,
                SESSION_ID,
                COMPACTION_TURN_START_ID,
                new CompactionPayload(
                    "compaction summary",
                    new AssistantMessageMetadata(
                        GenerationStopReason.COMPLETE, BELOW_THRESHOLD_USAGE, 50L)),
                COMPACTION_RESULT_TIME)));
  }

  /** 测试意图：历史预览只接受模型输出本身；非模型输出与不属于该 Session 的 id 都在任何规划之前拒绝。 */
  @Test
  void rejectsHistoricalPreviewForEntriesThatAreNotModelOutputs() {
    EntryPath sessionTree = toolRoundPath();
    when(runtime.getSessionEntries(SESSION_ID)).thenReturn(sessionTree.entries());

    IllegalArgumentException notAnOutput =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.previewHistorical(SESSION_ID, TURN_ONE_USER_ID));
    assertTrue(notAnOutput.getMessage().contains("assistant model output"));

    IllegalArgumentException foreign =
        assertThrows(
            IllegalArgumentException.class, () -> service.previewHistorical(SESSION_ID, id(99L)));
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
    SET_MODEL,
    SET_AGENT
  }

  private static NewThreadCommand setting(ThreadCommandKind kind) {
    return switch (kind) {
      case SET_MODEL -> new NewThreadCommand(
          new SetModelCommandPayload(new ModelSelection("provider", "model", "v1")), id(201L));
      case SET_AGENT -> new NewThreadCommand(new SetAgentCommandPayload("agent"), id(202L));
    };
  }

  private static ModelRequestSpec spec() {
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        GENERATION_ID,
        new ModelDescriptor(
            "provider", "model", "wire-model", Set.of(ModelInputModality.TEXT), false, false),
        new ModelVariant("v1"),
        1024,
        "system instruction",
        List.of(),
        List.of(),
        ProviderCacheControl.none());
  }

  private static ThreadSnapshot snapshot(
      ThreadState thread, EntryPath path, List<ThreadCommand> queued) {
    return new ThreadSnapshot(thread, path, queued, null, List.of(), List.of(), List.of());
  }

  private static ThreadState thread(UUID headEntryId, long nextCommandSequence) {
    return new ThreadState(
        THREAD_ID,
        SESSION_ID,
        null,
        headEntryId,
        REQUEST_HASH,
        "thread",
        ThreadYoloPolicy.root(false),
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
        TurnStartReason.INPUT, SETTINGS, THREAD_ID, CONTEXT_WINDOW, MAX_OUTPUT_TOKENS, null);
  }

  private static TurnStartPayload continuationTurnStart() {
    return new TurnStartPayload(
        TurnStartReason.CONTINUATION, SETTINGS, THREAD_ID, CONTEXT_WINDOW, MAX_OUTPUT_TOKENS, null);
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
            null));
  }

  private static MessagePayload assistantMessage(String text, ModelUsage usage) {
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent(text))),
        new AssistantMessageMetadata(GenerationStopReason.COMPLETE, usage, null),
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
