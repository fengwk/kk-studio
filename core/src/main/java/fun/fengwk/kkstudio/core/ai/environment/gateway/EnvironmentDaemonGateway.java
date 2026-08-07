package fun.fengwk.kkstudio.core.ai.environment.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.ai.environment.registry.BindResult;
import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.core.ai.environment.service.EnvironmentSkillLoadResult;
import fun.fengwk.kkstudio.core.ai.environment.service.EnvironmentSkillLoader;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonNameConflictException;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonProtocolException;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillLoadCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillsCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolResultCodec;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolCancelledException;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolFailedException;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolSendUncertainException;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolTransport;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolUnavailableException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Environment daemon 的连接/协议 transport 与能力/技能适配器。
 *
 * <p>使用 Daemon v2 wire 协议：每个 envelope 都由 HELLO 时绑定的 canonical {@link EnvironmentName} 限定；名称就是唯一
 * 路由身份，不存在独立展示名。HELLO 声称的名称被另一条 live 连接（打开 + 心跳未过期）持有时抛出 {@link
 * DaemonNameConflictException}（终态错误，daemon 收到后停止重连并非零退出）；持有者连接已关闭或心跳租约过期时，registry 原子接管（{@link
 * BindResult.Replaced}），本类把被替换的旧连接状态恰好清理一次（连接关闭、active remote 按不确定收敛、 pending skill load
 * 失败），绝不触碰新持有者。不拥有持久的 ToolInvocation claim/lease/terminal/retry 生命周期。持久化执行 由 Runtime {@code
 * ToolProcessor} 负责；本类提供 core 侧 {@link RemoteToolTransport}，并把 daemon 回调转发给注册的 Tool listener，严格校验
 * environment/connection/invocation 归属。
 */
