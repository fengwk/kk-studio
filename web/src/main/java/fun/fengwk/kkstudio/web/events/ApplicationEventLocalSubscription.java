package fun.fengwk.kkstudio.web.events;

import fun.fengwk.kkstudio.web.events.ApplicationEventHub.ResourceKey;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.Signal;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.Sink;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.Subscription;

import java.util.ArrayDeque;

/**
 * 一个本地订阅：激活前缓冲信号，激活后按到达顺序投递，关闭后丢弃全部缓冲。
 *
 * <p>{@link #deliver} 的 Canvas revision 过滤在锁外完成（cursor 不可变），其余判断与缓冲都在本订阅的锁内；{@link #markClosed()} 由
 * hub 在资源状态锁内调用，因此关闭与投递之间不存在半可见状态。
 */
final class ApplicationEventLocalSubscription implements Subscription {

  private final ResourceKey resource;
  private final long cursor;
  private final int maxBufferedSignals;
  private final Sink sink;
  private final Release release;
  private final Runnable onActivate;
  private final Object lock = new Object();
  private boolean active;
  private boolean closed;
  private final ArrayDeque<Signal> pending = new ArrayDeque<>();

  /** pending 已折叠为单个 Resync：激活前不再累积；只在 subscription lock 内读写。 */
  private boolean pendingCollapsed;

  ApplicationEventLocalSubscription(
      ResourceKey resource,
      long cursor,
      Sink sink,
      int maxBufferedSignals,
      Release release,
      Runnable onActivate) {
    this.resource = resource;
    this.cursor = cursor;
    this.sink = sink;
    this.maxBufferedSignals = maxBufferedSignals;
    this.release = release;
    this.onActivate = onActivate;
  }

  @Override
  public ResourceKey resource() {
    return resource;
  }

  @Override
  public long cursor() {
    return cursor;
  }

  @Override
  public void activate() {
    ArrayDeque<Signal> buffered;
    synchronized (lock) {
      if (closed) {
        return;
      }
      active = true;
      pendingCollapsed = false;
      buffered = new ArrayDeque<>(pending);
      pending.clear();
    }
    while (!buffered.isEmpty()) {
      sink.accept(buffered.poll());
    }
    onActivate.run();
  }

  @Override
  public void close() {
    release.release(this);
  }

  /** 由 hub 在资源锁内调用：标记关闭并丢弃缓冲。 */
  void markClosed() {
    synchronized (lock) {
      closed = true;
      pending.clear();
      pendingCollapsed = false;
    }
  }

  /** 投递信号；Canvas revision 信号过滤掉不晚于 ack cursor 的陈旧值。 */
  void deliver(Signal signal) {
    if (signal instanceof Signal.Version version) {
      if (Long.parseLong(version.version()) <= cursor) {
        return;
      }
    }
    synchronized (lock) {
      if (closed) {
        return;
      }
      if (!active) {
        // 激活前缓冲有界：溢出清空并折叠为单个 Resync（客户端整体快照恢复），之后不再累积。
        if (pendingCollapsed) {
          return;
        }
        if (pending.size() >= maxBufferedSignals) {
          pending.clear();
          pending.add(new Signal.Resync());
          pendingCollapsed = true;
          return;
        }
        pending.add(signal);
        return;
      }
    }
    sink.accept(signal);
  }

  /** 订阅关闭回调：由 hub 实现资源释放语义。 */
  @FunctionalInterface
  interface Release {

    void release(ApplicationEventLocalSubscription subscription);
  }
}
