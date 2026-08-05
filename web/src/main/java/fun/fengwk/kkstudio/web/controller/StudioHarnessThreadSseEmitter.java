package fun.fengwk.kkstudio.web.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import fun.fengwk.kkstudio.harness.runtime.spring.redis.RealtimeEventTail;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread realtime SSE tail backed by the runtime-spring {@link RealtimeEventTail}. Snapshot-first
 * clients load PostgreSQL state first. Revision events carry the durable revision as SSE id while
 * lossy Redis delta events intentionally carry no id, so Last-Event-ID cannot be corrupted by
 * Redis.
 */
@Slf4j
final class StudioHarnessThreadSseEmitter {
  private static final Duration BLOCK = Duration.ofMillis(250);
  private static final int BATCH = 100;

  private StudioHarnessThreadSseEmitter() {}

  static SseEmitter stream(
      long threadId,
      long afterRevision,
      String afterStreamId,
      RealtimeEventTail tail,
      ThreadRevisionEventSource revisionHub,
      Executor executor) {
    Objects.requireNonNull(tail, "tail");
    Objects.requireNonNull(executor, "executor");
    SseEmitter emitter = new SseEmitter(0L);
    AtomicBoolean closed = new AtomicBoolean(false);
    AtomicReference<String> cursor =
        new AtomicReference<>(RealtimeEventTail.normalizeAfterId(afterStreamId));
    AtomicReference<Future<?>> futureRef = new AtomicReference<>();
    AtomicBoolean resyncPending = new AtomicBoolean(false);
    AtomicBoolean revisionPending = new AtomicBoolean(false);
    AtomicLong latestRevision = new AtomicLong(afterRevision);
    AutoCloseable subscription =
        revisionHub.subscribe(
            threadId,
            afterRevision,
            event -> {
              if (event.resync()) {
                resyncPending.set(true);
              } else {
                latestRevision.accumulateAndGet(Long.parseLong(event.revision()), Math::max);
                revisionPending.set(true);
              }
            });
    Runnable close =
        () -> {
          closed.set(true);
          Future<?> future = futureRef.getAndSet(null);
          if (future != null) {
            future.cancel(true);
          }
          try {
            subscription.close();
          } catch (Exception closeFailure) {
            log.debug("Failed to close Thread revision subscription {}", threadId, closeFailure);
          }
        };
    emitter.onCompletion(close);
    emitter.onTimeout(close);
    emitter.onError(error -> close.run());
    Runnable work =
        () -> {
          try {
            long sentRevision = afterRevision;
            while (!closed.get() && !Thread.currentThread().isInterrupted()) {
              if (resyncPending.getAndSet(false)) {
                emitter.send(SseEmitter.event().name("resync").data(Map.of()));
              }
              if (revisionPending.getAndSet(false)) {
                long revision = latestRevision.get();
                if (revision > sentRevision) {
                  emitter.send(
                      SseEmitter.event()
                          .id(Long.toString(revision))
                          .name("revision")
                          .data(Map.of("revision", Long.toString(revision))));
                  sentRevision = revision;
                }
              }
              List<RealtimeEventTail.Record> batch =
                  tail.readAfter(threadId, cursor.get(), BATCH, BLOCK);
              if (closed.get()) {
                return;
              }
              for (RealtimeEventTail.Record record : batch) {
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
      close.run();
      try {
        emitter.completeWithError(rejected);
      } catch (IllegalStateException ignored) {
        // already completed
      }
    }
    return emitter;
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
