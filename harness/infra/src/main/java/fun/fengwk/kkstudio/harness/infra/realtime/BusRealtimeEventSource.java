package fun.fengwk.kkstudio.harness.infra.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fun.fengwk.kkstudio.harness.infra.realtime.RealtimeNotificationCodec.Envelope;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Realtime topic 的本地订阅与分发器：REALTIME 总线订阅由通知组合根持有，本类只维护 per-Thread 本地订阅与完成围栏。
 *
 * <p>本类不创建线程或连接；总线在自身独立执行阶段交付已解码的 {@link Envelope}，连接建立或重建、以及总线请求权威对账时触发 {@link #onResync()}。合法
 * EVENT 仅分发到对应 Thread，RESYNC 只提示客户端回读权威快照；畸形 payload 由总线的 topic codec 拒绝并转为 resync。 全局生命周期锁只保护
 * closed/map 与 subscriber 快照，用户回调由每个 subscriber 的独立关闭围栏管理并始终在全局锁外执行。
 */
public final class BusRealtimeEventSource implements RealtimeEventSource {

  private static final Logger log = LoggerFactory.getLogger(BusRealtimeEventSource.class);

  private final Map<UUID, List<Subscriber>> subscribersByThread = new HashMap<>();
  private final Object lifecycleFence = new Object();

  /**
   * Source-wide callback 完成围栏。唯一嵌套顺序是 Subscriber callbackFence -> sourceCallbackFence； source close
   * 会先逐个关闭 Subscriber 围栏，再单独等待本围栏，禁止反向持锁。
   */
  private final Object sourceCallbackFence = new Object();

  private final ThreadLocal<Integer> sourceCallbackDepth = ThreadLocal.withInitial(() -> 0);

  private boolean closed;
  private int activeSourceCallbacks;
  private boolean subscriberFencesClosed;

  @Override
  public AutoCloseable subscribe(
      UUID threadId, Consumer<RealtimeEvent> onEvent, Runnable onResync) {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(onEvent, "onEvent");
    Objects.requireNonNull(onResync, "onResync");
    Subscriber subscriber = new Subscriber(threadId, onEvent, onResync);
    synchronized (lifecycleFence) {
      if (closed) {
        throw new IllegalStateException("realtime event source is already closed");
      }
      subscribersByThread.computeIfAbsent(threadId, ignored -> new ArrayList<>()).add(subscriber);
    }
    return subscriber::close;
  }

  /** 由唯一通知运行时交付一条已解码的 realtime envelope。 */
  public void onEnvelope(Envelope envelope) {
    Objects.requireNonNull(envelope, "envelope");
    List<Subscriber> subscribers;
    synchronized (lifecycleFence) {
      if (closed) {
        return;
      }
      if (envelope instanceof Envelope.Event event) {
        subscribers = snapshot(event.event().threadId());
      } else {
        subscribers = snapshot(((Envelope.Resync) envelope).threadId());
      }
    }
    if (envelope instanceof Envelope.Event event) {
      dispatchEvent(subscribers, event.event());
    } else {
      dispatchResync(subscribers);
    }
  }

  /** 连接建立或重建、以及总线请求权威对账时触发全部本地订阅恢复。 */
  public void onResync() {
    List<Subscriber> subscribers;
    synchronized (lifecycleFence) {
      if (closed) {
        return;
      }
      subscribers = snapshotAll();
    }
    dispatchResync(subscribers);
  }

  @Override
  public void close() {
    // source 不持有总线订阅；这里只关闭本地订阅并按既有围栏等待在途回调结束。
    List<Subscriber> subscribers = List.of();
    boolean firstCloser = false;
    synchronized (lifecycleFence) {
      if (!closed) {
        closed = true;
        subscribers = snapshotAll();
        subscribersByThread.clear();
        firstCloser = true;
      }
    }
    if (firstCloser) {
      for (Subscriber subscriber : subscribers) {
        subscriber.disable();
      }
      synchronized (sourceCallbackFence) {
        subscriberFencesClosed = true;
        sourceCallbackFence.notifyAll();
      }
      awaitCallbacks(sourceCallbackDepth.get());
    } else if (sourceCallbackDepth.get() > 0) {
      awaitSubscriberFences();
    } else {
      awaitCallbacks(0);
    }
  }

  /** 仅在 lifecycleFence 内调用，复制目标 Thread 当前 subscriber 快照。 */
  private List<Subscriber> snapshot(UUID threadId) {
    List<Subscriber> subscribers = subscribersByThread.get(threadId);
    if (subscribers == null) {
      return List.of();
    }
    return List.copyOf(subscribers);
  }

  /** 仅在 lifecycleFence 内调用，复制全部当前 subscriber 快照。 */
  private List<Subscriber> snapshotAll() {
    List<Subscriber> snapshot = new ArrayList<>();
    for (List<Subscriber> subscribers : subscribersByThread.values()) {
      snapshot.addAll(subscribers);
    }
    return List.copyOf(snapshot);
  }

  private static void dispatchEvent(List<Subscriber> subscribers, RealtimeEvent event) {
    for (Subscriber subscriber : subscribers) {
      subscriber.event(event);
    }
  }

  private static void dispatchResync(List<Subscriber> subscribers) {
    for (Subscriber subscriber : subscribers) {
      subscriber.resync();
    }
  }

  private void callbackStarted() {
    synchronized (sourceCallbackFence) {
      activeSourceCallbacks++;
    }
    sourceCallbackDepth.set(sourceCallbackDepth.get() + 1);
  }

  private void callbackFinished() {
    int depth = sourceCallbackDepth.get() - 1;
    if (depth == 0) {
      sourceCallbackDepth.remove();
    } else {
      sourceCallbackDepth.set(depth);
    }
    synchronized (sourceCallbackFence) {
      activeSourceCallbacks--;
      sourceCallbackFence.notifyAll();
    }
  }

  private void awaitSubscriberFences() {
    boolean interrupted = false;
    synchronized (sourceCallbackFence) {
      while (!subscriberFencesClosed) {
        try {
          sourceCallbackFence.wait();
        } catch (InterruptedException ignored) {
          interrupted = true;
        }
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private void awaitCallbacks(int callbacksOwnedByCurrentThread) {
    boolean interrupted = false;
    synchronized (sourceCallbackFence) {
      while (!subscriberFencesClosed || activeSourceCallbacks > callbacksOwnedByCurrentThread) {
        try {
          sourceCallbackFence.wait();
        } catch (InterruptedException ignored) {
          interrupted = true;
        }
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private final class Subscriber {

    private final UUID threadId;
    private final Consumer<RealtimeEvent> onEvent;
    private final Runnable onResync;
    private final Object callbackFence = new Object();
    private final ThreadLocal<Integer> callbackDepth = ThreadLocal.withInitial(() -> 0);

    private boolean closed;
    private int activeCallbacks;

    private Subscriber(UUID threadId, Consumer<RealtimeEvent> onEvent, Runnable onResync) {
      this.threadId = threadId;
      this.onEvent = onEvent;
      this.onResync = onResync;
    }

    private void event(RealtimeEvent event) {
      if (!startCallback()) {
        return;
      }
      try {
        onEvent.accept(event);
      } catch (RuntimeException error) {
        log.warn(
            "realtime subscriber callback failed for threadId={}; errorType={}; skipping",
            threadId,
            error.getClass().getSimpleName());
      } finally {
        finishCallback();
      }
    }

    private void resync() {
      if (!startCallback()) {
        return;
      }
      try {
        onResync.run();
      } catch (RuntimeException error) {
        log.warn(
            "realtime resync callback failed for threadId={}; errorType={}; skipping",
            threadId,
            error.getClass().getSimpleName());
      } finally {
        finishCallback();
      }
    }

    private void close() {
      disableAndAwaitCallbacks();
      synchronized (lifecycleFence) {
        List<Subscriber> subscribers = subscribersByThread.get(threadId);
        if (subscribers == null) {
          return;
        }
        subscribers.remove(this);
        if (subscribers.isEmpty()) {
          subscribersByThread.remove(threadId);
        }
      }
    }

    private boolean startCallback() {
      synchronized (callbackFence) {
        if (closed) {
          return false;
        }
        activeCallbacks++;
        callbackDepth.set(callbackDepth.get() + 1);
        BusRealtimeEventSource.this.callbackStarted();
        return true;
      }
    }

    private void finishCallback() {
      synchronized (callbackFence) {
        int depth = callbackDepth.get() - 1;
        if (depth == 0) {
          callbackDepth.remove();
        } else {
          callbackDepth.set(depth);
        }
        activeCallbacks--;
        callbackFence.notifyAll();
      }
      BusRealtimeEventSource.this.callbackFinished();
    }

    private void disable() {
      synchronized (callbackFence) {
        closed = true;
      }
    }

    private void disableAndAwaitCallbacks() {
      boolean interrupted = false;
      synchronized (callbackFence) {
        closed = true;
        if (callbackDepth.get() == 0) {
          while (activeCallbacks > 0) {
            try {
              callbackFence.wait();
            } catch (InterruptedException ignored) {
              interrupted = true;
            }
          }
        }
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
