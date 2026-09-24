package fun.fengwk.kkstudio.platform.orchestration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.canvas.CanvasSessionRepository;
import fun.fengwk.kkstudio.canvas.CanvasStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.platform.chat.repo.ChatRepository;
import fun.fengwk.kkstudio.platform.chat.repo.ChatSessionRepository;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueAgentSession;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentSessionOwnershipRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentSessionRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 产品 owner 共享的 Harness Session 深删除：owner 删除在该 owner 对应仓库的排他行锁保护下（调用方已锁定或本服务自取），按 relation 枚举
 * Session，逐 Session 以规范的 Session -&gt; Thread 锁序原子删除 Thread 执行事实、release Session blob ref、删
 * relation/Entry/Session，绝不误删其他 owner 的 Session。
 */
@Service
public class SessionDeletionOrchestrator {

  private final ChatSessionRepository chatSessionRepository;
  private final CanvasSessionRepository canvasSessionRepository;
  private final ChatRepository chatRepository;
  private final CanvasStore canvasStore;
  private final ProjectRepository projectRepository;
  private final IssueRepository issueRepository;
  private final IssueAgentSessionRepository issueAgentSessionRepository;
  private final IssueAgentSessionOwnershipRepository issueAgentSessionOwnershipRepository;
  private final ObjectProvider<HarnessStore> stores;
  private final SessionBlobRefManager refManager;

  public SessionDeletionOrchestrator(
      ChatSessionRepository chatSessionRepository,
      CanvasSessionRepository canvasSessionRepository,
      ChatRepository chatRepository,
      CanvasStore canvasStore,
      ProjectRepository projectRepository,
      IssueRepository issueRepository,
      IssueAgentSessionRepository issueAgentSessionRepository,
      IssueAgentSessionOwnershipRepository issueAgentSessionOwnershipRepository,
      ObjectProvider<HarnessStore> stores,
      SessionBlobRefManager refManager) {
    this.chatSessionRepository =
        Objects.requireNonNull(chatSessionRepository, "chatSessionRepository");
    this.canvasSessionRepository =
        Objects.requireNonNull(canvasSessionRepository, "canvasSessionRepository");
    this.chatRepository = Objects.requireNonNull(chatRepository, "chatRepository");
    this.canvasStore = Objects.requireNonNull(canvasStore, "canvasStore");
    this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
    this.issueRepository = Objects.requireNonNull(issueRepository, "issueRepository");
    this.issueAgentSessionRepository =
        Objects.requireNonNull(issueAgentSessionRepository, "issueAgentSessionRepository");
    this.issueAgentSessionOwnershipRepository =
        Objects.requireNonNull(
            issueAgentSessionOwnershipRepository, "issueAgentSessionOwnershipRepository");
    this.stores = Objects.requireNonNull(stores, "stores");
    this.refManager = Objects.requireNonNull(refManager, "refManager");
  }

