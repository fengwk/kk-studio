package fun.fengwk.kkstudio.web.controller;

import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import fun.fengwk.kkstudio.core.harness.realtime.HarnessRealtimeEventTail;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread realtime SSE tail backed by Core {@link HarnessRealtimeEventTail}. Snapshot-first clients
 * load PostgreSQL state first. Revision events carry the durable revision as SSE id while lossy
 * Redis delta events intentionally carry no id, so Last-Event-ID cannot be corrupted by Redis.
 */
final class StudioHarnessThreadSseEmitter {
  private static final Duration BLOCK = Duration.ofSeconds(2);
  private static final int BATCH = 100;

  private StudioHarnessThreadSseEmitter() {}

  static SseEmitter stream(
      long threadId,
      String afterStreamId,
      HarnessRealtimeEventTail tail,
      ThreadRevisionEventSource revisionHub,
      Executor executor) {
    Objects.requireNonNull(tail, "tail");
    Objects.requireNonNull(executor, "executor");
    SseEmitter emitter = new SseEmitter(0L);
    AtomicBoolean closed = new AtomicBoolean(false);
    AtomicReference<String> cursor =
        new AtomicReference<>(HarnessRealtimeEventTail.normalizeAfterId(afterStreamId));
    AtomicReference<Future<?>> futureRef = new AtomicReference<>();
    LinkedBlockingQueue<ThreadRevisionEventSource.Event> revisionEvents =
        new LinkedBlockingQueue<>();
    AutoCloseable subscription = revisionHub.subscribe(threadId, revisionEvents::offer);
    Runnable close =
        () -> {
          closed.set(true);
          Future<?> future = futureRef.getAndSet(null);
          if (future != null) {
            future.cancel(true);
          }
          try {
            subscription.close();
          } catch (Exception ignored) {
            // Subscription cleanup is best-effort after transport teardown.
          }
        };
    emitter.onCompletion(close);
    emitter.onTimeout(close);
    emitter.onError(error -> close.run());
    Runnable work =
        () -> {
          try {
            while (!closed.get() && !Thread.currentThread().isInterrupted()) {
              ThreadRevisionEventSource.Event revision;
              while ((revision = revisionEvents.poll()) != null) {
                if (revision.resync()) {
                  emitter.send(SseEmitter.event().name("resync").data("{}"));
                } else {
                  emitter.send(
                      SseEmitter.event()
                          .id(revision.revision())
                          .name("revision")
                          .data("{\"revision\":\"" + revision.revision() + "\"}"));
                }
              }
              List<HarnessRealtimeEventTail.Record> batch =
                  tail.readAfter(threadId, cursor.get(), BATCH, BLOCK);
              if (closed.get()) {
                return;
              }
              for (HarnessRealtimeEventTail.Record record : batch) {
                emitter.send(SseEmitter.event().name("realtime").data(record.payloadJson()));
                cursor.set(record.id());
              }
            }
          } catch (IOException | RuntimeException error) {
            if (!closed.get()) {
              close.run();
              try {
                emitter.completeWithError(error);
              } catch (IllegalStateException ignored) {
                // already completed
              }
            }
          }
        };
    try {
      Future<?> future = submit(executor, work);
      futureRef.set(future);
      if (closed.get()) {
        close.run();
      }
    } catch (RejectedExecutionException rejected) {
      // Bounded overload: fail the emitter immediately instead of leaving an inert SSE open.
      closed.set(true);
      try {
        emitter.completeWithError(rejected);
      } catch (IllegalStateException ignored) {
        // already completed
      }
    }
    return emitter;
  }

  static SseEmitter stream(
      long threadId, String afterStreamId, HarnessRealtimeEventTail tail, Executor executor) {
    return stream(threadId, afterStreamId, tail, (ignored, consumer) -> () -> {}, executor);
  }

  private static Future<?> submit(Executor executor, Runnable work) {
    if (executor instanceof AsyncTaskExecutor asyncTaskExecutor) {
      return asyncTaskExecutor.submit(work);
    }
    if (executor instanceof ExecutorService executorService) {
      return executorService.submit(work);
    }
    FutureTask<Void> task = new FutureTask<>(work, null);
    executor.execute(task);
    return task;
  }
}
