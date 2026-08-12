package fun.fengwk.kkstudio.web.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
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
 * Canvas graph version SSE tail。客户端持有权威快照后订阅；'version' 事件只携带前进的 version，客户端按最后已知版本拉取
 * changes，'resync' 事件要求整体快照恢复。版本以持久 {@code canvas_document.version} 作为事件 id，因此 Last-Event-ID
 * 不会被有损缓存污染。
 */
@Slf4j
final class CanvasSseEmitter {

  private CanvasSseEmitter() {}

  static SseEmitter stream(
      UUID canvasId, long afterVersion, CanvasVersionEventSource eventSource, Executor executor) {
    Objects.requireNonNull(canvasId, "canvasId");
    Objects.requireNonNull(eventSource, "eventSource");
    Objects.requireNonNull(executor, "executor");
    SseEmitter emitter = new SseEmitter(0L);
    AtomicBoolean closed = new AtomicBoolean(false);
    AtomicReference<Future<?>> futureRef = new AtomicReference<>();
    AtomicBoolean resyncPending = new AtomicBoolean(false);
    AtomicBoolean versionPending = new AtomicBoolean(false);
    AtomicLong latestVersion = new AtomicLong(afterVersion);
    Object wakeup = new Object();
    AutoCloseable subscription =
        eventSource.subscribe(
            canvasId,
            afterVersion,
            event -> {
              if (event.resync()) {
                resyncPending.set(true);
              } else {
                latestVersion.accumulateAndGet(event.version(), Math::max);
                versionPending.set(true);
              }
              synchronized (wakeup) {
                wakeup.notifyAll();
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
            log.debug("Failed to close canvas version subscription {}", canvasId, closeFailure);
          }
          synchronized (wakeup) {
            wakeup.notifyAll();
          }
        };
    emitter.onCompletion(close);
    emitter.onTimeout(close);
    emitter.onError(error -> close.run());
    Runnable work =
        () -> {
          try {
            long sentVersion = afterVersion;
            while (!closed.get() && !Thread.currentThread().isInterrupted()) {
              if (resyncPending.getAndSet(false)) {
                emitter.send(SseEmitter.event().name("resync").data(Map.of()));
              }
              if (versionPending.getAndSet(false)) {
                long version = latestVersion.get();
                if (version > sentVersion) {
                  emitter.send(versionEvent(version));
                  sentVersion = version;
                }
              }
              // hub 通知或关闭都会 notifyAll；超时只是兜底，防止订阅丢失导致静默挂起。
              synchronized (wakeup) {
                if (!closed.get() && !resyncPending.get() && !versionPending.get()) {
                  wakeup.wait(5_000);
                }
              }
            }
          } catch (IOException | RuntimeException | InterruptedException error) {
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

  /**
   * 'version' 事件帧：event id 与 payload version 都必须是规范非负十进制字符串（id 用作 Last-Event-ID 游标，payload 是客户端拉取
   * changes 的提示）。
   */
  static SseEmitter.SseEventBuilder versionEvent(long version) {
    return SseEmitter.event()
        .id(Long.toString(version))
        .name("version")
        .data(Map.of("version", Long.toString(version)));
  }
}
