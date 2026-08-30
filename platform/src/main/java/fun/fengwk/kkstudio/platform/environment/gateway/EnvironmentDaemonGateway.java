package fun.fengwk.kkstudio.platform.environment.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.environment.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.environment.EnvironmentName;
import fun.fengwk.kkstudio.harness.environment.EnvironmentWorkspacePath;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCancelledException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityFailedException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
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
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonNameConflictException;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocolException;
import fun.fengwk.kkstudio.harness.environment.daemon.EnvironmentDirectoryEntry;
import fun.fengwk.kkstudio.harness.environment.daemon.EnvironmentDirectoryListing;
import fun.fengwk.kkstudio.harness.tool.JsonToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.platform.environment.query.EnvironmentQueryCoordinator;
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
import java.util.ArrayDeque;
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
import java.util.concurrent.TimeoutException;

/**
 * Environment daemon 的连接/协议 transport 与能力/技能适配器。
 *
 * <p>使用当前 Daemon wire 协议（VERSION=5）：每个 envelope 都由 HELLO 时绑定的 canonical {@link EnvironmentName}
 * 限定；名称就是唯一路由身份，不存在独立展示名。HELLO 认证时通过 PostgreSQL 路由租约原子抢占：同 daemonId 活跃路由返回 RETRY_LATER；不同 daemonId
 * 活跃路由抛出 {@link DaemonNameConflictException}（终态错误，daemon 收到后停止重连并非零退出）；缺少或过期路由以新 routeToken 接管。
 *
 * <p>READY、HEARTBEAT 与断开连接均以 {@code (environmentName, ownerNodeId, routeToken)} 围栏更新；数据库不可用时
 * fail-closed。只读目录查询支持本地直接派发与跨节点 {@code environment_query} 信箱协调。
 */
