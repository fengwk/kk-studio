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

import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasSessionRepository;
import fun.fengwk.kkstudio.canvas.CanvasStore;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadLifecycleStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.platform.chat.repo.ChatRepository;
import fun.fengwk.kkstudio.platform.chat.repo.ChatSessionRepository;
import fun.fengwk.kkstudio.platform.chat.service.model.Chat;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueAgentSession;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentSessionOwnershipRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentSessionRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * owner 深删的编排契约：锁序、闭包范围、relation 顺序与 fail-safe 回滚。
 *
 * <p>测试意图：真实 PG 上的外键/FK 行为由 {@code SessionDeletionOrchestratorPostgresTest}
 * 覆盖；本测试只固定平台编排顺序与可达范围，用内存模型 表达 Harness Store 的 Thread/Session 图。
 */
class SessionDeletionOrchestratorTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final UUID CHAT_ID = id(1);
  private static final UUID CANVAS_ID = id(2);
  private static final UUID SESSION_1 = id(10);
  private static final UUID SESSION_2 = id(20);
  private static final UUID SESSION_3 = id(25);
  private static final UUID THREAD_1 = id(30);
  private static final UUID THREAD_2 = id(40);
  private static final UUID THREAD_3 = id(45);
  private static final UUID THREAD_FORK = id(55);
  private static final UUID BLOB_1 = id(50);
  private static final UUID BLOB_2 = id(60);
  private static final UUID PROJECT_ID = id(70);
  private static final UUID ISSUE_AGENT_SESSION_ID = id(80);
  private static final UUID ISSUE_ID = id(90);
  private static final String AGENT_NAME = "executor";
  private static final OwnerRef CHAT_OWNER = new OwnerRef(OwnerType.CHAT, CHAT_ID);
  private static final OwnerRef CANVAS_OWNER = new OwnerRef(OwnerType.CANVAS, CANVAS_ID);
  private static final OwnerRef ISSUE_AGENT_SESSION_OWNER =
      new OwnerRef(OwnerType.ISSUE_AGENT_SESSION, ISSUE_AGENT_SESSION_ID);

  private ChatSessionRepository chatSessionRepository;
  private CanvasSessionRepository canvasSessionRepository;
  private ChatRepository chatRepository;
  private CanvasStore canvasStore;
  private ProjectRepository projectRepository;
  private IssueRepository issueRepository;
  private IssueAgentSessionRepository issueAgentSessionRepository;
  private IssueAgentSessionOwnershipRepository issueAgentSessionOwnershipRepository;
  private ObjectProvider<HarnessStore> stores;
  private HarnessStore store;
  private HarnessStore.Transaction transaction;
  private SessionBlobRefManager refManager;
  private IssueAgentSession agentSessionBinding;
  private Project project;
  private Issue issue;
  private SessionDeletionOrchestrator service;

  /** 内存模型：Session -> Thread、Thread -> 直接子 Thread，以及当前仍存在的 Session/Thread。 */
  private final Map<UUID, List<ThreadState>> sessionThreads = new HashMap<>();

  private final Map<UUID, List<ThreadState>> children = new HashMap<>();
  private final Set<UUID> presentSessions = new HashSet<>();
  private final Map<UUID, ThreadState> threads = new HashMap<>();

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
    when(transaction.listThreadsBySession(any()))
        .thenAnswer(
            inv -> new ArrayList<>(sessionThreads.getOrDefault(inv.getArgument(0), List.of())));
    when(transaction.listChildren(any()))
        .thenAnswer(inv -> new ArrayList<>(children.getOrDefault(inv.getArgument(0), List.of())));
    when(transaction.findAncestorChain(any())).thenAnswer(inv -> ancestorChain(inv.getArgument(0)));
    when(transaction.lockSessionForUpdate(any()))
        .thenAnswer(
            inv ->
                presentSessions.contains(inv.getArgument(0))
                    ? Optional.of(new Session(inv.getArgument(0), "session", NOW))
                    : Optional.empty());
    when(transaction.lockThread(any()))
        .thenAnswer(inv -> Optional.ofNullable(threads.get(inv.getArgument(0))));
    when(transaction.findThread(any()))
        .thenAnswer(inv -> Optional.ofNullable(threads.get(inv.getArgument(0))));
    when(transaction.deleteThreads(any()))
        .thenAnswer(inv -> ((List<UUID>) inv.getArgument(0)).size());
    when(transaction.deleteSession(any())).thenReturn(true);

    when(chatRepository.lockById(CHAT_ID)).thenReturn(mock(Chat.class));
    when(canvasStore.lockDocument(CANVAS_ID)).thenReturn(Optional.of(mock(CanvasDocument.class)));

    agentSessionBinding =
        IssueAgentSession.builder()
            .id(ISSUE_AGENT_SESSION_ID)
            .issueId(ISSUE_ID)
            .agentName(AGENT_NAME)
            .sessionId(SESSION_1)
            .threadId(THREAD_1)
            .createdAt(NOW)
            .updatedAt(NOW)
            .build();
    project = mock(Project.class);
    when(project.getId()).thenReturn(PROJECT_ID);
    when(projectRepository.lockById(PROJECT_ID)).thenReturn(project);
    issue = mock(Issue.class);
    when(issue.getId()).thenReturn(ISSUE_ID);
    when(issue.getProjectId()).thenReturn(PROJECT_ID);
    when(issueRepository.getById(ISSUE_ID)).thenReturn(issue);
    when(issueRepository.lockById(ISSUE_ID)).thenReturn(issue);
    when(issueAgentSessionRepository.getById(ISSUE_AGENT_SESSION_ID))
        .thenReturn(agentSessionBinding);
    when(issueAgentSessionRepository.findByIssueIdAndAgentName(ISSUE_ID, AGENT_NAME))
        .thenReturn(agentSessionBinding);

    service =
        new SessionDeletionOrchestrator(
            chatSessionRepository,
            canvasSessionRepository,
            chatRepository,
            canvasStore,
            projectRepository,
            issueRepository,
            issueAgentSessionRepository,
            issueAgentSessionOwnershipRepository,
            stores,
            refManager);
  }

  @Test
  void deletesChatSessionsAndThreadsInCanonicalLockOrder() {
    // Session 与 Thread 即使由仓库逆序返回，也必须在树锁后按 UUID 全局排序锁定并删除。
    when(chatSessionRepository.listSessionIds(CHAT_ID)).thenReturn(List.of(SESSION_2, SESSION_1));
    putSession(SESSION_1, thread(THREAD_2, SESSION_1));
    putSession(SESSION_2, thread(THREAD_1, SESSION_2));
    when(refManager.listBlobIds(SESSION_1)).thenReturn(List.of(BLOB_2, BLOB_1));

    service.deleteSessionsByOwner(CHAT_OWNER);

    InOrder storeOrder = inOrder(transaction);
    storeOrder.verify(transaction).lockTree(THREAD_1);
    storeOrder.verify(transaction).lockTree(THREAD_2);
    storeOrder.verify(transaction).lockSessionForUpdate(SESSION_1);
    storeOrder.verify(transaction).lockSessionForUpdate(SESSION_2);
    storeOrder.verify(transaction).lockThread(THREAD_1);
    storeOrder.verify(transaction).lockThread(THREAD_2);
    storeOrder.verify(transaction).deleteJoinsByChild(THREAD_1);
    storeOrder.verify(transaction).deleteJoinsByChild(THREAD_2);
    storeOrder.verify(transaction).deleteThreads(List.of(THREAD_1, THREAD_2));
    storeOrder.verify(transaction).deleteEntries(SESSION_1);
    storeOrder.verify(transaction).deleteSession(SESSION_1);
    storeOrder.verify(transaction).deleteEntries(SESSION_2);
    storeOrder.verify(transaction).deleteSession(SESSION_2);

    InOrder blobOrder = inOrder(refManager);
    blobOrder.verify(refManager).listBlobIds(SESSION_1);
    blobOrder.verify(refManager).releaseRef(SESSION_1, BLOB_2);
    blobOrder.verify(refManager).releaseRef(SESSION_1, BLOB_1);
    verify(chatSessionRepository).deleteBySessionId(SESSION_1);
    verify(chatSessionRepository).deleteBySessionId(SESSION_2);
    verifyNoInteractions(canvasSessionRepository, issueAgentSessionOwnershipRepository);
  }

  @Test
  void deletesTaskChildSessionsAndParentlessForksInTheSameTree() {
    // 测试意图：task 子 Session 没有 owner relation，且其中可能有另一入口建立的无父 root fork；
    // 闭包必须以 Session 为单位推进，才能把整棵树（含 fork）一起删除，而不是留下悬挂 join/History。
    when(chatSessionRepository.listSessionIds(CHAT_ID)).thenReturn(List.of(SESSION_1));
    ThreadState child = thread(THREAD_2, SESSION_2, THREAD_1);
    ThreadState fork = thread(THREAD_FORK, SESSION_2, null);
    putSession(SESSION_1, thread(THREAD_1, SESSION_1));
    putSession(SESSION_2, child, fork);
    putChildren(THREAD_1, child);

    service.deleteSessionsByOwner(CHAT_OWNER);

    // 子 Session 与其 fork 都被删除，且 fork 自己的执行树 root 也被锁定（否则并发可加入未锁的树）。
    verify(transaction).lockTree(THREAD_1);
    verify(transaction).lockTree(THREAD_FORK);
    verify(transaction).lockSessionForUpdate(SESSION_1);
    verify(transaction).lockSessionForUpdate(SESSION_2);
    verify(transaction).deleteThreads(List.of(THREAD_1, THREAD_2, THREAD_FORK));
    verify(transaction).deleteJoinsByChild(THREAD_2);
    verify(transaction).deleteJoinsByChild(THREAD_FORK);
    verify(transaction).deleteSession(SESSION_2);
  }

  @Test
  void newForkAppearingBetweenDiscoveryAndSessionLockAbortsDeletion() {
    // 测试意图：fork 创建由 Session 锁排除；若在发现与加锁之间出现新 Session，必须整体回滚而不是删掉漏后代的集合。
    when(chatSessionRepository.listSessionIds(CHAT_ID)).thenReturn(List.of(SESSION_1));
    putSession(SESSION_1, thread(THREAD_1, SESSION_1));
    when(transaction.lockSessionForUpdate(SESSION_1))
        .thenAnswer(
            inv -> {
              // 模拟并发 fork：加锁期间在另一个 Session 中出现 THREAD_1 的新子线程。
              putChild(THREAD_1, thread(THREAD_3, SESSION_3, THREAD_1));
              return Optional.of(new Session(SESSION_1, "session-1", NOW));
            });

    assertThrows(IllegalStateException.class, () -> service.deleteSessionsByOwner(CHAT_OWNER));

    verify(transaction, never()).deleteThreads(any());
    verify(transaction, never()).deleteJoinsByChild(any());
    verify(transaction, never()).deleteEntries(any());
    verify(transaction, never()).deleteSession(any());
  }

  @Test
  void deletesOnlyPresentCanvasSessions() {
    // 并发消失的 Session 被跳过，不产生删除副作用。
    when(canvasSessionRepository.listSessionIds(CANVAS_ID))
        .thenReturn(List.of(SESSION_2, SESSION_1));
    putSession(SESSION_2, thread(THREAD_2, SESSION_2));

    service.deleteSessionsByOwner(CANVAS_OWNER);

    verify(canvasSessionRepository, never()).deleteBySessionId(SESSION_1);
    verify(canvasSessionRepository).deleteBySessionId(SESSION_2);
    verify(transaction, never()).deleteEntries(SESSION_1);
    verify(transaction).deleteEntries(SESSION_2);
    verify(transaction).deleteSession(SESSION_2);
    verifyNoInteractions(chatSessionRepository, issueAgentSessionOwnershipRepository);
  }

  @Test
  void deletesIssueAgentSessionSessionsAndRelationsInCanonicalOrder() {
    // relation 行（FK RESTRICT）必须先于 Thread/Session 删除。
    when(issueAgentSessionOwnershipRepository.listSessionIds(ISSUE_AGENT_SESSION_ID))
        .thenReturn(List.of(SESSION_1));
    putSession(SESSION_1, thread(THREAD_1, SESSION_1));
    when(refManager.listBlobIds(SESSION_1)).thenReturn(List.of(BLOB_1));

    service.deleteSessionsByOwner(ISSUE_AGENT_SESSION_OWNER);

    InOrder lockOrder = inOrder(projectRepository, issueRepository, issueAgentSessionRepository);
    lockOrder.verify(projectRepository).lockById(PROJECT_ID);
    lockOrder.verify(issueRepository).lockById(ISSUE_ID);
    lockOrder.verify(issueAgentSessionRepository).findByIssueIdAndAgentName(ISSUE_ID, AGENT_NAME);

    InOrder deleteOrder =
        inOrder(
            issueAgentSessionOwnershipRepository,
            issueAgentSessionRepository,
            transaction,
            refManager);
    deleteOrder.verify(issueAgentSessionOwnershipRepository).deleteBySessionId(SESSION_1);
    deleteOrder.verify(issueAgentSessionRepository).deleteById(ISSUE_AGENT_SESSION_ID);
    deleteOrder.verify(transaction).lockThread(THREAD_1);
    deleteOrder.verify(transaction).deleteThreads(List.of(THREAD_1));
    deleteOrder.verify(refManager).releaseRef(SESSION_1, BLOB_1);
    deleteOrder.verify(transaction).deleteEntries(SESSION_1);
    deleteOrder.verify(transaction).deleteSession(SESSION_1);
  }

  @Test
  void missingOwnerIsAnIdempotentNoop() {
    // Owner 已不存在等价于删除已完成；不能枚举 relation，也不能打开 Harness 事务。
    when(chatRepository.lockById(CHAT_ID)).thenReturn(null);
    when(canvasStore.lockDocument(CANVAS_ID)).thenReturn(Optional.empty());
    service.deleteSessionsByOwner(CHAT_OWNER);
    service.deleteSessionsByOwner(CANVAS_OWNER);

    when(issueAgentSessionRepository.getById(ISSUE_AGENT_SESSION_ID)).thenReturn(null);
    service.deleteSessionsByOwner(ISSUE_AGENT_SESSION_OWNER);
    when(issueAgentSessionRepository.getById(ISSUE_AGENT_SESSION_ID))
        .thenReturn(agentSessionBinding);
    when(issueRepository.getById(ISSUE_ID)).thenReturn(null);
    service.deleteSessionsByOwner(ISSUE_AGENT_SESSION_OWNER);
    when(issueRepository.getById(ISSUE_ID)).thenReturn(issue);
    when(projectRepository.lockById(PROJECT_ID)).thenReturn(null);
    service.deleteSessionsByOwner(ISSUE_AGENT_SESSION_OWNER);
    when(projectRepository.lockById(PROJECT_ID)).thenReturn(project);
    when(issueRepository.lockById(ISSUE_ID)).thenReturn(null);
    service.deleteSessionsByOwner(ISSUE_AGENT_SESSION_OWNER);
    when(issueRepository.lockById(ISSUE_ID)).thenReturn(issue);
    when(issueAgentSessionRepository.findByIssueIdAndAgentName(ISSUE_ID, AGENT_NAME))
        .thenReturn(null);
    service.deleteSessionsByOwner(ISSUE_AGENT_SESSION_OWNER);

    verifyNoInteractions(
        chatSessionRepository,
        canvasSessionRepository,
        issueAgentSessionOwnershipRepository,
        store,
        refManager);
  }

  @Test
  void ownerWithoutSessionsReturnsBeforeStoreLookup() {
    when(chatSessionRepository.listSessionIds(CHAT_ID)).thenReturn(List.of());

    service.deleteSessionsByOwner(CHAT_OWNER);

    verify(stores, never()).getIfAvailable();
    verifyNoInteractions(store, transaction, refManager);
  }

  @Test
  void missingStoreFailsBeforeOpeningDeletionTransaction() {
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
    putSession(SESSION_1, thread(THREAD_1, SESSION_1));
    when(transaction.lockThread(THREAD_1)).thenReturn(Optional.empty());

    assertThrows(IllegalStateException.class, () -> service.deleteSessionsByOwner(CHAT_OWNER));

    verify(transaction, never()).deleteThreads(any());
    verify(transaction, never()).deleteEntries(SESSION_1);
    verify(transaction, never()).deleteSession(SESSION_1);
    verifyNoInteractions(refManager);
  }

  @Test
  void rejectsNullOwner() {
    assertThrows(NullPointerException.class, () -> service.deleteSessionsByOwner(null));
  }

  private void putSession(UUID sessionId, ThreadState... sessionThreads) {
    presentSessions.add(sessionId);
    List<ThreadState> threadsInSession = new ArrayList<>(List.of(sessionThreads));
    this.sessionThreads.put(sessionId, threadsInSession);
    for (ThreadState thread : sessionThreads) {
      threads.put(thread.id(), thread);
    }
  }

  private void putChildren(UUID parentThreadId, ThreadState... directChildren) {
    children.put(parentThreadId, List.of(directChildren));
    for (ThreadState child : directChildren) {
      threads.put(child.id(), child);
    }
  }

  /** 在 {@code parent} 下新增一个子线程；子线程自带其 Session。 */
  private void putChild(UUID parentThreadId, ThreadState child) {
    List<ThreadState> direct = new ArrayList<>(children.getOrDefault(parentThreadId, List.of()));
    direct.add(child);
    children.put(parentThreadId, List.copyOf(direct));
    threads.put(child.id(), child);
    presentSessions.add(child.sessionId());
    sessionThreads.computeIfAbsent(child.sessionId(), key -> new ArrayList<>()).add(child);
  }

  /** head-to-root 祖先链：沿不可变 parentThreadId 走到根。 */
  private List<UUID> ancestorChain(UUID threadId) {
    List<UUID> chain = new ArrayList<>();
    UUID current = threadId;
    while (current != null && threads.containsKey(current) && !chain.contains(current)) {
      chain.add(current);
      current = threads.get(current).parentThreadId();
    }
    if (current != null && !threads.containsKey(current)) {
      chain.add(current);
    }
    return chain;
  }

  private static ThreadState thread(UUID threadId, UUID sessionId) {
    return thread(threadId, sessionId, null);
  }

  private static ThreadState thread(UUID threadId, UUID sessionId, UUID parentThreadId) {
    return new ThreadState(
        threadId,
        sessionId,
        parentThreadId,
        id(100),
        "0".repeat(64),
        "thread",
        false,
        ThreadLifecycleStatus.IDLE,
        1,
        0,
        NOW,
        NOW);
  }

  private static UUID id(long value) {
    return new UUID(0L, value);
  }
}
