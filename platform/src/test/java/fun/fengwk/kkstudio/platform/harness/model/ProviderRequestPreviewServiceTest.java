package fun.fengwk.kkstudio.platform.harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfigProvider;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
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
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadLifecycleStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.GoalCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.platform.harness.model.ProviderResolutionService.ResolvedExecution;
import fun.fengwk.kkstudio.platform.harness.thread.command.DatabaseTurnResolver;
import fun.fengwk.kkstudio.platform.harness.thread.command.LiveTurnPlan;
import fun.fengwk.kkstudio.platform.orchestration.HarnessCommandAcceptanceOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.platform.orchestration.OwnerType;
import fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessProviderRequestPreviewDTO;

import java.math.BigDecimal;
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
 * <p>测试意图：预览必须在 owner 校验、cursor/idle/queued 判定、压缩判定、附件 READY 判定与资源归属上与正式发送同源且 fail-closed； 附件只读
 * peek（绝不 lock/delete/retain），命令形状只接受 SET_* 前缀 + 末尾 USER_MESSAGE，规划拒绝与 adapter 不支持的
 * 详情绝不外泄。规划/物化/编码本身由集成测试与 provider slice 的逐字节回归负责，这里用真实 planner/classifier/preparer 配合
 * 受控快照，确保拒绝发生在任何 Provider I/O 之前。
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
  private static final ModelPricing PRICING =
      new ModelPricing(
          "USD",
          "standard",
          "standard",
          BigDecimal.ONE,
          "v1",
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO);
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
  private static final byte[] BODY = "{\"model\":\"wire-model\"}".getBytes(StandardCharsets.UTF_8);

  private HarnessRuntime runtime;
  private HarnessCommandAcceptanceOrchestrator acceptanceOrchestrator;
  private DatabaseTurnResolver turnResolver;
  private DatabaseProviderResolutionService providerResolution;
  private StorageUploadService uploadService;
  private SessionBlobRefManager refManager;
  private StorageBlobManager blobManager;
  private ProviderRequestPreviewService service;

  @BeforeEach
  void setUp() {
    runtime = mock(HarnessRuntime.class);
    acceptanceOrchestrator = mock(HarnessCommandAcceptanceOrchestrator.class);
    turnResolver = mock(DatabaseTurnResolver.class);
    providerResolution = mock(DatabaseProviderResolutionService.class);
    uploadService = mock(StorageUploadService.class);
    refManager = mock(SessionBlobRefManager.class);
    blobManager = mock(StorageBlobManager.class);
    CompactionConfigProvider compactionConfigProvider = () -> CompactionConfig.DEFAULT;
    service =
        new ProviderRequestPreviewService(
            runtime,
            acceptanceOrchestrator,
            turnResolver,
            providerResolution,
            compactionConfigProvider,
            refManager,
            blobManager,
            uploadService,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  /**
   * 测试意图：preview 只覆盖既有 THREAD（path 与 target 必须一致）且只服务 CHAT/CANVAS，命令形状只允许 SET_* + 末尾 USER_MESSAGE。
   */
  @Test
  void rejectsForeignTargetOwnerAndNonUserMessageShape() {
    OwnerRef chat = new OwnerRef(OwnerType.CHAT, id(20L));
    OwnerRef issueAgent = new OwnerRef(OwnerType.ISSUE_AGENT_SESSION, id(21L));

    // target 不是 THREAD：草稿预览不接受新建语义。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.preview(
                THREAD_ID,
                chat,
                new AcceptCommandsCommand(
                    new AcceptCommandsTarget.NewSession(
                        SESSION_ID, THREAD_ID, SETTINGS, null, false),
                    List.of(userMessage("hi")))));
    // target threadId 与 path 不一致。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.preview(
                THREAD_ID,
                chat,
                command(new AcceptCommandsTarget.Thread(id(99L), ROOT_ID, 1L), userMessage("hi"))));
    // Issue Agent Session 的命令由 Issue 工作流拥有。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.preview(
                THREAD_ID,
                issueAgent,
                command(
                    new AcceptCommandsTarget.Thread(THREAD_ID, ROOT_ID, 1L), userMessage("hi"))));
    // GOAL 终止输入属于 Goal 专属功能，不是草稿消息预览。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.preview(
                THREAD_ID,
                chat,
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
                chat,
                command(
                    new AcceptCommandsTarget.Thread(THREAD_ID, ROOT_ID, 1L),
                    userMessage("first"),
                    userMessage("second"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.preview(
                THREAD_ID,
                chat,
                command(
                    new AcceptCommandsTarget.Thread(THREAD_ID, ROOT_ID, 1L),
                    userMessage("first"),
                    setting(ThreadCommandKind.SET_MODEL))));
    // 形状拒绝必须发生在任何 owner 校验与快照读取之前。
    verify(acceptanceOrchestrator, never()).authorizeThread(any(), any());
    verify(runtime, never()).getThreadSnapshot(any());
  }

  /** 测试意图：owner 授权复用只读入口；cursor 漂移与非空 queued 都在任何规划之前 fail closed。 */
  @Test
  void rejectsStaleCursorAndQueuedCommandsAfterReusingReadOnlyOwnerCheck() {
    OwnerRef owner = new OwnerRef(OwnerType.CHAT, id(30L));
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
                    owner,
                    command(
                        new AcceptCommandsTarget.Thread(THREAD_ID, ROOT_ID, 1L),
                        userMessage("hi"))));
    assertTrue(staleHead.getMessage().contains("head/next command sequence"));
    // sequence 漂移。
    assertThrows(
        ProviderRequestPreviewUnavailableException.class,
        () ->
            service.preview(
                THREAD_ID,
                owner,
                command(
                    new AcceptCommandsTarget.Thread(THREAD_ID, TURN_TWO_END_ID, 2L),
                    userMessage("hi"))));

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
                    owner,
                    command(
                        new AcceptCommandsTarget.Thread(THREAD_ID, TURN_TWO_END_ID, 1L),
                        userMessage("hi"))));
    assertTrue(queued.getMessage().contains("queued commands"));
    // 预览只调用只读 owner 校验，不触达接受路径的其它任何能力。
    verify(acceptanceOrchestrator, atLeastOnce()).authorizeThread(eq(owner), eq(THREAD_ID));
    verifyNoMoreInteractions(acceptanceOrchestrator);
  }

  /**
   * 测试意图：Thread 非空闲（此例 head 是 continueModel 的 TURN_END，下一步必然是 continuation）时拒绝，而不是预览一个 INPUT 请求。
   */
  @Test
  void rejectsThreadThatIsNotIdle() {
    OwnerRef owner = new OwnerRef(OwnerType.CHAT, id(40L));
    EntryPath continuationDue = continuationDuePath();
    when(runtime.getThreadSnapshot(THREAD_ID))
        .thenReturn(snapshot(thread(TURN_TWO_END_ID, 1L), continuationDue, List.of()));

    ProviderRequestPreviewUnavailableException error =
        assertThrows(
            ProviderRequestPreviewUnavailableException.class,
            () ->
                service.preview(
                    THREAD_ID,
                    owner,
                    command(
                        new AcceptCommandsTarget.Thread(THREAD_ID, TURN_TWO_END_ID, 1L),
                        userMessage("hi"))));
    assertTrue(error.getMessage().contains("not idle"));
  }

  /** 测试意图：下一步必定是自动压缩时明确拒绝（不运行压缩模型）；阈值以下的历史则继续走到真实规划边界。 */
  @Test
  void rejectsWhenNextStepIsAutomaticCompaction() {
    OwnerRef owner = new OwnerRef(OwnerType.CHAT, id(50L));
    ThreadState thread = thread(TURN_TWO_END_ID, 1L);

    when(runtime.getThreadSnapshot(THREAD_ID))
        .thenReturn(snapshot(thread, closedTurnPath(OVER_THRESHOLD_USAGE), List.of()));
    ProviderRequestPreviewUnavailableException compaction =
        assertThrows(
            ProviderRequestPreviewUnavailableException.class,
            () ->
                service.preview(
                    THREAD_ID,
                    owner,
                    command(
                        new AcceptCommandsTarget.Thread(THREAD_ID, TURN_TWO_END_ID, 1L),
                        userMessage("hi"))));
    assertTrue(compaction.getMessage().contains("compaction"));
    verify(turnResolver, never()).planLive(any(), any());

    // 阴性对照：同一形状但 usage 远低于阈值时压缩门放行，拒绝只来自真实的规划结果（证明这是一个真实判定而非恒真拒绝）。
    when(runtime.getThreadSnapshot(THREAD_ID))
        .thenReturn(snapshot(thread, closedTurnPath(BELOW_THRESHOLD_USAGE), List.of()));
    when(turnResolver.planLive(eq(THREAD_ID), any()))
        .thenReturn(new LiveTurnPlan.Rejected("PLANNING_FAILED", "provider detail leaked"));
    ProviderRequestPreviewUnavailableException planning =
        assertThrows(
            ProviderRequestPreviewUnavailableException.class,
            () ->
                service.preview(
                    THREAD_ID,
                    owner,
                    command(
                        new AcceptCommandsTarget.Thread(THREAD_ID, TURN_TWO_END_ID, 1L),
                        userMessage("hi"))));
    assertTrue(planning.getMessage().contains("PLANNING_FAILED"));
    assertFalse(planning.getMessage().contains("leaked"));
  }

  /**
   * 测试意图：附件只做 READY 的只读 peek（权威 blobId/文件名进入候选请求），不 lock/delete/retain；同一份共享 transform 也必须让 已持有的
   * RESOURCE 与设置前缀进入最终请求；响应只暴露请求体与稳定 cursor。
   */
  @Test
  void peeksAttachmentWithoutConsumingAndProjectsFinalBody() {
    OwnerRef owner = new OwnerRef(OwnerType.CANVAS, id(60L));
    EntryPath idle = closedTurnPath(BELOW_THRESHOLD_USAGE);
    when(runtime.getThreadSnapshot(THREAD_ID))
        .thenReturn(snapshot(thread(TURN_TWO_END_ID, 3L), idle, List.of()));
    when(uploadService.peekReady(UPLOAD_ID))
        .thenReturn(new StorageUploadService.ReadyUpload(ATTACHMENT_BLOB_ID, "authoritative.png"));
    when(refManager.contains(SESSION_ID, OWNED_BLOB_ID)).thenReturn(true);
    ModelRequestSpec spec = spec();
    when(turnResolver.planLive(eq(THREAD_ID), any()))
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
            owner,
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

    assertEquals(HarnessProviderRequestPreviewDTO.KIND, dto.getKind());
    assertEquals(NOW, dto.getGeneratedAt());
    assertEquals("OPENAI", dto.getProviderType());
    assertEquals("model", dto.getModelName());
    assertEquals(BODY.length, dto.getBodyByteSize());
    assertEquals(new String(BODY, StandardCharsets.UTF_8), dto.getBodyJson());
    assertEquals(TURN_TWO_END_ID.toString(), dto.getSourceHeadEntryId());
    assertEquals(HarnessProviderRequestPreviewDTO.SNAPSHOT_NOTICE, dto.getSnapshotNotice());

    // 候选历史由同一 transform 构造：新 USER 消息同时携带文本、权威附件事实与已持有 RESOURCE，且顺序不变。
    ArgumentCaptor<EntryPath> pathCaptor = ArgumentCaptor.forClass(EntryPath.class);
    verify(turnResolver).planLive(eq(THREAD_ID), pathCaptor.capture());
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
    OwnerRef owner = new OwnerRef(OwnerType.CHAT, id(70L));
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
                    owner,
                    command(
                        target,
                        new NewThreadCommand(
                            new UserMessageCommandPayload(
                                new AgentMessage(
                                    AgentMessageRole.USER,
                                    List.of(new AttachmentMessageContent(UPLOAD_ID, null)))),
                            id(71L)))));
    assertTrue(pending.getMessage().contains("not READY"));

    when(refManager.contains(SESSION_ID, OWNED_BLOB_ID)).thenReturn(false);
    IllegalArgumentException foreign =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.preview(
                    THREAD_ID,
                    owner,
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
    verify(turnResolver, never()).planLive(any(), any());
  }

  /** 测试意图：adapter 未实现预览能力时明确拒绝（绝不伪装成空体），Provider 编码失败只回显稳定类别而不外泄原因文本。 */
  @Test
  void rejectsUnsupportedAdapterAndSanitizesProviderFailure() {
    OwnerRef owner = new OwnerRef(OwnerType.CHAT, id(80L));
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
    when(turnResolver.planLive(eq(THREAD_ID), any()))
        .thenReturn(new LiveTurnPlan.Planned(spec(), CONTEXT_WINDOW, List.of(), List.of()));
    ProviderRequestPreviewUnavailableException unsupported =
        assertThrows(
            ProviderRequestPreviewUnavailableException.class,
            () -> service.preview(THREAD_ID, owner, command));
    assertTrue(unsupported.getMessage().contains("does not support request body preview"));

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
            () -> service.preview(THREAD_ID, owner, command));
    assertTrue(invalid.getMessage().contains("INVALID_REQUEST"));
    assertFalse(invalid.getMessage().contains("provider.internal.example"));
  }

  /** 测试意图：Provider 解析漂移（generation/type 变化）与缺失 factory 一律确定性拒绝，绝不越过解析直接编码。 */
  @Test
  void rejectsProviderResolutionDrift() {
    OwnerRef owner = new OwnerRef(OwnerType.CHAT, id(90L));
    when(runtime.getThreadSnapshot(THREAD_ID))
        .thenReturn(
            snapshot(
                thread(TURN_TWO_END_ID, 1L), closedTurnPath(BELOW_THRESHOLD_USAGE), List.of()));
    when(turnResolver.planLive(eq(THREAD_ID), any()))
        .thenReturn(new LiveTurnPlan.Planned(spec(), CONTEXT_WINDOW, List.of(), List.of()));
    when(providerResolution.resolve(any(), any(), any()))
        .thenThrow(new IllegalArgumentException("provider connection generation drift"));

    ProviderRequestPreviewUnavailableException drift =
        assertThrows(
            ProviderRequestPreviewUnavailableException.class,
            () ->
                service.preview(
                    THREAD_ID,
                    owner,
                    command(
                        new AcceptCommandsTarget.Thread(THREAD_ID, TURN_TWO_END_ID, 1L),
                        userMessage("hi"))));
    assertTrue(drift.getMessage().contains("cannot resolve the current provider"));
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
            "provider",
            "model",
            "wire-model",
            Set.of(ModelInputModality.TEXT),
            false,
            false,
            PRICING),
        new ModelVariant("v1"),
        1024,
        "system instruction",
        List.of(),
        List.of(),
        ProviderCacheControl.none());
  }

  private static ThreadSnapshot snapshot(
      ThreadState thread, EntryPath path, List<ThreadCommand> queued) {
    return new ThreadSnapshot(thread, path, queued, null, List.of(), List.of());
  }

  private static ThreadState thread(UUID headEntryId, long nextCommandSequence) {
    return new ThreadState(
        THREAD_ID,
        SESSION_ID,
        null,
        headEntryId,
        REQUEST_HASH,
        "thread",
        false,
        ThreadLifecycleStatus.IDLE,
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

  private static MessagePayload assistantMessage(String text, ModelUsage usage) {
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent(text))),
        new AssistantMessageMetadata(
            GenerationStopReason.COMPLETE,
            usage,
            new ModelCost(
                "USD",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO)),
        null);
  }

  /** 取候选历史中最后一条 USER 消息：预览追加的输入边界。 */
  private static MessagePayload lastUserMessage(EntryPath path) {
    for (int i = path.entries().size() - 1; i >= 0; i--) {
      if (path.entries().get(i).payload() instanceof MessagePayload message
          && message.message().role() == AgentMessageRole.USER) {
        return message;
      }
    }
    throw new AssertionError("candidate path has no user message");
  }

  private static UUID id(long value) {
    return new UUID(0L, value);
  }
}
