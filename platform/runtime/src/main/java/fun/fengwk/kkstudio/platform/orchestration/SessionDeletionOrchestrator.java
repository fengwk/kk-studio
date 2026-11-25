package fun.fengwk.kkstudio.platform.orchestration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 产品 owner 共享的 Harness Session 深删除：owner 删除在该 owner 对应仓库的排他行锁保护下（调用方已锁定或本服务自取），按归属枚举 Session，以
 * owner -&gt; tree -&gt; Session -&gt; Thread 的规范锁序原子删除 Thread 执行事实与 join 回执、release Session blob
 * ref、删归属行与 Entry/Session，绝不误删其他 owner 的 Session。
 *
 * <p>归属行（如 {@code chat_session}）须在删除 Harness Session 前清理；仍有业务引用时由外键拒绝并整体回滚。
 */
@Service
public class SessionDeletionOrchestrator {

  private final List<SessionOwnership> ownerships;
  private final ObjectProvider<HarnessRuntime> runtimes;
  private final SessionBlobRefManager refManager;

  public SessionDeletionOrchestrator(
      List<SessionOwnership> ownerships,
      ObjectProvider<HarnessRuntime> runtimes,
      SessionBlobRefManager refManager) {
    this.ownerships = List.copyOf(ownerships);
    this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
    this.refManager = Objects.requireNonNull(refManager, "refManager");
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
                    .forEach(id -> ownership.beforeSessionDeletion(owner, id)),
            ids -> ids.forEach(this::releaseSessionBlobRefs));
  }

  /** 深删已结算的公开执行根；调用方负责产品归属和定时目标门禁。 */
  @Transactional
  public void deleteThread(UUID rootThreadId) {
    deleteThread(rootThreadId, null);
  }

  @Transactional
  public void deleteThread(UUID rootThreadId, Long expectedVersion) {
    requireRuntime()
        .deleteThread(
            rootThreadId,
            expectedVersion,
            (sessionId, removed, retained) -> {
              Set<UUID> live = Set.copyOf(SessionEntryResources.blobIds(retained));
              Set<UUID> held = Set.copyOf(refManager.listBlobIds(sessionId));
              for (UUID blobId : SessionEntryResources.blobIds(removed)) {
                if (!live.contains(blobId) && held.contains(blobId)) {
                  refManager.releaseRef(sessionId, blobId);
                }
              }
            });
  }

  @Transactional
  public void deleteSession(UUID sessionId, Consumer<List<UUID>> removePlacement) {
    requireRuntime()
        .deleteSessions(
            List.of(sessionId), removePlacement, ids -> ids.forEach(this::releaseSessionBlobRefs));
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
