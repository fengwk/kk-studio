package fun.fengwk.kkstudio.platform.orchestration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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
import fun.fengwk.kkstudio.harness.runtime.store.EntryStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.JoinStore;
import fun.fengwk.kkstudio.harness.runtime.store.SessionStore;
import fun.fengwk.kkstudio.harness.runtime.store.ThreadStore;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.platform.chat.Chat;
import fun.fengwk.kkstudio.platform.chat.ChatRepository;
import fun.fengwk.kkstudio.platform.chat.ChatSessionRepository;
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
 * owner 深删除编排的单元契约：锁序、闭包范围、relation 顺序与 fail-safe 回滚。
 *
 * <p>测试意图：真实 PG 上的外键/FK 行为由 Project 模块的 {@code SessionDeletionIntegrationTest} 覆盖；本测试用内存模型表达
 * Harness Store 的 Thread/Session 图，固定锁序（Owner -&gt; Tree -&gt; Session -&gt; Thread）、以 Session
 * 为单位推进的删除闭包（含 task 子 Session 与其中的无父 root fork）、归属行先于 harness Thread/Session 删除、blob 引用释放，以及「存在 Run
 * 引用绑定/Thread 时删除被 RESTRICT 拒绝且不产生半删状态」的 fail closed 行为。
 */
class SessionDeletionOrchestratorTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final UUID CHAT_ID = id(1);
  private static final UUID SESSION_1 = id(10);
  private static final UUID SESSION_2 = id(20);
  private static final UUID SESSION_3 = id(25);
  private static final UUID THREAD_1 = id(30);
  private static final UUID THREAD_2 = id(40);
  private static final UUID THREAD_3 = id(45);
  private static final UUID THREAD_FORK = id(55);
  private static final UUID BLOB_1 = id(50);
  private static final UUID BLOB_2 = id(60);
  private static final UUID ISSUE_ID = id(90);
  private static final String AGENT_NAME = "executor";
  private static final OwnerRef CHAT_OWNER = new OwnerRef.Chat(CHAT_ID);
  private static final OwnerRef.IssueAgent ISSUE_AGENT =
      new OwnerRef.IssueAgent(ISSUE_ID, AGENT_NAME);
  private static final OwnerRef ISSUE_AGENT_OWNER = ISSUE_AGENT;

  private ChatSessionRepository chatSessionRepository;
  private ChatRepository chatRepository;
  private IssueAgentOwnership issueAgentOwnership;
  private ObjectProvider<IssueAgentOwnership> issueAgentOwnerships;
  private ObjectProvider<HarnessStore> stores;
  private HarnessStore store;
  private HarnessStore.Transaction transaction;
  private SessionStore sessionStore;
  private EntryStore entryStore;
  private ThreadStore threadStore;
  private JoinStore joinStore;
  private SessionBlobRefManager refManager;
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
    chatRepository = mock(ChatRepository.class);
    issueAgentOwnership = mock(IssueAgentOwnership.class);
    issueAgentOwnerships = mock(ObjectProvider.class);
    stores = mock(ObjectProvider.class);
    store = mock(HarnessStore.class);
    transaction = mock(HarnessStore.Transaction.class);
    sessionStore = mock(SessionStore.class);
    entryStore = mock(EntryStore.class);
    threadStore = mock(ThreadStore.class);
    joinStore = mock(JoinStore.class);
    refManager = mock(SessionBlobRefManager.class);

    when(transaction.sessions()).thenReturn(sessionStore);
    when(transaction.entries()).thenReturn(entryStore);
    when(transaction.threads()).thenReturn(threadStore);
    when(transaction.joins()).thenReturn(joinStore);
    when(stores.getIfAvailable()).thenReturn(store);
    when(store.transaction(any()))
        .thenAnswer(
            invocation -> {
              Function<HarnessStore.Transaction, ?> callback = invocation.getArgument(0);
              return callback.apply(transaction);
            });
    when(threadStore.listBySession(any()))
        .thenAnswer(
            inv -> new ArrayList<>(sessionThreads.getOrDefault(inv.getArgument(0), List.of())));
    when(threadStore.listChildren(any()))
        .thenAnswer(inv -> new ArrayList<>(children.getOrDefault(inv.getArgument(0), List.of())));
    when(threadStore.findAncestorChain(any())).thenAnswer(inv -> ancestorChain(inv.getArgument(0)));
    when(sessionStore.lockForUpdate(any()))
        .thenAnswer(
            inv ->
                presentSessions.contains(inv.getArgument(0))
                    ? Optional.of(new Session(inv.getArgument(0), "session", NOW))
                    : Optional.empty());
    when(threadStore.lock(any()))
        .thenAnswer(inv -> Optional.ofNullable(threads.get(inv.getArgument(0))));
    when(threadStore.find(any()))
        .thenAnswer(inv -> Optional.ofNullable(threads.get(inv.getArgument(0))));
    when(threadStore.delete(any())).thenAnswer(inv -> ((List<UUID>) inv.getArgument(0)).size());
    when(sessionStore.delete(any())).thenReturn(true);

    when(chatRepository.lockById(CHAT_ID)).thenReturn(mock(Chat.class));

    when(issueAgentOwnerships.getIfAvailable()).thenReturn(issueAgentOwnership);
    when(issueAgentOwnership.lockForDeletion(ISSUE_AGENT)).thenReturn(Optional.of(THREAD_1));

    service =
        new SessionDeletionOrchestrator(
            chatSessionRepository, chatRepository, issueAgentOwnerships, stores, refManager);
  }

  @Test
  void deletesChatSessionsAndThreadsInCanonicalLockOrder() {
    // Session 与 Thread 即使由仓库逆序返回，也必须在树锁后按 UUID 全局排序锁定并删除。
    when(chatSessionRepository.listSessionIds(CHAT_ID)).thenReturn(List.of(SESSION_2, SESSION_1));
    putSession(SESSION_1, thread(THREAD_2, SESSION_1));
    putSession(SESSION_2, thread(THREAD_1, SESSION_2));
    when(refManager.listBlobIds(SESSION_1)).thenReturn(List.of(BLOB_2, BLOB_1));

    service.deleteSessionsByOwner(CHAT_OWNER);

    InOrder storeOrder = inOrder(threadStore, sessionStore, joinStore, entryStore);
    storeOrder.verify(threadStore).lockTree(THREAD_1);
    storeOrder.verify(threadStore).lockTree(THREAD_2);
    storeOrder.verify(sessionStore).lockForUpdate(SESSION_1);
    storeOrder.verify(sessionStore).lockForUpdate(SESSION_2);
    storeOrder.verify(threadStore).lock(THREAD_1);
    storeOrder.verify(threadStore).lock(THREAD_2);
    storeOrder.verify(joinStore).deleteForThreads(List.of(THREAD_1, THREAD_2));
    storeOrder.verify(threadStore).delete(List.of(THREAD_1, THREAD_2));
    storeOrder.verify(entryStore).delete(SESSION_1);
    storeOrder.verify(sessionStore).delete(SESSION_1);
    storeOrder.verify(entryStore).delete(SESSION_2);
    storeOrder.verify(sessionStore).delete(SESSION_2);

    InOrder blobOrder = inOrder(refManager);
    blobOrder.verify(refManager).listBlobIds(SESSION_1);
    blobOrder.verify(refManager).releaseRef(SESSION_1, BLOB_2);
    blobOrder.verify(refManager).releaseRef(SESSION_1, BLOB_1);
    verify(chatSessionRepository).deleteBySessionId(SESSION_1);
    verify(chatSessionRepository).deleteBySessionId(SESSION_2);
    verifyNoInteractions(issueAgentOwnership);
  }

  @Test
  void deletesIssueAgentSessionByResolvingBindingThreadAndRemovesBindingFirst() {
    // Session 只能由稳定的绑定 Thread 解析；归属行必须先于 harness Thread/Session 删除。
    putSession(SESSION_1, thread(THREAD_1, SESSION_1));
    when(refManager.listBlobIds(SESSION_1)).thenReturn(List.of(BLOB_1));

    service.deleteSessionsByOwner(ISSUE_AGENT_OWNER);

    InOrder deleteOrder =
        inOrder(
            issueAgentOwnership,
            threadStore,
            joinStore,
            sessionStore,
            entryStore,
            refManager,
            chatSessionRepository);
    deleteOrder.verify(issueAgentOwnership).lockForDeletion(ISSUE_AGENT);
    deleteOrder.verify(issueAgentOwnership).deleteBinding(ISSUE_AGENT);
    deleteOrder.verify(threadStore).lock(THREAD_1);
    deleteOrder.verify(joinStore).deleteForThreads(List.of(THREAD_1));
    deleteOrder.verify(threadStore).delete(List.of(THREAD_1));
    deleteOrder.verify(refManager).releaseRef(SESSION_1, BLOB_1);
    deleteOrder.verify(entryStore).delete(SESSION_1);
    deleteOrder.verify(sessionStore).delete(SESSION_1);
    verifyNoInteractions(chatSessionRepository);
  }

  @Test
  void skipsSessionsThatDisappearedBeforeLock() {
    // 并发消失的 Session 被跳过，只深删仍存在的 Session，且不为它删归属行或 History。
    when(chatSessionRepository.listSessionIds(CHAT_ID)).thenReturn(List.of(SESSION_2, SESSION_1));
    putSession(SESSION_2, thread(THREAD_2, SESSION_2));

    service.deleteSessionsByOwner(CHAT_OWNER);

    verify(chatSessionRepository, never()).deleteBySessionId(SESSION_1);
    verify(chatSessionRepository).deleteBySessionId(SESSION_2);
    verify(entryStore, never()).delete(SESSION_1);
    verify(entryStore).delete(SESSION_2);
    verify(sessionStore).delete(SESSION_2);
    verifyNoInteractions(issueAgentOwnership);
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
    verify(threadStore).lockTree(THREAD_1);
    verify(threadStore).lockTree(THREAD_FORK);
    verify(sessionStore).lockForUpdate(SESSION_1);
    verify(sessionStore).lockForUpdate(SESSION_2);
    verify(threadStore).delete(List.of(THREAD_1, THREAD_2, THREAD_FORK));
    verify(joinStore).deleteForThreads(List.of(THREAD_1, THREAD_2, THREAD_FORK));
    verify(sessionStore).delete(SESSION_2);
  }

  @Test
  void newForkAppearingBetweenDiscoveryAndSessionLockAbortsDeletion() {
    // 测试意图：fork 创建由 Session 锁排除；若在发现与加锁之间出现新 Session，必须整体回滚而不是删掉漏后代的集合。
    when(chatSessionRepository.listSessionIds(CHAT_ID)).thenReturn(List.of(SESSION_1));
    putSession(SESSION_1, thread(THREAD_1, SESSION_1));
    when(sessionStore.lockForUpdate(SESSION_1))
        .thenAnswer(
            inv -> {
              // 模拟并发 fork：加锁期间在另一个 Session 中出现 THREAD_1 的新子线程。
              putChild(THREAD_1, thread(THREAD_3, SESSION_3, THREAD_1));
              return Optional.of(new Session(SESSION_1, "session-1", NOW));
            });

    assertThrows(IllegalStateException.class, () -> service.deleteSessionsByOwner(CHAT_OWNER));

    verify(threadStore, never()).delete(any());
    verify(joinStore, never()).deleteForThreads(any());
    verify(entryStore, never()).delete(any());
    verify(sessionStore, never()).delete(any());
  }

  @Test
  void missingOwnerIsAnIdempotentNoop() {
    // Owner 已不存在等价于删除已完成；不能枚举 relation，也不能打开 Harness 事务。
    when(chatRepository.lockById(CHAT_ID)).thenReturn(null);
    service.deleteSessionsByOwner(CHAT_OWNER);

    // Issue+Agent owner 不存在或尚未绑定时 lockForDeletion 为空。
    when(issueAgentOwnership.lockForDeletion(ISSUE_AGENT)).thenReturn(Optional.empty());
    service.deleteSessionsByOwner(ISSUE_AGENT_OWNER);

    verifyNoInteractions(chatSessionRepository, store, refManager);
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

  /** Run 仍引用绑定/Thread 时归属行删除被 FK RESTRICT 拒绝：深删除必须整体失败且不触碰 Thread/Entry/Session，绝无绕过业务清理的硬删。 */
  @Test
  void runReferencedBindingFailsClosedWithoutDeletingHarnessFacts() {
    putSession(SESSION_1, thread(THREAD_1, SESSION_1));
    doThrow(new DataIntegrityViolationException("referenced by project_issue_run"))
        .when(issueAgentOwnership)
        .deleteBinding(ISSUE_AGENT);

    assertThrows(
        DataIntegrityViolationException.class,
        () -> service.deleteSessionsByOwner(ISSUE_AGENT_OWNER));

    verify(threadStore, never()).delete(any());
    verify(joinStore, never()).deleteForThreads(any());
    verify(entryStore, never()).delete(any());
    verify(sessionStore, never()).delete(any());
    verifyNoInteractions(refManager);
    verify(chatSessionRepository, never()).deleteBySessionId(any());
  }

  @Test
  void rejectsNullDependenciesAndNullOwner() {
    // 验证 SessionBlobRefManager 等强依赖与 owner 均不可为 null。
    assertThrows(
        NullPointerException.class,
        () ->
            new SessionDeletionOrchestrator(
                null, chatRepository, issueAgentOwnerships, stores, refManager));
    assertThrows(
        NullPointerException.class,
        () ->
            new SessionDeletionOrchestrator(
                chatSessionRepository, chatRepository, issueAgentOwnerships, stores, null));
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
    // 根线程使用独立开关；子线程恒 FOLLOW 传入的执行根（fixture 的 parent 即执行根）。
    return new ThreadState(
        threadId,
        sessionId,
        parentThreadId,
        id(100),
        "0".repeat(64),
        "thread",
        parentThreadId == null ? threadId : parentThreadId,
        false,
        SpawnTrigger.MODEL_TOOL,
        ThreadConstraints.NONE,
        ThreadExecutionControl.RUNNABLE,
        0L,
        1L,
        0L,
        NOW,
        NOW);
  }

  private static UUID id(long value) {
    return new UUID(0L, value);
  }
}
