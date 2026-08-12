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
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 由 runtime-spring {@link RealtimeEventTail} 支撑的 Thread realtime SSE tail。Snapshot-first 客户端 先加载
 * PostgreSQL 状态。revision 事件以持久 revision 作为 SSE id，而有损的 Redis delta 事件故意不携带 id， 因此 Last-Event-ID 不会被
 * Redis 污染。
 */
@Slf4j
final class StudioHarnessThreadSseEmitter {
  private static final Duration BLOCK = Duration.ofMillis(250);
  private static final int BATCH = 100;

  private StudioHarnessThreadSseEmitter() {}

  static SseEmitter stream(
      UUID threadId,
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
                // 已完成
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
      // 有界过载：立即让 emitter 失败，而不是保持一个空闲的 SSE 连接。
      close.run();
      try {
        emitter.completeWithError(rejected);
      } catch (IllegalStateException ignored) {
        // 已完成
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
