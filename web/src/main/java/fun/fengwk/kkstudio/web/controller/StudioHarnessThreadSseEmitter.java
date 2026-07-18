package fun.fengwk.kkstudio.web.controller;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import fun.fengwk.kkstudio.core.harness.observability.service.HarnessObservabilityQueryService;
import fun.fengwk.kkstudio.share.model.ThreadEventDTO;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Thread event SSE：定时轮询 listThreadEvents；关闭时取消 scheduled future。 */
final class StudioHarnessThreadSseEmitter {
  private static final ScheduledExecutorService SCHEDULER =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "thread-event-sse");
            t.setDaemon(true);
            return t;
          });

  private StudioHarnessThreadSseEmitter() {}

  static SseEmitter stream(
      String threadId, long afterEventId, HarnessObservabilityQueryService queryService) {
    SseEmitter emitter = new SseEmitter(0L);
    AtomicLong cursor = new AtomicLong(afterEventId);
    AtomicBoolean closed = new AtomicBoolean(false);
    AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();
    Runnable close =
        () -> {
          closed.set(true);
          ScheduledFuture<?> future = futureRef.getAndSet(null);
          if (future != null) {
            future.cancel(false);
          }
        };
    emitter.onCompletion(close);
    emitter.onTimeout(close);
    emitter.onError(error -> close.run());
    ScheduledFuture<?> future =
        SCHEDULER.scheduleWithFixedDelay(
            () -> {
              if (closed.get()) {
                return;
              }
              try {
                List<ThreadEventDTO> events =
                    queryService.listThreadEvents(threadId, cursor.get(), 100);
                for (ThreadEventDTO event : events) {
                  emitter.send(
                      SseEmitter.event().id(event.getEventId()).name("thread_event").data(event));
                  cursor.set(Long.parseLong(event.getEventId()));
                }
              } catch (IOException | RuntimeException error) {
                close.run();
                try {
                  emitter.completeWithError(error);
                } catch (IllegalStateException ignored) {
                  // already completed
                }
              }
            },
            0,
            200,
            TimeUnit.MILLISECONDS);
    futureRef.set(future);
    // 若在 set 前 emitter 已关闭，立即取消，避免 future 泄漏。
    if (closed.get()) {
      close.run();
    }
    return emitter;
  }
}
