package fun.fengwk.kkstudio.core.harness.tool.service;

import fun.fengwk.kkstudio.core.harness.run.service.HarnessRunTransactionService;
import fun.fengwk.kkstudio.core.harness.run.store.mapper.HarnessRunMapper;
import fun.fengwk.kkstudio.core.harness.run.store.model.HarnessRunDO;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventDraft;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventPayloads;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventType;
import fun.fengwk.kkstudio.harness.runtime.run.RunStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ConcurrentModificationException;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Workspace-scoped Root Session YOLO 显式 set；Child 始终动态读取 Root。 */
@Service
public class HarnessSessionYoloService {
  private final HarnessSessionMapper sessionMapper;
  private final HarnessRunMapper runMapper;
  private final HarnessRunTransactionService runTransactions;
  private final Clock clock;

  public HarnessSessionYoloService(
      HarnessSessionMapper sessionMapper,
      HarnessRunMapper runMapper,
      HarnessRunTransactionService runTransactions,
      Clock harnessRunClock) {
    this.sessionMapper = Objects.requireNonNull(sessionMapper, "sessionMapper");
    this.runMapper = Objects.requireNonNull(runMapper, "runMapper");
    this.runTransactions = Objects.requireNonNull(runTransactions, "runTransactions");
    this.clock = Objects.requireNonNull(harnessRunClock, "harnessRunClock");
  }

  @Transactional
  public YoloState set(long workspaceId, long sessionId, boolean enabled) {
    HarnessSessionDO initialRequested = requireInWorkspace(workspaceId, sessionId, false);
    HarnessSessionDO initialRoot =
        initialRequested.getRootSessionId().equals(initialRequested.getId())
            ? initialRequested
            : requireInWorkspace(workspaceId, initialRequested.getRootSessionId(), false);
    validateRoot(initialRoot);
    Long directRunId = activeRunId(initialRequested, initialRoot);
    Long candidateRunId =
        directRunId != null
            ? directRunId
            : sessionMapper.findAnyActiveRunIdByRoot(workspaceId, initialRoot.getId());
    if (candidateRunId != null) {
      HarnessRunDO candidateRun = runMapper.findForUpdate(candidateRunId);
      if (candidateRun == null) {
        throw new IllegalStateException("active run does not exist: " + candidateRunId);
      }
      if (RunStatus.valueOf(candidateRun.getStatus()).terminal()) {
        throw new IllegalStateException("session points to terminal run: " + candidateRunId);
      }
      if (directRunId != null) {
        long candidateSessionId =
            initialRequested.getActiveRunId() != null
                ? initialRequested.getId()
                : initialRoot.getId();
        if (!Objects.equals(candidateRun.getSessionId(), candidateSessionId)) {
          throw new IllegalStateException(
              "active run does not belong to session: " + candidateRunId);
        }
      } else {
        HarnessSessionDO candidateSession =
            requireInWorkspace(workspaceId, candidateRun.getSessionId());
        if (!Objects.equals(candidateSession.getActiveRunId(), candidateRunId)) {
          throw new ConcurrentModificationException(
              "root session activity changed while setting yolo");
        }
        if (!Objects.equals(candidateSession.getRootSessionId(), initialRoot.getId())) {
          throw new IllegalStateException(
              "active run does not belong to root session: " + candidateRunId);
        }
      }
    }

    HarnessSessionDO requested = requireInWorkspace(workspaceId, sessionId);
    HarnessSessionDO root =
        requested.getRootSessionId().equals(requested.getId())
            ? requested
            : requireInWorkspace(workspaceId, requested.getRootSessionId());
    validateRoot(root);
    if (!Objects.equals(directRunId, activeRunId(requested, root))) {
      throw new ConcurrentModificationException("session activity changed while setting yolo");
    }
    boolean current = Boolean.TRUE.equals(root.getYoloEnabled());
    Instant now = clock.instant();
    if (current != enabled) {
      if (sessionMapper.setRootYolo(workspaceId, root.getId(), enabled, utc(now)) != 1) {
        throw new IllegalStateException("cannot update root session yolo");
      }
      if (candidateRunId != null) {
        runTransactions.appendExternalEvent(
            candidateRunId,
            new RunEventDraft(
                RunEventType.RUNTIME_STATE_CHANGED,
                RunEventPayloads.of("rootSessionId", root.getId(), "yoloEnabled", enabled)),
            now);
      }
    }
    return new YoloState(sessionId, root.getId(), enabled);
  }

  @Transactional(readOnly = true)
  public YoloState get(long workspaceId, long sessionId) {
    HarnessSessionDO requested = requireInWorkspace(workspaceId, sessionId, false);
    HarnessSessionDO root =
        requested.getRootSessionId().equals(requested.getId())
            ? requested
            : requireInWorkspace(workspaceId, requested.getRootSessionId(), false);
    validateRoot(root);
    return new YoloState(sessionId, root.getId(), Boolean.TRUE.equals(root.getYoloEnabled()));
  }

  private HarnessSessionDO requireInWorkspace(long workspaceId, long sessionId) {
    return requireInWorkspace(workspaceId, sessionId, true);
  }

  private HarnessSessionDO requireInWorkspace(long workspaceId, long sessionId, boolean forUpdate) {
    HarnessSessionDO session =
        forUpdate ? sessionMapper.findForUpdate(sessionId) : sessionMapper.find(sessionId);
    if (session == null || !session.getWorkspaceId().equals(workspaceId)) {
      throw new IllegalArgumentException("session not found in workspace");
    }
    return session;
  }

  private static void validateRoot(HarnessSessionDO root) {
    if (root.getParentSessionId() != null
        || !Objects.equals(root.getRootSessionId(), root.getId())) {
      throw new IllegalStateException("invalid root session: " + root.getId());
    }
  }

  private static Long activeRunId(HarnessSessionDO requested, HarnessSessionDO root) {
    return requested.getActiveRunId() != null ? requested.getActiveRunId() : root.getActiveRunId();
  }

  private static LocalDateTime utc(Instant value) {
    return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
  }

  public record YoloState(long sessionId, long rootSessionId, boolean enabled) {}
}
