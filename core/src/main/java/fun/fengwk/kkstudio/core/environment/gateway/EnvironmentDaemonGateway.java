package fun.fengwk.kkstudio.core.environment.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.environment.registry.LiveEnvironment;
import fun.fengwk.kkstudio.core.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.core.environment.service.EnvironmentSkillLoadResult;
import fun.fengwk.kkstudio.core.environment.service.EnvironmentSkillLoader;
import fun.fengwk.kkstudio.core.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.core.harness.tool.worker.DatabaseEnvironmentToolInvocationWorkerStore;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservation.ToolCompleted;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservers;
import fun.fengwk.kkstudio.harness.runtime.tool.AfterToolCallContext;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInterceptorChain;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolTargetType;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ArtifactStore;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ClaimedToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolInvocationTransactions;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolWorkerConfig;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonProtocolException;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillLoadCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolCapabilitiesCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolResultCodec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Connection-local Daemon v1 protocol handling and durable Environment invocation dispatch.
 *
 * <p>The live {@link LiveEnvironmentRegistry} owns READY connections and canonical capabilities.
 * Durable ToolInvocation leases/results remain in the database. Offline environments fail queued
 * ENVIRONMENT invocations immediately without wait/retry/fallback.
 */
@Service
public class EnvironmentDaemonGateway implements EnvironmentSkillLoader {

  private static final Pattern UNSIGNED_POSITIVE_DECIMAL = Pattern.compile("^[1-9][0-9]*$");
  private static final int OFFLINE_FAIL_BATCH = 32;

  private final LiveEnvironmentRegistry environmentRegistry;
  private final DatabaseEnvironmentToolInvocationWorkerStore invocationStore;
  private final ToolInvocationTransactions transactions;
  private final ToolInterceptorChain interceptorChain;
  private final ArtifactStore artifactStore;
  private final HarnessLifecycleObservers lifecycleObservers;
  private final DaemonToolCapabilitiesCodec capabilitiesCodec;
  private final DaemonToolResultCodec resultCodec = new DaemonToolResultCodec();
  private final DaemonEnvelopeCodec envelopeCodec = new DaemonEnvelopeCodec();
  private final DaemonSkillLoadCodec skillLoadCodec = new DaemonSkillLoadCodec();
  private final HarnessRuntimeProperties runtimeProperties;
  private final EnvironmentGatewayProperties properties;
  private final ToolWorkerConfig workerConfig;
  private final Clock clock;
  private final Map<String, ConnectionState> connections = new HashMap<>();
  private final Map<String, ConnectionState> environmentConnections = new HashMap<>();
  private final Map<String, ActiveInvocation> activeInvocations = new HashMap<>();
  private final Set<String> dispatchingEnvironments = new HashSet<>();
  private final Map<String, PendingSkillLoad> pendingSkillLoads = new HashMap<>();

