package fun.fengwk.kkstudio.platform.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;

import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasSession;
import fun.fengwk.kkstudio.canvas.CanvasSessionRepository;
import fun.fengwk.kkstudio.canvas.CanvasStore;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.GoalCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.platform.chat.repo.ChatRepository;
import fun.fengwk.kkstudio.platform.chat.repo.ChatSession;
import fun.fengwk.kkstudio.platform.chat.repo.ChatSessionRepository;
import fun.fengwk.kkstudio.platform.chat.service.model.Chat;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueAgentSession;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentSessionOwnershipRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentSessionRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

class HarnessCommandAcceptanceOrchestratorTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final UUID CHAT_ID = id(1);
  private static final UUID CANVAS_ID = id(2);
  private static final UUID SESSION_ID = id(3);
  private static final UUID THREAD_ID = id(4);
  private static final UUID ENTRY_ID = id(5);
  private static final UUID UPLOAD_ID = id(6);
  private static final UUID BLOB_ID = id(7);
  private static final UUID PROJECT_ID = id(8);
  private static final UUID ISSUE_AGENT_SESSION_ID = id(9);
  private static final UUID ISSUE_ID = id(10);
  private static final String AGENT_NAME = "executor";
  private static final OwnerRef CHAT_OWNER = new OwnerRef(OwnerType.CHAT, CHAT_ID);
  private static final OwnerRef CANVAS_OWNER = new OwnerRef(OwnerType.CANVAS, CANVAS_ID);
  private static final OwnerRef ISSUE_AGENT_SESSION_OWNER =
      new OwnerRef(OwnerType.ISSUE_AGENT_SESSION, ISSUE_AGENT_SESSION_ID);
  private static final BranchSettings SETTINGS =
      new BranchSettings("assistant", new ModelSelection("provider", "model", "default"), null);

  private ChatSessionRepository chatSessionRepository;
  private CanvasSessionRepository canvasSessionRepository;
  private ChatRepository chatRepository;
  private CanvasStore canvasStore;
  private ProjectRepository projectRepository;
  private IssueRepository issueRepository;
  private IssueAgentSessionRepository issueAgentSessionRepository;
  private IssueAgentSessionOwnershipRepository issueAgentSessionOwnershipRepository;
  private ObjectProvider<HarnessStore> stores;
  private ObjectProvider<HarnessRuntime> runtimes;
  private HarnessStore store;
  private HarnessStore.Transaction transaction;
  private HarnessRuntime runtime;
  private StorageUploadService uploadService;
  private SessionBlobRefManager refManager;
  private AcceptedCommands accepted;
  private IssueAgentSession agentSessionBinding;
  private Project project;
  private Issue issue;
  private HarnessCommandAcceptanceOrchestrator service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    chatSessionRepository = mock(ChatSessionRepository.class);
    canvasSessionRepository = mock(CanvasSessionRepository.class);
    chatRepository = mock(ChatRepository.class);
    canvasStore = mock(CanvasStore.class);
    projectRepository = mock(ProjectRepository.class);
    issueRepository = mock(IssueRepository.class);
    issueAgentSessionRepository = mock(IssueAgentSessionRepository.class);
    issueAgentSessionOwnershipRepository = mock(IssueAgentSessionOwnershipRepository.class);
    stores = mock(ObjectProvider.class);
    runtimes = mock(ObjectProvider.class);
    store = mock(HarnessStore.class);
    transaction = mock(HarnessStore.Transaction.class);
    runtime = mock(HarnessRuntime.class);
    uploadService = mock(StorageUploadService.class);
    refManager = mock(SessionBlobRefManager.class);
    accepted = mock(AcceptedCommands.class);

    when(stores.getIfAvailable()).thenReturn(store);
    when(runtimes.getIfAvailable()).thenReturn(runtime);
    when(store.transaction(any()))
        .thenAnswer(
            invocation -> {
              Function<HarnessStore.Transaction, ?> callback = invocation.getArgument(0);
              return callback.apply(transaction);
            });
    when(transaction.findSession(any())).thenReturn(Optional.empty());
    when(chatRepository.lockForKeyShare(CHAT_ID)).thenReturn(mock(Chat.class));
    when(canvasStore.lockDocumentForKeyShare(CANVAS_ID))
        .thenReturn(Optional.of(mock(CanvasDocument.class)));

    agentSessionBinding =
        IssueAgentSession.builder()
            .id(ISSUE_AGENT_SESSION_ID)
            .issueId(ISSUE_ID)
            .agentName(AGENT_NAME)
            .sessionId(SESSION_ID)
            .threadId(THREAD_ID)
            .createdAt(NOW)
            .updatedAt(NOW)
            .build();
    project = mock(Project.class);
    when(project.getId()).thenReturn(PROJECT_ID);
    when(project.isArchived()).thenReturn(false);
    when(projectRepository.lockForKeyShare(PROJECT_ID)).thenReturn(project);

    issue = mock(Issue.class);
    when(issue.getId()).thenReturn(ISSUE_ID);
    when(issue.getProjectId()).thenReturn(PROJECT_ID);
    when(issue.isArchived()).thenReturn(false);
    when(issueRepository.getById(ISSUE_ID)).thenReturn(issue);
    when(issueRepository.lockById(ISSUE_ID)).thenReturn(issue);
    when(issueAgentSessionRepository.getById(ISSUE_AGENT_SESSION_ID))
        .thenReturn(agentSessionBinding);
    when(issueAgentSessionRepository.findByIssueIdAndAgentName(ISSUE_ID, AGENT_NAME))
        .thenReturn(agentSessionBinding);
    when(issueAgentSessionOwnershipRepository.findAgentSessionIdBySessionId(SESSION_ID))
        .thenReturn(ISSUE_AGENT_SESSION_ID);

    when(runtime.acceptCommands(any(), any())).thenReturn(accepted);

    service =
        new HarnessCommandAcceptanceOrchestrator(
            chatSessionRepository,
            canvasSessionRepository,
            chatRepository,
            canvasStore,
            projectRepository,
            issueRepository,
            issueAgentSessionRepository,
            issueAgentSessionOwnershipRepository,
            stores,
            runtimes,
            uploadService,
            refManager);
  }

  @Test
  void createsChatCanvasAndIssueAgentSessionOwnershipAndPreservesNonUserCommands() {
    // NEW_SESSION preflight 只创建对应 owner relation；SET_* 保持同一命令实例，USER 文本保持幂等键。
    NewThreadCommand setAgent =
        new NewThreadCommand(new SetAgentCommandPayload("assistant"), id(20));
    NewThreadCommand user = user(new TextMessageContent("hello"));
    AcceptCommandsCommand chatCommand = newSession(setAgent, user);
    when(chatSessionRepository.insert(SESSION_ID, CHAT_ID)).thenReturn(true);

    AcceptancePreflight chatPreflight = acceptAndCapturePreflight(CHAT_OWNER, chatCommand);
    List<NewThreadCommand> chatPrepared =
        chatPreflight.prepare(
            transaction, new Session(SESSION_ID, "session", NOW), chatCommand.commands());

    assertSame(setAgent, chatPrepared.getFirst());
    assertEquals(user.idempotencyKey(), chatPrepared.get(1).idempotencyKey());
    assertEquals(user.requestHash(), chatPrepared.get(1).requestHash());
    UserMessageCommandPayload mapped =
        assertInstanceOf(UserMessageCommandPayload.class, chatPrepared.get(1).payload());
    assertEquals("hello", ((TextMessageContent) mapped.message().contents().getFirst()).text());
    verify(chatSessionRepository).insert(SESSION_ID, CHAT_ID);

    AcceptCommandsCommand canvasCommand = newSession(user(new TextMessageContent("canvas")));
    when(canvasSessionRepository.insert(SESSION_ID, CANVAS_ID)).thenReturn(true);

    AcceptancePreflight canvasPreflight = acceptAndCapturePreflight(CANVAS_OWNER, canvasCommand);
    canvasPreflight.prepare(
        transaction, new Session(SESSION_ID, "session", NOW), canvasCommand.commands());
    verify(canvasSessionRepository).insert(SESSION_ID, CANVAS_ID);

    AcceptCommandsCommand agentSessionCommand =
        newSession(user(new TextMessageContent("agent session")));
    when(issueAgentSessionOwnershipRepository.insert(SESSION_ID, ISSUE_AGENT_SESSION_ID))
        .thenReturn(true);

    AcceptancePreflight agentSessionPreflight =
        acceptAndCapturePreflight(ISSUE_AGENT_SESSION_OWNER, agentSessionCommand);
    agentSessionPreflight.prepare(
        transaction, new Session(SESSION_ID, "agent session", NOW), agentSessionCommand.commands());
    verify(issueAgentSessionOwnershipRepository).insert(SESSION_ID, ISSUE_AGENT_SESSION_ID);
  }

  @Test
  void authorizesExistingNewSessionEntryAndThreadTargetsForTheirOwners() {
    // 已存在 Session、NEW_THREAD 与 THREAD 都必须先锁 owner，再验证对应 relation。
    when(transaction.findSession(SESSION_ID))
        .thenReturn(Optional.of(new Session(SESSION_ID, "session", NOW)));
    when(chatSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new ChatSession(SESSION_ID, CHAT_ID));
    AcceptCommandsCommand replay = newSession(user(new TextMessageContent("replay")));

    assertSame(accepted, service.accept(CHAT_OWNER, replay));

    AcceptCommandsCommand entry =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewThread(SESSION_ID, ENTRY_ID, THREAD_ID, false),
            List.of(user(new TextMessageContent("entry"))));
    assertSame(accepted, service.accept(CHAT_OWNER, entry));

    ThreadState thread = thread(THREAD_ID, SESSION_ID);
    when(transaction.findThread(THREAD_ID)).thenReturn(Optional.of(thread));
    when(canvasSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new CanvasSession(SESSION_ID, CANVAS_ID));
    AcceptCommandsCommand threadCommand =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(THREAD_ID, ENTRY_ID, 1),
            List.of(user(new TextMessageContent("thread"))));
    assertSame(accepted, service.accept(CANVAS_OWNER, threadCommand));

    AcceptCommandsCommand agentSessionThreadCommand =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(THREAD_ID, ENTRY_ID, 1),
            List.of(user(new TextMessageContent("agent thread"))));
    assertSame(accepted, service.accept(ISSUE_AGENT_SESSION_OWNER, agentSessionThreadCommand));

    verify(chatRepository, times(2)).lockForKeyShare(CHAT_ID);
    verify(canvasStore).lockDocumentForKeyShare(CANVAS_ID);
    verify(chatSessionRepository, times(2)).findBySessionId(SESSION_ID);
    verify(canvasSessionRepository).findBySessionId(SESSION_ID);
    verify(issueAgentSessionOwnershipRepository).findAgentSessionIdBySessionId(SESSION_ID);
  }

  @Test
  void authorizesIssueAgentSessionWithCorrectLockOrder() {
    // 验证 ISSUE_AGENT_SESSION 严格按 Project -> Issue -> IssueAgentSession 加锁与校验
    AcceptCommandsCommand command = newSession(user(new TextMessageContent("work")));
    service.accept(ISSUE_AGENT_SESSION_OWNER, command);

    InOrder lockOrder = inOrder(projectRepository, issueRepository, issueAgentSessionRepository);
    lockOrder.verify(projectRepository).lockForKeyShare(PROJECT_ID);
    lockOrder.verify(issueRepository).lockById(ISSUE_ID);
    lockOrder.verify(issueAgentSessionRepository).findByIssueIdAndAgentName(ISSUE_ID, AGENT_NAME);
  }

  @Test
  void rejectsMissingOwnersBeforeRuntimeAcceptance() {
    // Owner 行缺失时不能读取或写入 Runtime acceptance。
    when(chatRepository.lockForKeyShare(CHAT_ID)).thenReturn(null);
    assertThrows(
        IllegalArgumentException.class,
        () -> service.accept(CHAT_OWNER, newSession(user(new TextMessageContent("missing chat")))));

    when(canvasStore.lockDocumentForKeyShare(CANVAS_ID)).thenReturn(Optional.empty());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.accept(
                CANVAS_OWNER, newSession(user(new TextMessageContent("missing canvas")))));

    when(issueAgentSessionRepository.getById(ISSUE_AGENT_SESSION_ID)).thenReturn(null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.accept(
                ISSUE_AGENT_SESSION_OWNER,
                newSession(user(new TextMessageContent("missing binding")))));

    verify(runtime, never()).acceptCommands(any(), any());
  }

  @Test
  void rejectsAcceptanceWhenProjectOrIssueIsArchived() {
    // 归档的 Project 或 Issue 禁止接受任何命令，统一 backend 防线拦截。
    when(project.isArchived()).thenReturn(true);
    AcceptCommandsCommand cmd1 = newSession(user(new TextMessageContent("archived project")));
    IllegalArgumentException ex1 =
        assertThrows(
            IllegalArgumentException.class, () -> service.accept(ISSUE_AGENT_SESSION_OWNER, cmd1));
    assertTrue(ex1.getMessage().contains("archived project"));

    when(project.isArchived()).thenReturn(false);
    when(issue.isArchived()).thenReturn(true);
    AcceptCommandsCommand cmd2 = newSession(user(new TextMessageContent("archived issue")));
    IllegalArgumentException ex2 =
        assertThrows(
            IllegalArgumentException.class, () -> service.accept(ISSUE_AGENT_SESSION_OWNER, cmd2));
    assertTrue(ex2.getMessage().contains("archived issue"));
  }

  @Test
  void rejectsInconsistentHierarchyOrOwnershipForIssueAgentSession() {
    // Issue 归属的项目 ID 不匹配
    Issue inconsistentIssue = mock(Issue.class);
    when(inconsistentIssue.getId()).thenReturn(ISSUE_ID);
    when(inconsistentIssue.getProjectId()).thenReturn(id(999));
    when(inconsistentIssue.isArchived()).thenReturn(false);
    when(issueRepository.lockById(ISSUE_ID)).thenReturn(inconsistentIssue);

    AcceptCommandsCommand cmd1 = newSession(user(new TextMessageContent("bad hierarchy")));
    IllegalArgumentException ex1 =
        assertThrows(
            IllegalArgumentException.class, () -> service.accept(ISSUE_AGENT_SESSION_OWNER, cmd1));
    assertTrue(ex1.getMessage().contains("Issue owner hierarchy is inconsistent"));

    when(issueRepository.lockById(ISSUE_ID)).thenReturn(issue);

    // 锁定的 binding 与原 binding session 不一致
    IssueAgentSession inconsistentBinding =
        IssueAgentSession.builder()
            .id(ISSUE_AGENT_SESSION_ID)
            .issueId(ISSUE_ID)
            .agentName(AGENT_NAME)
            .sessionId(id(999))
            .threadId(THREAD_ID)
            .build();
    when(issueAgentSessionRepository.findByIssueIdAndAgentName(ISSUE_ID, AGENT_NAME))
        .thenReturn(inconsistentBinding);
    AcceptCommandsCommand cmd2 = newSession(user(new TextMessageContent("bad binding")));
    IllegalArgumentException ex2 =
        assertThrows(
            IllegalArgumentException.class, () -> service.accept(ISSUE_AGENT_SESSION_OWNER, cmd2));
    assertTrue(ex2.getMessage().contains("Issue agent session ownership is inconsistent"));
  }

  @Test
  void rejectsNewSessionWhenIssueAgentSessionBoundToDifferentSessionOrBranch() {
    // IssueAgentSession 仅允许绑定与其一致的 sessionId 和 threadId，尝试指定不同 ID 将被确定性拒绝
    AcceptCommandsCommand diffSessionCmd =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewSession(id(999), THREAD_ID, SETTINGS, null, false),
            List.of(user(new TextMessageContent("diff session"))));
    IllegalArgumentException ex1 =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.accept(ISSUE_AGENT_SESSION_OWNER, diffSessionCmd));
    assertTrue(ex1.getMessage().contains("bound to a different session or branch"));

    AcceptCommandsCommand diffThreadCmd =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewSession(SESSION_ID, id(999), SETTINGS, null, false),
            List.of(user(new TextMessageContent("diff thread"))));
    IllegalArgumentException ex2 =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.accept(ISSUE_AGENT_SESSION_OWNER, diffThreadCmd));
    assertTrue(ex2.getMessage().contains("bound to a different session or branch"));
  }

  @Test
  void rejectsMissingMismatchedOrUnownedSessionsAndThreads() {
    // relation 缺失与 owner 不匹配都 fail closed；THREAD 不存在时不能猜测 Session。
    AcceptCommandsCommand chatEntry =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewThread(SESSION_ID, ENTRY_ID, THREAD_ID, false),
            List.of(user(new TextMessageContent("entry"))));
    assertThrows(IllegalArgumentException.class, () -> service.accept(CHAT_OWNER, chatEntry));

    when(chatSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new ChatSession(SESSION_ID, id(99)));
    assertThrows(IllegalArgumentException.class, () -> service.accept(CHAT_OWNER, chatEntry));

    AcceptCommandsCommand canvasEntry =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewThread(SESSION_ID, ENTRY_ID, THREAD_ID, false),
            List.of(user(new TextMessageContent("canvas entry"))));
    assertThrows(IllegalArgumentException.class, () -> service.accept(CANVAS_OWNER, canvasEntry));
    when(canvasSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new CanvasSession(SESSION_ID, id(98)));
    assertThrows(IllegalArgumentException.class, () -> service.accept(CANVAS_OWNER, canvasEntry));

    AcceptCommandsCommand agentEntry =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewThread(SESSION_ID, ENTRY_ID, THREAD_ID, false),
            List.of(user(new TextMessageContent("agent entry"))));
    when(issueAgentSessionOwnershipRepository.findAgentSessionIdBySessionId(SESSION_ID))
        .thenReturn(id(97));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.accept(ISSUE_AGENT_SESSION_OWNER, agentEntry));

    AcceptCommandsCommand threadCommand =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(THREAD_ID, ENTRY_ID, 1),
            List.of(user(new TextMessageContent("thread"))));
    assertThrows(IllegalArgumentException.class, () -> service.accept(CHAT_OWNER, threadCommand));

    when(transaction.findSession(SESSION_ID))
        .thenReturn(Optional.of(new Session(SESSION_ID, "session", NOW)));
    when(chatSessionRepository.findBySessionId(SESSION_ID)).thenReturn(null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.accept(CHAT_OWNER, newSession(user(new TextMessageContent("orphan replay")))));

    verify(runtime, never()).acceptCommands(any(), any());
  }

  @Test
  void rejectsOwnershipInsertConflictsForChat() {
    // Relation 未插入或数据库报告 owner 主键冲突时都回滚 acceptance。
    AcceptCommandsCommand command = newSession(user(new TextMessageContent("chat ownership")));
    AcceptancePreflight preflight = acceptAndCapturePreflight(CHAT_OWNER, command);
    when(chatSessionRepository.insert(SESSION_ID, CHAT_ID)).thenReturn(false);

    assertThrows(
        IllegalStateException.class,
        () ->
            preflight.prepare(
                transaction, new Session(SESSION_ID, "session", NOW), command.commands()));

    when(chatSessionRepository.insert(SESSION_ID, CHAT_ID))
        .thenThrow(new DataIntegrityViolationException("owner conflict"));
    assertThrows(
        IllegalStateException.class,
        () ->
            preflight.prepare(
                transaction, new Session(SESSION_ID, "session", NOW), command.commands()));
  }

  @Test
  void rejectsOwnershipInsertConflictsForIssueAgentSession() {
    AcceptCommandsCommand command = newSession(user(new TextMessageContent("agent ownership")));
    AcceptancePreflight agentPreflight =
        acceptAndCapturePreflight(ISSUE_AGENT_SESSION_OWNER, command);
    when(issueAgentSessionOwnershipRepository.insert(SESSION_ID, ISSUE_AGENT_SESSION_ID))
        .thenReturn(false);
    assertThrows(
        IllegalStateException.class,
        () ->
            agentPreflight.prepare(
                transaction, new Session(SESSION_ID, "session", NOW), command.commands()));

    doThrow(new DataIntegrityViolationException("duplicate binding"))
        .when(issueAgentSessionOwnershipRepository)
        .insert(SESSION_ID, ISSUE_AGENT_SESSION_ID);
    assertThrows(
        IllegalStateException.class,
        () ->
            agentPreflight.prepare(
                transaction, new Session(SESSION_ID, "session", NOW), command.commands()));
  }

  @Test
  void materializesAttachmentBeforeDeletingUploadAndPreservesRawIdentity() {
    // READY upload 先建立 Session retain，再删除 upload；durable payload 替换但 raw 幂等键不变。
    NewThreadCommand raw = user(new AttachmentMessageContent(UPLOAD_ID));
    AcceptCommandsCommand command = newSession(raw);
    when(chatSessionRepository.insert(SESSION_ID, CHAT_ID)).thenReturn(true);
    when(uploadService.lockReady(UPLOAD_ID))
        .thenReturn(new StorageUploadService.ReadyUpload(BLOB_ID, "report.pdf"));
    AcceptancePreflight preflight = acceptAndCapturePreflight(CHAT_OWNER, command);

    List<NewThreadCommand> prepared =
        preflight.prepare(transaction, new Session(SESSION_ID, "session", NOW), command.commands());

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

  @Test
  void rejectsNullDependenciesInConstructor() {
    // 强依赖验证：各 Repository、Store、Runtime、Upload service 与 Session ref manager 必须非空注入。
    assertThrows(
        NullPointerException.class,
        () ->
            new HarnessCommandAcceptanceOrchestrator(
                null,
                canvasSessionRepository,
                chatRepository,
                canvasStore,
                projectRepository,
                issueRepository,
                issueAgentSessionRepository,
                issueAgentSessionOwnershipRepository,
                stores,
                runtimes,
                uploadService,
                refManager));
    assertThrows(
        NullPointerException.class,
        () ->
            new HarnessCommandAcceptanceOrchestrator(
                chatSessionRepository,
                canvasSessionRepository,
                chatRepository,
                canvasStore,
                projectRepository,
                issueRepository,
                issueAgentSessionRepository,
                issueAgentSessionOwnershipRepository,
                stores,
                runtimes,
                null,
                refManager));
    assertThrows(
        NullPointerException.class,
        () ->
            new HarnessCommandAcceptanceOrchestrator(
                chatSessionRepository,
                canvasSessionRepository,
                chatRepository,
                canvasStore,
                projectRepository,
                issueRepository,
                issueAgentSessionRepository,
                issueAgentSessionOwnershipRepository,
                stores,
                runtimes,
                uploadService,
                null));
  }

  @Test
  void translatesAttachmentLookupAndVerificationFailures() {
    // Storage 的 not-found 与 verification 失败都统一成为非法用户内容，且不得 retain/delete。
    AcceptCommandsCommand command = newSession(user(new AttachmentMessageContent(UPLOAD_ID)));
    when(chatSessionRepository.insert(SESSION_ID, CHAT_ID)).thenReturn(true);
    AcceptancePreflight preflight = acceptAndCapturePreflight(CHAT_OWNER, command);
    doThrow(new StorageResourceNotFoundException("upload", UPLOAD_ID.toString()))
        .when(uploadService)
        .lockReady(UPLOAD_ID);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            preflight.prepare(
                transaction, new Session(SESSION_ID, "session", NOW), command.commands()));

    doThrow(new StorageVerificationException("upload is pending"))
        .when(uploadService)
        .lockReady(UPLOAD_ID);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            preflight.prepare(
                transaction, new Session(SESSION_ID, "session", NOW), command.commands()));
    verify(refManager, never()).retainRef(any(), any());
    verify(uploadService, never()).delete(any());
  }

  @Test
  void reusesOnlyResourcesAlreadyOwnedByTheSession() {
    // Durable RESOURCE 不新增 retain；跨 Session blob 明确拒绝。
    ResourceMessageContent resource =
        ResourceMessageContent.media(BLOB_ID, "existing.txt", "preview");
    AcceptCommandsCommand command = newSession(user(resource));
    when(chatSessionRepository.insert(SESSION_ID, CHAT_ID)).thenReturn(true);
    AcceptancePreflight preflight = acceptAndCapturePreflight(CHAT_OWNER, command);

    when(refManager.contains(SESSION_ID, BLOB_ID)).thenReturn(false);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            preflight.prepare(
                transaction, new Session(SESSION_ID, "session", NOW), command.commands()));

    when(refManager.contains(SESSION_ID, BLOB_ID)).thenReturn(true);
    List<NewThreadCommand> prepared =
        preflight.prepare(transaction, new Session(SESSION_ID, "session", NOW), command.commands());
    UserMessageCommandPayload payload =
        assertInstanceOf(UserMessageCommandPayload.class, prepared.getFirst().payload());
    assertSame(resource, payload.message().contents().getFirst());
    verify(refManager, never()).retainRef(any(), any());
  }

  @Test
  void requiresRuntimeAndStoreBeforeAcceptance() {
    // 缺 Runtime 或 Store 时部署必须 fail closed，不能返回伪成功。
    when(runtimes.getIfAvailable()).thenReturn(null);
    assertThrows(
        IllegalStateException.class,
        () -> service.accept(CHAT_OWNER, newSession(user(new TextMessageContent("runtime")))));

    when(runtimes.getIfAvailable()).thenReturn(runtime);
    when(stores.getIfAvailable()).thenReturn(null);
    assertThrows(
        IllegalStateException.class,
        () -> service.accept(CHAT_OWNER, newSession(user(new TextMessageContent("store")))));
    verify(runtime, never()).acceptCommands(any(), any());
  }

  /**
   * 测试意图：Issue Agent Branch 不得经产品入口设置或清除 Branch Goal——GOAL 命令必须对 {@code ISSUE_AGENT_SESSION} 的三个
   * target（NEW_SESSION / NEW_THREAD / THREAD）全部确定性拒绝，且在任何锁与 Runtime 调用之前失败；Chat/Canvas 的普通 Branch
   * Goal 不受影响。
   */
  @Test
  void rejectsGoalCommandsForIssueAgentSessionBeforeAnyLockOrRuntimeCall() {
    NewThreadCommand goal =
        new NewThreadCommand(new GoalCommandPayload("ship the release"), id(21));
    NewThreadCommand clearGoal = new NewThreadCommand(new GoalCommandPayload(null), id(22));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.accept(ISSUE_AGENT_SESSION_OWNER, newSession(goal)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.accept(
                ISSUE_AGENT_SESSION_OWNER,
                new AcceptCommandsCommand(
                    new AcceptCommandsTarget.NewThread(SESSION_ID, ENTRY_ID, THREAD_ID, false),
                    List.of(clearGoal))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.accept(
                ISSUE_AGENT_SESSION_OWNER,
                new AcceptCommandsCommand(
                    new AcceptCommandsTarget.Thread(THREAD_ID, ENTRY_ID, 1), List.of(goal))));

    // 拒绝必须在 owner 锁与 Runtime 之前发生：不读取归属、不物化附件、不入队命令。
    verify(runtime, never()).acceptCommands(any(), any());
    verify(issueAgentSessionRepository, never()).getById(any());
    verify(projectRepository, never()).lockForKeyShare(any());
    verify(issueAgentSessionOwnershipRepository, never()).findAgentSessionIdBySessionId(any());
  }

  @Test
  void acceptsGoalCommandsForOrdinaryChatBranches() {
    // Chat 普通 Branch 的 Goal 是产品能力：同一 GOAL 命令在 CHAT owner 下必须继续被接受。
    NewThreadCommand goal =
        new NewThreadCommand(new GoalCommandPayload("ship the release"), id(23));
    when(chatSessionRepository.insert(SESSION_ID, CHAT_ID)).thenReturn(true);

    assertSame(
        accepted, service.accept(CHAT_OWNER, newSession(goal, user(new TextMessageContent("go")))));

    verify(runtime).acceptCommands(any(), any());
  }

  private AcceptancePreflight acceptAndCapturePreflight(
      OwnerRef owner, AcceptCommandsCommand command) {
    assertSame(accepted, service.accept(owner, command));
    ArgumentCaptor<AcceptancePreflight> captor = ArgumentCaptor.forClass(AcceptancePreflight.class);
    verify(runtime).acceptCommands(eq(command), captor.capture());
    return captor.getValue();
  }

  private static AcceptCommandsCommand newSession(NewThreadCommand... commands) {
    return new AcceptCommandsCommand(
        new AcceptCommandsTarget.NewSession(SESSION_ID, THREAD_ID, SETTINGS, null, false),
        List.of(commands));
  }

  private static NewThreadCommand user(AgentMessageContent... contents) {
    return new NewThreadCommand(
        new UserMessageCommandPayload(new AgentMessage(AgentMessageRole.USER, List.of(contents))),
        id(30));
  }

  private static ThreadState thread(UUID threadId, UUID sessionId) {
    return new ThreadState(
        threadId, sessionId, ENTRY_ID, "0".repeat(64), "thread", false, 1, 0, NOW, NOW);
  }

  private static UUID id(long value) {
    return new UUID(0L, value);
  }
}
