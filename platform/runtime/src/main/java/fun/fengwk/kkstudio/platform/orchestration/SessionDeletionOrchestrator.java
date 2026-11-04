package fun.fengwk.kkstudio.platform.orchestration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.chat.ChatRepository;
import fun.fengwk.kkstudio.platform.chat.ChatSessionRepository;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;

import java.util.List;
import java.util.Objects;
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
  private final ObjectProvider<IssueAgentOwnership> issueAgentOwnership;
  private final ObjectProvider<HarnessRuntime> runtimes;
  private final SessionBlobRefManager refManager;

  public SessionDeletionOrchestrator(
      ChatSessionRepository chatSessionRepository,
      ChatRepository chatRepository,
      ObjectProvider<IssueAgentOwnership> issueAgentOwnership,
      ObjectProvider<HarnessRuntime> runtimes,
      SessionBlobRefManager refManager) {
    this.chatSessionRepository =
        Objects.requireNonNull(chatSessionRepository, "chatSessionRepository");
    this.chatRepository = Objects.requireNonNull(chatRepository, "chatRepository");
    this.issueAgentOwnership = Objects.requireNonNull(issueAgentOwnership, "issueAgentOwnership");
    this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
    this.refManager = Objects.requireNonNull(refManager, "refManager");
  }

  /**
   * 深删除某 owner 的全部 Session（含归属行）。owner 行以排他锁锁定，锁序 consistent 于接受路径（Owner -&gt; Tree -&gt; Session
   * -&gt; Thread）；任何失败整体回滚。owner 行不存在视为已删，整个操作无副作用。
   */
  @Transactional
  public void deleteSessionsByOwner(OwnerRef owner) {
    Objects.requireNonNull(owner, "owner");
    List<UUID> sessionIds = lockOwnerAndListSessionIds(owner);
    if (sessionIds.isEmpty()) {
      return;
    }
    // Runtime 的 store 事务加入本应用事务，blob release 要求已有事务。
    requireRuntime()
        .deleteSessions(
            sessionIds,
            ids -> ids.forEach(id -> deleteRelation(owner, id)),
            ids -> ids.forEach(this::releaseSessionBlobRefs));
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
        // Issue+Agent 的锁序与接受路径一致（Project 排他 -&gt; Issue 排他 -&gt; 读稳定绑定）；绑定缺失即无归属可删。
      case OwnerRef.IssueAgent issueAgent -> requireIssueAgentOwnership()
          .lockForDeletion(issueAgent)
          .map(threadId -> List.of(boundThreadSessionId(threadId)))
          .orElse(List.of());
    };
  }

  /** 由稳定绑定解析其 Thread 的 Session：绑定行持有指向 Thread 的外键且归属已被排他锁定，Thread 必然存在。 */
  private UUID boundThreadSessionId(UUID threadId) {
    return requireRuntime().findThreadSessionId(threadId).orElseThrow();
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
      case OwnerRef.IssueAgent issueAgent -> requireIssueAgentOwnership().deleteBinding(issueAgent);
    }
  }

  private HarnessRuntime requireRuntime() {
    HarnessRuntime runtime = runtimes.getIfAvailable();
    if (runtime == null) {
      throw new IllegalStateException(
          "harness runtime is not available; cannot deep delete owner sessions");
    }
    return runtime;
  }

  private IssueAgentOwnership requireIssueAgentOwnership() {
    IssueAgentOwnership ownership = issueAgentOwnership.getIfAvailable();
    if (ownership == null) {
      throw new IllegalStateException("Issue agent owners require the Project application");
    }
    return ownership;
  }
}
