package fun.fengwk.kkstudio.harness.environment.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
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
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocolException;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceTransferCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonWorkdirSyntax;

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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Environment daemon 的服务端会话核心：唯一拥有连接代际的握手、租约围栏、在途 invocation 与终态状态。
 *
 * <p>协议为当前 daemon wire：HELLO scope 为 null 且 payload 携带 {@code registrationToken} 与规范随机 {@code
 * daemonInstanceId}；核心通过 {@link DaemonRegistrationDirectory} 解析环境身份，通过 {@link DaemonLeaseStore} 以
 * {@code (environment_id, owner_node_id, lease_token)} 围栏原子抢占路由（已有活跃路由返回 RETRY_LATER），成功后下发
 * WELCOME。READY、HEARTBEAT 与断开连接同样以围栏推进状态； 租约存储不可用时 fail-closed。
 *
 * <p><b>实例身份与重连恢复：</b>调用只按 {@code (environmentId, invocationId)} 关联，物理连接失效不终结在途调用。同一 {@code
 * daemonInstanceId} 再次 READY 时，核心以相同 invocationId 重放每个在途 INVOKE，Daemon journal 负责重放 STARTED
 * 或终态而不重复执行。只有身份不同的新 daemon 进程接管该 Environment 时，旧的在途调用才被判定为结果不确定（exactly-once 通知）。
 *
 * <p><b>终态唯一：</b>COMPLETED/FAILED/CANCELLED 回调、连接清理与显式 {@link #expire} 竞争时只有一个赢家；已知 invocation 的迟到
 * STARTED/PROGRESS 静默丢弃，未知 invocation 的回调按协议违规关闭连接。
 *
 * <p><b>资源上传控制面：</b>调用作用域的 {@code RESOURCE_UPLOAD_REQUEST}/{@code COMMIT} 只做协议校验与状态回执，真正的上传绑定与 对象存储
 * I/O 由窄端口 {@link DaemonResourceTicketService} 承担并在核心锁之外执行。同一 transfer 的重复申请/提交是幂等的；
 * FAILED/CANCELLED、实例接管与超时会幂等释放全部传输；COMPLETED 只释放未被结果引用的上传，被引用上传交给 history 物化事务转移 owner。
 *
 * <p><b>锁边界：</b>每个连接一代的 {@code gate} 只串行化入站协议处理；{@code state} 只保护该连接的协议字段；{@code inventory} 保护环境 与
 * invocation 目录。锁顺序固定为 {@code gate > state > inventory}；租约存储访问、票据服务回调、会话监听器回调与连接关闭都在这些锁之外执行。
 *
 * <p>本类只依赖 JDK、Jackson、harness.common 与 harness.environment 契约，不含 Spring/JDBC/产品 DTO。
 */
public final class EnvironmentDaemonServer
    implements EnvironmentCapabilityTransport, DaemonEndpoint {

  private static final int MAX_INVOCATION_TOMBSTONES = 1024;

  /** 单次调用允许的并发资源传输数上限，防止恶意/失控 Daemon 无界创建上传行。 */
  private static final int MAX_TRANSFERS_PER_INVOCATION = 16;

  /** 票据服务异常时的固定失败说明：绝不复述异常原文，避免把存储内部事实泄漏到控制面。 */
  private static final String TRANSFER_FAILURE_MESSAGE = "resource upload is unavailable";

  private final DaemonLeaseStore leaseStore;
  private final DaemonRegistrationDirectory registrationDirectory;
  private final EnvironmentSessionListener sessionListener;
  private final DaemonResourceTicketService ticketService;
  private final Supplier<EnvironmentServerSettings> settings;
  private final DaemonCapabilitiesCodec capabilitiesCodec = new DaemonCapabilitiesCodec();
  private final DaemonCapabilityInvokeCodec capabilityInvokeCodec =
      new DaemonCapabilityInvokeCodec();
  private final DaemonCapabilityResultCodec resultCodec = new DaemonCapabilityResultCodec();
  private final DaemonEnvelopeCodec envelopeCodec = new DaemonEnvelopeCodec();
  private final DaemonResourceTransferCodec transferCodec = new DaemonResourceTransferCodec();

  /** 保护环境/连接目录与 invocation 目录；绝不在持有该锁时获取任何连接锁或访问外部端口。 */
  private final Object inventory = new Object();

  private final Map<String, ConnectionState> connections = new HashMap<>();
  private final Map<EnvironmentId, EnvironmentState> environments = new HashMap<>();
  private final Map<EnvironmentId, LinkedHashMap<UUID, ActiveInvocation>> invocationsByEnvironment =
      new HashMap<>();
  private final Map<EnvironmentId, ArrayDeque<UUID>> invocationTombstones = new HashMap<>();
  private final AtomicLong connectionGenerations = new AtomicLong();

  public EnvironmentDaemonServer(
      DaemonLeaseStore leaseStore,
      DaemonRegistrationDirectory registrationDirectory,
      EnvironmentSessionListener sessionListener,
      DaemonResourceTicketService ticketService,
      Supplier<EnvironmentServerSettings> settings) {
    this.leaseStore = Objects.requireNonNull(leaseStore, "leaseStore");
    this.registrationDirectory =
        Objects.requireNonNull(registrationDirectory, "registrationDirectory");
    this.sessionListener = Objects.requireNonNull(sessionListener, "sessionListener");
    this.ticketService = Objects.requireNonNull(ticketService, "ticketService");
    this.settings = Objects.requireNonNull(settings, "settings");
  }

  /** 建立一条新的连接代际；同 connectionId 的旧代际被幂等清理后由新代际取代。 */
  @Override
  public void open(DaemonChannel channel) {
    Objects.requireNonNull(channel, "channel");
    String connectionId = requireNonBlank(channel.connectionId(), "connectionId");
    ConnectionState state = new ConnectionState(channel, connectionGenerations.incrementAndGet());
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
    PreparedTransfer prepared = null;
    List<Runnable> deferred = new ArrayList<>();
    RuntimeException protocolError = null;
    EnvironmentId receivedEnvironmentId = null;
    synchronized (state.gate) {
      try {
        DaemonEnvelope envelope = envelopeCodec.decode(rawMessage);
        receivedEnvironmentId = envelope.environmentId();
        // 资源上传控制面只在此处完成协议与绑定校验；对象存储/数据库 I/O 在 gate 之外执行。
        switch (envelope.messageType()) {
          case RESOURCE_UPLOAD_REQUEST -> prepared = prepareUploadReserve(state, envelope);
          case RESOURCE_UPLOAD_COMMIT -> prepared = prepareUploadCommit(state, envelope);
          default -> handleInbound(state, envelope, deferred);
        }
      } catch (RuntimeException error) {
        protocolError = error;
      }
    }
    if (protocolError != null) {
      protocolFailure(state, protocolError, receivedEnvironmentId);
      return;
    }
    runDeferred(deferred);
    if (prepared != null) {
      executeTransfer(state, prepared);
    }
  }

  /** 幂等关闭连接代际：解绑路由、释放租约围栏并关闭传输；在途调用保留等待同实例重连。 */
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
      state = connectionOf(environmentId);
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
      state = connectionOf(environmentId);
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
      for (Map.Entry<EnvironmentId, EnvironmentState> entry : environments.entrySet()) {
        ConnectionState state = entry.getValue().connection;
        if (state != null && state.isReady()) {
          ready.add(entry.getKey());
        }
      }
    }
    return Set.copyOf(ready);
  }

  @Override
  public EnvironmentCapabilityExecutionHandle invoke(
      EnvironmentId environmentId,
      EnvironmentCapabilityExecutionRequest request,
      EnvironmentCapabilityExecutionListener listener) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");
    EnvironmentCapabilityDescriptor descriptor = request.descriptor();
    EnvironmentCapabilityDescriptor catalogDescriptor =
        EnvironmentCapabilityCatalog.require(descriptor.id());
    if (!catalogDescriptor.equals(descriptor)) {
      throw new IllegalArgumentException(
          "capability descriptor does not match EnvironmentCapabilityCatalog: " + descriptor.id());
    }
    UUID invocationId = parseUuid(request.call().id(), "call.id");

    ConnectionState state;
    UUID leaseToken;
    synchronized (inventory) {
      state = connectionOf(environmentId);
      leaseToken = state == null ? null : state.leaseToken;
    }
    if (state == null || leaseToken == null || !state.isReady()) {
      throw unavailable(environmentId, descriptor.id().value());
    }
    validateWorkdirShape(state, descriptor, request.call());

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
                request.call().argumentsJson(),
                request.timeout()));
    ActiveInvocation active =
        new ActiveInvocation(environmentId, invocationId, listener, invokePayload);
    DaemonOfferResult outcome = null;
    RuntimeException sendFailure = null;
    synchronized (state) {
      if (!state.isReady() || !leaseToken.equals(state.leaseToken)) {
        throw unavailable(environmentId, descriptor.id().value());
      }
      register(active);
      try {
        outcome = state.offer(DaemonMessageType.INVOKE, invocationId.toString(), invokePayload);
        if (outcome == DaemonOfferResult.ACCEPTED) {
          active.sentGeneration = state.generation;
        }
      } catch (RuntimeException error) {
        sendFailure = error;
      }
    }
    if (sendFailure != null) {
      // 传输在递交过程中失败：连接失效，调用保持活动并由同实例重连以同一 invocationId 重放。
      close(state.connection.connectionId());
      return active;
    }
    if (outcome == DaemonOfferResult.ACCEPTED) {
      return active;
    }
    unregister(active);
    active.markTerminal();
    if (outcome == DaemonOfferResult.BUSY) {
      // 本地队列容量/字节预算拒绝：帧肯定未发送，连接仍然可用，调用方可以安全重试。
      throw new EnvironmentCapabilityBusyException(
          "daemon outbound queue is full for environment " + environmentId);
    }
    // 连接已关闭：帧肯定未发送，调用肯定未执行。
    throw unavailable(environmentId, descriptor.id().value());
  }

  /**
   * 终结一次由调用方判定超时或放弃的调用：移出活动目录、登记 tombstone 并至多发送一次 CANCEL。
   *
   * <p>该方法不向 listener 发送终态；超时判定由调用方在自身 deadline 上完成。调用被放弃后不再随重连重放，因此即使 CANCEL 未能 送达也不会重复执行。
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
      addTombstone(active.environmentId, active.invocationId);
      state = connectionOf(active.environmentId);
    }
    if (shouldCancel && state != null) {
      offer(state, DaemonMessageType.CANCEL, active.invocationId.toString(), "{}");
    }
    releaseAllTransfers(active);
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

  private List<ActiveInvocation> activeInvocations(EnvironmentId environmentId) {
    synchronized (inventory) {
      LinkedHashMap<UUID, ActiveInvocation> invocations =
          invocationsByEnvironment.get(environmentId);
      return invocations == null ? List.of() : List.copyOf(invocations.values());
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
      case HELLO -> handleHello(state, envelope, deferred);
      case READY -> handleReady(state, envelope, deferred);
      case HEARTBEAT -> handleHeartbeat(state, envelope);
      case STARTED -> handleStarted(state, envelope);
      case PROGRESS -> handleProgress(state, envelope, deferred);
      case COMPLETED -> handleCompleted(state, envelope, deferred);
      case FAILED -> handleFailed(state, envelope, deferred);
      case CANCELLED -> handleCancelled(state, envelope, deferred);
      case ERROR -> handleError(state, envelope);
      case RESOURCE_UPLOAD_REQUEST, RESOURCE_UPLOAD_COMMIT -> throw new DaemonProtocolException(
          "resource upload control is handled by the connection gate");
      case WELCOME, INVOKE, CANCEL, RESOURCE_UPLOAD_TICKET -> throw new DaemonProtocolException(
          "daemon must not send " + envelope.messageType() + " to server");
    }
  }

  /** 处理 daemon 首帧 HELLO：解析实例身份与注册凭据、抢占路由租约并完成 WELCOME 回包。 */
  private void handleHello(
      ConnectionState state, DaemonEnvelope envelope, List<Runnable> deferred) {
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
        Set.of(
            "protocolVersion", "registrationToken", "capabilityCatalogVersion", "daemonInstanceId"),
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
    String daemonInstanceId =
        parseUuid(requiredText(payload, "daemonInstanceId", "HELLO payload"), "daemonInstanceId")
            .toString();
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
      // 身份不同的新进程接管：旧进程已不可能再给出该 Environment 的终态，其全部在途调用按结果不确定终结恰好一次。
      List<ActiveInvocation> abandoned =
          replaceDaemonInstance(environmentId, daemonInstanceId)
              ? takeAbandonedInvocations(environmentId)
              : List.of();
      state.bind(environmentId, acquired.leaseToken());
      synchronized (inventory) {
        environmentOf(environmentId).connection = state;
      }
      for (ActiveInvocation active : abandoned) {
        deferred.add(
            () ->
                active.listener.onError(
                    new EnvironmentCapabilitySendUncertainException(
                        "Environment daemon instance changed for "
                            + environmentId
                            + "; capability invocation outcome is uncertain.")));
        // 换进程接管后旧进程不可能再给出这些调用的终态：其名下上传按已绑定 uploadId 清理，绝不留给新进程消费。
        deferred.add(() -> releaseAllTransfers(active));
      }
      ObjectNode welcomePayload = envelopeCodec.createPayload();
      welcomePayload.put("environmentId", environmentId.toString());
      welcomePayload.put("name", registration.displayName());
      welcomePayload.put("maxResourceBytes", settings().maxResourceBytes());
      offer(state, DaemonMessageType.WELCOME, null, welcomePayload.toString());
    }
  }

  /**
   * 记录新的 daemon 实例身份。
   *
   * @return 该 Environment 此前已有不同实例身份（新进程接管）时返回 true
   */
  private boolean replaceDaemonInstance(EnvironmentId environmentId, String daemonInstanceId) {
    synchronized (inventory) {
      EnvironmentState environment = environmentOf(environmentId);
      String previous = environment.daemonInstanceId;
      environment.daemonInstanceId = daemonInstanceId;
      return previous != null && !previous.equals(daemonInstanceId);
    }
  }

  /** 取走该 Environment 的全部在途调用并登记 tombstone：新进程无法再提供它们的终态，其迟到帧一律忽略。 */
  private List<ActiveInvocation> takeAbandonedInvocations(EnvironmentId environmentId) {
    List<ActiveInvocation> abandoned;
    synchronized (inventory) {
      LinkedHashMap<UUID, ActiveInvocation> invocations =
          invocationsByEnvironment.remove(environmentId);
      abandoned = invocations == null ? List.of() : List.copyOf(invocations.values());
    }
    for (ActiveInvocation active : abandoned) {
      active.markTerminal();
      addTombstone(environmentId, active.invocationId);
    }
    return abandoned;
  }

  /** 幂等释放该调用名下的全部传输绑定：未消费的上传按已绑定 uploadId 请求全局清理。 */
  private void releaseAllTransfers(ActiveInvocation active) {
    List<TransferBinding> bindings;
    synchronized (active) {
      if (active.transfers.isEmpty()) {
        return;
      }
      bindings = List.copyOf(active.transfers.values());
      active.transfers.clear();
    }
    for (TransferBinding binding : bindings) {
      releaseBinding(active, binding);
    }
  }

  /**
   * 幂等释放单个绑定：只释放已绑定到该传输的 uploadId（从未申请到 upload 的传输无需释放）。
   *
   * <p>释放前先把绑定标记为已释放，使并发在途的票据 I/O 结果不再回执，也不重复释放同一个 uploadId。
   */
  private void releaseBinding(ActiveInvocation active, TransferBinding binding) {
    UUID uploadId;
    synchronized (binding) {
      if (binding.released) {
        return;
      }
      binding.released = true;
      uploadId = binding.currentUploadId;
    }
    if (uploadId == null) {
      return;
    }
    try {
      ticketService.release(active.environmentId, active.invocationId.toString(), uploadId);
    } catch (RuntimeException ignored) {
      // 释放失败不影响会话核心状态；上传行本身仍受全局过期回收保护。
    }
  }

  /** 在 I/O 完成后发现调用已终结/被接管时，立即释放本次产生的 uploadId 并标记绑定已释放。 */
  private void markReleasedAndRelease(
      ActiveInvocation active, TransferBinding binding, UUID uploadId) {
    synchronized (binding) {
      if (binding.released) {
        return;
      }
      binding.released = true;
    }
    try {
      ticketService.release(active.environmentId, active.invocationId.toString(), uploadId);
    } catch (RuntimeException ignored) {
      // 释放失败不影响会话核心状态；上传行本身仍受全局过期回收保护。
    }
  }

  private ConnectionState otherConnectionOf(ConnectionState self, EnvironmentId environmentId) {
    synchronized (inventory) {
      ConnectionState existing = connectionOf(environmentId);
      return existing == self ? null : existing;
    }
  }

  /** 处理 daemon READY 声明：围栏式登记 READY 能力、唤醒会话监听器并重放同实例的在途调用。 */
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
      state.daemonOperatingSystem = capabilities.environment().operatingSystem();
    }
    deferred.add(() -> notifyEnvironmentReady(environmentId));
    resendActiveInvocations(state, environmentId);
  }

  /**
   * 以相同 invocationId 重放尚未在当前连接代际发出的在途 INVOKE。
   *
   * <p>Daemon 的 invocation journal 以 invocationId 去重：RUNNING 重放 STARTED，已终结重放终态，因此重放不会重复执行副作用。
   * 本地队列拒绝不改变在途调用的存在，仅留待下一次 READY 或由调用方 deadline 收敛。
   */
  private void resendActiveInvocations(ConnectionState state, EnvironmentId environmentId) {
    for (ActiveInvocation active : activeInvocations(environmentId)) {
      if (active.sentGeneration == state.generation || active.isTerminal()) {
        continue;
      }
      DaemonOfferResult outcome;
      synchronized (state) {
        if (!state.isReady() || active.sentGeneration == state.generation) {
          continue;
        }
        try {
          outcome =
              state.offer(
                  DaemonMessageType.INVOKE, active.invocationId.toString(), active.invokePayload);
        } catch (RuntimeException transportFailure) {
          outcome = null;
        }
        if (outcome == DaemonOfferResult.ACCEPTED) {
          active.sentGeneration = state.generation;
        }
      }
      if (outcome == null) {
        // 传输在递交过程中失败：连接已失效。关闭包含租约存储访问，必须在连接状态锁之外执行。
        close(state.connection.connectionId());
        return;
      }
      if (outcome == DaemonOfferResult.CLOSED) {
        return;
      }
    }
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

  private void handleError(ConnectionState state, DaemonEnvelope envelope) {
    requireHello(state);
    requireNoInvocationId(envelope);
    requiredSingleText(envelopeCodec.readPayload(envelope), "message", "ERROR payload");
  }

  /**
   * 在 {@code gate} 内完成资源上传申请的协议与绑定校验。
   *
   * <p>只做无副作用判定：调用必须仍属于本连接、transfer 数量未超上限、同一 transfer 的重复申请必须完全一致。任何不满足都以协议错误 关闭连接（恶意/失控 Daemon
   * 不能靠乱发控制帧影响其它调用）。
   */
  private PreparedTransfer prepareUploadReserve(ConnectionState state, DaemonEnvelope envelope) {
    ActiveInvocation active = transferTarget(state, envelope);
    DaemonResourceTransferCodec.UploadRequest request =
        transferCodec.decodeRequest(envelope.payloadJson());
    UUID transferId = request.transferId();
    if (request.size() > settings().maxResourceBytes()) {
      // 超出本节点资源预算的申请在任何存储副作用之前被拒绝；恶意/失控 Daemon 不能靠超大声明创建上传行。
      throw new DaemonProtocolException(
          "resource transfer size exceeds the environment limit: " + transferId);
    }
    DaemonResourceTicketService.TransferRequest serviceRequest =
        new DaemonResourceTicketService.TransferRequest(
            transferId, request.mediaType(), request.name(), request.size(), request.sha256());
    synchronized (active) {
      TransferBinding existing = active.transfers.get(transferId);
      if (existing != null) {
        if (!existing.request.equals(serviceRequest)) {
          throw new DaemonProtocolException(
              "resource transfer is already bound to a different request: " + transferId);
        }
        // 同一 transfer 的重复申请（含同实例重连后的重发）沿用既有绑定，绝不重复创建传输或上传行。
        return new PreparedTransfer(state, active, transferId, existing.request, null);
      }
      if (active.transfers.size() >= MAX_TRANSFERS_PER_INVOCATION) {
        throw new DaemonProtocolException(
            "invocation "
                + active.invocationId
                + " exceeds "
                + MAX_TRANSFERS_PER_INVOCATION
                + " concurrent resource transfers");
      }
      active.transfers.put(transferId, new TransferBinding(serviceRequest));
    }
    return new PreparedTransfer(state, active, transferId, serviceRequest, null);
  }

  /** 在 {@code gate} 内完成资源上传提交的协议与绑定校验；提交必须指向本调用已申请且 uploadId 一致的 transfer。 */
  private PreparedTransfer prepareUploadCommit(ConnectionState state, DaemonEnvelope envelope) {
    ActiveInvocation active = transferTarget(state, envelope);
    DaemonResourceTransferCodec.UploadCommit commit =
        transferCodec.decodeCommit(envelope.payloadJson());
    UUID transferId = commit.transferId();
    synchronized (active) {
      TransferBinding existing = active.transfers.get(transferId);
      if (existing == null) {
        throw new DaemonProtocolException(
            "resource upload commit references an unrequested transfer: " + transferId);
      }
      synchronized (existing) {
        if (existing.released) {
          throw new DaemonProtocolException(
              "resource upload commit references a released transfer: " + transferId);
        }
        UUID owned = existing.currentUploadId;
        // 提交只能指向本 transfer 已由服务端签发的上传：从未签发（伪造/抢先提交）或指向其它上传都是协议违规。
        if (owned == null || !owned.equals(commit.uploadId())) {
          throw new DaemonProtocolException(
              "resource upload commit uploadId does not match the issued ticket: " + transferId);
        }
      }
    }
    return new PreparedTransfer(state, active, transferId, null, commit.uploadId());
  }

  /** 上传控制面只允许指向本连接的、仍然活动的 invocation。 */
  private ActiveInvocation transferTarget(ConnectionState state, DaemonEnvelope envelope) {
    requireReady(state);
    UUID invocationId = parseUuid(envelope.invocationId(), "invocationId");
    ActiveInvocation active = registeredInvocation(state.environmentId, invocationId);
    if (active == null) {
      throw new DaemonProtocolException(
          "resource upload control does not own invocationId: " + envelope.invocationId());
    }
    return active;
  }

  /**
   * 在核心锁之外执行票据服务 I/O，并按票据状态回执。
   *
   * <p>同一 transfer 的 I/O 由该绑定自身的锁串行化：同一申请/提交（含瞬时 FAILED 后的重试、同实例重连后的重发）永不产生第二个上传行，竞态下 也不会出现重复
   * reserve。I/O 完成后重新判定调用是否仍然活动；若已完成终态或已被接管，则把本次得到的 uploadId 立即释放，绝不泄漏上传行。
   */
  private void executeTransfer(ConnectionState state, PreparedTransfer prepared) {
    ActiveInvocation active = prepared.active();
    if (!isActive(active)) {
      return;
    }
    UUID transferId = prepared.transferId();
    TransferBinding binding;
    synchronized (active) {
      binding = active.transfers.get(transferId);
      if (binding == null) {
        return;
      }
    }
    boolean commit = prepared.commitUploadId() != null;
    DaemonResourceTicketService.Ticket ticket;
    UUID producedUploadId = null;
    boolean replay;
    synchronized (binding) {
      if (binding.released) {
        return;
      }
      ticket = commit ? binding.commitTicket() : binding.reserveTicket();
      replay = ticket != null;
      if (!replay) {
        try {
          ticket =
              commit
                  ? ticketService.commit(
                      active.environmentId,
                      active.invocationId.toString(),
                      prepared.commitUploadId())
                  : ticketService.reserve(
                      active.environmentId, active.invocationId.toString(), binding.request);
        } catch (RuntimeException error) {
          // 绝不把异常原文回执给 Daemon：票据服务的异常可能携带存储细节，控制面只报告固定的有界说明。
          ticket = new DaemonResourceTicketService.Ticket.Failed(TRANSFER_FAILURE_MESSAGE);
        }
        producedUploadId = ticket.uploadId();
        if (producedUploadId != null) {
          binding.currentUploadId = producedUploadId;
        }
        // FAILED 多为可重试的瞬时故障，因此不缓存：同一 transfer 的下一次重试必须再次触达票据服务。
        if (ticket instanceof DaemonResourceTicketService.Ticket.Failed) {
          binding.recordFailure(commit);
        } else if (commit) {
          binding.commitTicket = ticket;
        } else {
          binding.reserveTicket = ticket;
        }
      }
    }
    if (producedUploadId != null && !isActive(active)) {
      // 调用在 I/O 期间终结或被接管：本次上传没有任何消费者，立即释放以免泄漏上传行。
      markReleasedAndRelease(active, binding, producedUploadId);
      return;
    }
    DaemonResourceTransferCodec.UploadTicket wire = toWireTicket(transferId, ticket);
    // 票据只在调用仍然活动时回执；终态之后到达的票据不再发送。
    if (isActive(active)) {
      pushUploadTicket(state, active, wire);
    }
  }

  private static DaemonResourceTransferCodec.UploadTicket toWireTicket(
      UUID transferId, DaemonResourceTicketService.Ticket ticket) {
    return switch (ticket) {
      case DaemonResourceTicketService.Ticket.Pending pending -> DaemonResourceTransferCodec
          .UploadTicket.pending(transferId, pending.uploadId(), pending.presignedPut());
      case DaemonResourceTicketService.Ticket.Ready ready -> DaemonResourceTransferCodec
          .UploadTicket.ready(transferId, ready.uploadId());
      case DaemonResourceTicketService.Ticket.Failed failed -> DaemonResourceTransferCodec
          .UploadTicket.failed(transferId, failed.message());
    };
  }

  /** 递交一条票据帧：envelope 的 invocationId 是被调用的活动调用，transferId 只存在于 payload 中。 */
  private void pushUploadTicket(
      ConnectionState state,
      ActiveInvocation active,
      DaemonResourceTransferCodec.UploadTicket ticket) {
    offer(
        state,
        DaemonMessageType.RESOURCE_UPLOAD_TICKET,
        active.invocationId.toString(),
        transferCodec.encodeTicket(ticket));
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

  private void handleProgress(
      ConnectionState state, DaemonEnvelope envelope, List<Runnable> deferred) {
    ActiveInvocation active = callbackTarget(state, envelope);
    EnvironmentCapabilityResult result =
        resultCodec.decodeProgressForInvocation(
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
    // 终态前先完成上传事实核对：未就绪、伪造、重复或与申请不符的上传一律是协议违规，绝不进入消费方。
    Set<UUID> referenced = requireCompletedUploads(state, envelope, result);
    ActiveInvocation active = takeTerminalTarget(state, envelope);
    if (active != null) {
      deferred.add(() -> active.listener.onComplete(result));
      // 结果引用的上传必须保留给消费方；其余未引用传输在终态后不再有消费者。
      deferred.add(() -> releaseUnreferencedTransfers(active, referenced));
    }
  }

  /**
   * 核对终态结果声明的每一个上传：必须属于本调用、已就绪、且与申请事实逐字段一致。
   *
   * <p>任何不满足都是协议违规（关闭连接）：否则消费方会拿到无法转移的引用，或把别的调用的内容当成本次结果。
   *
   * @return 结果实际引用的全局上传 id 集合
   */
  private Set<UUID> requireCompletedUploads(
      ConnectionState state, DaemonEnvelope envelope, EnvironmentCapabilityResult result) {
    Set<UUID> referenced = new HashSet<>();
    List<UUID> declared = new ArrayList<>();
    for (ResultContent content : result.contents()) {
      if (content instanceof ResourceResultContent resource) {
        UUID uploadId = resource.resource().blobUploadId();
        if (uploadId == null) {
          throw new DaemonProtocolException(
              "resource content must reference a global upload in a terminal result");
        }
        if (!referenced.add(uploadId)) {
          throw new DaemonProtocolException(
              "terminal result declares the same upload twice: " + uploadId);
        }
        declared.add(uploadId);
      }
    }
    if (declared.isEmpty()) {
      return referenced;
    }
    ActiveInvocation active =
        registeredInvocation(
            state.environmentId, parseUuid(envelope.invocationId(), "invocationId"));
    if (active == null) {
      // 已终结调用的终态重放：上传早已转移或释放，重放不再触发任何消费。
      return referenced;
    }
    List<TransferBinding> bindings;
    synchronized (active) {
      bindings = List.copyOf(active.transfers.values());
    }
    Map<UUID, TransferBinding> byUploadId = new HashMap<>();
    for (TransferBinding binding : bindings) {
      synchronized (binding) {
        if (binding.currentUploadId != null && !binding.released) {
          byUploadId.put(binding.currentUploadId, binding);
        }
      }
    }
    Set<UUID> consumed = new HashSet<>();
    for (ResultContent content : result.contents()) {
      if (!(content instanceof ResourceResultContent resource)) {
        continue;
      }
      ResourceRef ref = resource.resource();
      UUID uploadId = ref.blobUploadId();
      if (!consumed.add(uploadId)) {
        continue;
      }
      TransferBinding binding = byUploadId.get(uploadId);
      if (binding == null) {
        throw new DaemonProtocolException(
            "terminal result references an upload this invocation does not own: " + uploadId);
      }
      binding.requireReadyAndMatching(ref);
    }
    return referenced;
  }

  /** 释放终态结果未引用的传输绑定：被引用的上传由消费方（history 物化）负责原子转移。 */
  private void releaseUnreferencedTransfers(ActiveInvocation active, Set<UUID> referenced) {
    List<TransferBinding> abandoned = new ArrayList<>();
    synchronized (active) {
      for (TransferBinding binding : active.transfers.values()) {
        if (binding.currentUploadId == null || !referenced.contains(binding.currentUploadId)) {
          abandoned.add(binding);
        }
      }
      abandoned.forEach(binding -> active.transfers.values().remove(binding));
    }
    for (TransferBinding binding : abandoned) {
      releaseBinding(active, binding);
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
      // 失败终态不携带任何上传引用，未消费的传输在此之前已无消费者。
      deferred.add(() -> releaseAllTransfers(active));
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
      deferred.add(() -> releaseAllTransfers(active));
    }
  }

  /** 返回匹配 invocationId 的活动调用；已终结调用返回 null（迟到帧静默忽略），其余为协议错误。 */
  private ActiveInvocation callbackTarget(ConnectionState state, DaemonEnvelope envelope) {
    requireReady(state);
    UUID invocationId = parseUuid(envelope.invocationId(), "invocationId");
    ActiveInvocation active = registeredInvocation(state.environmentId, invocationId);
    if (active != null) {
      return active;
    }
    if (hasTombstone(state.environmentId, invocationId)) {
      return null;
    }
    throw new DaemonProtocolException(
        "daemon callback does not own invocationId: " + envelope.invocationId());
  }

  private boolean isActive(ActiveInvocation active) {
    if (active.isTerminal()) {
      return false;
    }
    return registeredInvocation(active.environmentId, active.invocationId) == active;
  }

  /**
   * 原子终结并移除匹配 invocationId 的活动调用，并登记 tombstone。
   *
   * <p>tombstone 让同一 invocation 的迟到/重放终态被静默忽略：Daemon 的 journal 会在重连后重放终态，重放不是越权回调，也不得触发协议错误。
   */
  private ActiveInvocation takeTerminalTarget(ConnectionState state, DaemonEnvelope envelope) {
    requireReady(state);
    UUID invocationId = parseUuid(envelope.invocationId(), "invocationId");
    ActiveInvocation active = registeredInvocation(state.environmentId, invocationId);
    if (active != null) {
      unregister(active);
      active.markTerminal();
      addTombstone(state.environmentId, invocationId);
      return active;
    }
    if (hasTombstone(state.environmentId, invocationId)) {
      return null;
    }
    throw new DaemonProtocolException(
        "daemon terminal callback does not own invocationId: " + envelope.invocationId());
  }

  /** 递交一帧；传输同步失败意味着连接已失效：断开该连接并返回 false。 */
  private boolean offer(
      ConnectionState state, DaemonMessageType type, String invocationId, String payloadJson) {
    try {
      return state.offer(type, invocationId, payloadJson) == DaemonOfferResult.ACCEPTED;
    } catch (RuntimeException error) {
      close(state.connection.connectionId());
      return false;
    }
  }

  /**
   * 发送前按该连接 READY 中冻结的目标 Daemon OS 校验 arguments.workdir 的词法形状。
   *
   * <p>只做纯文本校验：不使用 Backend 本机 {@code Path} 解析远端路径，也不做 home/环境变量展开。真实存在性、目录类型与可访问性由 Daemon 用 自己的
   * {@code Path} 判定。
   */
  private static void validateWorkdirShape(
      ConnectionState state,
      EnvironmentCapabilityDescriptor descriptor,
      EnvironmentCapabilityCall call) {
    if (!EnvironmentCapabilityCatalog.requiresWorkdir(descriptor.id())) {
      return;
    }
    JsonNode arguments = JsonValues.readTree(call.argumentsJson());
    JsonNode workdir = arguments.get("workdir");
    DaemonOperatingSystem operatingSystem = state.readyDaemonOperatingSystem();
    if (operatingSystem == null) {
      throw new EnvironmentCapabilityUnavailableException(
          "target daemon operating system is not known; cannot validate workdir for "
              + descriptor.id().value());
    }
    try {
      DaemonWorkdirSyntax.requireAbsolute(
          workdir == null || !workdir.isTextual() ? null : workdir.textValue(), operatingSystem);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          "invalid workdir for capability " + descriptor.id().value() + ": " + error.getMessage(),
          error);
    }
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

  /** 幂等清理连接代际：解绑路由租约并关闭传输；在途调用保留等待同实例重连重放。 */
  private void closeConnectionState(ConnectionState state) {
    EnvironmentId environmentId;
    UUID leaseToken;
    boolean closeAfterFlush;
    synchronized (state) {
      if (state.cleaned) {
        return;
      }
      state.cleaned = true;
      environmentId = state.environmentId;
      leaseToken = state.leaseToken;
      closeAfterFlush = state.closeAfterFlush;
    }
    synchronized (inventory) {
      if (environmentId != null) {
        EnvironmentState environment = environments.get(environmentId);
        if (environment != null && environment.connection == state) {
          environment.connection = null;
        }
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

  /** 调用方必须持有 {@code inventory}；不存在时创建空状态。 */
  private EnvironmentState environmentOf(EnvironmentId environmentId) {
    return environments.computeIfAbsent(environmentId, ignored -> new EnvironmentState());
  }

  /** 调用方必须持有 {@code inventory}。 */
  private ConnectionState connectionOf(EnvironmentId environmentId) {
    EnvironmentState environment = environments.get(environmentId);
    return environment == null ? null : environment.connection;
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

  /** 协议错误说明：只描述 Daemon 自身的协议违规，绝不触及存储或票据服务内部事实。 */
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
   * <p>{@link #cancel()} 只表达取消请求，不终结本地所有权；终态仍由 daemon 回调、实例接管或调用方 deadline 给出。
   */
  private final class ActiveInvocation implements EnvironmentCapabilityExecutionHandle {

    private final EnvironmentId environmentId;
    private final UUID invocationId;
    private final EnvironmentCapabilityExecutionListener listener;
    private final String invokePayload;
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final AtomicBoolean cancelSent = new AtomicBoolean();
    private volatile boolean cancelled;

    /**
     * 最近一次成功递交 INVOKE 的连接代际；0 表示尚未递交。重连后与当前代际不同即触发重放，因此该字段的良性竞争最多导致一次冗余重放 （Daemon journal 以
     * invocationId 去重）。
     */
    private volatile long sentGeneration;

    /**
     * 本次调用名下的资源传输绑定（{@code transferId -> binding}），由 {@code this} 保护。
     *
     * <p>绑定只存在于进程内存：对象存储的权威事实始终是全局上传行，因此进程崩溃只会留下受全局过期回收保护的上传行，不会产生悬空引用。
     */
    private final Map<UUID, TransferBinding> transfers = new LinkedHashMap<>();

    private ActiveInvocation(
        EnvironmentId environmentId,
        UUID invocationId,
        EnvironmentCapabilityExecutionListener listener,
        String invokePayload) {
      this.environmentId = environmentId;
      this.invocationId = invocationId;
      this.listener = listener;
      this.invokePayload = invokePayload;
    }

    @Override
    public void cancel() {
      synchronized (this) {
        cancelled = true;
        if (terminal.get() || cancelSent.getAndSet(true)) {
          return;
        }
      }
      ConnectionState state;
      synchronized (inventory) {
        state = connectionOf(environmentId);
      }
      if (state != null) {
        offer(state, DaemonMessageType.CANCEL, invocationId.toString(), "{}");
      }
    }

    @Override
    public boolean isCancelled() {
      return cancelled;
    }

    private boolean isTerminal() {
      return terminal.get();
    }

    private void markTerminal() {
      terminal.set(true);
    }
  }

  /** 单个连接代际的协议状态；{@code generation} 用于识别需要重放 INVOKE 的新连接。 */
  private final class ConnectionState {

    /** 只串行化该连接的入站协议处理；不与其它锁嵌套反向获取。 */
    private final Object gate = new Object();

    private final DaemonChannel connection;
    private final long generation;
    private volatile UUID leaseToken;
    private volatile EnvironmentId environmentId;
    private volatile boolean helloReceived;
    private volatile DaemonOperatingSystem daemonOperatingSystem;
    private volatile boolean ready;
    private volatile boolean cleaned;
    private boolean closeAfterFlush;

    private ConnectionState(DaemonChannel connection, long generation) {
      this.connection = Objects.requireNonNull(connection, "connection");
      this.generation = generation;
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

    /** READY 中冻结的目标 Daemon OS；READY 之前为 null。 */
    private DaemonOperatingSystem readyDaemonOperatingSystem() {
      return isReady() ? daemonOperatingSystem : null;
    }

    private boolean isReady() {
      return !cleaned
          && ready
          && connection.isOpen()
          && environmentId != null
          && leaseToken != null;
    }

    private synchronized DaemonOfferResult offer(
        DaemonMessageType type, String invocationId, String payloadJson) {
      if (cleaned || environmentId == null || leaseToken == null) {
        return DaemonOfferResult.CLOSED;
      }
      String text = encode(type, environmentId, invocationId, payloadJson);
      return connection.offerText(text);
    }

    /** 协议错误帧：未绑定环境时回退到入站声明的 environmentId，入队成功后要求 flush 后关闭。 */
    private synchronized void enqueueError(
        EnvironmentId receivedEnvironmentId, String payloadJson) {
      if (cleaned || !connection.isOpen()) {
        return;
      }
      EnvironmentId scope = environmentId != null ? environmentId : receivedEnvironmentId;
      String text = encode(DaemonMessageType.ERROR, scope, null, payloadJson);
      try {
        if (connection.offerText(text) == DaemonOfferResult.ACCEPTED) {
          closeAfterFlush = true;
        }
      } catch (RuntimeException ignored) {
        // 传输不可用；连接关闭由 closeConnectionState 完成。
      }
    }

    private String encode(
        DaemonMessageType type, EnvironmentId scope, String invocationId, String payloadJson) {
      return envelopeCodec.encode(
          new DaemonEnvelope(DaemonProtocol.VERSION, type, scope, invocationId, payloadJson));
    }
  }

  /** Environment 级事实：当前 READY 连接与最近一次 accepted 的 daemon 进程身份；字段由 {@code inventory} 保护。 */
  private static final class EnvironmentState {
    private String daemonInstanceId;
    private ConnectionState connection;
  }

  /**
   * 一次资源传输的进程内绑定：申请事实恒定；申请票据、提交票据与已消耗的 uploadId 在首次成功后固化。
   *
   * <p>字段由该绑定自身的监视器保护，因此同一 transfer 的票据 I/O 串行进行：重复申请/提交（含瞬时 FAILED 后的重试与同进程重连重发）永不产生第二个上传行。
   * 绑定只在进程内存中存在，权威内容事实始终是全局上传行。
   */
  private static final class TransferBinding {

    private final DaemonResourceTicketService.TransferRequest request;

    /** 本次传输当前拥有的全局上传 id；申请或提交成功后固化，释放后不再回执。 */
    private UUID currentUploadId;

    /** 申请票据；只在非 FAILED 时缓存，FAILED 必须允许后续重试再次触达票据服务。 */
    private DaemonResourceTicketService.Ticket reserveTicket;

    /** 提交票据；只在非 FAILED 时缓存。 */
    private DaemonResourceTicketService.Ticket commitTicket;

    /** 已释放标记：置位后不再回执、不再重复释放。 */
    private boolean released;

    private TransferBinding(DaemonResourceTicketService.TransferRequest request) {
      this.request = Objects.requireNonNull(request, "request");
    }

    private synchronized DaemonResourceTicketService.Ticket reserveTicket() {
      return reserveTicket;
    }

    private synchronized DaemonResourceTicketService.Ticket commitTicket() {
      return commitTicket;
    }

    /** 瞬时失败不缓存：同一 transfer 的下一次重试必须再次触达票据服务，而不是重放失败结论。 */
    private synchronized void recordFailure(boolean commit) {
      if (commit) {
        this.commitTicket = null;
      } else {
        this.reserveTicket = null;
      }
    }

    /**
     * 核对终态结果对该上传的引用与申请事实逐字段一致，且内容已就绪。
     *
     * <p>「已就绪」= 申请去重命中或提交已成功；PENDING/FAILED 说明内容根本不可消费，属于终态早于就绪的协议违规。
     */
    private synchronized void requireReadyAndMatching(ResourceRef ref) {
      if (released) {
        throw new DaemonProtocolException(
            "terminal result references an upload this invocation already released");
      }
      boolean ready =
          (reserveTicket instanceof DaemonResourceTicketService.Ticket.Ready)
              || (commitTicket instanceof DaemonResourceTicketService.Ticket.Ready);
      if (!ready) {
        throw new DaemonProtocolException(
            "terminal result references an upload that is not ready: " + currentUploadId);
      }
      if (!Objects.equals(request.mediaType(), ref.mediaType())
          || !Objects.equals(request.name(), ref.name())
          || ref.size() == null
          || ref.size() != request.size()
          || !Objects.equals(request.sha256(), ref.sha256())) {
        throw new DaemonProtocolException(
            "terminal result upload metadata does not match its request: " + currentUploadId);
      }
    }
  }

  /**
   * 已在 {@code gate} 内完成协议与绑定校验、待执行的服务端票据动作。
   *
   * <p>{@code commitUploadId} 非空表示提交；为空表示申请。绑定已存在时沿用其既有事实，因此同一 transfer 的重复帧不会再次创建传输。
   */
  private record PreparedTransfer(
      ConnectionState state,
      ActiveInvocation active,
      UUID transferId,
      DaemonResourceTicketService.TransferRequest request,
      UUID commitUploadId) {

    private PreparedTransfer {
      if ((request == null) == (commitUploadId == null)) {
        throw new IllegalArgumentException("exactly one of request or commitUploadId must be set");
      }
    }
  }
}
