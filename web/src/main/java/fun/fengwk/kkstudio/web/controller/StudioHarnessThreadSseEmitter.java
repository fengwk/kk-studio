package fun.fengwk.kkstudio.web.controller;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import fun.fengwk.kkstudio.core.harness.redis.RedisRealtimeEventTail;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread realtime SSE tail backed by Redis Streams. Snapshot-first clients load PostgreSQL state
 * first, then resume from a Redis stream id; Redis failures only disconnect the emitter.
 */
final class StudioHarnessThreadSseEmitter {
  private static final Duration BLOCK = Duration.ofSeconds(2);
  private static final int BATCH = 100;
  private static final ExecutorService WORKERS =
      Executors.newCachedThreadPool(
          r -> {
            Thread t = new Thread(r, "thread-realtime-sse");
            t.setDaemon(true);
            return t;
          });

  private StudioHarnessThreadSseEmitter() {}

  static SseEmitter stream(long threadId, String afterStreamId, RedisRealtimeEventTail tail) {
    SseEmitter emitter = new SseEmitter(0L);
    AtomicBoolean closed = new AtomicBoolean(false);
    AtomicReference<String> cursor =
        new AtomicReference<>(RedisRealtimeEventTail.normalizeAfterId(afterStreamId));
    AtomicReference<Future<?>> futureRef = new AtomicReference<>();
    Runnable close =
        () -> {
          closed.set(true);
          Future<?> future = futureRef.getAndSet(null);
          if (future != null) {
            future.cancel(true);
          }
        };
    emitter.onCompletion(close);
    emitter.onTimeout(close);
    emitter.onError(error -> close.run());
    Future<?> future =
        WORKERS.submit(
            () -> {
              try {
                while (!closed.get() && !Thread.currentThread().isInterrupted()) {
                  List<RedisRealtimeEventTail.Record> batch =
                      tail.readAfter(threadId, cursor.get(), BATCH, BLOCK);
                  if (closed.get()) {
                    return;
                  }
                  for (RedisRealtimeEventTail.Record record : batch) {
                    emitter.send(
                        SseEmitter.event()
                            .id(record.id())
                            .name("realtime")
                            .data(record.payloadJson()));
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
            });
    futureRef.set(future);
    if (closed.get()) {
      close.run();
    }
    return emitter;
  }
}
