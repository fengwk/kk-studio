package fun.fengwk.kkstudio.core.ai.runtime.execution;

import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single-thread PostgreSQL durable execution-target dispatcher.
 *
 * <p>Behavior:
 *
 * <ul>
 *   <li>One drain executor runs the {@link #drainLoop()} body. No periodic scan and no
 *       per-invocation timer.
 *   <li>{@link #start()} submits the initial wake. {@link #wake()} is the only public coalesced
 *       entry point; callers (startup hook, {@code LISTEN} reconnect, NOTIFY, the nearest-due timer
 *       itself) invoke it.
 *   <li>Coalescing uses a {@code wakeRequested} flag plus a {@code drainRunning} flag. {@code
 *       wake()} only submits a new drain when no drain is currently running. The drain loop drains
 *       once, then performs a final {@code wakeRequested} CAS recheck so a wake that arrived during
 *       the previous drain triggers another iteration without losing the request.
 *   <li>The nearest-due timer calls {@link #wake()} rather than draining directly, so all drains
 *       execute on the single drain thread and cannot overlap.
 *   <li>Handlers receive lock-free row snapshots and must NOT block on external I/O; a handler
 *       returning {@code false} means the row is stale and the dispatcher skips it.
 *   <li>Eligibility: NULL {@code routeKey} targets are always eligible. Non-NULL {@code routeKey}
 *       targets are eligible only when {@link ExecutionTargetRouteEligibility#readyRouteKeys()}
 *       contains the key; a target whose environment is not locally READY stays durable but is
 *       never claimed or busy-looped on this node.
 * </ul>
 */
@Slf4j
public final class PostgresqlExecutionTargetDispatcher {

  /** Hard cap for one drain iteration so a stuck handler cannot loop. */
  private static final int MAX_ROWS_PER_DRAIN = 64;

  private final ExecutionTargetStore store;
  private final ExecutionTargetRouteEligibility routeEligibility;
  private final ExecutionTargetHandler handler;
  private final Clock clock;
  private final ExecutorService drainExecutor;
  private final ScheduledExecutorService wakeExecutor;

  private final AtomicBoolean started = new AtomicBoolean(false);
  private final AtomicBoolean stopping = new AtomicBoolean(false);
  private final AtomicBoolean wakeRequested = new AtomicBoolean(false);
  private final AtomicBoolean drainRunning = new AtomicBoolean(false);
  private final AtomicReference<ScheduledFuture<?>> wakeTimer = new AtomicReference<>();

  public PostgresqlExecutionTargetDispatcher(
      ExecutionTargetStore store,
      ExecutionTargetRouteEligibility routeEligibility,
      ExecutionTargetHandler handler,
      Clock clock,
      ExecutorService drainExecutor,
      ScheduledExecutorService wakeExecutor) {
    this.store = Objects.requireNonNull(store, "store");
    this.routeEligibility = Objects.requireNonNull(routeEligibility, "routeEligibility");
    this.handler = Objects.requireNonNull(handler, "handler");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.drainExecutor = Objects.requireNonNull(drainExecutor, "drainExecutor");
    this.wakeExecutor = Objects.requireNonNull(wakeExecutor, "wakeExecutor");
  }

  /** Submit the initial wake; idempotent. */
  public void start() {
    if (!started.compareAndSet(false, true)) {
      return;
    }
    wake();
  }

  /**
   * Coalesced wake. The flag plus {@link #drainRunning} CAS guarantees that at most one drain
   * iteration is in flight on the drain executor at any time and that wakes arriving during a drain
   * are picked up by the CAS-recheck at the end of the drain loop.
   */
  public void wake() {
    if (stopping.get() || !started.get()) {
      return;
    }
    wakeRequested.set(true);
    if (drainRunning.compareAndSet(false, true)) {
      try {
        drainExecutor.execute(this::drainLoop);
      } catch (RejectedExecutionException rejection) {
        // The executor is shutting down; flip the flag back so any
        // subsequent wake() during stop() can re-evaluate the stopping
        // state and exit cleanly.
        drainRunning.set(false);
        throw rejection;
      }
    }
  }

  /** Stop wakes from firing and tear down the nearest-due timer. */
  public void stop() {
    stopping.set(true);
    ScheduledFuture<?> timer = wakeTimer.getAndSet(null);
    if (timer != null) {
      timer.cancel(false);
    }
    // Unstick any drain waiting for a wake — the loop body re-checks
    // stopping and exits on the next iteration.
    wakeRequested.set(true);
  }

  private void drainLoop() {
    try {
      while (true) {
        // Atomically claim exactly one pending wake. If none, exit.
        if (!wakeRequested.compareAndSet(true, false)) {
          return;
        }
        if (stopping.get()) {
          return;
        }
        drainOnce();
        // Final CAS recheck: if wake() was called during drainOnce it set
        // wakeRequested back to true, and the loop runs again. Otherwise
        // we exit and the next wake() will submit a new drain.
      }
    } catch (RuntimeException error) {
      log.warn("execution target drain failed", error);
    } finally {
      drainRunning.set(false);
      if (wakeRequested.get() && !stopping.get() && drainRunning.compareAndSet(false, true)) {
        try {
          drainExecutor.execute(this::drainLoop);
        } catch (RejectedExecutionException rejection) {
          drainRunning.set(false);
          if (!stopping.get()) {
            log.warn("execution target drain resubmission rejected", rejection);
          }
        }
      }
    }
  }

  private void drainOnce() {
    Instant now = clock.instant();
    List<ExecutionTargetRow> rows;
    try {
      rows = store.findEligibleDue(routeEligibility, now, MAX_ROWS_PER_DRAIN);
    } catch (RuntimeException error) {
      log.warn("execution target eligible-due scan failed", error);
      return;
    }
    for (ExecutionTargetRow row : rows) {
      if (stopping.get()) {
        return;
      }
      boolean handled;
      try {
        handled = handler.handle(row);
      } catch (RuntimeException error) {
        log.warn("execution target handler failed for {}", row, error);
        handled = false;
      }
      if (!handled) {
        log.debug("execution target handler reported stale row {}", row);
      }
    }
    armNextTimer();
  }

  private void armNextTimer() {
    Optional<Instant> next = store.findNearestEligibleAvailableAt(routeEligibility);
    ScheduledFuture<?> previous = wakeTimer.getAndSet(null);
    if (previous != null) {
      previous.cancel(false);
    }
    if (next.isEmpty()) {
      return;
    }
    long delayMillis = delayMillis(next.get());
    ScheduledFuture<?> scheduled =
        wakeExecutor.schedule(this::wake, delayMillis, TimeUnit.MILLISECONDS);
    // stop() may clear the timer between schedule() and publication. Do not
    // leave that late timer alive after the dispatcher has stopped.
    if (!wakeTimer.compareAndSet(null, scheduled) || stopping.get()) {
      wakeTimer.compareAndSet(scheduled, null);
      scheduled.cancel(false);
    }
  }

  private long delayMillis(Instant target) {
    Duration d = Duration.between(clock.instant(), target);
    if (d.isNegative() || d.isZero()) {
      return 1L;
    }
    long ms = d.toMillis();
    return ms <= 0L ? 1L : ms;
  }
}
