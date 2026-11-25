package fun.fengwk.kkstudio.web.events;

import jakarta.annotation.PreDestroy;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import fun.fengwk.kkstudio.ide.browser.ConnectionIdentity;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUse;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUseRegistry;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUseRegistry.Acceptance;
import fun.fengwk.kkstudio.platform.account.StudioAccountService;
import fun.fengwk.kkstudio.platform.account.StudioBrowserSession;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.account.StudioRole;
import fun.fengwk.kkstudio.platform.orchestration.ResourceAccess;
import fun.fengwk.kkstudio.platform.orchestration.ResourceOwnership.Kind;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.ResourceKey;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.ResourceKind;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.Signal;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.Subscription;
import fun.fengwk.kkstudio.web.events.EventFrameCodec.ClientFrame;
import fun.fengwk.kkstudio.web.events.EventFrameCodec.IdeUseFrame;
import fun.fengwk.kkstudio.web.events.EventFrameCodec.RefreshFrame;
import fun.fengwk.kkstudio.web.events.EventFrameCodec.SubscriptionFrame;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 浏览器事件通道 {@code /ws/events} 的 Spring WebSocket 适配器。
 *
 * <p>连接建立时解包 {@link NativeWebSocketSession} 获得 {@link Session}，正文与原生 ping 全部经 {@link
 * AsyncTextSender} 异步串行发送，不使用同步 Spring sendMessage。会话状态（订阅表、发送队列、IDE 使用登记）按物理连接维护：重复 subscribe
 * 幂等、断线释放全部订阅与本次连接自己的 IDE 证明。
 *
 * <p>连接建立后的第一帧是 {@code connected{eventNodeId,connectionId,heartbeatIntervalMillis}}，其中 heartbeat
 * 间隔是服务端实际配置值；服务端每 15 秒发送原生 ping，45 秒没有收到有效 pong 即停止 为该连接提供使用证明并关闭连接；浏览器的后台 JS 定时器不参与保活。资源不存在只回资源级
 * error（{@code RESOURCE_NOT_FOUND}）并保持连接； 非法协议帧与发送过载/失败才关闭连接。
 *
 * <p>单帧放不下与发送积压是两件事：{@code ThreadSnapshot} / {@code ThreadTreeSnapshot} 的完整帧超过本连接 UTF-8 单帧预算时，按资源发
 * {@code snapshot-ref{resource[,version]}}（Thread 带真实 DTO version，执行树不带），
 * 订阅与物理连接保持不变，后续可发送快照照常投递；只有队列帧数或待发字节总量越界才算过载，仍以 {@code BACKPRESSURE} 收尾。
 */
@Component
public final class ApplicationEventWebSocketHandler extends TextWebSocketHandler {

  public static final String PATH = "/ws/events";

  static final long PING_INTERVAL_MILLIS = 15_000L;
  static final long HEARTBEAT_CHECK_INTERVAL_MILLIS = 1_000L;
  private static final long AUTHENTICATION_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(59);
  static final int LOGIN_REQUIRED_CLOSE_CODE = 4001;
  static final long PONG_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(45);

  private final ApplicationEventHub hub;
  private final EventFrameCodec codec;
  private final UUID eventNodeId;
  private final IdeBrowserUseRegistry browserUseRegistry;
  private final Supplier<ApplicationEventSettings> settingsProvider;
  private final ScheduledExecutorService heartbeatScheduler;
  private final LongSupplier nanoClock;
  private final Map<String, ConnectionState> connections = new ConcurrentHashMap<>();
  private final StudioAccountService accounts;
  private final ResourceAccess access;
  private final ScheduledFuture<?> heartbeatTask;
  private final ScheduledFuture<?> pingTask;

