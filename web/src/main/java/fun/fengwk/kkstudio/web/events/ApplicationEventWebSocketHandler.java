package fun.fengwk.kkstudio.web.events;

import jakarta.annotation.PreDestroy;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import fun.fengwk.kkstudio.web.events.ApplicationEventHub.ResourceKey;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.Signal;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.Subscription;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 浏览器事件通道 {@code /api/events/v1} 的 Spring WebSocket 适配器。
 *
 * <p>连接建立时解包 {@link NativeWebSocketSession} 获得 {@link jakarta.websocket.Session}，正文全部经 {@link
 * AsyncTextSender}（jakarta AsyncRemote）异步串行发送，不使用同步 Spring sendMessage。会话状态（订阅表、 发送队列）按连接维护：重复
 * subscribe 幂等、断线释放全部订阅。
 *
 * <p>资源不存在只回资源级 error（{@code RESOURCE_NOT_FOUND}，带 resource）并保持连接；非法协议帧与发送 过载/失败才关闭连接。
 */
@Component
public final class ApplicationEventWebSocketHandler extends TextWebSocketHandler {

  public static final String PATH = "/api/events/v1";

  /** 测试便捷构造使用的默认事件软策略（与 SystemSettings.Advanced 默认一致）。 */
  private static final long TEST_MAX_BYTES = 2L * 1024 * 1024;

  private static final long TEST_SEND_TIMEOUT_MILLIS = 10_000L;
  private static final long TEST_HEARTBEAT_INTERVAL_MILLIS = 20_000L;

  private final ApplicationEventHub hub;
  private final EventFrameCodec codec;
  private final int senderCapacity;
  private final long maxBytes;
  private final long sendTimeoutMillis;
  private final Map<String, ConnectionState> connections = new ConcurrentHashMap<>();
  private final ScheduledFuture<?> heartbeatTask;

  @Autowired
  public ApplicationEventWebSocketHandler(
      ApplicationEventHub hub,
      EventFrameCodec codec,
      ApplicationEventSettings settings,
      @Qualifier("applicationEventHeartbeatScheduler")
          ScheduledExecutorService heartbeatScheduler) {
    this(
        hub,
        codec,
        settings.queueCapacity(),
        settings.maxBytes(),
        settings.sendTimeoutMillis(),
        settings.heartbeatIntervalMillis(),
        heartbeatScheduler);
  }

  /** 测试可注入发送队列容量；其余事件软策略使用仓库默认（与 SystemSettings.Advanced 默认一致）。 */
  ApplicationEventWebSocketHandler(
      ApplicationEventHub hub, EventFrameCodec codec, int senderCapacity) {
    this(
        hub,
        codec,
        senderCapacity,
        TEST_MAX_BYTES,
        TEST_SEND_TIMEOUT_MILLIS,
        TEST_HEARTBEAT_INTERVAL_MILLIS,
        null);
  }

  private ApplicationEventWebSocketHandler(
      ApplicationEventHub hub,
      EventFrameCodec codec,
      int senderCapacity,
      long maxBytes,
      long sendTimeoutMillis,
      long heartbeatIntervalMillis,
      ScheduledExecutorService heartbeatScheduler) {
    this.hub = Objects.requireNonNull(hub, "hub");
    this.codec = Objects.requireNonNull(codec, "codec");
    if (senderCapacity <= 0) {
      throw new IllegalArgumentException("senderCapacity must be positive");
    }
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be positive");
    }
    if (sendTimeoutMillis <= 0) {
      throw new IllegalArgumentException("sendTimeoutMillis must be positive");
    }
    if (heartbeatIntervalMillis <= 0) {
      throw new IllegalArgumentException("heartbeatIntervalMillis must be positive");
    }
    this.senderCapacity = senderCapacity;
    this.maxBytes = maxBytes;
    this.sendTimeoutMillis = sendTimeoutMillis;
    this.heartbeatTask =
        heartbeatScheduler == null
            ? null
            : heartbeatScheduler.scheduleAtFixedRate(
                this::heartbeat,
                heartbeatIntervalMillis,
                heartbeatIntervalMillis,
                TimeUnit.MILLISECONDS);
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    Session nativeSession = requireNativeSession(session);
    connections.put(
        session.getId(),
        new ConnectionState(
            hub,
            codec,
            new AsyncTextSender(nativeSession, senderCapacity, maxBytes, sendTimeoutMillis)));
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

  /**
   * 应用 shutdown：让现存浏览器连接以 1012 {@code SERVICE_RESTART} 收敛（error 帧出队后关闭），并释放全部订阅。
   * 幂等；之后到达的帧按正常断线路径处理。
   */
  @PreDestroy
  public void shutdown() {
    if (heartbeatTask != null) {
      heartbeatTask.cancel(false);
    }
    for (ConnectionState state : connections.values()) {
      state.shutdown();
    }
  }

  /** 单次共享 heartbeat tick；只向每连接的 AsyncTextSender 非阻塞入队。 */
  void heartbeat() {
    for (ConnectionState state : connections.values()) {
      state.heartbeat();
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

  /** 单连接的协议状态：订阅表 + 发送队列；closeLock 使「建立、登记、关闭」互斥，杜绝关闭竞态下的订阅泄漏。 */
  private static final class ConnectionState {

    private final ApplicationEventHub hub;
    private final EventFrameCodec codec;
    private final AsyncTextSender sender;
    private final Map<ResourceKey, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final Object closeLock = new Object();
    private boolean closed;

    private ConnectionState(
        ApplicationEventHub hub, EventFrameCodec codec, AsyncTextSender sender) {
      this.hub = hub;
      this.codec = codec;
      this.sender = sender;
    }

    private void handle(String payload) {
      EventFrameCodec.ClientFrame frame;
      try {
        frame = codec.decode(payload);
      } catch (RuntimeException error) {
        fail(
            EventFrameCodec.INVALID_FRAME,
            "invalid frame: " + error.getMessage(),
            CloseReason.CloseCodes.PROTOCOL_ERROR);
        return;
      }
      switch (frame.type()) {
        case SUBSCRIBE -> subscribe(frame.resource());
        case UNSUBSCRIBE -> unsubscribe(frame.resource());
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

    /** 发送 error 帧并在其出队后关闭连接。 */
    private void fail(String code, String message, CloseReason.CloseCodes closeCode) {
      sender.fail(codec.error(code, message), closeCode);
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
