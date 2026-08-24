package fun.fengwk.kkstudio.platform.harness.model;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.runtime.admission.ConcurrencyAdmission;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.port.ModelGateway;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy;
import java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy;
import java.util.concurrent.ThreadPoolExecutor.DiscardPolicy;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 以 PostgreSQL provider 资源为支撑的生产 {@link ModelGateway} 适配器。
 *
 * <p>{@code start} 通过 {@link ProviderResolutionService} 同步解析冻结 {@link
 * fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType} 与内存 {@link ProviderRequest}，并向共享
 * 虚拟线程 executor 提交一个 transport 任务。任务按配置的 timeout 策略打开一个 {@link ModelProvider}， 并把 SDK 流桥接到 Runtime
 * listener；gateway 绝不读写 HarnessStore。
 *
 * <p>Admission 分类：确定性解析失败（{@link IllegalArgumentException}）返回携带 {@link
 * ProviderErrorKind#INVALID_REQUEST} 的 {@link Rejected}，因为执行可证明从未被提交、 重试也无济于事；其他解析失败原样传播，由
 * Processor 重新调度该次尝试。executor 拒绝提交返回携带 配置延迟的 {@link Busy}；提交抛出意外异常时返回 {@link Indeterminate}，因为
 * gateway 无法再证明 transport 任务是否已启动。
 *
 * <p>两阶段激活：{@code start} 绝不打开回调 gate。transport 任务首先等待 admission gate，该 gate 只在 Processor 附加 handle
 * 并把 invocation 持久化标记为 RUNNING 之后由 {@link ModelGateway.Handle#activate()} 打开——即使 Provider SDK 在
 * {@code stream} 内同步调用 handler，也无法与调用方竞争；激活前发出的 {@code Handle#cancel()} 会唤醒等待中的任务并使其中止，且绝不触碰
 * Provider。等待期间被中断（而非取消）的 任务会向 {@code activate()} 延迟恰好一次 UNKNOWN——已接受、可能 RUNNING 的执行绝不停摆到 lease 恢复，
 * 且任何回调都不会在 {@code start} 返回前投递。内联运行任务的 executor（direct executor 或 {@code
 * CallerRunsPolicy}）或静默丢弃被拒任务（{@code DiscardPolicy} / {@code DiscardOldestPolicy}） 在构造时被拒绝，因为 gate
 * 会死锁或拒绝永远不会浮出水面。
 *
 * <p>回调分类：分类过的 {@link ProviderException} 连同其 kind 通过 {@code onFailed} 投递；null 的
 * provider/stream/event/response payload 与 adapter 装配错误是确定性 {@code INVALID_REQUEST} 失败； 未分类的
 * transport 失败或抛异常的 listener 通过 {@code onUnknown} 投递，因为持久化结果无法确认。 terminal 回调至多投递一次，terminal
 * 之后的迟到增量一律忽略。{@link ModelGateway.Handle#cancel()} 在 pre-task、pre-bind 与 post-bind 各窗口内幂等且
 * best-effort。
 *
 * <p>回调桥是串行 FIFO 单 drainer 状态机（与 Tool 桥同构）：桥 monitor 只保护 queue/dispatch/terminal 状态，持有它时绝不调用 {@code
 * bindStream}（可能触发 {@code ProviderStream.cancel}）、{@code cancel} 或任何 Runtime listener 方法——Provider
 * 回调与内部报告只入队 typed signal，恰好一个 drainer 在锁外 按 FIFO 处理它们，因此重入或派生回调线程的 cancel 绝不会死锁。第一个 terminal
 * 信号获胜；迟到/重复信号在 任何 listener 工作前被清除。非 terminal 的 {@code onEvent} listener 失败可以选择第一个 UNKNOWN；
 * terminal listener 异常只记录日志，绝不产生第二个 terminal 回调。队列有上限 （{@value #MAX_BUFFERED_SIGNALS}
 * 个信号）；溢出确定性选择恰好一次 UNKNOWN。
 */
@Slf4j
public final class PlatformModelGateway implements ModelGateway {

  /** 回调桥缓冲队列的保守上限：adversarial Provider 同步回调绝不能无界缓冲。 */
  static final int MAX_BUFFERED_SIGNALS = 256;

  private final ProviderResolutionService providerResolution;
  private final ExecutorService executor;
  private final Supplier<Duration> busyRetryDelay;
  private final ConcurrencyAdmission admission;

  /**
   * 生产与测试共用的唯一构造器。{@code busyRetryDelay} 在每次 {@code Busy} 判定时现读，由装配方决定来源——生产装配传入
   * SystemSettingsSnapshot 的 live supplier（每次 start 从 {@code tool.modelGatewayBusyRetryMillis}
   * 现读）。admission 必须由装配方或测试显式提供， 不允许以无界容量绕过执行上限。
   */
  PlatformModelGateway(
      ProviderResolutionService providerResolution,
      ExecutorService executor,
      Supplier<Duration> busyRetryDelay,
      ConcurrencyAdmission admission) {
    this.providerResolution = Objects.requireNonNull(providerResolution, "providerResolution");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.busyRetryDelay = Objects.requireNonNull(busyRetryDelay, "busyRetryDelay");
    this.admission = Objects.requireNonNull(admission, "admission");
    rejectUnsafeExecutorPolicies(executor);
    rejectInlineExecutor(executor);
  }

  @Override
  public StartResult start(Execution execution, Listener listener) {
    Objects.requireNonNull(execution, "execution");
    Objects.requireNonNull(listener, "listener");
    ProviderResolutionService.ResolvedExecution resolved;
    try {
      resolved = providerResolution.resolve(execution.providerType(), execution.request());
    } catch (IllegalArgumentException setupFailure) {
      // 提交前的确定性装配失败：终止 invocation，绝不重试。
      return new Rejected(
          new ModelInvocationError(
              ProviderErrorKind.INVALID_REQUEST,
              message(setupFailure, "cannot resolve provider for model invocation")));
    }
    Optional<ConcurrencyAdmission.Lease> acquired = admission.tryAcquire();
    if (acquired.isEmpty()) {
      return new Busy(busyRetryDelay.get());
    }
    ConcurrencyAdmission.Lease lease = acquired.orElseThrow();
    Listener admittedListener = new ReleasingListener(listener, lease);
    StartGate gate = new StartGate();
    GatewayHandle handle = new GatewayHandle(gate, admittedListener, lease);
    try {
      TransportTask task =
          new TransportTask(resolved, resolved.effectiveRequest(), admittedListener, handle, gate);
      executor.execute(task);
    } catch (RejectedExecutionException rejected) {
      // 任务可证明从未被接受：按配置的延迟返回 Busy。cancel 会唤醒 broken executor 可能已启动的任务，
      // 使其中止且绝不触碰 Provider。
      gate.cancel();
      lease.close();
      return new Busy(busyRetryDelay.get());
    } catch (RuntimeException ambiguous) {
      // broken executor 可能在抛异常前已启动任务：结果未知。
      gate.cancel();
      lease.close();
      return new Indeterminate(
          new ModelInvocationError(
              ProviderErrorKind.TRANSIENT,
              "model execution submission failed; provider outcome cannot be confirmed"));
    }
    // 两阶段激活：Processor 附加 handle 并把 invocation 持久化标记为 RUNNING 之前，admission gate
    // 保持关闭，随后才调用 Handle#activate()。
    return new Started(handle);
  }

  /** 拒绝内联运行 transport 任务或静默丢弃被拒任务的 executor 策略。 */
  private static void rejectUnsafeExecutorPolicies(ExecutorService executor) {
    if (executor instanceof ThreadPoolExecutor threadPool) {
      RejectedExecutionHandler handler = threadPool.getRejectedExecutionHandler();
      if (handler instanceof CallerRunsPolicy) {
        throw new IllegalStateException(
            "model gateway requires a non-inline executor; CallerRunsPolicy is not supported");
      }
      if (handler instanceof DiscardPolicy || handler instanceof DiscardOldestPolicy) {
        throw new IllegalStateException(
            "model gateway requires a rejecting executor; silent discard policies are not supported");
      }
    }
  }

  /**
   * 拒绝任务在 {@code execute} 内同步运行的直接 inline executor；这种 executor 会使 admission gate 死锁。
   * 探测会记录实际运行任务的线程，因此异步 executor 永远不会被误判。
   */
  private static void rejectInlineExecutor(ExecutorService executor) {
    Thread caller = Thread.currentThread();
    AtomicReference<Thread> runner = new AtomicReference<>();
    try {
      executor.execute(() -> runner.set(Thread.currentThread()));
    } catch (RuntimeException ignored) {
      // 拒绝型或已损坏的 executor 不可能内联运行任务；失败由 start() 呈现。
      return;
    }
    if (runner.get() == caller) {
      throw new IllegalStateException("model gateway requires a non-inline executor");
    }
  }

  /**
   * 阻塞 transport 任务直到执行被激活。{@link #open()} 恰好由 {@link ModelGateway.Handle#activate()} 调用；{@link
   * #cancel()} 唤醒并中止激活前被取消的任务（该任务绝不可触碰 Provider）。任一状态变更都会 唤醒所有等待者，因此 cancel-before-activate
   * 的任务绝不会泄漏在停驻线程上。
   */
  static final class StartGate {

    private final Object monitor = new Object();
    private boolean open;
    private boolean cancelled;

    void open() {
      synchronized (monitor) {
        open = true;
        monitor.notifyAll();
      }
    }

    void cancel() {
      synchronized (monitor) {
        cancelled = true;
        monitor.notifyAll();
      }
    }

    /**
     * @return 执行已激活时返回 true；任务在激活前被取消或等待期间被中断时返回 false——被取消的任务必须 静默中止，被中断的任务必须向激活延迟恰好一次 UNKNOWN（见
     *     {@link GatewayHandle}）， 使已接受、可能 RUNNING 的执行绝不停摆到 lease 恢复。
     */
    boolean awaitStartReturned() {
      synchronized (monitor) {
        while (!open && !cancelled) {
          try {
            monitor.wait();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
          }
        }
        return open && !cancelled;
      }
    }
  }

  /**
   * 覆盖 pre-task、pre-bind 与 post-bind 窗口的 best-effort 幂等取消控制。 {@link #activate()} 恰好一次打开 admission
   * gate——或者，当 transport 任务在激活前被中断时， 在不触碰 Provider 的情况下恰好投递一次 UNKNOWN；激活前发出的 cancel 会唤醒等待中的任务，使其不启动
   * Provider 即中止，并丢弃任何延迟的激活失败（cancel-before-activate 保持静默）。 activate / cancel / defer
   * 均幂等且竞争安全：一旦取消，activate 绝不投递或打开 gate，且无论 defer 与 activate 竞争哪一方获胜，延迟的激活失败至多投递一次。
   */
  static final class GatewayHandle implements ModelGateway.Handle {

    private final Listener listener;
    private final StartGate gate;
    private final ConcurrencyAdmission.Lease lease;
    private boolean activated;
    private boolean cancelled;
    private boolean cancellationDelivered;
    private boolean activationFailureDelivered;
    private ProviderStream boundStream;
    private ModelInvocationError deferredFailure;

    GatewayHandle(StartGate gate, Listener listener) {
      this(gate, listener, () -> {});
    }

    GatewayHandle(StartGate gate, Listener listener, ConcurrencyAdmission.Lease lease) {
      this.gate = Objects.requireNonNull(gate, "gate");
      this.listener = Objects.requireNonNull(listener, "listener");
      this.lease = Objects.requireNonNull(lease, "lease");
    }

    /**
     * 等待期被中断的任务把无法确认的结果延迟到激活时恰好一次 UNKNOWN——回调绝不发生在 {@code start()} 返回之前。若 activate 已先赢 （任务在 defer
     * 前被激活：gate 已开、任务已死、Provider 从未启动），立即恰好一次 UNKNOWN；已取消则保持静默；已投递过的延迟 失败绝不二次投递（terminal-once）。
     */
    void deferActivationFailure(ModelInvocationError error) {
      Listener target = null;
      ModelInvocationError failure = null;
      synchronized (this) {
        if (cancelled || activationFailureDelivered) {
          return;
        }
        if (activated) {
          // activate 先赢：立即恰好一次 UNKNOWN（绝不打开 gate——任务已死，无需再开；Provider 从未被触碰）。
          activationFailureDelivered = true;
          target = listener;
          failure = error;
        } else {
          deferredFailure = error;
        }
      }
      if (target != null) {
        target.onUnknown(failure);
      }
    }

    @Override
    public void activate() {
      Listener target = null;
      ModelInvocationError failure = null;
      synchronized (this) {
        if (activated || cancelled) {
          return;
        }
        activated = true;
        if (deferredFailure != null) {
          activationFailureDelivered = true;
          target = listener;
          failure = deferredFailure;
          deferredFailure = null;
        }
      }
      if (target != null) {
        // 等待期中断：执行已接受但 Provider 从未启动，结果无法确认——恰好一次 UNKNOWN；绝不打开 gate、绝不触碰 Provider。
        target.onUnknown(failure);
        return;
      }
      gate.open();
    }

    @Override
    public void cancel() {
      boolean cancelGate = false;
      ProviderStream target;
      synchronized (this) {
        if (!activated && !cancelled) {
          cancelGate = true;
        }
        if (cancelled) {
          return;
        }
        cancelled = true;
        deferredFailure = null;
        target = cancellationTarget();
      }
      try {
        if (cancelGate) {
          // 激活前取消：唤醒等待的 transport task 使其中止，绝不启动 Provider、不泄漏等待线程。
          gate.cancel();
        }
        if (target != null) {
          target.cancel();
        }
      } finally {
        lease.close();
      }
    }

    boolean isCancelled() {
      synchronized (this) {
        return cancelled;
      }
    }

    void bindStream(ProviderStream stream) {
      Objects.requireNonNull(stream, "stream");
      ProviderStream cancelTarget;
      synchronized (this) {
        if (boundStream == null) {
          boundStream = stream;
        } else if (boundStream != stream) {
          throw new IllegalArgumentException("provider callback returned inconsistent streams");
        }
        cancelTarget = cancellationTarget();
      }
      if (cancelTarget != null) {
        try {
          cancelTarget.cancel();
        } catch (RuntimeException ignored) {
          // 延迟绑定的自动取消绝不可破坏 transport 任务。
        }
      }
    }

    private ProviderStream cancellationTarget() {
      if (!cancelled || boundStream == null || cancellationDelivered) {
        return null;
      }
      cancellationDelivered = true;
      return boundStream;
    }
  }

  /** terminal 回调与 handle cancel 共用同一个幂等 lease，避免 listener 异常造成容量泄漏。 */
  private static final class ReleasingListener implements Listener {

    private final Listener delegate;
    private final ConcurrencyAdmission.Lease lease;

    private ReleasingListener(Listener delegate, ConcurrencyAdmission.Lease lease) {
      this.delegate = Objects.requireNonNull(delegate, "delegate");
      this.lease = Objects.requireNonNull(lease, "lease");
    }

    @Override
    public void onEvent(ProviderStreamEvent event) {
      delegate.onEvent(event);
    }

    @Override
    public void onSucceeded(ProviderResponse response) {
      try {
        delegate.onSucceeded(response);
      } finally {
        lease.close();
      }
    }

    @Override
    public void onFailed(ModelInvocationError error) {
      try {
        delegate.onFailed(error);
      } finally {
        lease.close();
      }
    }

    @Override
    public void onUnknown(ModelInvocationError error) {
      try {
        delegate.onUnknown(error);
      } finally {
        lease.close();
      }
    }
  }

  /** 在 admission 之后运行 Provider I/O；负责装配与 transport 失败的分类。 */
  private final class TransportTask implements Runnable {

    private final ProviderResolutionService.ResolvedExecution resolved;
    private final ProviderRequest request;
    private final Listener listener;
    private final GatewayHandle handle;
    private final StartGate gate;

    TransportTask(
        ProviderResolutionService.ResolvedExecution resolved,
        ProviderRequest request,
        Listener listener,
        GatewayHandle handle,
        StartGate gate) {
      this.resolved = Objects.requireNonNull(resolved, "resolved");
      this.request = Objects.requireNonNull(request, "request");
      this.listener = Objects.requireNonNull(listener, "listener");
      this.handle = Objects.requireNonNull(handle, "handle");
      this.gate = Objects.requireNonNull(gate, "gate");
    }

    @Override
    public void run() {
      if (!gate.awaitStartReturned()) {
        // 中断而非取消：执行已被接受（Processor 可能已 markRunning），任务绝不能静默消失——延迟一个 UNKNOWN 到
        // activate() 时恰好一次投递，绝不回调在 start() 返回前发生；cancel-before-activate 保持静默。
        if (!handle.isCancelled()) {
          handle.deferActivationFailure(
              new ModelInvocationError(
                  ProviderErrorKind.TRANSIENT,
                  "model gateway transport task was interrupted before activation; provider outcome cannot be confirmed"));
        }
        return;
      }
      if (handle.isCancelled()) {
        return;
      }
      BridgingHandler bridge = new BridgingHandler(listener, handle);
      try {
        runTransport(bridge);
      } catch (RuntimeException unexpected) {
        // 已接受执行上的意外异常（含 adversarial getMessage）：收敛恰好一次 UNKNOWN（terminal-once），绝不静默消失。
        bridge.reportUnknown(
            "model gateway transport failed unexpectedly; provider outcome cannot be confirmed: "
                + message(unexpected, "unexpected failure"));
      }
    }

    private void runTransport(BridgingHandler bridge) {
      ModelProvider provider;
      try {
        provider = resolved.openProvider(resolved.timeoutPolicy());
      } catch (RuntimeException setupFailure) {
        bridge.reportInvalidRequest(
            "cannot create provider for model invocation: "
                + message(setupFailure, "setup failure"));
        return;
      }
      if (handle.isCancelled()) {
        return;
      }
      ProviderStream stream;
      try {
        stream = provider.stream(request, bridge);
      } catch (ProviderException classified) {
        bridge.reportFailed(classified);
        return;
      } catch (RuntimeException unclassified) {
        bridge.reportUnknown(
            "provider stream failed with an unclassified error; outcome cannot be confirmed: "
                + message(unclassified, "unclassified error"));
        return;
      }
      if (stream == null) {
        bridge.reportInvalidRequest("provider returned a null stream");
        return;
      }
      try {
        handle.bindStream(stream);
      } catch (RuntimeException failure) {
        bridge.reportInvalidRequest(
            "invalid provider stream handle: " + message(failure, "bind failure"));
      }
    }
  }

  /**
   * 桥接 SDK {@link ProviderStreamHandler} 到 Runtime listener：串行 FIFO 单 drainer 状态机（与 Tool 桥同构）。
   *
   * <p>桥 monitor 只保护 queue / dispatch / terminal 状态，绝不持有它调用外部代码：Provider 回调（onEvent / onComplete /
   * onError）与内部 reportInvalidRequest / reportFailed / reportUnknown 都只入队 typed signal；恰好一个 drainer
   * 在 monitor 外按 FIFO 处理信号——bind stream（可能触发 {@link ProviderStream#cancel} 的同步或异步回调，回调线程只短暂 获取
   * monitor 入队即可完成，绝不死锁）、分类、投递。第一个 terminal 信号获胜：terminal 后队列被清空、迟到 / 重复信号在 listener 工作前被忽略。非
   * terminal 的 onEvent listener 失败允许选择第一个 terminal UNKNOWN；terminal listener 抛异常只记录 日志，绝不发出第二个
   * terminal 回调。缓冲有界（≤{@value PlatformModelGateway#MAX_BUFFERED_SIGNALS}），溢出即清空缓冲并确定性收敛 恰好一次
   * UNKNOWN。
   */
  private static final class BridgingHandler implements ProviderStreamHandler {

    private final Listener listener;
    private final GatewayHandle handle;
    private final Object monitor = new Object();
    private final ArrayDeque<Signal> queue = new ArrayDeque<>();
    private boolean dispatching;
    private boolean terminal;
    private boolean overflowed;

    BridgingHandler(Listener listener, GatewayHandle handle) {
      this.listener = Objects.requireNonNull(listener, "listener");
      this.handle = Objects.requireNonNull(handle, "handle");
    }

    @Override
    public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
      if (event == null) {
        enqueue(new Signal.Invalid("provider returned a null stream event"));
        return;
      }
      enqueue(new Signal.Event(event, stream));
    }

    @Override
    public void onComplete(ProviderResponse response, ProviderStream stream) {
      if (response == null) {
        enqueue(new Signal.Invalid("provider returned a null response"));
        return;
      }
      enqueue(new Signal.Complete(response, stream));
    }

    @Override
    public void onError(ProviderException error, ProviderStream stream) {
      if (error == null) {
        enqueue(new Signal.Invalid("provider returned a null failure"));
        return;
      }
      enqueue(new Signal.Error(error, stream));
    }

    /** 内部 reportInvalidRequest：与 Provider 回调同一 FIFO / terminal-once 语义入队。 */
    private void reportInvalidRequest(String message) {
      enqueue(new Signal.Invalid(message));
    }

    /** 内部 reportFailed：与 Provider 回调同一 FIFO / terminal-once 语义入队。 */
    private void reportFailed(ProviderException error) {
      enqueue(new Signal.Failed(error));
    }

    /** 内部 reportUnknown：与 Provider 回调同一 FIFO / terminal-once 语义入队。 */
    private void reportUnknown(String message) {
      enqueue(new Signal.Unknown(message));
    }

    /**
     * 入队并（必要时）成为 drainer：monitor 内只改 queue / dispatching / terminal / overflowed 状态，绝不调用外部代码。
     * terminal 之后的迟到 / 重复信号一律忽略；溢出即清空缓冲并标记，drainer 确定性选择恰好一次 UNKNOWN。
     */
    private void enqueue(Signal signal) {
      boolean dispatch = false;
      synchronized (monitor) {
        if (terminal || overflowed) {
          // 迟到 / 重复信号：terminal 或溢出之后一律忽略。
          return;
        }
        if (queue.size() >= MAX_BUFFERED_SIGNALS) {
          // 保守上界：缓冲无界增长时结果已不可信——清空缓冲并标记溢出，drainer 确定性选择恰好一次 UNKNOWN。
          overflowed = true;
          queue.clear();
          if (!dispatching) {
            dispatching = true;
            dispatch = true;
          }
          return;
        }
        queue.add(signal);
        if (!dispatching) {
          dispatching = true;
          dispatch = true;
        }
      }
      if (dispatch) {
        dispatchLoop();
      }
    }

    /** 单一分发循环：每次恰好一个线程处理，FIFO 顺序；terminal 信号处理后清空队列；任何意外异常收敛恰好一次 UNKNOWN。 */
    private void dispatchLoop() {
      while (true) {
        Signal signal;
        synchronized (monitor) {
          signal = queue.poll();
          if (signal == null && !overflowed) {
            dispatching = false;
            return;
          }
        }
        boolean terminalSignal;
        if (signal == null) {
          // 缓冲溢出：确定性选择恰好一次 UNKNOWN（fire-once；绝无第二个 terminal）。
          terminalSignal =
              unknown(
                  "model gateway callback buffer exceeded "
                      + MAX_BUFFERED_SIGNALS
                      + " signals; provider outcome cannot be confirmed");
        } else {
          try {
            terminalSignal = process(signal);
          } catch (RuntimeException failure) {
            // 分发循环内意外异常（如 adversarial getMessage）：收敛恰好一次 UNKNOWN，绝不让 dispatching 卡死。
            terminalSignal =
                unknown(
                    "model gateway callback dispatch failed; outcome cannot be confirmed: "
                        + message(failure, "unexpected failure"));
          }
        }
        if (terminalSignal) {
          synchronized (monitor) {
            terminal = true;
            queue.clear();
            dispatching = false;
          }
          return;
        }
      }
    }

    /**
     * @return true 表示该信号已使桥 terminal（后续信号一律忽略）。
     */
    private boolean process(Signal signal) {
      return switch (signal) {
        case Signal.Event event -> processEvent(event);
        case Signal.Complete complete -> processComplete(complete);
        case Signal.Error error -> processError(error);
        case Signal.Invalid invalid -> fail(invalid.message());
        case Signal.Failed failed -> fail(failed.error());
        case Signal.Unknown unknown -> unknown(unknown.message());
      };
    }

    private boolean processEvent(Signal.Event signal) {
      if (!bind(signal.stream())) {
        return true;
      }
      // 只有非 terminal 的 onEvent 失败才允许选择第一个 terminal UNKNOWN。
      return deliverEventOrUnknown(() -> listener.onEvent(signal.event()));
    }

    private boolean processComplete(Signal.Complete signal) {
      if (!bind(signal.stream())) {
        return true;
      }
      // terminal 选择已经发生：listener 拒绝只记录，绝不发出第二个 terminal 回调。
      deliverTerminal(() -> listener.onSucceeded(signal.response()));
      return true;
    }

    private boolean processError(Signal.Error signal) {
      if (!bind(signal.stream())) {
        return true;
      }
      // 先构造 terminal payload（转换失败由分发循环守卫收敛 UNKNOWN）：terminal 回调绝不因 payload 构造异常而消失。
      ModelInvocationError payload = toInvocationError(signal.error());
      deliverTerminal(() -> listener.onFailed(payload));
      return true;
    }

    /**
     * 在 monitor 外绑定 stream（可能触发 {@link ProviderStream#cancel} 的同步/异步回调——回调线程只短暂获取 monitor
     * 入队即可完成，绝不死锁）。
     *
     * @return true 表示绑定成功、可继续投递；false 表示该信号已使桥 terminal（null stream / bind 失败选择了 INVALID_REQUEST）。
     */
    private boolean bind(ProviderStream stream) {
      if (stream == null) {
        return fail("provider callback returned a null stream");
      }
      try {
        handle.bindStream(stream);
        return true;
      } catch (RuntimeException failure) {
        return fail("invalid provider stream handle");
      }
    }

    private boolean fail(String message) {
      // terminal 选择已经发生：listener 拒绝只记录，绝不发出第二个 terminal 回调。
      deliverTerminal(
          () ->
              listener.onFailed(
                  new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, message)));
      return true;
    }

    private boolean fail(ProviderException error) {
      // 先构造 terminal payload：转换失败由分发循环守卫收敛 UNKNOWN，绝不留下已 claim 却未投递的 terminal。
      ModelInvocationError payload = toInvocationError(error);
      deliverTerminal(() -> listener.onFailed(payload));
      return true;
    }

    private boolean unknown(String message) {
      deliverTerminal(
          () -> listener.onUnknown(new ModelInvocationError(ProviderErrorKind.TRANSIENT, message)));
      return true;
    }

    /**
     * 投递一次非 terminal 回调（仅 onEvent）：listener 抛异常 ⇒ 已接受执行上的内部失败，选择第一个 terminal UNKNOWN （至多一次；该
     * UNKNOWN 的投递仍 fire-once）。
     */
    private boolean deliverEventOrUnknown(Runnable callback) {
      try {
        callback.run();
        return false;
      } catch (RuntimeException failure) {
        return unknown(
            "listener failed to process provider event; outcome cannot be confirmed: "
                + message(failure, "listener failure"));
      }
    }

    /**
     * 投递 terminal 回调（fire-once）：恰好调用一次。listener 抛异常只记录日志，绝不发出第二个 terminal 回调——terminal
     * 选择已经发生，结果（Succeeded / Failed / Unknown）是确定的，不得用 UNKNOWN 覆盖。
     */
    private void deliverTerminal(Runnable callback) {
      try {
        callback.run();
      } catch (RuntimeException failure) {
        // terminal 已 fire-once；日志也必须 best effort，恶意 Throwable 渲染不得逃逸到
        // dispatchLoop 后触发第二个 UNKNOWN。
        try {
          log.warn(
              "model gateway listener threw while accepting a terminal callback for {}: {}; "
                  + "no further terminal will be issued",
              listener.getClass().getSimpleName(),
              message(failure, "listener failure"));
        } catch (RuntimeException ignored) {
          // 状态已 terminal；日志失败不得改变协议结果。
        }
      }
    }
  }

  /** 桥队列中的一次回调信号（FIFO 顺序即到达顺序）。 */
  private sealed interface Signal
      permits Signal.Event,
          Signal.Complete,
          Signal.Error,
          Signal.Invalid,
          Signal.Failed,
          Signal.Unknown {

    record Event(ProviderStreamEvent event, ProviderStream stream) implements Signal {}

    record Complete(ProviderResponse response, ProviderStream stream) implements Signal {}

    record Error(ProviderException error, ProviderStream stream) implements Signal {}

    record Invalid(String message) implements Signal {}

    record Failed(ProviderException error) implements Signal {}

    record Unknown(String message) implements Signal {}
  }

  private static ModelInvocationError toInvocationError(ProviderException error) {
    // ProviderException 是 final 类，但其 message 提取仍走安全路径：任何渲染失败都不能 bypass 状态转换。
    return new ModelInvocationError(error.kind(), message(error, "provider failure"));
  }

  /** 非抛出的消息提取：adversarial getMessage 只影响 UNKNOWN/FAILED 的 detail，绝不 bypass 状态转换。 */
  private static String message(RuntimeException failure, String fallback) {
    if (failure == null) {
      return fallback;
    }
    String detail;
    try {
      detail = failure.getMessage();
    } catch (RuntimeException ignored) {
      detail = null;
    }
    return detail == null || detail.isBlank() ? fallback : detail;
  }
}
