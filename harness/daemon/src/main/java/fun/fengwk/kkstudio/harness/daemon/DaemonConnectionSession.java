package fun.fengwk.kkstudio.harness.daemon;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.daemon.transport.DaemonConnection;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonTransport;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonTransportListener;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilitiesCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocolException;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Daemon 到 Gateway 的连接会话：唯一负责连接代际、握手、心跳与退避重连。
 *
 * <p>本会话持有全部连接状态（当前连接、代际计数、绑定 Environment、生命周期状态与失败原因），并对入站帧做协议版本与 scope 校验。每次连接尝试由独立的 {@code
 * connectionGeneration} 标识：迟到于旧连接的入站消息与回调在代际检查处直接丢弃，绝不进入当前连接的执行或控制面。
 *
 * <p>连接级帧（{@code WELCOME}/{@code ERROR}）在本会话内处理；其余帧交给 {@link Handler}。WELCOME 通告的资源预算与临时资源策略、READY
 * 之后的放行与重放、 断开后的连接作用域复位都通过 {@code Handler} 回调，使本类不持有资源、调用或更新状态。
 *
 * <p>本进程的 {@code daemonInstanceId} 由运行时构造期生成一次并在每个 HELLO 中声明；同一进程的多次重连复用同一身份，因此 Gateway 可以区分「同一
 * Daemon 重连」与「另一个 Daemon 进程接管」。wire 协议没有序号，入站消息不做跨消息顺序校验。
 */
final class DaemonConnectionSession {

  private static final Logger LOG = System.getLogger(DaemonConnectionSession.class.getName());

  /** 由会话回调的非连接级职责；实现方不得持有连接状态。 */
  interface Handler {

    /** 处理一条非连接级入站 envelope；协议异常见 {@link DaemonProtocolException} 由会话回复 ERROR。 */
    void onEnvelope(DaemonEnvelope envelope);

    /** WELCOME 通告的资源字节预算与临时资源策略；在 READY 进入传输之前应用。 */
    void onWelcomePayload(long maxResourceBytes, long ttlSeconds, long cleanupIntervalSeconds);

    /** 连接已 READY：放行连接作用域的等待并重放断开期间保留的事实。 */
    void onReady();

    /** 连接失效：会话已重置绑定，调用方据此复位连接作用域的等待与预算。 */
    void onConnectionLost();

    /** 连接级终态失败（注册被拒）：运行时据此停止重连并释放其余生命周期资源。 */
    void onTerminalFailure(String reason);
  }

  private final DaemonConfig config;
  private final DaemonTransport transport;
  private final DaemonEnvironmentInfo environmentInfo;
  private final ScheduledExecutorService scheduler;
  private final Handler handler;

  /** 本 Daemon 进程的生命周期身份：构造期随机生成一次，所有重连复用。 */
  private final String daemonInstanceId = UUID.randomUUID().toString();

  /** 本 Daemon 的构建版本：HELLO/READY 上报的事实。 */
  private final String daemonVersion = DaemonBuildInfo.version();

  private final DaemonEnvelopeCodec envelopeCodec = new DaemonEnvelopeCodec();
  private final DaemonCapabilitiesCodec capabilitiesCodec = new DaemonCapabilitiesCodec();

  /** 本连接 WELCOME 中收到的 Environment 绑定；WELCOME 之前为 null，断开时重置。 */
  private final AtomicReference<EnvironmentId> boundEnvironmentId = new AtomicReference<>();

  private final AtomicReference<ActiveConnection> activeConnection = new AtomicReference<>();
  private final AtomicLong connectionGeneration = new AtomicLong();
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicBoolean connecting = new AtomicBoolean();
  private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
  private final Object lifecycleLock = new Object();
  private final Object reconnectLock = new Object();
  private final CountDownLatch termination = new CountDownLatch(1);

  private volatile DaemonRuntimeState state = DaemonRuntimeState.STOPPED;
  private volatile String failureReason;
  private Duration nextReconnectDelay;

