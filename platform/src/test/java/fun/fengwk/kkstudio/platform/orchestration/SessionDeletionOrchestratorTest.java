package fun.fengwk.kkstudio.platform.orchestration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;

import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.platform.chat.repo.ChatRepository;
import fun.fengwk.kkstudio.platform.chat.repo.ChatSessionRepository;
import fun.fengwk.kkstudio.platform.chat.service.model.Chat;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
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
 * owner 深删除编排的单元契约。
 *
 * <p>覆盖锁序（Owner -&gt; Session -&gt; Thread）、归属行先于 harness Thread/Session 删除、blob 引用释放，以及「存在 Run
 * 引用绑定/Thread 时删除被 RESTRICT 拒绝且不产生半删状态」的 fail closed 行为。
 */
class SessionDeletionOrchestratorTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final UUID CHAT_ID = id(1);
  private static final UUID SESSION_1 = id(10);
  private static final UUID SESSION_2 = id(20);
  private static final UUID THREAD_1 = id(30);
  private static final UUID THREAD_2 = id(40);
  private static final UUID BLOB_1 = id(50);
  private static final UUID BLOB_2 = id(60);
  private static final UUID PROJECT_ID = id(70);
  private static final UUID ISSUE_ID = id(90);
  private static final String AGENT_NAME = "executor";
  private static final OwnerRef CHAT_OWNER = new OwnerRef.Chat(CHAT_ID);
  private static final OwnerRef ISSUE_AGENT_OWNER = new OwnerRef.IssueAgent(ISSUE_ID, AGENT_NAME);

  private ChatSessionRepository chatSessionRepository;
  private ChatRepository chatRepository;
  private ProjectRepository projectRepository;
  private IssueRepository issueRepository;
  private IssueAgentThreadRepository issueAgentThreadRepository;
  private ObjectProvider<HarnessStore> stores;
  private HarnessStore store;
  private HarnessStore.Transaction transaction;
  private SessionBlobRefManager refManager;
  private IssueAgentThread binding;
  private Project project;
  private Issue issue;
  private SessionDeletionOrchestrator service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    chatSessionRepository = mock(ChatSessionRepository.class);
    chatRepository = mock(ChatRepository.class);
    projectRepository = mock(ProjectRepository.class);
    issueRepository = mock(IssueRepository.class);
    issueAgentThreadRepository = mock(IssueAgentThreadRepository.class);
    stores = mock(ObjectProvider.class);
    store = mock(HarnessStore.class);
    transaction = mock(HarnessStore.Transaction.class);
    refManager = mock(SessionBlobRefManager.class);

    when(stores.getIfAvailable()).thenReturn(store);
    when(store.transaction(any()))
        .thenAnswer(
            invocation -> {
              Function<HarnessStore.Transaction, ?> callback = invocation.getArgument(0);
              return callback.apply(transaction);
            });
    when(chatRepository.lockById(CHAT_ID)).thenReturn(mock(Chat.class));

    binding = new IssueAgentThread(ISSUE_ID, AGENT_NAME, THREAD_1);
    project = mock(Project.class);
    when(project.getId()).thenReturn(PROJECT_ID);
    when(projectRepository.lockById(PROJECT_ID)).thenReturn(project);

    issue = mock(Issue.class);
    when(issue.getId()).thenReturn(ISSUE_ID);
    when(issue.getProjectId()).thenReturn(PROJECT_ID);
    when(issueRepository.getById(ISSUE_ID)).thenReturn(issue);
    when(issueRepository.lockById(ISSUE_ID)).thenReturn(issue);
    when(issueAgentThreadRepository.findByIssueIdAndAgentName(ISSUE_ID, AGENT_NAME))
        .thenReturn(binding);

    service =
        new SessionDeletionOrchestrator(
            chatSessionRepository,
            chatRepository,
            projectRepository,
            issueRepository,
            issueAgentThreadRepository,
            stores,
            refManager);
  }

  @Test
  void deletesChatSessionsAndThreadsInCanonicalLockOrder() {
    // Session 与 Thread 即使由仓库逆序返回，也必须按 UUID 全局排序后锁定并按子事实顺序深删。
    when(chatSessionRepository.listSessionIds(CHAT_ID)).thenReturn(List.of(SESSION_2, SESSION_1));
    when(transaction.lockSessionForUpdate(SESSION_1))
        .thenReturn(Optional.of(new Session(SESSION_1, "session-1", NOW)));
    when(transaction.lockSessionForUpdate(SESSION_2))
        .thenReturn(Optional.of(new Session(SESSION_2, "session-2", NOW)));
    ThreadState thread1 = thread(THREAD_1, SESSION_2);
    ThreadState thread2 = thread(THREAD_2, SESSION_1);
    when(transaction.listThreadsBySession(SESSION_1)).thenReturn(List.of(thread2));
    when(transaction.listThreadsBySession(SESSION_2)).thenReturn(List.of(thread1));
    when(transaction.lockThread(THREAD_1)).thenReturn(Optional.of(thread1));
    when(transaction.lockThread(THREAD_2)).thenReturn(Optional.of(thread2));
    when(transaction.deleteThreads(List.of(THREAD_1, THREAD_2))).thenReturn(2);
    when(refManager.listBlobIds(SESSION_1)).thenReturn(List.of(BLOB_2, BLOB_1));
    when(refManager.listBlobIds(SESSION_2)).thenReturn(List.of());

    service.deleteSessionsByOwner(CHAT_OWNER);

    InOrder storeOrder = inOrder(transaction);
    storeOrder.verify(transaction).lockSessionForUpdate(SESSION_1);
    storeOrder.verify(transaction).lockSessionForUpdate(SESSION_2);
    storeOrder.verify(transaction).listThreadsBySession(SESSION_1);
    storeOrder.verify(transaction).listThreadsBySession(SESSION_2);
    storeOrder.verify(transaction).lockThread(THREAD_1);
    storeOrder.verify(transaction).lockThread(THREAD_2);
    storeOrder.verify(transaction).deleteThreads(List.of(THREAD_1, THREAD_2));
    storeOrder.verify(transaction).deleteEntries(SESSION_1);
    storeOrder.verify(transaction).deleteSession(SESSION_1);
    storeOrder.verify(transaction).deleteEntries(SESSION_2);
    storeOrder.verify(transaction).deleteSession(SESSION_2);

    InOrder blobOrder = inOrder(refManager);
    blobOrder.verify(refManager).listBlobIds(SESSION_1);
    blobOrder.verify(refManager).releaseRef(SESSION_1, BLOB_2);
    blobOrder.verify(refManager).releaseRef(SESSION_1, BLOB_1);
    blobOrder.verify(refManager).listBlobIds(SESSION_2);
    verify(chatSessionRepository).deleteBySessionId(SESSION_1);
    verify(chatSessionRepository).deleteBySessionId(SESSION_2);
    verifyNoInteractions(issueAgentThreadRepository);
  }

  @Test
  void deletesIssueAgentSessionByResolvingBindingThreadAndRemovesBindingFirst() {
    // Session 只能由稳定的绑定 Thread 解析；归属行必须先于 harness Thread/Session 删除。
    when(transaction.findThread(THREAD_1)).thenReturn(Optional.of(thread(THREAD_1, SESSION_1)));
    when(transaction.lockSessionForUpdate(SESSION_1))
        .thenReturn(Optional.of(new Session(SESSION_1, "agent-session", NOW)));
    ThreadState thread = thread(THREAD_1, SESSION_1);
    when(transaction.listThreadsBySession(SESSION_1)).thenReturn(List.of(thread));
    when(transaction.lockThread(THREAD_1)).thenReturn(Optional.of(thread));
    when(transaction.deleteThreads(List.of(THREAD_1))).thenReturn(1);
    when(refManager.listBlobIds(SESSION_1)).thenReturn(List.of(BLOB_1));

    service.deleteSessionsByOwner(ISSUE_AGENT_OWNER);

    InOrder lockOrder = inOrder(projectRepository, issueRepository, issueAgentThreadRepository);
    lockOrder.verify(projectRepository).lockById(PROJECT_ID);
    lockOrder.verify(issueRepository).lockById(ISSUE_ID);
    lockOrder.verify(issueAgentThreadRepository).findByIssueIdAndAgentName(ISSUE_ID, AGENT_NAME);

    InOrder deleteOrder =
        inOrder(issueAgentThreadRepository, transaction, refManager, chatSessionRepository);
    deleteOrder
        .verify(issueAgentThreadRepository)
        .deleteByIssueIdAndAgentName(ISSUE_ID, AGENT_NAME);
    deleteOrder.verify(transaction).lockThread(THREAD_1);
    deleteOrder.verify(transaction).deleteThreads(List.of(THREAD_1));
    deleteOrder.verify(refManager).releaseRef(SESSION_1, BLOB_1);
    deleteOrder.verify(transaction).deleteEntries(SESSION_1);
    deleteOrder.verify(transaction).deleteSession(SESSION_1);
    verifyNoInteractions(chatSessionRepository);
  }

  @Test
  void skipsSessionsThatDisappearedBeforeLock() {
    // 并发消失的 Session 被跳过，只深删仍存在的 Session。
    when(chatSessionRepository.listSessionIds(CHAT_ID)).thenReturn(List.of(SESSION_2, SESSION_1));
    when(transaction.lockSessionForUpdate(SESSION_1)).thenReturn(Optional.empty());
    when(transaction.lockSessionForUpdate(SESSION_2))
        .thenReturn(Optional.of(new Session(SESSION_2, "session-2", NOW)));
    when(transaction.listThreadsBySession(SESSION_2)).thenReturn(List.of());
    when(refManager.listBlobIds(SESSION_2)).thenReturn(List.of());

    service.deleteSessionsByOwner(CHAT_OWNER);

    verify(chatSessionRepository, never()).deleteBySessionId(SESSION_1);
    verify(chatSessionRepository).deleteBySessionId(SESSION_2);
    verify(transaction, never()).deleteEntries(SESSION_1);
    verify(transaction).deleteEntries(SESSION_2);
    verify(transaction).deleteSession(SESSION_2);
    verifyNoInteractions(issueAgentThreadRepository);
  }

  @Test
  void missingOwnerIsAnIdempotentNoop() {
    // Owner 已不存在等价于删除已完成，不能枚举 relation 或打开 Harness 事务。
    when(chatRepository.lockById(CHAT_ID)).thenReturn(null);
    service.deleteSessionsByOwner(CHAT_OWNER);

    when(issueRepository.getById(ISSUE_ID)).thenReturn(null);
    service.deleteSessionsByOwner(ISSUE_AGENT_OWNER);

    when(issueRepository.getById(ISSUE_ID)).thenReturn(issue);
    when(projectRepository.lockById(PROJECT_ID)).thenReturn(null);
    service.deleteSessionsByOwner(ISSUE_AGENT_OWNER);

    when(projectRepository.lockById(PROJECT_ID)).thenReturn(project);
    when(issueRepository.lockById(ISSUE_ID)).thenReturn(null);
    service.deleteSessionsByOwner(ISSUE_AGENT_OWNER);

    when(issueRepository.lockById(ISSUE_ID)).thenReturn(issue);
    when(issueAgentThreadRepository.findByIssueIdAndAgentName(ISSUE_ID, AGENT_NAME))
        .thenReturn(null);
    service.deleteSessionsByOwner(ISSUE_AGENT_OWNER);

    verifyNoInteractions(chatSessionRepository, store, refManager);
  }

  @Test
  void ownerWithoutSessionsReturnsBeforeStoreLookup() {
    // relation 列表为空时不需要 HarnessStore，也不产生删除副作用。
    when(chatSessionRepository.listSessionIds(CHAT_ID)).thenReturn(List.of());

    service.deleteSessionsByOwner(CHAT_OWNER);

    verify(stores, never()).getIfAvailable();
    verifyNoInteractions(store, transaction, refManager);
  }

  @Test
  void missingStoreFailsBeforeOpeningDeletionTransaction() {
    // 有归属 Session 却未装配 HarnessStore 时必须 fail closed。
    when(chatSessionRepository.listSessionIds(CHAT_ID)).thenReturn(List.of(SESSION_1));
    when(stores.getIfAvailable()).thenReturn(null);

    assertThrows(IllegalStateException.class, () -> service.deleteSessionsByOwner(CHAT_OWNER));

    verifyNoInteractions(transaction, refManager);
    verify(chatSessionRepository, never()).deleteBySessionId(any());
  }

  @Test
  void disappearingThreadAbortsDeepDeletionBeforeChildRowsAreRemoved() {
    // Session 锁后列出的 Thread 若在 Thread 锁阶段消失，必须抛错并依赖外事务整体回滚。
    when(chatSessionRepository.listSessionIds(CHAT_ID)).thenReturn(List.of(SESSION_1));
    when(transaction.lockSessionForUpdate(SESSION_1))
        .thenReturn(Optional.of(new Session(SESSION_1, "session-1", NOW)));
    ThreadState thread = thread(THREAD_1, SESSION_1);
    when(transaction.listThreadsBySession(SESSION_1)).thenReturn(List.of(thread));
    when(transaction.lockThread(THREAD_1)).thenReturn(Optional.empty());

    assertThrows(IllegalStateException.class, () -> service.deleteSessionsByOwner(CHAT_OWNER));

    verify(transaction, never()).deleteThreads(any());
    verify(transaction, never()).deleteEntries(SESSION_1);
    verify(transaction, never()).deleteSession(SESSION_1);
    verifyNoInteractions(refManager);
  }

  /** Run 仍引用绑定/Thread 时归属行删除被 FK RESTRICT 拒绝：深删除必须整体失败且不触碰 Thread/Entry/Session，绝无绕过业务清理的硬删。 */
  @Test
  void runReferencedBindingFailsClosedWithoutDeletingHarnessFacts() {
    when(transaction.findThread(THREAD_1)).thenReturn(Optional.of(thread(THREAD_1, SESSION_1)));
    when(transaction.lockSessionForUpdate(SESSION_1))
        .thenReturn(Optional.of(new Session(SESSION_1, "agent-session", NOW)));
    when(issueAgentThreadRepository.deleteByIssueIdAndAgentName(ISSUE_ID, AGENT_NAME))
        .thenThrow(new DataIntegrityViolationException("referenced by project_issue_run"));

    assertThrows(
        DataIntegrityViolationException.class,
        () -> service.deleteSessionsByOwner(ISSUE_AGENT_OWNER));

    verify(transaction, never()).deleteThreads(any());
    verify(transaction, never()).deleteEntries(any());
    verify(transaction, never()).deleteSession(any());
    verifyNoInteractions(refManager);
    verify(chatSessionRepository, never()).deleteBySessionId(any());
  }

  @Test
  void rejectsNullDependenciesAndNullOwner() {
    // 验证 SessionBlobRefManager 等强依赖不可为 null。
    assertThrows(
        NullPointerException.class,
        () ->
            new SessionDeletionOrchestrator(
                null,
                chatRepository,
                projectRepository,
                issueRepository,
                issueAgentThreadRepository,
                stores,
                refManager));
    assertThrows(
        NullPointerException.class,
        () ->
            new SessionDeletionOrchestrator(
                chatSessionRepository,
                chatRepository,
                projectRepository,
                issueRepository,
                issueAgentThreadRepository,
                stores,
                null));
    assertThrows(NullPointerException.class, () -> service.deleteSessionsByOwner(null));
  }

  private static ThreadState thread(UUID threadId, UUID sessionId) {
    return new ThreadState(
        threadId, sessionId, id(100), "0".repeat(64), "thread", false, 1, 0, NOW, NOW);
  }

  private static UUID id(long value) {
    return new UUID(0L, value);
  }
}
