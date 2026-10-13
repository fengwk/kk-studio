package fun.fengwk.kkstudio.harness.daemon;

import fun.fengwk.kkstudio.harness.daemon.coding.TextOutputStore;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 受控临时 workspace 的保留策略与定时清扫：唯一持有最近一次通告的保留期与扫描间隔。
 *
 * <p>策略可经 WELCOME 与同一控制通道的 {@code TEMPORARY_RESOURCE_POLICY} 热更：保留期只在清扫时读取（仍在使用的 workspace 因 in-use
 * lease 永不删除），扫描间隔变化时重排定时任务，同一间隔下不重复调度。清扫执行派发到任务执行器，避免文件删除拖慢承载 heartbeat/reconnect 的单线程 scheduler。
 *
 * <p>无受控存储（测试构造）时只记录策略、不调度清扫。
 */
final class DaemonTemporaryResourcePolicy implements AutoCloseable {

  private static final Logger LOG = System.getLogger(DaemonTemporaryResourcePolicy.class.getName());

  private final ScheduledExecutorService scheduler;
  private final ExecutorService taskExecutor;

  /** 受控临时 workspace 存储；为 {@code null} 时只记录策略。 */
  private final TextOutputStore store;

  /** 最近一次通告的临时资源保留期（秒）；0 表示尚未收到策略。 */
  private volatile long ttlSeconds;

  /** 最近一次通告的临时资源扫描间隔（秒）。 */
  private volatile long cleanupIntervalSeconds;

  private final Object policyLock = new Object();

  /** 当前已排定的清扫间隔（秒）；仅由 {@link #policyLock} 保护，用于避免重复调度。 */
  private long scheduledIntervalSeconds;

  private ScheduledFuture<?> sweep;

  DaemonTemporaryResourcePolicy(
      ScheduledExecutorService scheduler, ExecutorService taskExecutor, TextOutputStore store) {
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor");
    this.store = store;
  }

  /**
   * 应用最新策略：记录保留期并按最新扫描间隔（重）排定时清扫；同一间隔下不重复调度。
   *
   * <p>热更只对未回收的旧 workspace 生效：下一次清扫读取最新保留期。无存储时只记录策略、不调度。
   */
  void apply(long ttlSeconds, long cleanupIntervalSeconds) {
    this.ttlSeconds = ttlSeconds;
    this.cleanupIntervalSeconds = cleanupIntervalSeconds;
    if (store == null) {
      return;
    }
    synchronized (policyLock) {
      if (sweep != null && scheduledIntervalSeconds == cleanupIntervalSeconds) {
        return;
      }
      scheduledIntervalSeconds = cleanupIntervalSeconds;
      if (sweep != null) {
        sweep.cancel(false);
        sweep = null;
      }
      try {
        sweep =
            scheduler.scheduleWithFixedDelay(
                this::submitSweep,
                cleanupIntervalSeconds,
                cleanupIntervalSeconds,
                TimeUnit.SECONDS);
      } catch (RejectedExecutionException ignored) {
        // 调度器已停机：关闭中忽略，保留策略事实。
        sweep = null;
      }
    }
  }

  /** 最近一次应用的保留期（秒）；仅用于测试断言策略热更是否生效。 */
  long ttlSeconds() {
    return ttlSeconds;
  }

  /** 最近一次应用的扫描间隔（秒）；仅用于测试断言策略热更是否生效。 */
  long cleanupIntervalSeconds() {
    return cleanupIntervalSeconds;
  }

  /** 取消定时清扫任务；已关闭后的重复调用无副作用。 */
  @Override
  public void close() {
    synchronized (policyLock) {
      if (sweep != null) {
        sweep.cancel(false);
        sweep = null;
      }
    }
  }

  /** 把清扫派发到任务执行器，避免文件删除拖慢承载 heartbeat/reconnect 的单线程 scheduler。 */
  private void submitSweep() {
    try {
      taskExecutor.execute(this::sweepOnce);
    } catch (RejectedExecutionException ignored) {
      // 关闭中：忽略。
    }
  }

  /**
   * 按最新保留期清扫过期 workspace。
   *
   * <p>单次清扫失败只记录安全阶段与异常类型：清理职责独立继续执行，既不静默、也不把一次文件系统故障升级为进程终止（{@code Error} 仍向上传播）。
   */
  private void sweepOnce() {
    try {
      store.sweep(ttlSeconds);
    } catch (RuntimeException error) {
      LOG.log(
          Level.WARNING, "temporary resource sweep failed: " + error.getClass().getSimpleName());
    }
  }
}
