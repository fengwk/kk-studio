package fun.fengwk.kkstudio.core.harness.tool.worker;

import fun.fengwk.kkstudio.core.harness.run.store.HarnessRunEventWriter;
import fun.fengwk.kkstudio.core.harness.run.store.mapper.HarnessRunMapper;
import fun.fengwk.kkstudio.core.harness.run.store.model.HarnessRunDO;
import fun.fengwk.kkstudio.core.harness.tool.store.MysqlToolInvocationStore;
import fun.fengwk.kkstudio.core.harness.tool.store.mapper.ToolInvocationMapper;
import fun.fengwk.kkstudio.core.harness.tool.store.model.ToolInvocationDO;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventDraft;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventPayloads;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventType;
import fun.fengwk.kkstudio.harness.runtime.run.RunStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ClaimedToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolInvocationTransactions;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolResultJsonCodec;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomically journals Tool state changes; terminal materialization is per-Run coordinated. */
@Slf4j
@Service
public class ToolInvocationTransactionService implements ToolInvocationTransactions {
  private static final int COORDINATION_SCAN_LIMIT = 100;

  private final ToolInvocationMapper invocationMapper;
  private final MysqlToolInvocationStore invocationStore;
  private final HarnessRunMapper runMapper;
  private final HarnessRunEventWriter eventWriter;
  private final ToolInvocationCoordinationService coordinationService;

  public ToolInvocationTransactionService(
      ToolInvocationMapper invocationMapper,
      MysqlToolInvocationStore invocationStore,
      HarnessRunMapper runMapper,
      HarnessRunEventWriter eventWriter,
      ToolInvocationCoordinationService coordinationService) {
    this.invocationMapper = Objects.requireNonNull(invocationMapper, "invocationMapper");
    this.invocationStore = Objects.requireNonNull(invocationStore, "invocationStore");
    this.runMapper = Objects.requireNonNull(runMapper, "runMapper");
    this.eventWriter = Objects.requireNonNull(eventWriter, "eventWriter");
    this.coordinationService = Objects.requireNonNull(coordinationService, "coordinationService");
  }

  /**
   * Records an idempotent cancellation request; the owner remains responsible for its terminal
   * race.
   */
  @Transactional
  public boolean requestCancel(long invocationId, Instant now) {
    if (invocationId <= 0) {
      throw new IllegalArgumentException("invocationId must be positive");
    }
    return invocationMapper.requestCancel(invocationId, utc(now)) == 1;
  }

  @Override
  @Transactional
  public boolean start(ClaimedToolInvocation claimed, Instant now) {
    HarnessRunDO run = lockWaitingRun(claimed.invocation().runId());
    if (run == null) {
      return false;
    }
    // Run is locked; Session/Root before Invocation and before event id allocation.
    eventWriter.lockSessionAndRoot(run.getSessionId());
    ToolInvocation invocation = lockOwned(claimed, now, run.getId());
    if (invocation == null) {
      return false;
    }
    eventWriter.appendLocked(
        run,
        List.of(
            new RunEventDraft(
                RunEventType.TOOL_STARTED,
                RunEventPayloads.of(
                    "invocationId",
                    invocation.id(),
                    "ordinal",
                    invocation.ordinal(),
                    "toolCallId",
                    invocation.toolCallId(),
                    "attempt",
                    run.getAttempt(),
                    "turnIndex",
                    run.getTurnIndex()))),
        now);
    return true;
  }

  @Override
  @Transactional
  public boolean appendPartial(
      ClaimedToolInvocation claimed, List<ToolResult> partials, Instant now) {
    List<ToolResult> batch = List.copyOf(Objects.requireNonNull(partials, "partials"));
    if (batch.isEmpty()) {
      return true;
    }
    HarnessRunDO run = lockWaitingRun(claimed.invocation().runId());
    if (run == null) {
      return false;
    }
    eventWriter.lockSessionAndRoot(run.getSessionId());
    ToolInvocation invocation = lockOwned(claimed, now, run.getId());
    if (invocation == null) {
      return false;
    }
    for (ToolResult partial : batch) {
      requireResultFor(invocation, partial);
    }
    eventWriter.appendLocked(
        run,
        List.of(
            new RunEventDraft(
                RunEventType.TOOL_DELTA_BATCH,
                RunEventPayloads.of(
                    "invocationId",
                    invocation.id(),
                    "ordinal",
                    invocation.ordinal(),
                    "partialResults",
                    batch.stream().map(ToolResultJsonCodec::encode).toList(),
                    "attempt",
                    run.getAttempt(),
                    "turnIndex",
                    run.getTurnIndex()))),
        now);
    return true;
  }

