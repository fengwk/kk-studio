package fun.fengwk.kkstudio.canvas.infra.function;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import fun.fengwk.kkstudio.canvas.infra.postgresql.CanvasFunctionWorkStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** DB 唯一队列的 wake + single drain + 固定 worker 并发 dispatcher。 */
@Slf4j
public final class CanvasFunctionDispatcher implements SmartLifecycle, AutoCloseable {

  private final CanvasFunctionWorkStore workStore;
  private final CanvasFunctionRuntimeProperties properties;
  private final Clock clock;
  private final Executor drainExecutor;
  private final Executor workerExecutor;
  private final ScheduledExecutorService pollScheduler;
  private final CanvasFunctionWorker worker;
  private final AtomicBoolean wakeRequested = new AtomicBoolean();
  private final AtomicBoolean drainRunning = new AtomicBoolean();
  private final AtomicInteger capacity = new AtomicInteger();
  private final Object lifecycleLock = new Object();
  private volatile boolean running;
  private volatile boolean stopped;
  private ScheduledFuture<?> pollFuture;

  public CanvasFunctionDispatcher(
      CanvasFunctionWorkStore workStore,
      CanvasFunctionRuntimeProperties properties,
      Clock clock,
      Executor drainExecutor,
      Executor workerExecutor,
      ScheduledExecutorService pollScheduler,
      CanvasFunctionWorker worker) {
    this.workStore = Objects.requireNonNull(workStore, "workStore");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.properties.validate();
    this.clock = Clock.tick(Objects.requireNonNull(clock, "clock"), Duration.ofMillis(1));
    this.drainExecutor = Objects.requireNonNull(drainExecutor, "drainExecutor");
    this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor");
    this.pollScheduler = Objects.requireNonNull(pollScheduler, "pollScheduler");
    this.worker = Objects.requireNonNull(worker, "worker");
  }

  @Override
  public void start() {
    synchronized (lifecycleLock) {
      if (stopped) {
        throw new IllegalStateException("dispatcher is already stopped");
      }
      if (running) {
        return;
      }
      running = true;
      long pollMillis = properties.getPollIntervalMillis();
      pollFuture =
          pollScheduler.scheduleWithFixedDelay(
              this::wake, pollMillis, pollMillis, TimeUnit.MILLISECONDS);
    }
    wake();
  }

  public void wake() {
    if (!running || stopped) {
      return;
    }
    wakeRequested.set(true);
    if (drainRunning.compareAndSet(false, true)) {
      submitDrain();
    }
  }

  private void submitDrain() {
    try {
      drainExecutor.execute(this::runDrain);
    } catch (RuntimeException error) {
      drainRunning.set(false);
      log.warn("Canvas Function drain executor rejected wake; poll will retry", error);
    }
  }

  private void runDrain() {
    try {
      do {
        wakeRequested.set(false);
        drainOnce();
      } while (!stopped && wakeRequested.get());
    } catch (RuntimeException error) {
      log.warn("Canvas Function drain failed; poll will retry", error);
    } finally {
      drainRunning.set(false);
      if (!stopped && wakeRequested.get() && drainRunning.compareAndSet(false, true)) {
        submitDrain();
      }
    }
  }

  private void drainOnce() {
    while (!stopped && capacity.get() < properties.getMaxDispatchTasks()) {
      Instant now = clock.instant();
      ClaimedRun claim =
          workStore
              .claimNext(
                  now,
                  Duration.ofMillis(properties.getLeaseDurationMillis()),
                  UUID.randomUUID().toString())
              .orElse(null);
      if (claim == null) {
        return;
      }
      if (stopped || !handoff(claim)) {
        workStore.reschedule(
            claim, clock.instant(), Duration.ofMillis(properties.getRejectionDelayMillis()));
        return;
      }
    }
  }

  private boolean handoff(ClaimedRun claim) {
    AtomicBoolean taskStarted = new AtomicBoolean();
    capacity.incrementAndGet();
    try {
      workerExecutor.execute(
          () -> {
            taskStarted.set(true);
            try {
              worker.run(claim);
            } finally {
              capacity.decrementAndGet();
              wake();
            }
          });
      return true;
    } catch (RuntimeException error) {
      if (taskStarted.get()) {
        throw error;
      }
      capacity.decrementAndGet();
      log.warn(
          "Canvas Function worker executor rejected claim nodeId={} requestId={}",
          claim.nodeId(),
          claim.requestId(),
          error);
      return false;
    }
  }

  @Override
  public void stop() {
    synchronized (lifecycleLock) {
      if (stopped) {
        return;
      }
      stopped = true;
      running = false;
      if (pollFuture != null) {
        pollFuture.cancel(false);
      }
    }
  }

  @Override
  public void stop(Runnable callback) {
    try {
      stop();
    } finally {
      callback.run();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public boolean isAutoStartup() {
    return true;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 1;
  }

  @Override
  public void close() {
    stop();
  }
}
