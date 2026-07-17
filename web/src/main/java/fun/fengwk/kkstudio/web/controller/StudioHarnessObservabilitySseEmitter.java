package fun.fengwk.kkstudio.web.controller;

import static fun.fengwk.kkstudio.core.harness.observability.service.ObservabilityLimits.requireNonNegativeCursor;

import fun.fengwk.kkstudio.core.harness.observability.service.HarnessObservabilityQueryService;
import fun.fengwk.kkstudio.core.harness.observability.service.ObservabilityLimits;
import fun.fengwk.kkstudio.core.harness.run.store.MysqlHarnessRunStore;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.core.harness.session.support.HarnessIds;
import fun.fengwk.kkstudio.share.model.RootActivityDTO;
import fun.fengwk.kkstudio.share.model.RunEventDTO;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Reusable database-backed SSE runtime for Run event and Root activity streams.
 *
 * <p>Uses {@code harnessEventStreamTaskExecutor} (no EventBus), polls the persisted run/activity
 * store at 250ms cadence, sends a 5s {@code heartbeat}, and closes the stream after 30s of
 * inactivity once the corresponding Run has reached a terminal state or the root tree has no active
 * run. Local cursors are only advanced after a successful {@code emitter.send(...)}.
 */
@Component
public class StudioHarnessObservabilitySseEmitter {

  private static final Logger log =
      LoggerFactory.getLogger(StudioHarnessObservabilitySseEmitter.class);

  private static final long EMITTER_TIMEOUT_MILLIS = 65_000L;
  private static final long POLL_INTERVAL_MILLIS = 250L;
  private static final long HEARTBEAT_INTERVAL_MILLIS = 5_000L;
  private static final long IDLE_TIMEOUT_MILLIS = 30_000L;
  private static final long MIN_IDLE_TIMEOUT_MILLIS = 50L;

  private final HarnessObservabilityQueryService observabilityService;
  private final MysqlHarnessRunStore runStore;
  private final HarnessSessionMapper sessionMapper;
  private final Executor eventStreamTaskExecutor;

  public StudioHarnessObservabilitySseEmitter(
      HarnessObservabilityQueryService observabilityService,
      MysqlHarnessRunStore runStore,
      HarnessSessionMapper sessionMapper,
      @Qualifier("harnessEventStreamTaskExecutor") Executor eventStreamTaskExecutor) {
    this.observabilityService = observabilityService;
    this.runStore = runStore;
    this.sessionMapper = sessionMapper;
    this.eventStreamTaskExecutor = eventStreamTaskExecutor;
  }

  public SseEmitter openRunStream(String runId, long afterSequence, Long idleTimeoutMillis) {
    long parsedRunId = HarnessIds.parsePositive(runId, "runId");
    requireNonNegativeCursor(afterSequence, "afterSequence");
    if (runStore.find(parsedRunId).isEmpty()) {
      throw new IllegalArgumentException("unknown run: " + runId);
    }
    long idle = normalizeIdleTimeoutMillis(idleTimeoutMillis);
    SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
    AtomicBoolean active = registerCallbacks(emitter);
    submitStream(
        () -> emitRunEvents(parsedRunId, afterSequence, idle, emitter, active), emitter, "run");
    return emitter;
  }

  public SseEmitter openRootActivityStream(
      String sessionId, long afterEventId, Long idleTimeoutMillis) {
    long parsedSessionId = HarnessIds.parsePositive(sessionId, "sessionId");
    requireNonNegativeCursor(afterEventId, "afterEventId");
    var sessionRow = sessionMapper.find(parsedSessionId);
    if (sessionRow == null) {
      throw new IllegalArgumentException("unknown session: " + sessionId);
    }
    long rootSessionId = sessionRow.getRootSessionId();
    long idle = normalizeIdleTimeoutMillis(idleTimeoutMillis);
    SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
    AtomicBoolean active = registerCallbacks(emitter);
    submitStream(
        () ->
            emitRootActivities(parsedSessionId, rootSessionId, afterEventId, idle, emitter, active),
        emitter,
        "root activity");
    return emitter;
  }

