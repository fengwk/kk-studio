package fun.fengwk.kkstudio.harness.daemon.terminal;

import fun.fengwk.kkstudio.harness.daemon.process.ProcessScope;
import fun.fengwk.kkstudio.harness.environment.terminal.AdmissionResult;
import fun.fengwk.kkstudio.harness.environment.terminal.ControlResult;
import fun.fengwk.kkstudio.harness.environment.terminal.ErrorCode;
import fun.fengwk.kkstudio.harness.environment.terminal.ErrorDisposition;
import fun.fengwk.kkstudio.harness.environment.terminal.OperationDigest;
import fun.fengwk.kkstudio.harness.environment.terminal.OperationOutcome;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalIdentity;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalRequest;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalResponse;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalRoute;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalStatus;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalViewUpdate;
import fun.fengwk.kkstudio.harness.environment.terminal.WriterState;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Daemon 侧单个 Daemon 实例的终端协调器：唯一 shell、唯一 writer 与至多 {@value #MAX_OBSERVERS} 个观察流的单 writer 状态机。
 *
 * <p>owner 状态（绑定、session、观察者、有界 mailbox 与在途 snapshot）由调用方注入的单 owner executor 串行访问；本类不创建线程、不做阻塞
 * 收敛，阻塞 PTY 生命周期只在 {@link TerminalRuntime} 内部。外部请求（bind/disconnect/receive）统一进入同一个有界 mailbox，满时或关闭时
 * 立即以固定异常失败，受理后一定终结；唯一的关闭控制槽用于 owner 自身的收尾。
 *
 * <p>{@link #ownerLock} 只保护队列与调度簿记（mailbox、control 与 tick 标记），不保护 session/观察者等 owner 状态；后者只由单 owner
 * executor 串行化。失败关闭 {@link #failCoordinator} 可从任意线程调用，它先在同一把锁下围栏 drain（置位 {@code drainFailed} 并清空
 * control），使 owner 不再执行任何任务，再排他地停止 runtime、释放观察流与终结在途外部 future。非 owner 的失败只会发生在没有 drain 任务 在跑时（调度
 * drain 需要 {@code drainScheduled} 由 false 迁移，而它只在 drain 收尾且不再有 owner 状态变更后复位），因此该排他清理不与 owner
 * 状态变更并发。
 *
 * <p>跨线程只暴露两类不可变信息：启动闸门读取的原子准入身份，以及被停止 runtime 的有界终止信号；owner 不会从别的线程读 session。
 *
 * <p>围栏：generation 拒绝过期命令，environment 决定重绑语义，writer epoch/token 与 seq/digest 决定控制与操作是否精确匹配；观察流
 * credit 超时、空闲与 emitter 失败都会移除观察者，但不动 shell。默认尺寸 80x24、历史 {@value #DEFAULT_HISTORY_LINES}。
 */
public final class TerminalCoordinator implements AutoCloseable {

  /** 单个 terminal 允许的并发观察流上限。 */
  public static final int MAX_OBSERVERS = 8;

  /** mailbox 项数上限（含正在处理的一项）。 */
  static final int MAILBOX_CAPACITY = 128;

  /** mailbox pending 字节上限（含正在处理的一项）。 */
  static final int MAILBOX_MAX_BYTES = 512 * 1024;

  /** 单条外部请求的固定元数据开销。 */
  static final int METADATA_BYTES = 128;

  /** 普通捕获与超时扫描的 tick 周期：等价于画面发布速率上界约 30Hz。 */
  static final long TICK_INTERVAL_MILLIS = 34L;

  /** 每个 tick 之前最多服务的 mailbox 项数：保证持续外部请求不会饿死超时撤流与捕获。 */
  static final int MAILBOX_BATCH_PER_TICK = 1;

  /** 观察者空闲撤销阈值。 */
  static final long IDLE_REVOKE_NANOS = 15_000_000_000L;

  /** 在途画面更新未被确认的最大时长。 */
  static final long VIEW_APPLIED_TIMEOUT_NANOS = 10_000_000_000L;

  /** shutdown 有界等待上限。 */
  static final long SHUTDOWN_WAIT_SECONDS = 90L;

  /** 单次 drain 最多处理的外部项数。 */
  private static final int DRAIN_BATCH = 512;

  /** 默认终端几何。 */
  static final int DEFAULT_COLUMNS = 80;

  static final int DEFAULT_ROWS = 24;

  /** 默认有界历史行数：与 {@code TerminalLimits.MAX_HISTORY_LINES} 一致。 */
  static final int DEFAULT_HISTORY_LINES = 512;

  private static final String COORDINATOR_CLOSED_MESSAGE = "terminal coordinator is closed";
  private static final String MAILBOX_OVERFLOW_MESSAGE = "terminal coordinator mailbox is full";
  private static final String COORDINATOR_FAILURE_MESSAGE = "terminal coordinator failed";
  private static final String SHUTDOWN_FAILURE_MESSAGE = "terminal coordinator did not converge";
  static final String REBINDING_MESSAGE = "terminal coordinator is rebinding";
  private static final String REBIND_FAILURE_MESSAGE = "terminal rebind could not converge";
  private static final String SHUTDOWN_INTERRUPTED_MESSAGE =
      "terminal coordinator shutdown was interrupted";
  private static final String SHUTDOWN_TIMEOUT_MESSAGE =
      "terminal coordinator shutdown did not converge";

  private final UUID daemonInstanceId;
  private final TerminalLaunchSpec launchSpec;
  private final Map<String, String> environment;
  private final ExecutorService ownerExecutor;
  private final ExecutorService vtExecutor;
  private final ExecutorService ioExecutor;
  private final ScheduledExecutorService scheduler;
  private final Consumer<TerminalResponse> emitter;
  private final RuntimeFactory runtimeFactory;
  private final LongSupplier clock;

  /** 队列与调度簿记的唯一锁；不保护 session/观察者等 owner 状态（见类注释）。 */
  private final Object ownerLock = new Object();

  private final ArrayDeque<Envelope> mailbox = new ArrayDeque<>();
  private int mailboxItems;
  private long mailboxBytes;
  private final ArrayDeque<Runnable> control = new ArrayDeque<>();
  private boolean drainScheduled;
  private boolean tickPending;
  private boolean mailboxServedSinceTick;
  private UUID environmentId;
  private long generation;
  private boolean bound;
  private boolean rebinding;
  private Session session;

  /** 至多 {@value #MAX_OBSERVERS} 个观察者；只在 owner 上变更，迭代一律先做快照。 */
  private final List<Observer> observers = new ArrayList<>();

  /** 唯一在途重绑；跨线程可见，使失败关闭也能终结它已受理的 future。 */
  private volatile Rebind pendingRebind;

  private boolean snapshotInFlight;
  private UUID lastBroadcastEpoch;
  private boolean lastBroadcastEpochKnown;

  /** 关闭/失败后不再受理任何外部请求；跨线程读取。 */
  private volatile boolean closing;

  /** owner 已失败；跨线程读取，用于让 offer 立即失败。 */
  private volatile boolean drainFailed;

  /** 启动闸门读取的原子准入身份；owner 更新，IO 线程读取。 */
  private final AtomicReference<Admission> admission =
      new AtomicReference<>(new Admission(null, false));

  private final AtomicBoolean shutdownStarted = new AtomicBoolean();

  /** 已创建但尚未收敛的 runtime；用于 fatal/shutdown 时停止并等待资源信号。 */
  private final Set<TerminalRuntime> liveRuntimes = ConcurrentHashMap.newKeySet();

  /** 尚未由 owner 决议的启动；fatal/重绑时可被任意线程围住。 */
  private final Set<StartToken> pendingStarts = ConcurrentHashMap.newKeySet();

  /** 终止汇合状态：与 owner 状态解耦，允许 runtime 的 IO 线程参与收敛判定。 */
  private final Object endLock = new Object();

  private boolean endRequested;
  private Throwable endFailure;
  private boolean ownerSettled;
  private final CompletableFuture<Void> terminated = new CompletableFuture<>();

  private final ScheduledFuture<?> ticker;

  /**
   * 生产入口：以真实 {@link TerminalRuntime} 承载 shell。
   *
   * @param daemonInstanceId 承载终端的 Daemon 实例身份
   * @param launchSpec 已解析的启动规格
   * @param environment 被继承的环境变量
   * @param ownerExecutor 唯一串行访问协调器状态的调用方 executor
   * @param vtExecutor VT 内核唯一 owner 使用的调用方 executor
   * @param ioExecutor PTY 启动与阻塞 I/O 使用的调用方 executor
   * @param scheduler native 截止时间与 tick 使用的调用方 scheduler
   * @param emitter 非阻塞的出站事件入队器
   */
  public TerminalCoordinator(
      UUID daemonInstanceId,
      TerminalLaunchSpec launchSpec,
      Map<String, String> environment,
      ExecutorService ownerExecutor,
      ExecutorService vtExecutor,
      ExecutorService ioExecutor,
      ScheduledExecutorService scheduler,
      Consumer<TerminalResponse> emitter) {
    this(
        daemonInstanceId,
        launchSpec,
        environment,
        ownerExecutor,
        vtExecutor,
        ioExecutor,
        scheduler,
        emitter,
        TerminalCoordinator::startRuntime,
        System::nanoTime);
  }

  /** 包内测试入口：注入最小 RuntimeFactory 与单调时钟，其余语义与生产入口一致。 */
  TerminalCoordinator(
      UUID daemonInstanceId,
      TerminalLaunchSpec launchSpec,
      Map<String, String> environment,
      ExecutorService ownerExecutor,
      ExecutorService vtExecutor,
      ExecutorService ioExecutor,
      ScheduledExecutorService scheduler,
      Consumer<TerminalResponse> emitter,
      RuntimeFactory runtimeFactory,
      LongSupplier clock) {
    this.daemonInstanceId = Objects.requireNonNull(daemonInstanceId, "daemonInstanceId");
    this.launchSpec = Objects.requireNonNull(launchSpec, "launchSpec");
    this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
    this.ownerExecutor = Objects.requireNonNull(ownerExecutor, "ownerExecutor");
    this.vtExecutor = Objects.requireNonNull(vtExecutor, "vtExecutor");
    this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.emitter = Objects.requireNonNull(emitter, "emitter");
    this.runtimeFactory = Objects.requireNonNull(runtimeFactory, "runtimeFactory");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ticker = scheduleTicker();
  }

  /**
   * 绑定/重绑一个 Environment 代际。同 Environment 清观察 route、保留 shell；不同 Environment 先围住旧操作入口，等待旧启动决议与 旧
   * runtime 的停止/收敛，再绑定新身份。更低或过期的 generation 不得倒退当前绑定。
   *
   * @param environmentId 目标环境
   * @param generation 当前后端连接代际
   * @return 绑定处理完成的 future
   */
  public CompletableFuture<Void> bind(UUID environmentId, long generation) {
    Objects.requireNonNull(environmentId, "environmentId");
    return submit(METADATA_BYTES, done -> handleBind(environmentId, generation, done));
  }

  /**
   * 受理一次外部控制请求，进入有界 mailbox。future 只表示命令已被受理/状态处理完成，真实操作结果以事件为准。
   *
   * @param generation 命令所属代际，过期代际无副作用
   * @param request 控制请求
   * @return 受理完成的 future；mailbox 满或已关闭时以固定异常失败
   */
  public CompletableFuture<Void> receive(long generation, TerminalRequest request) {
    Objects.requireNonNull(request, "request");
    return submit(
        accountBytes(request.command()),
        done -> {
          if (fenceAccepts(generation)) {
            handleCommand(request);
          }
          done.complete(null);
        });
  }

  /**
   * 断开指定代际的观察连接：只清观察 route，不杀 shell，也不释放仍有效的 writer。
   *
   * @param generation 期望的代际，过期代际无副作用
   * @return 处理完成的 future
   */
  public CompletableFuture<Void> disconnect(long generation) {
    return submit(
        METADATA_BYTES,
        done -> {
          if (fenceAccepts(generation)) {
            clearObservers();
          }
          done.complete(null);
        });
  }

  /** 幂等关闭：停止并等待唯一 runtime 收敛；收敛失败以异常 future 显式报告。 */
  public CompletableFuture<Void> shutdown() {
    if (shutdownStarted.compareAndSet(false, true)) {
      closing = true;
      admission.set(new Admission(null, false));
      synchronized (endLock) {
        endRequested = true;
      }
      cancelTicker();
      enqueueControl(this::handleShutdown);
    }
    return termination();
  }

  /** 协调器整体终止信号；收敛失败时异常完成。 */
  public CompletableFuture<Void> termination() {
    return terminated.copy();
  }

  /** 关闭并统一有界等待；被中断、超时或收敛失败都显式抛出。 */
  @Override
  public void close() {
    shutdown();
    try {
      terminated.get(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(SHUTDOWN_INTERRUPTED_MESSAGE);
    } catch (TimeoutException timeout) {
      throw new IllegalStateException(SHUTDOWN_TIMEOUT_MESSAGE);
    } catch (ExecutionException failure) {
      throw new IllegalStateException(SHUTDOWN_FAILURE_MESSAGE);
    }
  }

  // ------------------------------------------------------------------ 外部受理

  private CompletableFuture<Void> submit(int bytes, Consumer<CompletableFuture<Void>> action) {
    CompletableFuture<Void> done = new CompletableFuture<>();
    CompletableFuture<Void> rejection = offer(new Envelope(bytes, done, action));
    return rejection != null ? rejection : done;
  }

  private CompletableFuture<Void> offer(Envelope envelope) {
    synchronized (ownerLock) {
      if (closing || drainFailed) {
        return CompletableFuture.failedFuture(new CoordinatorClosedException());
      }
      if (mailboxItems >= MAILBOX_CAPACITY || mailboxBytes + envelope.bytes() > MAILBOX_MAX_BYTES) {
        return CompletableFuture.failedFuture(new MailboxOverflowException());
      }
      mailbox.addLast(envelope);
      mailboxItems++;
      mailboxBytes += envelope.bytes();
    }
    signalOwner();
    return null;
  }

  /** 命令/断开的代际围栏：只在 owner 上求值。 */
  private boolean fenceAccepts(long envelopeGeneration) {
    return !closing && !drainFailed && bound && envelopeGeneration == generation;
  }

  // ------------------------------------------------------------------ 调度

  private ScheduledFuture<?> scheduleTicker() {
    try {
      return scheduler.scheduleAtFixedRate(
          this::enqueueTick, TICK_INTERVAL_MILLIS, TICK_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException rejected) {
      throw new IllegalStateException("terminal coordinator scheduler rejected the ticker");
    }
  }

  private void cancelTicker() {
    if (ticker != null) {
      ticker.cancel(false);
    }
  }

  private void enqueueTick() {
    synchronized (ownerLock) {
      if (closing || drainFailed || tickPending) {
        return;
      }
      tickPending = true;
    }
    signalOwner();
  }

  private void signalOwner() {
    synchronized (ownerLock) {
      if (drainFailed || drainScheduled) {
        return;
      }
      drainScheduled = true;
    }
    scheduleDrain();
  }

  private void scheduleDrain() {
    try {
      ownerExecutor.execute(this::drain);
    } catch (RejectedExecutionException rejected) {
      synchronized (ownerLock) {
        drainScheduled = false;
      }
      failCoordinator(rejected);
    }
  }

  private void enqueueControl(Runnable task) {
    synchronized (ownerLock) {
      if (drainFailed) {
        return;
      }
      control.addLast(task);
    }
    signalOwner();
  }

  private void drain() {
    try {
      int processed = 0;
      while (processed < DRAIN_BATCH) {
        Runnable task = null;
        Envelope envelope = null;
        synchronized (ownerLock) {
          if (drainFailed) {
            break;
          }
          if (!control.isEmpty()) {
            task = control.pollFirst();
          } else if (tickPending && (mailbox.isEmpty() || mailboxServedSinceTick)) {
            tickPending = false;
            mailboxServedSinceTick = false;
            task = this::onTick;
          } else if (!mailbox.isEmpty()) {
            envelope = mailbox.pollFirst();
            mailboxServedSinceTick = true;
          }
        }
        if (task == null && envelope == null) {
          break;
        }
        if (task != null) {
          runGuarded(task);
        } else {
          processEnvelope(envelope);
        }
        processed++;
      }
    } finally {
      boolean more;
      synchronized (ownerLock) {
        more = !drainFailed && (!control.isEmpty() || !mailbox.isEmpty() || tickPending);
        if (!more) {
          drainScheduled = false;
          // 一次积压排空后重新计票：新积压从「一个 mailbox 项」起就要让出 tick。
          mailboxServedSinceTick = false;
        }
      }
      if (more) {
        scheduleDrain();
      }
    }
  }

  private void runGuarded(Runnable task) {
    try {
      task.run();
    } catch (Throwable error) {
      failCoordinator(error);
    }
  }

  private void processEnvelope(Envelope envelope) {
    try {
      if (drainFailed) {
        envelope.done().completeExceptionally(new CoordinatorClosedException());
        return;
      }
      envelope.action().accept(envelope.done());
    } catch (RuntimeException error) {
      envelope.done().completeExceptionally(error);
    } finally {
      synchronized (ownerLock) {
        mailboxItems--;
        mailboxBytes -= envelope.bytes();
      }
    }
  }

  // ------------------------------------------------------------------ 失败与终止收敛

  /**
   * 协调器进入失败关闭边界：围住启动闸门、终结全部外部受理（含唯一在途重绑）、停止并汇合全部 runtime、释放观察状态。可从任意线程调用； 先在同一把锁下围栏 drain，因此不与其他
   * owner 状态变更并发。
   */
  private void failCoordinator(Throwable error) {
    List<CompletableFuture<Void>> pending = new ArrayList<>();
    Rebind rebind;
    synchronized (ownerLock) {
      if (drainFailed) {
        return;
      }
      drainFailed = true;
      Envelope envelope;
      while ((envelope = mailbox.pollFirst()) != null) {
        pending.add(envelope.done());
      }
      mailboxItems = 0;
      mailboxBytes = 0;
      control.clear();
      tickPending = false;
      // 已从 mailbox 取出的重绑 future 仍是已受理的外部请求，必须与协调器一起终结。
      rebind = pendingRebind;
      pendingRebind = null;
      rebinding = false;
    }
    closing = true;
    admission.set(new Admission(null, false));
    cancelTicker();
    synchronized (endLock) {
      endRequested = true;
      if (endFailure == null) {
        endFailure = unwrap(error);
      }
      ownerSettled = true;
    }
    for (CompletableFuture<Void> done : pending) {
      done.completeExceptionally(new CoordinatorClosedException());
    }
    if (rebind != null) {
      rebind.done().completeExceptionally(new CoordinatorClosedException());
    }
    for (StartToken token : pendingStarts) {
      token.abandon();
    }
    for (TerminalRuntime runtime : liveRuntimes) {
      runtime.stop();
    }
    releaseObserverState();
    tryCompleteTermination();
  }

  private void registerRuntime(TerminalRuntime runtime) {
    synchronized (endLock) {
      liveRuntimes.add(runtime);
    }
    runtime
        .termination()
        .whenComplete(
            (ignored, error) -> {
              synchronized (endLock) {
                liveRuntimes.remove(runtime);
                if (error != null && endFailure == null) {
                  endFailure = unwrap(error);
                }
              }
              tryCompleteTermination();
            });
  }

  private boolean tryCompleteTermination() {
    synchronized (endLock) {
      if (!endRequested || !ownerSettled || !liveRuntimes.isEmpty() || !pendingStarts.isEmpty()) {
        return false;
      }
      if (endFailure != null) {
        terminated.completeExceptionally(endFailure);
      } else {
        terminated.complete(null);
      }
      return true;
    }
  }

  private void settleOwner() {
    if (closing) {
      clearObservers();
    }
    synchronized (endLock) {
      ownerSettled = true;
    }
    tryCompleteTermination();
  }

  private void maybeCompleteTermination() {
    if (closing) {
      settleOwner();
    }
  }

  // ------------------------------------------------------------------ 绑定

  private void handleBind(UUID newEnvironmentId, long newGeneration, CompletableFuture<Void> done) {
    if (closing || drainFailed) {
      done.completeExceptionally(new CoordinatorClosedException());
      return;
    }
    if (rebinding) {
      // 未完成重绑期间不接受任何新绑定：pendingRebind 仍是唯一在途绑定，避免虚假「已绑定」。
      done.completeExceptionally(new IllegalStateException(REBINDING_MESSAGE));
      return;
    }
    if (bound && newGeneration < generation) {
      done.complete(null);
      return;
    }
    if (bound && environmentId.equals(newEnvironmentId)) {
      environmentId = newEnvironmentId;
      generation = newGeneration;
      refreshAdmission();
      clearObservers();
      done.complete(null);
      return;
    }
    if (bound && newGeneration == generation) {
      // 同代际换 Environment 不是合法重绑：不得复用或倒退当前绑定。
      done.complete(null);
      return;
    }
    Session previous = session;
    environmentId = newEnvironmentId;
    generation = newGeneration;
    bound = true;
    refreshAdmission();
    clearObservers();
    if (previous != null && !previous.terminationHandled) {
      rebinding = true;
      pendingRebind = new Rebind(previous, done);
      if (previous.runtime != null) {
        previous.runtime.stop();
      } else {
        abandonStart(previous);
      }
      return;
    }
    session = null;
    done.complete(null);
  }

  private void handleShutdown() {
    Session current = session;
    if (current != null && !current.terminationHandled) {
      if (current.runtime != null) {
        current.runtime.stop();
      } else {
        abandonStart(current);
      }
      return;
    }
    settleOwner();
  }

  private void maybeCompleteRebind(Session resolved) {
    Rebind rebind = pendingRebind;
    if (rebind == null || rebind.session() != resolved) {
      return;
    }
    pendingRebind = null;
    rebinding = false;
    session = null;
    if (resolved.terminationError != null) {
      rebind.done().completeExceptionally(new IllegalStateException(REBIND_FAILURE_MESSAGE));
      failCoordinator(new IllegalStateException(REBIND_FAILURE_MESSAGE));
    } else {
      rebind.done().complete(null);
    }
  }

  // ------------------------------------------------------------------ 命令分发

  private void handleCommand(TerminalRequest request) {
    TerminalCommand command = request.command();
    if (environmentId == null || !command.environmentId().equals(environmentId)) {
      emitError(request, ErrorCode.TERMINAL_NOT_FOUND, ErrorDisposition.NOT_EXECUTED);
      return;
    }
    if (rebinding) {
      emitError(request, ErrorCode.ROUTE_UNAVAILABLE, ErrorDisposition.NOT_EXECUTED);
      return;
    }
    switch (command.payload()) {
      case TerminalCommand.Open open -> handleOpen(request, open);
      case TerminalCommand.Attach attach -> handleAttach(request, attach);
      case TerminalCommand.Detach detach -> handleDetach(request, detach);
      case TerminalCommand.Claim claim -> handleClaim(request, claim);
      case TerminalCommand.Takeover takeover -> handleTakeover(request, takeover);
      case TerminalCommand.Release release -> handleRelease(request, release);
      case TerminalCommand.Input input -> handleInput(request, input);
      case TerminalCommand.Resize resize -> handleResize(request, resize);
      case TerminalCommand.ViewApplied viewApplied -> handleViewApplied(request, viewApplied);
      case TerminalCommand.Keepalive keepalive -> handleKeepalive(request, keepalive);
      case TerminalCommand.Close close -> handleClose(request, close);
    }
  }

  private void handleOpen(TerminalRequest request, TerminalCommand.Open open) {
    UUID viewerId = request.command().viewerId();
    AttachSignature signature =
        new AttachSignature(TerminalCommand.Type.OPEN, null, open.expectedExited());
    Observer existing = observerFor(request.route(), viewerId);
    if (existing != null && replayAttach(existing, request.command().requestId(), signature)) {
      return;
    }
    Session current = session;
    if (current == null) {
      if (open.expectedExited() != null) {
        // 没有旧 session 时旧 CAS 无法匹配，不得凭空启动。
        emitError(request, ErrorCode.REQUEST_CONFLICT, ErrorDisposition.NOT_EXECUTED);
        return;
      }
      startNewSession(request, viewerId, signature);
      return;
    }
    TerminalIdentity expected = open.expectedExited();
    if (current.starting || current.status == TerminalStatus.RUNNING) {
      if (expected != null) {
        emitError(request, ErrorCode.REQUEST_CONFLICT, ErrorDisposition.NOT_EXECUTED);
        return;
      }
      attachExisting(request, viewerId, signature, current);
      return;
    }
    if (expected != null) {
      if (!expected.daemonInstanceId().equals(daemonInstanceId)
          || !expected.terminalId().equals(current.terminalId)) {
        emitError(request, ErrorCode.REQUEST_CONFLICT, ErrorDisposition.NOT_EXECUTED);
        return;
      }
      clearObservers();
      startNewSession(request, viewerId, signature);
      return;
    }
    if (!isAttachable(current)) {
      emitError(request, ErrorCode.RUNTIME_FAILED, ErrorDisposition.NOT_EXECUTED);
      return;
    }
    attachExisting(request, viewerId, signature, current);
  }

  private void handleAttach(TerminalRequest request, TerminalCommand.Attach attach) {
    Session current = session;
    TerminalIdentity identity = attach.identity();
    if (!identity.daemonInstanceId().equals(daemonInstanceId)) {
      emitError(request, ErrorCode.DAEMON_MISMATCH, ErrorDisposition.NOT_EXECUTED);
      return;
    }
    if (current == null || !identity.terminalId().equals(current.terminalId)) {
      emitError(request, ErrorCode.TERMINAL_NOT_FOUND, ErrorDisposition.NOT_EXECUTED);
      return;
    }
    UUID viewerId = request.command().viewerId();
    AttachSignature signature = new AttachSignature(TerminalCommand.Type.ATTACH, identity, null);
    Observer existing = observerFor(request.route(), viewerId);
    if (existing != null && replayAttach(existing, request.command().requestId(), signature)) {
      return;
    }
    if (!isAttachable(current)) {
      emitError(request, ErrorCode.RUNTIME_FAILED, ErrorDisposition.NOT_EXECUTED);
      return;
    }
    attachExisting(request, viewerId, signature, current);
  }

  private void handleDetach(TerminalRequest request, TerminalCommand.Detach detach) {
    if (!checkIdentity(request, detach.identity())) {
      return;
    }
    Observer observer =
        observerFor(request.route(), request.command().viewerId(), detach.streamId());
    if (observer == null) {
      emitError(request, ErrorCode.STREAM_NOT_FOUND, ErrorDisposition.NOT_EXECUTED);
      return;
    }
    removeObserver(observer);
  }

  private void handleClaim(TerminalRequest request, TerminalCommand.Claim claim) {
    if (!checkIdentity(request, claim.identity())) {
      return;
    }
    Observer observer =
        observerFor(request.route(), request.command().viewerId(), claim.streamId());
    if (observer == null) {
      emitError(request, ErrorCode.STREAM_NOT_FOUND, ErrorDisposition.NOT_EXECUTED);
      return;
    }
    if (!requireRunningControl(request, observer)) {
      return;
    }
    Session current = session;
    WriterOwner owner = writerOwner(request);
    UUID requestId = request.command().requestId();
    ControlResult result =
        claim.recovery() == null
            ? current.writer.claim(owner, requestId)
            : current.writer.recover(
                owner,
                requestId,
                claim.recovery().previous(),
                claim.recovery().seq(),
                claim.recovery().digest());
    emitControlResult(request, current, result, changedWriter(result));
  }

  private void handleTakeover(TerminalRequest request, TerminalCommand.Takeover takeover) {
    if (!checkIdentity(request, takeover.identity())) {
      return;
    }
    Observer observer =
        observerFor(request.route(), request.command().viewerId(), takeover.streamId());
    if (observer == null) {
      emitError(request, ErrorCode.STREAM_NOT_FOUND, ErrorDisposition.NOT_EXECUTED);
      return;
    }
    if (!requireRunningControl(request, observer)) {
      return;
    }
    Session current = session;
    ControlResult result =
        current.writer.takeover(
            writerOwner(request), request.command().requestId(), takeover.expectedWriterEpoch());
    emitControlResult(request, current, result, changedWriter(result));
  }

  private void handleRelease(TerminalRequest request, TerminalCommand.Release release) {
    if (!checkIdentity(request, release.identity())) {
      return;
    }
    Observer observer =
        observerFor(request.route(), request.command().viewerId(), release.streamId());
    if (observer == null) {
      emitError(request, ErrorCode.STREAM_NOT_FOUND, ErrorDisposition.NOT_EXECUTED);
      return;
    }
    Session current = session;
    ControlResult result =
        current.writer.release(
            writerOwner(request), request.command().requestId(), release.grant());
    emitControlResult(request, current, result, result.status() == ControlResult.Status.RELEASED);
  }

  private void handleInput(TerminalRequest request, TerminalCommand.Input input) {
    if (!checkIdentity(request, input.identity())) {
      return;
    }
    Observer observer =
        observerFor(request.route(), request.command().viewerId(), input.streamId());
    if (observer == null) {
      emitError(request, ErrorCode.STREAM_NOT_FOUND, ErrorDisposition.NOT_EXECUTED);
      return;
    }
    if (!requireRunningControl(request, observer)) {
      return;
    }
    Session current = session;
    AdmissionResult admission =
        current.writer.submitInput(
            writerOwner(request),
            input.grant(),
            input.seq(),
            input.bytes(),
            input.inputModeRevision());
    if (!admission.isAccepted()) {
      emitOpAck(
          request.route(),
          request.command().viewerId(),
          input.grant().epoch(),
          admission,
          codeForAdmission(admission));
      return;
    }
    UUID epoch = input.grant().epoch();
    long seq = admission.seq();
    OperationDigest digest = admission.digest();
    current
        .runtime
        .writeInput(input.bytes(), input.inputModeRevision())
        .whenComplete(
            (ignored, error) ->
                enqueueControl(
                    () ->
                        onOperationResolved(
                            current,
                            request.route(),
                            request.command().viewerId(),
                            epoch,
                            seq,
                            digest,
                            error)));
  }

  private void handleResize(TerminalRequest request, TerminalCommand.Resize resize) {
    if (!checkIdentity(request, resize.identity())) {
      return;
    }
    Observer observer =
        observerFor(request.route(), request.command().viewerId(), resize.streamId());
    if (observer == null) {
      emitError(request, ErrorCode.STREAM_NOT_FOUND, ErrorDisposition.NOT_EXECUTED);
      return;
    }
    if (!requireRunningControl(request, observer)) {
      return;
    }
    Session current = session;
    AdmissionResult admission =
        current.writer.submitResize(
            writerOwner(request), resize.grant(), resize.seq(), resize.cols(), resize.rows());
    if (!admission.isAccepted()) {
      emitOpAck(
          request.route(),
          request.command().viewerId(),
          resize.grant().epoch(),
          admission,
          codeForAdmission(admission));
      return;
    }
    UUID epoch = resize.grant().epoch();
    long seq = admission.seq();
    OperationDigest digest = admission.digest();
    current
        .runtime
        .resize(resize.cols(), resize.rows())
        .whenComplete(
            (ignored, error) ->
                enqueueControl(
                    () ->
                        onOperationResolved(
                            current,
                            request.route(),
                            request.command().viewerId(),
                            epoch,
                            seq,
                            digest,
                            error)));
  }

  private void handleViewApplied(TerminalRequest request, TerminalCommand.ViewApplied viewApplied) {
    if (!checkIdentity(request, viewApplied.identity())) {
      return;
    }
    Observer observer =
        observerFor(request.route(), request.command().viewerId(), viewApplied.streamId());
    if (observer == null) {
      emitError(request, ErrorCode.STREAM_NOT_FOUND, ErrorDisposition.NOT_EXECUTED);
      return;
    }
    observer.lastActivityNanos = clock.getAsLong();
    observer.stream.applied(viewApplied.streamId(), viewApplied.version());
  }

  private void handleKeepalive(TerminalRequest request, TerminalCommand.Keepalive keepalive) {
    if (!checkIdentity(request, keepalive.identity())) {
      return;
    }
    Observer observer =
        observerFor(request.route(), request.command().viewerId(), keepalive.streamId());
    if (observer == null) {
      emitError(request, ErrorCode.STREAM_NOT_FOUND, ErrorDisposition.NOT_EXECUTED);
      return;
    }
    observer.lastActivityNanos = clock.getAsLong();
    if (keepalive.grant() != null) {
      Session current = session;
      ControlResult result =
          current.writer.renew(
              writerOwner(request), request.command().requestId(), keepalive.grant());
      emitControlResult(request, current, result, false);
    }
  }

  private void handleClose(TerminalRequest request, TerminalCommand.Close close) {
    if (!checkIdentity(request, close.identity())) {
      return;
    }
    Session current = session;
    WriterState state = current.writer.state();
    if (!Objects.equals(close.expectedWriterEpoch(), state.writerEpoch())) {
      emitError(request, ErrorCode.REQUEST_CONFLICT, ErrorDisposition.NOT_EXECUTED);
      return;
    }
    if (state.pendingSeq() > 0L) {
      emitError(request, ErrorCode.BUSY, ErrorDisposition.NOT_EXECUTED);
      return;
    }
    if (current.runtime != null && !current.terminationHandled) {
      current.runtime.stop();
    }
  }

  // ------------------------------------------------------------------ 会话与观察流

  private void startNewSession(TerminalRequest request, UUID viewerId, AttachSignature signature) {
    Observer existing = observerFor(request.route(), viewerId);
    if (existing == null && observers.size() >= MAX_OBSERVERS) {
      emitError(request, ErrorCode.BACKPRESSURE, ErrorDisposition.NOT_EXECUTED);
      return;
    }
    UUID terminalId = UUID.randomUUID();
    Session created = new Session(terminalId, new TerminalWriter(terminalId, clock));
    created.starting = true;
    session = created;
    lastBroadcastEpoch = created.writer.state().writerEpoch();
    lastBroadcastEpochKnown = true;
    Observer observer = ensureObserver(request.route(), viewerId);
    if (observer == null) {
      emitError(request, ErrorCode.BACKPRESSURE, ErrorDisposition.NOT_EXECUTED);
      return;
    }
    resetObserverStream(observer, created, request.command().requestId(), signature);
    dispatchStart(created);
  }

  private void attachExisting(
      TerminalRequest request, UUID viewerId, AttachSignature signature, Session current) {
    Observer observer = ensureObserver(request.route(), viewerId);
    if (observer == null) {
      emitError(request, ErrorCode.BACKPRESSURE, ErrorDisposition.NOT_EXECUTED);
      return;
    }
    resetObserverStream(observer, current, request.command().requestId(), signature);
  }

  private static boolean isAttachable(Session current) {
    return (current.starting || current.runtime != null) && !current.captureUnavailable;
  }

  private void dispatchStart(Session created) {
    StartToken token = new StartToken(created);
    pendingStarts.add(token);
    UUID startEnvironment = environmentId;
    Runnable start =
        () -> {
          TerminalRuntime runtime;
          try {
            runtime =
                runtimeFactory.create(
                    launchSpec,
                    DEFAULT_COLUMNS,
                    DEFAULT_ROWS,
                    DEFAULT_HISTORY_LINES,
                    environment,
                    () -> startAllowed(startEnvironment),
                    vtExecutor,
                    ioExecutor,
                    scheduler);
          } catch (Exception error) {
            resolveStart(token);
            enqueueControl(() -> onStartFailed(created, token));
            return;
          }
          registerRuntime(runtime);
          if (token.handIn(runtime)) {
            resolveStart(token);
            enqueueControl(() -> onStarted(created, token, runtime));
          } else {
            resolveStart(token);
            enqueueControl(() -> onAbandonedStart(created, runtime));
          }
        };
    try {
      ioExecutor.execute(start);
    } catch (RejectedExecutionException rejected) {
      resolveStart(token);
      enqueueControl(() -> onStartFailed(created, token));
    }
  }

  /** 启动决议离开 {@link #pendingStarts} 后必须独立触发终止汇合：fatal 已发生时 owner 回调会被丢弃，不能因此让 termination 悬空。 */
  private void resolveStart(StartToken token) {
    pendingStarts.remove(token);
    tryCompleteTermination();
  }

  private void abandonStart(Session created) {
    for (StartToken token : pendingStarts) {
      if (token.session() == created) {
        token.abandon();
      }
    }
  }

  private void onStartFailed(Session created, StartToken token) {
    pendingStarts.remove(token);
    settleStartFailure(created, null);
  }

  /** 被放弃或迟到的启动：始终先停止 runtime，并把 cleanup 失败传入 session 与重绑失败边界。 */
  private void onAbandonedStart(Session created, TerminalRuntime runtime) {
    runtime.stop();
    runtime
        .termination()
        .whenComplete((ignored, error) -> enqueueControl(() -> settleStartFailure(created, error)));
  }

  private void settleStartFailure(Session created, Throwable terminationError) {
    Throwable cause = unwrap(terminationError);
    created.starting = false;
    created.status = TerminalStatus.FAILED;
    created.exitCode = null;
    created.terminationHandled = true;
    if (cause != null && created.terminationError == null) {
      created.terminationError = cause;
    }
    created.writer.expire();
    if (session == created && !rebinding) {
      emitErrorToObservers(created, ErrorCode.RUNTIME_FAILED, ErrorDisposition.NOT_EXECUTED);
      clearObservers();
    }
    maybeCompleteRebind(created);
    maybeCompleteTermination();
  }

  private void onStarted(Session created, StartToken token, TerminalRuntime runtime) {
    pendingStarts.remove(token);
    if (session != created || closing) {
      // owner 已放弃这个新会话：停止并等待其收敛后才决议重绑/收尾。
      onAbandonedStart(created, runtime);
      return;
    }
    created.runtime = runtime;
    created.starting = false;
    runtime
        .termination()
        .whenComplete(
            (ignored, error) -> enqueueControl(() -> onRuntimeTerminated(created, runtime, error)));
    requestCapture();
  }

  private void onRuntimeTerminated(Session created, TerminalRuntime runtime, Throwable error) {
    if (created.runtime != runtime) {
      return;
    }
    created.terminationHandled = true;
    created.terminationError = unwrap(error);
    created.exitCode = runtime.exitCode();
    if (created.captureFailed) {
      // 画面捕获已失败：按固定 FAILED 收敛，不靠自然 exitCode 模糊解释。
      created.status = TerminalStatus.FAILED;
    } else if (created.terminationError == null && created.exitCode != null) {
      created.status = TerminalStatus.EXITED;
    } else {
      created.status = TerminalStatus.FAILED;
    }
    created.writer.expire();
    if (session == created) {
      emitExitedToObservers(created);
    }
    maybeCompleteRebind(created);
    maybeCompleteTermination();
  }

  private void onOperationResolved(
      Session created,
      TerminalRoute route,
      UUID viewerId,
      UUID epoch,
      long seq,
      OperationDigest digest,
      Throwable error) {
    if (session != created) {
      return;
    }
    Throwable cause = unwrap(error);
    OperationOutcome outcome;
    ErrorCode code;
    if (cause == null) {
      outcome = OperationOutcome.WRITTEN;
      code = null;
    } else if (cause instanceof TerminalRuntime.StaleModeException) {
      outcome = OperationOutcome.NOT_WRITTEN;
      code = ErrorCode.STALE_MODE;
    } else if (cause instanceof TerminalRuntime.OutcomeUnknownException) {
      outcome = OperationOutcome.OUTCOME_UNKNOWN;
      code = ErrorCode.OUTCOME_UNKNOWN;
    } else {
      outcome = OperationOutcome.NOT_WRITTEN;
      code = ErrorCode.RUNTIME_FAILED;
    }
    if (created.writer.complete(epoch, seq, digest, outcome)) {
      emitOpAck(route, viewerId, epoch, AdmissionResult.confirmed(outcome, seq, digest), code);
    }
    if (outcome == OperationOutcome.OUTCOME_UNKNOWN
        && created.runtime != null
        && !created.terminationHandled) {
      created.runtime.stop();
    }
  }

  private void onSnapshot(
      Session created, TerminalRuntime runtime, TerminalView view, Throwable error) {
    snapshotInFlight = false;
    if (session != created || created.runtime != runtime) {
      return;
    }
    if (unwrap(error) != null || view == null) {
      failCapture(created, runtime);
      return;
    }
    for (Observer observer : new ArrayList<>(observers)) {
      if (session != created) {
        return;
      }
      if (!observers.contains(observer)) {
        continue;
      }
      if (observer.pendingAttach && !emitAttached(observer, created, view)) {
        continue;
      }
      if (observer.stream != null && !observer.stream.hasInFlight()) {
        Optional<TerminalViewUpdate> update = observer.stream.offer(view);
        if (update.isPresent()) {
          observer.inflightSinceNanos = clock.getAsLong();
          emitViewUpdate(observer, update.get());
        }
      }
    }
  }

  /**
   * 画面捕获失败：固定 ERROR RUNTIME_FAILED 通告观察者，按 FAILED 收敛且不再读取失败内核。runtime 尚未终止时先停止；已终止且没有保留末屏时
   * 标记会话不可附着，让之后的 ATTACH 拿到固定错误而不是永远 pending。
   */
  private void failCapture(Session created, TerminalRuntime runtime) {
    created.captureFailed = true;
    created.status = TerminalStatus.FAILED;
    emitErrorToObservers(created, ErrorCode.RUNTIME_FAILED, ErrorDisposition.NOT_EXECUTED);
    if (!created.terminationHandled) {
      runtime.stop();
      return;
    }
    created.captureUnavailable = true;
    clearObservers();
  }

  private void requestCapture() {
    if (snapshotInFlight || session == null) {
      return;
    }
    Session created = session;
    TerminalRuntime runtime = created.runtime;
    if (runtime == null || created.captureUnavailable || !needsCapture()) {
      return;
    }
    if (created.captureFailed && !created.terminationHandled) {
      // 内核读取已失败且未终止：不再读取失败内核；终止后 snapshot() 只会返回保留末屏，可以再试一次。
      return;
    }
    snapshotInFlight = true;
    runtime
        .snapshot()
        .whenComplete(
            (view, error) -> enqueueControl(() -> onSnapshot(created, runtime, view, error)));
  }

  private boolean needsCapture() {
    for (Observer observer : observers) {
      if (observer.pendingAttach) {
        return true;
      }
      if (observer.stream != null && !observer.stream.hasInFlight()) {
        return true;
      }
    }
    return false;
  }

  private void onTick() {
    long now = clock.getAsLong();
    if (session != null) {
      // 先发布公开 writer 变化，再扫描撤销：租期到期与空闲撤销同一 tick 冲突时，旁观者仍能看到 null epoch。
      broadcastWriterEpochIfChanged();
      for (int index = observers.size() - 1; index >= 0; index--) {
        Observer observer = observers.get(index);
        if (now - observer.lastActivityNanos >= IDLE_REVOKE_NANOS) {
          removeObserver(observer);
          continue;
        }
        if (observer.stream != null
            && observer.stream.hasInFlight()
            && now - observer.inflightSinceNanos >= VIEW_APPLIED_TIMEOUT_NANOS) {
          removeObserver(observer);
        }
      }
    }
    requestCapture();
  }

  private void broadcastWriterEpochIfChanged() {
    UUID epoch = session.writer.state().writerEpoch();
    if (lastBroadcastEpochKnown && Objects.equals(epoch, lastBroadcastEpoch)) {
      return;
    }
    lastBroadcastEpoch = epoch;
    lastBroadcastEpochKnown = true;
    for (Observer observer : new ArrayList<>(observers)) {
      emit(
          observer.route,
          new TerminalResponse(
              observer.route,
              new TerminalEvent(
                  null,
                  environmentId,
                  observer.viewerId,
                  new TerminalIdentity(daemonInstanceId, session.terminalId),
                  new TerminalEvent.WriterChanged(session.writer.state(), null))));
    }
  }

  private Observer ensureObserver(TerminalRoute route, UUID viewerId) {
    Observer existing = observerFor(route, viewerId);
    if (existing != null) {
      return existing;
    }
    if (observers.size() >= MAX_OBSERVERS) {
      return null;
    }
    Observer created = new Observer(route, viewerId);
    observers.add(created);
    return created;
  }

  private Observer observerFor(TerminalRoute route, UUID viewerId) {
    for (Observer observer : observers) {
      if (observer.route.equals(route) && observer.viewerId.equals(viewerId)) {
        return observer;
      }
    }
    return null;
  }

  private Observer observerFor(TerminalRoute route, UUID viewerId, UUID streamId) {
    Observer observer = observerFor(route, viewerId);
    if (observer != null && observer.streamId != null && observer.streamId.equals(streamId)) {
      return observer;
    }
    return null;
  }

  private boolean replayAttach(Observer observer, UUID requestId, AttachSignature signature) {
    if (observer.lastRequestId == null || !observer.lastRequestId.equals(requestId)) {
      return false;
    }
    if (!signature.equals(observer.lastSignature)) {
      emitConflict(observer);
      return true;
    }
    if (observer.lastResult != null) {
      emit(observer.route, observer.lastResult);
      return true;
    }
    // 结果尚未生成（启动或首个 snapshot 在途）：命中已处理 slot，不旋转流、不发送伪 ATTACHED。
    return true;
  }

  private void emitConflict(Observer observer) {
    TerminalEvent event =
        new TerminalEvent(
            observer.lastRequestId,
            environmentId,
            observer.viewerId,
            session == null ? null : new TerminalIdentity(daemonInstanceId, session.terminalId),
            new TerminalEvent.ErrorPayload(
                ErrorCode.REQUEST_CONFLICT, ErrorDisposition.NOT_EXECUTED));
    emit(observer.route, new TerminalResponse(observer.route, event));
  }

  private void resetObserverStream(
      Observer observer, Session created, UUID requestId, AttachSignature signature) {
    if (observer.stream != null) {
      observer.stream.close();
    }
    observer.streamId = UUID.randomUUID();
    observer.stream = new TerminalViewStream(created.terminalId, observer.streamId);
    observer.pendingAttach = true;
    observer.pendingAttachRequestId = requestId;
    observer.lastActivityNanos = clock.getAsLong();
    observer.lastRequestId = requestId;
    observer.lastSignature = signature;
    observer.lastResult = null;
    requestCapture();
  }

  private boolean emitAttached(Observer observer, Session created, TerminalView view) {
    TerminalEvent event =
        new TerminalEvent(
            observer.pendingAttachRequestId,
            environmentId,
            observer.viewerId,
            new TerminalIdentity(daemonInstanceId, created.terminalId),
            new TerminalEvent.Attached(
                observer.streamId,
                launchSpec.executable(),
                created.status,
                created.exitCode,
                view.inputModeRevision(),
                created.writer.state()));
    TerminalResponse response = new TerminalResponse(observer.route, event);
    if (emit(observer.route, response)) {
      observer.pendingAttach = false;
      observer.pendingAttachRequestId = null;
      observer.lastResult = response;
      return true;
    }
    return false;
  }

  private boolean requireRunningControl(TerminalRequest request, Observer observer) {
    Session current = session;
    if (current == null || current.status != TerminalStatus.RUNNING) {
      emitError(request, ErrorCode.BUSY, ErrorDisposition.NOT_EXECUTED);
      return false;
    }
    if (!observer.stream.isApplied()) {
      emitError(request, ErrorCode.VIEW_NOT_APPLIED, ErrorDisposition.NOT_EXECUTED);
      return false;
    }
    return true;
  }

  private boolean checkIdentity(TerminalRequest request, TerminalIdentity identity) {
    if (!identity.daemonInstanceId().equals(daemonInstanceId)) {
      emitError(request, ErrorCode.DAEMON_MISMATCH, ErrorDisposition.NOT_EXECUTED);
      return false;
    }
    Session current = session;
    if (current == null || !identity.terminalId().equals(current.terminalId)) {
      emitError(request, ErrorCode.TERMINAL_NOT_FOUND, ErrorDisposition.NOT_EXECUTED);
      return false;
    }
    return true;
  }

  // ------------------------------------------------------------------ 出站

  private boolean emit(TerminalRoute route, TerminalResponse response) {
    try {
      emitter.accept(response);
      return true;
    } catch (RuntimeException error) {
      removeObserversForRoute(route);
      return false;
    }
  }

  private void emitError(TerminalRequest request, ErrorCode code, ErrorDisposition disposition) {
    TerminalEvent event =
        new TerminalEvent(
            request.command().requestId(),
            environmentId,
            request.command().viewerId(),
            session == null ? null : new TerminalIdentity(daemonInstanceId, session.terminalId),
            new TerminalEvent.ErrorPayload(code, disposition));
    emit(request.route(), new TerminalResponse(request.route(), event));
  }

  private void emitErrorToObservers(Session created, ErrorCode code, ErrorDisposition disposition) {
    for (Observer observer : new ArrayList<>(observers)) {
      TerminalEvent event =
          new TerminalEvent(
              null,
              environmentId,
              observer.viewerId,
              new TerminalIdentity(daemonInstanceId, created.terminalId),
              new TerminalEvent.ErrorPayload(code, disposition));
      emit(observer.route, new TerminalResponse(observer.route, event));
    }
  }

  private void emitExitedToObservers(Session created) {
    for (Observer observer : new ArrayList<>(observers)) {
      TerminalEvent event =
          new TerminalEvent(
              null,
              environmentId,
              observer.viewerId,
              new TerminalIdentity(daemonInstanceId, created.terminalId),
              new TerminalEvent.Exited(created.status, created.exitCode));
      emit(observer.route, new TerminalResponse(observer.route, event));
    }
  }

  private void emitViewUpdate(Observer observer, TerminalViewUpdate update) {
    TerminalEvent event =
        new TerminalEvent(
            null,
            environmentId,
            observer.viewerId,
            new TerminalIdentity(daemonInstanceId, update.terminalId()),
            new TerminalEvent.ViewUpdate(update));
    emit(observer.route, new TerminalResponse(observer.route, event));
  }

  private void emitOpAck(
      TerminalRoute route,
      UUID viewerId,
      UUID writerEpoch,
      AdmissionResult result,
      ErrorCode code) {
    Session current = session;
    TerminalEvent event =
        new TerminalEvent(
            null,
            environmentId,
            viewerId,
            new TerminalIdentity(daemonInstanceId, current.terminalId),
            new TerminalEvent.OpAck(writerEpoch, result, code));
    emit(route, new TerminalResponse(route, event));
  }

  private void emitControlResult(
      TerminalRequest request, Session current, ControlResult result, boolean broadcast) {
    if (broadcast) {
      for (Observer observer : new ArrayList<>(observers)) {
        TerminalEvent event =
            new TerminalEvent(
                null,
                environmentId,
                observer.viewerId,
                new TerminalIdentity(daemonInstanceId, current.terminalId),
                new TerminalEvent.WriterChanged(current.writer.state(), null));
        emit(observer.route, new TerminalResponse(observer.route, event));
      }
      lastBroadcastEpoch = current.writer.state().writerEpoch();
      lastBroadcastEpochKnown = true;
    }
    TerminalEvent direct =
        new TerminalEvent(
            request.command().requestId(),
            environmentId,
            request.command().viewerId(),
            new TerminalIdentity(daemonInstanceId, current.terminalId),
            new TerminalEvent.WriterChanged(current.writer.state(), result));
    emit(request.route(), new TerminalResponse(request.route(), direct));
  }

  // ------------------------------------------------------------------ 辅助

  private WriterOwner writerOwner(TerminalRequest request) {
    return new WriterOwner(
        request.route().appNodeId(), request.route().connectionId(), request.command().viewerId());
  }

  private void refreshAdmission() {
    admission.set(new Admission(environmentId, !closing));
  }

  private boolean startAllowed(UUID startEnvironment) {
    Admission current = admission.get();
    return current.admitting() && startEnvironment.equals(current.environment());
  }

  private void clearObservers() {
    for (Observer observer : new ArrayList<>(observers)) {
      removeObserver(observer);
    }
  }

  private void removeObserver(Observer observer) {
    observers.remove(observer);
    releaseObserver(observer);
  }

  /** 释放单个观察者的流与重放引用；调用方负责从 {@link #observers} 移除。 */
  private void releaseObserver(Observer observer) {
    if (observer.stream != null) {
      observer.stream.close();
      observer.stream = null;
    }
    observer.pendingAttach = false;
    observer.pendingAttachRequestId = null;
    observer.streamId = null;
    observer.lastRequestId = null;
    observer.lastSignature = null;
    observer.lastResult = null;
  }

  private void removeObserversForRoute(TerminalRoute route) {
    for (Observer observer : new ArrayList<>(observers)) {
      if (observer.route.equals(route)) {
        removeObserver(observer);
      }
    }
  }

  /** fatal 排他清理：释放全部观察者的流与重放引用并清空列表。只在围栏 drain 之后调用，不与 owner 状态变更并发。 */
  private void releaseObserverState() {
    for (Observer observer : new ArrayList<>(observers)) {
      releaseObserver(observer);
    }
    observers.clear();
  }

  /** 公开 writer 状态是否发生变化（授予新 epoch 或释放），用于决定是否广播 WRITER_CHANGED。 */
  private static boolean changedWriter(ControlResult result) {
    return result.isGranted() || result.status() == ControlResult.Status.RELEASED;
  }

  private static ErrorCode codeForAdmission(AdmissionResult admission) {
    if (admission.kind() != AdmissionResult.Kind.REJECTED || admission.reason() == null) {
      return null;
    }
    return switch (admission.reason()) {
      case INVALID -> ErrorCode.INVALID_REQUEST;
      case SEQ_CONFLICT, SEQ_GAP, UNVERIFIABLE -> ErrorCode.REQUEST_CONFLICT;
      case NOT_OWNER, LEASE_EXPIRED, FROZEN -> ErrorCode.BUSY;
    };
  }

  private static Throwable unwrap(Throwable error) {
    if (error instanceof CompletionException completion && completion.getCause() != null) {
      return completion.getCause();
    }
    return error;
  }

  private static int accountBytes(TerminalCommand command) {
    int input = command.payload() instanceof TerminalCommand.Input in ? in.bytes().length : 0;
    return METADATA_BYTES + input;
  }

  private static TerminalRuntime startRuntime(
      TerminalLaunchSpec spec,
      int columns,
      int rows,
      int maxHistoryLines,
      Map<String, String> environment,
      ProcessScope.StartGate gate,
      ExecutorService vtExecutor,
      ExecutorService ioExecutor,
      ScheduledExecutorService scheduler)
      throws IOException {
    return TerminalRuntime.start(
        spec, columns, rows, maxHistoryLines, environment, gate, vtExecutor, ioExecutor, scheduler);
  }

  // ------------------------------------------------------------------ 内部类型

  /** 生产/测试注入的最小 Runtime 工厂；只返回真实 {@link TerminalRuntime}，不引入第二份 PTY 生命周期。 */
  @FunctionalInterface
  interface RuntimeFactory {

    TerminalRuntime create(
        TerminalLaunchSpec spec,
        int columns,
        int rows,
        int maxHistoryLines,
        Map<String, String> environment,
        ProcessScope.StartGate gate,
        ExecutorService vtExecutor,
        ExecutorService ioExecutor,
        ScheduledExecutorService scheduler)
        throws IOException;
  }

  private record Envelope(
      int bytes, CompletableFuture<Void> done, Consumer<CompletableFuture<Void>> action) {}

  private record Rebind(Session session, CompletableFuture<Void> done) {}

  private record Admission(UUID environment, boolean admitting) {}

  private record AttachSignature(
      TerminalCommand.Type type, TerminalIdentity identity, TerminalIdentity expectedExited) {}

  /** 一次启动的跨线程句柄：owner 放弃后必须停止已返回的 runtime，不丢资源完成信号。 */
  private final class StartToken {

    private final Session session;
    private boolean abandoned;
    private TerminalRuntime runtime;

    private StartToken(Session session) {
      this.session = session;
    }

    private Session session() {
      return session;
    }

    private synchronized boolean handIn(TerminalRuntime value) {
      if (abandoned) {
        return false;
      }
      runtime = value;
      return true;
    }

    private void abandon() {
      TerminalRuntime stopped;
      synchronized (this) {
        abandoned = true;
        stopped = runtime;
      }
      if (stopped != null) {
        stopped.stop();
      }
    }
  }

  /** 单个观察连接：一个真实 route+viewer、其当前流与有界重放槽。 */
  private final class Observer {

    private final TerminalRoute route;
    private final UUID viewerId;
    private UUID streamId;
    private TerminalViewStream stream;
    private boolean pendingAttach;
    private UUID pendingAttachRequestId;
    private long lastActivityNanos;
    private long inflightSinceNanos;
    private UUID lastRequestId;
    private AttachSignature lastSignature;
    private TerminalResponse lastResult;

    private Observer(TerminalRoute route, UUID viewerId) {
      this.route = route;
      this.viewerId = viewerId;
    }
  }

  /** 单个终端的 runtime/writer 与生命周期状态。 */
  private final class Session {

    private final UUID terminalId;
    private final TerminalWriter writer;
    private TerminalRuntime runtime;
    private boolean starting;
    private TerminalStatus status = TerminalStatus.RUNNING;
    private Integer exitCode;
    private boolean terminationHandled;
    private Throwable terminationError;
    private boolean captureFailed;
    private boolean captureUnavailable;

    private Session(UUID terminalId, TerminalWriter writer) {
      this.terminalId = terminalId;
      this.writer = writer;
    }
  }

  /** mailbox 已满：请求未被受理，已收请求不受影响。 */
  public static final class MailboxOverflowException extends IllegalStateException {

    MailboxOverflowException() {
      super(MAILBOX_OVERFLOW_MESSAGE);
    }
  }

  /** 协调器已关闭或已失败：请求未被受理。 */
  public static final class CoordinatorClosedException extends IllegalStateException {

    CoordinatorClosedException() {
      super(COORDINATOR_CLOSED_MESSAGE);
    }
  }
}
