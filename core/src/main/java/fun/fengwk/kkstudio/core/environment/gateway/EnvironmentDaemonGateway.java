package fun.fengwk.kkstudio.core.environment.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.core.environment.service.EnvironmentSkillLoadResult;
import fun.fengwk.kkstudio.core.environment.service.EnvironmentSkillLoader;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonProtocolException;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillLoadCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolCapabilitiesCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolResultCodec;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolCancelledException;
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
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Connection/protocol transport and capability/skill adapter for Environment daemons.
 *
 * <p>Does not own durable ToolInvocation claim/lease/terminal/retry/artifact lifecycle. Durable
 * execution is owned by {@code ToolWorker}; this class supplies the core-side {@link
 * RemoteToolTransport} and forwards daemon callbacks to the registered Tool listener with strict
 * environment/connection/invocation ownership.
 */
@Service
public class EnvironmentDaemonGateway implements EnvironmentSkillLoader, RemoteToolTransport {

  private static final Pattern UNSIGNED_POSITIVE_DECIMAL = Pattern.compile("^[1-9][0-9]*$");

  private final LiveEnvironmentRegistry environmentRegistry;
  private final DaemonToolCapabilitiesCodec capabilitiesCodec;
  private final DaemonToolResultCodec resultCodec = new DaemonToolResultCodec();
  private final DaemonEnvelopeCodec envelopeCodec = new DaemonEnvelopeCodec();
  private final DaemonSkillLoadCodec skillLoadCodec = new DaemonSkillLoadCodec();
  private final EnvironmentGatewayProperties properties;
  private final Clock clock;
  private final Map<String, ConnectionState> connections = new HashMap<>();
  private final Map<String, ConnectionState> environmentConnections = new HashMap<>();
  private final Map<String, ActiveRemote> activeByEnvironment = new HashMap<>();
  private final Map<String, PendingSkillLoad> pendingSkillLoads = new HashMap<>();
  private volatile Consumer<String> environmentReadyHandler = ignored -> {};
  private volatile Executor readyDispatchExecutor = Runnable::run;

  public EnvironmentDaemonGateway(
      LiveEnvironmentRegistry environmentRegistry,
      DaemonToolCapabilitiesCodec capabilitiesCodec,
      EnvironmentGatewayProperties properties,
      Clock clock) {
    this.environmentRegistry = Objects.requireNonNull(environmentRegistry, "environmentRegistry");
    this.capabilitiesCodec = Objects.requireNonNull(capabilitiesCodec, "capabilitiesCodec");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Wires READY notifications so ToolWorker can claim due ENVIRONMENT work. */
  public void setEnvironmentReadyHandler(Consumer<String> environmentReadyHandler) {
    this.environmentReadyHandler =
        environmentReadyHandler == null ? ignored -> {} : environmentReadyHandler;
  }

  /**
   * Executor used to schedule READY dispatch off the WebSocket receive stack. Defaults to direct
   * execution (tests); production wires the tool-worker scheduler.
   */
  public void setReadyDispatchExecutor(Executor readyDispatchExecutor) {
    this.readyDispatchExecutor =
        readyDispatchExecutor == null ? Runnable::run : readyDispatchExecutor;
  }

  /** Registers a newly opened transport before its first HELLO frame arrives. */
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

  /**
   * Processes one inbound text frame. Protocol decode/sequence validation may hold connection
   * state; ToolExecutionListener and READY dispatch always run after locks are released.
   */
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
    synchronized (state) {
      try {
        DaemonEnvelope envelope = envelopeCodec.decode(rawMessage);
        if (!state.acceptInbound(envelope, envelopeCodec.readPayload(envelope))) {
          return;
        }
        handleInbound(state, envelope, deferred);
      } catch (RuntimeException error) {
        protocolError = error;
      }
    }
    if (protocolError != null) {
      protocolFailure(state, protocolError);
      return;
    }
    runDeferred(deferred);
  }

  /** Drops a transport handle; active remotes are notified as outcome-uncertain. */
  public void close(String connectionId) {
    ConnectionState state;
    synchronized (this) {
      state = connections.remove(connectionId);
    }
    if (state != null) {
      closeConnectionState(state);
    }
  }

  /**
   * Notifies ToolWorker for each READY environment. Durable recovery remains in ToolWorker;
   * retained for lifecycle scheduling and deterministic tests.
   */
  public void pollOnce() {
    List<String> ready;
    synchronized (this) {
      ready =
          environmentConnections.values().stream()
              .filter(ConnectionState::isReady)
              .map(state -> state.environmentName)
              .toList();
    }
    Consumer<String> handler = environmentReadyHandler;
    for (String environmentName : ready) {
      try {
        handler.accept(environmentName);
      } catch (RuntimeException ignored) {
        // Recovery polling remains authoritative when the routing hint fails.
      }
    }
  }

