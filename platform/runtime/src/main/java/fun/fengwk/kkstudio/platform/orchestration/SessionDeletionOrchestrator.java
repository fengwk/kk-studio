package fun.fengwk.kkstudio.platform.orchestration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.storage.persistence.SessionFileRepository;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;

import java.util.List;
import java.util.Objects;
import java.util.Set;
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

  private final List<SessionOwnership> ownerships;
  private final ObjectProvider<HarnessRuntime> runtimes;
  private final SessionBlobRefManager refManager;
  private final SessionFileRepository sessionFileRepository;

  public SessionDeletionOrchestrator(
      List<SessionOwnership> ownerships,
      ObjectProvider<HarnessRuntime> runtimes,
      SessionBlobRefManager refManager,
      SessionFileRepository sessionFileRepository) {
    this.ownerships = List.copyOf(ownerships);
    this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
    this.refManager = Objects.requireNonNull(refManager, "refManager");
    this.sessionFileRepository =
        Objects.requireNonNull(sessionFileRepository, "sessionFileRepository");
  }

  /**
   * 深删除某 owner 的全部 Session（含归属行）。owner 行以排他锁锁定，锁序 consistent 于接受路径（Owner -&gt; Tree -&gt; Session
   * -&gt; Thread）；任何失败整体回滚。owner 行不存在视为已删，整个操作无副作用。
   */
  @Transactional
  public void deleteSessionsByOwner(SessionOwner owner) {
    Objects.requireNonNull(owner, "owner");
    List<SessionOwnership> matches =
        ownerships.stream().filter(ownership -> owner.kind().equals(ownership.kind())).toList();
    if (matches.size() != 1) {
      throw new IllegalArgumentException(
          "Unknown or ambiguous session owner kind: " + owner.kind());
    }
    SessionOwnership ownership = matches.getFirst();
    List<UUID> sessionIds = ownership.lockForDeletion(owner);
    if (sessionIds.isEmpty()) {
      return;
    }
    Set<UUID> ownedSessionIds = Set.copyOf(sessionIds);
    // Runtime 的 store 事务加入本应用事务，blob release 要求已有事务。
    requireRuntime()
        .deleteSessions(
            sessionIds,
            ids ->
                ids.stream()
                    .filter(ownedSessionIds::contains)
                    .forEach(
                        id -> {
                          // session_file 以 FK RESTRICT 指向 harness_session：行必须先于 Session 删除；
                          // Blob 引用由既有的 session_blob_ref 释放路径统一处理。
                          sessionFileRepository.deleteBySession(id);
                          ownership.beforeSessionDeletion(owner, id);
                        }),
            ids -> ids.forEach(this::releaseSessionBlobRefs));
  }

  /** release Session 级 blob 引用（ref_count 对账 + 删除 session_blob_ref 行），先于 Session 删除。 */
  private void releaseSessionBlobRefs(UUID sessionId) {
    for (UUID blobId : refManager.listBlobIds(sessionId)) {
      refManager.releaseRef(sessionId, blobId);
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
}
