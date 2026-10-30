package fun.fengwk.kkstudio.work;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 有界 work 调度骨架：合并 wake 的单 drain、bounded worker handoff、周期 poll 与 stop 生命周期。
 *
 * <p>本类只承担与业务无关的同步调度和资源计数：一次只有一个 drain 在跑；已 claim 的 item 只有在 worker executor 恰好接受一次 handoff task
 * 后才移交给 {@code execute}；worker task 的数量由 {@code maxDispatchTasks} 约束，收尾时释放并再 wake 一次。它不解释 claim
 * 结果、不决定业务终态，也不做任何持久化—— 所有 claim / return / wake 语义都由调用方通过注入的回调表达。
 *
 * <p>回调所有权：{@code claimNext} 由 drain 线程串行调用，返回一个 claim 或 {@code null}（无可认领即结束本轮 drain）；{@code
 * execute} 在 worker executor 上执行一个已接受的 claim，可自行处理业务异常；{@code returnUnstartedClaim} 用于归还「已 claim
 * 但尚未被 worker 接受」的 item（worker 拒绝、stop 或 worker executor 在接收前抛错），它不参与容量计数，失败处理由调用方决定。三个回调都不得为
 * {@code null}。
 *
 * <p>生命周期：{@link #start()} 成功注册 periodic poll 后才进入运行态并触发首次 wake；{@link #wake()} 在 start 前或 stop 后都是
 * no-op；{@link #stop()} 幂等——只取消 poll future 并立即返回，不 shutdown 调用方注入的 executor、不 interrupt 或等待已接受的
 * worker task，stop 后不再开始新的 drain iteration，但已进入数据库的 claim 若在 handoff 前观察到 stop 会经 {@code
 * returnUnstartedClaim} 归还；stop 后调用 {@link #start()} 明确抛 {@link IllegalStateException}。{@code
 * drainExecutor} 必须串行执行提交的任务，且与 {@code workerExecutor} 一样必须 使用 fail-fast 的提交契约：成功 {@code execute}
 * 恰好接受一次 task，无法接受时在接受前同步抛出，禁止 CallerRunsPolicy、DiscardPolicy、DiscardOldestPolicy 或任何静默丢弃 / 替换已接受
 * task 的实现。三个 executor 的生命周期都由调用方持有，关闭顺序必须先 {@link #stop()} 再 shutdown。
 *
 * <p>诊断：本类只记录受控的 {@code name}/{@code role} 与异常类名，不携带原始 throwable；业务异常语义留在各域自己的 回调中。
 *
 * @param <T> claim 类型，对调度骨架不透明
 */
public final class BoundedWorkDispatcher<T> implements AutoCloseable {

  private static final Logger LOGGER = System.getLogger(BoundedWorkDispatcher.class.getName());

  private final String name;
  private final int maxDispatchTasks;
  private final Duration pollInterval;
  private final Executor drainExecutor;
  private final Executor workerExecutor;
  private final ScheduledExecutorService pollScheduler;
  private final Supplier<T> claimNext;
  private final Consumer<T> execute;
  private final Consumer<T> returnUnstartedClaim;

  private final AtomicBoolean wakeRequested = new AtomicBoolean();
  private final AtomicBoolean drainRunning = new AtomicBoolean();
  private final AtomicInteger inFlight = new AtomicInteger();
  private final Object lifecycleLock = new Object();
  private volatile boolean started;
  private volatile boolean stopped;
  private ScheduledFuture<?> pollFuture;

  public BoundedWorkDispatcher(
      String name,
      int maxDispatchTasks,
      Duration pollInterval,
      Executor drainExecutor,
      Executor workerExecutor,
      ScheduledExecutorService pollScheduler,
      Supplier<T> claimNext,
      Consumer<T> execute,
      Consumer<T> returnUnstartedClaim) {
    this.name = requireName(name);
    if (maxDispatchTasks <= 0) {
      throw new IllegalArgumentException(name + " maxDispatchTasks must be positive");
    }
    this.maxDispatchTasks = maxDispatchTasks;
    this.pollInterval = requirePositiveWholeMillis(pollInterval, name, "pollInterval");
    this.drainExecutor = requireFailFastExecutor(drainExecutor, name, "drainExecutor");
    this.workerExecutor = requireFailFastExecutor(workerExecutor, name, "workerExecutor");
    this.pollScheduler = Objects.requireNonNull(pollScheduler, "pollScheduler");
    this.claimNext = Objects.requireNonNull(claimNext, "claimNext");
    this.execute = Objects.requireNonNull(execute, "execute");
    this.returnUnstartedClaim =
        Objects.requireNonNull(returnUnstartedClaim, "returnUnstartedClaim");
  }

  /**
   * 启动调度：注册 fixed-delay periodic poll 并触发首次 wake。幂等（重复调用只注册一次）；已 stop 后调用抛 {@link
   * IllegalStateException}。
   */
  public void start() {
    synchronized (lifecycleLock) {
      if (stopped) {
        throw new IllegalStateException(name + " is already stopped");
      }
      if (started) {
        return;
      }
      long intervalMillis = pollInterval.toMillis();
      pollFuture =
          pollScheduler.scheduleWithFixedDelay(
              this::wake, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
      started = true;
    }
    // 注册成功后才进入运行态，并在释放 lifecycleLock 之后再 wake：inline drain 中阻塞的 claim 不会持锁，stop 无需等待。
    wake();
  }

  /** 请求一次 drain；start 前或 stop 后为 no-op。 */
  public void wake() {
    if (!started || stopped) {
      return;
    }
    wakeRequested.set(true);
    if (drainRunning.compareAndSet(false, true)) {
      submitDrain();
    }
  }

  /**
   * 停止调度：取消 periodic poll future，之后不再开始新的 drain iteration；已进入数据库的 claim 可能在 handoff 前被归还。不 shutdown
   * 注入 executor、不 interrupt 已接受 task、不等待 worker task 完成。幂等。
   */
  public void stop() {
    synchronized (lifecycleLock) {
      if (stopped) {
        return;
      }
      stopped = true;
      started = false;
      ScheduledFuture<?> future = pollFuture;
      if (future != null) {
        future.cancel(false);
      }
    }
  }

  @Override
  public void close() {
    stop();
  }

  /** 调度是否处于运行态：成功 start 后为 {@code true}，stop 后为 {@code false}。 */
  public boolean isRunning() {
    return started;
  }

  /** 当前已接受、尚未收尾的 worker task 数，用于容量观测。 */
  public int inFlightCount() {
    return inFlight.get();
  }

  private void submitDrain() {
    AtomicBoolean taskStarted = new AtomicBoolean();
    try {
      drainExecutor.execute(
          () -> {
            taskStarted.set(true);
            runDrain();
          });
    } catch (RuntimeException error) {
      if (taskStarted.get()) {
        throw error;
      }
      drainRunning.set(false);
      LOGGER.log(
          Level.WARNING,
          name
              + " drain executor failed to accept a drain task; the next wake or poll will retry"
              + "; failure="
              + error.getClass().getName());
    } catch (Error error) {
      if (!taskStarted.get()) {
        drainRunning.set(false);
      }
      throw error;
    }
  }

  private void runDrain() {
    try {
      do {
        wakeRequested.set(false);
        drainOnce();
      } while (!stopped && wakeRequested.get());
    } catch (RuntimeException error) {
      LOGGER.log(
          Level.WARNING,
          name
              + " work drain failed; the next wake or poll will retry"
              + "; failure="
              + error.getClass().getName());
    } finally {
      drainRunning.set(false);
      // drain 收尾窗口内到达的 wake：drainRunning 已清，必须由 recheck 重新提交，否则 wake 丢失。
      if (!stopped && wakeRequested.get() && drainRunning.compareAndSet(false, true)) {
        submitDrain();
      }
    }
  }

  /** 单次 drain 扫描：claim -> 容量检查 -> handoff，直到无 due、capacity 满或需要结束。 */
  private void drainOnce() {
    while (!stopped && inFlight.get() < maxDispatchTasks) {
      T claim = claimNext.get();
      if (claim == null) {
        break;
      }
      if (stopped) {
        returnUnstartedClaim.accept(claim);
        break;
      }
      if (!handoff(claim)) {
        // worker executor rejection：结束当前 drain，禁止 claim -> reject 热循环。
        returnUnstartedClaim.accept(claim);
        break;
      }
    }
  }

  private boolean handoff(T claim) {
    AtomicBoolean taskStarted = new AtomicBoolean();
    inFlight.incrementAndGet();
    try {
      workerExecutor.execute(
          () -> {
            taskStarted.set(true);
            try {
              execute.accept(claim);
            } finally {
              inFlight.decrementAndGet();
              wake();
            }
          });
      return true;
    } catch (RuntimeException error) {
      if (taskStarted.get()) {
        throw error;
      }
      inFlight.decrementAndGet();
      LOGGER.log(
          Level.WARNING,
          name
              + " worker executor failed to accept a dispatch task; returning the claim"
              + "; failure="
              + error.getClass().getName());
      return false;
    } catch (Error error) {
      if (!taskStarted.get()) {
        inFlight.decrementAndGet();
        LOGGER.log(
            Level.ERROR,
            name
                + " worker executor failed before accepting a dispatch task; returning the claim"
                + "; failure="
                + error.getClass().getName());
        try {
          returnUnstartedClaim.accept(claim);
        } finally {
          throw error;
        }
      }
      throw error;
    }
  }

  private static String requireName(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    return name;
  }

  private static Duration requirePositiveWholeMillis(Duration value, String name, String role) {
    Objects.requireNonNull(value, role);
    long millis = value.toMillis();
    if (millis <= 0 || !value.equals(Duration.ofMillis(millis))) {
      throw new IllegalArgumentException(
          name + " " + role + " must be a positive whole number of milliseconds");
    }
    return value;
  }

  private static Executor requireFailFastExecutor(Executor executor, String name, String role) {
    Objects.requireNonNull(executor, role);
    if (executor instanceof ThreadPoolExecutor threadPool) {
      RejectedExecutionHandler handler = threadPool.getRejectedExecutionHandler();
      if (handler instanceof ThreadPoolExecutor.CallerRunsPolicy
          || handler instanceof ThreadPoolExecutor.DiscardPolicy
          || handler instanceof ThreadPoolExecutor.DiscardOldestPolicy) {
        throw new IllegalArgumentException(
            name + " " + role + " must use a fail-fast rejection policy");
      }
    }
    return executor;
  }
}
