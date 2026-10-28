package fun.fengwk.kkstudio.web.events;

import jakarta.annotation.PreDestroy;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Session;
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
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.ResourceKey;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.Signal;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.Subscription;
import fun.fengwk.kkstudio.web.events.EventFrameCodec.ClientFrame;
import fun.fengwk.kkstudio.web.events.EventFrameCodec.IdeUseFrame;
import fun.fengwk.kkstudio.web.events.EventFrameCodec.SubscriptionFrame;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 浏览器事件通道 {@code /api/events/v2} 的 Spring WebSocket 适配器。
 *
 * <p>连接建立时解包 {@link NativeWebSocketSession} 获得 {@link jakarta.websocket.Session}，正文与原生 ping 全部经
 * {@link AsyncTextSender}（jakarta AsyncRemote）异步串行发送，不使用同步 Spring sendMessage。会话状态（订阅表、发送队列、IDE
 * 使用登记）按物理连接维护：重复 subscribe 幂等、断线释放全部订阅与本次连接自己的 IDE 证明。
 *
 * <p>连接建立后的第一帧是 {@code connected{eventNodeId,connectionId}}。服务端每 15 秒发送原生 ping，45 秒没有收到有效 pong 即停止
 * 为该连接提供使用证明并关闭连接；浏览器的后台 JS 定时器不参与保活。资源不存在只回资源级 error（{@code RESOURCE_NOT_FOUND}）并保持连接；
 * 非法协议帧与发送过载/失败才关闭连接。
 */
@Component
public final class ApplicationEventWebSocketHandler extends TextWebSocketHandler {

  public static final String PATH = "/api/events/v2";

  static final long PING_INTERVAL_MILLIS = 15_000L;
  static final long PONG_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(45);

  private final ApplicationEventHub hub;
  private final EventFrameCodec codec;
  private final UUID eventNodeId;
  private final IdeBrowserUseRegistry browserUseRegistry;
  private final int senderCapacity;
  private final long maxBytes;
  private final long sendTimeoutMillis;
  private final LongSupplier nanoClock;
  private final Map<String, ConnectionState> connections = new ConcurrentHashMap<>();
  private final ScheduledFuture<?> heartbeatTask;
  private final ScheduledFuture<?> pingTask;

  @Autowired
  public ApplicationEventWebSocketHandler(
      ApplicationEventHub hub,
      EventFrameCodec codec,
      ApplicationEventSettings settings,
      @Qualifier("nodeInstanceId") UUID eventNodeId,
      IdeBrowserUseRegistry browserUseRegistry,
      @Qualifier("applicationEventHeartbeatScheduler")
          ScheduledExecutorService heartbeatScheduler) {
    this(
        hub,
        codec,
        settings,
        eventNodeId,
        browserUseRegistry,
        heartbeatScheduler,
        System::nanoTime);
  }