  DaemonConnectionSession(
      DaemonConfig config,
      DaemonTransport transport,
      DaemonEnvironmentInfo environmentInfo,
      ScheduledExecutorService scheduler,
      Handler handler) {
    this.config = Objects.requireNonNull(config, "config");
    this.transport = Objects.requireNonNull(transport, "transport");
    this.environmentInfo = Objects.requireNonNull(environmentInfo, "environmentInfo");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.handler = Objects.requireNonNull(handler, "handler");
    this.nextReconnectDelay = config.initialReconnectDelay();
  }

  /** 启动连接生命周期并立即尝试建立 WebSocket。重复调用无副作用。 */
  void start() {
    synchronized (lifecycleLock) {
      if (closed.get() || !started.compareAndSet(false, true)) {
        return;
      }
      state = DaemonRuntimeState.DISCONNECTED;
      try {
        ScheduledFuture<?> heartbeat =
            scheduler.scheduleWithFixedDelay(
                this::sendHeartbeat,
                config.heartbeatInterval().toMillis(),
                config.heartbeatInterval().toMillis(),
                TimeUnit.MILLISECONDS);
        if (!scheduleReconnect(Duration.ZERO)) {
          heartbeat.cancel(false);
          started.set(false);
          state = DaemonRuntimeState.STOPPED;
        }
      } catch (RejectedExecutionException ignored) {
        // 调度器已停机：回到未启动状态，不允许停在「已 started 但永不连接」的半启动态。
        started.set(false);
        state = DaemonRuntimeState.STOPPED;
      }
    }
  }

  /** 当前生命周期状态。 */
  DaemonRuntimeState state() {
    return state;
  }

  /** 终态失败原因；非 FAILED 时为 {@code null}。 */
  String failureReason() {
    return failureReason;
  }

  /** 本连接 WELCOME 绑定的 Environment；未绑定或断开后为 {@code null}。 */
  EnvironmentId boundEnvironmentId() {
    return boundEnvironmentId.get();
  }

  /** 会话是否处于已启动（尚未进入终态关闭）状态。 */
  boolean isStarted() {
    return started.get();
  }

  /** 阻塞等待会话终止（由 {@link #beginShutdown} 触发）；返回终态。 */
  DaemonRuntimeState awaitTermination() throws InterruptedException {
    termination.await();
    return state;
  }

  /**
   * 把当前连接上的报文交给活跃连接；返回 {@code false} 表示没有可递交的连接（报文确定未发送）。
   *
   * <p>是否允许发送（例如仅在 READY 时发送 UPDATE_RESULT）由调用方按自身语义判断。
   */
  boolean send(DaemonMessageType messageType, String invocationId, String payloadJson) {
    ActiveConnection connection = activeConnection.get();
    return connection != null && sendOn(connection, messageType, invocationId, payloadJson);
  }

  /**
   * 进入终态关闭：幂等，只有首个调用者继续执行收尾；先停止传输接入并关闭当前连接与 transport。
   *
   * @return true 表示本调用者赢得关闭，应继续释放其余生命周期资源并最终调用 {@link #signalTerminated()}
   */
  boolean beginShutdown(DaemonRuntimeState terminalState, String reason) {
    ActiveConnection connection;
    synchronized (lifecycleLock) {
      if (!closed.compareAndSet(false, true)) {
        return false;
      }
      started.set(false);
      state = terminalState;
      failureReason = reason;
      connection = activeConnection.getAndSet(null);
    }
    if (connection != null) {
      closeConnectionQuietly(connection.connection(), "shutdown");
    }
    closeTransportQuietly();
    return true;
  }

  /** 释放终止闩；由赢得关闭的调用者在全部清理完成后调用一次。 */
  void signalTerminated() {
    termination.countDown();
  }

  private boolean scheduleReconnect(Duration delay) {
    if (!started.get() || !reconnectScheduled.compareAndSet(false, true)) {
      return started.get();
    }
    try {
      scheduler.schedule(
          () -> {
            reconnectScheduled.set(false);
            connect();
          },
          delay.toMillis(),
          TimeUnit.MILLISECONDS);
      return true;
    } catch (RejectedExecutionException ignored) {
      reconnectScheduled.set(false);
      return false;
    }
  }