  private void submitStream(Runnable task, SseEmitter emitter, String streamKind) {
    try {
      eventStreamTaskExecutor.execute(task);
    } catch (RejectedExecutionException error) {
      // Fail the HTTP request promptly; do not leave the client with an open but inert SSE body.
      emitter.complete();
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, streamKind + " event stream capacity exceeded", error);
    }
  }

  private static long normalizeIdleTimeoutMillis(Long idleTimeoutMillis) {
    if (idleTimeoutMillis == null) {
      return IDLE_TIMEOUT_MILLIS;
    }
    return Math.max(MIN_IDLE_TIMEOUT_MILLIS, Math.min(idleTimeoutMillis, IDLE_TIMEOUT_MILLIS));
  }

  private static AtomicBoolean registerCallbacks(SseEmitter emitter) {
    AtomicBoolean active = new AtomicBoolean(true);
    emitter.onCompletion(() -> active.set(false));
    emitter.onTimeout(() -> active.set(false));
    emitter.onError(error -> active.set(false));
    return active;
  }

  private void emitRunEvents(
      long runId,
      long afterSequence,
      long idleTimeoutMillis,
      SseEmitter emitter,
      AtomicBoolean active) {
    long localCursor = afterSequence;
    long lastActivityMillis = System.currentTimeMillis();
    long lastHeartbeatMillis = 0L;
    try {
      while (active.get()) {
        List<RunEventDTO> batch =
            observabilityService.listRunEvents(
                Long.toString(runId), localCursor, ObservabilityLimits.MAX_LIMIT);
        long[] advanced = advanceRunCursor(batch, emitter);
        if (advanced[1] == 1L) {
          localCursor = advanced[0];
          lastActivityMillis = System.currentTimeMillis();
        }
        long now = System.currentTimeMillis();
        if (now - lastHeartbeatMillis >= HEARTBEAT_INTERVAL_MILLIS) {
          emitter.send(SseEmitter.event().name("heartbeat").data(Map.of("timestampMillis", now)));
          lastHeartbeatMillis = now;
        }
        if (shouldCloseForRun(runId, idleTimeoutMillis, now, lastActivityMillis)) {
          emitter.complete();
          return;
        }
        Thread.sleep(POLL_INTERVAL_MILLIS);
      }
    } catch (IOException e) {
      emitter.complete();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      emitter.complete();
    } catch (RuntimeException e) {
      log.warn("observability run SSE failed for run={}", runId, e);
      emitter.completeWithError(e);
    }
  }

  private void emitRootActivities(
      long sessionId,
      long rootSessionId,
      long afterEventId,
      long idleTimeoutMillis,
      SseEmitter emitter,
      AtomicBoolean active) {
    long localCursor = afterEventId;
    long lastActivityMillis = System.currentTimeMillis();
    long lastHeartbeatMillis = 0L;
    try {
      while (active.get()) {
        List<RootActivityDTO> batch =
            observabilityService.listRootActivities(
                Long.toString(sessionId), localCursor, ObservabilityLimits.MAX_LIMIT);
        long[] advanced = advanceActivityCursor(batch, emitter);
        if (advanced[1] == 1L) {
          localCursor = advanced[0];
          lastActivityMillis = System.currentTimeMillis();
        }
        long now = System.currentTimeMillis();
        if (now - lastHeartbeatMillis >= HEARTBEAT_INTERVAL_MILLIS) {
          emitter.send(SseEmitter.event().name("heartbeat").data(Map.of("timestampMillis", now)));
          lastHeartbeatMillis = now;
        }
        if (shouldCloseForRoot(rootSessionId, idleTimeoutMillis, now, lastActivityMillis)) {
          emitter.complete();
          return;
        }
        Thread.sleep(POLL_INTERVAL_MILLIS);
      }
    } catch (IOException e) {
      emitter.complete();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      emitter.complete();
    } catch (RuntimeException e) {
      log.warn("observability root activity SSE failed for session={}", sessionId, e);
      emitter.completeWithError(e);
    }
  }

  private static long[] advanceRunCursor(List<RunEventDTO> batch, SseEmitter emitter)
      throws IOException {
    long newCursor = 0L;
    boolean advanced = false;
    for (RunEventDTO event : batch) {
      // SSE event id must be the decimal sequence, never the DTO eventId (which is the Snowflake
      // id). The DTO eventId field is used for typed access while the wire id is set explicitly.
      emitter.send(
          SseEmitter.event().name("run_event").id(Long.toString(event.getSequence())).data(event));
      newCursor = event.getSequence();
      advanced = true;
    }
    return new long[] {newCursor, advanced ? 1L : 0L};
  }

  private static long[] advanceActivityCursor(List<RootActivityDTO> batch, SseEmitter emitter)
      throws IOException {
    long newCursor = 0L;
    boolean advanced = false;
    for (RootActivityDTO event : batch) {
      emitter.send(SseEmitter.event().name("root_activity").id(event.getEventId()).data(event));
      newCursor = Long.parseLong(event.getEventId());
      advanced = true;
    }
    return new long[] {newCursor, advanced ? 1L : 0L};
  }

  private boolean shouldCloseForRun(
      long runId, long idleTimeoutMillis, long nowMillis, long lastActivityMillis) {
    return isRunTerminal(runId) && nowMillis - lastActivityMillis >= idleTimeoutMillis;
  }

  private boolean shouldCloseForRoot(
      long rootSessionId, long idleTimeoutMillis, long nowMillis, long lastActivityMillis) {
    return !isRootActive(rootSessionId) && nowMillis - lastActivityMillis >= idleTimeoutMillis;
  }

  private boolean isRunTerminal(long runId) {
    return runStore.find(runId).map(run -> run.status().terminal()).orElse(true);
  }

  private boolean isRootActive(long rootSessionId) {
    return sessionMapper.findAnyActiveRunIdByRoot(rootSessionId) != null;
  }
}