  @Override
  @Transactional
  public boolean terminate(
      ClaimedToolInvocation claimed,
      ToolInvocationStatus terminalStatus,
      ToolResult result,
      String errorMessage,
      Instant now) {
    if (terminalStatus == null || !terminalStatus.isTerminal()) {
      throw new IllegalArgumentException("terminalStatus must be terminal");
    }
    HarnessRunDO run = lockWaitingRun(claimed.invocation().runId());
    if (run == null) {
      return false;
    }
    eventWriter.lockSessionAndRoot(run.getSessionId());
    ToolInvocation invocation = lockOwned(claimed, now, run.getId());
    if (invocation == null) {
      return false;
    }
    invocation.status().requireTransitionTo(terminalStatus);
    requireResultFor(invocation, result);
    if (invocationMapper.terminateOwned(
            invocation.id(),
            claimed.invocation().leaseOwner(),
            terminalStatus.name(),
            ToolResultJsonCodec.encode(result),
            errorMessage,
            utc(now))
        != 1) {
      return false;
    }
    eventWriter.appendLocked(
        run,
        List.of(
            new RunEventDraft(
                RunEventType.TOOL_COMPLETED,
                RunEventPayloads.of(
                    "invocationId",
                    invocation.id(),
                    "ordinal",
                    invocation.ordinal(),
                    "toolCallId",
                    invocation.toolCallId(),
                    "status",
                    terminalStatus.name(),
                    "error",
                    result.error(),
                    "attempt",
                    run.getAttempt(),
                    "turnIndex",
                    run.getTurnIndex()))),
        now);
    return true;
  }

  /**
   * Scans ready Runs without a surrounding transaction; each Run coordinates through a separate
   * Spring proxy transaction so failures stay isolated.
   */
  @Override
  public int coordinateReadyRuns(Instant now) {
    int coordinated = 0;
    for (HarnessRunDO run : runMapper.listWaitingTools(COORDINATION_SCAN_LIMIT)) {
      try {
        if (coordinationService.coordinate(run.getId(), now)) {
          coordinated++;
        }
      } catch (RuntimeException error) {
        log.warn(
            "skipping tool coordination for waiting run {} after isolated failure",
            run.getId(),
            error);
      }
    }
    return coordinated;
  }

  /** Delegates to the per-Run transactional coordinator for direct single-run tests/callers. */
  public boolean coordinate(long runId, Instant now) {
    return coordinationService.coordinate(runId, now);
  }

  private HarnessRunDO lockWaitingRun(long runId) {
    HarnessRunDO run = runMapper.findForUpdate(runId);
    if (run == null || !RunStatus.WAITING_TOOLS.name().equals(run.getStatus())) {
      return null;
    }
    return run;
  }

  private ToolInvocation lockOwned(ClaimedToolInvocation claimed, Instant now, long runId) {
    ToolInvocationDO source = invocationMapper.findForUpdate(claimed.invocation().id());
    if (source == null) {
      return null;
    }
    ToolInvocation invocation = invocationStore.toInvocation(source);
    if (invocation.runId() != runId
        || (invocation.status() != ToolInvocationStatus.RUNNING
            && invocation.status() != ToolInvocationStatus.CANCEL_REQUESTED)
        || !Objects.equals(invocation.leaseOwner(), claimed.invocation().leaseOwner())
        || invocation.leaseUntil() == null
        || !invocation.leaseUntil().isAfter(now)) {
      return null;
    }
    return invocation;
  }

  private void requireResultFor(ToolInvocation invocation, ToolResult result) {
    if (result == null || !invocation.toolCallId().equals(result.toolCallId())) {
      throw new IllegalArgumentException("ToolResult must match frozen toolCallId");
    }
  }

  private static LocalDateTime utc(Instant value) {
    return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
  }
}
