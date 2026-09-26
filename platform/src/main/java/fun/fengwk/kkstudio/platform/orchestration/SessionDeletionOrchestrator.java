package fun.fengwk.kkstudio.platform.orchestration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.platform.chat.repo.ChatRepository;
import fun.fengwk.kkstudio.platform.chat.repo.ChatSessionRepository;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 产品 owner 共享的 Harness Session 深删除：owner 删除在该 owner 对应仓库的排他行锁保护下（调用方已锁定或本服务自取），按归属枚举 Session，逐
 * Session 以规范的 Session -&gt; Thread 锁序原子删除 Thread 执行事实、release Session blob ref、删归属行与
 * Entry/Session，绝不误删其他 owner 的 Session。
 *
 * <p>归属行（{@code chat_session} / {@code project_issue_agent_thread}，均为 FK RESTRICT）必须先于 harness
 * Thread/Session 行删除。已绑定归属的 Session 不提供绕过业务清理的裸删除：Run 仍引用其 Thread/归属时，删除由复合 FK RESTRICT
 * 整体拒绝，本服务不做任何 约束绕过、不删除 Run/Activity/预算等产品事实。因此 Issue 深删除的调用方必须先收尾并删除该 Issue 的 Run，再按 Agent 调用本服务。
 */
@Service
public class SessionDeletionOrchestrator {

  private final ChatSessionRepository chatSessionRepository;
  private final ChatRepository chatRepository;
  private final ProjectRepository projectRepository;
  private final IssueRepository issueRepository;
  private final IssueAgentThreadRepository issueAgentThreadRepository;
  private final ObjectProvider<HarnessStore> stores;
  private final SessionBlobRefManager refManager;

  public SessionDeletionOrchestrator(
      ChatSessionRepository chatSessionRepository,
      ChatRepository chatRepository,
      ProjectRepository projectRepository,
      IssueRepository issueRepository,
      IssueAgentThreadRepository issueAgentThreadRepository,
      ObjectProvider<HarnessStore> stores,
      SessionBlobRefManager refManager) {
    this.chatSessionRepository =
        Objects.requireNonNull(chatSessionRepository, "chatSessionRepository");
    this.chatRepository = Objects.requireNonNull(chatRepository, "chatRepository");
    this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
    this.issueRepository = Objects.requireNonNull(issueRepository, "issueRepository");
    this.issueAgentThreadRepository =
        Objects.requireNonNull(issueAgentThreadRepository, "issueAgentThreadRepository");
    this.stores = Objects.requireNonNull(stores, "stores");
    this.refManager = Objects.requireNonNull(refManager, "refManager");
  }

  /**
   * 深删除某 owner 的全部 Session（含归属行）。owner 行以排他锁锁定，锁序 consistent 于接受路径（Owner -&gt; Session -&gt;
   * Thread），与 Harness Session 锁序一致；任何失败整体回滚。owner 行不存在视为已删，整个操作无副作用。
   */
  @Transactional
  public void deleteSessionsByOwner(OwnerRef owner) {
    Objects.requireNonNull(owner, "owner");
    List<UUID> sessionIds = new ArrayList<>(lockOwnerAndListSessionIds(owner));
    sessionIds.sort(UuidOrder.COMPARATOR);
    if (sessionIds.isEmpty()) {
      return;
    }
    HarnessStore store = requireStore();
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
          // 阶段 2：产品归属行（FK RESTRICT）必须先于 harness Thread/Session 行删除：chat_session 行与
          // project_issue_agent_thread 行分别持有指向 Session / Thread 的外键。
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

  /** 排他锁定 owner 行并枚举其 Session；owner 不存在（或 Issue+Agent 尚未绑定）返回空列表表示删除目标不存在。 */
  private List<UUID> lockOwnerAndListSessionIds(OwnerRef owner) {
    return switch (owner) {
      case OwnerRef.Chat chat -> {
        if (chatRepository.lockById(chat.chatId()) == null) {
          yield List.of();
        }
        yield chatSessionRepository.listSessionIds(chat.chatId());
      }
      case OwnerRef.IssueAgent issueAgent -> lockIssueAgentAndListSessionIds(issueAgent);
    };
  }

  /** Issue+Agent 深删除的锁序与接受路径一致：Project 排他 -&gt; Issue 排他 -&gt; 读稳定绑定。绑定行缺失即无归属可删。 */
  private List<UUID> lockIssueAgentAndListSessionIds(OwnerRef.IssueAgent owner) {
    Issue issue = issueRepository.getById(owner.issueId());
    if (issue == null) {
      return List.of();
    }
    if (projectRepository.lockById(issue.getProjectId()) == null) {
      return List.of();
    }
    if (issueRepository.lockById(owner.issueId()) == null) {
      return List.of();
    }
    IssueAgentThread binding =
        issueAgentThreadRepository.findByIssueIdAndAgentName(owner.issueId(), owner.agentName());
    if (binding == null) {
      return List.of();
    }
    return List.of(boundThreadSessionId(binding));
  }

  /** 由稳定绑定解析其 Thread 的 Session；Thread 不可解析说明归属事实已不一致，失败而不是静默跳过删除。 */
  private UUID boundThreadSessionId(IssueAgentThread binding) {
    return requireStore()
        .transaction(tx -> tx.findThread(binding.threadId()).map(ThreadState::sessionId))
        .orElseThrow(
            () -> new IllegalStateException("Issue agent thread disappeared before deletion"));
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
   * 归属行（FK RESTRICT）先于 Session/Thread 行删除：Chat 删 {@code chat_session} 边；Issue+Agent 删稳定 Thread 绑定（其
   * thread_id 指向将被删除的 harness Thread）。
   */
  private void deleteRelation(OwnerRef owner, UUID sessionId) {
    switch (owner) {
      case OwnerRef.Chat chat -> chatSessionRepository.deleteBySessionId(sessionId);
      case OwnerRef.IssueAgent issueAgent -> issueAgentThreadRepository.deleteByIssueIdAndAgentName(
          issueAgent.issueId(), issueAgent.agentName());
    }
  }

  private HarnessStore requireStore() {
    HarnessStore store = stores.getIfAvailable();
    if (store == null) {
      throw new IllegalStateException(
          "harness store is not available; cannot deep delete owner sessions");
    }
    return store;
  }
}