  public EnvironmentDaemonGateway(
      LiveEnvironmentRegistry environmentRegistry,
      DatabaseEnvironmentToolInvocationWorkerStore invocationStore,
      ToolInvocationTransactions transactions,
      ToolInterceptorChain interceptorChain,
      ArtifactStore artifactStore,
      HarnessLifecycleObservers lifecycleObservers,
      DaemonToolCapabilitiesCodec capabilitiesCodec,
      HarnessRuntimeProperties runtimeProperties,
      EnvironmentGatewayProperties properties,
      ToolWorkerConfig workerConfig,
      Clock clock) {
    this.environmentRegistry = Objects.requireNonNull(environmentRegistry, "environmentRegistry");
    this.invocationStore = Objects.requireNonNull(invocationStore, "invocationStore");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.interceptorChain = Objects.requireNonNull(interceptorChain, "interceptorChain");
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    this.lifecycleObservers = Objects.requireNonNull(lifecycleObservers, "lifecycleObservers");
    this.capabilitiesCodec = Objects.requireNonNull(capabilitiesCodec, "capabilitiesCodec");
    this.runtimeProperties = Objects.requireNonNull(runtimeProperties, "runtimeProperties");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.workerConfig = Objects.requireNonNull(workerConfig, "workerConfig");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Registers a newly opened transport before its first HELLO frame arrives. */
  public synchronized void open(EnvironmentDaemonConnection connection) {
    Objects.requireNonNull(connection, "connection");
    String connectionId = requireNonBlank(connection.connectionId(), "connectionId");
    ConnectionState state = new ConnectionState(connection);
    ConnectionState previous = connections.put(connectionId, state);
    if (previous != null) {
      closeState(previous);
    }
  }

  /** Processes one inbound text frame; malformed protocol input never mutates durable state. */
  public void receive(String connectionId, String rawMessage) {
    ConnectionState state;
    synchronized (this) {
      state = connections.get(connectionId);
    }
    if (state == null) {
      return;
    }
    synchronized (state) {
      try {
        DaemonEnvelope envelope = envelopeCodec.decode(rawMessage);
        if (!state.acceptInbound(envelope, envelopeCodec.readPayload(envelope))) {
          return;
        }
        handleInbound(state, envelope);
      } catch (RuntimeException error) {
        protocolFailure(state, error);
      }
    }
  }

  /** Drops a transport handle without changing its durable invocation lease. */
  public synchronized void close(String connectionId) {
    ConnectionState state = connections.remove(connectionId);
    if (state != null) {
      closeState(state);
    }
  }

  /** Durable worker tick; exposed for lifecycle scheduling and deterministic integration tests. */
  public void pollOnce() {
    Instant now = clock.instant();
    failOfflineDueInvocations(now);
    List<ConnectionState> readyConnections;
    synchronized (this) {
      readyConnections =
          environmentConnections.values().stream().filter(ConnectionState::isReady).toList();
    }
    for (ConnectionState state : readyConnections) {
      pollEnvironment(state, now);
    }
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
    PendingSkillLoad pending = new PendingSkillLoad(name, skill, future);
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

  private void handleInbound(ConnectionState state, DaemonEnvelope envelope) {
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
      case READY -> handleReady(state, envelope);
      case HEARTBEAT -> handleHeartbeat(state, envelope);
      case STARTED -> handleStarted(state, envelope);
      case PARTIAL -> handlePartial(state, envelope);
      case COMPLETED -> handleCompleted(state, envelope);
      case FAILED -> handleFailed(state, envelope);
      case CANCELLED -> handleCancelled(state, envelope);
      case ACK -> handleAck(state, envelope);
      case ERROR -> handleError(state, envelope);
      case SKILL_LOADED -> handleSkillLoaded(state, envelope);
      case SKILL_LOAD_FAILED -> handleSkillLoadFailed(state, envelope);
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

  private void handleReady(ConnectionState state, DaemonEnvelope envelope) {
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
    pollEnvironment(state, now);
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

  private void handlePartial(ConnectionState state, DaemonEnvelope envelope) {
    ActiveInvocation active = requireActive(state, envelope);
    ToolResult result = decodeResult(active, envelope.payloadJson());
    if (!transactions.appendPartial(active.claimed, List.of(result), clock.instant())) {
      removeActive(active);
    }
  }

  private void handleCompleted(ConnectionState state, DaemonEnvelope envelope) {
    ActiveInvocation active = requireActive(state, envelope);
    complete(
        active, ToolInvocationStatus.SUCCEEDED, decodeResult(active, envelope.payloadJson()), null);
  }

  private void handleFailed(ConnectionState state, DaemonEnvelope envelope) {
    ActiveInvocation active = requireActive(state, envelope);
    String message =
        requiredSingleText(envelopeCodec.readPayload(envelope), "message", "FAILED payload");
    complete(
        active,
        ToolInvocationStatus.FAILED,
        ToolResult.error(active.claimed.invocation().toolCallId(), message),
        message);
  }

  private void handleCancelled(ConnectionState state, DaemonEnvelope envelope) {
    ActiveInvocation active = requireActive(state, envelope);
    String reason =
        requiredSingleText(envelopeCodec.readPayload(envelope), "reason", "CANCELLED payload");
    complete(
        active,
        ToolInvocationStatus.CANCELLED,
        ToolResult.error(active.claimed.invocation().toolCallId(), reason),
        reason);
  }

  private void handleSkillLoaded(ConnectionState state, DaemonEnvelope envelope) {
    requireReady(state);
    PendingSkillLoad pending = takePendingSkillLoad(state, envelope);
    DaemonSkillLoadCodec.SkillLoaded loaded = skillLoadCodec.decodeLoaded(envelope.payloadJson());
    if (!pending.skillName.equals(loaded.name())) {
      throw new DaemonProtocolException(
          "SKILL_LOADED name does not match request: " + loaded.name());
    }
    pending.future.complete(new EnvironmentSkillLoadResult.Loaded(loaded.name(), loaded.content()));
  }

  private void handleSkillLoadFailed(ConnectionState state, DaemonEnvelope envelope) {
    requireReady(state);
    PendingSkillLoad pending = takePendingSkillLoad(state, envelope);
    DaemonSkillLoadCodec.SkillLoadFailed failed =
        skillLoadCodec.decodeFailed(envelope.payloadJson());
    if (!pending.skillName.equals(failed.name())) {
      throw new DaemonProtocolException(
          "SKILL_LOAD_FAILED name does not match request: " + failed.name());
    }
    pending.future.complete(new EnvironmentSkillLoadResult.Failed(failed.name(), failed.message()));
  }

  private PendingSkillLoad takePendingSkillLoad(ConnectionState state, DaemonEnvelope envelope) {
    String requestId = requireNonBlank(envelope.invocationId(), "invocationId");
    PendingSkillLoad pending;
    synchronized (this) {
      pending = pendingSkillLoads.remove(requestId);
    }
    if (pending == null
        || !pending.environmentName.equals(state.environmentName)
        || !state.connection.connectionId().equals(state.connection.connectionId())) {
      throw new DaemonProtocolException(
          "skill load callback does not own invocationId: " + requestId);
    }
    return pending;
  }

  private void failOfflineDueInvocations(Instant now) {
    List<ToolInvocation> candidates =
        invocationStore.listDueEnvironmentCandidates(now, OFFLINE_FAIL_BATCH);
    for (ToolInvocation candidate : candidates) {
      String environmentName = candidate.environmentName();
      if (environmentName == null || environmentRegistry.isReady(environmentName)) {
        continue;
      }
      String owner = leaseOwner(environmentName) + "-offline";
      ClaimedToolInvocation claimed =
          invocationStore
              .claimDue(environmentName, owner, now, workerConfig.leaseDuration())
              .orElse(null);
      if (claimed == null) {
        continue;
      }
      ToolInvocation invocation = claimed.invocation();
      String message = offlineUnavailableMessage(environmentName, invocation.toolName());
      complete(
          new ActiveInvocation(claimed, null, "offline"),
          ToolInvocationStatus.FAILED,
          ToolResult.error(invocation.toolCallId(), message),
          message);
    }
  }

  private void pollEnvironment(ConnectionState state, Instant now) {
    if (!state.isReady()) {
      return;
    }
    ActiveInvocation active;
    synchronized (this) {
      active = activeInvocations.get(state.environmentName);
    }
    if (active != null) {
      pollActive(state, active, now);
      return;
    }
    dispatchNext(state, now);
  }

  private void pollActive(ConnectionState state, ActiveInvocation active, Instant now) {
    if (!active.connectionId.equals(state.connection.connectionId())) {
      return;
    }
    ToolInvocation current = invocationStore.find(active.claimed.invocation().id()).orElse(null);
    if (current == null || current.status().isTerminal()) {
      removeActive(active);
      return;
    }
    if (!now.isBefore(current.deadlineAt())) {
      complete(
          active,
          ToolInvocationStatus.FAILED,
          ToolResult.error(current.toolCallId(), "Tool execution deadline exceeded."),
          "Tool execution deadline exceeded.");
      return;
    }
    if (current.status() == ToolInvocationStatus.CANCEL_REQUESTED
        || current.cancelRequestedAt() != null) {
      if (active.markCancelSent()
          && !send(state, DaemonMessageType.CANCEL, wireInvocationId(active), "{}")) {
        return;
      }
    }
    if (!active.claimHeartbeat(now, workerConfig.heartbeatInterval())) {
      return;
    }
    if (!invocationStore.heartbeat(active.claimed, now, workerConfig.leaseDuration())) {
      removeActive(active);
    }
  }

  private void dispatchNext(ConnectionState state, Instant now) {
    String environmentName = state.environmentName;
    synchronized (this) {
      if (activeInvocations.containsKey(environmentName)
          || !state.isReady()
          || !dispatchingEnvironments.add(environmentName)) {
        return;
      }
    }
    try {
      String owner = leaseOwner(environmentName);
      ClaimedToolInvocation claimed =
          invocationStore
              .claimDue(environmentName, owner, now, workerConfig.leaseDuration())
              .orElse(null);
      if (claimed == null) {
        return;
      }
      ToolInvocation invocation = claimed.invocation();
      if (claimed.recoveredLease() && invocation.sideEffect() == ToolSideEffect.NON_IDEMPOTENT) {
        String message = "Tool ownership was lost; side effect result is unknown.";
        complete(
            new ActiveInvocation(claimed, null, state.connection.connectionId()),
            ToolInvocationStatus.UNKNOWN,
            ToolResult.error(invocation.toolCallId(), message),
            "non-idempotent invocation lease expired");
        return;
      }
      if (invocation.status() == ToolInvocationStatus.CANCEL_REQUESTED
          || invocation.cancelRequestedAt() != null) {
        complete(
            new ActiveInvocation(claimed, null, state.connection.connectionId()),
            ToolInvocationStatus.CANCELLED,
            ToolResult.error(invocation.toolCallId(), "Tool execution cancelled."),
            "Tool execution cancelled.");
        return;
      }
      if (!now.isBefore(invocation.deadlineAt())) {
        complete(
            new ActiveInvocation(claimed, null, state.connection.connectionId()),
            ToolInvocationStatus.FAILED,
            ToolResult.error(invocation.toolCallId(), "Tool execution deadline exceeded."),
            "Tool execution deadline exceeded.");
        return;
      }
      if (!environmentRegistry.isReady(environmentName)) {
        String message = offlineUnavailableMessage(environmentName, invocation.toolName());
        complete(
            new ActiveInvocation(claimed, null, state.connection.connectionId()),
            ToolInvocationStatus.FAILED,
            ToolResult.error(invocation.toolCallId(), message),
            message);
        return;
      }
      ToolBinding binding;
      String invokePayload;
      try {
        binding = resolveBinding(invocation);
        invokePayload = createInvokePayload(invocation, now);
      } catch (RuntimeException error) {
        String message = errorMessage(error, "Frozen Environment tool is unavailable.");
        complete(
            new ActiveInvocation(claimed, null, state.connection.connectionId()),
            ToolInvocationStatus.FAILED,
            ToolResult.error(invocation.toolCallId(), message),
            message);
        return;
      }
      if (!transactions.start(claimed, now)) {
        return;
      }
      ActiveInvocation active =
          new ActiveInvocation(claimed, binding, state.connection.connectionId());
      synchronized (this) {
        activeInvocations.put(environmentName, active);
      }
      if (!sendInvoke(state, active, invokePayload)) {
        removeActive(active);
      }
    } finally {
      synchronized (this) {
        dispatchingEnvironments.remove(environmentName);
      }
    }
  }

  private String createInvokePayload(ToolInvocation invocation, Instant now) {
    long timeoutMillis = Math.max(0, Duration.between(now, invocation.deadlineAt()).toMillis());
    JsonNode arguments = envelopeCodec.readJson(invocation.argumentsJson());
    if (!arguments.isObject()) {
      throw new IllegalStateException("frozen Environment tool arguments must be a JSON object");
    }
    ObjectNode payload = envelopeCodec.createPayload();
    payload.put("toolName", invocation.toolName());
    payload.put("toolVersion", invocation.toolVersion());
    payload.set("arguments", arguments);
    payload.put("timeoutMillis", timeoutMillis);
    return envelopeCodec.writeJson(payload);
  }

  private boolean sendInvoke(ConnectionState state, ActiveInvocation active, String invokePayload) {
    return send(state, DaemonMessageType.INVOKE, wireInvocationId(active), invokePayload);
  }

  private ToolBinding resolveBinding(ToolInvocation invocation) {
    String environmentName = invocation.environmentName();
    if (environmentName == null || environmentName.isBlank()) {
      throw new IllegalArgumentException("Environment invocation has no environmentName");
    }
    LiveEnvironment environment =
        environmentRegistry
            .find(environmentName)
            .filter(LiveEnvironment::isReady)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        offlineUnavailableMessage(environmentName, invocation.toolName())));
    ToolDescriptor descriptor =
        environment.tools().stream()
            .filter(
                candidate ->
                    candidate.name().equals(invocation.toolName())
                        && candidate.version().equals(invocation.toolVersion()))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        offlineUnavailableMessage(environmentName, invocation.toolName())));
    if (descriptor.executionMode() != ToolExecutionMode.ENVIRONMENT) {
      throw new IllegalArgumentException(
          "Environment capability must use ENVIRONMENT execution mode");
    }
    if (descriptor.sideEffect() != invocation.sideEffect()) {
      throw new IllegalArgumentException(
          "Environment capability sideEffect does not match frozen invocation: "
              + invocation.toolName()
              + "@"
              + invocation.toolVersion());
    }
    return new ToolBinding(descriptor, ToolTargetType.ENVIRONMENT, environmentName);
  }

