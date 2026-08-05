package fun.fengwk.kkstudio.core.ai.environment.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.core.ai.environment.service.EnvironmentSkillLoadResult;
import fun.fengwk.kkstudio.core.ai.environment.service.EnvironmentSkillLoader;
import fun.fengwk.kkstudio.harness.tool.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType;
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
 * Connection/protocol transport and capability/skill adapter for Environment daemons.
 *
 * <p>Speaks the Daemon v2 wire protocol: every envelope is scoped by the canonical {@link
 * EnvironmentId} bound at HELLO; the display {@code environmentName} is metadata only and never
 * routes. Does not own durable ToolInvocation claim/lease/terminal/retry lifecycle. Durable
 * execution is owned by {@code ToolWorker}; this class supplies the core-side {@link
 * RemoteToolTransport} and forwards daemon callbacks to the registered Tool listener with strict
 * environment/connection/invocation ownership.
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
  private final Map<EnvironmentId, ConnectionState> environmentConnections = new HashMap<>();
  private final Map<EnvironmentId, ActiveRemote> activeByEnvironment = new HashMap<>();
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
    EnvironmentId receivedEnvironmentId = null;
    String receivedEnvironmentName = null;
    synchronized (state) {
      try {
        DaemonEnvelope envelope = envelopeCodec.decode(rawMessage);
        receivedEnvironmentId = envelope.environmentId();
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
      protocolFailure(state, protocolError, receivedEnvironmentId, receivedEnvironmentName);
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
      EnvironmentId environmentId, ToolExecutionRequest request, ToolExecutionListener listener) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");
    ConnectionState state;
    ActiveRemote active;
    String invokePayload;
    long invocationId;
    synchronized (this) {
      state = environmentConnections.get(environmentId);
      if (state == null || !state.isReady() || !environmentRegistry.isReady(environmentId)) {
        throw new RemoteToolUnavailableException(
            offlineUnavailableMessage(environmentId, state, request.call().toolName()));
      }
      if (activeByEnvironment.containsKey(environmentId)) {
        throw new RemoteToolUnavailableException(
            environmentId + " already has an active remote tool invocation");
      }
      ToolDescriptor capability =
          EnvironmentToolCatalog.find(request.call().toolName())
              .filter(tool -> tool.version().equals(request.descriptor().version()))
              .orElse(null);
      if (capability == null || !capability.equals(request.descriptor())) {
        throw new RemoteToolUnavailableException(
            offlineUnavailableMessage(environmentId, state, request.call().toolName()));
      }
      invocationId = request.context().invocationId();
      if (invocationId <= 0) {
        throw new IllegalArgumentException("invocationId must be positive");
      }
      active =
          new ActiveRemote(
              environmentId,
              state.connection.connectionId(),
              invocationId,
              request.call(),
              listener);
      activeByEnvironment.put(environmentId, active);
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
      activeByEnvironment.remove(environmentId, active);
    }
    if (outcome == SendOutcome.UNCERTAIN) {
      close(state.connection.connectionId());
      throw new RemoteToolSendUncertainException(
          "INVOKE send outcome is uncertain for environment "
              + environmentLabel(environmentId, state.environmentName));
    }
    throw new RemoteToolUnavailableException(
        offlineUnavailableMessage(environmentId, state, request.call().toolName()));
  }

  @Override
  public CompletableFuture<EnvironmentSkillLoadResult> loadSkill(
      EnvironmentId environmentId, String skillName, Duration timeout) {
    Objects.requireNonNull(environmentId, "environmentId");
    String skill = requireNonBlank(skillName, "skillName");
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    ConnectionState state;
    synchronized (this) {
      state = environmentConnections.get(environmentId);
      if (state == null || !state.isReady()) {
        return CompletableFuture.completedFuture(
            new EnvironmentSkillLoadResult.Failed(
                skill, environmentId + " is offline; " + skill + " is unavailable"));
      }
    }
    String requestId = UUID.randomUUID().toString();
    CompletableFuture<EnvironmentSkillLoadResult> future = new CompletableFuture<>();
    PendingSkillLoad pending =
        new PendingSkillLoad(environmentId, skill, state.connection.connectionId(), future);
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
              skill, environmentId + " is offline; " + skill + " is unavailable"));
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
                  skill, environmentId + " is offline; " + skill + " is unavailable");
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
    // Post-HELLO envelopes must carry the bound canonical id; a mismatch is a protocol failure that
    // never re-routes the binding. The display name must stay stable within one connection.
    if (state.environmentId != null && !state.environmentId.equals(envelope.environmentId())) {
      throw new DaemonProtocolException("envelope environmentId does not match bound connection");
    }
    if (state.environmentName != null
        && !state.environmentName.equals(envelope.environmentName())) {
      throw new DaemonProtocolException("envelope environmentName does not match bound connection");
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
    // HELLO binds the canonical envelope identity after token/version/catalog validation.
    EnvironmentId environmentId = envelope.environmentId();
    String environmentName = envelope.environmentName();
    Instant now = clock.instant();
    if (!environmentRegistry.tryBind(environmentId, environmentName, state.connection, now)) {
      throw new DaemonProtocolException(
          "environmentId already bound to another active daemon: " + environmentId);
    }
    state.environmentId = environmentId;
    state.environmentName = environmentName;
    state.helloReceived = true;
    synchronized (this) {
      environmentConnections.put(environmentId, state);
    }
    send(state, DaemonMessageType.WELCOME, null, "{}");
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
        state.environmentId, state.connection, skills, clock.instant());
    Instant now = clock.instant();
    environmentRegistry.markReady(state.environmentId, state.connection, now);
    state.ready = true;
    EnvironmentId environmentId = state.environmentId;
    deferred.add(() -> notifyEnvironmentReady(environmentId));
  }

  private void handleHeartbeat(ConnectionState state, DaemonEnvelope envelope) {
    requireReady(state);
    requireNoInvocationId(envelope);
    rejectUnexpectedFields(envelopeCodec.readPayload(envelope), Set.of(), "HEARTBEAT payload");
    environmentRegistry.heartbeat(state.environmentId, state.connection, clock.instant());
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
    // PARTIAL rejects resource content: partial results must stay text/json only.
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
    // COMPLETED decodes resource segments to transient in-memory BinaryToolContent; durable
    // externalization belongs to the ToolWorker, never to the gateway.
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
        || !pending.environmentId.equals(state.environmentId)
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
      active = activeByEnvironment.get(state.environmentId);
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
      activeByEnvironment.remove(state.environmentId, active);
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
          || state.environmentId == null) {
        return SendOutcome.NOT_SENT;
      }
      DaemonEnvelope envelope =
          new DaemonEnvelope(
              DaemonProtocol.VERSION_2,
              type,
              state.environmentId,
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

  private void protocolFailure(
      ConnectionState state,
      RuntimeException error,
      EnvironmentId receivedEnvironmentId,
      String receivedEnvironmentName) {
    String message = errorMessage(error, "invalid daemon protocol message");
    ObjectNode payload = envelopeCodec.createPayload();
    payload.put("message", message);
    if (state.environmentId == null && receivedEnvironmentId != null) {
      // Pre-HELLO failure: echo the received envelope identity so the daemon can attribute it.
      synchronized (state) {
        if (!state.cleaned && !state.sendFailed && state.connection.isOpen()) {
          DaemonEnvelope envelope =
              new DaemonEnvelope(
                  DaemonProtocol.VERSION_2,
                  DaemonMessageType.ERROR,
                  receivedEnvironmentId,
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
    EnvironmentId environmentId = null;
    String environmentName = null;
    synchronized (this) {
      if (state.cleaned) {
        return;
      }
      // cleaned is the completed-cleanup fence; sendFailed only means transport is unusable.
      state.cleaned = true;
      state.sendFailed = true;
      environmentId = state.environmentId;
      environmentName = state.environmentName;
      if (environmentId != null) {
        environmentRegistry.unregister(environmentId, state.connection);
        environmentConnections.remove(environmentId, state);
        ActiveRemote active = activeByEnvironment.get(environmentId);
        if (active != null && active.connectionId.equals(state.connection.connectionId())) {
          activeByEnvironment.remove(environmentId, active);
          boolean shouldNotify = !active.terminal;
          active.terminal = true;
          if (shouldNotify) {
            lostRemote = active;
          }
        }
        doomedSkills = takePendingSkillLoads(environmentId);
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
      String env = environmentLabel(environmentId, environmentName);
      runDeferred(
          List.of(
              () ->
                  remote.listener.onError(
                      new RemoteToolSendUncertainException(
                          "Daemon connection lost for environment "
                              + env
                              + "; remote tool outcome is uncertain."))));
    }
    completeDoomedSkillLoads(environmentId, environmentName, doomedSkills);
  }

  private List<PendingSkillLoad> takePendingSkillLoads(EnvironmentId environmentId) {
    List<PendingSkillLoad> doomed = new ArrayList<>();
    List<String> keys = new ArrayList<>();
    for (Map.Entry<String, PendingSkillLoad> entry : pendingSkillLoads.entrySet()) {
      if (environmentId.equals(entry.getValue().environmentId)) {
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
      EnvironmentId environmentId, String environmentName, List<PendingSkillLoad> doomed) {
    if (environmentId == null || doomed.isEmpty()) {
      return;
    }
    String env = environmentLabel(environmentId, environmentName);
    for (PendingSkillLoad pending : doomed) {
      pending.future.complete(
          new EnvironmentSkillLoadResult.Failed(
              pending.skillName, env + " is offline; " + pending.skillName + " is unavailable"));
    }
  }

  private void notifyEnvironmentReady(EnvironmentId environmentId) {
    try {
      environmentReadyListener.onEnvironmentReady(environmentId);
    } catch (RuntimeException ignored) {
      // Listener failures must not re-enter protocol state.
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

  private static String environmentLabel(EnvironmentId environmentId, String environmentName) {
    if (environmentName == null) {
      return environmentId.toString();
    }
    return environmentId + " (" + environmentName + ")";
  }

  private static String offlineUnavailableMessage(
      EnvironmentId environmentId, ConnectionState state, String toolName) {
    String environmentName = state == null ? null : state.environmentName;
    return environmentLabel(environmentId, environmentName)
        + " is offline; "
        + toolName
        + " is unavailable";
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
    private final EnvironmentId environmentId;
    private final String connectionId;
    private final long invocationId;
    private final ToolCall call;
    private final ToolExecutionListener listener;
    private volatile boolean cancelled;
    private volatile boolean terminal;
    private volatile boolean cancelSent;

    private ActiveRemote(
        EnvironmentId environmentId,
        String connectionId,
        long invocationId,
        ToolCall call,
        ToolExecutionListener listener) {
      this.environmentId = environmentId;
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
        state = environmentConnections.get(environmentId);
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
    private final EnvironmentId environmentId;
    private final String skillName;
    private final String connectionId;
    private final CompletableFuture<EnvironmentSkillLoadResult> future;

    private PendingSkillLoad(
        EnvironmentId environmentId,
        String skillName,
        String connectionId,
        CompletableFuture<EnvironmentSkillLoadResult> future) {
      this.environmentId = environmentId;
      this.skillName = skillName;
      this.connectionId = connectionId;
      this.future = future;
    }
  }

  private static final class ConnectionState {
    private final EnvironmentDaemonConnection connection;
    private volatile EnvironmentId environmentId;
    private volatile String environmentName;
    private volatile boolean helloReceived;
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
      return !cleaned && !sendFailed && ready && connection.isOpen() && environmentId != null;
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
      EnvironmentId environmentId,
      String environmentName,
      String invocationId,
      long sequence,
      JsonNode payload) {

    private static InboundEnvelopeIdentity from(DaemonEnvelope envelope, JsonNode payload) {
      return new InboundEnvelopeIdentity(
          envelope.protocolVersion(),
          envelope.messageType(),
          envelope.environmentId(),
          envelope.environmentName(),
          envelope.invocationId(),
          envelope.sequence(),
          payload);
    }
  }
}