  @Override
  public ToolExecutionHandle invoke(
      String environmentName, ToolExecutionRequest request, ToolExecutionListener listener) {
    String name = requireNonBlank(environmentName, "environmentName");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");
    ConnectionState state;
    ActiveRemote active;
    String invokePayload;
    long invocationId;
    synchronized (this) {
      state = environmentConnections.get(name);
      if (state == null || !state.isReady() || !environmentRegistry.isReady(name)) {
        throw new RemoteToolUnavailableException(
            offlineUnavailableMessage(name, request.call().toolName()));
      }
      if (activeByEnvironment.containsKey(name)) {
        throw new RemoteToolUnavailableException(
            name + " already has an active remote tool invocation");
      }
      ToolDescriptor capability =
          environmentRegistry
              .find(name)
              .flatMap(
                  env ->
                      env.tools().stream()
                          .filter(
                              tool ->
                                  tool.name().equals(request.call().toolName())
                                      && tool.version().equals(request.descriptor().version()))
                          .findFirst())
              .orElse(null);
      if (capability == null || !capability.equals(request.descriptor())) {
        throw new RemoteToolUnavailableException(
            offlineUnavailableMessage(name, request.call().toolName()));
      }
      invocationId = request.context().invocationId();
      if (invocationId <= 0) {
        throw new IllegalArgumentException("invocationId must be positive");
      }
      active =
          new ActiveRemote(
              name, state.connection.connectionId(), invocationId, request.call(), listener);
      activeByEnvironment.put(name, active);
      invokePayload = createInvokePayload(request);
    }
    // Send outside the gateway monitor to avoid this->state lock inversion with receive paths.
    SendOutcome outcome =
        sendWithOutcome(
            state, DaemonMessageType.INVOKE, Long.toString(invocationId), invokePayload);
    if (outcome == SendOutcome.SENT) {
      return active;
    }
    // Prevent connection-close notify from double-firing with the thrown uncertain path.
    active.terminal = true;
    synchronized (this) {
      activeByEnvironment.remove(name, active);
    }
    if (outcome == SendOutcome.UNCERTAIN) {
      close(state.connection.connectionId());
      throw new RemoteToolSendUncertainException(
          "INVOKE send outcome is uncertain for environment " + name);
    }
    throw new RemoteToolUnavailableException(
        offlineUnavailableMessage(name, request.call().toolName()));
  }

