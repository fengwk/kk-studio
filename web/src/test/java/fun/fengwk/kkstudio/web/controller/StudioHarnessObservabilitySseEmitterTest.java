package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.core.harness.observability.service.HarnessObservabilityQueryService;
import fun.fengwk.kkstudio.core.harness.run.store.MysqlHarnessRunStore;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.harness.runtime.run.AgentRun;
import fun.fengwk.kkstudio.harness.runtime.run.RunStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

class StudioHarnessObservabilitySseEmitterTest {

  /**
   * Executor rejection must map to HTTP 503 immediately instead of returning an open but inert SSE
   * connection.
   */
  @Test
  void rejectsRunStreamWhenExecutorIsSaturated() {
    MysqlHarnessRunStore runStore = mock(MysqlHarnessRunStore.class);
    when(runStore.find(anyLong())).thenReturn(Optional.of(run()));
    AtomicInteger submissions = new AtomicInteger();
    Executor rejecting =
        command -> {
          submissions.incrementAndGet();
          throw new RejectedExecutionException("saturated");
        };
    StudioHarnessObservabilitySseEmitter emitter =
        new StudioHarnessObservabilitySseEmitter(
            mock(HarnessObservabilityQueryService.class),
            runStore,
            mock(HarnessSessionMapper.class),
            rejecting);

    ResponseStatusException error =
        assertThrows(ResponseStatusException.class, () -> emitter.openRunStream("12", 0L, 60L));
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
    assertEquals(1, submissions.get());
  }

  private static AgentRun run() {
    Instant now = Instant.parse("2026-07-16T00:00:00Z");
    return new AgentRun(
        12L, 2L, 3L, RunStatus.SUCCEEDED, 0, 1, 0, null, null, now, null, now, now, now, now);
  }
}