@Service
public class EnvironmentDaemonGateway
    implements EnvironmentDaemonEndpoint,
        EnvironmentSkillLoader,
        EnvironmentCapabilityTransport,
        EnvironmentDirectoryLister {

  private static final int MAX_INVOCATION_TOMBSTONES = 1024;

  private final LiveEnvironmentRegistry environmentRegistry;
  private final DaemonCapabilitiesCodec capabilitiesCodec = new DaemonCapabilitiesCodec();
  private final DaemonCapabilityInvokeCodec capabilityInvokeCodec =
      new DaemonCapabilityInvokeCodec();
  private final DaemonCapabilityResultCodec resultCodec = new DaemonCapabilityResultCodec();
  private final DaemonEnvelopeCodec envelopeCodec = new DaemonEnvelopeCodec();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final EnvironmentGatewayProperties properties;
  private final SystemSettingsSnapshot snapshot;
  private final Clock clock;
  private final EnvironmentReadyListener environmentReadyListener;
  private final EnvironmentQueryCoordinator queryCoordinator;
  private final Map<String, ConnectionState> connections = new HashMap<>();
  private final Map<EnvironmentName, ConnectionState> environmentConnections = new HashMap<>();
  private final Map<EnvironmentName, ActiveCapability> activeByEnvironment = new HashMap<>();

  @Autowired
  public EnvironmentDaemonGateway(
      LiveEnvironmentRegistry environmentRegistry,
      EnvironmentGatewayProperties properties,
      SystemSettingsSnapshot snapshot,
      Clock clock,
      EnvironmentReadyListener environmentReadyListener,
      @Autowired(required = false) EnvironmentQueryCoordinator queryCoordinator) {
    this.environmentRegistry = Objects.requireNonNull(environmentRegistry, "environmentRegistry");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.environmentReadyListener =
        Objects.requireNonNull(environmentReadyListener, "environmentReadyListener");
    this.queryCoordinator = queryCoordinator;
    if (queryCoordinator != null) {
      queryCoordinator.registerLocalExecutor(
          this::listDirectoryLocal, this::localReadyEnvironments);
    }
  }

  public EnvironmentDaemonGateway(
      LiveEnvironmentRegistry environmentRegistry,
      EnvironmentGatewayProperties properties,
      SystemSettingsSnapshot snapshot,
      Clock clock,
      EnvironmentReadyListener environmentReadyListener) {
    this(environmentRegistry, properties, snapshot, clock, environmentReadyListener, null);
  }

  /** 心跳过期超时（毫秒）：环境按不可用处理的租约时长。 */
  Duration heartbeatTimeout() {
    return Duration.ofMillis(environment().heartbeatTimeoutMillis());
  }

  private SystemSettings.Environment environment() {
    return snapshot.get().environment();
  }

  public synchronized Set<EnvironmentName> localReadyEnvironments() {
    Set<EnvironmentName> ready = new HashSet<>();
    for (Map.Entry<EnvironmentName, ConnectionState> entry : environmentConnections.entrySet()) {
      if (entry.getValue().isReady()) {
        ready.add(entry.getKey());
      }
    }
    return Set.copyOf(ready);
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
    return startCapability(binding, request, listener);
  }

  private ActiveCapability startCapability(
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
      LiveEnvironment live = environmentRegistry.find(environmentName).orElse(null);
      if (state == null
          || !state.isReady()
          || live == null
          || !live.isReady(clock.instant(), heartbeatTimeout())
          || !live.ownerNodeId().equals(environmentRegistry.ownerNodeId())
          || !Objects.equals(live.routeToken(), state.routeToken)) {
        throw new EnvironmentCapabilityUnavailableException(
            unavailableMessage(environmentName, descriptor.id().value()));
      }
      if (activeByEnvironment.containsKey(environmentName)) {
        throw new EnvironmentCapabilityBusyException(
            environmentName + " already has an active capability invocation");
      }
      invokePayload = createInvokePayload(binding, request);
      active =
          new ActiveCapability(
              environmentName, state.connection.connectionId(), invocationId, listener);
      activeByEnvironment.put(environmentName, active);
    }
    SendOutcome outcome =
        sendWithOutcome(state, DaemonMessageType.INVOKE, invocationId.toString(), invokePayload);
    if (outcome == SendOutcome.SENT) {
      return active;
    }
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
    EnvironmentCapabilityDescriptor descriptor =
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.SKILL_LOAD);
    ObjectNode argsNode = objectMapper.createObjectNode();
    argsNode.put("name", skill);
    String callId = UUID.randomUUID().toString();
    EnvironmentCapabilityCall call = new EnvironmentCapabilityCall(callId, argsNode.toString());
    EnvironmentCapabilityExecutionRequest request =
        new EnvironmentCapabilityExecutionRequest(descriptor, call, timeout, null);
    EnvironmentBinding binding = new EnvironmentBinding(environmentName, ".");

    CompletableFuture<EnvironmentSkillLoadResult> future = new CompletableFuture<>();
    ActiveCapability active;
    try {
      active =
          startCapability(
              binding,
              request,
              new EnvironmentCapabilityExecutionListener() {
                @Override
                public void onPartial(EnvironmentCapabilityResult partial) {}

                @Override
                public void onComplete(EnvironmentCapabilityResult result) {
                  if (result.error()) {
                    String errorMsg = extractErrorMessage(result, "unknown skill: " + skill);
                    future.complete(new EnvironmentSkillLoadResult.Failed(skill, errorMsg));
                  } else if (result.contents().size() == 1
                      && result.contents().get(0) instanceof TextToolContent text) {
                    future.complete(new EnvironmentSkillLoadResult.Loaded(skill, text.text()));
                  } else {
                    future.complete(
                        new EnvironmentSkillLoadResult.Failed(
                            skill, "Unexpected skill content type for " + skill));
                  }
                }

                @Override
                public void onError(Throwable error) {
                  future.complete(
                      new EnvironmentSkillLoadResult.Failed(
                          skill, environmentName + " is offline; " + skill + " is unavailable"));
                }
              });
    } catch (EnvironmentCapabilityUnavailableException unavailable) {
      return CompletableFuture.completedFuture(
          new EnvironmentSkillLoadResult.Failed(
              skill, environmentName + " is offline; " + skill + " is unavailable"));
    } catch (EnvironmentCapabilityBusyException busy) {
      return CompletableFuture.completedFuture(
          new EnvironmentSkillLoadResult.Failed(skill, busy.getMessage()));
    } catch (EnvironmentCapabilitySendUncertainException uncertain) {
      return CompletableFuture.completedFuture(
          new EnvironmentSkillLoadResult.Failed(skill, uncertain.getMessage()));
    } catch (RuntimeException error) {
      return CompletableFuture.completedFuture(
          new EnvironmentSkillLoadResult.Failed(
              skill, error.getMessage() != null ? error.getMessage() : "skill load failed"));
    }

    return future
        .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
        .exceptionally(
            error -> {
              expireCapability(active);
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
      directoryPath = EnvironmentWorkspacePath.requireCanonicalRelativePath(path);
    } catch (IllegalArgumentException error) {
      return CompletableFuture.completedFuture(
          new EnvironmentDirectoryListResult.Failed(
              EnvironmentDirectoryFailureCode.INVALID_PATH, error.getMessage()));
    }
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }

    LiveEnvironment live;
    try {
      live = environmentRegistry.find(environmentName).orElse(null);
    } catch (DataAccessException error) {
      return CompletableFuture.completedFuture(
          new EnvironmentDirectoryListResult.Failed(
              EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE,
              "database unavailable: " + error.getMessage()));
    }
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

    ConnectionState localState;
    synchronized (this) {
      localState = environmentConnections.get(environmentName);
    }
    if (localState != null
        && localState.isReady()
        && localState.routeToken != null
        && localState.routeToken.equals(live.routeToken())
        && live.ownerNodeId().equals(environmentRegistry.ownerNodeId())) {
      return listDirectoryLocal(environmentName, directoryPath, timeout);
    }

    if (queryCoordinator != null) {
      return queryCoordinator.executeRemoteDirectoryQuery(environmentName, directoryPath, timeout);
    }

    return CompletableFuture.completedFuture(
        new EnvironmentDirectoryListResult.Failed(
            EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE,
            environmentName + " is not ready locally"));
  }

  public CompletableFuture<EnvironmentDirectoryListResult> listDirectoryLocal(
      EnvironmentName environmentName, String path, Duration timeout) {
    Objects.requireNonNull(environmentName, "environmentName");
    String directoryPath;
    try {
      directoryPath = EnvironmentWorkspacePath.requireCanonicalRelativePath(path);
    } catch (IllegalArgumentException error) {
      return CompletableFuture.completedFuture(
          new EnvironmentDirectoryListResult.Failed(
              EnvironmentDirectoryFailureCode.INVALID_PATH, error.getMessage()));
    }
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }

    EnvironmentCapabilityDescriptor descriptor =
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_LIST_DIRECTORY);
    ObjectNode argsNode = objectMapper.createObjectNode();
    argsNode.put("path", directoryPath);
    String callId = UUID.randomUUID().toString();
    EnvironmentCapabilityCall call = new EnvironmentCapabilityCall(callId, argsNode.toString());
    EnvironmentCapabilityExecutionRequest request =
        new EnvironmentCapabilityExecutionRequest(descriptor, call, timeout, null);
    EnvironmentBinding binding = new EnvironmentBinding(environmentName, ".");

    CompletableFuture<EnvironmentDirectoryListResult> future = new CompletableFuture<>();
    ActiveCapability active;
    try {
      active =
          startCapability(
              binding,
              request,
              new EnvironmentCapabilityExecutionListener() {
                @Override
                public void onPartial(EnvironmentCapabilityResult partial) {}

                @Override
                public void onComplete(EnvironmentCapabilityResult result) {
                  if (result.error()) {
                    String errorMsg = extractErrorMessage(result, "directory listing failed");
                    EnvironmentDirectoryFailureCode code = classifyDirectoryError(errorMsg);
                    future.complete(new EnvironmentDirectoryListResult.Failed(code, errorMsg));
                  } else if (result.contents().size() == 1
                      && result.contents().get(0) instanceof JsonToolContent json) {
                    try {
                      EnvironmentDirectoryListing listing =
                          objectMapper.readValue(json.json(), EnvironmentDirectoryListing.class);
                      EnvironmentDirectoryDTO dto = toDto(listing);
                      future.complete(new EnvironmentDirectoryListResult.Loaded(dto));
                    } catch (Exception parseError) {
                      future.complete(
                          new EnvironmentDirectoryListResult.Failed(
                              EnvironmentDirectoryFailureCode.IO_ERROR,
                              "Failed to parse directory listing: " + parseError.getMessage()));
                    }
                  } else {
                    future.complete(
                        new EnvironmentDirectoryListResult.Failed(
                            EnvironmentDirectoryFailureCode.IO_ERROR,
                            "Expected JsonToolContent but got unexpected content"));
                  }
                }

                @Override
                public void onError(Throwable error) {
                  future.complete(
                      new EnvironmentDirectoryListResult.Failed(
                          EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE,
                          environmentName + " is not ready; directory listing is unavailable"));
                }
              });
    } catch (EnvironmentCapabilityUnavailableException unavailable) {
      return CompletableFuture.completedFuture(
          new EnvironmentDirectoryListResult.Failed(
              EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE,
              environmentName + " is not ready; directory listing is unavailable"));
    } catch (EnvironmentCapabilityBusyException busy) {
      return CompletableFuture.completedFuture(
          new EnvironmentDirectoryListResult.Failed(
              EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE, busy.getMessage()));
    } catch (EnvironmentCapabilitySendUncertainException uncertain) {
      return CompletableFuture.completedFuture(
          new EnvironmentDirectoryListResult.Failed(
              EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE, uncertain.getMessage()));
    } catch (RuntimeException error) {
      return CompletableFuture.completedFuture(
          new EnvironmentDirectoryListResult.Failed(
              EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE, error.getMessage()));
    }

    return future
        .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
        .exceptionally(
            error -> {
              expireCapability(active);
              boolean isTimeout =
                  error instanceof TimeoutException || error.getCause() instanceof TimeoutException;
              EnvironmentDirectoryFailureCode code =
                  isTimeout
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

  private static EnvironmentDirectoryDTO toDto(EnvironmentDirectoryListing listing) {
    EnvironmentDirectoryDTO dto = new EnvironmentDirectoryDTO();
    dto.setPath(listing.path());
    dto.setDisplayPath(listing.displayPath());
    dto.setParentPath(listing.parentPath());
    dto.setTruncated(listing.truncated());
    dto.setGitBranch(listing.gitBranch());
    List<EnvironmentDirectoryEntryDTO> entries = new ArrayList<>();
    for (EnvironmentDirectoryEntry entry : listing.entries()) {
      EnvironmentDirectoryEntryDTO entryDto = new EnvironmentDirectoryEntryDTO();
      entryDto.setName(entry.name());
      entryDto.setPath(entry.path());
      entries.add(entryDto);
    }
    dto.setEntries(List.copyOf(entries));
    return dto;
  }

  private static EnvironmentDirectoryFailureCode classifyDirectoryError(String message) {
    if (message == null) {
      return EnvironmentDirectoryFailureCode.IO_ERROR;
    }
    String lower = message.toLowerCase();
    if (lower.contains("nosuchfile")
        || lower.contains("not found")
        || lower.contains("does not exist")) {
      return EnvironmentDirectoryFailureCode.NOT_FOUND;
    }
    if (lower.contains("notdirectory") || lower.contains("not a directory")) {
      return EnvironmentDirectoryFailureCode.NOT_DIRECTORY;
    }
    if (lower.contains("invalid") || lower.contains("escapes") || lower.contains("canonical")) {
      return EnvironmentDirectoryFailureCode.INVALID_PATH;
    }
    return EnvironmentDirectoryFailureCode.IO_ERROR;
  }

  private static String extractErrorMessage(EnvironmentCapabilityResult result, String fallback) {
    if (!result.contents().isEmpty() && result.contents().get(0) instanceof TextToolContent text) {
      String msg = text.text();
      if (msg.startsWith("Error: ")) {
        return msg.substring("Error: ".length());
      }
      return msg;
    }
    return fallback;
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
      case WELCOME, INVOKE, CANCEL -> throw new DaemonProtocolException(
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
    String daemonId = requiredText(payload, "daemonId", "HELLO payload");
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
    EnvironmentName environmentName = envelope.environmentName();

    BindResult bind = environmentRegistry.tryAcquire(environmentName, daemonId, heartbeatTimeout());
    if (bind instanceof BindResult.Conflict conflict) {
      throw new DaemonNameConflictException(conflict.message());
    }
    if (bind instanceof BindResult.RetryLater retryLater) {
      throw new DaemonRetryLaterException(retryLater.message());
    }
    if (bind instanceof BindResult.Acquired acquired) {
      ConnectionState previousState;
      synchronized (this) {
        previousState = environmentConnections.get(environmentName);
      }
      if (previousState != null && previousState != state) {
        closeConnectionState(previousState);
      }
      state.routeToken = acquired.routeToken();
      state.daemonId = daemonId;
      state.environmentName = environmentName;
      state.helloReceived = true;
      synchronized (this) {
        environmentConnections.put(environmentName, state);
      }
      send(state, DaemonMessageType.WELCOME, null, "{}");
    }
  }

  private void handleReady(
      ConnectionState state, DaemonEnvelope envelope, List<Runnable> deferred) {
    requireHello(state);
    requireNoInvocationId(envelope);
    if (state.ready) {
      throw new DaemonProtocolException("READY may only be sent once per connection");
    }
    if (state.routeToken == null) {
      throw new DaemonProtocolException("routeToken missing on READY");
    }
    DaemonCapabilities capabilities = capabilitiesCodec.decode(envelope.payloadJson());
    boolean ok =
        environmentRegistry.markReady(
            state.environmentName, state.routeToken, capabilities, heartbeatTimeout());
    if (!ok) {
      throw new DaemonProtocolException(
          "route fence lost for environment " + state.environmentName);
    }
    state.ready = true;
    EnvironmentName environmentName = state.environmentName;
    deferred.add(() -> notifyEnvironmentReady(environmentName));
  }

  private void handleHeartbeat(ConnectionState state, DaemonEnvelope envelope) {
    requireReady(state);
    requireNoInvocationId(envelope);
    rejectUnexpectedFields(envelopeCodec.readPayload(envelope), Set.of(), "HEARTBEAT payload");
    if (state.routeToken == null) {
      throw new DaemonProtocolException("routeToken missing on HEARTBEAT");
    }
    boolean ok =
        environmentRegistry.heartbeat(state.environmentName, state.routeToken, heartbeatTimeout());
    if (!ok) {
      throw new DaemonProtocolException(
          "route fence lost for environment " + state.environmentName);
    }
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
    ActiveCapability active = callbackTarget(state, envelope);
    EnvironmentCapabilityResult result =
        resultCodec.decodePartialForInvocation(
            envelope.payloadJson(), envelope.invocationId(), environment().maxResourceBytes());
    if (active != null && isCurrentActive(active)) {
      deferred.add(() -> active.listener.onPartial(result));
    }
  }

  private void handleCompleted(
      ConnectionState state, DaemonEnvelope envelope, List<Runnable> deferred) {
    callbackTarget(state, envelope);
    EnvironmentCapabilityResult result =
        resultCodec.decodeCompletedForInvocation(
            envelope.payloadJson(), envelope.invocationId(), environment().maxResourceBytes());
    ActiveCapability active = takeTerminalTarget(state, envelope);
    if (active != null) {
      deferred.add(() -> active.listener.onComplete(result));
    }
  }

  private void handleFailed(
      ConnectionState state, DaemonEnvelope envelope, List<Runnable> deferred) {
    callbackTarget(state, envelope);
    String message =
        requiredSingleText(envelopeCodec.readPayload(envelope), "message", "FAILED payload");
    ActiveCapability active = takeTerminalTarget(state, envelope);
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
    ActiveCapability active = takeTerminalTarget(state, envelope);
    if (active != null) {
      deferred.add(
          () -> active.listener.onError(new EnvironmentCapabilityCancelledException(reason)));
    }
  }

  private ActiveCapability callbackTarget(ConnectionState state, DaemonEnvelope envelope) {
    requireReady(state);
    UUID invocationId = parseUuid(envelope.invocationId(), "invocationId");
    synchronized (this) {
      ActiveCapability active = activeByEnvironment.get(state.environmentName);
      if (active != null
          && active.invocationId.equals(invocationId)
          && active.connectionId.equals(state.connection.connectionId())) {
        return active;
      }
      if (state.invocationTombstones.contains(invocationId)) {
        return null;
      }
    }
    throw new DaemonProtocolException(
        "daemon callback does not own invocationId: " + envelope.invocationId());
  }

  private boolean isCurrentActive(ActiveCapability active) {
    synchronized (this) {
      return activeByEnvironment.get(active.environmentName) == active && !active.terminal;
    }
  }

  private ActiveCapability takeTerminalTarget(ConnectionState state, DaemonEnvelope envelope) {
    requireReady(state);
    UUID invocationId = parseUuid(envelope.invocationId(), "invocationId");
    synchronized (this) {
      ActiveCapability active = activeByEnvironment.get(state.environmentName);
      if (active != null
          && active.invocationId.equals(invocationId)
          && active.connectionId.equals(state.connection.connectionId())) {
        activeByEnvironment.remove(state.environmentName, active);
        active.terminal = true;
        return active;
      }
      if (state.invocationTombstones.remove(invocationId)) {
        return null;
      }
    }
    throw new DaemonProtocolException(
        "daemon terminal callback does not own invocationId: " + envelope.invocationId());
  }

  private void expireCapability(ActiveCapability active) {
    boolean sendCancel;
    synchronized (active) {
      if (active.terminal) {
        return;
      }
      active.cancelled = true;
      sendCancel = !active.cancelSent;
      active.cancelSent = true;
      active.terminal = true;
    }
    ConnectionState state;
    synchronized (this) {
      if (!activeByEnvironment.remove(active.environmentName, active)) {
        return;
      }
      state = environmentConnections.get(active.environmentName);
      if (state == null || !state.connection.connectionId().equals(active.connectionId)) {
        return;
      }
      state.invocationTombstones.remove(active.invocationId);
      state.invocationTombstones.addLast(active.invocationId);
      while (state.invocationTombstones.size() > MAX_INVOCATION_TOMBSTONES) {
        state.invocationTombstones.removeFirst();
      }
    }
    if (sendCancel) {
      send(state, DaemonMessageType.CANCEL, active.invocationId.toString(), "{}");
    }
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
          || state.environmentName == null
          || state.routeToken == null) {
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
        state.sendFailed = true;
        return SendOutcome.UNCERTAIN;
      } catch (RuntimeException error) {
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
      payload.put("code", DaemonProtocol.ERROR_CODE_ENVIRONMENT_NAME_CONFLICT);
    } else if (error instanceof DaemonRetryLaterException) {
      payload.put("code", DaemonProtocol.ERROR_CODE_RETRY_LATER);
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
    EnvironmentName environmentName;
    UUID routeToken;
    boolean closeAfterFlush;
    synchronized (state) {
      if (state.cleaned) {
        return;
      }
      state.cleaned = true;
      state.sendFailed = true;
      environmentName = state.environmentName;
      routeToken = state.routeToken;
      closeAfterFlush = state.closeAfterFlush;
    }
    synchronized (this) {
      if (environmentName != null) {
        if (routeToken != null) {
          environmentRegistry.disconnect(environmentName, routeToken, heartbeatTimeout());
        }
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
        state.invocationTombstones.clear();
      }
      connections.remove(state.connection.connectionId(), state);
    }
    try {
      if (closeAfterFlush) {
        state.connection.closeAfterFlush();
      } else {
        state.connection.close();
      }
    } catch (RuntimeException ignored) {
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
  }

  private void notifyEnvironmentReady(EnvironmentName environmentName) {
    try {
      environmentReadyListener.onEnvironmentReady(environmentName);
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

  private void verifyGatewayToken(String suppliedToken) {
    byte[] expected = properties.requireDaemonToken().getBytes(StandardCharsets.UTF_8);
    byte[] supplied = suppliedToken.getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(expected, supplied)) {
      throw new DaemonProtocolException("daemon gateway token is invalid");
    }
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

  private static final class ConnectionState {
    private final EnvironmentDaemonConnection connection;
    private volatile UUID routeToken;
    private volatile String daemonId;
    private volatile EnvironmentName environmentName;
    private volatile boolean helloReceived;
    private volatile boolean ready;
    private volatile boolean sendFailed;
    private volatile boolean cleaned;
    private boolean closeAfterFlush;
    private long outboundSequence;
    private InboundEnvelopeIdentity lastInbound;
    private final ArrayDeque<UUID> invocationTombstones = new ArrayDeque<>();

    private ConnectionState(EnvironmentDaemonConnection connection) {
      this.connection = Objects.requireNonNull(connection, "connection");
    }

    private boolean isReady() {
      return !cleaned
          && !sendFailed
          && ready
          && connection.isOpen()
          && environmentName != null
          && routeToken != null;
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
