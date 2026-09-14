package fun.fengwk.kkstudio.platform.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import fun.fengwk.kkstudio.platform.storage.configuration.StorageMaintenanceProperties;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 全局 Blob 存储的耐久后台维护循环。
 *
 * <p>数据库行是唯一清理事实；本生命周期拥有单一 daemon scheduled executor，通过 startup wake、合并 wake 与 fixed-delay poll
 * 驱动上传 lease 清理和 DELETING blob 清扫。所有 S3 I/O 只在该后台线程或显式事务外请求路径执行。
 */
@Slf4j
public final class StorageMaintenance
    implements SmartLifecycle, AutoCloseable, StorageMaintenanceWakeup {

  private static final long SHUTDOWN_WAIT_MILLIS = 5_000;

  private final StorageUploadService uploadService;
  private final StorageBlobManager blobManager;
  private final long pollDelayMillis;
  private final Object lifecycleLock = new Object();
  private final AtomicBoolean wakeRequested = new AtomicBoolean();
  private final AtomicBoolean drainRunning = new AtomicBoolean();

  private volatile boolean running;
  private volatile boolean closed;
  private volatile ScheduledExecutorService executor;
  private volatile ScheduledFuture<?> pollFuture;

  public StorageMaintenance(
      StorageUploadService uploadService,
      StorageBlobManager blobManager,
      StorageMaintenanceProperties properties) {
    this.uploadService = Objects.requireNonNull(uploadService, "uploadService");
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
    Objects.requireNonNull(properties, "properties");
    this.pollDelayMillis = requirePositiveWholeMillis(properties.getPollDelay(), "pollDelay");
  }

  @Override
  public void start() {
    synchronized (lifecycleLock) {
      if (closed) {
        throw new IllegalStateException("storage maintenance is already closed");
      }
      if (running) {
        return;
      }
      ScheduledExecutorService created =
          Executors.newSingleThreadScheduledExecutor(
              runnable ->
                  Thread.ofPlatform().name("storage-maintenance").daemon(true).unstarted(runnable));
      executor = created;
      running = true;
      try {
        pollFuture =
            created.scheduleWithFixedDelay(
                this::wake, pollDelayMillis, pollDelayMillis, TimeUnit.MILLISECONDS);
        wake();
      } catch (RuntimeException | Error error) {
        running = false;
        executor = null;
        created.shutdownNow();
        throw error;
      }
    }
  }

  /** start 前或 stop 后为 no-op；并发 wake 合并为单一 drain。 */
  @Override
  public void wake() {
    if (!running || closed) {
      return;
    }
    wakeRequested.set(true);
    if (drainRunning.compareAndSet(false, true)) {
      submitDrain();
    }
  }

  @Override
  public void stop() {
    stopInternal(false);
  }

  @Override
  public void stop(Runnable callback) {
    try {
      stopInternal(false);
    } finally {
      callback.run();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }

  @Override
  public void close() {
    stopInternal(true);
  }

  private void stopInternal(boolean permanentClose) {
    ScheduledExecutorService current;
    synchronized (lifecycleLock) {
      if (closed || (!running && !permanentClose)) {
        return;
      }
      if (permanentClose) {
        closed = true;
      }
      running = false;
      ScheduledFuture<?> future = pollFuture;
      if (future != null) {
        future.cancel(false);
      }
      current = executor;
      executor = null;
    }
    if (current != null) {
      current.shutdownNow();
      try {
        if (!current.awaitTermination(SHUTDOWN_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
          log.warn("storage maintenance executor did not stop within {}ms", SHUTDOWN_WAIT_MILLIS);
        }
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void submitDrain() {
    ScheduledExecutorService current = executor;
    if (current == null) {
      drainRunning.set(false);
      return;
    }
    try {
      current.execute(this::runDrain);
    } catch (RejectedExecutionException error) {
      drainRunning.set(false);
      if (running) {
        log.warn("storage maintenance executor rejected a wake; periodic poll will retry", error);
      }
    }
  }

  private void runDrain() {
    try {
      do {
        wakeRequested.set(false);
        drainUntilIdle();
      } while (running && wakeRequested.get());
    } catch (RuntimeException ignored) {
      log.warn("storage maintenance drain failed; next wake or poll will retry");
    } finally {
      drainRunning.set(false);
      if (running && wakeRequested.get() && drainRunning.compareAndSet(false, true)) {
        submitDrain();
      }
    }
  }

  private void drainUntilIdle() {
    int expired;
    int swept;
    do {
      expired = expire(uploadService);
      swept = sweep(blobManager);
    } while (running
        && (expired == StorageUploadService.MAX_EXPIRY_BATCH
            || swept == StorageBlobManager.MAX_SWEEP_BATCH));
  }

  private int expire(StorageUploadService uploadService) {
    try {
      return uploadService.expireOnce();
    } catch (RuntimeException ignored) {
      log.warn("storage upload maintenance failed; next wake or poll will retry");
      return 0;
    }
  }

  private int sweep(StorageBlobManager blobManager) {
    try {
      return blobManager.sweepDeleting();
    } catch (RuntimeException ignored) {
      log.warn("storage blob maintenance failed; next wake or poll will retry");
      return 0;
    }
  }

  private static long requirePositiveWholeMillis(Duration value, String name) {
    Objects.requireNonNull(value, name);
    long nanos;
    try {
      nanos = value.toNanos();
    } catch (ArithmeticException error) {
      throw new IllegalArgumentException(name + " is too large", error);
    }
    if (nanos <= 0 || nanos % 1_000_000 != 0) {
      throw new IllegalArgumentException(name + " must be a positive whole-millisecond duration");
    }
    return nanos / 1_000_000;
  }
}
