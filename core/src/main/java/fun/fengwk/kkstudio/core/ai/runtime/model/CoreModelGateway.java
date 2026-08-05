package fun.fengwk.kkstudio.core.ai.runtime.model;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

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

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy;
import java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy;
import java.util.concurrent.ThreadPoolExecutor.DiscardPolicy;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Production {@link ModelGateway} adapter backed by PostgreSQL provider resources.
 *
 * <p>{@code start} resolves the frozen {@link
 * fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationRequest} synchronously
 * through {@link ProviderResolutionService} and submits a transport task to the shared
 * virtual-thread executor. The task opens one {@link ModelProvider} with the configured timeout
 * policy and bridges the SDK stream onto the Runtime listener; the gateway never reads or writes
 * the HarnessStore.
 *
 * <p>Admission classification: a deterministic resolution failure ({@link
 * IllegalArgumentException}) returns {@link Rejected} with {@link
 * ProviderErrorKind#INVALID_REQUEST} because the execution was provably never submitted and
 * retrying cannot help; any other resolution failure propagates so the Processor reschedules the
 * attempt. A rejected executor submission returns {@link Busy} with the configured delay; a
 * submission that fails with an unexpected exception returns {@link Indeterminate} because the
 * gateway can no longer prove whether the transport task started.
 *
 * <p>Two-phase activation: {@code start} never opens a callback gate. The transport task first
 * waits on an admission gate that {@link ModelGateway.Handle#activate()} opens only after the
 * Processor has attached the handle and durably marked the invocation RUNNING, so even a Provider
 * SDK that invokes the handler synchronously inside {@code stream} cannot race the caller; a {@code
 * Handle#cancel()} issued before activation wakes the waiting task and aborts it without touching
 * the Provider. A task interrupted while waiting (not cancelled) defers exactly one UNKNOWN to
 * {@code activate()} — an accepted, possibly RUNNING execution never hangs until lease recovery,
 * and no callback is ever delivered before {@code start} returns. Executors that run tasks inline
 * (direct executors or {@code CallerRunsPolicy}) or discard rejected tasks silently ({@code
 * DiscardPolicy} / {@code DiscardOldestPolicy}) are rejected at construction time because the gate
 * would deadlock or the rejection would never surface.
 *
 * <p>Callback classification: a classified {@link ProviderException} is delivered through {@code
 * onFailed} with its kind; null provider/stream/event/response payloads and adapter setup errors
 * are deterministic {@code INVALID_REQUEST} failures; an unclassified transport failure or a
 * throwing listener is delivered through {@code onUnknown} because the durable outcome cannot be
 * confirmed. Terminal callbacks are delivered at most once and late deltas after a terminal are
 * ignored. {@link ModelGateway.Handle#cancel()} is idempotent and best-effort across the pre-task,
 * pre-bind and post-bind windows.
 *
 * <p>The callback bridge is a serialized FIFO single-drainer state machine (same structure as the
 * Tool bridge): the bridge monitor only protects queue/dispatch/terminal state, and {@code
 * bindStream} (which may call {@code ProviderStream.cancel}), {@code cancel} and every Runtime
 * listener method are never invoked while holding it — Provider callbacks and internal reports
 * enqueue typed signals and exactly one drainer processes them FIFO outside the lock, so a cancel
 * that re-enters or spawns callback threads can never deadlock. The first terminal signal wins;
 * late/duplicate signals are cleared before any listener work. A non-terminal {@code onEvent}
 * listener failure may select the first UNKNOWN; a terminal listener exception is logged and never
 * yields a second terminal callback. The queue is capped ({@value #MAX_BUFFERED_SIGNALS} signals);
 * overflow deterministically selects exactly one UNKNOWN.
 */
@Slf4j
@Component
public final class CoreModelGateway implements ModelGateway {

  /** 回调桥缓冲队列的保守上限：adversarial Provider 同步回调绝不能无界缓冲。 */
  static final int MAX_BUFFERED_SIGNALS = 256;

  private final ProviderResolutionService providerResolution;
  private final ExecutorService executor;
  private final ModelGatewayConfig config;

  public CoreModelGateway(
      ProviderResolutionService providerResolution,
      @Qualifier("modelExecutionExecutor") ExecutorService executor,
      ModelGatewayConfig config) {
    this.providerResolution = Objects.requireNonNull(providerResolution, "providerResolution");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.config = Objects.requireNonNull(config, "config");
    rejectUnsafeExecutorPolicies(executor);
    rejectInlineExecutor(executor);
  }

  @Override
  public StartResult start(Execution execution, Listener listener) {
    Objects.requireNonNull(execution, "execution");
    Objects.requireNonNull(listener, "listener");
    ProviderResolutionService.ResolvedExecution resolved;
    try {
      resolved = providerResolution.resolve(execution.request().providerRequest());
    } catch (IllegalArgumentException setupFailure) {
      // Deterministic pre-submission setup failure: terminate the invocation, never retry.
      return new Rejected(
          new ModelInvocationError(
              ProviderErrorKind.INVALID_REQUEST,
              message(setupFailure, "cannot resolve provider for model invocation")));
    }
    StartGate gate = new StartGate();
    GatewayHandle handle = new GatewayHandle(gate, listener);
    TransportTask task =
        new TransportTask(resolved, execution.request().providerRequest(), listener, handle, gate);
    try {
      executor.execute(task);
    } catch (RejectedExecutionException rejected) {
      // The task was provably never accepted: busy with the configured delay. Cancel wakes a task a
      // broken executor may still have started so it aborts without touching the Provider.
      gate.cancel();
      return new Busy(config.busyRetryDelay());
    } catch (RuntimeException ambiguous) {
      // A broken executor may have started the task before throwing: the outcome is unknown.
      gate.cancel();
      return new Indeterminate(
          new ModelInvocationError(
              ProviderErrorKind.TRANSIENT,
              "model execution submission failed; provider outcome cannot be confirmed"));
    }
    // Two-phase activation: the admission gate stays closed until the Processor has attached the
    // handle and durably marked the invocation RUNNING, then calls Handle#activate().
    return new Started(handle);
  }

  /**
   * Rejects executor policies that run the transport task inline or drop rejected tasks silently.
   */
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
   * Rejects direct inline executors whose task runs synchronously inside {@code execute}; such
   * executors would deadlock the admission gate. The probe records the thread that runs the task,
   * so asynchronous executors can never be misclassified.
   */
  private static void rejectInlineExecutor(ExecutorService executor) {
    Thread caller = Thread.currentThread();
    AtomicReference<Thread> runner = new AtomicReference<>();
    try {
      executor.execute(() -> runner.set(Thread.currentThread()));
    } catch (RuntimeException ignored) {
      // Rejecting or broken executors cannot run tasks inline; failures surface from start().
      return;
    }
    if (runner.get() == caller) {
      throw new IllegalStateException("model gateway requires a non-inline executor");
    }
  }

  /**
   * Blocks the transport task until the execution is activated. {@link #open()} is called exactly
   * by {@link ModelGateway.Handle#activate()}; {@link #cancel()} wakes and aborts a task that was
   * cancelled before activation (the task must never touch the Provider). Either state change wakes
   * every waiter, so a cancelled-before-activate task can never leak on a parked thread.
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
     * @return true when the execution was activated; false when the task was cancelled before
     *     activation or interrupted while waiting — a cancelled task must abort silently, an
     *     interrupted task must defer exactly one UNKNOWN to activation (see {@link GatewayHandle})
     *     so an accepted, possibly RUNNING execution never hangs until lease recovery.
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
   * Best-effort idempotent cancel control covering the pre-task, pre-bind and post-bind windows.
   * {@link #activate()} opens the admission gate exactly once — or, when the transport task was
   * interrupted before activation, delivers exactly one UNKNOWN without ever touching the Provider;
   * a cancel issued before activation wakes the waiting task so it aborts without starting the
   * Provider and drops any deferred activation failure (cancel-before-activate stays silent).
   * activate / cancel / defer are idempotent and race-safe: once cancelled, activate never delivers
   * or opens the gate, and the deferred activation failure is delivered at most once no matter
   * which side of the defer-vs-activate race wins.
   */
  static final class GatewayHandle implements ModelGateway.Handle {

    private final Listener listener;
    private final StartGate gate;
    private boolean activated;
    private boolean cancelled;
    private boolean cancellationDelivered;
    private boolean activationFailureDelivered;
    private ProviderStream boundStream;
    private ModelInvocationError deferredFailure;

    GatewayHandle(StartGate gate, Listener listener) {
      this.gate = Objects.requireNonNull(gate, "gate");
      this.listener = Objects.requireNonNull(listener, "listener");
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
      if (cancelGate) {
        // 激活前取消：唤醒等待的 transport task 使其中止，绝不启动 Provider、不泄漏等待线程。
        gate.cancel();
      }
      if (target != null) {
        target.cancel();
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
          // A delayed-bind auto-cancel must never break the transport task.
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

  /** Runs the Provider I/O after admission; owns classification of setup and transport failures. */
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
   * terminal 回调。缓冲有界（≤{@value CoreModelGateway#MAX_BUFFERED_SIGNALS}），溢出即清空缓冲并确定性收敛 恰好一次 UNKNOWN。
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