  @Override
  public CompletableFuture<EnvironmentSkillLoadResult> loadSkill(
      String environmentName, String skillName, Duration timeout) {
    String name = requireNonBlank(environmentName, "environmentName");
    String skill = requireNonBlank(skillName, "skillName");
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    ConnectionState state;
    synchronized (this) {
      state = environmentConnections.get(name);
      if (state == null || !state.isReady()) {
        return CompletableFuture.completedFuture(
            new EnvironmentSkillLoadResult.Failed(
                skill, name + " is offline; " + skill + " is unavailable"));
      }
    }
    String requestId = UUID.randomUUID().toString();
    CompletableFuture<EnvironmentSkillLoadResult> future = new CompletableFuture<>();
    PendingSkillLoad pending =
        new PendingSkillLoad(name, skill, state.connection.connectionId(), future);
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
              skill, name + " is offline; " + skill + " is unavailable"));
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
                  skill, name + " is offline; " + skill + " is unavailable");
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
    if (state.environmentName != null
        && !state.environmentName.equals(envelope.environmentName())) {
      throw new DaemonProtocolException("envelope environmentName does not match bound connection");
    }
    switch (envelope.messageType()) {
      case HELLO -> handleHello(state, envelope);
      case CAPABILITIES -> handleCapabilities(state, envelope);
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
        payload, Set.of("daemonId", "protocolVersion", "gatewayToken"), "HELLO payload");
    requiredText(payload, "daemonId", "HELLO payload");
    if (requiredLong(payload, "protocolVersion", "HELLO payload") != DaemonProtocol.VERSION_1) {
      throw new DaemonProtocolException("HELLO payload.protocolVersion must be 1");
    }
    verifyGatewayToken(requiredText(payload, "gatewayToken", "HELLO payload"));
    String environmentName = requireNonBlank(envelope.environmentName(), "environmentName");
    Instant now = clock.instant();
    if (!environmentRegistry.tryBind(environmentName, state.connection, now)) {
      throw new DaemonProtocolException(
          "environmentName already bound to another active daemon: " + environmentName);
    }
    state.environmentName = environmentName;
    state.helloReceived = true;
    synchronized (this) {
      environmentConnections.put(environmentName, state);
    }
    send(state, DaemonMessageType.WELCOME, null, "{}");
  }

  private void handleCapabilities(ConnectionState state, DaemonEnvelope envelope) {
    requireHello(state);
    requireNoInvocationId(envelope);
    var capabilities = capabilitiesCodec.decode(envelope.payloadJson());
    environmentRegistry.updateCapabilities(
        state.environmentName, state.connection, capabilities, clock.instant());
    state.capabilitiesReceived = true;
  }

  private void handleReady(
      ConnectionState state, DaemonEnvelope envelope, List<Runnable> deferred) {
    requireHello(state);
    requireNoInvocationId(envelope);
    if (!state.capabilitiesReceived) {
      throw new DaemonProtocolException("CAPABILITIES must precede READY");
    }
    ObjectNode payload = envelopeCodec.readPayload(envelope);
    rejectUnexpectedFields(payload, Set.of("pull"), "READY payload");
    JsonNode pull = payload.get("pull");
    if (pull == null || !pull.isBoolean() || !pull.booleanValue()) {
      throw new DaemonProtocolException("READY payload.pull must be true");
    }
    Instant now = clock.instant();
    environmentRegistry.markReady(state.environmentName, state.connection, now);
    state.ready = true;
    String environmentName = state.environmentName;
    deferred.add(() -> scheduleReadyDispatch(environmentName));
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
    ToolResult result =
        resultCodec.decodeResultForInvocation(
            envelope.payloadJson(),
            wireInvocationId(active),
            properties.requireMaxArtifactBytes(),
            false);
    ToolResult mapped =
        new ToolResult(
            active.call.id(), result.contents(), result.error(), result.detailsJson(), false);
    deferred.add(() -> active.listener.onPartial(mapped));
  }

  private void handleCompleted(
      ConnectionState state, DaemonEnvelope envelope, List<Runnable> deferred) {
    ActiveRemote active = takeActive(state, envelope);
    ToolResult result =
        resultCodec.decodeResultForInvocation(
            envelope.payloadJson(),
            wireInvocationId(active),
            properties.requireMaxArtifactBytes(),
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
    deferred.add(() -> active.listener.onError(new IllegalStateException(message)));
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
              DaemonProtocol.VERSION_1,
              type,
              state.environmentName,
              invocationId,
              state.outboundSequence++,
              payloadJson);
      try {
        state.connection.sendText(envelopeCodec.encode(envelope));
        return SendOutcome.SENT;
      } catch (RuntimeException error) {
        // Transport is unusable for further sends, but registry/connection cleanup is not done yet.
        // Caller must invoke close() so closeConnectionState can finish exactly once.
        state.sendFailed = true;
        return SendOutcome.UNCERTAIN;
      }
    }
  }

  private void protocolFailure(ConnectionState state, RuntimeException error) {
    String message = errorMessage(error, "invalid daemon protocol message");
    ObjectNode payload = envelopeCodec.createPayload();
    payload.put("message", message);
    send(state, DaemonMessageType.ERROR, null, envelopeCodec.writeJson(payload));
    close(state.connection.connectionId());
  }

  private void closeConnectionState(ConnectionState state) {
    ActiveRemote lostRemote = null;
    List<PendingSkillLoad> doomedSkills = List.of();
    String environmentName = null;
    synchronized (this) {
      if (state.cleaned) {
        return;
      }
      // cleaned is the completed-cleanup fence; sendFailed only means transport is unusable.
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
      // Transport close failures must not touch durable state.
    }
    if (lostRemote != null) {
      ActiveRemote remote = lostRemote;
      String env = environmentName;
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

  private List<PendingSkillLoad> takePendingSkillLoads(String environmentName) {
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

  private void completeDoomedSkillLoads(String environmentName, List<PendingSkillLoad> doomed) {
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

  private void scheduleReadyDispatch(String environmentName) {
    Consumer<String> handler = environmentReadyHandler;
    Executor executor = readyDispatchExecutor;
    try {
      executor.execute(
          () -> {
            try {
              handler.accept(environmentName);
            } catch (RuntimeException ignored) {
              // Durable polling remains authoritative.
            }
          });
    } catch (RuntimeException rejected) {
      try {
        handler.accept(environmentName);
      } catch (RuntimeException ignored) {
        // Best-effort fallback when the scheduler rejects work.
      }
    }
  }

  private static void runDeferred(List<Runnable> deferred) {
    for (Runnable action : deferred) {
      try {
        action.run();
      } catch (RuntimeException ignored) {
        // Listener/projection failures must not re-enter protocol state.
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

  private static String offlineUnavailableMessage(String environmentName, String toolName) {
    return environmentName + " is offline; " + toolName + " is unavailable";
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
    private final String environmentName;
    private final String connectionId;
    private final long invocationId;
    private final ToolCall call;
    private final ToolExecutionListener listener;
    private volatile boolean cancelled;
    private volatile boolean terminal;
    private volatile boolean cancelSent;

    private ActiveRemote(
        String environmentName,
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
      // Idempotent ToolExecutionHandle.cancel: at most one CANCEL after a successful start, and
      // never
      // after a terminal COMPLETED/FAILED/CANCELLED callback.
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
    private final String environmentName;
    private final String skillName;
    private final String connectionId;
    private final CompletableFuture<EnvironmentSkillLoadResult> future;

    private PendingSkillLoad(
        String environmentName,
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
    private volatile String environmentName;
    private volatile boolean helloReceived;
    private volatile boolean capabilitiesReceived;
    private volatile boolean ready;

    /** Transport is unusable for further sends (failed send or cleanup started). */
    private volatile boolean sendFailed;

    /** Registry/connection maps have been cleaned exactly once. */
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
      String environmentName,
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
