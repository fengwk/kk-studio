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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
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
          // 阶段 1（无锁）：先算出删除闭包，再据此取锁。闭包以「Session 的全部 Thread」为单位向下推进，因此
          // 共享 Session 内由别的入口建立的无父 root fork 及其后代同样进入删除集合，不会被漏掉。
          DeletionScope scope = discoverScope(tx, sessionIds);
          // 阶段 2：树锁必须早于 Session/Thread 锁；并发加入子树时按规范顺序先锁全部执行树 root。
          for (UUID root : scope.roots()) {
            tx.lockTree(root);
          }
          // 阶段 3：以规范锁序锁定全部目标 Session（SESSION rank）。多 Session 深删若逐个取 SESSION 锁，
          // 第二 Session 开始会因曾取过 THREAD 锁而违反 SESSION -> THREAD 单调锁序。
          List<UUID> presentSessions = new ArrayList<>(scope.sessions().size());
          for (UUID sessionId : scope.sessions()) {
            if (tx.lockSessionForUpdate(sessionId).isPresent()) {
              presentSessions.add(sessionId);
            }
          }
          // 阶段 4：锁内复核。持有 Session FOR UPDATE 后同 Session 的 fork 创建已被排除；若闭包在此前变大
          // （新 fork/新 Session 取了未锁的树、或产生未锁 root），则整体回滚交由调用方重试，绝不删除漏后代的集合。
          DeletionScope confirmed = discoverScope(tx, presentSessions);
          if (!scope.sessions().containsAll(confirmed.sessions())
              || !scope.roots().containsAll(confirmed.roots())) {
            throw new IllegalStateException(
                "harness execution tree changed while acquiring its deletion locks");
          }
          scope = confirmed;
          // 阶段 5：产品归属 relation 行（FK RESTRICT）必须先于 harness Thread/Session 行删除：
          // session_owner 行以及 project_issue_agent_session 行都持有指向 Session/Thread 的外键。
          for (UUID sessionId : presentSessions) {
            deleteRelation(owner, sessionId);
          }
          // 阶段 6：闭包内全部 Thread 一起删除，保证 THREAD rank 全局单调递增。
          deleteThreadsDeep(tx, scope.threads());
          // 阶段 7：blob ref 释放与 Entry/Session 清理（均不取 harness 锁）。
          for (UUID sessionId : scope.sessions()) {
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

  /**
   * 删除闭包内全部 Thread：先按 UUID 升序锁定全部 Thread，再校验执行两端都在删除集合内，最后删 join 与执行事实。
   *
   * <p>仍拒绝单边删除：任一 Thread 的父（或子）不在删除集合内都说明闭包被截断，必须整体失败而不是留下悬挂的 join 引用。
   */
  private void deleteThreadsDeep(HarnessStore.Transaction tx, Set<UUID> threadIds) {
    List<UUID> ordered = threadIds.stream().sorted(UuidOrder.COMPARATOR).toList();
    for (UUID threadId : ordered) {
      tx.lockThread(threadId)
          .orElseThrow(
              () -> new IllegalStateException("Thread disappeared while deleting session"));
    }
    for (UUID threadId : ordered) {
      ThreadState thread =
          tx.findThread(threadId)
              .orElseThrow(
                  () -> new IllegalStateException("Thread disappeared while deleting session"));
      if (thread.parentThreadId() != null && !threadIds.contains(thread.parentThreadId())) {
        throw new IllegalStateException(
            "cannot delete a child thread while its execution parent survives");
      }
      if (tx.listChildren(threadId).stream().anyMatch(child -> !threadIds.contains(child.id()))) {
        throw new IllegalStateException(
            "cannot delete a parent thread while its execution children survive");
      }
    }
    deleteJoins(tx, ordered);
    if (!ordered.isEmpty() && tx.deleteThreads(ordered) != ordered.size()) {
      throw new IllegalStateException("not all locked threads were deleted");
    }
  }

  /**
   * 删除闭包内全部 join：join 固定回执引用源 Command 与结果 Entry，必须在删除命令/历史之前移除。
   *
   * <p>闭包已保证父子两端都在删除集合内，因此逐 child 删除即为完整删除。
   */
  private static void deleteJoins(HarnessStore.Transaction tx, List<UUID> threadIds) {
    for (UUID threadId : threadIds) {
      tx.deleteJoinsByChild(threadId);
    }
  }

  /** 一次 owner 深删的闭包：目标 Session（升序）、全部待删 Thread（含无父 root fork）与其执行树 root（升序）。 */
  private record DeletionScope(List<UUID> sessions, Set<UUID> threads, List<UUID> roots) {}

  /**
   * 计算 Session/Thread 删除闭包：从 owner relation 的 Session 出发，逐 Session 取其全部 Thread，再沿永久孩子关系把后代 Session
   * 并入。
   *
   * <p>以 Session 为单位而不是以 Thread 为单位推进，是为了覆盖「task 子 Session 中另建的无父 root fork」这类没有父边可循的线程；整个闭包在
   * 取锁前后各算一次，用于 fail-safe 复核。
   */
  private static DeletionScope discoverScope(
      HarnessStore.Transaction tx, List<UUID> initialSessions) {
    Set<UUID> sessions = new TreeSet<>(UuidOrder.COMPARATOR);
    Set<UUID> threads = new HashSet<>();
    List<UUID> pending = new ArrayList<>(initialSessions);
    for (int index = 0; index < pending.size(); index++) {
      UUID sessionId = pending.get(index);
      if (!sessions.add(sessionId)) {
        continue;
      }
      for (ThreadState thread : tx.listThreadsBySession(sessionId)) {
        threads.add(thread.id());
        for (ThreadState child : tx.listChildren(thread.id())) {
          if (!sessions.contains(child.sessionId())) {
            pending.add(child.sessionId());
          }
        }
      }
    }
    List<UUID> roots =
        threads.stream()
            .map(tx::findAncestorChain)
            .filter(chain -> !chain.isEmpty())
            .map(chain -> chain.get(chain.size() - 1))
            .distinct()
            .sorted(UuidOrder.COMPARATOR)
            .toList();
    return new DeletionScope(List.copyOf(sessions), Set.copyOf(threads), roots);
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
