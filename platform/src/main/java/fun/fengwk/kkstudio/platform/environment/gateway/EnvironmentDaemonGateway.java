package fun.fengwk.kkstudio.platform.environment.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityCancelledException;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityFailedException;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityTransport;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonCapabilitiesCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonCapabilityInvokeCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonCapabilityResultCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonDirectoryCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonDirectoryFailureCode;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonNameConflictException;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonProtocolException;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillLoadCodec;
import fun.fengwk.kkstudio.platform.environment.registry.BindResult;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironment;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentDirectoryFailureCode;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentDirectoryListResult;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentDirectoryLister;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentSkillLoadResult;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentSkillLoader;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentDirectoryDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentDirectoryEntryDTO;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Environment daemon 的连接/协议 transport 与能力/技能适配器。
 *
 * <p>使用当前 Daemon wire 协议（VERSION=4）：每个 envelope 都由 HELLO 时绑定的 canonical {@link EnvironmentName}
 * 限定；名称就是唯一 路由身份，不存在独立展示名。HELLO 声称的名称被另一条 live 连接（打开 + 心跳未过期）持有时抛出 {@link
 * DaemonNameConflictException}（终态错误，daemon 收到后停止重连并非零退出）；持有者连接已关闭或心跳租约过期时，registry 原子接管（{@link
 * BindResult.Replaced}），本类把被替换的旧连接状态恰好清理一次（连接关闭、active capability invocation 按不确定收敛、 pending skill
 * load 失败），绝不触碰新持有者。不拥有持久的 ToolInvocation claim/lease/terminal/retry 生命周期。持久化执行 由 Runtime {@code
 * ToolProcessor} 负责；本类提供 platform 侧 {@link EnvironmentCapabilityTransport}，并把 daemon 回调转发给注册的
 * capability listener，严格校验 environment/connection/invocation 归属。每个 Environment 的单一 active
 * capability invocation 槽位只负责 wire ownership；同 Environment 的并发 sibling 在发送前返回 {@link
 * EnvironmentCapabilityBusyException}，由 Harness retry 序列化，不在本类排队。
 */
