package fun.fengwk.kkstudio.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.api.AcceptedCommands;
import fun.fengwk.kkstudio.harness.api.command.GoalCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.api.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.api.session.Session;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.model.ImageInputTier;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.spi.AcceptancePreflight;
import fun.fengwk.kkstudio.platform.orchestration.DirectCommandsNotAllowedException;
import fun.fengwk.kkstudio.platform.orchestration.HarnessCommandAcceptanceOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwner;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwnership;
import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 产品命令接受编排的单元契约。
 *
 * <p>覆盖 Chat 的归属授权和 preflight、禁止直接命令的产品 owner、fork 与附件物化。既有 Thread 走 owner-free 的 {@code
 * acceptOnThread}；授权或形状错误均不得进入 Runtime 接受写入。
 */
class HarnessCommandAcceptanceOrchestratorTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final UUID CHAT_ID = id(1);
  private static final UUID OTHER_CHAT_ID = id(2);
  private static final UUID SESSION_ID = id(3);
  private static final UUID OTHER_SESSION_ID = id(4);
  private static final UUID THREAD_ID = id(5);
  private static final UUID OTHER_THREAD_ID = id(6);
  private static final UUID ENTRY_ID = id(7);
  private static final UUID UPLOAD_ID = id(8);
  private static final UUID BLOB_ID = id(9);
  private static final UUID ISSUE_ID = id(11);
  private static final UUID SOURCE_THREAD_ID = id(40);
  private static final UUID NEW_SESSION_ID = id(41);
  private static final UUID NEW_THREAD_ID = id(42);
  private static final UUID COPIED_BLOB_ID = id(43);
  private static final UUID NESTED_BLOB_ID = id(44);
  private static final UUID FOREIGN_BLOB_ID = id(45);
  private static final String AGENT_NAME = "executor";
  private static final SessionOwner CHAT_OWNER = new SessionOwner("CHAT", CHAT_ID.toString());
  private static final SessionOwner ISSUE_AGENT_OWNER =
      new SessionOwner("ISSUE_AGENT", ISSUE_ID + ":" + AGENT_NAME);
  private static final BranchSettings SETTINGS =
      new BranchSettings(
          "assistant", BuiltinExecution.of(new ModelSelection("model", "default")), null);

  private ChatSessionRepository chatSessionRepository;
  private ChatRepository chatRepository;
  private SessionOwnership issueAgentOwnership;
  private ObjectProvider<HarnessRuntime> runtimes;
  private HarnessRuntime runtime;
  private StorageUploadService uploadService;
  private SessionBlobRefManager refManager;
  private StorageBlobManager blobManager;
  private AcceptedCommands accepted;
  private HarnessCommandAcceptanceOrchestrator service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    chatSessionRepository = mock(ChatSessionRepository.class);
    chatRepository = mock(ChatRepository.class);
    issueAgentOwnership = mock(SessionOwnership.class);
    runtimes = mock(ObjectProvider.class);
    runtime = mock(HarnessRuntime.class);
    uploadService = mock(StorageUploadService.class);
    refManager = mock(SessionBlobRefManager.class);
    blobManager = mock(StorageBlobManager.class);
    accepted = mock(AcceptedCommands.class);

    when(runtimes.getIfAvailable()).thenReturn(runtime);
    when(chatRepository.lockForKeyShare(CHAT_ID)).thenReturn(mock(Chat.class));

    when(issueAgentOwnership.kind()).thenReturn("ISSUE_AGENT");
    when(issueAgentOwnership.allowsDirectCommands()).thenReturn(false);

    when(runtime.acceptCommands(any(), any())).thenReturn(accepted);

    service =
        new HarnessCommandAcceptanceOrchestrator(
            List.of(
                new ChatSessionOwnership(chatRepository, chatSessionRepository),
                issueAgentOwnership),
            runtimes,
            uploadService,
            refManager,
            blobManager);
  }

  /** Chat NEW_SESSION preflight 建立归属边并保留命令；Issue+Agent 直接命令被拒绝。 */
  @Test
  void newSessionCreatesChatOwnershipAndPreservesNonUserCommands() {
    NewThreadCommand setAgent =
        new NewThreadCommand(new SetAgentCommandPayload("assistant"), id(20));
    NewThreadCommand user = user(new TextMessageContent("hello"));
    AcceptCommandsCommand chatCommand = newSession(THREAD_ID, setAgent, user);
    when(chatSessionRepository.insert(SESSION_ID, CHAT_ID)).thenReturn(true);

    AcceptancePreflight chatPreflight = acceptAndCapturePreflight(CHAT_OWNER, chatCommand);
    List<NewThreadCommand> chatPrepared =
        chatPreflight.prepare(
            new Session(SESSION_ID, "session", NOW), chatCommand.commands(), List.of());

    assertSame(setAgent, chatPrepared.getFirst());
    assertEquals(user.idempotencyKey(), chatPrepared.get(1).idempotencyKey());
    assertEquals(user.requestHash(), chatPrepared.get(1).requestHash());
    UserMessageCommandPayload mapped =
        assertInstanceOf(UserMessageCommandPayload.class, chatPrepared.get(1).payload());
    assertEquals("hello", ((TextMessageContent) mapped.message().contents().getFirst()).text());
    verify(chatSessionRepository).insert(SESSION_ID, CHAT_ID);

    AcceptCommandsCommand agentCommand = newSession(OTHER_THREAD_ID, user);
    assertThrows(
        DirectCommandsNotAllowedException.class,
        () -> service.accept(ISSUE_AGENT_OWNER, agentCommand));
    verify(runtime, never()).acceptCommands(eq(agentCommand), any());
    verify(issueAgentOwnership, never()).lockForAcceptance(any(), any());
    verify(chatSessionRepository, times(1)).insert(any(), any());
  }

  /** 已存在 Session（精确 replay）与 NEW_THREAD 都必须先锁 owner，再验证目标 Session 由该 owner 持有。 */
  @Test
  void authorizesExistingSessionAndNewThreadTargetsForTheirOwners() {
    when(runtime.sessionExists(SESSION_ID)).thenReturn(true);
    when(chatSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new ChatSession(SESSION_ID, CHAT_ID));
    AcceptCommandsCommand replay = newSession(THREAD_ID, user(new TextMessageContent("replay")));

    assertSame(accepted, service.accept(CHAT_OWNER, replay));

    AcceptCommandsCommand entry =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewThread(
                SESSION_ID, ENTRY_ID, OTHER_THREAD_ID, "branch", false),
            List.of(user(new TextMessageContent("entry"))));
    assertSame(accepted, service.accept(CHAT_OWNER, entry));

    // Issue+Agent 的直接命令必须由产品工作流处理。
    AcceptCommandsCommand siblingThread =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewThread(
                SESSION_ID, ENTRY_ID, OTHER_THREAD_ID, "branch", false),
            List.of(user(new TextMessageContent("sibling"))));
    assertThrows(
        DirectCommandsNotAllowedException.class,
        () -> service.accept(ISSUE_AGENT_OWNER, siblingThread));
    verify(runtime, never()).acceptCommands(eq(siblingThread), any());
    verify(issueAgentOwnership, never()).lockForAcceptance(any(), any());

    verify(chatRepository, times(2)).lockForKeyShare(CHAT_ID);
    verify(chatSessionRepository, times(2)).findBySessionId(SESSION_ID);
  }

  /**
   * owner-aware 入口只服务创建（NEW_SESSION / NEW_THREAD）：既有 Thread 的 THREAD target 在任何锁与 Runtime
   * 调用之前拒绝，继续写入改走 {@link HarnessCommandAcceptanceOrchestrator#acceptOnThread}。
   */
  @Test
  void rejectsThreadTargetOnOwnerAwareAcceptance() {
    AcceptCommandsCommand threadCommand =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(THREAD_ID, ENTRY_ID, 1),
            List.of(user(new TextMessageContent("continue"))));

    assertThrows(IllegalArgumentException.class, () -> service.accept(CHAT_OWNER, threadCommand));
    assertThrows(
        IllegalArgumentException.class, () -> service.accept(ISSUE_AGENT_OWNER, threadCommand));

    verify(runtime, never()).acceptCommands(any(), any());
    verify(chatRepository, never()).lockForKeyShare(any());
    verifyNoInteractions(issueAgentOwnership);
  }

  /** owner-free 既有 Thread 接受在同一事务内物化 USER_MESSAGE 附件，但不解锁也不改写任何 owner 归属事实。 */
  @Test
  void acceptOnThreadMaterializesAttachmentsWithoutOwnerAuthorization() {
    AcceptCommandsCommand command =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(THREAD_ID, ENTRY_ID, 1),
            List.of(user(new AttachmentMessageContent(UPLOAD_ID))));
    when(uploadService.lockReady(UPLOAD_ID))
        .thenReturn(new StorageUploadService.ReadyUpload(BLOB_ID, "report.pdf"));

    assertSame(accepted, service.acceptOnThread(command));

    ArgumentCaptor<AcceptancePreflight> captor = ArgumentCaptor.forClass(AcceptancePreflight.class);
    verify(runtime).acceptCommands(eq(command), captor.capture());
    List<NewThreadCommand> prepared =
        captor
            .getValue()
            .prepare(new Session(SESSION_ID, "session", NOW), command.commands(), List.of());
    UserMessageCommandPayload payload =
        assertInstanceOf(UserMessageCommandPayload.class, prepared.getFirst().payload());
    ResourceMessageContent resource =
        assertInstanceOf(ResourceMessageContent.class, payload.message().contents().getFirst());
    assertEquals(BLOB_ID, resource.blobId());
    verify(refManager).retainRef(SESSION_ID, BLOB_ID);
    verify(uploadService).delete(UPLOAD_ID);
    verify(chatRepository, never()).lockForKeyShare(any());
    verifyNoInteractions(issueAgentOwnership);
  }

  /** acceptOnThread 只接受既有的 THREAD target；创建语义必须走 owner-aware 入口。 */
  @Test
  void acceptOnThreadRejectsCreationTarget() {
    assertThrows(
        IllegalArgumentException.class,
        () -> service.acceptOnThread(newSession(THREAD_ID, user(new TextMessageContent("x")))));
    verify(runtime, never()).acceptCommands(any(), any());
  }

  /** Issue+Agent 不能通过公共入口写入，拒绝发生在产品锁和 Runtime 之前。 */
  @Test
  void issueAgentDirectAcceptanceIsRejectedBeforeLockAndRuntime() {
    assertThrows(
        DirectCommandsNotAllowedException.class,
        () ->
            service.accept(
                ISSUE_AGENT_OWNER, newSession(THREAD_ID, user(new TextMessageContent("work")))));
    verify(issueAgentOwnership, never()).lockForAcceptance(any(), any());
    verifyNoInteractions(runtime);
  }

  /** 缺失 Chat 和禁止直接写入的 Issue 均在 Runtime 接受之前拒绝。 */
  @Test
  void rejectsMissingChatAndDirectIssueCommandsBeforeRuntimeAcceptance() {
    AcceptCommandsCommand command = newSession(THREAD_ID, user(new TextMessageContent("denied")));

    when(chatRepository.lockForKeyShare(CHAT_ID)).thenReturn(null);
    assertThrows(DomainException.class, () -> service.accept(CHAT_OWNER, command));
    assertThrows(
        DirectCommandsNotAllowedException.class, () -> service.accept(ISSUE_AGENT_OWNER, command));
    verify(issueAgentOwnership, never()).lockForAcceptance(any(), any());

    verify(runtime, never()).acceptCommands(any(), any());
  }

  /** Issue+Agent 不能用 NEW_THREAD 绕过其产品工作流。 */
  @Test
  void rejectsIssueAgentNewThreadOnUnownedSession() {
    AcceptCommandsCommand onSession =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewThread(
                SESSION_ID, ENTRY_ID, OTHER_THREAD_ID, "branch", false),
            List.of(user(new TextMessageContent("steal session"))));
    assertThrows(
        DirectCommandsNotAllowedException.class,
        () -> service.accept(ISSUE_AGENT_OWNER, onSession));

    verify(runtime, never()).acceptCommands(any(), any());
  }

  /** Session 已由另一 owner 持有时，NEW_SESSION 不能被当作 replay 接管。 */
  @Test
  void rejectsNewSessionWhenSessionIsOwnedByAnotherOwner() {
    when(runtime.sessionExists(SESSION_ID)).thenReturn(true);
    when(chatSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new ChatSession(SESSION_ID, OTHER_CHAT_ID));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.accept(CHAT_OWNER, newSession(THREAD_ID, user(new TextMessageContent("x")))));

    // Issue+Agent 公共命令无论绑定状态如何均被拒绝。
    assertThrows(
        DirectCommandsNotAllowedException.class,
        () ->
            service.accept(
                ISSUE_AGENT_OWNER, newSession(THREAD_ID, user(new TextMessageContent("x")))));

    verify(runtime, never()).acceptCommands(any(), any());
  }

  /** Issue+Agent 不能借 NEW_SESSION 直接写入；绑定校验由产品 SPI 测试覆盖。 */
  @Test
  void rejectsIssueAgentNewRootForAnotherThread() {
    assertThrows(
        DirectCommandsNotAllowedException.class,
        () ->
            service.accept(
                ISSUE_AGENT_OWNER,
                newSession(OTHER_THREAD_ID, user(new TextMessageContent("rebind")))));

    verify(runtime, never()).acceptCommands(any(), any());
  }

  /** Chat 归属插入冲突必须显式失败并整体回滚（唯一键是最终 owner 互斥边界），不能静默复用既有归属。 */
  @Test
  void rejectsChatOwnershipConflicts() {
    AcceptCommandsCommand chatCommand =
        newSession(THREAD_ID, user(new TextMessageContent("chat conflict")));

    when(chatSessionRepository.insert(SESSION_ID, CHAT_ID)).thenReturn(false);
    AcceptancePreflight chatPreflight = acceptAndCapturePreflight(CHAT_OWNER, chatCommand);
    assertThrows(
        IllegalStateException.class,
        () ->
            chatPreflight.prepare(
                new Session(SESSION_ID, "session", NOW), chatCommand.commands(), List.of()));

    when(chatSessionRepository.insert(SESSION_ID, CHAT_ID))
        .thenThrow(new DataIntegrityViolationException("duplicate session owner"));
    assertThrows(
        IllegalStateException.class,
        () ->
            chatPreflight.prepare(
                new Session(SESSION_ID, "session", NOW), chatCommand.commands(), List.of()));
  }

  /** Issue+Agent 的 Goal 也不能绕过产品工作流直接接受。 */
  @Test
  void rejectsGoalCommandsForIssueAgentThreads() {
    NewThreadCommand goal =
        new NewThreadCommand(new GoalCommandPayload("ship the release"), id(21));
    assertThrows(
        DirectCommandsNotAllowedException.class,
        () ->
            service.accept(
                ISSUE_AGENT_OWNER,
                new AcceptCommandsCommand(
                    new AcceptCommandsTarget.NewThread(
                        SESSION_ID, ENTRY_ID, OTHER_THREAD_ID, "branch", false),
                    List.of(goal))));

    verify(runtime, never()).acceptCommands(any(), any());
    verify(issueAgentOwnership, never()).lockForAcceptance(any(), any());
  }

  /** Chat 普通 Branch 的 Goal 是产品能力：同一 GOAL 命令在 CHAT owner 下必须继续被接受。 */
  @Test
  void acceptsGoalCommandsForOrdinaryChatBranches() {
    NewThreadCommand goal =
        new NewThreadCommand(new GoalCommandPayload("ship the release"), id(23));
    when(chatSessionRepository.insert(SESSION_ID, CHAT_ID)).thenReturn(true);

    assertSame(
        accepted,
        service.accept(
            CHAT_OWNER, newSession(THREAD_ID, goal, user(new TextMessageContent("go")))));

    verify(runtime).acceptCommands(any(), any());
  }

  @Test
  void materializesAttachmentBeforeDeletingUploadAndPreservesRawIdentity() {
    // READY upload 先建立 Session retain，再删除 upload；durable payload 替换但 raw 幂等键不变。
    NewThreadCommand raw = user(new AttachmentMessageContent(UPLOAD_ID));
    AcceptCommandsCommand command = newSession(THREAD_ID, raw);
    when(chatSessionRepository.insert(SESSION_ID, CHAT_ID)).thenReturn(true);
    when(uploadService.lockReady(UPLOAD_ID))
        .thenReturn(new StorageUploadService.ReadyUpload(BLOB_ID, "report.pdf"));
    AcceptancePreflight preflight = acceptAndCapturePreflight(CHAT_OWNER, command);

    List<NewThreadCommand> prepared =
        preflight.prepare(new Session(SESSION_ID, "session", NOW), command.commands(), List.of());

    NewThreadCommand durable = prepared.getFirst();
    assertEquals(raw.idempotencyKey(), durable.idempotencyKey());
    assertEquals(raw.requestHash(), durable.requestHash());
    UserMessageCommandPayload payload =
        assertInstanceOf(UserMessageCommandPayload.class, durable.payload());
    ResourceMessageContent resource =
        assertInstanceOf(ResourceMessageContent.class, payload.message().contents().getFirst());
    assertEquals(BLOB_ID, resource.blobId());
    assertEquals("report.pdf", resource.name());
    InOrder order = inOrder(refManager, uploadService);
    order.verify(refManager).retainRef(SESSION_ID, BLOB_ID);
    order.verify(uploadService).delete(UPLOAD_ID);
  }

  /** 意图：图片输入档位随命令冻结，且只对图片媒体有意义——wire 缺省即平台默认 720P，非图片媒体与无法确认媒体类型的 blob 一律收敛为 null。 */
  @Test
  void freezesAttachmentImageTierButDropsItForNonImageMedia() {
    when(chatSessionRepository.insert(SESSION_ID, CHAT_ID)).thenReturn(true);
    when(uploadService.lockReady(UPLOAD_ID))
        .thenReturn(new StorageUploadService.ReadyUpload(BLOB_ID, "photo.png"));
    when(blobManager.getBlob(BLOB_ID)).thenReturn(activeBlob("image/png"));

    assertEquals(
        ImageInputTier.P1080,
        preparedAttachmentTier(41L, new AttachmentMessageContent(UPLOAD_ID, ImageInputTier.P1080)));
    // wire 未选择档位：图片按平台默认 720P 冻结，绝不落成 null（否则历史重放会退回原图）。
    assertEquals(
        ImageInputTier.P720,
        preparedAttachmentTier(42L, new AttachmentMessageContent(UPLOAD_ID, null)));

    when(uploadService.lockReady(UPLOAD_ID))
        .thenReturn(new StorageUploadService.ReadyUpload(BLOB_ID, "report.pdf"));
    when(blobManager.getBlob(BLOB_ID)).thenReturn(activeBlob("application/pdf"));
    assertNull(
        preparedAttachmentTier(43L, new AttachmentMessageContent(UPLOAD_ID, ImageInputTier.P1080)));

    // 缺失或非 ACTIVE：无法确认媒体类型，必须收敛为 null 而不是持久化无意义档位。
    when(blobManager.getBlob(BLOB_ID)).thenReturn(null);
    assertNull(
        preparedAttachmentTier(44L, new AttachmentMessageContent(UPLOAD_ID, ImageInputTier.P1080)));
    when(blobManager.getBlob(BLOB_ID)).thenReturn(deletingBlob("image/png"));
    assertNull(
        preparedAttachmentTier(45L, new AttachmentMessageContent(UPLOAD_ID, ImageInputTier.P1080)));
  }

  /** 意图：复用已有 RESOURCE 时同样按权威媒体类型收敛档位；图片档位原样保留且不重写 durable 实例。 */
  @Test
  void normalizesReusedResourceTierAgainstAuthoritativeMediaType() {
    when(chatSessionRepository.insert(SESSION_ID, CHAT_ID)).thenReturn(true);
    when(refManager.contains(SESSION_ID, BLOB_ID)).thenReturn(true);
    ResourceMessageContent imageResource =
        ResourceMessageContent.media(BLOB_ID, "photo.png", "preview", ImageInputTier.P1080);
    ResourceMessageContent videoResource =
        ResourceMessageContent.media(BLOB_ID, "clip.mp4", "preview", ImageInputTier.P1080);

    when(blobManager.getBlob(BLOB_ID)).thenReturn(activeBlob("image/png"));
    assertSame(imageResource, preparedResource(46L, imageResource));

    when(blobManager.getBlob(BLOB_ID)).thenReturn(activeBlob("video/mp4"));
    ResourceMessageContent normalized = preparedResource(47L, videoResource);
    assertNotSame(videoResource, normalized);
    assertNull(normalized.imageTier());
    assertEquals("clip.mp4", normalized.name());
    assertEquals(BLOB_ID, normalized.blobId());

    when(blobManager.getBlob(BLOB_ID)).thenReturn(null);
    assertNull(preparedResource(48L, videoResource).imageTier());
    when(blobManager.getBlob(BLOB_ID)).thenReturn(deletingBlob("image/png"));
    assertNull(preparedResource(49L, imageResource).imageTier());
  }

  @Test
  void translatesAttachmentLookupAndVerificationFailures() {
    // Storage 的 not-found 与 verification 失败都统一成为非法用户内容，且不得 retain/delete。
    AcceptCommandsCommand command =
        newSession(THREAD_ID, user(new AttachmentMessageContent(UPLOAD_ID)));
    when(chatSessionRepository.insert(SESSION_ID, CHAT_ID)).thenReturn(true);
    AcceptancePreflight preflight = acceptAndCapturePreflight(CHAT_OWNER, command);
    doThrow(new StorageResourceNotFoundException("upload", UPLOAD_ID.toString()))
        .when(uploadService)
        .lockReady(UPLOAD_ID);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            preflight.prepare(
                new Session(SESSION_ID, "session", NOW), command.commands(), List.of()));

    doThrow(new StorageVerificationException("upload is pending"))
        .when(uploadService)
        .lockReady(UPLOAD_ID);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            preflight.prepare(
                new Session(SESSION_ID, "session", NOW), command.commands(), List.of()));
    verify(refManager, never()).retainRef(any(), any());
    verify(uploadService, never()).delete(any());
  }

  @Test
  void reusesOnlyResourcesAlreadyOwnedByTheSession() {
    // Durable RESOURCE 不新增 retain；跨 Session blob 明确拒绝。
    ResourceMessageContent resource =
        ResourceMessageContent.media(BLOB_ID, "existing.txt", "preview");
    AcceptCommandsCommand command = newSession(THREAD_ID, user(resource));
    when(chatSessionRepository.insert(SESSION_ID, CHAT_ID)).thenReturn(true);
    AcceptancePreflight preflight = acceptAndCapturePreflight(CHAT_OWNER, command);

    when(refManager.contains(SESSION_ID, BLOB_ID)).thenReturn(false);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            preflight.prepare(
                new Session(SESSION_ID, "session", NOW), command.commands(), List.of()));

    when(refManager.contains(SESSION_ID, BLOB_ID)).thenReturn(true);
    List<NewThreadCommand> prepared =
        preflight.prepare(new Session(SESSION_ID, "session", NOW), command.commands(), List.of());
    UserMessageCommandPayload payload =
        assertInstanceOf(UserMessageCommandPayload.class, prepared.getFirst().payload());
    assertSame(resource, payload.message().contents().getFirst());
    verify(refManager, never()).retainRef(any(), any());
  }

  @Test
  void requiresRuntimeBeforeAcceptance() {
    // 缺 Runtime 时部署必须 fail closed，不能返回伪成功。
    when(runtimes.getIfAvailable()).thenReturn(null);
    assertThrows(
        IllegalStateException.class,
        () -> service.accept(CHAT_OWNER, newSession(THREAD_ID, user(new TextMessageContent("r")))));

    verify(runtime, never()).acceptCommands(any(), any());
  }

  @Test
  void rejectsUnknownOrAmbiguousOwnerKindsBeforeLocking() {
    // 未知或重复 kind 必须在产品锁和 runtime 写之前拒绝。
    AcceptCommandsCommand command = newSession(THREAD_ID, user(new TextMessageContent("x")));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.accept(new SessionOwner("UNKNOWN", "id"), command));
    SessionOwnership duplicate = mock(SessionOwnership.class);
    when(duplicate.kind()).thenReturn("ISSUE_AGENT");
    HarnessCommandAcceptanceOrchestrator ambiguous =
        new HarnessCommandAcceptanceOrchestrator(
            List.of(issueAgentOwnership, duplicate),
            runtimes,
            uploadService,
            refManager,
            blobManager);
    assertThrows(
        IllegalArgumentException.class, () -> ambiguous.accept(ISSUE_AGENT_OWNER, command));
    verify(issueAgentOwnership, never()).lockForAcceptance(any(), any());
    verify(duplicate, never()).lockForAcceptance(any(), any());
    verifyNoInteractions(runtime);
  }

  @Test
  void rejectsNullDependenciesAndNullOwner() {
    // 强依赖与 owner 均不得为空。
    assertThrows(
        NullPointerException.class,
        () ->
            new HarnessCommandAcceptanceOrchestrator(
                null, runtimes, uploadService, refManager, blobManager));
    assertThrows(
        NullPointerException.class,
        () ->
            new HarnessCommandAcceptanceOrchestrator(
                List.of(issueAgentOwnership), runtimes, null, refManager, blobManager));
    assertThrows(
        NullPointerException.class,
        () ->
            new HarnessCommandAcceptanceOrchestrator(
                List.of(issueAgentOwnership), runtimes, uploadService, refManager, null));
    assertThrows(
        NullPointerException.class,
        () -> service.accept(null, newSession(THREAD_ID, user(new TextMessageContent("null")))));
    assertThrows(NullPointerException.class, () -> service.accept(CHAT_OWNER, null));
  }

  /**
   * 会话 fork：preflight 为新 Session 建立 Chat 归属，并把被复制消息引用的 blob（含 tool result 内嵌）重新 retain 到新 Session。
   */
  @Test
  void sessionForkCreatesChatOwnershipAndRetainsCopiedResources() {
    stubOwnedSourceSession(SESSION_ID);
    when(chatSessionRepository.insert(NEW_SESSION_ID, CHAT_ID)).thenReturn(true);
    AcceptCommandsCommand command = sessionFork(user(new TextMessageContent("fork")));

    AcceptancePreflight preflight = acceptAndCapturePreflight(CHAT_OWNER, command);
    List<NewThreadCommand> prepared =
        preflight.prepare(
            new Session(NEW_SESSION_ID, "forked", NOW),
            command.commands(),
            List.of(copiedResourceEntry(), copiedToolResultEntry()));

    assertEquals(1, prepared.size());
    verify(chatSessionRepository).insert(NEW_SESSION_ID, CHAT_ID);
    verify(refManager).retainRef(NEW_SESSION_ID, COPIED_BLOB_ID);
    verify(refManager).retainRef(NEW_SESSION_ID, NESTED_BLOB_ID);
  }

  /** 会话 fork 的初始消息可复用本 Session 已复制的 blob，但任何未持有（跨 owner）的 blob 必须拒绝。 */
  @Test
  void sessionForkInitialMessageMayReuseCopiedBlobButRejectsForeignBlob() {
    stubOwnedSourceSession(SESSION_ID);
    when(chatSessionRepository.insert(NEW_SESSION_ID, CHAT_ID)).thenReturn(true);
    when(refManager.contains(NEW_SESSION_ID, COPIED_BLOB_ID)).thenReturn(true);
    when(refManager.contains(NEW_SESSION_ID, FOREIGN_BLOB_ID)).thenReturn(false);

    AcceptCommandsCommand reuse =
        sessionFork(user(ResourceMessageContent.media(COPIED_BLOB_ID, "copied.txt")));
    AcceptancePreflight reusePreflight = acceptAndCapturePreflight(CHAT_OWNER, reuse);
    List<NewThreadCommand> prepared =
        reusePreflight.prepare(
            new Session(NEW_SESSION_ID, "forked", NOW),
            reuse.commands(),
            List.of(copiedResourceEntry()));
    assertEquals(1, prepared.size());

    AcceptCommandsCommand foreign =
        sessionFork(user(ResourceMessageContent.media(FOREIGN_BLOB_ID, "foreign.txt")));
    AcceptancePreflight foreignPreflight = acceptAndCapturePreflight(CHAT_OWNER, foreign);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            foreignPreflight.prepare(
                new Session(NEW_SESSION_ID, "forked", NOW),
                foreign.commands(),
                List.of(copiedResourceEntry())));

    verify(refManager, never()).retainRef(NEW_SESSION_ID, FOREIGN_BLOB_ID);
  }

  /** 会话 fork 只服务 Chat：Issue+Agent 不支持，且必须在任何写入与 Runtime 调用之前拒绝。 */
  @Test
  void rejectsIssueAgentSessionForkBeforeAnySideEffect() {
    AcceptCommandsCommand command = sessionFork(user(new TextMessageContent("fork")));

    assertThrows(
        DirectCommandsNotAllowedException.class, () -> service.accept(ISSUE_AGENT_OWNER, command));
    verify(issueAgentOwnership, never()).lockForAcceptance(any(), any());

    verify(runtime, never()).acceptCommands(any(), any());
    verify(chatSessionRepository, never()).insert(any(), any());
    verify(refManager, never()).retainRef(any(), any());
  }

  /** 会话 fork 的来源 Session 必须由该 Chat 持有：别人的 Session 一律拒绝且零写入。 */
  @Test
  void rejectsSessionForkOfUnownedSourceSession() {
    when(runtime.findThreadSessionId(SOURCE_THREAD_ID)).thenReturn(Optional.of(SESSION_ID));
    when(chatSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new ChatSession(SESSION_ID, OTHER_CHAT_ID));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.accept(CHAT_OWNER, sessionFork(user(new TextMessageContent("fork")))));

    verify(runtime, never()).acceptCommands(any(), any());
    verify(chatSessionRepository, never()).insert(any(), any());
    verify(refManager, never()).retainRef(any(), any());
  }

  /** 来源 Thread 不可解析说明归属事实不一致：fail closed 且零写入。 */
  @Test
  void rejectsSessionForkWithUnresolvableSourceThread() {
    assertThrows(
        IllegalArgumentException.class,
        () -> service.accept(CHAT_OWNER, sessionFork(user(new TextMessageContent("fork")))));

    verify(runtime, never()).acceptCommands(any(), any());
    verify(chatSessionRepository, never()).insert(any(), any());
    verify(refManager, never()).retainRef(any(), any());
  }

  private void stubOwnedSourceSession(UUID sessionId) {
    when(runtime.findThreadSessionId(SOURCE_THREAD_ID)).thenReturn(Optional.of(sessionId));
    when(chatSessionRepository.findBySessionId(sessionId))
        .thenReturn(new ChatSession(sessionId, CHAT_ID));
  }

  private static AcceptCommandsCommand sessionFork(NewThreadCommand... commands) {
    return new AcceptCommandsCommand(
        new AcceptCommandsTarget.NewForkedSession(
            SOURCE_THREAD_ID, ENTRY_ID, NEW_SESSION_ID, NEW_THREAD_ID, false),
        List.of(commands));
  }

  private static Entry copiedResourceEntry() {
    return new Entry(
        id(70),
        NEW_SESSION_ID,
        ENTRY_ID,
        new MessagePayload(
            new AgentMessage(
                AgentMessageRole.USER,
                List.of(ResourceMessageContent.media(COPIED_BLOB_ID, "copied.txt"))),
            null,
            null),
        NOW);
  }

  /** tool result 内嵌 Resource：fork retain 必须递归进入 tool result 内容，不能漏掉。 */
  private static Entry copiedToolResultEntry() {
    ToolResultMessageContent result =
        new ToolResultMessageContent(
            "call-1",
            "bash",
            "bash",
            List.of(ResourceMessageContent.media(NESTED_BLOB_ID, "nested.txt")),
            false,
            "{}");
    return new Entry(
        id(71),
        NEW_SESSION_ID,
        ENTRY_ID,
        new MessagePayload(
            new AgentMessage(AgentMessageRole.TOOL, List.of(result)),
            null,
            new ToolResultMetadata(
                id(72),
                ENTRY_ID,
                "call-1",
                0,
                ToolResultStatus.SUCCEEDED,
                false,
                null,
                null,
                null)),
        NOW);
  }

  private ImageInputTier preparedAttachmentTier(long key, AttachmentMessageContent attachment) {
    UserMessageCommandPayload payload =
        preparedPayload(newSession(THREAD_ID, uniqueUser(key, attachment)));
    return ((ResourceMessageContent) payload.message().contents().getFirst()).imageTier();
  }

  private ResourceMessageContent preparedResource(long key, ResourceMessageContent resource) {
    UserMessageCommandPayload payload =
        preparedPayload(newSession(THREAD_ID, uniqueUser(key, resource)));
    return assertInstanceOf(ResourceMessageContent.class, payload.message().contents().getFirst());
  }

  private UserMessageCommandPayload preparedPayload(AcceptCommandsCommand command) {
    AcceptancePreflight preflight = acceptAndCapture(command);
    return assertInstanceOf(
        UserMessageCommandPayload.class,
        preflight
            .prepare(new Session(SESSION_ID, "session", NOW), command.commands(), List.of())
            .getFirst()
            .payload());
  }

  /** 每个命令单独捕获 preflight；同一测试内不同用例使用不同幂等键，避免重复提交同一命令。 */
  private AcceptancePreflight acceptAndCapture(AcceptCommandsCommand command) {
    service.accept(CHAT_OWNER, command);
    ArgumentCaptor<AcceptancePreflight> captor = ArgumentCaptor.forClass(AcceptancePreflight.class);
    verify(runtime, atLeastOnce()).acceptCommands(eq(command), captor.capture());
    return captor.getValue();
  }

  private AcceptancePreflight acceptAndCapturePreflight(
      SessionOwner owner, AcceptCommandsCommand command) {
    assertSame(accepted, service.accept(owner, command));
    ArgumentCaptor<AcceptancePreflight> captor = ArgumentCaptor.forClass(AcceptancePreflight.class);
    verify(runtime).acceptCommands(eq(command), captor.capture());
    return captor.getValue();
  }

  private static AcceptCommandsCommand newSession(UUID threadId, NewThreadCommand... commands) {
    return new AcceptCommandsCommand(
        new AcceptCommandsTarget.NewRootSession(SESSION_ID, threadId, SETTINGS, false),
        List.of(commands));
  }

  private static NewThreadCommand user(AgentMessageContent... contents) {
    return new NewThreadCommand(
        new UserMessageCommandPayload(new AgentMessage(AgentMessageRole.USER, List.of(contents))),
        id(30));
  }

  private static NewThreadCommand uniqueUser(long key, AgentMessageContent... contents) {
    return new NewThreadCommand(
        new UserMessageCommandPayload(new AgentMessage(AgentMessageRole.USER, List.of(contents))),
        id(key));
  }

  private static StorageBlob activeBlob(String mediaType) {
    return blob(mediaType, StorageBlobState.ACTIVE);
  }

  private static StorageBlob deletingBlob(String mediaType) {
    return blob(mediaType, StorageBlobState.DELETING);
  }

  private static StorageBlob blob(String mediaType, StorageBlobState state) {
    StorageBlob blob = new StorageBlob();
    blob.setId(BLOB_ID);
    blob.setMediaType(mediaType);
    blob.setState(state);
    return blob;
  }

  private static UUID id(long value) {
    return new UUID(0L, value);
  }
}
