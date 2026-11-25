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

/** Harness 执行树与 Session 深删除协调器：在单一事务内删除 Thread 执行事实与 join 回执，并释放 Session blob 引用。 */
@Service
public class SessionDeletionOrchestrator {

  private final ObjectProvider<HarnessRuntime> runtimes;
  private final SessionBlobRefManager refManager;

  public SessionDeletionOrchestrator(
      ObjectProvider<HarnessRuntime> runtimes, SessionBlobRefManager refManager) {
    this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
    this.refManager = Objects.requireNonNull(refManager, "refManager");
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
