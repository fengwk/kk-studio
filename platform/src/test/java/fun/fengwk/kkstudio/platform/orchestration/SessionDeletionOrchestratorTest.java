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
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.platform.chat.repo.ChatRepository;
import fun.fengwk.kkstudio.platform.chat.repo.ChatSessionRepository;
import fun.fengwk.kkstudio.platform.chat.service.model.Chat;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

class SessionDeletionOrchestratorTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final UUID CHAT_ID = id(1);
  private static final UUID CANVAS_ID = id(2);
  private static final UUID SESSION_1 = id(10);
  private static final UUID SESSION_2 = id(20);
  private static final UUID THREAD_1 = id(30);
  private static final UUID THREAD_2 = id(40);
  private static final UUID BLOB_1 = id(50);
  private static final UUID BLOB_2 = id(60);
  private static final OwnerRef CHAT_OWNER = new OwnerRef(OwnerType.CHAT, CHAT_ID);
  private static final OwnerRef CANVAS_OWNER = new OwnerRef(OwnerType.CANVAS, CANVAS_ID);

  private ChatSessionRepository chatSessionRepository;
  private CanvasSessionRepository canvasSessionRepository;
  private ChatRepository chatRepository;
  private CanvasStore canvasStore;
  private ObjectProvider<HarnessStore> stores;
  private ObjectProvider<SessionBlobRefManager> refManagers;
  private HarnessStore store;
  private HarnessStore.Transaction transaction;
  private SessionBlobRefManager refManager;
  private SessionDeletionOrchestrator service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    chatSessionRepository = mock(ChatSessionRepository.class);
    canvasSessionRepository = mock(CanvasSessionRepository.class);
    chatRepository = mock(ChatRepository.class);
    canvasStore = mock(CanvasStore.class);
    stores = mock(ObjectProvider.class);
    refManagers = mock(ObjectProvider.class);
    store = mock(HarnessStore.class);
    transaction = mock(HarnessStore.Transaction.class);
    refManager = mock(SessionBlobRefManager.class);

    when(stores.getIfAvailable()).thenReturn(store);
    when(refManagers.getIfAvailable()).thenReturn(refManager);
    when(store.transaction(any()))
        .thenAnswer(
            invocation -> {
              Function<HarnessStore.Transaction, ?> callback = invocation.getArgument(0);
              return callback.apply(transaction);
            });
    when(chatRepository.lockById(CHAT_ID)).thenReturn(mock(Chat.class));
    when(canvasStore.lockDocument(CANVAS_ID)).thenReturn(Optional.of(mock(CanvasDocument.class)));

    service =
        new SessionDeletionOrchestrator(
            chatSessionRepository,
            canvasSessionRepository,
            chatRepository,
            canvasStore,
            stores,
            refManagers);
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
    verifyNoInteractions(canvasSessionRepository);
  }

  @Test
  void deletesOnlyPresentCanvasSessionsWithoutBlobManager() {
    // 并发消失的 Session 被跳过；禁用全局 storage 时仍删除无 blob ref 的 Canvas Session。
    when(canvasSessionRepository.listSessionIds(CANVAS_ID))
        .thenReturn(List.of(SESSION_2, SESSION_1));
    when(transaction.lockSessionForUpdate(SESSION_1)).thenReturn(Optional.empty());
    when(transaction.lockSessionForUpdate(SESSION_2))
        .thenReturn(Optional.of(new Session(SESSION_2, "session-2", NOW)));
    when(transaction.listThreadsBySession(SESSION_2)).thenReturn(List.of());
    when(refManagers.getIfAvailable()).thenReturn(null);

    service.deleteSessionsByOwner(CANVAS_OWNER);

    verify(canvasSessionRepository, never()).deleteBySessionId(SESSION_1);
    verify(canvasSessionRepository).deleteBySessionId(SESSION_2);
    verify(transaction, never()).deleteEntries(SESSION_1);
    verify(transaction).deleteEntries(SESSION_2);
    verify(transaction).deleteSession(SESSION_2);
    verifyNoInteractions(chatSessionRepository);
  }

  @Test
  void missingChatOrCanvasOwnerIsAnIdempotentNoop() {
    // Owner 已不存在等价于删除已完成，不能枚举 relation 或打开 Harness 事务。
    when(chatRepository.lockById(CHAT_ID)).thenReturn(null);
    when(canvasStore.lockDocument(CANVAS_ID)).thenReturn(Optional.empty());

    service.deleteSessionsByOwner(CHAT_OWNER);
    service.deleteSessionsByOwner(CANVAS_OWNER);

    verifyNoInteractions(chatSessionRepository, canvasSessionRepository, store, refManager);
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
    verify(chatSessionRepository, never()).deleteBySessionId(SESSION_1);
    verifyNoInteractions(refManager);
  }

  @Test
  void rejectsNullOwner() {
    // 删除边界不接受缺失 owner。
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
