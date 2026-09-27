package fun.fengwk.kkstudio.platform.orchestration;

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
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.model.ImageInputTier;
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
import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 产品命令接受编排的单元契约。
 *
 * <p>覆盖三类边界：owner 授权（Chat 由 {@code chat_session} 持有；Issue+Agent 只认 {@code (issueId, agentName) ->
 * threadId} 稳定绑定与由该 Thread 解析出的 Session）、NEW_SESSION 的归属建立（Chat 由 preflight 写 chat_session；
 * Issue+Agent 绑定由调用方在 Harness 接受之后于同一事务内写入，设计 §7.1），以及 USER_MESSAGE 附件物化。任何授权失败都必须在 Runtime
 * 之前确定性拒绝且不产生副作用。
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
  private static final UUID PROJECT_ID = id(10);
  private static final UUID ISSUE_ID = id(11);
  private static final String AGENT_NAME = "executor";
  private static final OwnerRef CHAT_OWNER = new OwnerRef.Chat(CHAT_ID);
  private static final OwnerRef ISSUE_AGENT_OWNER = new OwnerRef.IssueAgent(ISSUE_ID, AGENT_NAME);
  private static final BranchSettings SETTINGS =
      new BranchSettings("assistant", new ModelSelection("provider", "model", "default"), null);

  private ChatSessionRepository chatSessionRepository;
  private ChatRepository chatRepository;
  private ProjectRepository projectRepository;
  private IssueRepository issueRepository;
  private IssueAgentThreadRepository issueAgentThreadRepository;
  private ObjectProvider<HarnessStore> stores;
  private ObjectProvider<HarnessRuntime> runtimes;
  private HarnessStore store;
  private HarnessStore.Transaction transaction;
  private HarnessRuntime runtime;
  private StorageUploadService uploadService;
  private SessionBlobRefManager refManager;
  private StorageBlobManager blobManager;
  private AcceptedCommands accepted;
  private IssueAgentThread binding;
  private Project project;
  private Issue issue;
  private HarnessCommandAcceptanceOrchestrator service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    chatSessionRepository = mock(ChatSessionRepository.class);
    chatRepository = mock(ChatRepository.class);
    projectRepository = mock(ProjectRepository.class);
    issueRepository = mock(IssueRepository.class);
    issueAgentThreadRepository = mock(IssueAgentThreadRepository.class);
    stores = mock(ObjectProvider.class);
    runtimes = mock(ObjectProvider.class);
    store = mock(HarnessStore.class);
    transaction = mock(HarnessStore.Transaction.class);
    runtime = mock(HarnessRuntime.class);
    uploadService = mock(StorageUploadService.class);
    refManager = mock(SessionBlobRefManager.class);
    blobManager = mock(StorageBlobManager.class);
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

    binding = new IssueAgentThread(ISSUE_ID, AGENT_NAME, THREAD_ID);
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
    when(issueAgentThreadRepository.findByIssueIdAndAgentName(ISSUE_ID, AGENT_NAME))
        .thenReturn(binding);

    when(runtime.acceptCommands(any(), any())).thenReturn(accepted);

    service =
        new HarnessCommandAcceptanceOrchestrator(
            chatSessionRepository,
            chatRepository,
            projectRepository,
            issueRepository,
            issueAgentThreadRepository,
            stores,
            runtimes,
            uploadService,
            refManager,
            blobManager);
  }

  /**
   * NEW_SESSION preflight 只为 Chat 建立归属边 {@code chat_session}，并保留非 USER 命令；Issue+Agent 首次接受时本服务不写稳定
   * Thread 绑定（由调用方在接受后于同一物理事务内写入，设计 §7.1），但仍物化 USER_MESSAGE 内容。
   */
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
            transaction, new Session(SESSION_ID, "session", NOW), chatCommand.commands());

    assertSame(setAgent, chatPrepared.getFirst());
    assertEquals(user.idempotencyKey(), chatPrepared.get(1).idempotencyKey());
    assertEquals(user.requestHash(), chatPrepared.get(1).requestHash());
    UserMessageCommandPayload mapped =
        assertInstanceOf(UserMessageCommandPayload.class, chatPrepared.get(1).payload());
    assertEquals("hello", ((TextMessageContent) mapped.message().contents().getFirst()).text());
    verify(chatSessionRepository).insert(SESSION_ID, CHAT_ID);

    when(issueAgentThreadRepository.findByIssueIdAndAgentName(ISSUE_ID, AGENT_NAME))
        .thenReturn(null);
    AcceptCommandsCommand agentCommand = newSession(OTHER_THREAD_ID, user);
    AcceptancePreflight agentPreflight = acceptAndCapturePreflight(ISSUE_AGENT_OWNER, agentCommand);
    List<NewThreadCommand> agentPrepared =
        agentPreflight.prepare(
            transaction, new Session(SESSION_ID, "agent session", NOW), agentCommand.commands());

    assertEquals(1, agentPrepared.size());
    assertEquals(user.idempotencyKey(), agentPrepared.getFirst().idempotencyKey());
    verify(issueAgentThreadRepository, never()).insert(any());
    verify(chatSessionRepository, times(1)).insert(any(), any());
  }

  /** 已存在 Session（精确 replay）、NEW_THREAD 与 THREAD 都必须先锁 owner，再验证目标 Session 由该 owner 持有。 */
  @Test
  void authorizesExistingSessionAndThreadTargetsForTheirOwners() {
    when(transaction.findSession(SESSION_ID))
        .thenReturn(Optional.of(new Session(SESSION_ID, "session", NOW)));
    when(chatSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new ChatSession(SESSION_ID, CHAT_ID));
    AcceptCommandsCommand replay = newSession(THREAD_ID, user(new TextMessageContent("replay")));

    assertSame(accepted, service.accept(CHAT_OWNER, replay));

    AcceptCommandsCommand entry =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewThread(SESSION_ID, ENTRY_ID, OTHER_THREAD_ID, false),
            List.of(user(new TextMessageContent("entry"))));
    assertSame(accepted, service.accept(CHAT_OWNER, entry));

    // Issue+Agent：目标 Thread 属于绑定 Session 才允许继续接受；同 Session 内另开 Thread 也允许。
    when(transaction.findThread(THREAD_ID)).thenReturn(Optional.of(thread(THREAD_ID, SESSION_ID)));
    AcceptCommandsCommand threadCommand =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(THREAD_ID, ENTRY_ID, 1),
            List.of(user(new TextMessageContent("thread"))));
    assertSame(accepted, service.accept(ISSUE_AGENT_OWNER, threadCommand));

    AcceptCommandsCommand siblingThread =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewThread(SESSION_ID, ENTRY_ID, OTHER_THREAD_ID, false),
            List.of(user(new TextMessageContent("sibling"))));
    assertSame(accepted, service.accept(ISSUE_AGENT_OWNER, siblingThread));

    verify(chatRepository, times(2)).lockForKeyShare(CHAT_ID);
    verify(chatSessionRepository, times(2)).findBySessionId(SESSION_ID);
  }

  /**
   * Issue+Agent 稳定绑定由调用方在产品接受事务内、Harness 接受之后写入（设计 §7.1）：绑定缺失时只允许全新 NEW_SESSION；未绑定期间同一 Thread
   * 的后续接受必须被拒绝；绑定写入后同一 Thread 立即被授权。
   */
  @Test
  void issueAgentBindingIsCallerWrittenAfterNewSessionAcceptance() {
    when(issueAgentThreadRepository.findByIssueIdAndAgentName(ISSUE_ID, AGENT_NAME))
        .thenReturn(null);
    when(transaction.findThread(THREAD_ID)).thenReturn(Optional.of(thread(THREAD_ID, SESSION_ID)));

    assertSame(
        accepted,
        service.accept(
            ISSUE_AGENT_OWNER, newSession(THREAD_ID, user(new TextMessageContent("first")))));

    AcceptCommandsCommand threadCommand =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(THREAD_ID, ENTRY_ID, 1),
            List.of(user(new TextMessageContent("continue"))));
    assertThrows(
        IllegalArgumentException.class, () -> service.accept(ISSUE_AGENT_OWNER, threadCommand));

    // 调用方在同一事务内写入绑定（harness Thread 此时已存在，即时 FK 成立）后，同一 Thread 立即受该身份授权。
    when(issueAgentThreadRepository.findByIssueIdAndAgentName(ISSUE_ID, AGENT_NAME))
        .thenReturn(binding);
    assertSame(accepted, service.accept(ISSUE_AGENT_OWNER, threadCommand));
  }

  /** Issue+Agent 授权严格按 Project SHARE -> Issue UPDATE -> 稳定绑定读取的顺序加锁。 */
  @Test
  void issueAgentAcceptanceLocksProjectThenIssueThenBinding() {
    service.accept(ISSUE_AGENT_OWNER, newSession(THREAD_ID, user(new TextMessageContent("work"))));

    InOrder lockOrder = inOrder(projectRepository, issueRepository, issueAgentThreadRepository);
    lockOrder.verify(projectRepository).lockForKeyShare(PROJECT_ID);
    lockOrder.verify(issueRepository).lockById(ISSUE_ID);
    lockOrder.verify(issueAgentThreadRepository).findByIssueIdAndAgentName(ISSUE_ID, AGENT_NAME);
  }

  /** owner 行缺失、层级不一致或已归档时必须在 Runtime 接受之前确定性拒绝。 */
  @Test
  void rejectsMissingOrArchivedOwnerHierarchyBeforeRuntimeAcceptance() {
    AcceptCommandsCommand command = newSession(THREAD_ID, user(new TextMessageContent("denied")));

    when(chatRepository.lockForKeyShare(CHAT_ID)).thenReturn(null);
    assertThrows(IllegalArgumentException.class, () -> service.accept(CHAT_OWNER, command));

    when(issueRepository.getById(ISSUE_ID)).thenReturn(null);
    assertThrows(IllegalArgumentException.class, () -> service.accept(ISSUE_AGENT_OWNER, command));

    when(issueRepository.getById(ISSUE_ID)).thenReturn(issue);
    when(projectRepository.lockForKeyShare(PROJECT_ID)).thenReturn(null);
    assertThrows(IllegalArgumentException.class, () -> service.accept(ISSUE_AGENT_OWNER, command));

    Project archivedProject = mock(Project.class);
    when(archivedProject.getId()).thenReturn(PROJECT_ID);
    when(archivedProject.isArchived()).thenReturn(true);
    when(projectRepository.lockForKeyShare(PROJECT_ID)).thenReturn(archivedProject);
    assertThrows(IllegalArgumentException.class, () -> service.accept(ISSUE_AGENT_OWNER, command));

    when(projectRepository.lockForKeyShare(PROJECT_ID)).thenReturn(project);
    Issue archivedIssue = mock(Issue.class);
    when(archivedIssue.getId()).thenReturn(ISSUE_ID);
    when(archivedIssue.getProjectId()).thenReturn(PROJECT_ID);
    when(archivedIssue.isArchived()).thenReturn(true);
    when(issueRepository.getById(ISSUE_ID)).thenReturn(archivedIssue);
    when(issueRepository.lockById(ISSUE_ID)).thenReturn(archivedIssue);
    assertThrows(IllegalArgumentException.class, () -> service.accept(ISSUE_AGENT_OWNER, command));

    Issue foreignIssue = mock(Issue.class);
    when(foreignIssue.getId()).thenReturn(ISSUE_ID);
    when(foreignIssue.getProjectId()).thenReturn(id(12));
    when(issueRepository.getById(ISSUE_ID)).thenReturn(foreignIssue);
    when(issueRepository.lockById(ISSUE_ID)).thenReturn(foreignIssue);
    assertThrows(IllegalArgumentException.class, () -> service.accept(ISSUE_AGENT_OWNER, command));

    verify(runtime, never()).acceptCommands(any(), any());
  }

  /** 同一 Thread 只属于其稳定绑定的 Issue+Agent：向另一个 Issue 的 Agent 身份接受该 Thread（或该 Thread 所在 Session）必须被拒绝。 */
  @Test
  void rejectsIssueAgentAcceptanceForThreadAttachedToAnotherIssue() {
    when(issueAgentThreadRepository.findByIssueIdAndAgentName(ISSUE_ID, AGENT_NAME))
        .thenReturn(null);
    when(transaction.findThread(THREAD_ID)).thenReturn(Optional.of(thread(THREAD_ID, SESSION_ID)));

    AcceptCommandsCommand onThread =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(THREAD_ID, ENTRY_ID, 1),
            List.of(user(new TextMessageContent("steal"))));
    assertThrows(IllegalArgumentException.class, () -> service.accept(ISSUE_AGENT_OWNER, onThread));

    AcceptCommandsCommand onSession =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewThread(SESSION_ID, ENTRY_ID, OTHER_THREAD_ID, false),
            List.of(user(new TextMessageContent("steal session"))));
    assertThrows(
        IllegalArgumentException.class, () -> service.accept(ISSUE_AGENT_OWNER, onSession));

    verify(runtime, never()).acceptCommands(any(), any());
  }

  /** Session 已由另一 owner 持有时，NEW_SESSION 不能被当作 replay 接管。 */
  @Test
  void rejectsNewSessionWhenSessionIsOwnedByAnotherOwner() {
    when(transaction.findSession(SESSION_ID))
        .thenReturn(Optional.of(new Session(SESSION_ID, "session", NOW)));
    when(chatSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new ChatSession(SESSION_ID, OTHER_CHAT_ID));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.accept(CHAT_OWNER, newSession(THREAD_ID, user(new TextMessageContent("x")))));

    // Issue+Agent 不能接管 Chat 已持有的 Session（本身份尚未绑定）。
    when(issueAgentThreadRepository.findByIssueIdAndAgentName(ISSUE_ID, AGENT_NAME))
        .thenReturn(null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.accept(
                ISSUE_AGENT_OWNER, newSession(THREAD_ID, user(new TextMessageContent("x")))));

    verify(runtime, never()).acceptCommands(any(), any());
  }

  /** 绑定一旦存在就不可重绑：同一 Agent 不能用 NEW_SESSION 指向另一个 Thread。 */
  @Test
  void rejectsRebindingIssueAgentToAnotherThread() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.accept(
                ISSUE_AGENT_OWNER,
                newSession(OTHER_THREAD_ID, user(new TextMessageContent("rebind")))));

    assertEquals(
        binding, issueAgentThreadRepository.findByIssueIdAndAgentName(ISSUE_ID, AGENT_NAME));
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
                transaction, new Session(SESSION_ID, "session", NOW), chatCommand.commands()));

    when(chatSessionRepository.insert(SESSION_ID, CHAT_ID))
        .thenThrow(new DataIntegrityViolationException("duplicate session owner"));
    assertThrows(
        IllegalStateException.class,
        () ->
            chatPreflight.prepare(
                transaction, new Session(SESSION_ID, "session", NOW), chatCommand.commands()));

    verify(issueAgentThreadRepository, never()).insert(any());
  }

  /** Issue Agent Thread 不得经产品入口设置或清除 Branch Goal：三个 target 全部确定性拒绝，且在任何锁与 Runtime 调用之前失败。 */
  @Test
  void rejectsGoalCommandsForIssueAgentBeforeAnyLockOrRuntimeCall() {
    NewThreadCommand goal =
        new NewThreadCommand(new GoalCommandPayload("ship the release"), id(21));
    NewThreadCommand clearGoal = new NewThreadCommand(new GoalCommandPayload(null), id(22));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.accept(ISSUE_AGENT_OWNER, newSession(THREAD_ID, goal)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.accept(
                ISSUE_AGENT_OWNER,
                new AcceptCommandsCommand(
                    new AcceptCommandsTarget.NewThread(SESSION_ID, ENTRY_ID, THREAD_ID, false),
                    List.of(clearGoal))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.accept(
                ISSUE_AGENT_OWNER,
                new AcceptCommandsCommand(
                    new AcceptCommandsTarget.Thread(THREAD_ID, ENTRY_ID, 1), List.of(goal))));

    verify(runtime, never()).acceptCommands(any(), any());
    verify(issueAgentThreadRepository, never()).findByIssueIdAndAgentName(any(), any());
    verify(projectRepository, never()).lockForKeyShare(any());
    verify(chatRepository, never()).lockForKeyShare(any());
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
    AcceptCommandsCommand command = newSession(THREAD_ID, user(resource));
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
        () -> service.accept(CHAT_OWNER, newSession(THREAD_ID, user(new TextMessageContent("r")))));

    when(runtimes.getIfAvailable()).thenReturn(runtime);
    when(stores.getIfAvailable()).thenReturn(null);
    assertThrows(
        IllegalStateException.class,
        () -> service.accept(CHAT_OWNER, newSession(THREAD_ID, user(new TextMessageContent("s")))));
    verify(runtime, never()).acceptCommands(any(), any());
  }

  @Test
  void rejectsNullDependenciesAndNullOwner() {
    // 强依赖验证：各 Repository、Store、Runtime、Upload service、Session ref manager 与 Blob manager 必须非空注入。
    assertThrows(
        NullPointerException.class,
        () ->
            new HarnessCommandAcceptanceOrchestrator(
                null,
                chatRepository,
                projectRepository,
                issueRepository,
                issueAgentThreadRepository,
                stores,
                runtimes,
                uploadService,
                refManager,
                blobManager));
    assertThrows(
        NullPointerException.class,
        () ->
            new HarnessCommandAcceptanceOrchestrator(
                chatSessionRepository,
                chatRepository,
                projectRepository,
                issueRepository,
                issueAgentThreadRepository,
                stores,
                runtimes,
                null,
                refManager,
                blobManager));
    assertThrows(
        NullPointerException.class,
        () ->
            new HarnessCommandAcceptanceOrchestrator(
                chatSessionRepository,
                chatRepository,
                projectRepository,
                issueRepository,
                issueAgentThreadRepository,
                stores,
                runtimes,
                uploadService,
                refManager,
                null));
    assertThrows(
        NullPointerException.class,
        () -> service.accept(null, newSession(THREAD_ID, user(new TextMessageContent("null")))));
    assertThrows(NullPointerException.class, () -> service.accept(CHAT_OWNER, null));
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
            .prepare(transaction, new Session(SESSION_ID, "session", NOW), command.commands())
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
      OwnerRef owner, AcceptCommandsCommand command) {
    assertSame(accepted, service.accept(owner, command));
    ArgumentCaptor<AcceptancePreflight> captor = ArgumentCaptor.forClass(AcceptancePreflight.class);
    verify(runtime).acceptCommands(eq(command), captor.capture());
    return captor.getValue();
  }

  private static AcceptCommandsCommand newSession(UUID threadId, NewThreadCommand... commands) {
    return new AcceptCommandsCommand(
        new AcceptCommandsTarget.NewSession(SESSION_ID, threadId, SETTINGS, null, false),
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

  private static ThreadState thread(UUID threadId, UUID sessionId) {
    return new ThreadState(
        threadId, sessionId, ENTRY_ID, "0".repeat(64), "thread", false, 1, 0, NOW, NOW);
  }

  private static UUID id(long value) {
    return new UUID(0L, value);
  }
}