  ApplicationEventWebSocketHandler(
      ApplicationEventHub hub,
      EventFrameCodec codec,
      ApplicationEventSettings settings,
      UUID eventNodeId,
      IdeBrowserUseRegistry browserUseRegistry,
      ScheduledExecutorService heartbeatScheduler,
      LongSupplier nanoClock) {
    this.hub = Objects.requireNonNull(hub, "hub");
    this.codec = Objects.requireNonNull(codec, "codec");
    this.eventNodeId = Objects.requireNonNull(eventNodeId, "eventNodeId");
    this.browserUseRegistry = Objects.requireNonNull(browserUseRegistry, "browserUseRegistry");
    this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    if (settings.queueCapacity() <= 0) {
      throw new IllegalArgumentException("senderCapacity must be positive");
    }
    if (settings.maxBytes() <= 0) {
      throw new IllegalArgumentException("maxBytes must be positive");
    }
    if (settings.sendTimeoutMillis() <= 0) {
      throw new IllegalArgumentException("sendTimeoutMillis must be positive");
    }
    if (settings.heartbeatIntervalMillis() <= 0) {
      throw new IllegalArgumentException("heartbeatIntervalMillis must be positive");
    }
    this.senderCapacity = settings.queueCapacity();
    this.maxBytes = settings.maxBytes();
    this.sendTimeoutMillis = settings.sendTimeoutMillis();
    this.heartbeatTask =
        heartbeatScheduler == null
            ? null
            : heartbeatScheduler.scheduleAtFixedRate(
                this::heartbeat,
                settings.heartbeatIntervalMillis(),
                settings.heartbeatIntervalMillis(),
                TimeUnit.MILLISECONDS);
    this.pingTask =
        heartbeatScheduler == null
            ? null
            : heartbeatScheduler.scheduleAtFixedRate(
                this::ping, PING_INTERVAL_MILLIS, PING_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    Session nativeSession = requireNativeSession(session);
    // 先构造发送器再登记：发送器构造失败时不会留下无主的 IDE 使用登记。
    AsyncTextSender sender =
        new AsyncTextSender(nativeSession, senderCapacity, maxBytes, sendTimeoutMillis);
    ConnectionIdentity identity = new ConnectionIdentity(eventNodeId, UUID.randomUUID());
    long generation = browserUseRegistry.register(identity);
    ConnectionState state =
        new ConnectionState(
            hub, codec, browserUseRegistry, identity, generation, sender, nanoClock.getAsLong());
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
      state.handle(message.getPayload());
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
    if (heartbeatTask != null) {
      heartbeatTask.cancel(false);
    }
    if (pingTask != null) {
      pingTask.cancel(false);
    }
    for (ConnectionState state : connections.values()) {
      state.shutdown();
    }
  }

  /** 单次共享文本 heartbeat tick；只向每连接的 AsyncTextSender 非阻塞入队。 */
  void heartbeat() {
    for (ConnectionState state : connections.values()) {
      state.heartbeat();
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
      if (nativeSession instanceof Session jakartaSession) {
        return jakartaSession;
      }
    }
    throw new IllegalStateException(
        "event channel requires a servlet NativeWebSocketSession with a jakarta Session");
  }

  /** 单连接的协议状态：订阅表 + 发送队列 + 使用证明；closeLock 使建立、登记与关闭互斥。 */
  private static final class ConnectionState {

    private final ApplicationEventHub hub;
    private final EventFrameCodec codec;
    private final IdeBrowserUseRegistry browserUseRegistry;
    private final ConnectionIdentity identity;
    private final long generation;
    private final AsyncTextSender sender;
    private final Map<ResourceKey, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final Object closeLock = new Object();
    private long lastPongNanos;
    private volatile boolean closed;

    private ConnectionState(
        ApplicationEventHub hub,
        EventFrameCodec codec,
        IdeBrowserUseRegistry browserUseRegistry,
        ConnectionIdentity identity,
        long generation,
        AsyncTextSender sender,
        long nowNanos) {
      this.hub = hub;
      this.codec = codec;
      this.browserUseRegistry = browserUseRegistry;
      this.identity = identity;
      this.generation = generation;
      this.sender = sender;
      this.lastPongNanos = nowNanos;
    }

    private void start() {
      synchronized (closeLock) {
        if (closed) {
          return;
        }
        if (!sender.enqueue(codec.connected(identity.eventNodeId(), identity.connectionId()))) {
          fail(
              EventFrameCodec.BACKPRESSURE,
              "event queue is full",
              CloseReason.CloseCodes.TRY_AGAIN_LATER);
        }
      }
    }

    private boolean isClosed() {
      return closed;
    }

    private void handle(String payload) {
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
            CloseReason.CloseCodes.PROTOCOL_ERROR);
        return;
      }
      switch (frame) {
        case SubscriptionFrame subscription -> {
          if (subscription.type() == EventFrameCodec.SubscriptionType.SUBSCRIBE) {
            subscribe(subscription.resource());
          } else {
            unsubscribe(subscription.resource());
          }
        }
        case IdeUseFrame ideUse -> acceptIdeUse(ideUse.use());
      }
    }

