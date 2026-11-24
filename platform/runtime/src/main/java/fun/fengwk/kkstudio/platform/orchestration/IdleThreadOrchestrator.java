package fun.fengwk.kkstudio.platform.orchestration;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;

import java.util.UUID;

/** Coordinates idle thread forks with Session-level blob resource retention. */
@Service
public class IdleThreadOrchestrator {

  private final HarnessRuntime runtime;
  private final SessionBlobRefManager refManager;

  public IdleThreadOrchestrator(HarnessRuntime runtime, SessionBlobRefManager refManager) {
    this.runtime = runtime;
    this.refManager = refManager;
  }

  @Transactional
  public ThreadState createIdleFork(
      UUID sourceThreadId, UUID turnEndEntryId, UUID sessionId, UUID threadId, String sessionName) {
    return runtime.createIdleFork(
        sourceThreadId,
        turnEndEntryId,
        sessionId,
        threadId,
        sessionName,
        (targetSessionId, copiedEntries) ->
            SessionEntryResources.retain(refManager, targetSessionId, copiedEntries));
  }
}
