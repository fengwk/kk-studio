package fun.fengwk.kkstudio.canvas.infra.function;

import org.springframework.context.SmartLifecycle;

import fun.fengwk.kkstudio.canvas.infra.postgresql.CanvasFunctionWorkStore;
import fun.fengwk.kkstudio.harness.common.concurrent.BoundedWorkDispatcher;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

/**
 * DB 唯一队列的 wake + single drain + 固定 worker 并发 dispatcher。
 *
 * <p>合并 wake 的单 drain、bounded handoff、容量计数、stop 生命周期与 fail-fast executor 契约由共享的 {@link
 * BoundedWorkDispatcher} 提供；本类只注入 Canvas 专有的 claim、{@link CanvasFunctionWorker#run} handoff 与
 * ownership-fenced reschedule 归还。
 */
public final class CanvasFunctionDispatcher implements SmartLifecycle, AutoCloseable {

  /** Canvas Function Run 在 {@code durable_work} 上的 target type。 */
  public static final String TARGET_TYPE = "CANVAS_RUN";

  private static final String NAME = "canvas function dispatcher";

  private final CanvasFunctionWorkStore workStore;
  private final CanvasFunctionRuntimeProperties properties;
  private final BoundedWorkDispatcher<ClaimedRun> dispatcher;

  public CanvasFunctionDispatcher(
      CanvasFunctionWorkStore workStore,
      CanvasFunctionRuntimeProperties properties,
      Executor drainExecutor,
      Executor workerExecutor,
      ScheduledExecutorService pollScheduler,
      CanvasFunctionWorker worker) {
    this.workStore = Objects.requireNonNull(workStore, "workStore");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.properties.validate();
    this.dispatcher =
        new BoundedWorkDispatcher<>(
            NAME,
            properties.getMaxDispatchTasks(),
            Duration.ofMillis(properties.getPollIntervalMillis()),
            drainExecutor,
            workerExecutor,
            pollScheduler,
            this::claimNext,
            Objects.requireNonNull(worker, "worker")::run,
            this::returnClaim);
  }

  @Override
  public void start() {
    dispatcher.start();
  }

  public void wake() {
    dispatcher.wake();
  }

  @Override
  public void stop() {
    dispatcher.stop();
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
    return dispatcher.isRunning();
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

  private ClaimedRun claimNext() {
    return workStore
        .claimNext(
            Duration.ofMillis(properties.getLeaseDurationMillis()), UUID.randomUUID().toString())
        .orElse(null);
  }

  /** ownership-fenced 归还：只把仍持有的 RUNNING claim 按 rejectionDelay 重排回 READY。 */
  private void returnClaim(ClaimedRun claim) {
    workStore.reschedule(claim, Duration.ofMillis(properties.getRejectionDelayMillis()));
  }
}
