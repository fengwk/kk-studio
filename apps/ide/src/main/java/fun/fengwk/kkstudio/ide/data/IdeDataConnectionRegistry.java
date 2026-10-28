package fun.fengwk.kkstudio.ide.data;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonDataBind;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.harness.environment.server.OneTimeTicketStore;
import fun.fengwk.kkstudio.ide.mux.MuxConnection;
import fun.fengwk.kkstudio.ide.mux.MuxConnectionClosedException;
import fun.fengwk.kkstudio.ide.mux.MuxConnectionListener;
import fun.fengwk.kkstudio.ide.mux.MuxHeader;
import fun.fengwk.kkstudio.ide.mux.MuxOpen;
import fun.fengwk.kkstudio.ide.mux.MuxStream;
import fun.fengwk.kkstudio.ide.mux.MuxStreamClosedException;
import fun.fengwk.kkstudio.ide.mux.MuxStreamListener;
import fun.fengwk.kkstudio.ide.mux.MuxTransport;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 每个 Environment 当前 READY 控制身份下的唯一复用数据连接。
 *
 * <p><b>绑定：</b>首次有逻辑流需求时，注册表在已验证 owner lease 的当前 READY 连接上铸造一次性 256-bit ticket（30 秒有效），并用 {@link
 * EnvironmentDaemonServer#sendDataBind(EnvironmentId, DaemonDataBind)} 下发新数据代际。ticket 只绑定
 * Environment、Daemon 实例控制身份和数据代际；同一 Environment 不并发下发第二个绑定。Daemon 回连后由 {@link #accept}
 * 一次性消费，立即建立当前代际的 {@link MuxConnection}。
 *
 * <p><b>代际围栏：</b>接受、每次逻辑流准入以及周期性租约检查都重新核对当前 READY {@link DaemonConnectionIdentity} 与 {@link
 * EnvironmentDaemonServer#holdsReadyLease(EnvironmentId)}。另一个实例接管、控制断开或租约失效会关闭旧数据连接；旧连接的迟到回调按代际忽略，
 * 不会注销新连接。旧 ticket 同时撤销，且没有自动建连或请求重放。
 *
 * <p><b>资源：</b>数据连接最多 64 条并发流，credit、队列与 fail-stop 完全由 {@link MuxConnection} 负责。本类只负责控制面绑定和逻辑流生命周期，
 * 不解析浏览器 HTTP/WS，也不把管理命令塞进这条连接。
 */
@Slf4j
public final class IdeDataConnectionRegistry implements AutoCloseable {

  static final Duration DEFAULT_TICKET_TTL = Duration.ofSeconds(30);
  static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
  static final Duration DEFAULT_LEASE_CHECK_INTERVAL = Duration.ofSeconds(5);

  private final EnvironmentDaemonServer server;
  private final IdeDataStreamUrlFactory streamUrlFactory;
  private final OneTimeTicketStore<TicketBinding> tickets;
  private final Duration ticketTtl;
  private final Duration connectTimeout;
  private final Duration leaseCheckInterval;
  private final ScheduledExecutorService timer;
  private final boolean ownsTimer;

  private final Object lock = new Object();
  private final Map<EnvironmentId, EnvironmentState> environments = new HashMap<>();
  private final AtomicLong generations = new AtomicLong();
  private boolean closed;
  private final ScheduledFuture<?> leaseFenceTask;

  /** 生产装配：使用默认 30 秒票据、10 秒回连等待和 5 秒租约围栏。 */
  public IdeDataConnectionRegistry(
      EnvironmentDaemonServer server, IdeDataStreamUrlFactory streamUrlFactory) {
    this(
        server,
        streamUrlFactory,
        new SecureRandom(),
        Clock.systemUTC(),
        DEFAULT_TICKET_TTL,
        DEFAULT_CONNECT_TIMEOUT,
        DEFAULT_LEASE_CHECK_INTERVAL,
        null);
  }

  /** 可注入随机源、时钟、超时与共享调度器；{@code timer} 为 null 时由本对象创建独占调度器。 */
  IdeDataConnectionRegistry(
      EnvironmentDaemonServer server,
      IdeDataStreamUrlFactory streamUrlFactory,
      SecureRandom random,
      Clock clock,
      Duration ticketTtl,
      Duration connectTimeout,
      Duration leaseCheckInterval,
      ScheduledExecutorService timer) {
    this.server = Objects.requireNonNull(server, "server");
    this.streamUrlFactory = Objects.requireNonNull(streamUrlFactory, "streamUrlFactory");
    this.tickets =
        new OneTimeTicketStore<>(
            Objects.requireNonNull(random, "random"),
            Objects.requireNonNull(clock, "clock"),
            requirePositive(ticketTtl, "ticketTtl"));
    this.ticketTtl = ticketTtl;
    this.connectTimeout = requirePositive(connectTimeout, "connectTimeout");
    this.leaseCheckInterval = requirePositive(leaseCheckInterval, "leaseCheckInterval");
    this.ownsTimer = timer == null;
    this.timer =
        timer == null
            ? Executors.newSingleThreadScheduledExecutor(
                runnable -> {
                  Thread thread = new Thread(runnable, "ide-data-lease-fence");
                  thread.setDaemon(true);
                  return thread;
                })
            : timer;
    this.leaseFenceTask =
        this.timer.scheduleWithFixedDelay(
            this::fenceLeasesSafely,
            this.leaseCheckInterval.toMillis(),
            this.leaseCheckInterval.toMillis(),
            TimeUnit.MILLISECONDS);
  }

  /**
   * 取得当前数据连接，必要时下发一次 {@code DATA_BIND} 并等待 Daemon 回连，然后打开逻辑流。
   *
   * @throws IdeDataUnavailableException 当前节点没有 READY owner lease，或逻辑流无法 admission
   * @throws IdeDataConnectTimeoutException 回连未在期限内完成
   * @throws IdeDataGenerationReplacedException 控制身份已被替代
   */
  public MuxStream open(EnvironmentId environmentId, MuxOpen request, MuxStreamListener listener) {
    return open(environmentId, request, listener, null);
  }

  /**
   * 在调用方已冻结的授权身份上打开逻辑流。
   *
   * <p>授权身份必须同时等于当前 READY 控制身份和已接受数据连接的控制身份；否则拒绝，且不关闭当前健康连接。这样旧 cookie/epoch 不会 悄悄落到新 Daemon
   * 实例的数据连接上。
   */
  public MuxStream openForIdentity(
      EnvironmentId environmentId,
      DaemonConnectionIdentity expectedControlIdentity,
      MuxOpen request,
      MuxStreamListener listener) {
    return open(
        environmentId,
        request,
        listener,
        Objects.requireNonNull(expectedControlIdentity, "expectedControlIdentity"));
  }

  private MuxStream open(
      EnvironmentId environmentId,
      MuxOpen request,
      MuxStreamListener listener,
      DaemonConnectionIdentity expectedControlIdentity) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");
    DaemonConnectionIdentity before;
    try {
      before = requireReadyIdentity(environmentId);
    } catch (RuntimeException error) {
      // 租约/控制身份失效时，旧数据连接也必须立即离开可服务状态。
      closeActive(environmentId);
      throw error;
    }
    if (expectedControlIdentity != null && !expectedControlIdentity.equals(before)) {
      throw new IdeDataGenerationReplacedException(
          "IDE view authorization belongs to a replaced daemon generation for " + environmentId);
    }
    IdeDataConnection connection = demand(environmentId);
    DaemonConnectionIdentity current;
    try {
      current = requireReadyIdentity(environmentId);
    } catch (RuntimeException error) {
      closeIfActive(connection);
      throw error;
    }
    if (expectedControlIdentity != null && !expectedControlIdentity.equals(current)) {
      throw new IdeDataGenerationReplacedException(
          "IDE view authorization belongs to a replaced daemon generation for " + environmentId);
    }
    if (!connection.controlIdentity().equals(current)) {
      closeIfActive(connection);
      throw new IdeDataGenerationReplacedException(
          "IDE data connection generation was replaced for " + environmentId);
    }
    synchronized (lock) {
      requireOpen();
      EnvironmentState state = state(environmentId);
      if (state.active != connection || !connection.mux().isOpen()) {
        throw new IdeDataUnavailableException(
            "IDE data connection is no longer current for " + environmentId);
      }
      MuxStream stream = connection.mux().open(request, listener);
      TrackedStream tracked = new TrackedStream(request.viewId(), request.bindingEpoch(), stream);
      state.streams.add(tracked);
      stream
          .closed()
          .whenComplete(
              (ignored, error) -> {
                synchronized (lock) {
                  state.streams.remove(tracked);
                }
              });
      return stream;
    }
  }

  /** 打开一条 HTTP 逻辑流；视图作用域校验由调用方在进入数据面前完成。 */
  public MuxStream openHttp(
      EnvironmentId environmentId,
      UUID viewId,
      long bindingEpoch,
      String method,
      String path,
      List<MuxHeader> headers,
      MuxStreamListener listener) {
    return open(
        environmentId, MuxOpen.http(viewId, bindingEpoch, method, path, headers), listener, null);
  }

  /** 携带授权控制身份的 HTTP 逻辑流。 */
  public MuxStream openHttp(
      EnvironmentId environmentId,
      UUID viewId,
      long bindingEpoch,
      String method,
      String path,
      List<MuxHeader> headers,
      DaemonConnectionIdentity expectedControlIdentity,
      MuxStreamListener listener) {
    return openForIdentity(
        environmentId,
        expectedControlIdentity,
        MuxOpen.http(viewId, bindingEpoch, method, path, headers),
        listener);
  }

  /** 打开一条浏览器 WebSocket 逻辑流；升级仍固定在 GET。 */
  public MuxStream openWebSocket(
      EnvironmentId environmentId,
      UUID viewId,
      long bindingEpoch,
      String path,
      List<MuxHeader> headers,
      MuxStreamListener listener) {
    return open(
        environmentId, MuxOpen.webSocket(viewId, bindingEpoch, path, headers), listener, null);
  }

  /** 携带授权控制身份的浏览器 WS 逻辑流。 */
  public MuxStream openWebSocket(
      EnvironmentId environmentId,
      UUID viewId,
      long bindingEpoch,
      String path,
      List<MuxHeader> headers,
      DaemonConnectionIdentity expectedControlIdentity,
      MuxStreamListener listener) {
    return openForIdentity(
        environmentId,
        expectedControlIdentity,
        MuxOpen.webSocket(viewId, bindingEpoch, path, headers),
        listener);
  }

  /**
   * 消费 Daemon 回连票据并接受物理连接。
   *
   * <p>只在票据未过期、Environment 匹配、当前 READY 控制身份仍是票据绑定身份且 owner lease 有效时接受。旧代际的迟到回连一律返回 empty，由适配器
   * fail-closed 关闭，不影响当前连接。
   */
  public Optional<IdeDataConnection> accept(
      EnvironmentId environmentId, String ticket, MuxTransport transport) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(transport, "transport");
    Optional<TicketBinding> consumed = tickets.consume(ticket);
    if (consumed.isEmpty() || !consumed.get().environmentId.equals(environmentId)) {
      return Optional.empty();
    }
    TicketBinding binding = consumed.get();
    Optional<DaemonConnectionIdentity> current = server.connectionIdentity(environmentId);
    if (current.isEmpty()
        || !current.get().equals(binding.controlIdentity)
        || !server.holdsReadyLease(environmentId)) {
      return Optional.empty();
    }

    IdeDataConnection accepted;
    IdeDataConnection previous;
    synchronized (lock) {
      if (closed) {
        return Optional.empty();
      }
      EnvironmentState state = state(environmentId);
      Pending pending = state.pending;
      if (pending == null
          || pending.binding.generation != binding.generation
          || !pending.ticket.equals(ticket)) {
        return Optional.empty();
      }
      if (!server
              .connectionIdentity(environmentId)
              .filter(binding.controlIdentity::equals)
              .isPresent()
          || !server.holdsReadyLease(environmentId)) {
        return Optional.empty();
      }
      MuxConnection mux =
          new MuxConnection(
              transport,
              new MuxConnectionListener() {
                @Override
                public void onFailure(MuxConnection connection, Throwable cause) {
                  onConnectionFailure(environmentId, binding.generation);
                }
              });
      accepted =
          new IdeDataConnection(environmentId, binding.generation, binding.controlIdentity, mux);
      previous = state.active;
      state.active = accepted;
      state.pending = null;
      lock.notifyAll();
    }
    if (previous != null) {
      previous.close();
    }
    return Optional.of(accepted);
  }

  /** 当前已接受数据连接；仅诊断/准入使用，不建立新连接。 */
  public Optional<IdeDataConnection> activeConnection(EnvironmentId environmentId) {
    Objects.requireNonNull(environmentId, "environmentId");
    synchronized (lock) {
      EnvironmentState state = environments.get(environmentId);
      if (state == null || state.active == null || !state.active.isOpen()) {
        return Optional.empty();
      }
      return Optional.of(state.active);
    }
  }

  /** 当前活动逻辑流数（含尚未写入 OPEN 的流）；无连接为 0。 */
  public int activeStreamCount(EnvironmentId environmentId) {
    Objects.requireNonNull(environmentId, "environmentId");
    synchronized (lock) {
      EnvironmentState state = environments.get(environmentId);
      return state == null || state.active == null ? 0 : state.active.activeStreamCount();
    }
  }

  /** 按 viewId + bindingEpoch 关闭逻辑流；只影响精确匹配，不触碰新代际或其它视图。 */
  public int resetView(EnvironmentId environmentId, UUID viewId, long bindingEpoch) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(viewId, "viewId");
    List<MuxStream> targets = new ArrayList<>();
    synchronized (lock) {
      EnvironmentState state = environments.get(environmentId);
      if (state == null) {
        return 0;
      }
      for (TrackedStream tracked : state.streams) {
        if (tracked.viewId.equals(viewId) && tracked.bindingEpoch == bindingEpoch) {
          targets.add(tracked.stream);
        }
      }
    }
    int reset = 0;
    for (MuxStream stream : targets) {
      if (!stream.isOpen()) {
        continue;
      }
      try {
        stream.reset("VIEW_CLOSED", "view binding is no longer active");
        reset++;
      } catch (MuxStreamClosedException | MuxConnectionClosedException ignored) {
        // 并行关闭已经收敛该流，无需重发 RESET。
      }
    }
    return reset;
  }

  /** 另一个 Daemon 实例接管：关闭该控制身份绑定的数据连接与待消费票据。 */
  public void closeGeneration(DaemonConnectionIdentity controlIdentity) {
    Objects.requireNonNull(controlIdentity, "controlIdentity");
    EnvironmentId environmentId = controlIdentity.environmentId();
    IdeDataConnection active = null;
    synchronized (lock) {
      EnvironmentState state = environments.get(environmentId);
      if (state != null) {
        if (state.active != null && state.active.controlIdentity().equals(controlIdentity)) {
          active = state.active;
          state.active = null;
        }
        if (state.pending != null
            && state.pending.binding.controlIdentity.equals(controlIdentity)) {
          state.pending = null;
        }
        lock.notifyAll();
      }
    }
    tickets.revoke(binding -> binding.controlIdentity.equals(controlIdentity));
    if (active != null) {
      active.close();
    }
  }

  @Override
  public void close() {
    List<IdeDataConnection> connections = new ArrayList<>();
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
      for (EnvironmentState state : environments.values()) {
        if (state.active != null) {
          connections.add(state.active);
          state.active = null;
        }
        state.pending = null;
      }
      lock.notifyAll();
    }
    leaseFenceTask.cancel(false);
    if (ownsTimer) {
      timer.shutdownNow();
    }
    tickets.revoke(ignored -> true);
    for (IdeDataConnection connection : connections) {
      connection.close();
    }
  }

  private IdeDataConnection demand(EnvironmentId environmentId) {
    long waitDeadline = System.nanoTime() + connectTimeout.toNanos();
    Pending expired = null;
    while (true) {
      Pending pending;
      boolean mustSend = false;
      synchronized (lock) {
        requireOpen();
        EnvironmentState state = state(environmentId);
        if (state.active != null) {
          if (state.active.isOpen()) {
            return state.active;
          }
          state.active = null;
        }
        pending = state.pending;
        if (pending != null && pending.expiresAtNanos <= System.nanoTime()) {
          expired = pending;
          state.pending = null;
          pending = null;
          lock.notifyAll();
        }
        if (pending == null) {
          mustSend = true;
        }
      }
      if (expired != null) {
        revoke(expired);
        expired = null;
      }
      if (mustSend) {
        DaemonConnectionIdentity identity = requireReadyIdentity(environmentId);
        Pending created = null;
        synchronized (lock) {
          requireOpen();
          EnvironmentState state = state(environmentId);
          if (state.active != null && state.active.isOpen()) {
            return state.active;
          }
          if (state.pending != null) {
            pending = state.pending;
          } else {
            Optional<DaemonConnectionIdentity> current = server.connectionIdentity(environmentId);
            if (current.isEmpty()) {
              throw new IdeDataUnavailableException(
                  "IDE control connection is not READY for " + environmentId);
            }
            if (!current.get().equals(identity)) {
              throw new IdeDataGenerationReplacedException(
                  "IDE control generation changed for " + environmentId);
            }
            long generation = generations.incrementAndGet();
            TicketBinding binding = new TicketBinding(environmentId, identity, generation);
            String ticket = tickets.issue(binding);
            URI streamUrl = streamUrlFactory.streamUrl(environmentId, generation, ticket);
            created =
                new Pending(
                    ticket,
                    binding,
                    Objects.requireNonNull(streamUrl, "streamUrl"),
                    System.nanoTime() + ticketTtl.toNanos());
            state.pending = created;
            pending = created;
          }
        }
        if (created != null) {
          try {
            server.sendDataBind(
                environmentId,
                new DaemonDataBind(created.binding.generation, created.ticket, created.streamUrl));
          } catch (RuntimeException error) {
            clearPending(environmentId, created);
            revoke(created);
            throw new IdeDataUnavailableException(
                "cannot send DATA_BIND for " + environmentId, error);
          }
        }
      }
      if (pending == null) {
        continue;
      }
      return awaitConnection(environmentId, pending, waitDeadline);
    }
  }

  private IdeDataConnection awaitConnection(
      EnvironmentId environmentId, Pending pending, long waitDeadlineNanos) {
    synchronized (lock) {
      while (true) {
        requireOpen();
        EnvironmentState state = state(environmentId);
        if (state.active != null && state.active.generation() == pending.binding.generation) {
          return state.active;
        }
        if (state.pending != pending) {
          throw new IdeDataUnavailableException(
              "IDE data binding was superseded for " + environmentId);
        }
        long now = System.nanoTime();
        long deadline = Math.min(waitDeadlineNanos, pending.expiresAtNanos);
        long remaining = deadline - now;
        if (remaining <= 0L) {
          state.pending = null;
          lock.notifyAll();
          revoke(pending);
          throw new IdeDataConnectTimeoutException(
              "timed out waiting for IDE data connection for " + environmentId);
        }
        try {
          long millis = TimeUnit.NANOSECONDS.toMillis(remaining);
          int nanos = (int) (remaining % 1_000_000L);
          lock.wait(Math.max(1L, millis), nanos);
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          throw new IdeDataUnavailableException(
              "interrupted while waiting for IDE data connection for " + environmentId, error);
        }
      }
    }
  }

  private void fenceLeasesSafely() {
    try {
      fenceLeases();
    } catch (RuntimeException error) {
      log.warn(
          "IDE data lease fence failed; phase=IDE_DATA_LEASE_FENCE; exception={}",
          error.getClass().getSimpleName());
    }
  }

  private void fenceLeases() {
    List<EnvironmentId> candidates = new ArrayList<>();
    synchronized (lock) {
      if (closed) {
        return;
      }
      for (Map.Entry<EnvironmentId, EnvironmentState> entry : environments.entrySet()) {
        if (entry.getValue().active != null || entry.getValue().pending != null) {
          candidates.add(entry.getKey());
        }
      }
    }
    for (EnvironmentId environmentId : candidates) {
      Optional<DaemonConnectionIdentity> current = server.connectionIdentity(environmentId);
      boolean leaseValid = current.isPresent() && server.holdsReadyLease(environmentId);
      IdeDataConnection discarded = null;
      Pending revoked = null;
      synchronized (lock) {
        EnvironmentState state = environments.get(environmentId);
        if (state == null || closed) {
          continue;
        }
        if (state.active != null
            && (!leaseValid || !state.active.controlIdentity().equals(current.orElse(null)))) {
          discarded = state.active;
          state.active = null;
        }
        if (state.pending != null
            && (!leaseValid
                || !state.pending.binding.controlIdentity.equals(current.orElse(null)))) {
          revoked = state.pending;
          state.pending = null;
        }
        if (discarded != null || revoked != null) {
          lock.notifyAll();
        }
      }
      if (revoked != null) {
        revoke(revoked);
      }
      if (discarded != null) {
        discarded.close();
      }
    }
  }

  private DaemonConnectionIdentity requireReadyIdentity(EnvironmentId environmentId) {
    Optional<DaemonConnectionIdentity> identity = server.connectionIdentity(environmentId);
    if (identity.isEmpty()) {
      throw new IdeDataUnavailableException(
          "IDE control connection is not READY for " + environmentId);
    }
    if (!server.holdsReadyLease(environmentId)) {
      throw new IdeDataUnavailableException("IDE owner lease is not valid for " + environmentId);
    }
    Optional<DaemonConnectionIdentity> after = server.connectionIdentity(environmentId);
    if (after.isEmpty() || !after.get().equals(identity.get())) {
      throw new IdeDataGenerationReplacedException(
          "IDE control generation changed for " + environmentId);
    }
    return identity.get();
  }

  private void onConnectionFailure(EnvironmentId environmentId, long generation) {
    synchronized (lock) {
      EnvironmentState state = environments.get(environmentId);
      if (state != null && state.active != null && state.active.generation() == generation) {
        state.active = null;
        lock.notifyAll();
      }
    }
  }

  private void closeActive(EnvironmentId environmentId) {
    IdeDataConnection active;
    synchronized (lock) {
      EnvironmentState state = environments.get(environmentId);
      if (state == null || state.active == null) {
        return;
      }
      active = state.active;
      state.active = null;
      lock.notifyAll();
    }
    active.close();
  }

  private void closeIfActive(IdeDataConnection connection) {
    synchronized (lock) {
      EnvironmentState state = environments.get(connection.environmentId());
      if (state == null || state.active != connection) {
        return;
      }
      state.active = null;
      lock.notifyAll();
    }
    connection.close();
  }

  private void clearPending(EnvironmentId environmentId, Pending pending) {
    synchronized (lock) {
      EnvironmentState state = environments.get(environmentId);
      if (state != null && state.pending == pending) {
        state.pending = null;
        lock.notifyAll();
      }
    }
  }

  private void revoke(Pending pending) {
    tickets.revoke(binding -> binding.equals(pending.binding));
  }

  private EnvironmentState state(EnvironmentId environmentId) {
    return environments.computeIfAbsent(environmentId, ignored -> new EnvironmentState());
  }

  private void requireOpen() {
    if (closed) {
      throw new IdeDataUnavailableException("IDE data connection registry is closed");
    }
  }

  private static Duration requirePositive(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }

  private static final class EnvironmentState {

    private final Set<TrackedStream> streams = new HashSet<>();
    private IdeDataConnection active;
    private Pending pending;
  }

  private static final class Pending {

    private final String ticket;
    private final TicketBinding binding;
    private final URI streamUrl;
    private final long expiresAtNanos;

    private Pending(String ticket, TicketBinding binding, URI streamUrl, long expiresAtNanos) {
      this.ticket = ticket;
      this.binding = binding;
      this.streamUrl = streamUrl;
      this.expiresAtNanos = expiresAtNanos;
    }
  }

  private record TicketBinding(
      EnvironmentId environmentId, DaemonConnectionIdentity controlIdentity, long generation) {}

  private record TrackedStream(UUID viewId, long bindingEpoch, MuxStream stream) {}
}