  private ToolResult decodeResult(ActiveInvocation active, String payloadJson) {
    ToolResult remote =
        resultCodec.decodeResultForInvocation(
            payloadJson,
            wireInvocationId(active),
            (mediaType, sizeBytes, bytes) -> artifactStore.save(mediaType, "identity", bytes),
            properties.requireMaxArtifactBytes());
    ToolInvocation invocation = active.claimed.invocation();
    return new ToolResult(
        invocation.toolCallId(), remote.contents(), remote.error(), remote.detailsJson(), false);
  }

  private void complete(
      ActiveInvocation active,
      ToolInvocationStatus requestedStatus,
      ToolResult requestedResult,
      String requestedError) {
    ToolInvocation invocation = active.claimed.invocation();
    ToolInvocationStatus status = requestedStatus;
    ToolResult result = requestedResult;
    String error = requestedError;
    if (active.binding != null) {
      try {
        result =
            interceptorChain.after(
                new AfterToolCallContext(
                    invocation.id(),
                    active.binding,
                    new ToolCall(
                        invocation.toolCallId(), invocation.toolName(), invocation.argumentsJson()),
                    result));
      } catch (RuntimeException hookError) {
        status = ToolInvocationStatus.FAILED;
        error = errorMessage(hookError, "afterToolCall interceptor failed.");
        result = ToolResult.error(invocation.toolCallId(), error);
      }
    }
    Instant now = clock.instant();
    if (transactions.terminate(active.claimed, status, result, error, now)) {
      lifecycleObservers.publish(
          new ToolCompleted(invocation.id(), invocation.threadId(), status, error, now));
    }
    removeActive(active);
  }

