package fun.fengwk.kkstudio.core.studio;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.ai.chat.repo.ChatRepository;
import fun.fengwk.kkstudio.core.ai.chat.repo.ChatSessionRepository;
import fun.fengwk.kkstudio.core.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasDocumentMapper;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.studio.canvas.CanvasSessionRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Chat/Canvas 共享的 Harness Session 深删除：owner 删除在该 owner 对应仓库的排他行锁保护下（调用方已锁定或本服务自取），按 relation 枚举
 * Session，逐 Session 以规范的 Session -&gt; Thread 锁序删除 Work/Tool/Model/Command/Thread、 release Session
 * blob ref、删 relation/Entry/Session，绝不误删其他 owner 的 Session。
 */
@Service
public class HarnessSessionDeletionService {

  private final ChatSessionRepository chatSessionRepository;
  private final CanvasSessionRepository canvasSessionRepository;
  private final ChatRepository chatRepository;
  private final CanvasDocumentMapper canvasDocumentMapper;
  private final ObjectProvider<HarnessStore> stores;
  private final ObjectProvider<SessionBlobRefManager> refManagers;

  public HarnessSessionDeletionService(
      ChatSessionRepository chatSessionRepository,
      CanvasSessionRepository canvasSessionRepository,
      ChatRepository chatRepository,
      CanvasDocumentMapper canvasDocumentMapper,
      ObjectProvider<HarnessStore> stores,
      ObjectProvider<SessionBlobRefManager> refManagers) {
    this.chatSessionRepository =
        Objects.requireNonNull(chatSessionRepository, "chatSessionRepository");
    this.canvasSessionRepository =
        Objects.requireNonNull(canvasSessionRepository, "canvasSessionRepository");
    this.chatRepository = Objects.requireNonNull(chatRepository, "chatRepository");
    this.canvasDocumentMapper =
        Objects.requireNonNull(canvasDocumentMapper, "canvasDocumentMapper");
    this.stores = Objects.requireNonNull(stores, "stores");
    this.refManagers = Objects.requireNonNull(refManagers, "refManagers");
  }

  /**
   * 深删除某 owner 的全部 Session（含 relation 行）。owner 行以排他锁锁定（Chat/Canvas 通用），锁序 consistent 于接受路径 （Owner
   * -&gt; Session -&gt; Thread），与 Harness Session 锁序一致；任何失败整体回滚。owner 行不存在视为已删，整个操作无副作用。
   */
  @Transactional
  public void deleteSessionsByOwner(StudioOwner owner) {
    Objects.requireNonNull(owner, "owner");
    if (!lockOwnerForDelete(owner)) {
      return;
    }
    UUID ownerId = owner.id();
    List<UUID> sessionIds =
        new ArrayList<>(
            owner.type() == StudioOwnerType.CHAT
                ? chatSessionRepository.listSessionIds(ownerId)
                : canvasSessionRepository.listSessionIds(ownerId));
    sessionIds.sort(UuidOrder.COMPARATOR);
    if (sessionIds.isEmpty()) {
      return;
    }
    HarnessStore store = stores.getIfAvailable();
    if (store == null) {
      throw new IllegalStateException(
          "harness store is not available; cannot deep delete owner sessions");
    }
    // store 事务（PROPAGATION_REQUIRED）加入本应用事务；blob release 经 SessionBlobRefManager（MANDATORY）。
    store.transaction(
        tx -> {
          // 阶段 1：以规范锁序先行锁定全部目标 Session（SESSION rank）。多 Session 深删若逐个取 SESSION 锁，
          // 第二 Session 开始会因曾取过 THREAD 锁而违反 SESSION -> THREAD 单调锁序。
          List<UUID> presentSessions = new ArrayList<>(sessionIds.size());
          for (UUID sessionId : sessionIds) {
            if (tx.lockSessionForUpdate(sessionId).isPresent()) {
              presentSessions.add(sessionId);
            }
          }
          // 阶段 2：跨全部 Session 收集并按 UUID 排序 Thread，保证 THREAD rank 全局单调递增。
          deleteThreadsDeep(tx, presentSessions);
          // 阶段 3：blob ref 释放（无 harness 锁）与 relation/Entry/Session 清理（均不取 harness 锁）。
          SessionBlobRefManager refManager = refManagers.getIfAvailable();
          for (UUID sessionId : presentSessions) {
            releaseSessionBlobRefs(refManager, sessionId);
            deleteRelation(owner, sessionId);
            tx.deleteEntries(sessionId);
            tx.deleteSession(sessionId);
          }
          return null;
        });
  }

  /** 锁定 owner 行用于删除；owner 不存在返回 {@code false}（删除目标不存在，整体视为 noop）。 */
  private boolean lockOwnerForDelete(StudioOwner owner) {
    if (owner.type() == StudioOwnerType.CHAT) {
      return chatRepository.lockById(owner.id()) != null;
    }
    return canvasDocumentMapper.getByIdForUpdate(owner.id()) != null;
  }

  /** 深删全部目标 Session 的 Thread：全局排序后逐 Thread 以 THREAD -&gt; children 顺序删除。 */
  private void deleteThreadsDeep(HarnessStore.Transaction tx, List<UUID> sessionIds) {
    List<ThreadState> threads = new ArrayList<>();
    for (UUID sessionId : sessionIds) {
      threads.addAll(tx.listThreadsBySession(sessionId));
    }
    threads.sort(Comparator.comparing(ThreadState::id, UuidOrder.COMPARATOR));
    for (ThreadState thread : threads) {
      UUID threadId = thread.id();
      // Thread FOR UPDATE 并删除其全部 Work/Tool/Model/Command/Thread 行（规范 Thread -&gt; children 顺序）。
      tx.lockThread(threadId)
          .orElseThrow(
              () ->
                  new IllegalStateException(
                      "thread "
                          + threadId
                          + " disappeared while deleting session "
                          + thread.sessionId()));
      tx.deleteWorkByThread(threadId);
      tx.deleteToolInvocations(threadId);
      tx.deleteModelInvocations(threadId);
      tx.deleteCommands(threadId);
      tx.deleteThread(threadId);
    }
  }

  /** release Session 级 blob 引用（ref_count 对账 + 删除 harness_session_blob_ref 行），先于 Session 删除。 */
  private void releaseSessionBlobRefs(SessionBlobRefManager refManager, UUID sessionId) {
    if (refManager != null) {
      for (UUID blobId : refManager.listBlobIds(sessionId)) {
        refManager.releaseRef(sessionId, blobId);
      }
    }
  }

  /** relation 行（FK RESTRICT）先于 Session 行删除。 */
  private void deleteRelation(StudioOwner owner, UUID sessionId) {
    if (owner.type() == StudioOwnerType.CHAT) {
      chatSessionRepository.deleteBySessionId(sessionId);
    } else {
      canvasSessionRepository.deleteBySessionId(sessionId);
    }
  }
}
