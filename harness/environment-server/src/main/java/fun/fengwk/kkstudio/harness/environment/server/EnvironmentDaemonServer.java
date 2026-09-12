package fun.fengwk.kkstudio.harness.environment.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.environment.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCancelledException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityFailedException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTransport;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilitiesCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilityInvokeCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilityResultCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocolException;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Environment daemon 的服务端会话核心：唯一拥有连接代际的握手、sequence、lease、pending invocation 与终态状态。
 *
 * <p>协议为当前 daemon wire v1：HELLO scope 为 null 且 payload 携带 {@code registrationToken}；核心通过 {@link
 * DaemonRegistrationDirectory} 解析环境身份，通过 {@link DaemonLeaseStore} 以 {@code (environment_id,
 * owner_node_id, lease_token)} 围栏原子抢占路由（已有活跃路由返回 RETRY_LATER），成功后下发 WELCOME。READY、HEARTBEAT
 * 与断开连接同样以围栏推进状态； 租约存储不可用时 fail-closed。
 *
 * <p><b>并发所有权：</b>调用只按 {@code (environmentId, invocationId)} 关联；同一 Environment 的多个 invocation
 * 立即发送并并发持有， 不存在 per-Environment 并发槽位、队列或容量配置。同一 Environment 内重复的活动 {@code invocationId} 属于调用方错误。
 *
 * <p><b>终态唯一：</b>COMPLETED/FAILED/CANCELLED 回调、连接清理与显式 {@link #expire} 竞争时只有一个赢家；已知 invocation 的迟到
 * PARTIAL/STARTED 静默丢弃，未知 invocation 的回调按协议违规关闭连接。
 *
 * <p><b>锁边界：</b>每个连接一代的 {@code gate} 只串行化入站协议处理；{@code state} 只保护该连接的协议字段；{@code inventory} 保护连接与
 * invocation 目录。锁顺序固定为 {@code gate > state > inventory}；租约存储访问、会话监听器回调与连接关闭都在这些锁之外执行。
 *
 * <p>本类只依赖 JDK、Jackson、harness.common 与 harness.environment 契约，不含 Spring/JDBC/产品 DTO。
 */
public final class EnvironmentDaemonServer
    implements EnvironmentCapabilityTransport, DaemonEndpoint {

  private static final int MAX_INVOCATION_TOMBSTONES = 1024;

  private final DaemonLeaseStore leaseStore;
  private final DaemonRegistrationDirectory registrationDirectory;
  private final EnvironmentSessionListener sessionListener;
  private final Supplier<EnvironmentServerSettings> settings;
  private final DaemonCapabilitiesCodec capabilitiesCodec = new DaemonCapabilitiesCodec();
  private final DaemonCapabilityInvokeCodec capabilityInvokeCodec =
      new DaemonCapabilityInvokeCodec();
  private final DaemonCapabilityResultCodec resultCodec = new DaemonCapabilityResultCodec();
  private final DaemonEnvelopeCodec envelopeCodec = new DaemonEnvelopeCodec();

  /** 保护连接目录与 invocation 目录；绝不在持有该锁时获取任何连接锁或访问外部端口。 */
  private final Object inventory = new Object();

  private final Map<String, ConnectionState> connections = new HashMap<>();
  private final Map<EnvironmentId, ConnectionState> environmentConnections = new HashMap<>();
  private final Map<EnvironmentId, LinkedHashMap<UUID, ActiveInvocation>> invocationsByEnvironment =
      new HashMap<>();
  private final Map<EnvironmentId, ArrayDeque<UUID>> invocationTombstones = new HashMap<>();

  public EnvironmentDaemonServer(
      DaemonLeaseStore leaseStore,
      DaemonRegistrationDirectory registrationDirectory,
      EnvironmentSessionListener sessionListener,
      Supplier<EnvironmentServerSettings> settings) {
    this.leaseStore = Objects.requireNonNull(leaseStore, "leaseStore");
    this.registrationDirectory =
        Objects.requireNonNull(registrationDirectory, "registrationDirectory");
    this.sessionListener = Objects.requireNonNull(sessionListener, "sessionListener");
    this.settings = Objects.requireNonNull(settings, "settings");
  }

  /** 建立一条新的连接代际；同 connectionId 的旧代际被幂等清理后由新代际取代。 */
  @Override
  public void open(DaemonChannel channel) {
    Objects.requireNonNull(channel, "channel");
    String connectionId = requireNonBlank(channel.connectionId(), "connectionId");
    ConnectionState state = new ConnectionState(channel);
    ConnectionState previous;
    synchronized (inventory) {
      previous = connections.put(connectionId, state);
    }
    if (previous != null) {
      closeConnectionState(previous);
    }
  }

  /** 解码并处理一条入站文本帧；协议违规以 ERROR 帧回应后关闭该连接。 */
  @Override
  public void receive(String connectionId, String rawMessage) {
    Objects.requireNonNull(connectionId, "connectionId");
    Objects.requireNonNull(rawMessage, "rawMessage");
    ConnectionState state;
    synchronized (inventory) {
      state = connections.get(connectionId);
    }
    if (state == null) {
      return;
    }
    synchronized (state.gate) {
      List<Runnable> deferred = new ArrayList<>();
      RuntimeException protocolError = null;
      EnvironmentId receivedEnvironmentId = null;
      try {
        DaemonEnvelope envelope = envelopeCodec.decode(rawMessage);
        receivedEnvironmentId = envelope.environmentId();
        if (!state.acceptInbound(envelope, envelopeCodec.readPayload(envelope))) {
          return;
        }
        handleInbound(state, envelope, deferred);
      } catch (RuntimeException error) {
        protocolError = error;
      }
      if (protocolError != null) {
        protocolFailure(state, protocolError, receivedEnvironmentId);
        return;
      }
      runDeferred(deferred);
    }
  }

  /** 幂等关闭连接代际：解绑路由、终结全部未完成调用并关闭传输。 */
  @Override
  public void close(String connectionId) {
    Objects.requireNonNull(connectionId, "connectionId");
    ConnectionState state;
    synchronized (inventory) {
      state = connections.remove(connectionId);
    }
    if (state != null) {
      closeConnectionState(state);
    }
  }

  /** 当前节点该 Environment 是否已有 READY 的活跃连接代际。 */
  public boolean isReady(EnvironmentId environmentId) {
    Objects.requireNonNull(environmentId, "environmentId");
    ConnectionState state;
    synchronized (inventory) {
      state = environmentConnections.get(environmentId);
    }
    return state != null && state.isReady();
  }

  /**
   * 本节点该 Environment 的活跃连接是否仍持有有效的围栏租约。
   *
   * <p>用于适配层判断本次请求能否由本节点承接：无本地连接、连接尚未取得租约或租约存储不可用时返回 false（fail-closed），其余情况透传 {@link
   * DaemonLeaseStore#holdsReadyLease} 的判定（生产实现同时要求数据库侧状态为 READY）。本方法只表达租约归属，不改变本地会话状态； 真正的发送准入仍由
   * {@link #invoke} 复核。
   */
  public boolean holdsReadyLease(EnvironmentId environmentId) {
    Objects.requireNonNull(environmentId, "environmentId");
    ConnectionState state;
    synchronized (inventory) {
      state = environmentConnections.get(environmentId);
    }
    if (state == null) {
      return false;
    }
    UUID leaseToken = state.leaseToken;
    if (leaseToken == null) {
      return false;
    }
    try {
      return leaseStore.holdsReadyLease(environmentId, leaseToken);
    } catch (RuntimeException error) {
      return false;
    }
  }

  /** 当前节点全部 READY 的 Environment 快照。 */
  public Set<EnvironmentId> readyEnvironments() {
    Set<EnvironmentId> ready = new HashSet<>();
    synchronized (inventory) {
      for (Map.Entry<EnvironmentId, ConnectionState> entry : environmentConnections.entrySet()) {
        if (entry.getValue().isReady()) {
          ready.add(entry.getKey());
        }
      }
    }
    return Set.copyOf(ready);
  }

  @Override
  public EnvironmentCapabilityExecutionHandle invoke(
      EnvironmentBinding binding,
      EnvironmentCapabilityExecutionRequest request,
      EnvironmentCapabilityExecutionListener listener) {
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");
    EnvironmentCapabilityDescriptor descriptor = request.descriptor();
    EnvironmentCapabilityDescriptor catalogDescriptor =
        EnvironmentCapabilityCatalog.require(descriptor.id());
    if (!catalogDescriptor.equals(descriptor)) {
      throw new IllegalArgumentException(
          "capability descriptor does not match EnvironmentCapabilityCatalog: " + descriptor.id());
    }
    if (request.workdir() != null) {
      throw new IllegalArgumentException("Environment capability request workdir must be null");
    }
    UUID invocationId = parseUuid(request.call().id(), "call.id");
    EnvironmentId environmentId = binding.environmentId();

    ConnectionState state;
    synchronized (inventory) {
      state = environmentConnections.get(environmentId);
    }
    UUID leaseToken = state == null ? null : state.leaseToken;
    if (state == null || leaseToken == null || !state.isReady()) {
      throw unavailable(environmentId, descriptor.id().value());
    }

    // 租约存储访问（可能跨进程/网络）绝不在核心状态锁内执行。
    boolean holdsReady;
    try {
      holdsReady = leaseStore.holdsReadyLease(environmentId, leaseToken);
    } catch (RuntimeException error) {
      throw new EnvironmentCapabilityUnavailableException(
          "database unavailable: " + error.getMessage(), error);
    }
    if (!holdsReady) {
      throw unavailable(environmentId, descriptor.id().value());
    }

    String invokePayload =
        capabilityInvokeCodec.encode(
            new DaemonCapabilityInvokeCodec.InvokeRequest(
                descriptor.id(),
                descriptor.version(),
                binding.workspacePath(),
                request.call().argumentsJson(),
                request.timeout()));
    ActiveInvocation active = new ActiveInvocation(environmentId, state, invocationId, listener);
    DaemonSendOutcome outcome;
    synchronized (state) {
      if (!state.isReady() || !leaseToken.equals(state.leaseToken)) {
        throw unavailable(environmentId, descriptor.id().value());
      }
      register(active);
      outcome = state.sendOutcome(DaemonMessageType.INVOKE, invocationId.toString(), invokePayload);
    }
    if (outcome == DaemonSendOutcome.SENT) {
      return active;
    }
    unregister(active);
    active.markTerminal();
    if (outcome == DaemonSendOutcome.NOT_SENT) {
      // 连接在准入与写入之间失效：帧肯定未送达 daemon，调用肯定未执行。
      throw unavailable(environmentId, descriptor.id().value());
    }
    close(state.connection.connectionId());
    throw new EnvironmentCapabilitySendUncertainException(
        "INVOKE send outcome is uncertain for environment " + environmentId);
  }

  /**
   * 终结一次由调用方判定超时或放弃的调用：移出活动目录、登记 tombstone 并至多发送一次 CANCEL。
   *
   * <p>该方法不向 listener 发送终态；超时判定由调用方在自身 deadline 上完成。
   */
  public void expire(EnvironmentCapabilityExecutionHandle handle) {
    Objects.requireNonNull(handle, "handle");
    if (!(handle instanceof ActiveInvocation active)) {
      throw new IllegalArgumentException("execution handle is not owned by this server");
    }
    boolean shouldCancel;
    synchronized (active) {
      active.cancelled = true;
      if (active.terminal.get() || active.cancelSent.getAndSet(true)) {
        return;
      }
      shouldCancel = true;
    }
    ConnectionState state;
    synchronized (inventory) {
      if (!unregister(active)) {
        return;
      }
      active.markTerminal();
      state = environmentConnections.get(active.environmentId);
      if (state != active.connection) {
        return;
      }
      addTombstone(active.environmentId, active.invocationId);
    }
    if (shouldCancel) {
      send(state, DaemonMessageType.CANCEL, active.invocationId.toString(), "{}");
    }
  }

  private void register(ActiveInvocation active) {
    synchronized (inventory) {
      LinkedHashMap<UUID, ActiveInvocation> invocations =
          invocationsByEnvironment.computeIfAbsent(
              active.environmentId, ignored -> new LinkedHashMap<>());
      if (invocations.containsKey(active.invocationId)) {
        throw new IllegalArgumentException(
            "invocationId is already active for environment "
                + active.environmentId
                + ": "
                + active.invocationId);
      }
      invocations.put(active.invocationId, active);
    }
  }

  private boolean unregister(ActiveInvocation active) {
    synchronized (inventory) {
      LinkedHashMap<UUID, ActiveInvocation> invocations =
          invocationsByEnvironment.get(active.environmentId);
      if (invocations == null || invocations.remove(active.invocationId) == null) {
        return false;
      }
      if (invocations.isEmpty()) {
        invocationsByEnvironment.remove(active.environmentId);
      }
      return true;
    }
  }

  private ActiveInvocation registeredInvocation(EnvironmentId environmentId, UUID invocationId) {
    synchronized (inventory) {
      LinkedHashMap<UUID, ActiveInvocation> invocations =
          invocationsByEnvironment.get(environmentId);
      return invocations == null ? null : invocations.get(invocationId);
    }
  }

  private boolean consumeTombstone(EnvironmentId environmentId, UUID invocationId) {
    synchronized (inventory) {
      ArrayDeque<UUID> tombstones = invocationTombstones.get(environmentId);
      if (tombstones == null || !tombstones.remove(invocationId)) {
        return false;
      }
      if (tombstones.isEmpty()) {
        invocationTombstones.remove(environmentId);
      }
      return true;
    }
  }

  private boolean hasTombstone(EnvironmentId environmentId, UUID invocationId) {
    synchronized (inventory) {
      ArrayDeque<UUID> tombstones = invocationTombstones.get(environmentId);
      return tombstones != null && tombstones.contains(invocationId);
    }
  }

  private void addTombstone(EnvironmentId environmentId, UUID invocationId) {
    synchronized (inventory) {
      ArrayDeque<UUID> tombstones =
          invocationTombstones.computeIfAbsent(environmentId, ignored -> new ArrayDeque<>());
      tombstones.remove(invocationId);
      tombstones.addLast(invocationId);
      while (tombstones.size() > MAX_INVOCATION_TOMBSTONES) {
        tombstones.removeFirst();
      }
    }
  }

  /** 校验入站信封协议版本与连接合法性，并按消息类型分派处理。 */
  private void handleInbound(
      ConnectionState state, DaemonEnvelope envelope, List<Runnable> deferred) {
    if (envelope.protocolVersion() != DaemonProtocol.VERSION) {
      throw new DaemonProtocolException(
          "daemon envelope protocolVersion must be " + DaemonProtocol.VERSION);
    }
    if (!state.helloReceived && envelope.messageType() != DaemonMessageType.HELLO) {
      throw new DaemonProtocolException("HELLO must be the first daemon message");
    }
    if (state.environmentId != null && !state.environmentId.equals(envelope.environmentId())) {
      throw new DaemonProtocolException(
          "envelope environmentId does not match bound connection: " + envelope.environmentId());
    }
    switch (envelope.messageType()) {
      case HELLO -> handleHello(state, envelope);
      case READY -> handleReady(state, envelope, deferred);
      case HEARTBEAT -> handleHeartbeat(state, envelope);
      case STARTED -> handleStarted(state, envelope);
      case PARTIAL -> handlePartial(state, envelope, deferred);
      case COMPLETED -> handleCompleted(state, envelope, deferred);
      case FAILED -> handleFailed(state, envelope, deferred);
      case CANCELLED -> handleCancelled(state, envelope, deferred);
      case ACK -> handleAck(state, envelope);
      case ERROR -> handleError(state, envelope);
      case WELCOME, INVOKE, CANCEL -> throw new DaemonProtocolException(
          "daemon must not send " + envelope.messageType() + " to server");
    }
  }

  /** 处理 daemon 首帧 HELLO：解析注册凭据、抢占路由租约并完成 WELCOME 回包。 */
  private void handleHello(ConnectionState state, DaemonEnvelope envelope) {
    if (state.helloReceived) {
      throw new DaemonProtocolException("HELLO may only be sent once per connection");
    }
    requireNoInvocationId(envelope);
    if (envelope.environmentId() != null) {
      throw new DaemonProtocolException("HELLO must not declare environmentId");
    }
    ObjectNode payload = envelopeCodec.readPayload(envelope);
    rejectUnexpectedFields(
        payload,
        Set.of("protocolVersion", "registrationToken", "capabilityCatalogVersion"),
        "HELLO payload");
    if (envelope.protocolVersion() != DaemonProtocol.VERSION
        || requiredLong(payload, "protocolVersion", "HELLO payload") != DaemonProtocol.VERSION) {
      throw new DaemonProtocolException("HELLO protocolVersion must be " + DaemonProtocol.VERSION);
    }
    if (!EnvironmentCapabilityCatalog.version()
        .equals(requiredText(payload, "capabilityCatalogVersion", "HELLO payload"))) {
      throw new DaemonProtocolException(
          "HELLO capabilityCatalogVersion does not match server catalog");
    }
    String registrationToken = requiredText(payload, "registrationToken", "HELLO payload");

    DaemonRegistration registration =
        registrationDirectory
            .findByRegistrationToken(registrationToken)
            .orElseThrow(
                () -> new DaemonRegistrationRejectedException("invalid registration token"));
    EnvironmentId environmentId = registration.environmentId();

    ConnectionState holder = otherConnectionOf(state, environmentId);
    UUID holderToken = holder == null ? null : holder.leaseToken;
    if (holder != null && holderToken != null && holder.connection.isOpen()) {
      boolean activeToken;
      try {
        activeToken = leaseStore.hasActiveLeaseToken(environmentId, holderToken);
      } catch (RuntimeException error) {
        throw new DaemonProtocolException("database unavailable: " + error.getMessage(), error);
      }
      if (activeToken) {
        throw new DaemonRetryLaterException(
            "environment "
                + environmentId
                + " is actively held by another connection on this node");
      }
    }

    LeaseBindResult bind =
        leaseStore.tryAcquire(environmentId, registrationToken, settings().heartbeatTimeout());
    if (bind instanceof LeaseBindResult.Rejected rejected) {
      throw new DaemonRegistrationRejectedException(rejected.message());
    }
    if (bind instanceof LeaseBindResult.RetryLater retryLater) {
      throw new DaemonRetryLaterException(retryLater.message());
    }
    if (bind instanceof LeaseBindResult.Acquired acquired) {
      ConnectionState previous = otherConnectionOf(state, environmentId);
      if (previous != null) {
        closeConnectionState(previous);
      }
      state.bind(environmentId, acquired.leaseToken());
      synchronized (inventory) {
        environmentConnections.put(environmentId, state);
      }
      ObjectNode welcomePayload = envelopeCodec.createPayload();
      welcomePayload.put("environmentId", environmentId.toString());
      welcomePayload.put("name", registration.displayName());
      state.send(DaemonMessageType.WELCOME, null, welcomePayload.toString());
    }
  }

  private ConnectionState otherConnectionOf(ConnectionState self, EnvironmentId environmentId) {
    synchronized (inventory) {
      ConnectionState existing = environmentConnections.get(environmentId);
      return existing == self ? null : existing;
    }
  }

  /** 处理 daemon READY 声明：围栏式登记 READY 能力并在锁外唤醒会话监听器。 */
  private void handleReady(
      ConnectionState state, DaemonEnvelope envelope, List<Runnable> deferred) {
    requireHello(state);
    requireNoInvocationId(envelope);
    EnvironmentId environmentId;
    UUID leaseToken;
    synchronized (state) {
      if (state.ready) {
        throw new DaemonProtocolException("READY may only be sent once per connection");
      }
      environmentId = state.environmentId;
      leaseToken = state.leaseToken;
    }
    if (leaseToken == null) {
      throw new DaemonProtocolException("leaseToken missing on READY");
    }
    DaemonCapabilities capabilities = capabilitiesCodec.decode(envelope.payloadJson());
    boolean ok = leaseStore.markReady(environmentId, leaseToken, capabilities, timeout());
    if (!ok) {
      throw new DaemonProtocolException("route fence lost for environment " + environmentId);
    }
    synchronized (state) {
      if (state.cleaned || !leaseToken.equals(state.leaseToken)) {
        throw new DaemonProtocolException("route fence lost for environment " + environmentId);
      }
      state.ready = true;
    }
    deferred.add(() -> notifyEnvironmentReady(environmentId));
  }

  private void handleHeartbeat(ConnectionState state, DaemonEnvelope envelope) {
    requireReady(state);
    requireNoInvocationId(envelope);
    rejectUnexpectedFields(envelopeCodec.readPayload(envelope), Set.of(), "HEARTBEAT payload");
    EnvironmentId environmentId = state.environmentId;
    UUID leaseToken = state.leaseToken;
    if (leaseToken == null) {
      throw new DaemonProtocolException("leaseToken missing on HEARTBEAT");
    }
    boolean ok = leaseStore.heartbeat(environmentId, leaseToken, timeout());
    if (!ok) {
      throw new DaemonProtocolException("route fence lost for environment " + environmentId);
    }
  }

  private void handleAck(ConnectionState state, DaemonEnvelope envelope) {
    requireReady(state);
    requireNoInvocationId(envelope);
    ObjectNode payload = envelopeCodec.readPayload(envelope);
    rejectUnexpectedFields(payload, Set.of("acknowledgedSequence"), "ACK payload");
    long acknowledgedSequence = requiredLong(payload, "acknowledgedSequence", "ACK payload");
    long sent;
    synchronized (state) {
      sent = state.outboundSequence;
    }
    if (acknowledgedSequence < 0 || acknowledgedSequence >= sent) {
      throw new DaemonProtocolException("ACK payload.acknowledgedSequence is not a sent frame");
    }
  }

  private void handleError(ConnectionState state, DaemonEnvelope envelope) {
    requireHello(state);
    requireNoInvocationId(envelope);
    requiredSingleText(envelopeCodec.readPayload(envelope), "message", "ERROR payload");
  }

  private void handleStarted(ConnectionState state, DaemonEnvelope envelope) {
    callbackTarget(state, envelope);
    ObjectNode payload = envelopeCodec.readPayload(envelope);
    if (payload.isEmpty()) {
      return;
    }
    rejectUnexpectedFields(payload, Set.of("replayed"), "STARTED payload");
    JsonNode replayed = payload.get("replayed");
    if (replayed == null || !replayed.isBoolean() || !replayed.booleanValue()) {
      throw new DaemonProtocolException("STARTED payload.replayed must be true when present");
    }
  }

  private void handlePartial(
      ConnectionState state, DaemonEnvelope envelope, List<Runnable> deferred) {
    ActiveInvocation active = callbackTarget(state, envelope);
    EnvironmentCapabilityResult result =
        resultCodec.decodePartialForInvocation(
            envelope.payloadJson(), envelope.invocationId(), settings().maxResourceBytes());
    if (active != null && isActive(active)) {
      deferred.add(() -> active.listener.onPartial(result));
    }
  }

  private void handleCompleted(
      ConnectionState state, DaemonEnvelope envelope, List<Runnable> deferred) {
    callbackTarget(state, envelope);
    EnvironmentCapabilityResult result =
        resultCodec.decodeCompletedForInvocation(
            envelope.payloadJson(), envelope.invocationId(), settings().maxResourceBytes());
    ActiveInvocation active = takeTerminalTarget(state, envelope);
    if (active != null) {
      deferred.add(() -> active.listener.onComplete(result));
    }
  }

  private void handleFailed(
      ConnectionState state, DaemonEnvelope envelope, List<Runnable> deferred) {
    callbackTarget(state, envelope);
    String message =
        requiredSingleText(envelopeCodec.readPayload(envelope), "message", "FAILED payload");
    ActiveInvocation active = takeTerminalTarget(state, envelope);
    if (active != null) {
      deferred.add(
          () -> active.listener.onError(new EnvironmentCapabilityFailedException(message)));
    }
  }

  private void handleCancelled(
      ConnectionState state, DaemonEnvelope envelope, List<Runnable> deferred) {
    callbackTarget(state, envelope);
    String reason =
        requiredSingleText(envelopeCodec.readPayload(envelope), "reason", "CANCELLED payload");
    ActiveInvocation active = takeTerminalTarget(state, envelope);
    if (active != null) {
      deferred.add(
          () -> active.listener.onError(new EnvironmentCapabilityCancelledException(reason)));
    }
  }

  /** 返回匹配当前连接代际与 invocationId 的活动调用；已知迟到 invocation 返回 null，其余为协议错误。 */
  private ActiveInvocation callbackTarget(ConnectionState state, DaemonEnvelope envelope) {
    requireReady(state);
    UUID invocationId = parseUuid(envelope.invocationId(), "invocationId");
    ActiveInvocation active = registeredInvocation(state.environmentId, invocationId);
    if (active != null && active.connection == state) {
      return active;
    }
    if (hasTombstone(state.environmentId, invocationId)) {
      return null;
    }
    throw new DaemonProtocolException(
        "daemon callback does not own invocationId: " + envelope.invocationId());
  }

  private boolean isActive(ActiveInvocation active) {
    if (active.terminal.get()) {
      return false;
    }
    return registeredInvocation(active.environmentId, active.invocationId) == active;
  }

  /** 原子终结并移除与入站连接和 invocationId 匹配的活动调用；已知迟到终态消费 tombstone 后忽略。 */
  private ActiveInvocation takeTerminalTarget(ConnectionState state, DaemonEnvelope envelope) {
    requireReady(state);
    UUID invocationId = parseUuid(envelope.invocationId(), "invocationId");
    ActiveInvocation active = registeredInvocation(state.environmentId, invocationId);
    if (active != null && active.connection == state) {
      unregister(active);
      active.markTerminal();
      return active;
    }
    if (consumeTombstone(state.environmentId, invocationId)) {
      return null;
    }
    throw new DaemonProtocolException(
        "daemon terminal callback does not own invocationId: " + envelope.invocationId());
  }

  private boolean send(
      ConnectionState state, DaemonMessageType type, String invocationId, String payloadJson) {
    DaemonSendOutcome outcome = state.send(type, invocationId, payloadJson);
    return outcome == DaemonSendOutcome.SENT;
  }

  private void protocolFailure(
      ConnectionState state, RuntimeException error, EnvironmentId receivedEnvironmentId) {
    String message = errorMessage(error, "invalid daemon protocol message");
    ObjectNode payload = envelopeCodec.createPayload();
    payload.put("message", message);
    if (error instanceof DaemonRegistrationRejectedException) {
      payload.put("code", DaemonProtocol.ERROR_CODE_REGISTRATION_REJECTED);
    } else if (error instanceof DaemonRetryLaterException) {
      payload.put("code", DaemonProtocol.ERROR_CODE_RETRY_LATER);
    }
    state.enqueueError(receivedEnvironmentId, envelopeCodec.writeJson(payload));
    close(state.connection.connectionId());
  }

  /** 幂等清理连接代际：解绑路由租约、终结全部未完成调用，并将未终结调用通知为结果不确定。 */
  private void closeConnectionState(ConnectionState state) {
    EnvironmentId environmentId;
    UUID leaseToken;
    boolean closeAfterFlush;
    synchronized (state) {
      if (state.cleaned) {
        return;
      }
      state.cleaned = true;
      state.sendFailed = true;
      environmentId = state.environmentId;
      leaseToken = state.leaseToken;
      closeAfterFlush = state.closeAfterFlush;
    }
    List<ActiveInvocation> lost;
    synchronized (inventory) {
      if (environmentId != null) {
        environmentConnections.remove(environmentId, state);
        lost = removeInvocations(environmentId);
        invocationTombstones.remove(environmentId);
      } else {
        lost = List.of();
      }
      connections.remove(state.connection.connectionId(), state);
    }
    if (environmentId != null && leaseToken != null) {
      try {
        leaseStore.disconnect(environmentId, leaseToken, timeout());
      } catch (RuntimeException ignored) {
      }
    }
    try {
      if (closeAfterFlush) {
        state.connection.closeAfterFlush();
      } else {
        state.connection.close();
      }
    } catch (RuntimeException ignored) {
    }
    List<Runnable> deferred = new ArrayList<>();
    for (ActiveInvocation active : lost) {
      if (active.markTerminalIfOpen()) {
        deferred.add(
            () ->
                active.listener.onError(
                    new EnvironmentCapabilitySendUncertainException(
                        "Daemon connection lost for environment "
                            + active.environmentId
                            + "; capability invocation outcome is uncertain.")));
      }
    }
    runDeferred(deferred);
  }

  private List<ActiveInvocation> removeInvocations(EnvironmentId environmentId) {
    LinkedHashMap<UUID, ActiveInvocation> invocations =
        invocationsByEnvironment.remove(environmentId);
    return invocations == null ? List.of() : List.copyOf(invocations.values());
  }

  private void notifyEnvironmentReady(EnvironmentId environmentId) {
    try {
      sessionListener.onEnvironmentReady(environmentId);
    } catch (RuntimeException ignored) {
    }
  }

  private void runDeferred(List<Runnable> callbacks) {
    for (Runnable callback : callbacks) {
      try {
        callback.run();
      } catch (RuntimeException ignored) {
      }
    }
  }

  private Duration timeout() {
    return settings().heartbeatTimeout();
  }

  private EnvironmentServerSettings settings() {
    return Objects.requireNonNull(settings.get(), "settings");
  }

  private static EnvironmentCapabilityUnavailableException unavailable(
      EnvironmentId environmentId, String capabilityId) {
    return new EnvironmentCapabilityUnavailableException(
        environmentId + " is unavailable; " + capabilityId + " cannot be executed");
  }

  private static void requireHello(ConnectionState state) {
    if (!state.helloReceived) {
      throw new DaemonProtocolException("HELLO must precede this daemon message");
    }
  }

  private static void requireReady(ConnectionState state) {
    requireHello(state);
    if (!state.ready) {
      throw new DaemonProtocolException("READY must precede invocation callbacks");
    }
  }

  private static void requireNoInvocationId(DaemonEnvelope envelope) {
    if (envelope.invocationId() != null) {
      throw new DaemonProtocolException(envelope.messageType() + " must not declare invocationId");
    }
  }

  private static String requiredSingleText(ObjectNode payload, String name, String context) {
    rejectUnexpectedFields(payload, Set.of(name), context);
    return requiredText(payload, name, context);
  }

  private static String requiredText(ObjectNode payload, String name, String context) {
    JsonNode value = payload.get(name);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new DaemonProtocolException(context + "." + name + " must be a non-blank string");
    }
    return value.textValue();
  }

  private static long requiredLong(ObjectNode payload, String name, String context) {
    JsonNode value = payload.get(name);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new DaemonProtocolException(context + "." + name + " must be a long integer");
    }
    return value.longValue();
  }

  private static void rejectUnexpectedFields(
      ObjectNode payload, Set<String> expected, String context) {
    List<String> unexpected = new ArrayList<>();
    payload
        .fieldNames()
        .forEachRemaining(
            field -> {
              if (!expected.contains(field)) {
                unexpected.add(field);
              }
            });
    if (!unexpected.isEmpty() || payload.size() != expected.size()) {
      throw new DaemonProtocolException(context + " has unexpected or missing fields");
    }
  }

  private static String errorMessage(Throwable error, String fallback) {
    String message = error.getMessage();
    return message == null || message.isBlank() ? fallback : message;
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static UUID parseUuid(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new DaemonProtocolException(field + " must not be blank: " + value);
    }
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value)) {
        throw new DaemonProtocolException(field + " must be a canonical UUID string: " + value);
      }
      return parsed;
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          field + " must be a canonical UUID string: " + value, error);
    }
  }

  /**
   * 一次能力调用的所有权记录：终态唯一、CANCEL 至多一次，cancel/expire/连接清理竞争只产生一个赢家。
   *
   * <p>{@link #cancel()} 只表达取消请求，不终结本地所有权；终态仍由 daemon 回调或连接清理给出。
   */
  private final class ActiveInvocation implements EnvironmentCapabilityExecutionHandle {

    private final EnvironmentId environmentId;
    private final ConnectionState connection;
    private final UUID invocationId;
    private final EnvironmentCapabilityExecutionListener listener;
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final AtomicBoolean cancelSent = new AtomicBoolean();
    private volatile boolean cancelled;

    private ActiveInvocation(
        EnvironmentId environmentId,
        ConnectionState connection,
        UUID invocationId,
        EnvironmentCapabilityExecutionListener listener) {
      this.environmentId = environmentId;
      this.connection = connection;
      this.invocationId = invocationId;
      this.listener = listener;
    }

    @Override
    public void cancel() {
      synchronized (this) {
        cancelled = true;
        if (terminal.get() || cancelSent.getAndSet(true)) {
          return;
        }
      }
      send(connection, DaemonMessageType.CANCEL, invocationId.toString(), "{}");
    }

    @Override
    public boolean isCancelled() {
      return cancelled;
    }

    private void markTerminal() {
      terminal.set(true);
    }

    /** 未终结时标记终结并返回 true；已终结返回 false，保证终态只通知一次。 */
    private boolean markTerminalIfOpen() {
      return terminal.compareAndSet(false, true);
    }
  }

  /** 单个连接代际的协议状态与出站 sequence。 */
  private final class ConnectionState {

    /** 只串行化该连接的入站协议处理；不与其它锁嵌套反向获取。 */
    private final Object gate = new Object();

    private final DaemonChannel connection;
    private volatile UUID leaseToken;
    private volatile EnvironmentId environmentId;
    private volatile boolean helloReceived;
    private volatile boolean ready;
    private volatile boolean sendFailed;
    private volatile boolean cleaned;
    private boolean closeAfterFlush;
    private long outboundSequence;
    private InboundEnvelopeIdentity lastInbound;

    private ConnectionState(DaemonChannel connection) {
      this.connection = Objects.requireNonNull(connection, "connection");
    }

    /** 完成 HELLO 绑定；重复绑定属于协议违规。 */
    private synchronized void bind(EnvironmentId boundEnvironmentId, UUID token) {
      if (helloReceived) {
        throw new DaemonProtocolException("HELLO may only be sent once per connection");
      }
      environmentId = boundEnvironmentId;
      leaseToken = token;
      helloReceived = true;
    }

    private boolean isReady() {
      return !cleaned
          && !sendFailed
          && ready
          && connection.isOpen()
          && environmentId != null
          && leaseToken != null;
    }

    private DaemonSendOutcome send(
        DaemonMessageType type, String invocationId, String payloadJson) {
      synchronized (this) {
        return sendOutcome(type, invocationId, payloadJson);
      }
    }

    private synchronized DaemonSendOutcome sendOutcome(
        DaemonMessageType type, String invocationId, String payloadJson) {
      if (cleaned
          || sendFailed
          || !connection.isOpen()
          || environmentId == null
          || leaseToken == null) {
        return DaemonSendOutcome.NOT_SENT;
      }
      String text = encode(type, environmentId, invocationId, payloadJson);
      try {
        if (connection.sendText(text)) {
          return DaemonSendOutcome.SENT;
        }
        // 传输拒绝接受该帧：本次写入不可确认，连接进入不可用状态（fail-closed）。
        sendFailed = true;
        return DaemonSendOutcome.UNCERTAIN;
      } catch (RuntimeException error) {
        sendFailed = true;
        return DaemonSendOutcome.UNCERTAIN;
      }
    }

    /** 协议错误帧：未绑定环境时回退到入站声明的 environmentId，入队成功后要求 flush 后关闭。 */
    private synchronized void enqueueError(
        EnvironmentId receivedEnvironmentId, String payloadJson) {
      if (cleaned || sendFailed || !connection.isOpen()) {
        return;
      }
      EnvironmentId scope = environmentId != null ? environmentId : receivedEnvironmentId;
      String text = encode(DaemonMessageType.ERROR, scope, null, payloadJson);
      try {
        if (connection.sendText(text)) {
          closeAfterFlush = true;
        } else {
          sendFailed = true;
        }
      } catch (RuntimeException ignored) {
        sendFailed = true;
      }
    }

    private String encode(
        DaemonMessageType type, EnvironmentId scope, String invocationId, String payloadJson) {
      return envelopeCodec.encode(
          new DaemonEnvelope(
              DaemonProtocol.VERSION, type, scope, invocationId, outboundSequence++, payloadJson));
    }

    /** 入站 sequence 必须严格递增；完全相同的重放被静默丢弃，其余冲突为协议违规。 */
    private synchronized boolean acceptInbound(DaemonEnvelope envelope, JsonNode payload) {
      InboundEnvelopeIdentity next = InboundEnvelopeIdentity.from(envelope, payload);
      if (lastInbound == null) {
        lastInbound = next;
        return true;
      }
      if (next.sequence == lastInbound.sequence + 1) {
        lastInbound = next;
        return true;
      }
      if (next.sequence == lastInbound.sequence) {
        if (next.equals(lastInbound)) {
          return false;
        }
        throw new DaemonProtocolException(
            "inbound sequence reused by a conflicting envelope: " + next.sequence);
      }
      if (next.sequence < lastInbound.sequence) {
        throw new DaemonProtocolException("inbound sequence moved backwards: " + next.sequence);
      }
      throw new DaemonProtocolException(
          "inbound sequence must immediately follow "
              + lastInbound.sequence
              + ": "
              + next.sequence);
    }
  }

  private record InboundEnvelopeIdentity(
      int protocolVersion,
      DaemonMessageType messageType,
      EnvironmentId environmentId,
      String invocationId,
      long sequence,
      JsonNode payload) {

    private static InboundEnvelopeIdentity from(DaemonEnvelope envelope, JsonNode payload) {
      return new InboundEnvelopeIdentity(
          envelope.protocolVersion(),
          envelope.messageType(),
          envelope.environmentId(),
          envelope.invocationId(),
          envelope.sequence(),
          payload);
    }
  }
}
