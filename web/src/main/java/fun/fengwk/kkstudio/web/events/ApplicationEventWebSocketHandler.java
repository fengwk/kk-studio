package fun.fengwk.kkstudio.web.events;

import jakarta.annotation.PreDestroy;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent;
import fun.fengwk.kkstudio.share.notification.NotificationCarrier;
import fun.fengwk.kkstudio.share.notification.NotificationLimits;
import fun.fengwk.kkstudio.share.notification.NotificationPeerLink;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.ResourceKey;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.Signal;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.Subscription;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 浏览器事件通道 {@code /api/events/v1} 的 Spring WebSocket 适配器。
 *
 * <p><b>唯一物理载体：</b>每条连接由 {@link NotificationPeerLink}（固定 topic {@value
 * #CARRIER_TOPIC}）承载全部逻辑消息——既有资源 subscribe/unsubscribe/ack/event/resync/heartbeat/error，以及新增的
 * shell.command/shell.event；不存在 raw JSON 或小包旁路。逻辑正文由 {@link EventFrameCodec} 做 v2 严格解码：resource 帧交给
 * {@link ApplicationEventHub}，shell 帧交给 {@link ShellGateway}，两者共享同一连接、发送预算、 分片与关闭语义。
 *
 * <p>正文全部经 {@link AsyncTextSender}（共享 outbox + 组合根 executor 驱动的 jakarta {@code
 * AsyncRemote}）异步串行发送。会话状态（订阅表、shell 观察、发送链）按连接维护：重复 subscribe 幂等、断线释放全部订阅与 shell 观察（只 DETACH，不关闭
 * PTY）。资源不存在只回资源级 error 并保持连接；非法 peer/topic/target、binary、超限、缺片/超时、非法
 * UTF-8、非法协议帧与发送过载一律清理并关闭连接，从不回显原 payload。
 */
@Component
public final class ApplicationEventWebSocketHandler extends TextWebSocketHandler {

  public static final String PATH = "/api/events/v1";

  /** 浏览器事件通道唯一固定 carrier topic；与 daemon.v3 一样是共享 carrier 的物理主题。 */
  static final String CARRIER_TOPIC = "app.events.v2";

  private final ApplicationEventHub hub;
  private final ShellGateway gateway;
  private final EventFrameCodec codec;
  private final NotificationLimits limits;
  private final int sendTimeoutMillis;
  private final ExecutorService sendExecutor;
  private final ScheduledExecutorService expireTimer;
  private final Map<String, ConnectionState> connections = new ConcurrentHashMap<>();
  private final ScheduledFuture<?> heartbeatTask;

  @Autowired
  public ApplicationEventWebSocketHandler(
      ApplicationEventHub hub,
      ShellGateway gateway,
      EventFrameCodec codec,
      ApplicationEventSettings settings,
      @Qualifier("applicationEventSendExecutor") ExecutorService sendExecutor,
      @Qualifier("applicationEventHeartbeatScheduler")
          ScheduledExecutorService heartbeatScheduler) {
    this(
        hub,
        gateway,
        codec,
        sendExecutor,
        heartbeatScheduler,
        requireLimits(settings),
        Math.toIntExact(settings.sendTimeoutMillis()),
        settings.heartbeatIntervalMillis());
  }

  ApplicationEventWebSocketHandler(
      ApplicationEventHub hub,
      ShellGateway gateway,
      EventFrameCodec codec,
      ExecutorService sendExecutor,
      ScheduledExecutorService heartbeatScheduler,
      NotificationLimits limits,
      int sendTimeoutMillis,
      long heartbeatIntervalMillis) {
    this.hub = Objects.requireNonNull(hub, "hub");
    this.gateway = Objects.requireNonNull(gateway, "gateway");
    this.codec = Objects.requireNonNull(codec, "codec");
    this.limits = Objects.requireNonNull(limits, "limits");
    this.sendExecutor = Objects.requireNonNull(sendExecutor, "sendExecutor");
    this.expireTimer = Objects.requireNonNull(heartbeatScheduler, "heartbeatScheduler");
    if (sendTimeoutMillis <= 0) {
      throw new IllegalArgumentException("sendTimeoutMillis must be positive");
    }
    if (heartbeatIntervalMillis <= 0) {
      throw new IllegalArgumentException("heartbeatIntervalMillis must be positive");
    }
    this.sendTimeoutMillis = sendTimeoutMillis;
    this.heartbeatTask =
        heartbeatScheduler.scheduleAtFixedRate(
            this::heartbeat,
            heartbeatIntervalMillis,
            heartbeatIntervalMillis,
            TimeUnit.MILLISECONDS);
  }

  /**
   * 由数据库 SystemSettings.Advanced 的 {@code applicationEvent*} 软策略构造共享 carrier 预算：逻辑整包上限固定 8 MiB，
   * pending 为 {@code applicationEventMaxBytes}，queueCapacity 表示逻辑包数。
   */
  private static NotificationLimits requireLimits(ApplicationEventSettings settings) {
    Objects.requireNonNull(settings, "settings");
    long maxBytes = settings.maxBytes();
    if (maxBytes > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("applicationEventMaxBytes exceeds the supported range");
    }
    NotificationLimits defaults = NotificationLimits.defaults();
    return new NotificationLimits(
        defaults.maxMessageBytes(),
        (int) maxBytes,
        settings.queueCapacity(),
        defaults.reassemblyBytes(),
        defaults.reassemblyMessages(),
        defaults.reassemblyTimeout(),
        defaults.sendBatchFrames());
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    Session nativeSession = requireNativeSession(session);
    applyMessageBuffer(session, nativeSession);
    ConnectionState state = new ConnectionState(session, nativeSession);
    connections.put(session.getId(), state);
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    ConnectionState state = connections.get(session.getId());
    if (state != null) {
      state.acceptInbound(message.getPayload());
    }
  }

  @Override
  protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
    // binary 帧不是本通道的物理载体：确定性关闭，不回显 payload。
    closeConnection(session.getId());
    closeNative(session, CloseReason.CloseCodes.PROTOCOL_ERROR);
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) {
    closeConnection(session.getId());
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    closeConnection(session.getId());
  }

  /** 应用 shutdown：让现存浏览器连接以 1012 {@code SERVICE_RESTART} 收敛（error 帧出队后关闭），并释放全部订阅与 shell 观察。幂等。 */
  @PreDestroy
  public void shutdown() {
    if (heartbeatTask != null) {
      heartbeatTask.cancel(false);
    }
    for (ConnectionState state : connections.values()) {
      state.shutdown();
    }
  }

  /** 单次共享 heartbeat tick；只向每连接的发送器非阻塞入队。 */
  void heartbeat() {
    for (ConnectionState state : connections.values()) {
      state.heartbeat();
    }
  }

  private void closeConnection(String sessionId) {
    ConnectionState state = connections.remove(sessionId);
    if (state != null) {
      state.close();
    }
  }

  private static void applyMessageBuffer(WebSocketSession session, Session nativeSession) {
    session.setTextMessageSizeLimit(NotificationCarrier.PAYLOAD_LIMIT);
    session.setBinaryMessageSizeLimit(NotificationCarrier.PAYLOAD_LIMIT);
    nativeSession.setMaxTextMessageBufferSize(NotificationCarrier.PAYLOAD_LIMIT);
    nativeSession.setMaxBinaryMessageBufferSize(NotificationCarrier.PAYLOAD_LIMIT);
  }

  private static void closeNative(WebSocketSession session, CloseReason.CloseCodes code) {
    try {
      session.close(new CloseStatus(code.getCode(), "event channel protocol violation"));
    } catch (Exception error) {
      // 连接已不可用
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

  /** 单连接的协议状态：资源订阅表 + shell 观察 + 共享 carrier 发送链。 */
  private final class ConnectionState {

    private final WebSocketSession springSession;
    private final Session nativeSession;
    private final NotificationPeerLink link;
    private final AsyncTextSender sender;
    private final ShellGateway.Connection shell;
    private final Map<ResourceKey, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final Object closeLock = new Object();
    private boolean closed;

    private ConnectionState(WebSocketSession springSession, Session nativeSession) {
      this.springSession = springSession;
      this.nativeSession = nativeSession;
      // 每个物理浏览器连接随机 endpoint publisher：绝不复用 nodeInstanceId，避免不同连接互相冒充/回声误判。
      this.link =
          new NotificationPeerLink(
              UUID.randomUUID(),
              CARRIER_TOPIC,
              limits,
              expireTimer,
              this::handle,
              // link 已完成 fail-closed：关闭只走唯一路径；不在 carrier 回调内做重活。
              this::onLinkViolation);
      this.sender = new AsyncTextSender(nativeSession, link, sendExecutor, sendTimeoutMillis);
      this.shell =
          gateway.open(springSession.getId(), this::enqueueShellEvent, this::onShellResync);
    }

    private void acceptInbound(String rawFrame) {
      link.accept(rawFrame);
    }

    private void handle(String body) {
      EventFrameCodec.ClientFrame frame;
      try {
        frame = codec.decode(body);
      } catch (RuntimeException error) {
        fail(
            EventFrameCodec.INVALID_FRAME,
            "invalid frame: " + error.getMessage(),
            CloseReason.CloseCodes.PROTOCOL_ERROR);
        return;
      }
      if (frame instanceof EventFrameCodec.ResourceFrame resourceFrame) {
        switch (resourceFrame.type()) {
          case SUBSCRIBE -> subscribe(resourceFrame.resource());
          case UNSUBSCRIBE -> unsubscribe(resourceFrame.resource());
        }
      } else if (frame instanceof EventFrameCodec.ShellCommand shellCommand) {
        shell.receive(shellCommand.command());
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
          if (!sender.offer(
              codec.error(EventFrameCodec.RESOURCE_NOT_FOUND, "Resource not found", resource))) {
            fail(
                EventFrameCodec.BACKPRESSURE,
                "event queue is full",
                CloseReason.CloseCodes.TRY_AGAIN_LATER);
          }
          return;
        }
        if (!sender.offer(codec.subscribed(resource, subscription.cursor()))) {
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
      if (!sender.offer(frame)) {
        fail(
            EventFrameCodec.BACKPRESSURE,
            "event queue is full",
            CloseReason.CloseCodes.TRY_AGAIN_LATER);
      }
    }

    /** shell 事件编码为 shell.event 帧后入队；预算拒绝按过载关闭。 */
    private void enqueueShellEvent(TerminalEvent event) {
      if (!sender.offer(codec.shellEvent(event))) {
        fail(
            EventFrameCodec.BACKPRESSURE,
            "event queue is full",
            CloseReason.CloseCodes.TRY_AGAIN_LATER);
      }
    }

    /**
     * Bus 重连回调：网关已冻结该连接的 shell 绑定；这里以 1012 SERVICE_RESTART 关闭真实连接，等待浏览器唯一 connection 重连后显式 ATTACH。
     */
    private void onShellResync() {
      // 绝不能只清 scope.route 却让连接一直 open：必须清理观察/订阅并关闭，引导重连；绝不重发 OPEN/INPUT 等副作用。
      terminate(false);
    }

    private void heartbeat() {
      if (!sender.offer(codec.heartbeat())) {
        fail(
            EventFrameCodec.BACKPRESSURE,
            "event queue is full",
            CloseReason.CloseCodes.TRY_AGAIN_LATER);
      }
    }

    /** 发送 error 帧并在其出队后关闭连接。 */
    private void fail(String code, String message, CloseReason.CloseCodes closeCode) {
      sender.fail(codec.error(code, message), closeCode);
    }

    /** carrier 层围栏（peer/topic/target/UTF-8/缺片/超时）触发的唯一关闭入口。 */
    private void onLinkViolation() {
      closeConnection(springSession.getId());
      closeNative(springSession, CloseReason.CloseCodes.PROTOCOL_ERROR);
    }

    /** 断线收尾：释放全部订阅与 shell 观察（只 DETACH），并终止发送链。 */
    private void close() {
      terminate(true);
    }

    /** shutdown 收尾：先让 error 帧出队再关闭，但同样释放订阅与 shell 观察。 */
    private void shutdown() {
      terminate(false);
    }

    private void terminate(boolean abortPending) {
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
      // shell 观察先退出（best-effort DETACH，不关 PTY）；随后按场景终止或冲刷发送链。
      shell.close();
      if (abortPending) {
        sender.close();
      } else {
        sender.fail(
            codec.error(EventFrameCodec.SEND_FAILED, "event channel is shutting down"),
            CloseReason.CloseCodes.SERVICE_RESTART);
      }
    }
  }
}
