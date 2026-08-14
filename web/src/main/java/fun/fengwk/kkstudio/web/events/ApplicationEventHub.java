package fun.fengwk.kkstudio.web.events;

import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.spring.redis.RealtimeEventSource;

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
 * <p>每个资源只有一组共享上游（Thread = revision source + realtime source；Canvas = version source）：首个本地
 * 订阅建立上游，最后一个释放时关闭；重复订阅幂等由传输层保证。订阅原子返回建立瞬间的 durable cursor 作为 {@code subscribed} ack 游标——上游注册先于
 * cursor 读取，且 fan-out 与「读取 cursor + 注册订阅者」在同一把 资源锁内互斥，因此 ack cursor 之后的事件不因注册竞态丢失（cursor
 * 之前的由客户端随后拉取的 snapshot/changes 覆盖）。
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
  sealed interface Signal permits Signal.Revision, Signal.Realtime, Signal.Version, Signal.Resync {
    record Revision(String revision) implements Signal {}

    record Realtime(RealtimeEvent event) implements Signal {}

    record Version(long version) implements Signal {}

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

  private final ThreadRevisionEventSource revisionSource;
  private final RealtimeEventSource realtimeSource;
  private final CanvasVersionEventSource versionSource;
  private final Map<ResourceKey, ResourceState> resources = new ConcurrentHashMap<>();

  ApplicationEventHub(
      ThreadRevisionEventSource revisionSource,
      RealtimeEventSource realtimeSource,
      CanvasVersionEventSource versionSource) {
    this.revisionSource = Objects.requireNonNull(revisionSource, "revisionSource");
    this.realtimeSource = Objects.requireNonNull(realtimeSource, "realtimeSource");
    this.versionSource = Objects.requireNonNull(versionSource, "versionSource");
  }

  /**
   * 注册一个本地订阅并建立（或复用）资源上游。
   *
   * @throws IllegalArgumentException 资源不存在
   */
  Subscription subscribe(ResourceKey resource, Sink sink) {
    Objects.requireNonNull(resource, "resource");
    Objects.requireNonNull(sink, "sink");
    ResourceState state = resources.computeIfAbsent(resource, ResourceState::new);
    synchronized (state) {
      try {
        if (state.subscribers.isEmpty()) {
          establishUpstream(state);
        }
      } catch (RuntimeException error) {
        closeQuietly(state.revisionHandle);
        closeQuietly(state.realtimeHandle);
        closeQuietly(state.versionHandle);
        resources.remove(resource, state);
        throw error;
      }
      LocalSubscription subscription = new LocalSubscription(this, resource, state.cursor, sink);
      state.subscribers.add(subscription);
      // 回放建立上游期间暂存的信号（establish 回调早于第一个订阅者加入）；deliver 按 cursor 过滤并缓冲到激活。
      for (Signal signal : state.early) {
        subscription.deliver(signal);
      }
      state.early.clear();
      return subscription;
    }
  }

  /** 释放全部资源上游（应用关闭/测试）。 */
  @Override
  public void close() {
    for (ResourceState state : resources.values()) {
      synchronized (state) {
        closeQuietly(state.revisionHandle);
        closeQuietly(state.realtimeHandle);
        closeQuietly(state.versionHandle);
        state.subscribers.clear();
      }
    }
    resources.clear();
  }

  private void establishUpstream(ResourceState state) {
    switch (state.key.kind()) {
      case THREAD -> {
        SourceSubscribed revision =
            revisionSource.subscribe(state.key.id(), event -> fanoutRevision(state, event));
        state.revisionHandle = revision.handle();
        state.realtimeHandle =
            realtimeSource.subscribe(
                state.key.id(),
                event -> fanout(state, new Signal.Realtime(event)),
                () -> fanout(state, new Signal.Resync()));
        state.cursor = revision.cursor();
      }
      case CANVAS -> {
        SourceSubscribed version =
            versionSource.subscribe(state.key.id(), event -> fanoutVersion(state, event));
        state.versionHandle = version.handle();
        state.cursor = version.cursor();
      }
    }
  }

  private void fanoutRevision(ResourceState state, ThreadRevisionEventSource.Event event) {
    if (event.resync()) {
      fanout(state, new Signal.Resync());
    } else {
      fanout(state, new Signal.Revision(event.revision()));
    }
  }

  private void fanoutVersion(ResourceState state, CanvasVersionEventSource.Event event) {
    if (event.resync()) {
      fanout(state, new Signal.Resync());
    } else {
      fanout(state, new Signal.Version(event.version()));
    }
  }

  /**
   * 在资源锁内 fan-out：与「读 cursor + 注册订阅者」互斥，保证 ack 后无注册竞态窗口。 没有订阅者时（establish 上游期间的回调可能先于第一个
   * LocalSubscription 加入）信号暂存到 {@link ResourceState#early}，由随后加入的订阅者按 cursor 过滤回放。
   */
  private void fanout(ResourceState state, Signal signal) {
    synchronized (state) {
      if (state.subscribers.isEmpty()) {
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
      return;
    }
    synchronized (state) {
      if (!state.subscribers.remove(subscription)) {
        return;
      }
      subscription.markClosed();
      if (state.subscribers.isEmpty()) {
        closeQuietly(state.revisionHandle);
        closeQuietly(state.realtimeHandle);
        closeQuietly(state.versionHandle);
        resources.remove(state.key, state);
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
    private AutoCloseable revisionHandle;
    private AutoCloseable realtimeHandle;
    private AutoCloseable versionHandle;
    private long cursor;

    private ResourceState(ResourceKey key) {
      this.key = key;
    }
  }

  private static final class LocalSubscription implements Subscription {
    private final ApplicationEventHub hub;
    private final ResourceKey resource;
    private final long cursor;
    private final Sink sink;
    private final Object lock = new Object();
    private boolean active;
    private boolean closed;
    private final ArrayDeque<Signal> pending = new ArrayDeque<>();

    private LocalSubscription(
        ApplicationEventHub hub, ResourceKey resource, long cursor, Sink sink) {
      this.hub = hub;
      this.resource = resource;
      this.cursor = cursor;
      this.sink = sink;
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
      }
    }

    /** 投递信号；revision/version 信号过滤掉不晚于 ack cursor 的陈旧值。 */
    private void deliver(Signal signal) {
      if (signal instanceof Signal.Revision revision) {
        if (Long.parseLong(revision.revision()) <= cursor) {
          return;
        }
      } else if (signal instanceof Signal.Version version) {
        if (version.version() <= cursor) {
          return;
        }
      }
      synchronized (lock) {
        if (closed) {
          return;
        }
        if (!active) {
          pending.add(signal);
          return;
        }
      }
      sink.accept(signal);
    }
  }
}