  private ActiveInvocation requireActive(ConnectionState state, DaemonEnvelope envelope) {
    requireReady(state);
    long invocationId = parsePositive(envelope.invocationId(), "invocationId");
    ActiveInvocation active;
    synchronized (this) {
      active = activeInvocations.get(state.environmentName);
    }
    if (active == null
        || active.claimed.invocation().id() != invocationId
        || !active.connectionId.equals(state.connection.connectionId())) {
      throw new DaemonProtocolException(
          "daemon callback does not own invocationId: " + envelope.invocationId());
    }
    return active;
  }

  private void removeActive(ActiveInvocation active) {
    String environmentName = active.claimed.invocation().environmentName();
    if (environmentName == null) {
      return;
    }
    synchronized (this) {
      activeInvocations.remove(environmentName, active);
    }
  }

  private boolean send(
      ConnectionState state, DaemonMessageType type, String invocationId, String payloadJson) {
    synchronized (state) {
      if (state.closed || !state.connection.isOpen() || state.environmentName == null) {
        return false;
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
        return true;
      } catch (RuntimeException error) {
        close(state.connection.connectionId());
        return false;
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

  private synchronized void closeState(ConnectionState state) {
    if (state.closed) {
      return;
    }
    state.closed = true;
    if (state.environmentName != null) {
      environmentRegistry.unregister(state.environmentName, state.connection);
      environmentConnections.remove(state.environmentName, state);
      ActiveInvocation active = activeInvocations.get(state.environmentName);
      if (active != null && active.connectionId.equals(state.connection.connectionId())) {
        activeInvocations.remove(state.environmentName, active);
      }
      failPendingSkillLoads(state.environmentName);
    }
    try {
      state.connection.close();
    } catch (RuntimeException ignored) {
      // The durable lease is deliberately left untouched regardless of transport close behavior.
    }
  }

  private void failPendingSkillLoads(String environmentName) {
    List<Map.Entry<String, PendingSkillLoad>> doomed = new ArrayList<>();
    for (Map.Entry<String, PendingSkillLoad> entry : pendingSkillLoads.entrySet()) {
      if (environmentName.equals(entry.getValue().environmentName)) {
        doomed.add(entry);
      }
    }
    for (Map.Entry<String, PendingSkillLoad> entry : doomed) {
      pendingSkillLoads.remove(entry.getKey(), entry.getValue());
      entry
          .getValue()
          .future
          .complete(
              new EnvironmentSkillLoadResult.Failed(
                  entry.getValue().skillName,
                  environmentName
                      + " is offline; "
                      + entry.getValue().skillName
                      + " is unavailable"));
    }
  }

  private String leaseOwner(String environmentName) {
    return runtimeProperties.requireWorkerId() + "-environment-" + environmentName;
  }

  private void verifyGatewayToken(String suppliedToken) {
    byte[] expected = properties.requireDaemonToken().getBytes(StandardCharsets.UTF_8);
    byte[] supplied = suppliedToken.getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(expected, supplied)) {
      throw new DaemonProtocolException("daemon gateway token is invalid");
    }
  }

  private static String wireInvocationId(ActiveInvocation active) {
    return Long.toString(active.claimed.invocation().id());
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

  private static final class ActiveInvocation {
    private final ClaimedToolInvocation claimed;
    private final ToolBinding binding;
    private final String connectionId;
    private boolean cancelSent;
    private Instant nextHeartbeatAt = Instant.MIN;

    private ActiveInvocation(
        ClaimedToolInvocation claimed, ToolBinding binding, String connectionId) {
      this.claimed = Objects.requireNonNull(claimed, "claimed");
      this.binding = binding;
      this.connectionId = requireNonBlank(connectionId, "connectionId");
    }

    private synchronized boolean markCancelSent() {
      if (cancelSent) {
        return false;
      }
      cancelSent = true;
      return true;
    }

    private synchronized boolean claimHeartbeat(Instant now, Duration interval) {
      if (now.isBefore(nextHeartbeatAt)) {
        return false;
      }
      nextHeartbeatAt = now.plus(interval);
      return true;
    }
  }

  private static final class PendingSkillLoad {
    private final String environmentName;
    private final String skillName;
    private final CompletableFuture<EnvironmentSkillLoadResult> future;

    private PendingSkillLoad(
        String environmentName,
        String skillName,
        CompletableFuture<EnvironmentSkillLoadResult> future) {
      this.environmentName = environmentName;
      this.skillName = skillName;
      this.future = future;
    }
  }

  private static final class ConnectionState {
    private final EnvironmentDaemonConnection connection;
    private volatile String environmentName;
    private volatile boolean helloReceived;
    private volatile boolean capabilitiesReceived;
    private volatile boolean ready;
    private volatile boolean closed;
    private long outboundSequence;
    private InboundEnvelopeIdentity lastInbound;

    private ConnectionState(EnvironmentDaemonConnection connection) {
      this.connection = Objects.requireNonNull(connection, "connection");
    }

    private boolean isReady() {
      return !closed && ready && connection.isOpen() && environmentName != null;
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