@Service
public class EnvironmentDaemonGateway
    implements EnvironmentDaemonEndpoint, EnvironmentSkillLoader, RemoteToolTransport {

  private static final Pattern UNSIGNED_POSITIVE_DECIMAL = Pattern.compile("^[1-9][0-9]*$");

  private final LiveEnvironmentRegistry environmentRegistry;
  private final DaemonSkillsCodec skillsCodec = new DaemonSkillsCodec();
  private final DaemonToolResultCodec resultCodec = new DaemonToolResultCodec();
  private final DaemonEnvelopeCodec envelopeCodec = new DaemonEnvelopeCodec();
  private final DaemonSkillLoadCodec skillLoadCodec = new DaemonSkillLoadCodec();
  private final EnvironmentGatewayProperties properties;
  private final Clock clock;
  private final EnvironmentReadyListener environmentReadyListener;
  private final Map<String, ConnectionState> connections = new HashMap<>();
  private final Map<EnvironmentName, ConnectionState> environmentConnections = new HashMap<>();
  private final Map<EnvironmentName, ActiveRemote> activeByEnvironment = new HashMap<>();
  private final Map<String, PendingSkillLoad> pendingSkillLoads = new HashMap<>();

  public EnvironmentDaemonGateway(
      LiveEnvironmentRegistry environmentRegistry,
      EnvironmentGatewayProperties properties,
      Clock clock,
      EnvironmentReadyListener environmentReadyListener) {
    this.environmentRegistry = Objects.requireNonNull(environmentRegistry, "environmentRegistry");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.environmentReadyListener =
        Objects.requireNonNull(environmentReadyListener, "environmentReadyListener");
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
  public ToolExecutionHandle invoke(
      EnvironmentName environmentName,
      ToolExecutionRequest request,
      ToolExecutionListener listener) {
    Objects.requireNonNull(environmentName, "environmentName");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");
    ConnectionState state;
    ActiveRemote active;
    String invokePayload;
    long invocationId;
    synchronized (this) {
      state = environmentConnections.get(environmentName);
      if (state == null
          || !state.isReady()
          || !environmentRegistry.isReady(
              environmentName, clock.instant(), properties.requireHeartbeatTimeout())) {
        throw new RemoteToolUnavailableException(
            unavailableMessage(environmentName, request.call().toolName()));
      }
      if (activeByEnvironment.containsKey(environmentName)) {
        throw new RemoteToolUnavailableException(
            environmentName + " already has an active remote tool invocation");
      }
      ToolDescriptor capability =
          EnvironmentToolCatalog.find(request.call().toolName())
              .filter(tool -> tool.version().equals(request.descriptor().version()))
              .orElse(null);
      if (capability == null || !capability.equals(request.descriptor())) {
        throw new RemoteToolUnavailableException(
            unavailableMessage(environmentName, request.call().toolName()));
      }
      invocationId = request.context().invocationId();
      if (invocationId <= 0) {
        throw new IllegalArgumentException("invocationId must be positive");
      }
      // 完整编码在注册 active 之前完成；确定性 payload 失败不得留下永远占用 Environment 的幽灵 invocation。
      invokePayload = createInvokePayload(request);
      active =
          new ActiveRemote(
              environmentName,
              state.connection.connectionId(),
              invocationId,
              request.call(),
              listener);
      activeByEnvironment.put(environmentName, active);
    }
    // 在 gateway monitor 之外发送，避免与 receive 路径产生 this->state 锁顺序反转。
    SendOutcome outcome =
        sendWithOutcome(
            state, DaemonMessageType.INVOKE, Long.toString(invocationId), invokePayload);
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
      throw new RemoteToolSendUncertainException(
          "INVOKE send outcome is uncertain for environment " + environmentName);
    }
    throw new RemoteToolUnavailableException(
        unavailableMessage(environmentName, request.call().toolName()));
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
          || !environmentRegistry.isReady(
              environmentName, clock.instant(), properties.requireHeartbeatTimeout())) {
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

  private String createInvokePayload(ToolExecutionRequest request) {
    long timeoutMillis = Math.max(0, request.timeout().toMillis());
    JsonNode arguments = envelopeCodec.readJson(request.call().argumentsJson());
    if (!arguments.isObject()) {
      throw new IllegalStateException("frozen Environment tool arguments must be a JSON object");
    }
    ObjectNode payload = envelopeCodec.createPayload();
    payload.put("toolName", request.call().toolName());
    payload.put("toolVersion", request.descriptor().version());
    payload.set("arguments", arguments);
    payload.put("timeoutMillis", timeoutMillis);
    return envelopeCodec.writeJson(payload);
  }

  private void handleInbound(
      ConnectionState state, DaemonEnvelope envelope, List<Runnable> deferred) {
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
      case WELCOME, INVOKE, CANCEL, LOAD_SKILL -> throw new DaemonProtocolException(
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
        Set.of("daemonId", "protocolVersion", "gatewayToken", "toolCatalogVersion"),
        "HELLO payload");
    requiredText(payload, "daemonId", "HELLO payload");
    if (requiredLong(payload, "protocolVersion", "HELLO payload") != DaemonProtocol.VERSION_2) {
      throw new DaemonProtocolException(
          "HELLO payload.protocolVersion must be " + DaemonProtocol.VERSION_2);
    }
    if (!EnvironmentToolCatalog.version()
        .equals(requiredText(payload, "toolCatalogVersion", "HELLO payload"))) {
      throw new DaemonProtocolException("HELLO toolCatalogVersion does not match server catalog");
    }
    verifyGatewayToken(requiredText(payload, "gatewayToken", "HELLO payload"));
    // HELLO 在 token/version/catalog 校验后绑定 canonical envelope 名称。
    EnvironmentName environmentName = envelope.environmentName();
    Instant now = clock.instant();
    BindResult bind =
        environmentRegistry.tryBind(
            environmentName, state.connection, now, properties.requireHeartbeatTimeout());
    if (bind instanceof BindResult.Rejected) {
      throw new DaemonNameConflictException(
          "environmentName is already held by another live daemon: " + environmentName);
    }
    if (bind instanceof BindResult.Replaced replaced) {
      // 旧持有者已死（连接关闭或心跳租约过期）：registry 已原子切换到本连接；只清理旧连接的
      // gateway 侧状态（恰好一次），registry 条目/新 holder 不受影响，也不泄漏 active remote
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
   * {@code environmentConnections.remove} 都是 owner 不匹配的 no-op；active remote 与 pending skill load
   * 只会按旧连接的 connectionId 清理，绝不会碰到新持有者。
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
    List<DaemonSkillDescriptor> skills = skillsCodec.decode(envelope.payloadJson());
    environmentRegistry.updateSkills(
        state.environmentName, state.connection, skills, clock.instant());
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
    ActiveRemote active = requireActive(state, envelope);
    // PARTIAL 拒绝 resource content：partial 结果必须只能是 text/json。
    ToolResult result =
        resultCodec.decodeResultForInvocation(
            envelope.payloadJson(),
            wireInvocationId(active),
            properties.requireMaxResourceBytes(),
            false);
    ToolResult mapped =
        new ToolResult(
            active.call.id(), result.contents(), result.error(), result.detailsJson(), false);
    deferred.add(() -> active.listener.onPartial(mapped));
  }

  private void handleCompleted(
      ConnectionState state, DaemonEnvelope envelope, List<Runnable> deferred) {
    ActiveRemote active = takeActive(state, envelope);
    // COMPLETED 把 resource 段解码为瞬态内存 BinaryToolContent；持久化外部化属于 ToolGateway，
    // 绝不属于 transport gateway。
    ToolResult result =
        resultCodec.decodeResultForInvocation(
            envelope.payloadJson(),
            wireInvocationId(active),
            properties.requireMaxResourceBytes(),
            true);
    ToolResult mapped =
        new ToolResult(
            active.call.id(), result.contents(), result.error(), result.detailsJson(), false);
    deferred.add(() -> active.listener.onComplete(mapped));
  }

  private void handleFailed(
      ConnectionState state, DaemonEnvelope envelope, List<Runnable> deferred) {
    ActiveRemote active = takeActive(state, envelope);
    String message =
        requiredSingleText(envelopeCodec.readPayload(envelope), "message", "FAILED payload");
    // daemon FAILED 是已确认的已知失败：typed 异常让 ToolGateway 映射为非可重试 durable FAILED（其余未分类错误是 UNKNOWN）。
    deferred.add(() -> active.listener.onError(new RemoteToolFailedException(message)));
  }

  private void handleCancelled(
      ConnectionState state, DaemonEnvelope envelope, List<Runnable> deferred) {
    ActiveRemote active = takeActive(state, envelope);
    String reason =
        requiredSingleText(envelopeCodec.readPayload(envelope), "reason", "CANCELLED payload");
    deferred.add(() -> active.listener.onError(new RemoteToolCancelledException(reason)));
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

  private ActiveRemote requireActive(ConnectionState state, DaemonEnvelope envelope) {
    requireReady(state);
    long invocationId = parsePositive(envelope.invocationId(), "invocationId");
    ActiveRemote active;
    synchronized (this) {
      active = activeByEnvironment.get(state.environmentName);
    }
    if (active == null
        || active.invocationId != invocationId
        || !active.connectionId.equals(state.connection.connectionId())) {
      throw new DaemonProtocolException(
          "daemon callback does not own invocationId: " + envelope.invocationId());
    }
    return active;
  }

  private ActiveRemote takeActive(ConnectionState state, DaemonEnvelope envelope) {
    ActiveRemote active = requireActive(state, envelope);
    synchronized (this) {
      activeByEnvironment.remove(state.environmentName, active);
    }
    active.terminal = true;
    return active;
  }

  private boolean send(
      ConnectionState state, DaemonMessageType type, String invocationId, String payloadJson) {
    return sendWithOutcome(state, type, invocationId, payloadJson) == SendOutcome.SENT;
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
              DaemonProtocol.VERSION_2,
              type,
              state.environmentName,
              invocationId,
              state.outboundSequence++,
              payloadJson);
      try {
        state.connection.sendText(envelopeCodec.encode(envelope));
        return SendOutcome.SENT;
      } catch (RuntimeException error) {
        // transport 无法继续发送，但 registry/connection 清理尚未完成。
        // 调用方必须调用 close()，让 closeConnectionState 恰好完成一次。
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
    if (state.environmentName == null && receivedEnvironmentName != null) {
      // HELLO 前失败：回显收到的 envelope 身份，让 daemon 可以归因。
      synchronized (state) {
        if (!state.cleaned && !state.sendFailed && state.connection.isOpen()) {
          DaemonEnvelope envelope =
              new DaemonEnvelope(
                  DaemonProtocol.VERSION_2,
                  DaemonMessageType.ERROR,
                  receivedEnvironmentName,
                  null,
                  state.outboundSequence++,
                  envelopeCodec.writeJson(payload));
          try {
            state.connection.sendText(envelopeCodec.encode(envelope));
          } catch (RuntimeException ignored) {
            state.sendFailed = true;
          }
        }
      }
    } else {
      send(state, DaemonMessageType.ERROR, null, envelopeCodec.writeJson(payload));
    }
    close(state.connection.connectionId());
  }

  private void closeConnectionState(ConnectionState state) {
    ActiveRemote lostRemote = null;
    List<PendingSkillLoad> doomedSkills = List.of();
    EnvironmentName environmentName = null;
    synchronized (this) {
      if (state.cleaned) {
        return;
      }
      // cleaned 是完成清理的围栏；sendFailed 只表示 transport 不可用。
      state.cleaned = true;
      state.sendFailed = true;
      environmentName = state.environmentName;
      if (environmentName != null) {
        environmentRegistry.unregister(environmentName, state.connection);
        environmentConnections.remove(environmentName, state);
        ActiveRemote active = activeByEnvironment.get(environmentName);
        if (active != null && active.connectionId.equals(state.connection.connectionId())) {
          activeByEnvironment.remove(environmentName, active);
          boolean shouldNotify = !active.terminal;
          active.terminal = true;
          if (shouldNotify) {
            lostRemote = active;
          }
        }
        doomedSkills = takePendingSkillLoads(environmentName);
      }
      connections.remove(state.connection.connectionId(), state);
    }
    try {
      state.connection.close();
    } catch (RuntimeException ignored) {
      // transport 关闭失败不得触碰持久状态。
    }
    if (lostRemote != null) {
      ActiveRemote remote = lostRemote;
      String env = String.valueOf(environmentName);
      runDeferred(
          List.of(
              () ->
                  remote.listener.onError(
                      new RemoteToolSendUncertainException(
                          "Daemon connection lost for environment "
                              + env
                              + "; remote tool outcome is uncertain."))));
    }
    completeDoomedSkillLoads(environmentName, doomedSkills);
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

  private static String wireInvocationId(ActiveRemote active) {
    return Long.toString(active.invocationId);
  }

  private static String unavailableMessage(EnvironmentName environmentName, String toolName) {
    return environmentName + " is unavailable; " + toolName + " cannot be executed";
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

  private static long parsePositive(String value, String field) {
    if (value == null || value.isBlank() || !UNSIGNED_POSITIVE_DECIMAL.matcher(value).matches()) {
      throw new DaemonProtocolException(field + " must be an unsigned positive decimal: " + value);
    }
    try {
      long parsed = Long.parseLong(value);
      if (parsed <= 0) {
        throw new DaemonProtocolException(field + " must be positive: " + value);
      }
      return parsed;
    } catch (NumberFormatException error) {
      throw new DaemonProtocolException(field + " exceeds long range: " + value, error);
    }
  }

  private enum SendOutcome {
    SENT,
    NOT_SENT,
    UNCERTAIN
  }

  private final class ActiveRemote implements ToolExecutionHandle {
    private final EnvironmentName environmentName;
    private final String connectionId;
    private final long invocationId;
    private final ToolCall call;
    private final ToolExecutionListener listener;
    private volatile boolean cancelled;
    private volatile boolean terminal;
    private volatile boolean cancelSent;

    private ActiveRemote(
        EnvironmentName environmentName,
        String connectionId,
        long invocationId,
        ToolCall call,
        ToolExecutionListener listener) {
      this.environmentName = environmentName;
      this.connectionId = connectionId;
      this.invocationId = invocationId;
      this.call = call;
      this.listener = listener;
    }

    @Override
    public void cancel() {
      // 幂等 ToolExecutionHandle.cancel：成功 start 后至多发送一次 CANCEL，且绝不在
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
        send(state, DaemonMessageType.CANCEL, Long.toString(invocationId), "{}");
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

  private static final class ConnectionState {
    private final EnvironmentDaemonConnection connection;
    private volatile EnvironmentName environmentName;
    private volatile boolean helloReceived;
    private volatile boolean ready;

    /** transport 无法继续发送（发送失败或已开始清理）。 */
    private volatile boolean sendFailed;

    /** registry/connection 映射已恰好清理一次。 */
    private volatile boolean cleaned;

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