  private void connect() {
    if (!started.get() || !connecting.compareAndSet(false, true)) {
      return;
    }
    state = DaemonRuntimeState.CONNECTING;
    // 每次尝试连接递增代际，使旧连接的延迟回调与已过时代际事件失效。
    long generation = connectionGeneration.incrementAndGet();
    DaemonTransportListener listener = new TransportListener(generation);
    try {
      transport
          .connect(listener)
          .whenComplete(
              (connection, error) -> {
                connecting.set(false);
                if (error != null || connection == null) {
                  handleDisconnected(generation);
                  return;
                }
                // 若运行时已关闭或代际已过时，安全释放新连接。
                if (!started.get() || generation != connectionGeneration.get()) {
                  closeConnectionQuietly(connection, "superseded connection");
                  return;
                }
                ActiveConnection active = new ActiveConnection(generation, connection);
                synchronized (active) {
                  activeConnection.set(active);
                  nextReconnectDelay = config.initialReconnectDelay();
                  if (sendHello(active)) {
                    active.markHelloSent();
                  } else {
                    // HELLO 未进入传输：停留在 CONNECTING 会挡住后续重连，必须关闭这条连接。
                    closeFailedHandshake(active);
                  }
                }
              });
    } catch (RuntimeException error) {
      connecting.set(false);
      handleDisconnected(generation);
    }
  }

  private final class TransportListener implements DaemonTransportListener {

    private final long generation;

    private TransportListener(long generation) {
      this.generation = generation;
    }

    @Override
    public void onMessage(String message) {
      DaemonConnectionSession.this.onMessage(generation, message);
    }

    @Override
    public void onDisconnected(Throwable cause) {
      handleDisconnected(generation);
    }
  }

  private void handleDisconnected(long generation) {
    ActiveConnection connection = activeConnection.get();
    // 仅当断开事件与当前活跃连接代际一致时执行清理。
    if (connection != null) {
      if (connection.generation() != generation
          || !activeConnection.compareAndSet(connection, null)) {
        return;
      }
    } else if (state != DaemonRuntimeState.CONNECTING) {
      return;
    }
    if (!started.get() || generation != connectionGeneration.get()) {
      return;
    }
    // 连接失效不影响 Invocation journal；复位连接作用域的等待、预算与绑定。
    handler.onConnectionLost();
    boundEnvironmentId.set(null);
    state = DaemonRuntimeState.DISCONNECTED;
    Duration delay;
    synchronized (reconnectLock) {
      delay = nextReconnectDelay;
      nextReconnectDelay = doubled(nextReconnectDelay, config.maxReconnectDelay());
    }
    scheduleReconnect(delay);
  }

  private static Duration doubled(Duration value, Duration maximum) {
    try {
      Duration doubled = value.multipliedBy(2);
      return doubled.compareTo(maximum) > 0 ? maximum : doubled;
    } catch (ArithmeticException error) {
      return maximum;
    }
  }

  private boolean sendHello(ActiveConnection connection) {
    ObjectNode payload = envelopeCodec.createPayload();
    payload.put("protocolVersion", DaemonProtocol.VERSION);
    payload.put("registrationToken", config.registrationToken());
    payload.put("capabilityCatalogVersion", EnvironmentCapabilityCatalog.version());
    payload.put("daemonVersion", daemonVersion);
    payload.put("daemonInstanceId", daemonInstanceId);
    return sendOn(connection, DaemonMessageType.HELLO, null, envelopeCodec.writeJson(payload));
  }

  private boolean sendReady(ActiveConnection connection) {
    DaemonCapabilities capabilities =
        new DaemonCapabilities(DaemonCapabilities.VERSION, daemonVersion, environmentInfo);
    return sendOn(
        connection, DaemonMessageType.READY, null, capabilitiesCodec.encode(capabilities));
  }

  private void sendHeartbeat() {
    if (state == DaemonRuntimeState.READY) {
      send(DaemonMessageType.HEARTBEAT, null, "{}");
    }
  }