  @Autowired
  public ApplicationEventWebSocketHandler(
      ApplicationEventHub hub,
      EventFrameCodec codec,
      Supplier<ApplicationEventSettings> settingsProvider,
      @Qualifier("nodeInstanceId") UUID eventNodeId,
      IdeBrowserUseRegistry browserUseRegistry,
      @Qualifier("applicationEventHeartbeatScheduler") ScheduledExecutorService heartbeatScheduler,
      StudioAccountService accounts,
      ResourceAccess access) {
    this(
        hub,
        codec,
        settingsProvider,
        eventNodeId,
        browserUseRegistry,
        heartbeatScheduler,
        System::nanoTime,
        accounts,
        access);
  }

  ApplicationEventWebSocketHandler(
      ApplicationEventHub hub,
      EventFrameCodec codec,
      Supplier<ApplicationEventSettings> settingsProvider,
      UUID eventNodeId,
      IdeBrowserUseRegistry browserUseRegistry,
      ScheduledExecutorService heartbeatScheduler,
      LongSupplier nanoClock,
      StudioAccountService accounts,
      ResourceAccess access) {
    this.hub = Objects.requireNonNull(hub, "hub");
    this.codec = Objects.requireNonNull(codec, "codec");
    this.eventNodeId = Objects.requireNonNull(eventNodeId, "eventNodeId");
    this.browserUseRegistry = Objects.requireNonNull(browserUseRegistry, "browserUseRegistry");
    this.settingsProvider = Objects.requireNonNull(settingsProvider, "settingsProvider");
    this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    this.accounts = Objects.requireNonNull(accounts, "accounts");
    this.access = Objects.requireNonNull(access, "access");
    this.heartbeatScheduler = Objects.requireNonNull(heartbeatScheduler, "heartbeatScheduler");
    this.heartbeatTask =
        heartbeatScheduler.scheduleAtFixedRate(
            this::heartbeatTick,
            HEARTBEAT_CHECK_INTERVAL_MILLIS,
            HEARTBEAT_CHECK_INTERVAL_MILLIS,
            TimeUnit.MILLISECONDS);
    this.pingTask =
        heartbeatScheduler.scheduleAtFixedRate(
            this::ping, PING_INTERVAL_MILLIS, PING_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
  }

  ApplicationEventWebSocketHandler(
      ApplicationEventHub hub,
      EventFrameCodec codec,
      ApplicationEventSettings settings,
      UUID eventNodeId,
      IdeBrowserUseRegistry browserUseRegistry,
      ScheduledExecutorService heartbeatScheduler,
      LongSupplier nanoClock,
      StudioAccountService accounts,
      ResourceAccess access) {
    this(
        hub,
        codec,
        () -> settings,
        eventNodeId,
        browserUseRegistry,
        heartbeatScheduler,
        nanoClock,
        accounts,
        access);
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    ApplicationEventSettings settings = Objects.requireNonNull(settingsProvider.get(), "settings");
    Session nativeSession = requireNativeSession(session);
    // 先构造发送器再登记：发送器构造失败时不会留下无主的 IDE 使用登记。
    AsyncTextSender sender =
        new AsyncTextSender(
            nativeSession,
            settings.queueCapacity(),
            settings.maxBytes(),
            settings.sendTimeoutMillis(),
            heartbeatScheduler);
    String token =
        (String) session.getAttributes().get(StudioAccountHandshakeInterceptor.TOKEN_ATTRIBUTE);
    StudioPrincipal principal =
        (StudioPrincipal) session.getAttributes().get(StudioBrowserSession.PRINCIPAL_ATTRIBUTE);
    if (token == null || principal == null) {
      sender.close(StatusCode.POLICY_VIOLATION, "authentication required");
      return;
    }
    ConnectionIdentity identity = new ConnectionIdentity(eventNodeId, UUID.randomUUID());
    long generation = browserUseRegistry.register(identity);
    ConnectionState state =
        new ConnectionState(
            hub,
            codec,
            browserUseRegistry,
            identity,
            generation,
            settings.heartbeatIntervalMillis(),
            sender,
            token,
            principal,
            accounts,
            access,
            nanoClock.getAsLong());
    ConnectionState previous = connections.putIfAbsent(session.getId(), state);
    if (previous != null) {
      state.close();
      throw new IllegalStateException("event channel session is already established");
    }
    state.start();
    if (state.isClosed()) {
      connections.remove(session.getId(), state);
    }
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    ConnectionState state = connections.get(session.getId());
    if (state != null) {
      state.handle(message.getPayload(), nanoClock.getAsLong());
    }
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) {
    close(session.getId());
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    close(session.getId());
  }

  /** Spring 的原生适配器已经把 PongMessage 路由到这里；按物理 session 刷新该连接的有效 pong 时间。 */
  @Override
  public void handlePongMessage(WebSocketSession session, PongMessage message) {
    ConnectionState state = connections.get(session.getId());
    if (state != null) {
      state.pong(nanoClock.getAsLong());
    }
  }

  /**
   * 应用 shutdown：让现存浏览器连接以 1012 {@code SERVICE_RESTART} 收敛（error 帧出队后关闭），并释放全部订阅与使用证明。
   * 幂等；之后到达的帧按正常断线路径处理。
   */
  @PreDestroy
  public void shutdown() {
    heartbeatTask.cancel(false);
    pingTask.cancel(false);
    for (ConnectionState state : connections.values()) {
      state.shutdown();
    }
  }

  /** 测试接缝：立即为所有连接发送一次 heartbeat。 */
  void heartbeat() {
    for (ConnectionState state : connections.values()) {
      state.heartbeatNow();
    }
  }

  void heartbeatTick() {
    long nowNanos = nanoClock.getAsLong();
    for (ConnectionState state : connections.values()) {
      state.authenticationTick(nowNanos);
      state.heartbeat(nowNanos);
    }
  }

  /** 单次共享原生 ping tick；过期连接立即撤销使用证明并关闭。 */
  void ping() {
    ping(nanoClock.getAsLong());
  }

  void ping(long nowNanos) {
    for (Map.Entry<String, ConnectionState> entry : connections.entrySet()) {
      ConnectionState state = entry.getValue();
      state.ping(nowNanos);
      if (state.isClosed()) {
        connections.remove(entry.getKey(), state);
      }
    }
  }

  private void close(String sessionId) {
    ConnectionState state = connections.remove(sessionId);
    if (state != null) {
      state.close();
    }
  }

  private static Session requireNativeSession(WebSocketSession session) {
    if (session instanceof NativeWebSocketSession nativeWebSocketSession) {
      Object nativeSession = nativeWebSocketSession.getNativeSession();
      if (nativeSession instanceof Session jettySession) {
        return jettySession;
      }
    }
    throw new IllegalStateException(
        "event channel requires a servlet NativeWebSocketSession with a Jetty native Session");
  }

  /** 单连接的协议状态：订阅表 + 发送队列 + 使用证明；closeLock 使建立、登记与关闭互斥。 */
  private static final class ConnectionState {

    private final ApplicationEventHub hub;
    private final EventFrameCodec codec;
    private final IdeBrowserUseRegistry browserUseRegistry;
    private final ConnectionIdentity identity;
    private final long generation;
    private final long heartbeatIntervalMillis;
    private final AsyncTextSender sender;
    private final String token;
    private StudioPrincipal principal;
    private final StudioAccountService accounts;
    private final ResourceAccess access;
    private long lastAuthenticationCheckNanos;
    private final Map<ResourceKey, Subscription> subscriptions = new ConcurrentHashMap<>();

    /**
     * 单帧超限的 Thread 溢出簿记：记录本连接本订阅已按 {@code snapshot-ref} 逻辑交付的版本，同版本不再重复提示，正常快照 或 unsubscribe 清除。只在
     * closeLock 内读写。
     */
    private final Map<ResourceKey, Long> overflowedSnapshotVersions = new HashMap<>();

    private final Object closeLock = new Object();
    private long lastPongNanos;
    private long lastHeartbeatNanos;
    private volatile boolean closed;

    private ConnectionState(
        ApplicationEventHub hub,
        EventFrameCodec codec,
        IdeBrowserUseRegistry browserUseRegistry,
        ConnectionIdentity identity,
        long generation,
        long heartbeatIntervalMillis,
        AsyncTextSender sender,
        String token,
        StudioPrincipal principal,
        StudioAccountService accounts,
        ResourceAccess access,
        long nowNanos) {
      this.hub = hub;
      this.codec = codec;
      this.browserUseRegistry = browserUseRegistry;
      this.identity = identity;
      this.generation = generation;
      this.heartbeatIntervalMillis = heartbeatIntervalMillis;
      this.sender = sender;
      this.token = token;
      this.principal = principal;
      this.accounts = accounts;
      this.access = access;
      this.lastAuthenticationCheckNanos = nowNanos;
      this.lastPongNanos = nowNanos;
      this.lastHeartbeatNanos = nowNanos;
    }

    private void start() {
      synchronized (closeLock) {
        if (closed) {
          return;
        }
        if (!sender.enqueue(
            codec.connected(
                identity.eventNodeId(), identity.connectionId(), heartbeatIntervalMillis))) {
          fail(EventFrameCodec.BACKPRESSURE, "event queue is full", StatusCode.TRY_AGAIN_LATER);
        }
      }
    }

    private boolean isClosed() {
      return closed;
    }

    private void handle(String payload, long nowNanos) {
      if (closed) {
        return;
      }
      ClientFrame frame;
      try {
        frame = codec.decode(payload);
      } catch (RuntimeException error) {
        fail(
            EventFrameCodec.INVALID_FRAME,
            "invalid frame: " + error.getMessage(),
            StatusCode.PROTOCOL);
        return;
      }
      switch (frame) {
        case SubscriptionFrame subscription -> {
          if (subscription.type() == EventFrameCodec.SubscriptionType.SUBSCRIBE) {
            subscribe(subscription.resource(), subscription.afterEntryId(), nowNanos);
          } else {
            unsubscribe(subscription.resource());
          }
        }
        case RefreshFrame refresh -> refresh(refresh.resource());
        case IdeUseFrame ideUse -> acceptIdeUse(ideUse.use());
      }
    }

    private void subscribe(ResourceKey resource, UUID afterEntryId, long nowNanos) {
      synchronized (closeLock) {
        if (closed) {
          return; // 连接已关闭：不再登记新订阅
        }
        if (!recheckAuthentication(nowNanos)) {
          return;
        }
        if (!canRead(resource, principal)) {
          enqueueResourceError("RESOURCE_NOT_FOUND", "Resource not found", resource);
          return;
        }
        if (principal.role() != StudioRole.ADMIN && adminOnly(resource.kind())) {
          enqueueResourceError("ADMIN_REQUIRED", "Administrator required", resource);
          return;
        }
        if (subscriptions.containsKey(resource)) {
          return; // 重复 subscribe 幂等
        }
        Subscription[] current = new Subscription[1];
        Subscription subscription;
        try {
          subscription =
              hub.subscribe(
                  resource,
                  signal -> {
                    synchronized (closeLock) {
                      if (closed || subscriptions.get(resource) != current[0]) {
                        return;
                      }
                      // 快照未被接受时抛给发布器：它只在实际投递成功后推进订阅游标，重试时仍从旧游标投影。
                      if (signal instanceof Signal.ThreadSnapshot) {
                        if (!enqueueSignal(resource, signal)) {
                          throw new IllegalStateException("thread snapshot was not queued");
                        }
                      } else {
                        enqueueSignal(resource, signal);
                      }
                    }
                  },
                  afterEntryId);
          current[0] = subscription;
        } catch (IllegalArgumentException error) {
          // 资源不存在：只回资源级 error，保持连接。
          if (!sender.enqueue(
              codec.error(EventFrameCodec.RESOURCE_NOT_FOUND, "Resource not found", resource))) {
            fail(EventFrameCodec.BACKPRESSURE, "event queue is full", StatusCode.TRY_AGAIN_LATER);
          }
          return;
        }
        if (!sender.enqueue(codec.subscribed(resource, subscription.cursor()))) {
          subscription.close();
          fail(EventFrameCodec.BACKPRESSURE, "event queue is full", StatusCode.TRY_AGAIN_LATER);
          return;
        }
        subscriptions.put(resource, subscription);
        subscription.activate();
      }
    }

    private void unsubscribe(ResourceKey resource) {
      synchronized (closeLock) {
        Subscription subscription = subscriptions.remove(resource);
        if (subscription != null) {
          overflowedSnapshotVersions.remove(resource);
          subscription.close();
        }
      }
    }

    /** Explicit thread-tree refresh never changes the connection's subscription refcount. */
    private void refresh(ResourceKey resource) {
      synchronized (closeLock) {
        if (closed) {
          return;
        }
        if (resource.kind() == ApplicationEventHub.ResourceKind.THREAD) {
          Subscription subscription = subscriptions.get(resource);
          if (subscription == null) {
            enqueueResourceError(
                EventFrameCodec.RESOURCE_NOT_SUBSCRIBED,
                "thread resource is not subscribed on this connection",
                resource);
            return;
          }
          if (!hub.requestThreadSnapshot(subscription)) {
            enqueueResourceError(
                EventFrameCodec.THREAD_SNAPSHOT_FAILED,
                "thread snapshot could not be requested",
                resource);
          }
          return;
        }
        if (resource.kind() != ApplicationEventHub.ResourceKind.THREAD_TREE) {
          enqueueResourceError(
              EventFrameCodec.INVALID_REFRESH_RESOURCE,
              "refresh is only supported for thread and thread-tree resources",
              resource);
          return;
        }
        if (!subscriptions.containsKey(resource)) {
          enqueueResourceError(
              EventFrameCodec.RESOURCE_NOT_SUBSCRIBED,
              "thread-tree resource is not subscribed on this connection",
              resource);
          return;
        }
        if (!hub.requestThreadTreeSnapshot(resource)) {
          enqueueResourceError(
              EventFrameCodec.TREE_SNAPSHOT_FAILED,
              "thread-tree snapshot could not be requested",
              resource);
        }
      }
    }

    private void enqueueResourceError(String code, String message, ResourceKey resource) {
      if (!sender.enqueue(codec.error(code, message, resource))) {
        fail(EventFrameCodec.BACKPRESSURE, "event queue is full", StatusCode.TRY_AGAIN_LATER);
      }
    }

    /** ide-use 的裁决与 ack 在同一 closeLock 内完成；拒绝是连接级 error，不关闭连接。 */
    private void acceptIdeUse(IdeBrowserUse usage) {
      synchronized (closeLock) {
        if (closed) {
          return;
        }
        Acceptance acceptance = browserUseRegistry.accept(identity, generation, usage);
        switch (acceptance) {
          case ACCEPTED, UNCHANGED -> {
            if (!sender.enqueue(codec.ideUseAccepted(usage.revision()))) {
              fail(EventFrameCodec.BACKPRESSURE, "event queue is full", StatusCode.TRY_AGAIN_LATER);
            }
          }
          case BROWSER_SESSION_MISMATCH -> rejectIdeUse(
              "browserSessionId is fixed for this connection");
          case REVISION_CONFLICT -> rejectIdeUse(
              "ide-use revision must increase and keep identical content for the same revision");
          case DISCONNECTED -> {
            // closeLock 已与关闭互斥；这里只在登记已释放时安全忽略。
          }
        }
      }
    }

    private void rejectIdeUse(String message) {
      if (!sender.enqueue(codec.error(EventFrameCodec.IDE_USE_REJECTED, message))) {
        fail(EventFrameCodec.BACKPRESSURE, "event queue is full", StatusCode.TRY_AGAIN_LATER);
      }
    }

    /**
     * 资源信号编码为帧后入队。
     *
     * <p>完整快照本身超出单帧 UTF-8 预算时只对该资源改发 {@code snapshot-ref}（Thread 带真实 DTO
     * version，执行树不带），订阅与物理连接保持：Thread 同版本不重复提示，正常的可发送快照照常投递并清除 该资源的簿记。队列帧数或待发字节总量越界仍是真实过载，发
     * BACKPRESSURE 后关闭连接。
     *
     * @return 该资源是否已接受本次投递（正常帧或 {@code snapshot-ref}，含同版本去重）；真实失败或已关闭时为 {@code false}，Thread
     *     发布器据此决定是否推进订阅游标
     */
    private boolean enqueueSignal(ResourceKey resource, Signal signal) {
      if (signal instanceof Signal.ThreadSnapshotFailed) {
        enqueueResourceError(
            EventFrameCodec.THREAD_SNAPSHOT_FAILED,
            "authoritative thread snapshot failed",
            resource);
        return false;
      }
      String frame;
      if (signal instanceof Signal.Resync) {
        frame = codec.resync(resource);
      } else if (signal instanceof Signal.ThreadTreeSnapshotFailed) {
        frame =
            codec.error(
                EventFrameCodec.TREE_SNAPSHOT_FAILED,
                "authoritative thread-tree snapshot failed",
                resource);
      } else {
        frame = codec.event(resource, signal);
      }
      if (!sender.enqueue(frame)) {
        if (sender.exceedsFrameBudget(frame) && isOversizedSnapshotFrame(signal)) {
          return enqueueSnapshotRef(resource, signal);
        }
        fail(EventFrameCodec.BACKPRESSURE, "event queue is full", StatusCode.TRY_AGAIN_LATER);
        return false;
      }
      if (isOversizedSnapshotFrame(signal)) {
        // 正常快照已交付：该资源不再处于溢出提示状态。
        overflowedSnapshotVersions.remove(resource);
      }
      return true;
    }

    /** 只有完整权威快照会因体积单独超限；其他帧超预算就只能是真实积压。 */
    private static boolean isOversizedSnapshotFrame(Signal signal) {
      return signal instanceof Signal.ThreadSnapshot || signal instanceof Signal.ThreadTreeSnapshot;
    }

    /**
     * 按资源提示“快照放不进单帧”：Thread 携带快照的真实 DTO version，执行树不带 version。
     *
     * <p>同一订阅内重复的相同 Thread 版本只提示一次；提示入队成功即视为该资源的逻辑交付，客户端据此回读 HTTP 权威事实并重新基准，因此不暂停订阅、不清空后续事实。version
     * 一律取快照的真实 DTO 值，严格 codec 拒绝缺失值，不在此合成或回退任何替代值。
     *
     * @return ref 已入队或同版本已逻辑交付时为 {@code true}；入队失败（已以 BACKPRESSURE 收尾）时为 {@code false}
     */
    private boolean enqueueSnapshotRef(ResourceKey resource, Signal signal) {
      Long version = null;
      if (signal instanceof Signal.ThreadSnapshot snapshot) {
        version = snapshot.snapshot().getVersion();
        if (version != null && version.equals(overflowedSnapshotVersions.get(resource))) {
          return true; // 同一版本已逻辑交付：不重复提示，也不制造 HTTP 回读循环。
        }
      }
      // 严格 codec 要求 Thread 必须携带真实 version；缺失即编码错误，不在此合成或回退任何替代值。
      if (!sender.enqueue(codec.snapshotRef(resource, version))) {
        fail(EventFrameCodec.BACKPRESSURE, "event queue is full", StatusCode.TRY_AGAIN_LATER);
        return false;
      }
      if (version != null) {
        overflowedSnapshotVersions.put(resource, version);
      }
      return true;
    }

    private void heartbeat(long nowNanos) {
      synchronized (closeLock) {
        if (closed
            || nowNanos - lastHeartbeatNanos
                < TimeUnit.MILLISECONDS.toNanos(heartbeatIntervalMillis)) {
          return;
        }
        lastHeartbeatNanos = nowNanos;
        enqueueHeartbeat();
      }
    }

    private void heartbeatNow() {
      synchronized (closeLock) {
        if (!closed) {
          enqueueHeartbeat();
        }
      }
    }

    private void enqueueHeartbeat() {
      if (!sender.enqueue(codec.heartbeat())) {
        fail(EventFrameCodec.BACKPRESSURE, "event queue is full", StatusCode.TRY_AGAIN_LATER);
      }
    }

    private void ping(long nowNanos) {
      boolean expired;
      synchronized (closeLock) {
        if (closed) {
          return;
        }
        expired = nowNanos - lastPongNanos >= PONG_TIMEOUT_NANOS;
        if (!expired && !sender.enqueuePing()) {
          fail(EventFrameCodec.BACKPRESSURE, "event queue is full", StatusCode.TRY_AGAIN_LATER);
          return;
        }
      }
      if (expired) {
        // 先撤销证明与订阅，再关闭物理连接；两者都在 closeLock 内完成测量，迟到 pong 不能复活本代际。
        close();
        sender.close(StatusCode.TRY_AGAIN_LATER, "pong timeout");
      }
    }

    private void authenticationTick(long nowNanos) {
      synchronized (closeLock) {
        if (!closed && nowNanos - lastAuthenticationCheckNanos >= AUTHENTICATION_INTERVAL_NANOS) {
          recheckAuthentication(nowNanos);
        }
      }
    }

    private boolean canRead(ResourceKey resource, StudioPrincipal actor) {
      return switch (resource.kind()) {
        case THREAD, THREAD_TREE -> access.allowsThread(actor, resource.id());
        case CANVAS -> access.allows(actor, Kind.CANVAS, resource.id());
        case INTERACTIONS, ENVIRONMENTS -> true;
      };
    }

    private static boolean adminOnly(ResourceKind kind) {
      return kind == ResourceKind.INTERACTIONS;
    }

    private boolean recheckAuthentication(long nowNanos) {
      StudioPrincipal current = accounts.authenticate(token).orElse(null);
      if (current == null || !current.userId().equals(principal.userId())) {
        fail("NOT_AUTHENTICATED", "Login required", LOGIN_REQUIRED_CLOSE_CODE);
        return false;
      }
      if (principal.role() == StudioRole.ADMIN && current.role() != StudioRole.ADMIN) {
        subscriptions.keySet().stream()
            .filter(key -> adminOnly(key.kind()))
            .toList()
            .forEach(this::unsubscribe);
      }
      subscriptions.keySet().stream()
          .filter(resource -> !canRead(resource, current))
          .toList()
          .forEach(this::unsubscribe);
      principal = current;
      lastAuthenticationCheckNanos = nowNanos;
      return true;
    }

    private void pong(long nowNanos) {
      synchronized (closeLock) {
        if (!closed) {
          lastPongNanos = nowNanos;
        }
      }
    }

    /** 发送 error 帧并在其出队后关闭连接；立即撤销本代际的订阅与使用证明。 */
    private void fail(String code, String message, int closeCode) {
      sender.fail(codec.error(code, message), closeCode, code);
      close();
    }

    private void close() {
      synchronized (closeLock) {
        if (closed) {
          return;
        }
        closed = true;
        for (Subscription subscription : subscriptions.values()) {
          subscription.close();
        }
        subscriptions.clear();
        browserUseRegistry.unregister(identity, generation);
        // 已失败的发送链保留待投递的 error 帧；正常断线只释放排队帧，不重复关闭物理 session。
        sender.discard();
      }
    }

    /** 应用 shutdown：先以 1012 收敛发送链（error 帧出队后关闭会话），再释放全部订阅。 */
    private void shutdown() {
      fail(
          EventFrameCodec.SEND_FAILED,
          "event channel is shutting down",
          StatusCode.SERVICE_RESTART);
      close();
    }
  }
}
