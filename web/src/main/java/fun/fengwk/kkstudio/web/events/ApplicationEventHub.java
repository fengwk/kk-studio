package fun.fengwk.kkstudio.web.events;

import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.spring.realtime.RealtimeEventSource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 传输无关的事件通道 Hub：按资源维护本地订阅与上游生命周期，供 WebSocket 等传输层使用。
 *
 * <p>每个资源只有一组共享上游（Thread = version source + realtime source；Canvas = version source）：首个本地
 * 订阅建立上游，最后一个释放时关闭；重复订阅幂等由传输层保证。订阅原子返回建立瞬间的 durable cursor 作为 {@code subscribed} ack 游标——上游注册先于
 * cursor 读取，且 fan-out 与「读取 cursor + 注册订阅者」在同一把 资源锁内互斥，因此 ack cursor 之后的事件不因注册竞态丢失（cursor
 * 之前的由客户端随后拉取的 snapshot 覆盖）。
 *
 * <p>同一资源的状态（上游句柄、订阅者集合、early 缓冲）由该状态的监视器串行化；map 只做「生命周期围栏内创建/获取」与「状态锁内的 identity 条件删除」。{@link
 * #lifecycleFence} 只把「{@link #closed} 边界」与「向 map 发布新状态」串在同一把锁上：{@link #close()} 一旦设立 closed，之后的
 * {@link #subscribe} 不能再插入存活状态；排空已发布状态在围栏外进行，避免外部 close 回等待时再抢围栏。被最后释放、建立失败或 {@link #close()}
 * 淘汰的状态先标记 {@link ResourceState#retired} 再移出 map，之后的上游回调一律丢弃，绝不向 detached state 累积；订阅者若在锁内发现状态已
 * retired 则重取 新状态，因此不会在 detached state 上重建上游（避免 orphan upstream）。
 *
 * <p>订阅激活前（传输层尚未发出 ack 帧）到达的事件缓冲在订阅内，{@link Subscription#activate()} 后按到达顺序 投递，保证事件帧不先于 ack 帧。
 */
final class ApplicationEventHub implements AutoCloseable {

  enum ResourceKind {
    THREAD,
    CANVAS
  }

  record ResourceKey(ResourceKind kind, UUID id) {
    public ResourceKey {
      kind = Objects.requireNonNull(kind, "kind");
      id = Objects.requireNonNull(id, "id");
    }
  }

  /** 投递给传输层的资源信号。 */
  sealed interface Signal permits Signal.Version, Signal.Realtime, Signal.Resync {
    record Version(String version) implements Signal {}

    record Realtime(RealtimeEvent event) implements Signal {}

    record Resync() implements Signal {}
  }

  /** 传输层出口；回调不保证线程，且不得阻塞。 */
  interface Sink {
    void accept(Signal signal);
  }

  /** 本地订阅句柄。 */
  interface Subscription extends AutoCloseable {
    ResourceKey resource();

    /** subscribed ack 游标（订阅建立瞬间的 durable cursor）。 */
    long cursor();

    /** 传输层在 ack 帧入队后调用；此前到达的事件被缓冲。 */
    void activate();

    @Override
    void close();
  }

  private final ThreadVersionEventSource threadVersionSource;
  private final RealtimeEventSource realtimeSource;
  private final CanvasVersionEventSource canvasVersionSource;
  private final int maxBufferedSignals;
  private final Map<ResourceKey, ResourceState> resources = new ConcurrentHashMap<>();

  /**
   * 生命周期围栏：只串行化 {@link #subscribe} 的「检查 closed + 发布状态」与 {@link #close()} 的「设立 closed 边界」。
   * 排空已发布状态不持有此锁。
   */
  private final Object lifecycleFence = new Object();

  /** 关闭标记：仅在 {@link #lifecycleFence} 内由 false 转为 true；之后不得再发布新状态。 */
  private volatile boolean closed;

  /**
   * 单订阅待发缓冲与建立期 early 缓冲的最大信号数（由数据库 SystemSettings.Advanced.applicationEventQueueCapacity
   * 在装配时传入）；溢出折叠为单个 {@link Signal.Resync}，保证可恢复且内存有界。
   */
  ApplicationEventHub(
      ThreadVersionEventSource threadVersionSource,
      RealtimeEventSource realtimeSource,
      CanvasVersionEventSource canvasVersionSource,
      int maxBufferedSignals) {
    this.threadVersionSource = Objects.requireNonNull(threadVersionSource, "threadVersionSource");
    this.realtimeSource = Objects.requireNonNull(realtimeSource, "realtimeSource");
    this.canvasVersionSource = Objects.requireNonNull(canvasVersionSource, "canvasVersionSource");
    if (maxBufferedSignals <= 0) {
      throw new IllegalArgumentException("maxBufferedSignals must be positive");
    }
    this.maxBufferedSignals = maxBufferedSignals;
  }

  /**
   * 注册一个本地订阅并建立（或复用）资源上游。
   *
   * <p>先在 {@link #lifecycleFence} 内完成「检查 closed + 创建/获取状态」，再在状态锁内校验状态未被并发淘汰（retired 则重取新状态），按需建立上游
   * 并登记订阅者。建立失败会淘汰并移除刚创建的状态，不遗留 detached 状态。
   *
   * @throws IllegalArgumentException 资源不存在
   * @throws IllegalStateException hub 已关闭
   */
  Subscription subscribe(ResourceKey resource, Sink sink) {
    Objects.requireNonNull(resource, "resource");
    Objects.requireNonNull(sink, "sink");
    while (true) {
      ResourceState state;
      synchronized (lifecycleFence) {
        if (closed) {
          throw new IllegalStateException("hub is closed");
        }
        state =
            resources.compute(
                resource, (key, existing) -> existing != null ? existing : new ResourceState(key));
      }
      synchronized (state) {
        if (state.retired) {
          // 状态已被并发最后释放/hub close 淘汰并移出 map：放弃，重取新状态，绝不在 detached state 上重建上游。
          continue;
        }
        try {
          if (state.subscribers.isEmpty()) {
            establishUpstream(state);
          }
        } catch (RuntimeException error) {
          state.retired = true;
          resources.remove(resource, state);
          closeQuietly(state.threadVersionHandle);
          closeQuietly(state.realtimeHandle);
          closeQuietly(state.canvasVersionHandle);
          throw error;
        }
        LocalSubscription subscription =
            new LocalSubscription(this, resource, state.cursor, sink, maxBufferedSignals);
        state.subscribers.add(subscription);
        // 回放建立上游期间暂存的信号（establish 回调早于第一个订阅者加入）；deliver 按 cursor 过滤并缓冲到激活。
        for (Signal signal : state.early) {
          subscription.deliver(signal);
        }
        state.early.clear();
        state.earlyCollapsed = false;
        return subscription;
      }
    }
  }

  /**
   * 释放全部资源上游（应用关闭/测试）。幂等；关闭后 {@link #subscribe} 抛 {@link IllegalStateException}，已发放订阅的
   * release/activate 均为安全 no-op（订阅已被标记关闭）。
   */
  @Override
  public void close() {
    synchronized (lifecycleFence) {
      if (closed) {
        return;
      }
      closed = true;
    }
    // 边界已设立：此后不能再发布新状态。已发布条目仍可见，在状态锁内淘汰并关闭上游。
    while (!resources.isEmpty()) {
      for (ResourceState state : resources.values()) {
        synchronized (state) {
          if (state.retired) {
            continue;
          }
          state.retired = true;
          resources.remove(state.key, state);
          closeQuietly(state.threadVersionHandle);
          closeQuietly(state.realtimeHandle);
          closeQuietly(state.canvasVersionHandle);
          for (LocalSubscription subscription : state.subscribers) {
            subscription.markClosed();
          }
          state.subscribers.clear();
        }
      }
    }
  }

  private void establishUpstream(ResourceState state) {
    switch (state.key.kind()) {
      case THREAD -> {
        SourceSubscribed version =
            threadVersionSource.subscribe(state.key.id(), event -> fanoutVersion(state, event));
        state.threadVersionHandle = version.handle();
        state.realtimeHandle =
            realtimeSource.subscribe(
                state.key.id(),
                event -> fanout(state, new Signal.Realtime(event)),
                () -> fanout(state, new Signal.Resync()));
        state.cursor = version.cursor();
      }
      case CANVAS -> {
        SourceSubscribed version =
            canvasVersionSource.subscribe(state.key.id(), event -> fanoutVersion(state, event));
        state.canvasVersionHandle = version.handle();
        state.cursor = version.cursor();
      }
    }
  }

  private void fanoutVersion(ResourceState state, ThreadVersionEventSource.Event event) {
    if (event.resync()) {
      fanout(state, new Signal.Resync());
    } else {
      fanout(state, new Signal.Version(event.version()));
    }
  }

  private void fanoutVersion(ResourceState state, CanvasVersionEventSource.Event event) {
    if (event.resync()) {
      fanout(state, new Signal.Resync());
    } else {
      fanout(state, new Signal.Version(Long.toString(event.version())));
    }
  }

  /**
   * 在资源锁内 fan-out：与「读 cursor + 注册订阅者」互斥，保证 ack 后无注册竞态窗口。 没有订阅者时（establish 上游期间的回调可能先于第一个
   * LocalSubscription 加入）信号暂存到 {@link ResourceState#early}，由随后加入的订阅者按 cursor 过滤回放；early
   * 超过缓冲上限时清空并折叠为单个 {@link Signal.Resync}，之后不再累积（首订阅前内存有界且可恢复）。 retired 状态（最后释放/建立失败/hub close
   * 淘汰）的迟到回调直接丢弃，不向 detached state 累积。
   */
  private void fanout(ResourceState state, Signal signal) {
    synchronized (state) {
      if (state.retired) {
        return;
      }
      if (state.subscribers.isEmpty()) {
        if (state.earlyCollapsed) {
          return;
        }
        if (state.early.size() >= maxBufferedSignals) {
          state.early.clear();
          state.early.add(new Signal.Resync());
          state.earlyCollapsed = true;
          return;
        }
        state.early.add(signal);
        return;
      }
      for (LocalSubscription subscription : state.subscribers) {
        subscription.deliver(signal);
      }
    }
  }

  private void release(LocalSubscription subscription) {
    ResourceState state = resources.get(subscription.resource);
    if (state == null) {
      return; // 已被最后释放/hub close 清理（幂等）
    }
    synchronized (state) {
      if (state.retired) {
        return; // 并发清理已生效（幂等）
      }
      if (!state.subscribers.remove(subscription)) {
        return; // 重复释放
      }
      subscription.markClosed();
      if (state.subscribers.isEmpty()) {
        state.retired = true;
        // 先移除 map entry 再关闭上游：并发 subscribe 只能取得全新状态，不会复用正在退役的旧状态。
        resources.remove(subscription.resource, state);
        closeQuietly(state.threadVersionHandle);
        closeQuietly(state.realtimeHandle);
        closeQuietly(state.canvasVersionHandle);
      }
    }
  }

  private static void closeQuietly(AutoCloseable handle) {
    if (handle == null) {
      return;
    }
    try {
      handle.close();
    } catch (Exception error) {
      // 释放失败不影响其余订阅
    }
  }

  private static final class ResourceState {
    private final ResourceKey key;
    private final Set<LocalSubscription> subscribers = new HashSet<>();
    private final List<Signal> early = new ArrayList<>();
    private AutoCloseable threadVersionHandle;
    private AutoCloseable realtimeHandle;
    private AutoCloseable canvasVersionHandle;
    private long cursor;

    /** 已淘汰（最后释放/建立失败/hub close）：上游回调一律丢弃；只在状态锁内读写。 */
    private boolean retired;

    /** early 已折叠为单个 Resync：首订阅加入前不再累积；只在状态锁内读写。 */
    private boolean earlyCollapsed;

    private ResourceState(ResourceKey key) {
      this.key = key;
    }
  }

  private static final class LocalSubscription implements Subscription {
    private final ApplicationEventHub hub;
    private final ResourceKey resource;
    private final long cursor;
    private final int maxBufferedSignals;
    private final Sink sink;
    private final Object lock = new Object();
    private boolean active;
    private boolean closed;
    private final ArrayDeque<Signal> pending = new ArrayDeque<>();

    /** pending 已折叠为单个 Resync：激活前不再累积；只在 subscription lock 内读写。 */
    private boolean pendingCollapsed;

    private LocalSubscription(
        ApplicationEventHub hub,
        ResourceKey resource,
        long cursor,
        Sink sink,
        int maxBufferedSignals) {
      this.hub = hub;
      this.resource = resource;
      this.cursor = cursor;
      this.sink = sink;
      this.maxBufferedSignals = maxBufferedSignals;
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
      synchronized (lock) {
        if (closed) {
          return;
        }
        active = true;
        pendingCollapsed = false;
        while (!pending.isEmpty()) {
          sink.accept(pending.poll());
        }
      }
    }

    @Override
    public void close() {
      hub.release(this);
    }

    /** 由 hub 在资源锁内调用：标记关闭并丢弃缓冲。 */
    private void markClosed() {
      synchronized (lock) {
        closed = true;
        pending.clear();
        pendingCollapsed = false;
      }
    }

    /** 投递信号；version 信号过滤掉不晚于 ack cursor 的陈旧值。 */
    private void deliver(Signal signal) {
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
  }
}
