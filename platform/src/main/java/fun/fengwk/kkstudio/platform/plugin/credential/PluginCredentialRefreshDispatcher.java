package fun.fengwk.kkstudio.platform.plugin.credential;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import fun.fengwk.kkstudio.platform.plugin.PluginProperties;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 凭据刷新 dispatcher：启动后立即扫描一次，随后以 fixed-delay 扫描 {@code next_refresh_at} 到期的行。
 *
 * <p>它只驱动 {@link PluginCredentialRefreshService}，本身不持有状态、不解析凭据也不访问网络；扫描失败不终止调度，下一个周期继续收敛。
 * 关闭时取消调度并等待在途扫描结束，避免在数据库下线过程中继续写。
 */
@Slf4j
public final class PluginCredentialRefreshDispatcher implements SmartLifecycle {

  private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;

  private final PluginCredentialRefreshService refreshService;
  private final PluginProperties properties;
  private final Object lifecycleLock = new Object();
  private final AtomicBoolean scanRunning = new AtomicBoolean();

  private ScheduledExecutorService executor;
  private ScheduledFuture<?> scheduled;
  private volatile boolean running;

  public PluginCredentialRefreshDispatcher(
      PluginCredentialRefreshService refreshService, PluginProperties properties) {
    this.refreshService = Objects.requireNonNull(refreshService, "refreshService");
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  @Override
  public void start() {
    synchronized (lifecycleLock) {
      if (running) {
        return;
      }
      executor =
          Executors.newSingleThreadScheduledExecutor(
              Thread.ofPlatform().daemon().name("plugin-credential-refresher").factory());
      running = true;
      long pollMillis = properties.getRefresh().getPollDelay().toMillis();
      scheduled =
          executor.scheduleWithFixedDelay(this::scan, 0L, pollMillis, TimeUnit.MILLISECONDS);
    }
  }

  @Override
  public void stop() {
    synchronized (lifecycleLock) {
      if (!running) {
        return;
      }
      running = false;
      if (scheduled != null) {
        scheduled.cancel(false);
        scheduled = null;
      }
      if (executor != null) {
        executor.shutdown();
        try {
          if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            executor.shutdownNow();
          }
        } catch (InterruptedException error) {
          executor.shutdownNow();
          Thread.currentThread().interrupt();
        }
        executor = null;
      }
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** 扫描相位排在最后，使数据库与依赖 bean 全部就绪后才第一次扫描。 */
  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }

  /** 合并并发触发：同一时刻只允许一次扫描，重复触发直接跳过。 */
  public void wake() {
    if (!running) {
      return;
    }
    executor.execute(this::scan);
  }

  private void scan() {
    if (!scanRunning.compareAndSet(false, true)) {
      return;
    }
    try {
      refreshService.refreshOnce();
    } catch (RuntimeException error) {
      log.warn("Plugin credential refresh scan failed", error);
    } finally {
      scanRunning.set(false);
    }
  }
}