    private void subscribe(ResourceKey resource) {
      synchronized (closeLock) {
        if (closed) {
          return; // 连接已关闭：不再登记新订阅
        }
        if (subscriptions.containsKey(resource)) {
          return; // 重复 subscribe 幂等
        }
        Subscription subscription;
        try {
          subscription = hub.subscribe(resource, signal -> enqueueSignal(resource, signal));
        } catch (IllegalArgumentException error) {
          // 资源不存在：只回资源级 error，保持连接。
          if (!sender.enqueue(
              codec.error(EventFrameCodec.RESOURCE_NOT_FOUND, "Resource not found", resource))) {
            fail(
                EventFrameCodec.BACKPRESSURE,
                "event queue is full",
                CloseReason.CloseCodes.TRY_AGAIN_LATER);
          }
          return;
        }
        if (!sender.enqueue(codec.subscribed(resource, subscription.cursor()))) {
          subscription.close();
          fail(
              EventFrameCodec.BACKPRESSURE,
              "event queue is full",
              CloseReason.CloseCodes.TRY_AGAIN_LATER);
          return;
        }
        subscription.activate();
        subscriptions.put(resource, subscription);
      }
    }

    private void unsubscribe(ResourceKey resource) {
      synchronized (closeLock) {
        Subscription subscription = subscriptions.remove(resource);
        if (subscription != null) {
          subscription.close();
        }
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
              fail(
                  EventFrameCodec.BACKPRESSURE,
                  "event queue is full",
                  CloseReason.CloseCodes.TRY_AGAIN_LATER);
            }
          }
          case DOCUMENT_MISMATCH -> rejectIdeUse("documentId is fixed for this connection");
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
        fail(
            EventFrameCodec.BACKPRESSURE,
            "event queue is full",
            CloseReason.CloseCodes.TRY_AGAIN_LATER);
      }
    }

    /** 资源信号编码为帧后入队；队列满视为过载，发 BACKPRESSURE 后关闭连接。 */
    private void enqueueSignal(ResourceKey resource, Signal signal) {
      String frame =
          signal instanceof Signal.Resync ? codec.resync(resource) : codec.event(resource, signal);
      if (!sender.enqueue(frame)) {
        fail(
            EventFrameCodec.BACKPRESSURE,
            "event queue is full",
            CloseReason.CloseCodes.TRY_AGAIN_LATER);
      }
    }

    private void heartbeat() {
      synchronized (closeLock) {
        if (closed) {
          return;
        }
        if (!sender.enqueue(codec.heartbeat())) {
          fail(
              EventFrameCodec.BACKPRESSURE,
              "event queue is full",
              CloseReason.CloseCodes.TRY_AGAIN_LATER);
        }
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
          fail(
              EventFrameCodec.BACKPRESSURE,
              "event queue is full",
              CloseReason.CloseCodes.TRY_AGAIN_LATER);
          return;
        }
      }
      if (expired) {
        // 先撤销证明与订阅，再关闭物理连接；两者都在 closeLock 内完成测量，迟到 pong 不能复活本代际。
        close();
        sender.close(CloseReason.CloseCodes.TRY_AGAIN_LATER, "pong timeout");
      }
    }

    private void pong(long nowNanos) {
      synchronized (closeLock) {
        if (!closed) {
          lastPongNanos = nowNanos;
        }
      }
    }

    /** 发送 error 帧并在其出队后关闭连接；立即撤销本代际的订阅与使用证明。 */
    private void fail(String code, String message, CloseReason.CloseCodes closeCode) {
      sender.fail(codec.error(code, message), closeCode);
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
          CloseReason.CloseCodes.SERVICE_RESTART);
      close();
    }
  }
}
