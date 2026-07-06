package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.kkstudio.core.agent.run.service.AgentRunService;
import fun.fengwk.kkstudio.core.agent.session.service.AgentSessionService;
import fun.fengwk.kkstudio.share.model.AgentSessionEventDTO;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import fun.fengwk.kkstudio.core.agent.run.service.AgentRunService;
import fun.fengwk.kkstudio.core.agent.session.service.AgentSessionService;
import fun.fengwk.kkstudio.share.model.AgentSessionEventDTO;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * @author fengwk
 */
@RequestMapping("/api/agent/sessions")
@RestController
public class StudioAgentSessionEventStreamController {

  private static final long DEFAULT_IDLE_TIMEOUT_MILLIS = 30_000L;
  private static final long DEFAULT_EMITTER_TIMEOUT_MILLIS = 65_000L;
  private static final long HEARTBEAT_INTERVAL_MILLIS = 5_000L;
  private static final long POLL_INTERVAL_MILLIS = 250L;

  private final AgentSessionService agentSessionService;
  private final AgentRunService agentRunService;
  private final Executor eventStreamTaskExecutor;

  public StudioAgentSessionEventStreamController(
      AgentSessionService agentSessionService,
      AgentRunService agentRunService,
      @Qualifier("agentEventStreamTaskExecutor") Executor eventStreamTaskExecutor) {
    this.agentSessionService = agentSessionService;
    this.agentRunService = agentRunService;
    this.eventStreamTaskExecutor = eventStreamTaskExecutor;
  }

  @GetMapping(value = "/{sessionId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamEvents(
      @PathVariable("sessionId") String sessionId,
      @RequestParam(value = "headEventId", required = false) String headEventId,
      @RequestParam(value = "idleTimeoutMillis", required = false) Long idleTimeoutMillis) {
    agentSessionService.getSession(sessionId);

    long idleTimeout = normalizeIdleTimeoutMillis(idleTimeoutMillis);
    SseEmitter emitter = new SseEmitter(DEFAULT_EMITTER_TIMEOUT_MILLIS);
    AtomicBoolean active = new AtomicBoolean(true);
    emitter.onCompletion(() -> active.set(false));
    emitter.onTimeout(() -> active.set(false));
    emitter.onError(error -> active.set(false));

    eventStreamTaskExecutor.execute(
        () -> emitSessionEvents(sessionId, headEventId, idleTimeout, emitter, active));
    return emitter;
  }

  private void emitSessionEvents(
      String sessionId,
      String headEventId,
      long idleTimeoutMillis,
      SseEmitter emitter,
      AtomicBoolean active) {
    Set<String> emittedEventIds = new LinkedHashSet<>();
    String lastEmittedEventId = null;
    long lastActivityMillis = System.currentTimeMillis();
    long lastHeartbeatMillis = 0L;
    try {
      while (active.get()) {
        EventEmission emission =
            emitNewEvents(sessionId, headEventId, lastEmittedEventId, emittedEventIds, emitter);
        boolean emitted = emission.emitted();
        lastEmittedEventId = emission.lastEmittedEventId();
        long nowMillis = System.currentTimeMillis();
        if (emitted) {
          lastActivityMillis = nowMillis;
        }
        if (nowMillis - lastHeartbeatMillis >= HEARTBEAT_INTERVAL_MILLIS) {
          emitter.send(
              SseEmitter.event().name("heartbeat").data(Map.of("timestampMillis", nowMillis)));
          lastHeartbeatMillis = nowMillis;
        }
        if (!hasActiveRun(sessionId) && nowMillis - lastActivityMillis >= idleTimeoutMillis) {
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
      emitter.completeWithError(e);
    }
  }

  private EventEmission emitNewEvents(
      String sessionId,
      String headEventId,
      String lastEmittedEventId,
      Set<String> emittedEventIds,
      SseEmitter emitter)
      throws IOException {
    List<AgentSessionEventDTO> events =
        isBlank(headEventId)
            ? agentSessionService.listEventsAfter(sessionId, lastEmittedEventId)
            : agentSessionService.listEvents(sessionId, headEventId);
    boolean emitted = false;
    String latestEmittedEventId = lastEmittedEventId;
    for (AgentSessionEventDTO event : events) {
      if (emittedEventIds.add(event.getEventId())) {
        emitter.send(SseEmitter.event().name("session_event").id(event.getEventId()).data(event));
        emitted = true;
        latestEmittedEventId = event.getEventId();
      }
    }
    return new EventEmission(emitted, latestEmittedEventId);
  }

  private boolean hasActiveRun(String sessionId) {
    return agentRunService.hasActiveRun(sessionId);
  }

  private long normalizeIdleTimeoutMillis(Long idleTimeoutMillis) {
    if (idleTimeoutMillis == null) {
      return DEFAULT_IDLE_TIMEOUT_MILLIS;
    }
    return Math.max(50L, Math.min(idleTimeoutMillis, DEFAULT_IDLE_TIMEOUT_MILLIS));
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private record EventEmission(boolean emitted, String lastEmittedEventId) {}
}
