package fun.fengwk.kkstudio.web.events;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.canvas.notification.CanvasVersionEventSource;
import fun.fengwk.kkstudio.harness.api.HarnessChangeSubscription;
import fun.fengwk.kkstudio.harness.api.HarnessChangeSubscriptions;
import fun.fengwk.kkstudio.harness.api.HarnessInvalidationChanges;
import fun.fengwk.kkstudio.harness.api.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.platform.cluster.RealtimeSubscriptionService;
import fun.fengwk.kkstudio.platform.events.SourceSubscribed;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadSnapshotDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadTreeNodeDTO;
import fun.fengwk.kkstudio.platform.harness.realtime.RealtimeEventSource;
import fun.fengwk.kkstudio.platform.notification.NotificationInvalidationSource;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.ResourceKey;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.Signal;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * 传输无关的事件通道 Hub：按资源维护本地订阅与上游生命周期，供 WebSocket 等传输层使用。
 *
 * <p>每个资源只有一组共享上游：Thread 使用快照失效订阅 + realtime source，Canvas 使用 version
 * source，Projects、Interactions、Environments 使用全局失效 source，Tree 使用按执行根的失效
 * source。首个本地订阅建立上游，最后一个释放时关闭；重复订阅幂等由传输层保证。订阅原子返回建立瞬间的 durable cursor 作为 {@code subscribed} ack
 * 游标（全局提示型资源固定为 0，无持久游标的 Tree 资源同样为 0）。
 *
 * <p>同一资源的状态（上游句柄、订阅者集合、early 缓冲）由 {@link ApplicationEventResourceState} 的监视器串行化；map
 * 只做「生命周期围栏内创建/获取」与「状态锁内的 identity 条件删除」。{@link #lifecycleFence} 只把「{@link #closed} 边界」与「向 map
 * 发布新状态」串在同一把锁上：{@link #close()} 一旦设立 closed，之后的 {@link #subscribe} 不能再插入存活状态；排空已发布状态在围栏外进行，避免外部
 * close 回等待时再抢围栏。被最后释放、建立失败或 {@link #close()} 淘汰的状态先标记 {@link
 * ApplicationEventResourceState#retired} 再移出 map，之后的上游回调一律丢弃，绝不向 detached state 累积；订阅者若在锁内发现状态已
 * retired 则重取新状态，因此不会在 detached state 上重建上游（避免 orphan upstream）。
 *
 * <p>订阅激活前（传输层尚未发出 ack 帧）到达的事件缓冲在订阅内，{@link Subscription#activate()} 后按到达顺序 投递，保证事件帧不先于 ack 帧。
 */
@Slf4j
final class ApplicationEventHub implements AutoCloseable {

  enum ResourceKind {
    THREAD,
    CANVAS,
    THREAD_TREE,
    INTERACTIONS,
    ENVIRONMENTS
  }

  record ResourceKey(ResourceKind kind, UUID id) {
    public ResourceKey {
      kind = Objects.requireNonNull(kind, "kind");
      if (requiresId(kind) && id == null) {
        throw new NullPointerException("id");
      }
      if (!requiresId(kind) && id != null) {
        throw new IllegalArgumentException("global resource must not have an id");
      }
    }

    private static boolean requiresId(ResourceKind kind) {
      return kind == ResourceKind.THREAD
          || kind == ResourceKind.CANVAS
          || kind == ResourceKind.THREAD_TREE;
    }
  }

  /** 投递给传输层的资源信号。 */
  sealed interface Signal
      permits Signal.Version,
          Signal.ThreadSnapshot,
          Signal.ThreadSnapshotFailed,
          Signal.Realtime,
          Signal.ThreadTreeChanged,
          Signal.ThreadTreeSnapshot,
          Signal.ThreadTreeSnapshotFailed,
          Signal.InteractionsChanged,
          Signal.EnvironmentChanged,
          Signal.Resync {
    record Version(String version) implements Signal {}

    record ThreadSnapshot(HarnessThreadSnapshotDTO snapshot) implements Signal {}

    record ThreadSnapshotFailed() implements Signal {}

    record Realtime(RealtimeEvent event) implements Signal {}

    /** 该根的执行树需要回读；资源本身已带根 id。 */
    record ThreadTreeChanged() implements Signal {}

    /** 小体积完整权威树快照；不附加 Thread.version 游标。 */
    record ThreadTreeSnapshot(List<HarnessThreadTreeNodeDTO> nodes) implements Signal {
      public ThreadTreeSnapshot {
        nodes = List.copyOf(nodes);
      }
    }

    /** 执行树权威快照生成或投递失败；保留客户端旧缓存，不触发自动 resync。 */
    record ThreadTreeSnapshotFailed() implements Signal {}

    /** 执行根的待处理交互需要回读（全局资源，data 携带真实执行根）。 */
    record InteractionsChanged(UUID rootThreadId) implements Signal {
      public InteractionsChanged {
        rootThreadId = Objects.requireNonNull(rootThreadId, "rootThreadId");
      }
    }

    /** Environment 连接状态需要回读（全局资源）。 */
    record EnvironmentChanged() implements Signal {}

    record Resync() implements Signal {}
  }

  /** 传输层出口；回调不保证线程，且不得阻塞。 */
  interface Sink {
    void accept(Signal signal);
  }

  /** 本地订阅句柄。 */
  interface Subscription extends AutoCloseable {
    ResourceKey resource();

    /** subscribed ACK cursor; Thread snapshot subscriptions use zero. */
    long cursor();

    /** 传输层在 ack 帧入队后调用；此前到达的事件被缓冲。 */
    void activate();

    @Override
    void close();
  }

  private final HarnessChangeSubscriptions harnessChanges;
  private final RealtimeEventSource realtimeSource;
  private final RealtimeSubscriptionService realtimeSubscriptions;
  private final CanvasVersionEventSource canvasVersionSource;
  private final NotificationInvalidationSource environmentChangeSource;
  private final IntSupplier maxBufferedSignals;
  private final ThreadTreeSnapshotPublisher threadTreeSnapshotPublisher;
  private final ThreadSnapshotPublisher threadSnapshotPublisher;
  private final Consumer<UUID> threadValidator;
  private final Consumer<UUID> threadTreeValidator;
  private final Map<ResourceKey, ApplicationEventResourceState> resources =
      new ConcurrentHashMap<>();

  /**
   * 生命周期围栏：只串行化 {@link #subscribe} 的「检查 closed + 发布状态」与 {@link #close()} 的「设立 closed 边界」。
   * 排空已发布状态不持有此锁。
   */
  private final Object lifecycleFence = new Object();

  /** 关闭标记：仅在 {@link #lifecycleFence} 内由 false 转为 true；之后不得再发布新状态。 */
  private volatile boolean closed;

  /** 单订阅待发缓冲与建立期 early 缓冲的最大信号数（每次创建订阅时读取当前配置）；溢出折叠为单个 {@link Signal.Resync}，保证可恢复且内存有界。 */
  ApplicationEventHub(
      HarnessChangeSubscriptions harnessChanges,
      RealtimeEventSource realtimeSource,
      RealtimeSubscriptionService realtimeSubscriptions,
      CanvasVersionEventSource canvasVersionSource,
      NotificationInvalidationSource environmentChangeSource,
      IntSupplier maxBufferedSignals) {
    this(
        harnessChanges,
        realtimeSource,
        realtimeSubscriptions,
        canvasVersionSource,
        environmentChangeSource,
        maxBufferedSignals,
        null,
        null,
        null);
  }

  ApplicationEventHub(
      HarnessChangeSubscriptions harnessChanges,
      RealtimeEventSource realtimeSource,
      RealtimeSubscriptionService realtimeSubscriptions,
      CanvasVersionEventSource canvasVersionSource,
      NotificationInvalidationSource environmentChangeSource,
      IntSupplier maxBufferedSignals,
      ThreadTreeSnapshotPublisher threadTreeSnapshotPublisher) {
    this(
        harnessChanges,
        realtimeSource,
        realtimeSubscriptions,
        canvasVersionSource,
        environmentChangeSource,
        maxBufferedSignals,
        threadTreeSnapshotPublisher,
        null);
  }

  ApplicationEventHub(
      HarnessChangeSubscriptions harnessChanges,
      RealtimeEventSource realtimeSource,
      RealtimeSubscriptionService realtimeSubscriptions,
      CanvasVersionEventSource canvasVersionSource,
      NotificationInvalidationSource environmentChangeSource,
      IntSupplier maxBufferedSignals,
      ThreadTreeSnapshotPublisher threadTreeSnapshotPublisher,
      ThreadSnapshotPublisher threadSnapshotPublisher) {
    this(
        harnessChanges,
        realtimeSource,
        realtimeSubscriptions,
        canvasVersionSource,
        environmentChangeSource,
        maxBufferedSignals,
        threadTreeSnapshotPublisher,
        threadSnapshotPublisher,
        null);
  }

  ApplicationEventHub(
      HarnessChangeSubscriptions harnessChanges,
      RealtimeEventSource realtimeSource,
      RealtimeSubscriptionService realtimeSubscriptions,
      CanvasVersionEventSource canvasVersionSource,
      NotificationInvalidationSource environmentChangeSource,
      IntSupplier maxBufferedSignals,
      ThreadTreeSnapshotPublisher threadTreeSnapshotPublisher,
      ThreadSnapshotPublisher threadSnapshotPublisher,
      Consumer<UUID> threadValidator) {
    this(
        harnessChanges,
        realtimeSource,
        realtimeSubscriptions,
        canvasVersionSource,
        environmentChangeSource,
        maxBufferedSignals,
        threadTreeSnapshotPublisher,
        threadSnapshotPublisher,
        threadValidator,
        null);
  }

  ApplicationEventHub(
      HarnessChangeSubscriptions harnessChanges,
      RealtimeEventSource realtimeSource,
      RealtimeSubscriptionService realtimeSubscriptions,
      CanvasVersionEventSource canvasVersionSource,
      NotificationInvalidationSource environmentChangeSource,
      IntSupplier maxBufferedSignals,
      ThreadTreeSnapshotPublisher threadTreeSnapshotPublisher,
      ThreadSnapshotPublisher threadSnapshotPublisher,
      Consumer<UUID> threadValidator,
      Consumer<UUID> threadTreeValidator) {
    this.harnessChanges = Objects.requireNonNull(harnessChanges, "harnessChanges");
    this.realtimeSource = Objects.requireNonNull(realtimeSource, "realtimeSource");
    this.realtimeSubscriptions =
        Objects.requireNonNull(realtimeSubscriptions, "realtimeSubscriptions");
    this.canvasVersionSource = Objects.requireNonNull(canvasVersionSource, "canvasVersionSource");
    this.environmentChangeSource =
        Objects.requireNonNull(environmentChangeSource, "environmentChangeSource");
    this.maxBufferedSignals = Objects.requireNonNull(maxBufferedSignals, "maxBufferedSignals");
    this.threadTreeSnapshotPublisher = threadTreeSnapshotPublisher;
    this.threadSnapshotPublisher = threadSnapshotPublisher;
    this.threadValidator = threadValidator;
    this.threadTreeValidator = threadTreeValidator;
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
    return subscribe(resource, sink, null);
  }

  Subscription subscribe(ResourceKey resource, Sink sink, UUID afterEntryId) {
    Objects.requireNonNull(resource, "resource");
    Objects.requireNonNull(sink, "sink");
    if (resource.kind() != ResourceKind.THREAD && afterEntryId != null) {
      throw new IllegalArgumentException("afterEntryId is only valid for threads");
    }
    if (resource.kind() == ResourceKind.THREAD && threadValidator != null) {
      threadValidator.accept(resource.id());
    }
    if (resource.kind() == ResourceKind.THREAD_TREE && threadTreeValidator != null) {
      threadTreeValidator.accept(resource.id());
    }
    while (true) {
      ApplicationEventResourceState state;
      synchronized (lifecycleFence) {
        if (closed) {
          throw new IllegalStateException("hub is closed");
        }
        state =
            resources.compute(
                resource,
                (key, existing) ->
                    existing != null ? existing : new ApplicationEventResourceState(key));
      }
      ApplicationEventLocalSubscription subscription;
      ThreadTreeSnapshotPublisher.Handle snapshotHandle;
      ThreadSnapshotPublisher.Handle[] threadHandle = new ThreadSnapshotPublisher.Handle[1];
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
          closeUpstreams(state);
          throw error;
        }
        int maxSignals = maxBufferedSignals.getAsInt();
        if (maxSignals <= 0) {
          throw new IllegalStateException("application event queue capacity must be positive");
        }
        subscription =
            new ApplicationEventLocalSubscription(
                resource,
                state.cursor,
                sink,
                maxSignals,
                this::release,
                () -> {
                  if (threadHandle[0] != null) {
                    threadHandle[0].activate();
                  }
                });
        state.subscribers.add(subscription);
        if (resource.kind() == ResourceKind.THREAD && threadSnapshotPublisher != null) {
          threadHandle[0] =
              threadSnapshotPublisher.subscribe(
                  resource.id(),
                  afterEntryId,
                  snapshot -> subscription.deliver(new Signal.ThreadSnapshot(snapshot)),
                  () -> subscription.deliver(new Signal.ThreadSnapshotFailed()));
          state.threadSnapshotHandles.put(subscription, threadHandle[0]);
        }
        // 回放建立上游期间暂存的信号（establish 回调早于第一个订阅者加入）；deliver 按 cursor 过滤并缓冲到激活。
        for (Signal signal : state.early) {
          subscription.deliver(signal);
        }
        state.early.clear();
        state.earlyCollapsed = false;
        snapshotHandle = state.threadTreeSnapshotHandle;
      }
      // 每个本地 thread-tree listener 都需首帧；同 root 的 publisher 会合并并只 fan-out 一份快照。
      if (snapshotHandle != null) {
        snapshotHandle.request(true);
      }
      return subscription;
    }
  }

  /** Force a complete snapshot only for the subscriber requesting it. */
  boolean requestThreadSnapshot(Subscription subscription) {
    ResourceKey resource = subscription.resource();
    if (resource.kind() != ResourceKind.THREAD) {
      return false;
    }
    ApplicationEventResourceState state = resources.get(resource);
    if (state == null) {
      return false;
    }
    ThreadSnapshotPublisher.Handle handle;
    synchronized (state) {
      handle = state.retired ? null : state.threadSnapshotHandles.get(subscription);
    }
    if (handle == null) {
      return false;
    }
    handle.request(true);
    return true;
  }

  boolean requestThreadTreeSnapshot(ResourceKey resource) {
    if (resource.kind() != ResourceKind.THREAD_TREE) {
      return false;
    }
    ApplicationEventResourceState state = resources.get(resource);
    if (state == null) {
      return false;
    }
    ThreadTreeSnapshotPublisher.Handle handle;
    synchronized (state) {
      if (state.retired || state.subscribers.isEmpty()) {
        return false;
      }
      handle = state.threadTreeSnapshotHandle;
    }
    if (handle == null) {
      return false;
    }
    handle.request(true);
    return true;
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
    // Boundary is set: no state can be published after the map is drained.
    for (ApplicationEventResourceState state : List.copyOf(resources.values())) {
      synchronized (state) {
        if (state.retired) {
          continue;
        }
        state.retired = true;
        resources.remove(state.key, state);
        for (ApplicationEventLocalSubscription subscription : state.subscribers) {
          subscription.markClosed();
        }
        state.subscribers.clear();
      }
      closeUpstreams(state);
      unregisterRealtimeSubscription(state);
    }
  }

  private void establishUpstream(ApplicationEventResourceState state) {
    switch (state.key.kind()) {
      case THREAD -> {
        HarnessChangeSubscription snapshots =
            harnessChanges
                .threadSnapshots()
                .subscribe(state.key.id(), ignored -> requestThreadSnapshots(state));
        state.threadSnapshotInvalidationHandle = snapshots;
        state.threadEnvironmentInvalidationRelease =
            environmentChangeSource.subscribe(null, ignored -> requestThreadSnapshots(state));
        state.realtimeHandle =
            realtimeSource.subscribe(
                state.key.id(),
                event -> fanout(state, new Signal.Realtime(event)),
                () -> fanout(state, new Signal.Resync()));
        registerRealtimeSubscription(state);
        state.cursor = 0;
      }
      case CANVAS -> {
        SourceSubscribed version =
            canvasVersionSource.subscribe(state.key.id(), event -> fanoutVersion(state, event));
        state.canvasVersionHandle = version.handle();
        state.cursor = version.cursor();
      }
      case THREAD_TREE -> {
        HarnessChangeSubscription subscription =
            harnessChanges
                .executionTree()
                .subscribe(state.key.id(), event -> onExecutionTreeChange(state, event));
        state.invalidationRelease = subscription::close;
        if (threadTreeSnapshotPublisher != null) {
          state.threadTreeSnapshotHandle =
              threadTreeSnapshotPublisher.subscribe(
                  state.key.id(),
                  nodes -> fanout(state, new Signal.ThreadTreeSnapshot(nodes)),
                  () -> fanout(state, new Signal.ThreadTreeSnapshotFailed()));
        }
      }
      case INTERACTIONS -> {
        HarnessChangeSubscription subscription =
            harnessChanges
                .interactions()
                .subscribe(null, event -> fanout(state, interactionSignal(event)));
        state.invalidationRelease = subscription::close;
      }
      case ENVIRONMENTS -> state.invalidationRelease =
          environmentChangeSource.subscribe(null, event -> fanoutInvalidation(state, event));
    }
  }

  /** LISTEN reconnect lost hints, not delivered snapshots; keep each subscriber's known base. */
  private void requestThreadSnapshots(ApplicationEventResourceState state) {
    List<ThreadSnapshotPublisher.Handle> handles;
    synchronized (state) {
      if (state.retired) {
        return;
      }
      handles = List.copyOf(state.threadSnapshotHandles.values());
    }
    for (ThreadSnapshotPublisher.Handle handle : handles) {
      handle.request(false);
    }
  }

  /** 执行树按资源自带根 id 定点失效，事件本身不携带额外坐标。 */
  private void onExecutionTreeChange(
      ApplicationEventResourceState state, HarnessInvalidationChanges.Event event) {
    if (threadTreeSnapshotPublisher == null || state.threadTreeSnapshotHandle == null) {
      fanout(state, executionTreeSignal(event));
      return;
    }
    state.threadTreeSnapshotHandle.request(
        event instanceof HarnessInvalidationChanges.Event.Resync);
  }

  private static Signal executionTreeSignal(HarnessInvalidationChanges.Event event) {
    if (event instanceof HarnessInvalidationChanges.Event.Changed) {
      return new Signal.ThreadTreeChanged();
    }
    return new Signal.Resync();
  }

  /** 待处理交互是全局资源：changed 携带真实执行根，resync 要求整体回读。 */
  private static Signal interactionSignal(HarnessInvalidationChanges.Event event) {
    if (event instanceof HarnessInvalidationChanges.Event.Changed changed) {
      return new Signal.InteractionsChanged(changed.key());
    }
    return new Signal.Resync();
  }

  /** 提示型资源的通用失效信号按资源种类折叠为最小回读信号。 */
  private void fanoutInvalidation(
      ApplicationEventResourceState state, NotificationInvalidationSource.Event event) {
    if (event instanceof NotificationInvalidationSource.Event.Resync) {
      fanout(state, new Signal.Resync());
      return;
    }
    NotificationInvalidationSource.Event.Changed changed =
        (NotificationInvalidationSource.Event.Changed) event;
    switch (state.key.kind()) {
      case ENVIRONMENTS -> fanout(state, new Signal.EnvironmentChanged());
      default -> throw new IllegalStateException("unexpected invalidation resource: " + state.key);
    }
  }

  private void fanoutVersion(
      ApplicationEventResourceState state, CanvasVersionEventSource.Event event) {
    if (event.resync()) {
      fanout(state, new Signal.Resync());
    } else {
      fanout(state, new Signal.Version(Long.toString(event.revision())));
    }
  }

  /**
   * 在资源锁内捕获订阅集合，锁外投递；关闭与投递由各订阅及传输层围栏裁决。没有订阅者时（establish 上游期间的回调可能先于第一个 LocalSubscription 加入）信号暂存到
   * {@link ApplicationEventResourceState#early}，由随后加入的订阅者按 cursor 过滤回放；early 超过缓冲上限时清空并折叠为单个 {@link
   * Signal.Resync}，之后不再累积（首订阅前内存有界且可恢复）。 retired 状态（最后释放/建立失败/hub close 淘汰）的迟到回调直接丢弃，不向 detached
   * state 累积。
   */
  private void fanout(ApplicationEventResourceState state, Signal signal) {
    List<ApplicationEventLocalSubscription> recipients;
    synchronized (state) {
      if (state.retired) {
        return;
      }
      if (state.subscribers.isEmpty()) {
        if (state.earlyCollapsed) {
          return;
        }
        if (state.early.size() >= maxBufferedSignals.getAsInt()) {
          state.early.clear();
          state.early.add(new Signal.Resync());
          state.earlyCollapsed = true;
          return;
        }
        state.early.add(signal);
        return;
      }
      recipients = List.copyOf(state.subscribers);
    }
    for (ApplicationEventLocalSubscription subscription : recipients) {
      subscription.deliver(signal);
    }
  }

  private void release(ApplicationEventLocalSubscription subscription) {
    ApplicationEventResourceState state = resources.get(subscription.resource());
    if (state == null) {
      return;
    }
    ThreadSnapshotPublisher.Handle threadHandle;
    boolean last;
    synchronized (state) {
      if (state.retired || !state.subscribers.remove(subscription)) {
        return;
      }
      subscription.markClosed();
      threadHandle = state.threadSnapshotHandles.remove(subscription);
      last = state.subscribers.isEmpty();
      if (last) {
        state.retired = true;
        resources.remove(subscription.resource(), state);
      }
    }
    closeQuietly(threadHandle);
    if (last) {
      closeUpstreams(state);
      unregisterRealtimeSubscription(state);
    }
  }

  /** 首个 Thread 订阅建立时登记跨节点订阅。登记失败只影响跨节点投递：记录安全错误并向该 Thread 缓冲一次 resync，本机上游保持建立。 */
  private void registerRealtimeSubscription(ApplicationEventResourceState state) {
    UUID threadId = state.key.id();
    try {
      realtimeSubscriptions.register(threadId, () -> resyncThread(threadId));
    } catch (RuntimeException error) {
      log.warn(
          "cannot register realtime subscription; local delivery continues threadId={}"
              + " errorType={}",
          threadId,
          error.getClass().getSimpleName());
      fanout(state, new Signal.Resync());
    }
  }

  /** 末个 Thread 订阅释放或 Hub close 时注销登记；注销失败只影响远端租约清理，不阻断本地释放。 */
  private void unregisterRealtimeSubscription(ApplicationEventResourceState state) {
    if (state.key.kind() != ResourceKind.THREAD) {
      return;
    }
    try {
      realtimeSubscriptions.unregister(state.key.id());
    } catch (RuntimeException error) {
      log.warn(
          "cannot release realtime subscription; relying on lease expiry threadId={}"
              + " errorType={}",
          state.key.id(),
          error.getClass().getSimpleName());
    }
  }

  /** 服务续租失败/恢复时的锁外回调：只向当前仍存活的 Thread 状态投递 resync。 */
  private void resyncThread(UUID threadId) {
    ApplicationEventResourceState state =
        resources.get(new ResourceKey(ResourceKind.THREAD, threadId));
    if (state != null) {
      fanout(state, new Signal.Resync());
    }
  }

  private static void closeUpstreams(ApplicationEventResourceState state) {
    closeQuietly(state.threadSnapshotInvalidationHandle);
    closeQuietly(state.threadEnvironmentInvalidationRelease);
    for (ThreadSnapshotPublisher.Handle handle : state.threadSnapshotHandles.values()) {
      closeQuietly(handle);
    }
    state.threadSnapshotHandles.clear();
    closeQuietly(state.realtimeHandle);
    closeQuietly(state.canvasVersionHandle);
    closeQuietly(state.invalidationRelease);
    closeQuietly(state.threadTreeSnapshotHandle);
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

  private static void closeQuietly(Runnable release) {
    if (release == null) {
      return;
    }
    try {
      release.run();
    } catch (RuntimeException error) {
      // 释放失败不影响其余订阅
    }
  }
}
