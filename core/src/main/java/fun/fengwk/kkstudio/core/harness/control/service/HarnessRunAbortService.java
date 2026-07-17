package fun.fengwk.kkstudio.core.harness.control.service;

import static java.util.Objects.requireNonNull;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.harness.run.store.HarnessRunEventWriter;
import fun.fengwk.kkstudio.core.harness.run.store.mapper.HarnessRunMapper;
import fun.fengwk.kkstudio.core.harness.run.store.model.HarnessRunDO;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
import fun.fengwk.kkstudio.core.harness.task.store.mapper.HarnessSubagentTaskMapper;
import fun.fengwk.kkstudio.core.harness.tool.store.mapper.ToolInvocationMapper;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlMessageStore;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventDraft;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventPayloads;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventType;
import fun.fengwk.kkstudio.harness.runtime.run.RunStatus;
import fun.fengwk.kkstudio.harness.runtime.task.TaskRuntime;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * 持久化 Session 级 abort 命令。数据库是取消事实源；本服务只请求取消并传播，不直接终结非终态 Run。active path 固定按 Run -> Session -> Root
 * 加锁，no-active path 按 Session -> Root 加锁，任意传播失败均整体回滚。
 */
@Service
public class HarnessRunAbortService {

  private final HarnessSessionMapper sessionMapper;
  private final HarnessRunMapper runMapper;
  private final ToolInvocationMapper invocationMapper;
  private final HarnessSubagentTaskMapper taskMapper;
  private final RunControlMessageStore controlStore;
  private final HarnessRunEventWriter eventWriter;
  private final TaskRuntime taskRuntime;

  public HarnessRunAbortService(
      HarnessSessionMapper sessionMapper,
      HarnessRunMapper runMapper,
      ToolInvocationMapper invocationMapper,
      HarnessSubagentTaskMapper taskMapper,
      RunControlMessageStore controlStore,
      HarnessRunEventWriter eventWriter,
      TaskRuntime taskRuntime) {
    this.sessionMapper = requireNonNull(sessionMapper, "sessionMapper");
    this.runMapper = requireNonNull(runMapper, "runMapper");
    this.invocationMapper = requireNonNull(invocationMapper, "invocationMapper");
    this.taskMapper = requireNonNull(taskMapper, "taskMapper");
    this.controlStore = requireNonNull(controlStore, "controlStore");
    this.eventWriter = requireNonNull(eventWriter, "eventWriter");
    this.taskRuntime = requireNonNull(taskRuntime, "taskRuntime");
  }

  /** Abort 结果。新请求 abort 写事件，重复请求不写。runId 可空表示当前 session 无 active run。 */
  public record AbortResult(
      long sessionId, Long runId, boolean newlyRequested, RunStatus status, Instant requestedAt) {}

  @Transactional
  public AbortResult abort(long sessionId, Instant now) {
    Instant timestamp = requireNonNull(now, "now").truncatedTo(ChronoUnit.MILLIS);

    HarnessSessionDO hint = sessionMapper.find(sessionId);
    if (hint == null) {
      throw new IllegalArgumentException("unknown session: " + sessionId);
    }
    Long activeHint = hint.getActiveRunId();

    if (activeHint == null) {
      HarnessSessionDO lockedSession = requireSessionForUpdate(sessionId);
      eventWriter.lockRoot(lockedSession);
      if (lockedSession.getActiveRunId() != null) {
        throw new RunAbortConflictException(
            "session active run changed under lock, abort rejected for session " + sessionId);
      }
      controlStore.clearPendingBySession(sessionId, timestamp);
      return new AbortResult(sessionId, null, false, null, null);
    }

    HarnessRunDO lockedRun = runMapper.findForUpdate(activeHint);
    if (lockedRun != null && lockedRun.getSessionId() != sessionId) {
      throw new IllegalStateException("active run does not belong to session: " + activeHint);
    }
    HarnessSessionDO lockedSession = eventWriter.lockSessionAndRoot(sessionId);

    if (lockedRun == null) {
      Long lockedActive = lockedSession.getActiveRunId();
      if (lockedActive != null && !activeHint.equals(lockedActive)) {
        throw new RunAbortConflictException(
            "session active run changed under lock, abort rejected for session " + sessionId);
      }
      if (Objects.equals(lockedActive, activeHint)
          && sessionMapper.clearActiveRun(sessionId, activeHint, utc(timestamp)) != 1) {
        throw new IllegalStateException(
            "cannot clear session active run during stale pointer repair: " + sessionId);
      }
      controlStore.clearPendingBySession(sessionId, timestamp);
      return new AbortResult(sessionId, activeHint, false, null, null);
    }

    if (!Objects.equals(lockedSession.getActiveRunId(), activeHint)) {
      throw new RunAbortConflictException(
          "session active run changed under lock, abort rejected for session "
              + sessionId
              + " from "
              + activeHint
              + " to "
              + lockedSession.getActiveRunId());
    }

    RunStatus runStatus = RunStatus.valueOf(lockedRun.getStatus());
    if (runStatus.terminal()) {
      if (sessionMapper.clearActiveRun(sessionId, activeHint, utc(timestamp)) != 1) {
        throw new IllegalStateException(
            "cannot clear session active run during terminal stale pointer repair: " + sessionId);
      }
      controlStore.clearPendingBySession(sessionId, timestamp);
      Instant existingRequested = instant(lockedRun.getCancelRequestedAt());
      return new AbortResult(sessionId, activeHint, false, runStatus, existingRequested);
    }

    Instant existingRequested = instant(lockedRun.getCancelRequestedAt());
    Instant localRequestedAt = existingRequested != null ? existingRequested : timestamp;
    boolean newlyRequested = runMapper.requestCancel(activeHint, utc(timestamp)) == 1;
    if (!newlyRequested && existingRequested == null) {
      throw new IllegalStateException("cannot request run cancellation: " + activeHint);
    }
    if (newlyRequested) {
      eventWriter.appendLocked(
          lockedRun,
          List.of(
              new RunEventDraft(
                  RunEventType.ABORT_REQUESTED,
                  RunEventPayloads.of(
                      "sessionId",
                      sessionId,
                      "runId",
                      activeHint,
                      "status",
                      runStatus.name(),
                      "requestedAt",
                      localRequestedAt))),
          timestamp);
    }

    controlStore.clearPendingBySession(sessionId, timestamp);
    List<Long> taskInvocations = taskMapper.listTaskInvocationIdsByParentRun(activeHint);
    for (Long parentInvocationId : taskInvocations) {
      taskRuntime.cancelTree(parentInvocationId, timestamp);
    }
    invocationMapper.requestCancelByRun(activeHint, utc(timestamp));

    return new AbortResult(sessionId, activeHint, newlyRequested, runStatus, localRequestedAt);
  }

  private HarnessSessionDO requireSessionForUpdate(long sessionId) {
    HarnessSessionDO session = sessionMapper.findForUpdate(sessionId);
    if (session == null) {
      throw new IllegalStateException("session disappeared while locking: " + sessionId);
    }
    return session;
  }

  private static LocalDateTime utc(Instant value) {
    return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
  }

  private static Instant instant(LocalDateTime value) {
    return value == null ? null : value.toInstant(ZoneOffset.UTC);
  }
}