  private void onMessage(long generation, String rawMessage) {
    ActiveConnection connection = activeConnection.get();
    if (connection == null || connection.generation() != generation) {
      return;
    }
    try {
      DaemonEnvelope envelope = envelopeCodec.decode(rawMessage);
      requireProtocolVersion(envelope);
      verifyScope(envelope);
      switch (envelope.messageType()) {
        case WELCOME -> handleWelcome(connection, envelope);
        case ERROR -> handleError(connection, envelope);
        default -> handler.onEnvelope(envelope);
      }
    } catch (IllegalArgumentException error) {
      sendOn(connection, DaemonMessageType.ERROR, null, DaemonFailurePayload.error(error));
    }
  }

  private void requireProtocolVersion(DaemonEnvelope envelope) {
    if (envelope.protocolVersion() != DaemonProtocol.VERSION) {
      throw new DaemonProtocolException(
          "daemon runtime requires protocolVersion " + DaemonProtocol.VERSION);
    }
  }

  private void handleWelcome(ActiveConnection connection, DaemonEnvelope envelope) {
    synchronized (connection) {
      if (!connection.helloSent()) {
        throw new DaemonProtocolException("WELCOME requires a preceding HELLO");
      }
      if (!connection.markWelcomed()) {
        throw new DaemonProtocolException("WELCOME may only be received once per connection");
      }
      this.boundEnvironmentId.set(
          Objects.requireNonNull(envelope.environmentId(), "WELCOME environmentId"));
      ObjectNode payload = envelopeCodec.readPayload(envelope);
      // 资源字节预算与临时资源策略由服务端在 WELCOME 中通告；缺失或非正数都 fail closed。
      long maxResourceBytes =
          DaemonProtocolPayloads.requiredPositiveLong(payload, "WELCOME", "maxResourceBytes");
      long ttlSeconds =
          DaemonProtocolPayloads.requiredPositiveLong(
              payload, "WELCOME", "temporaryResourceTtlSeconds");
      long cleanupIntervalSeconds =
          DaemonProtocolPayloads.requiredPositiveLong(
              payload, "WELCOME", "temporaryResourceCleanupIntervalSeconds");
      handler.onWelcomePayload(maxResourceBytes, ttlSeconds, cleanupIntervalSeconds);
      if (!sendReady(connection)) {
        // WELCOME 已接受但 READY 未进入传输：不得停在 CONNECTING，关闭后由既有重连恢复。
        if (activeConnection.get() == connection) {
          closeFailedHandshake(connection);
        }
        return;
      }
      if (activeConnection.get() == connection) {
        connection.markReady();
        state = DaemonRuntimeState.READY;
        // 只有 READY 连接才能递交上传控制帧；放行在断连期间等待重连的上传继续重放同一 transfer。
        handler.onReady();
      }
    }
  }

  /** 握手帧未成功递交时关闭当前连接，让既有断开路径调度重连；不额外轮询。 */
  private void closeFailedHandshake(ActiveConnection connection) {
    closeConnectionQuietly(connection.connection(), "failed handshake");
    handleDisconnected(connection.generation());
  }

  /**
   * 处理 Gateway ERROR。注册凭证被拒（REGISTRATION_REJECTED）是终态的；RETRY_LATER 触发退避重连；其余 ERROR 不改变 Invocation
   * journal 事实。
   */
  private void handleError(ActiveConnection connection, DaemonEnvelope envelope) {
    ObjectNode payload = envelopeCodec.readPayload(envelope);
    List<String> unexpected = new ArrayList<>();
    payload
        .propertyNames()
        .forEach(
            field -> {
              if (!"message".equals(field) && !"code".equals(field)) {
                unexpected.add(field);
              }
            });
    if (!unexpected.isEmpty()) {
      throw new DaemonProtocolException("ERROR payload has unexpected fields: " + unexpected);
    }
    JsonNode code = payload.get("code");
    if (code == null || code.isNull()) {
      return;
    }
    if (!code.isString()) {
      throw new DaemonProtocolException("ERROR payload.code is unknown: " + code);
    }
    String codeValue = code.stringValue();
    if (DaemonProtocol.ERROR_CODE_REGISTRATION_REJECTED.equals(codeValue)) {
      String message = payload.path("message").asString("");
      handler.onTerminalFailure(
          "environment registration is rejected" + (message.isBlank() ? "" : ": " + message));
      return;
    }
    if (DaemonProtocol.ERROR_CODE_RETRY_LATER.equals(codeValue)) {
      handleDisconnected(connection.generation());
      return;
    }
    throw new DaemonProtocolException("ERROR payload.code is unknown: " + code);
  }

