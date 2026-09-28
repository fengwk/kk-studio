package fun.fengwk.kkstudio.platform.orchestration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.platform.chat.repo.ChatRepository;
import fun.fengwk.kkstudio.platform.chat.repo.ChatSessionRepository;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 产品 owner 共享的 Harness Session 深删除：owner 删除在该 owner 对应仓库的排他行锁保护下（调用方已锁定或本服务自取），按归属枚举 Session，以
 * owner -&gt; tree -&gt; Session -&gt; Thread 的规范锁序原子删除 Thread 执行事实与 join 回执、release Session blob
 * ref、删归属行与 Entry/Session，绝不误删其他 owner 的 Session。
 *
 * <p>归属行（{@code chat_session} / {@code project_issue_agent_thread}，均为 FK RESTRICT）必须先于 harness
 * Thread/Session 行删除。已绑定归属的 Session 不提供绕过业务清理的裸删除：Run 仍引用其 Thread/归属时，删除由复合 FK RESTRICT
 * 整体拒绝，本服务不做任何约束绕过、不删除 Run/Activity/预算等产品事实。因此 Issue 深删除的调用方必须先收尾并删除该 Issue 的 Run，再按 Agent 调用本服务。
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
   * 深删除某 owner 的全部 Session（含归属行）。owner 行以排他锁锁定，锁序 consistent 于接受路径（Owner -&gt; Tree -&gt; Session
   * -&gt; Thread）；任何失败整体回滚。owner 行不存在视为已删，整个操作无副作用。
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
          // 先算闭包后取锁：闭包以「Session 的全部 Thread」为单位向下推进，共享 Session 内别的入口建立的 root fork 同样在删除集合内。
          DeletionScope scope = discoverScope(tx, sessionIds);
          // 锁序与 accept 路径一致（执行树 root -> Session -> Thread）；并发加入子树由树锁排除，加锁后复核闭包只增不减。
          for (UUID root : scope.roots()) {
            tx.lockTree(root);
          }
          // 多 Session 深删若逐个取 SESSION 锁，第二个 Session 开始会因曾取过 THREAD 锁而违反 SESSION -> THREAD 单调锁序。
          List<UUID> presentSessions = new ArrayList<>(scope.sessions().size());
          for (UUID sessionId : scope.sessions()) {
            if (tx.lockSessionForUpdate(sessionId).isPresent()) {
              presentSessions.add(sessionId);
            }
          }
          DeletionScope confirmed = discoverScope(tx, presentSessions);
          if (!scope.sessions().containsAll(confirmed.sessions())
              || !scope.roots().containsAll(confirmed.roots())) {
            throw new IllegalStateException(
                "harness execution tree changed while acquiring its deletion locks");
          }
          scope = confirmed;
          // 产品归属行（FK RESTRICT）必须先于 harness Thread/Session 行删除：chat_session 行与
          // project_issue_agent_thread 行分别持有指向 Session / Thread 的外键。
          for (UUID sessionId : presentSessions) {
            deleteRelation(owner, sessionId);
          }
          deleteThreadsDeep(tx, scope.threads());
          // blob ref 释放与 Entry/Session 清理（均不取 harness 锁）。
          for (UUID sessionId : scope.sessions()) {
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

  /** 删除闭包内全部 Thread：按 UUID 升序锁定，拒绝单边删除（父或子存活即闭包被截断），再批量删 join 与执行事实。 */
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
    if (ordered.isEmpty()) {
      return;
    }
    // 两端都在删除集合内，因此未匹配/未交付的 pending join 也一并删除；join 引用源 Command 与结果 Entry，必须先于二者删除。
    tx.deleteJoinsForThreads(ordered);
    if (tx.deleteThreads(ordered) != ordered.size()) {
      throw new IllegalStateException("not all locked threads were deleted");
    }
  }

  /** 一次 owner 深删的闭包：目标 Session（升序）、全部待删 Thread（含无父 root fork）与其执行树 root（升序）。 */
  private record DeletionScope(List<UUID> sessions, Set<UUID> threads, List<UUID> roots) {}

  /**
   * 计算 Session/Thread 删除闭包：从 owner 归属的 Session 出发，逐 Session 取其全部 Thread，再沿永久孩子关系把后代 Session 并入。
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