@Service
public class EnvironmentDaemonGateway
    implements EnvironmentDaemonEndpoint,
        EnvironmentSkillLoader,
        EnvironmentCapabilityTransport,
        EnvironmentDirectoryLister {

  private final LiveEnvironmentRegistry environmentRegistry;
  private final DaemonCapabilitiesCodec capabilitiesCodec = new DaemonCapabilitiesCodec();
  private final DaemonCapabilityInvokeCodec capabilityInvokeCodec =
      new DaemonCapabilityInvokeCodec();
  private final DaemonCapabilityResultCodec resultCodec = new DaemonCapabilityResultCodec();
  private final DaemonEnvelopeCodec envelopeCodec = new DaemonEnvelopeCodec();
  private final DaemonSkillLoadCodec skillLoadCodec = new DaemonSkillLoadCodec();
  private final DaemonDirectoryCodec directoryCodec = new DaemonDirectoryCodec();
  private final EnvironmentGatewayProperties properties;
  private final SystemSettingsSnapshot snapshot;
  private final Clock clock;
  private final EnvironmentReadyListener environmentReadyListener;
  private final Map<String, ConnectionState> connections = new HashMap<>();
  private final Map<EnvironmentName, ConnectionState> environmentConnections = new HashMap<>();
  private final Map<EnvironmentName, ActiveCapability> activeByEnvironment = new HashMap<>();
  private final Map<String, PendingSkillLoad> pendingSkillLoads = new HashMap<>();
  private final Map<String, PendingDirectoryList> pendingDirectoryLists = new HashMap<>();

  /** 每个连接的目录请求超时 tombstone 上限；超过时 FIFO 淘汰最旧条目。 */
  static final int MAX_DIRECTORY_TOMBSTONES = 1024;

  public EnvironmentDaemonGateway(
      LiveEnvironmentRegistry environmentRegistry,
      EnvironmentGatewayProperties properties,
      SystemSettingsSnapshot snapshot,
      Clock clock,
      EnvironmentReadyListener environmentReadyListener) {
    this.environmentRegistry = Objects.requireNonNull(environmentRegistry, "environmentRegistry");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.environmentReadyListener =
        Objects.requireNonNull(environmentReadyListener, "environmentReadyListener");
  }

  /** 心跳过期超时（毫秒）：环境按不可用处理的租约时长。 */
  Duration heartbeatTimeout() {
    return Duration.ofMillis(environment().heartbeatTimeoutMillis());
  }

  private SystemSettings.Environment environment() {
    return snapshot.get().environment();
  }

  @Override
  public void open(EnvironmentDaemonConnection connection) {
    Objects.requireNonNull(connection, "connection");
    String connectionId = requireNonBlank(connection.connectionId(), "connectionId");
    ConnectionState previous;
    ConnectionState state = new ConnectionState(connection);
    synchronized (this) {
      previous = connections.put(connectionId, state);
    }
    if (previous != null) {
      closeConnectionState(previous);
    }
  }

  @Override
  public void receive(String connectionId, String rawMessage) {
    ConnectionState state;
    synchronized (this) {
      state = connections.get(connectionId);
    }
    if (state == null) {
      return;
    }
    List<Runnable> deferred = new ArrayList<>();
    RuntimeException protocolError = null;
    EnvironmentName receivedEnvironmentName = null;
    synchronized (state) {
      // close 先在 state 锁上立 cleaned 围栏；已清理连接上的迟到 HELLO 不得再绑定。
      if (state.cleaned) {
        return;
      }
      try {
        DaemonEnvelope envelope = envelopeCodec.decode(rawMessage);
        receivedEnvironmentName = envelope.environmentName();
        if (!state.acceptInbound(envelope, envelopeCodec.readPayload(envelope))) {
          return;
        }
        handleInbound(state, envelope, deferred);
      } catch (RuntimeException error) {
        protocolError = error;
      }
    }
    if (protocolError != null) {
      protocolFailure(state, protocolError, receivedEnvironmentName);
      return;
    }
    runDeferred(deferred);
  }

  @Override
  public void close(String connectionId) {
    ConnectionState state;
    synchronized (this) {
      state = connections.remove(connectionId);
    }
    if (state != null) {
      closeConnectionState(state);
    }
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
    EnvironmentName environmentName = binding.environmentName();
    ConnectionState state;
    ActiveCapability active;
    String invokePayload;
    synchronized (this) {
      state = environmentConnections.get(environmentName);
      if (state == null
          || !state.isReady()
          || !environmentRegistry.isReady(environmentName, clock.instant(), heartbeatTimeout())) {
        throw new EnvironmentCapabilityUnavailableException(
            unavailableMessage(environmentName, descriptor.id().value()));
      }
      if (activeByEnvironment.containsKey(environmentName)) {
        throw new EnvironmentCapabilityBusyException(
            environmentName + " already has an active capability invocation");
      }
      // 完整编码在注册 active 之前完成；确定性 payload 失败不得留下永远占用 Environment 的幽灵 invocation。
      invokePayload = createInvokePayload(binding, request);
      active =
          new ActiveCapability(
              environmentName, state.connection.connectionId(), invocationId, listener);
      activeByEnvironment.put(environmentName, active);
    }
    // 在 gateway monitor 之外发送，避免与 receive 路径产生 this->state 锁顺序反转。
    SendOutcome outcome =
        sendWithOutcome(state, DaemonMessageType.INVOKE, invocationId.toString(), invokePayload);
    if (outcome == SendOutcome.SENT) {
      return active;
    }
    // 防止连接关闭通知与抛出的不确定（uncertain）路径双重触发。
    active.terminal = true;
    synchronized (this) {
      activeByEnvironment.remove(environmentName, active);
    }
    if (outcome == SendOutcome.UNCERTAIN) {
      close(state.connection.connectionId());
      throw new EnvironmentCapabilitySendUncertainException(
          "INVOKE send outcome is uncertain for environment " + environmentName);
    }
    throw new EnvironmentCapabilityUnavailableException(
        unavailableMessage(environmentName, descriptor.id().value()));
  }

  @Override
  public CompletableFuture<EnvironmentSkillLoadResult> loadSkill(
      EnvironmentName environmentName, String skillName, Duration timeout) {
    Objects.requireNonNull(environmentName, "environmentName");
    String skill = requireNonBlank(skillName, "skillName");
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    ConnectionState state;
    synchronized (this) {
      state = environmentConnections.get(environmentName);
      // 与 Tool invoke/查询完全相同的可用性规则：READY + 连接打开 + 心跳未过期。
      if (state == null
          || !state.isReady()
          || !environmentRegistry.isReady(environmentName, clock.instant(), heartbeatTimeout())) {
        return CompletableFuture.completedFuture(
            new EnvironmentSkillLoadResult.Failed(
                skill, environmentName + " is offline; " + skill + " is unavailable"));
      }
    }
    String requestId = UUID.randomUUID().toString();
    CompletableFuture<EnvironmentSkillLoadResult> future = new CompletableFuture<>();
    PendingSkillLoad pending =
        new PendingSkillLoad(environmentName, skill, state.connection.connectionId(), future);
    synchronized (this) {
      pendingSkillLoads.put(requestId, pending);
    }
    String payload = skillLoadCodec.encodeRequest(new DaemonSkillLoadCodec.LoadSkillRequest(skill));
    if (!send(state, DaemonMessageType.LOAD_SKILL, requestId, payload)) {
      synchronized (this) {
        pendingSkillLoads.remove(requestId, pending);
      }
      future.complete(
          new EnvironmentSkillLoadResult.Failed(
              skill, environmentName + " is offline; " + skill + " is unavailable"));
      return future;
    }
    return future
        .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
        .exceptionally(
            error -> {
              synchronized (this) {
                pendingSkillLoads.remove(requestId, pending);
              }
              return new EnvironmentSkillLoadResult.Failed(
                  skill, environmentName + " is offline; " + skill + " is unavailable");
            });
  }

  @Override
  public CompletableFuture<EnvironmentDirectoryListResult> listDirectory(
      EnvironmentName environmentName, String path, Duration timeout) {
    Objects.requireNonNull(environmentName, "environmentName");
    String directoryPath;
    try {
      directoryPath = DaemonDirectoryCodec.requireCanonicalRelativePath(path);
    } catch (IllegalArgumentException error) {
      // wire 契约在发端即校验：非法路径立即失败，绝不发送 LIST_DIRECTORY。
      return CompletableFuture.completedFuture(
          new EnvironmentDirectoryListResult.Failed(
              EnvironmentDirectoryFailureCode.INVALID_PATH, error.getMessage()));
    }
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    ConnectionState state;
    synchronized (this) {
      // registry 是可用性事实源：find 区分「未知」（404）与「已注册但未 READY/不可用」（409）。
      LiveEnvironment live = environmentRegistry.find(environmentName).orElse(null);
      if (live == null) {
        return CompletableFuture.completedFuture(
            new EnvironmentDirectoryListResult.Failed(
                EnvironmentDirectoryFailureCode.ENVIRONMENT_NOT_FOUND,
                "environment is not registered: " + environmentName));
      }
      if (!live.isReady(clock.instant(), heartbeatTimeout())) {
        return CompletableFuture.completedFuture(
            new EnvironmentDirectoryListResult.Failed(
                EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE,
                environmentName + " is not ready; directory listing is unavailable"));
      }
      state = environmentConnections.get(environmentName);
      if (state == null || !state.isReady()) {
        return CompletableFuture.completedFuture(
            new EnvironmentDirectoryListResult.Failed(
                EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE,
                environmentName + " is not ready; directory listing is unavailable"));
      }
    }
    String requestId = UUID.randomUUID().toString();
    CompletableFuture<EnvironmentDirectoryListResult> future = new CompletableFuture<>();
    PendingDirectoryList pending =
        new PendingDirectoryList(
            environmentName, directoryPath, state.connection.connectionId(), future);
    synchronized (this) {
      pendingDirectoryLists.put(requestId, pending);
    }
    String payload =
        directoryCodec.encodeRequest(
            new DaemonDirectoryCodec.ListDirectoryRequest(requestId, directoryPath));
    // 目录控制面不属于 invocation：envelope invocationId 恒为 null，关联 ID 只存在于 payload requestId。
    SendOutcome outcome = sendWithOutcome(state, DaemonMessageType.LIST_DIRECTORY, null, payload);
    if (outcome == SendOutcome.SENT) {
      return future
          .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
          .exceptionally(
              error -> {
                synchronized (this) {
                  if (pendingDirectoryLists.remove(requestId, pending)) {
                    // pending -> tombstone：同连接迟到响应按 requestId/path 校验后静默丢弃，不当作 unsolicited。
                    state.directoryTombstones.addLast(
                        new DirectoryTombstone(
                            requestId,
                            environmentName,
                            state.connection.connectionId(),
                            directoryPath));
                    evictDirectoryTombstones(state);
                  }
                }
                EnvironmentDirectoryFailureCode code =
                    error instanceof TimeoutException
                        ? EnvironmentDirectoryFailureCode.TIMEOUT
                        : EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE;
                String message =
                    code == EnvironmentDirectoryFailureCode.TIMEOUT
                        ? environmentName
                            + " directory listing timed out after "
                            + timeout.toMillis()
                            + "ms"
                        : environmentName + " is not ready; directory listing is unavailable";
                return new EnvironmentDirectoryListResult.Failed(code, message);
              });
    }
    synchronized (this) {
      pendingDirectoryLists.remove(requestId, pending);
    }
    if (outcome == SendOutcome.UNCERTAIN) {
      // transport 无法继续发送但清理尚未完成：与 invoke 相同，关闭连接让 closeConnectionState 恰好清理一次。
      close(state.connection.connectionId());
    }
    future.complete(
        new EnvironmentDirectoryListResult.Failed(
            EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE,
            environmentName + " is not ready; directory listing is unavailable"));
    return future;
  }

  private String createInvokePayload(
      EnvironmentBinding binding, EnvironmentCapabilityExecutionRequest request) {
    return capabilityInvokeCodec.encode(
        new DaemonCapabilityInvokeCodec.InvokeRequest(
            request.descriptor().id(),
            request.descriptor().version(),
            binding.workspacePath(),
            request.call().argumentsJson(),
            request.timeout()));
  }

  private void handleInbound(
      ConnectionState state, DaemonEnvelope envelope, List<Runnable> deferred) {
    if (envelope.protocolVersion() != DaemonProtocol.VERSION) {
      throw new DaemonProtocolException(
          "daemon envelope protocolVersion must be " + DaemonProtocol.VERSION);
    }
    if (!state.helloReceived && envelope.messageType() != DaemonMessageType.HELLO) {
      throw new DaemonProtocolException("HELLO must be the first daemon message");
    }
    // HELLO 之后的 envelope 必须携带绑定的 canonical 名称；不匹配属于协议失败，绝不重新路由绑定。
    if (state.environmentName != null
        && !state.environmentName.equals(envelope.environmentName())) {
      throw new DaemonProtocolException(
          "envelope environmentName does not match bound connection: "
              + envelope.environmentName());
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
      case SKILL_LOADED -> handleSkillLoaded(state, envelope, deferred);
      case SKILL_LOAD_FAILED -> handleSkillLoadFailed(state, envelope, deferred);
      case DIRECTORY_LISTED -> handleDirectoryListed(state, envelope, deferred);
      case DIRECTORY_LIST_FAILED -> handleDirectoryListFailed(state, envelope, deferred);
      case WELCOME, INVOKE, CANCEL, LOAD_SKILL, LIST_DIRECTORY -> throw new DaemonProtocolException(
          "daemon must not send " + envelope.messageType() + " to gateway");
    }
  }

  private void handleHello(ConnectionState state, DaemonEnvelope envelope) {
    if (state.helloReceived) {
      throw new DaemonProtocolException("HELLO may only be sent once per connection");
    }
    requireNoInvocationId(envelope);
    ObjectNode payload = envelopeCodec.readPayload(envelope);
    rejectUnexpectedFields(
        payload,
        Set.of("daemonId", "protocolVersion", "gatewayToken", "capabilityCatalogVersion"),
        "HELLO payload");
    requiredText(payload, "daemonId", "HELLO payload");
    if (envelope.protocolVersion() != DaemonProtocol.VERSION
        || requiredLong(payload, "protocolVersion", "HELLO payload") != DaemonProtocol.VERSION) {
      throw new DaemonProtocolException("HELLO protocolVersion must be " + DaemonProtocol.VERSION);
    }
    if (!EnvironmentCapabilityCatalog.version()
        .equals(requiredText(payload, "capabilityCatalogVersion", "HELLO payload"))) {
      throw new DaemonProtocolException(
          "HELLO capabilityCatalogVersion does not match server catalog");
    }
    verifyGatewayToken(requiredText(payload, "gatewayToken", "HELLO payload"));
    // HELLO 在 token/version/catalog 校验后绑定 canonical envelope 名称。
    EnvironmentName environmentName = envelope.environmentName();
    Instant now = clock.instant();
    BindResult bind =
        environmentRegistry.tryBind(environmentName, state.connection, now, heartbeatTimeout());
    if (bind instanceof BindResult.Rejected) {
      throw new DaemonNameConflictException(
          "environmentName is already held by another live daemon: " + environmentName);
    }
    if (bind instanceof BindResult.Replaced replaced) {
      // 旧持有者已死（连接关闭或心跳租约过期）：registry 已原子切换到本连接；只清理旧连接的
      // gateway 侧状态（恰好一次），registry 条目/新 holder 不受影响，也不泄漏 active capability invocation
      // 或 pending skill work。
      displace(replaced.displacedConnection());
    }
    state.environmentName = environmentName;
    state.helloReceived = true;
    synchronized (this) {
      environmentConnections.put(environmentName, state);
    }
    send(state, DaemonMessageType.WELCOME, null, "{}");
  }

  /**
   * 清理被替换的旧连接状态，恰好一次。registry 条目已是新 holder，因此 {@code closeConnectionState} 内的 {@code unregister} /
   * {@code environmentConnections.remove} 都是 owner 不匹配的 no-op；active capability invocation 与
   * pending skill load 只会按旧连接的 connectionId 清理，绝不会碰到新持有者。
   */
  private void displace(EnvironmentDaemonConnection displacedConnection) {
    ConnectionState displacedState;
    synchronized (this) {
      displacedState = connections.get(displacedConnection.connectionId());
    }
    if (displacedState != null) {
      closeConnectionState(displacedState);
    }
  }

  private void handleReady(
      ConnectionState state, DaemonEnvelope envelope, List<Runnable> deferred) {
    requireHello(state);
    requireNoInvocationId(envelope);
    if (state.ready) {
      throw new DaemonProtocolException("READY may only be sent once per connection");
    }
    DaemonCapabilities capabilities = capabilitiesCodec.decode(envelope.payloadJson());
    environmentRegistry.updateCapabilities(
        state.environmentName, state.connection, capabilities, clock.instant());
    Instant now = clock.instant();
    environmentRegistry.markReady(state.environmentName, state.connection, now);
    state.ready = true;
    EnvironmentName environmentName = state.environmentName;
    deferred.add(() -> notifyEnvironmentReady(environmentName));
  }

  private void handleHeartbeat(ConnectionState state, DaemonEnvelope envelope) {
    requireReady(state);
    requireNoInvocationId(envelope);
    rejectUnexpectedFields(envelopeCodec.readPayload(envelope), Set.of(), "HEARTBEAT payload");
    environmentRegistry.heartbeat(state.environmentName, state.connection, clock.instant());
  }

  private void handleAck(ConnectionState state, DaemonEnvelope envelope) {
    requireReady(state);
    requireNoInvocationId(envelope);
    ObjectNode payload = envelopeCodec.readPayload(envelope);
    rejectUnexpectedFields(payload, Set.of("acknowledgedSequence"), "ACK payload");
    long acknowledgedSequence = requiredLong(payload, "acknowledgedSequence", "ACK payload");
    if (acknowledgedSequence < 0 || acknowledgedSequence >= state.outboundSequence) {
      throw new DaemonProtocolException("ACK payload.acknowledgedSequence is not a sent frame");
    }
  }

  private void handleError(ConnectionState state, DaemonEnvelope envelope) {
    requireHello(state);
    requireNoInvocationId(envelope);
    requiredSingleText(envelopeCodec.readPayload(envelope), "message", "ERROR payload");
  }

  private void handleStarted(ConnectionState state, DaemonEnvelope envelope) {
    requireActive(state, envelope);
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
    ActiveCapability active = requireActive(state, envelope);
    // PARTIAL 拒绝 resource content：partial 结果必须只能是 text/json。
    EnvironmentCapabilityResult result =
        resultCodec.decodePartialForInvocation(
            envelope.payloadJson(), wireInvocationId(active), environment().maxResourceBytes());
    deferred.add(() -> active.listener.onPartial(result));
  }

  private void handleCompleted(
      ConnectionState state, DaemonEnvelope envelope, List<Runnable> deferred) {
    ActiveCapability active = takeActive(state, envelope);
    // COMPLETED 把 resource 段解码为瞬态内存 BinaryToolContent；持久化外部化属于 ToolGateway，
    // 绝不属于 transport gateway。
    EnvironmentCapabilityResult result =
        resultCodec.decodeCompletedForInvocation(
            envelope.payloadJson(), wireInvocationId(active), environment().maxResourceBytes());
    deferred.add(() -> active.listener.onComplete(result));
  }

  private void handleFailed(
      ConnectionState state, DaemonEnvelope envelope, List<Runnable> deferred) {
    ActiveCapability active = takeActive(state, envelope);
    String message =
        requiredSingleText(envelopeCodec.readPayload(envelope), "message", "FAILED payload");
    // daemon FAILED 是已确认的已知失败：typed 异常让 ToolGateway 映射为非可重试 durable FAILED（其余未分类错误是 UNKNOWN）。
    deferred.add(() -> active.listener.onError(new EnvironmentCapabilityFailedException(message)));
  }

  private void handleCancelled(
      ConnectionState state, DaemonEnvelope envelope, List<Runnable> deferred) {
    ActiveCapability active = takeActive(state, envelope);
    String reason =
        requiredSingleText(envelopeCodec.readPayload(envelope), "reason", "CANCELLED payload");
    deferred.add(
        () -> active.listener.onError(new EnvironmentCapabilityCancelledException(reason)));
  }

  private void handleSkillLoaded(
      ConnectionState state, DaemonEnvelope envelope, List<Runnable> deferred) {
    requireReady(state);
    PendingSkillLoad pending = takePendingSkillLoad(state, envelope);
    DaemonSkillLoadCodec.SkillLoaded loaded = skillLoadCodec.decodeLoaded(envelope.payloadJson());
    if (!pending.skillName.equals(loaded.name())) {
      throw new DaemonProtocolException(
          "SKILL_LOADED name does not match request: " + loaded.name());
    }
    deferred.add(
        () ->
            pending.future.complete(
                new EnvironmentSkillLoadResult.Loaded(loaded.name(), loaded.content())));
  }

  private void handleSkillLoadFailed(
      ConnectionState state, DaemonEnvelope envelope, List<Runnable> deferred) {
    requireReady(state);
    PendingSkillLoad pending = takePendingSkillLoad(state, envelope);
    DaemonSkillLoadCodec.SkillLoadFailed failed =
        skillLoadCodec.decodeFailed(envelope.payloadJson());
    if (!pending.skillName.equals(failed.name())) {
      throw new DaemonProtocolException(
          "SKILL_LOAD_FAILED name does not match request: " + failed.name());
    }
    deferred.add(
        () ->
            pending.future.complete(
                new EnvironmentSkillLoadResult.Failed(failed.name(), failed.message())));
  }

  private PendingSkillLoad takePendingSkillLoad(ConnectionState state, DaemonEnvelope envelope) {
    String requestId = requireNonBlank(envelope.invocationId(), "invocationId");
    PendingSkillLoad pending;
    synchronized (this) {
      pending = pendingSkillLoads.remove(requestId);
    }
    if (pending == null
        || !pending.environmentName.equals(state.environmentName)
        || !pending.connectionId.equals(state.connection.connectionId())) {
      throw new DaemonProtocolException(
          "skill load callback does not own invocationId: " + requestId);
    }
    return pending;
  }

  private void handleDirectoryListed(
      ConnectionState state, DaemonEnvelope envelope, List<Runnable> deferred) {
    requireReady(state);
    requireNoInvocationId(envelope);
    // 先严格 decode（失败则 pending 留在 map，closeConnectionState 立即以 ENVIRONMENT_UNAVAILABLE 完成），
    // 再 peek+ownership，最后 identity remove 并完成。
    DaemonDirectoryCodec.DirectoryListed listed =
        directoryCodec.decodeListed(envelope.payloadJson());
    PendingDirectoryList pending = peekPendingDirectoryList(state, listed.requestId());
    if (pending == null) {
      if (matchesDirectoryTombstone(state, listed.requestId(), listed.path())) {
        // 超时后的合法迟到响应：严格 decode/校验通过后静默丢弃，不是 unsolicited。
        return;
      }
      throw new DaemonProtocolException(
          "directory listing callback does not own requestId: " + listed.requestId());
    }
    if (!pending.path.equals(listed.path())) {
      throw new DaemonProtocolException(
          "DIRECTORY_LISTED path does not match request: " + listed.path());
    }
    if (!removePendingDirectoryList(state, listed.requestId(), pending)) {
      // 超时恰好抢先：pending 已被 orTimeout 移除并完成，tombstone 在同一临界区内落盘，按迟到响应静默丢弃。
      return;
    }
    deferred.add(
        () -> pending.future.complete(new EnvironmentDirectoryListResult.Loaded(toDto(listed))));
  }

  private void handleDirectoryListFailed(
      ConnectionState state, DaemonEnvelope envelope, List<Runnable> deferred) {
    requireReady(state);
    requireNoInvocationId(envelope);
    DaemonDirectoryCodec.DirectoryListFailed failed =
        directoryCodec.decodeFailed(envelope.payloadJson());
    PendingDirectoryList pending = peekPendingDirectoryList(state, failed.requestId());
    if (pending == null) {
      if (matchesDirectoryTombstone(state, failed.requestId(), failed.path())) {
        return;
      }
      throw new DaemonProtocolException(
          "directory listing callback does not own requestId: " + failed.requestId());
    }
    if (!pending.path.equals(failed.path())) {
      throw new DaemonProtocolException(
          "DIRECTORY_LIST_FAILED path does not match request: " + failed.path());
    }
    if (!removePendingDirectoryList(state, failed.requestId(), pending)) {
      return;
    }
    EnvironmentDirectoryFailureCode code = toApplicationCode(failed.code());
    deferred.add(
        () ->
            pending.future.complete(
                new EnvironmentDirectoryListResult.Failed(code, failed.message())));
  }

  /** 只读查找请求的 pending 并校验 ownership（不移动条目：decode/校验失败时 pending 必须留在 map 供断线清理完成）。 */
  private PendingDirectoryList peekPendingDirectoryList(ConnectionState state, String requestId) {
    synchronized (this) {
      PendingDirectoryList pending = pendingDirectoryLists.get(requestId);
      if (pending == null) {
        return null;
      }
      if (!pending.environmentName.equals(state.environmentName)
          || !pending.connectionId.equals(state.connection.connectionId())) {
        throw new DaemonProtocolException(
            "directory listing callback does not own requestId: " + requestId);
      }
      return pending;
    }
  }

  /** identity remove：pending 已被并发移除（超时/断线）时返回 false，调用方按迟到响应静默丢弃。 */
  private boolean removePendingDirectoryList(
      ConnectionState state, String requestId, PendingDirectoryList pending) {
    synchronized (this) {
      return pendingDirectoryLists.remove(requestId, pending);
    }
  }

  /** 超时 tombstone 是否覆盖该 requestId/path（同一连接、同一环境、同一请求路径）。 */
  private boolean matchesDirectoryTombstone(ConnectionState state, String requestId, String path) {
    synchronized (this) {
      for (DirectoryTombstone tombstone : state.directoryTombstones) {
        if (tombstone.requestId().equals(requestId)
            && tombstone.environmentName().equals(state.environmentName)
            && tombstone.connectionId().equals(state.connection.connectionId())
            && tombstone.path().equals(path)) {
          return true;
        }
      }
      return false;
    }
  }

  /** FIFO 有界淘汰：超过 {@link #MAX_DIRECTORY_TOMBSTONES} 时移除最旧条目。调用方必须持有 gateway monitor。 */
  private static void evictDirectoryTombstones(ConnectionState state) {
    while (state.directoryTombstones.size() > MAX_DIRECTORY_TOMBSTONES) {
      state.directoryTombstones.removeFirst();
    }
  }

  /** wire 失败分类原样投影为应用结果分类（wire 只有四种确定性 code）。 */
  private static EnvironmentDirectoryFailureCode toApplicationCode(
      DaemonDirectoryFailureCode wireCode) {
    return switch (wireCode) {
      case INVALID_PATH -> EnvironmentDirectoryFailureCode.INVALID_PATH;
      case NOT_FOUND -> EnvironmentDirectoryFailureCode.NOT_FOUND;
      case NOT_DIRECTORY -> EnvironmentDirectoryFailureCode.NOT_DIRECTORY;
      case IO_ERROR -> EnvironmentDirectoryFailureCode.IO_ERROR;
    };
  }

  /** wire {@code DIRECTORY_LISTED} → 应用读模型；条目顺序/截断标记原样保留。 */
  private static EnvironmentDirectoryDTO toDto(DaemonDirectoryCodec.DirectoryListed listed) {
    EnvironmentDirectoryDTO dto = new EnvironmentDirectoryDTO();
    dto.setPath(listed.path());
    dto.setDisplayPath(listed.displayPath());
    dto.setParentPath(listed.parentPath());
    dto.setTruncated(listed.truncated());
    dto.setGitBranch(listed.gitBranch());
    List<EnvironmentDirectoryEntryDTO> entries = new ArrayList<>();
    for (DaemonDirectoryCodec.DirectoryEntry entry : listed.entries()) {
      EnvironmentDirectoryEntryDTO entryDto = new EnvironmentDirectoryEntryDTO();
      entryDto.setName(entry.name());
      entryDto.setPath(entry.path());
      entries.add(entryDto);
    }
    dto.setEntries(List.copyOf(entries));
    return dto;
  }

  private ActiveCapability requireActive(ConnectionState state, DaemonEnvelope envelope) {
    requireReady(state);
    UUID invocationId = parseUuid(envelope.invocationId(), "invocationId");
    ActiveCapability active;
    synchronized (this) {
      active = activeByEnvironment.get(state.environmentName);
    }
    if (active == null
        || !active.invocationId.equals(invocationId)
        || !active.connectionId.equals(state.connection.connectionId())) {
      throw new DaemonProtocolException(
          "daemon callback does not own invocationId: " + envelope.invocationId());
    }
    return active;
  }

  private ActiveCapability takeActive(ConnectionState state, DaemonEnvelope envelope) {
    ActiveCapability active = requireActive(state, envelope);
    synchronized (this) {
      activeByEnvironment.remove(state.environmentName, active);
    }
    active.terminal = true;
    return active;
  }

  private boolean send(
      ConnectionState state, DaemonMessageType type, String invocationId, String payloadJson) {
    SendOutcome outcome = sendWithOutcome(state, type, invocationId, payloadJson);
    if (outcome == SendOutcome.UNCERTAIN) {
      close(state.connection.connectionId());
    }
    return outcome == SendOutcome.SENT;
  }

  private SendOutcome sendWithOutcome(
      ConnectionState state, DaemonMessageType type, String invocationId, String payloadJson) {
    synchronized (state) {
      if (state.cleaned
          || state.sendFailed
          || !state.connection.isOpen()
          || state.environmentName == null) {
        return SendOutcome.NOT_SENT;
      }
      DaemonEnvelope envelope =
          new DaemonEnvelope(
              DaemonProtocol.VERSION,
              type,
              state.environmentName,
              invocationId,
              state.outboundSequence++,
              payloadJson);
      try {
        if (state.connection.sendText(envelopeCodec.encode(envelope))) {
          return SendOutcome.SENT;
        }
        // 有界队列拒绝意味着该连接不能可靠传递当前帧；连接必须整体失败，不能跳过序号继续发送后续帧。
        state.sendFailed = true;
        return SendOutcome.UNCERTAIN;
      } catch (RuntimeException error) {
        // transport enqueue 契约异常同样使连接整体不可用。
        state.sendFailed = true;
        return SendOutcome.UNCERTAIN;
      }
    }
  }

  private void protocolFailure(
      ConnectionState state, RuntimeException error, EnvironmentName receivedEnvironmentName) {
    String message = errorMessage(error, "invalid daemon protocol message");
    ObjectNode payload = envelopeCodec.createPayload();
    payload.put("message", message);
    if (error instanceof DaemonNameConflictException) {
      // 终态名称冲突：daemon 必须据此停止重连并以非零状态退出。
      payload.put("code", DaemonProtocol.ERROR_CODE_ENVIRONMENT_NAME_CONFLICT);
    }
    enqueueProtocolError(state, receivedEnvironmentName, envelopeCodec.writeJson(payload));
    close(state.connection.connectionId());
  }

  private void enqueueProtocolError(
      ConnectionState state, EnvironmentName receivedEnvironmentName, String payloadJson) {
    synchronized (state) {
      EnvironmentName environmentName =
          state.environmentName == null ? receivedEnvironmentName : state.environmentName;
      if (state.cleaned
          || state.sendFailed
          || !state.connection.isOpen()
          || environmentName == null) {
        return;
      }
      DaemonEnvelope envelope =
          new DaemonEnvelope(
              DaemonProtocol.VERSION,
              DaemonMessageType.ERROR,
              environmentName,
              null,
              state.outboundSequence++,
              payloadJson);
      try {
        if (state.connection.sendText(envelopeCodec.encode(envelope))) {
          // ERROR 已被 transport 接受后必须先 drain 再 close，尤其名称冲突 code 要送达 daemon。
          state.closeAfterFlush = true;
        } else {
          state.sendFailed = true;
        }
      } catch (RuntimeException ignored) {
        state.sendFailed = true;
      }
    }
  }

  private void closeConnectionState(ConnectionState state) {
    ActiveCapability lostCapability = null;
    List<PendingSkillLoad> doomedSkills = List.of();
    List<PendingDirectoryList> doomedDirectoryLists = List.of();
    EnvironmentName environmentName;
    boolean closeAfterFlush;
    // 锁序固定为 state → this：先在 state 上立 cleaned/sendFailed，再清理 map/registry。
    // 这样 in-flight receive（含 HELLO tryBind）会先完成绑定，close 才能看见并摘掉条目；
    // 已 cleaned 的迟到 receive 直接忽略，不会留下幽灵 registry 占用。
    synchronized (state) {
      if (state.cleaned) {
        return;
      }
      // cleaned 是完成清理的围栏；sendFailed 只表示 transport 不可用。
      state.cleaned = true;
      state.sendFailed = true;
      environmentName = state.environmentName;
      closeAfterFlush = state.closeAfterFlush;
    }
    synchronized (this) {
      if (environmentName != null) {
        environmentRegistry.unregister(environmentName, state.connection);
        environmentConnections.remove(environmentName, state);
        ActiveCapability active = activeByEnvironment.get(environmentName);
        if (active != null && active.connectionId.equals(state.connection.connectionId())) {
          activeByEnvironment.remove(environmentName, active);
          boolean shouldNotify = !active.terminal;
          active.terminal = true;
          if (shouldNotify) {
            lostCapability = active;
          }
        }
        doomedSkills = takePendingSkillLoads(environmentName);
        doomedDirectoryLists = takePendingDirectoryLists(environmentName);
      }
      // 断线清理同时清掉该连接的目录请求 tombstone：晚响应不可能再出现在已清理的连接上。
      state.directoryTombstones.clear();
      connections.remove(state.connection.connectionId(), state);
    }
    try {
      if (closeAfterFlush) {
        state.connection.closeAfterFlush();
      } else {
        state.connection.close();
      }
    } catch (RuntimeException ignored) {
      // transport 关闭失败不得触碰持久状态。
    }
    if (lostCapability != null) {
      ActiveCapability capability = lostCapability;
      String env = String.valueOf(environmentName);
      runDeferred(
          List.of(
              () ->
                  capability.listener.onError(
                      new EnvironmentCapabilitySendUncertainException(
                          "Daemon connection lost for environment "
                              + env
                              + "; capability invocation outcome is uncertain."))));
    }
    completeDoomedSkillLoads(environmentName, doomedSkills);
    completeDoomedDirectoryLists(environmentName, doomedDirectoryLists);
  }

  private List<PendingSkillLoad> takePendingSkillLoads(EnvironmentName environmentName) {
    List<PendingSkillLoad> doomed = new ArrayList<>();
    List<String> keys = new ArrayList<>();
    for (Map.Entry<String, PendingSkillLoad> entry : pendingSkillLoads.entrySet()) {
      if (environmentName.equals(entry.getValue().environmentName)) {
        keys.add(entry.getKey());
        doomed.add(entry.getValue());
      }
    }
    for (String key : keys) {
      pendingSkillLoads.remove(key);
    }
    return doomed;
  }

  private void completeDoomedSkillLoads(
      EnvironmentName environmentName, List<PendingSkillLoad> doomed) {
    if (environmentName == null || doomed.isEmpty()) {
      return;
    }
    for (PendingSkillLoad pending : doomed) {
      pending.future.complete(
          new EnvironmentSkillLoadResult.Failed(
              pending.skillName,
              environmentName + " is offline; " + pending.skillName + " is unavailable"));
    }
  }

  private List<PendingDirectoryList> takePendingDirectoryLists(EnvironmentName environmentName) {
    List<PendingDirectoryList> doomed = new ArrayList<>();
    List<String> keys = new ArrayList<>();
    for (Map.Entry<String, PendingDirectoryList> entry : pendingDirectoryLists.entrySet()) {
      if (environmentName.equals(entry.getValue().environmentName)) {
        keys.add(entry.getKey());
        doomed.add(entry.getValue());
      }
    }
    for (String key : keys) {
      pendingDirectoryLists.remove(key);
    }
    return doomed;
  }

  private void completeDoomedDirectoryLists(
      EnvironmentName environmentName, List<PendingDirectoryList> doomed) {
    if (environmentName == null || doomed.isEmpty()) {
      return;
    }
    for (PendingDirectoryList pending : doomed) {
      pending.future.complete(
          new EnvironmentDirectoryListResult.Failed(
              EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE,
              environmentName + " is not ready; directory listing is unavailable"));
    }
  }

  private void notifyEnvironmentReady(EnvironmentName environmentName) {
    try {
      environmentReadyListener.onEnvironmentReady(environmentName);
    } catch (RuntimeException ignored) {
      // listener 失败不得重新进入协议状态。
    }
  }

  private static void runDeferred(List<Runnable> deferred) {
    for (Runnable action : deferred) {
      try {
        action.run();
      } catch (RuntimeException ignored) {
        // listener/投影失败不得重新进入协议状态。
      }
    }
  }

  private void verifyGatewayToken(String suppliedToken) {
    byte[] expected = properties.requireDaemonToken().getBytes(StandardCharsets.UTF_8);
    byte[] supplied = suppliedToken.getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(expected, supplied)) {
      throw new DaemonProtocolException("daemon gateway token is invalid");
    }
  }

  private static String wireInvocationId(ActiveCapability active) {
    return active.invocationId.toString();
  }

  private static String unavailableMessage(EnvironmentName environmentName, String capabilityId) {
    return environmentName + " is unavailable; " + capabilityId + " cannot be executed";
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

  private enum SendOutcome {
    SENT,
    NOT_SENT,
    UNCERTAIN
  }

  private final class ActiveCapability implements EnvironmentCapabilityExecutionHandle {
    private final EnvironmentName environmentName;
    private final String connectionId;
    private final UUID invocationId;
    private final EnvironmentCapabilityExecutionListener listener;
    private volatile boolean cancelled;
    private volatile boolean terminal;
    private volatile boolean cancelSent;

    private ActiveCapability(
        EnvironmentName environmentName,
        String connectionId,
        UUID invocationId,
        EnvironmentCapabilityExecutionListener listener) {
      this.environmentName = environmentName;
      this.connectionId = connectionId;
      this.invocationId = invocationId;
      this.listener = listener;
    }

    @Override
    public void cancel() {
      // 幂等 EnvironmentCapabilityExecutionHandle.cancel：成功 start 后至多发送一次 CANCEL，且绝不在
      // terminal COMPLETED/FAILED/CANCELLED 回调之后发送。
      synchronized (this) {
        if (cancelled || terminal || cancelSent) {
          cancelled = true;
          return;
        }
        cancelled = true;
        cancelSent = true;
      }
      ConnectionState state;
      synchronized (EnvironmentDaemonGateway.this) {
        state = environmentConnections.get(environmentName);
      }
      if (state != null && state.connection.connectionId().equals(connectionId)) {
        send(state, DaemonMessageType.CANCEL, invocationId.toString(), "{}");
      }
    }

    @Override
    public boolean isCancelled() {
      return cancelled;
    }
  }

  private static final class PendingSkillLoad {
    private final EnvironmentName environmentName;
    private final String skillName;
    private final String connectionId;
    private final CompletableFuture<EnvironmentSkillLoadResult> future;

    private PendingSkillLoad(
        EnvironmentName environmentName,
        String skillName,
        String connectionId,
        CompletableFuture<EnvironmentSkillLoadResult> future) {
      this.environmentName = environmentName;
      this.skillName = skillName;
      this.connectionId = connectionId;
      this.future = future;
    }
  }

  private static final class PendingDirectoryList {
    private final EnvironmentName environmentName;
    private final String path;
    private final String connectionId;
    private final CompletableFuture<EnvironmentDirectoryListResult> future;

    private PendingDirectoryList(
        EnvironmentName environmentName,
        String path,
        String connectionId,
        CompletableFuture<EnvironmentDirectoryListResult> future) {
      this.environmentName = environmentName;
      this.path = path;
      this.connectionId = connectionId;
      this.future = future;
    }
  }

  /** 目录请求超时 tombstone：同连接迟到响应（requestId/path 均匹配）静默丢弃；FIFO 有界，断线清理时清除。 */
  private record DirectoryTombstone(
      String requestId, EnvironmentName environmentName, String connectionId, String path) {}

  private static final class ConnectionState {
    private final EnvironmentDaemonConnection connection;
    private volatile EnvironmentName environmentName;
    private volatile boolean helloReceived;
    private volatile boolean ready;

    /** transport 无法继续发送（发送失败或已开始清理）。 */
    private volatile boolean sendFailed;

    /** registry/connection 映射已恰好清理一次。 */
    private volatile boolean cleaned;

    /** protocol ERROR 已成功入队，清理后需 drain 已接受帧再关闭。 */
    private boolean closeAfterFlush;

    /** 已超时的目录请求 tombstone（FIFO，{@link #MAX_DIRECTORY_TOMBSTONES} 上限）；仅 gateway monitor 下访问。 */
    private final ArrayDeque<DirectoryTombstone> directoryTombstones = new ArrayDeque<>();

    private long outboundSequence;
    private InboundEnvelopeIdentity lastInbound;

    private ConnectionState(EnvironmentDaemonConnection connection) {
      this.connection = Objects.requireNonNull(connection, "connection");
    }

    private boolean isReady() {
      return !cleaned && !sendFailed && ready && connection.isOpen() && environmentName != null;
    }

    private boolean acceptInbound(DaemonEnvelope envelope, JsonNode payload) {
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
      EnvironmentName environmentName,
      String invocationId,
      long sequence,
      JsonNode payload) {

    private static InboundEnvelopeIdentity from(DaemonEnvelope envelope, JsonNode payload) {
      return new InboundEnvelopeIdentity(
          envelope.protocolVersion(),
          envelope.messageType(),
          envelope.environmentName(),
          envelope.invocationId(),
          envelope.sequence(),
          payload);
    }
  }
}