  private void verifyScope(DaemonEnvelope envelope) {
    if (envelope.messageType() == DaemonMessageType.HELLO) {
      if (envelope.environmentId() != null) {
        throw new DaemonProtocolException("HELLO must not declare environmentId");
      }
      return;
    }
    if (envelope.messageType() == DaemonMessageType.WELCOME) {
      if (envelope.environmentId() == null) {
        throw new DaemonProtocolException("WELCOME must declare non-null environmentId");
      }
      return;
    }
    EnvironmentId bound = boundEnvironmentId.get();
    if (bound != null && envelope.environmentId() != null) {
      if (!bound.equals(envelope.environmentId())) {
        throw new DaemonProtocolException(
            "envelope environmentId does not match daemon: " + envelope.environmentId());
      }
    }
  }

  private boolean sendOn(
      ActiveConnection connection,
      DaemonMessageType messageType,
      String invocationId,
      String payloadJson) {
    if (activeConnection.get() != connection || !connection.connection().isOpen()) {
      return false;
    }
    DaemonEnvelope envelope = envelope(messageType, invocationId, payloadJson);
    connection
        .connection()
        .sendText(envelopeCodec.encode(envelope))
        .whenComplete(
            (ignored, error) -> {
              if (error != null) {
                handleDisconnected(connection.generation());
              }
            });
    return true;
  }

  /**
   * Daemon 出站 envelope：HELLO 在认证前没有 Environment scope（必须为 null）；其余消息都由已绑定连接发出（携带本 daemon 的 {@link
   * EnvironmentId}）。
   */
  private DaemonEnvelope envelope(
      DaemonMessageType messageType, String invocationId, String payloadJson) {
    boolean hello = messageType == DaemonMessageType.HELLO;
    return new DaemonEnvelope(
        DaemonProtocol.VERSION,
        messageType,
        hello ? null : boundEnvironmentId.get(),
        invocationId,
        payloadJson);
  }

  private void closeTransportQuietly() {
    try {
      transport.close();
    } catch (RuntimeException error) {
      // 连接失败收尾：transport 关闭失败不影响其余清理，只记录安全阶段与异常类型。
      LOG.log(
          Level.DEBUG,
          "daemon transport close failed during shutdown: " + error.getClass().getSimpleName());
    }
  }

  private void closeConnectionQuietly(DaemonConnection connection, String phase) {
    try {
      connection.close();
    } catch (RuntimeException error) {
      // 关闭失败仍要走代际断开，避免停在 CONNECTING；只记录安全阶段与异常类型。
      LOG.log(
          Level.DEBUG,
          "daemon connection close failed at " + phase + ": " + error.getClass().getSimpleName());
    }
  }

  private static final class ActiveConnection {
    private final long generation;
    private final DaemonConnection connection;
    private final AtomicBoolean helloSent = new AtomicBoolean();
    private final AtomicBoolean welcomed = new AtomicBoolean();
    private final AtomicBoolean ready = new AtomicBoolean();

    private ActiveConnection(long generation, DaemonConnection connection) {
      this.generation = generation;
      this.connection = connection;
    }

    long generation() {
      return generation;
    }

    DaemonConnection connection() {
      return connection;
    }

    void markHelloSent() {
      helloSent.set(true);
    }

    boolean helloSent() {
      return helloSent.get();
    }

    boolean markWelcomed() {
      return welcomed.compareAndSet(false, true);
    }

    void markReady() {
      ready.set(true);
    }
  }
}
