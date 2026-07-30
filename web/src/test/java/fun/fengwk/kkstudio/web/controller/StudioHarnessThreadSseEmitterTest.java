package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import fun.fengwk.kkstudio.core.ai.runtime.realtime.HarnessRealtimeEventTail;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** SSE close cancels the tail worker so block-read loops stop; overload rejects without work. */
class StudioHarnessThreadSseEmitterTest {

  @Test
  void completeStopsFurtherTailReads() throws Exception {
    HarnessRealtimeEventTail tail = mock(HarnessRealtimeEventTail.class);
    AtomicInteger calls = new AtomicInteger();
    when(tail.readAfter(anyLong(), anyString(), anyInt(), any(Duration.class)))
        .thenAnswer(
            inv -> {
              calls.incrementAndGet();
              Thread.sleep(50);
              return List.of(
                  new HarnessRealtimeEventTail.Record(
                      "1-0",
                      "{\"threadId\":\"1\",\"subjectKind\":\"MODEL_INVOCATION\",\"subjectId\":\"2\",\"attempt\":1,\"type\":\"MODEL_DELTA\",\"payload\":{\"kind\":\"TEXT_DELTA\",\"text\":\"x\"},\"createdAt\":\"2026-01-01T00:00:00Z\"}"));
            });
    ExecutorService executor =
        Executors.newCachedThreadPool(
            r -> {
              Thread t = new Thread(r, "thread-realtime-sse-test");
              t.setDaemon(true);
              return t;
            });
    try {
      SseEmitter emitter =
          StudioHarnessThreadSseEmitter.stream(1L, 0L, "$", tail, source(), executor);
      assertNotNull(emitter);
      Thread.sleep(250);
      emitter.complete();
      int afterClose = calls.get();
      Thread.sleep(500);
      assertTrue(calls.get() <= afterClose + 1);
      verify(tail, atLeastOnce()).readAfter(anyLong(), anyString(), anyInt(), any(Duration.class));
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * Bounded overload: a saturated executor fails the stream immediately without starting tail
   * polling or leaving background work that would call the tail later.
   */
  @Test
  void rejectedExecutorFailsWithoutInvokingTail() throws Exception {
    HarnessRealtimeEventTail tail = mock(HarnessRealtimeEventTail.class);
    Executor rejecting =
        command -> {
          throw new RejectedExecutionException("saturated");
        };

    SseEmitter emitter =
        StudioHarnessThreadSseEmitter.stream(1L, 0L, "$", tail, source(), rejecting);

    assertNotNull(emitter);
    verifyNoInteractions(tail);
    // Failed emitters reject further sends; proves the stream completed with an error.
    assertThrows(
        IllegalStateException.class,
        () -> emitter.send(SseEmitter.event().name("probe").data("x")));
    Thread.sleep(200);
    verifyNoInteractions(tail);
  }

  /** Production AsyncTaskExecutor and plain Executor adapters both retain a cancellable Future. */
  @Test
  void supportsManagedAsyncAndPlainExecutorsWithoutInlinePolling() {
    HarnessRealtimeEventTail tail = mock(HarnessRealtimeEventTail.class);
    AsyncTaskExecutor async = mock(AsyncTaskExecutor.class);
    @SuppressWarnings("unchecked")
    Future<Object> asyncFuture = mock(Future.class);
    doReturn(asyncFuture).when(async).submit(any(Runnable.class));

    SseEmitter asyncEmitter =
        StudioHarnessThreadSseEmitter.stream(1L, 0L, "$", tail, source(), async);

    verify(async).submit(any(Runnable.class));
    asyncEmitter.complete();

    AtomicReference<Runnable> submitted = new AtomicReference<>();
    SseEmitter plainEmitter =
        StudioHarnessThreadSseEmitter.stream(1L, 0L, "$", tail, source(), submitted::set);

    assertNotNull(submitted.get());
    plainEmitter.complete();
    verifyNoInteractions(tail);
  }

  private static ThreadRevisionEventSource source() {
    return (threadId, afterRevision, consumer) -> () -> {};
  }
}