  /**
   * 深删除某 owner 的全部 Session（含 relation 行）。owner 行以排他锁锁定，锁序 consistent 于接受路径（Owner -&gt; Session
   * -&gt; Thread），与 Harness Session 锁序一致；任何失败整体回滚。owner 行不存在视为已删，整个操作无副作用。
   */
  @Transactional
  public void deleteSessionsByOwner(OwnerRef owner) {
    Objects.requireNonNull(owner, "owner");
    if (!lockOwnerForDelete(owner)) {
      return;
    }
    UUID ownerId = owner.id();
    List<UUID> sessionIds =
        new ArrayList<>(
            switch (owner.type()) {
              case CHAT -> chatSessionRepository.listSessionIds(ownerId);
              case CANVAS -> canvasSessionRepository.listSessionIds(ownerId);
              case ISSUE_AGENT_SESSION -> issueAgentSessionOwnershipRepository.listSessionIds(
                  ownerId);
            });
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
          // 阶段 2：产品归属 relation 行（FK RESTRICT）必须先于 harness Thread/Session 行删除：
          // session_owner 行以及 project_issue_agent_session 行都持有指向 Session/Thread 的外键。
          for (UUID sessionId : presentSessions) {
            deleteRelation(owner, sessionId);
          }
          // 阶段 3：跨全部 Session 收集并按 UUID 排序 Thread，保证 THREAD rank 全局单调递增。
          deleteThreadsDeep(tx, presentSessions);
          // 阶段 4：blob ref 释放与 Entry/Session 清理（均不取 harness 锁）。
          for (UUID sessionId : presentSessions) {
            releaseSessionBlobRefs(sessionId);
            tx.deleteEntries(sessionId);
            tx.deleteSession(sessionId);
          }
          return null;
        });
  }

  /** 锁定 owner 行用于删除；owner 不存在返回 {@code false}（删除目标不存在，整体视为 noop）。 */
  private boolean lockOwnerForDelete(OwnerRef owner) {
    return switch (owner.type()) {
      case CHAT -> chatRepository.lockById(owner.id()) != null;
      case CANVAS -> canvasStore.lockDocument(owner.id()).isPresent();
      case ISSUE_AGENT_SESSION -> {
        IssueAgentSession binding = issueAgentSessionRepository.getById(owner.id());
        if (binding == null) {
          yield false;
        }
        Issue issue = issueRepository.getById(binding.getIssueId());
        if (issue == null) {
          yield false;
        }
        UUID projectId = issue.getProjectId();
        if (projectRepository.lockById(projectId) == null) {
          yield false;
        }
        if (issueRepository.lockById(issue.getId()) == null) {
          yield false;
        }
        IssueAgentSession lockedBinding =
            issueAgentSessionRepository.findByIssueIdAndAgentName(
                issue.getId(), binding.getAgentName());
        yield lockedBinding != null && lockedBinding.getId().equals(owner.id());
      }
    };
  }

  /** 深删全部目标 Session 的 Thread：先按 UUID 锁定全部 Thread，再由 Store 跨集合按 child rank 批量删除。 */
  private void deleteThreadsDeep(HarnessStore.Transaction tx, List<UUID> sessionIds) {
    List<ThreadState> threads = new ArrayList<>();
    for (UUID sessionId : sessionIds) {
      threads.addAll(tx.listThreadsBySession(sessionId));
    }
    threads.sort(Comparator.comparing(ThreadState::id, UuidOrder.COMPARATOR));
    List<UUID> threadIds = threads.stream().map(ThreadState::id).toList();
    for (ThreadState thread : threads) {
      tx.lockThread(thread.id())
          .orElseThrow(
              () -> new IllegalStateException("Thread disappeared while deleting session"));
    }
    if (!threadIds.isEmpty() && tx.deleteThreads(threadIds) != threadIds.size()) {
      throw new IllegalStateException("not all locked threads were deleted");
    }
  }

  /** release Session 级 blob 引用（ref_count 对账 + 删除 session_blob_ref 行），先于 Session 删除。 */
  private void releaseSessionBlobRefs(UUID sessionId) {
    for (UUID blobId : refManager.listBlobIds(sessionId)) {
      refManager.releaseRef(sessionId, blobId);
    }
  }

  /**
   * relation 行（FK RESTRICT）先于 Session/Thread 行删除：先删 session_owner 归属边，再删 Issue+Agent 的稳定归属行 （其
   * thread_id 指向将被删除的 harness Thread）。
   */
  private void deleteRelation(OwnerRef owner, UUID sessionId) {
    switch (owner.type()) {
      case CHAT -> chatSessionRepository.deleteBySessionId(sessionId);
      case CANVAS -> canvasSessionRepository.deleteBySessionId(sessionId);
      case ISSUE_AGENT_SESSION -> {
        issueAgentSessionOwnershipRepository.deleteBySessionId(sessionId);
        issueAgentSessionRepository.deleteById(owner.id());
      }
    }
  }
}
