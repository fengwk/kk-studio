package fun.fengwk.kkstudio.platform.storage.service.impl;

import software.amazon.awssdk.core.exception.AbortedException;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;

import fun.fengwk.kkstudio.platform.storage.ReadDeadline;
import fun.fengwk.kkstudio.platform.storage.S3ObjectStream;
import fun.fengwk.kkstudio.platform.storage.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.error.StorageReadInterruptedException;
import fun.fengwk.kkstudio.platform.storage.error.StorageReadTimeoutException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 受管 Blob 响应流的绝对截止看门狗。
 *
 * <p>AWS SDK 同步 {@code getObject} 返回的响应流不受调用方截止时间约束：{@code apiCallTimeout} 只覆盖到响应头返回，socket
 * 超时只在空闲时生效，因此这里在冻结的绝对截止点主动 abort 响应流，打断阻塞中的 {@code read}：
 *
 * <ul>
 *   <li>正常读到 EOF 后 {@link #close()} 直接关闭流，连接可复用；
 *   <li>提前退出（截止已过、被取消、消费失败）一律 abort，绝不 close 未读完的响应体，避免排空剩余体导致无界阻塞；
 *   <li>超时与被取消是两种可区分的失败（{@link StorageReadTimeoutException} / {@link
 *       StorageReadInterruptedException}），不伪装成二进制或普通 IO 错误；
 *   <li>看门狗任务挂在共享单线程 daemon 调度器上，并在 {@link #close()} 中注销。
 * </ul>
 */
final class DeadlineBoundedBlobStream extends InputStream {

  /** 共享单线程 daemon 调度器：任务只做 abort，随读取结束注销，不让 JVM 因它而无法退出。 */
  private static final ScheduledThreadPoolExecutor WATCHDOG = newWatchdog();

  private final String objectKey;
  private final S3ObjectStream object;
  private final ReadDeadline deadline;
  private final ScheduledFuture<?> watchdogTask;
  private final AtomicBoolean finished = new AtomicBoolean();
  private final AtomicBoolean eofReached = new AtomicBoolean();
  private final AtomicBoolean timedOut = new AtomicBoolean();
  private final AtomicBoolean interrupted = new AtomicBoolean();
  private final AtomicReference<RuntimeException> abortError = new AtomicReference<>();

  /**
   * 在截止时间内打开响应流。
   *
   * @throws StorageReadInterruptedException 打开前读取线程已处于中断状态
   * @throws StorageReadTimeoutException 打开前截止已过，或 S3 握手超过截止
   */
  static DeadlineBoundedBlobStream open(
      S3StorageService s3StorageService, String objectKey, ReadDeadline deadline) {
    Objects.requireNonNull(s3StorageService, "s3StorageService");
    Objects.requireNonNull(objectKey, "objectKey");
    Objects.requireNonNull(deadline, "deadline");
    if (Thread.currentThread().isInterrupted()) {
      throw new StorageReadInterruptedException(
          "blob read interrupted before S3 GET: " + objectKey);
    }
    if (deadline.isExpired()) {
      throw new StorageReadTimeoutException(
          "blob read deadline exceeded before S3 GET: " + objectKey);
    }
    S3ObjectStream object;
    try {
      object = s3StorageService.readObject(objectKey, deadline);
    } catch (RuntimeException error) {
      throw openFailure(objectKey, deadline, error);
    }
    return new DeadlineBoundedBlobStream(objectKey, object, deadline);
  }

  private static RuntimeException openFailure(
      String objectKey, ReadDeadline deadline, RuntimeException error) {
    // SDK 自带的 apiCallTimeout 会中断读取线程并在抛出前清除中断位，因此这里看到的“仍处于中断状态”只可能来自
    // 调用方取消；取消优先于超时上报，与 ensureReadable 的语义一致。
    if (error instanceof AbortedException && Thread.currentThread().isInterrupted()) {
      return new StorageReadInterruptedException(
          "blob read interrupted during S3 GET: " + objectKey, error);
    }
    if (error instanceof ApiCallTimeoutException
        || error instanceof ApiCallAttemptTimeoutException
        || deadline.isExpired()) {
      return new StorageReadTimeoutException(
          "blob read timed out during S3 GET: " + objectKey, error);
    }
    return error;
  }

  private static ScheduledThreadPoolExecutor newWatchdog() {
    ScheduledThreadPoolExecutor executor =
        new ScheduledThreadPoolExecutor(
            1,
            runnable -> {
              Thread thread = new Thread(runnable, "blob-read-watchdog");
              thread.setDaemon(true);
              return thread;
            });
    executor.setRemoveOnCancelPolicy(true);
    return executor;
  }

  /** 当前挂起的看门狗任务数；包内测试据此验证读取结束后任务被注销。 */
  static int pendingWatchdogTasks() {
    return WATCHDOG.getQueue().size();
  }

  private DeadlineBoundedBlobStream(
      String objectKey, S3ObjectStream object, ReadDeadline deadline) {
    this.objectKey = objectKey;
    this.object = object;
    this.deadline = deadline;
    this.watchdogTask =
        WATCHDOG.schedule(
            this::onDeadline, Math.max(deadline.remainingNanos(), 0L), TimeUnit.NANOSECONDS);
  }

  @Override
  public int read() throws IOException {
    byte[] single = new byte[1];
    if (read(single, 0, 1) == -1) {
      return -1;
    }
    return single[0] & 0xFF;
  }

  @Override
  public int read(byte[] bytes, int offset, int length) throws IOException {
    ensureReadable();
    int read;
    try {
      read = object.inputStream().read(bytes, offset, length);
    } catch (IOException error) {
      if (interrupted.get() || Thread.currentThread().isInterrupted()) {
        // 阻塞中的读取因取消失败：按取消而不是普通 IO 失败上报。
        interrupted.set(true);
        throw new StorageReadInterruptedException("blob read interrupted: " + objectKey, error);
      }
      if (timedOut.get()) {
        throw timeoutFailure(error);
      }
      throw error;
    }
    if (read == -1) {
      eofReached.set(true);
    }
    return read;
  }

  @Override
  public void close() {
    if (!finished.compareAndSet(false, true)) {
      return;
    }
    watchdogTask.cancel(false);
    if (eofReached.get()) {
      // 已读完：正常关闭，连接排空后可复用。
      try {
        object.close();
      } catch (IOException error) {
        throw new UncheckedIOException("failed to close blob stream: " + objectKey, error);
      }
      return;
    }
    abort();
  }

  /** 到点或已取消时中止连接并失败，不进入可能无界阻塞的 read。 */
  private void ensureReadable() {
    if (Thread.currentThread().isInterrupted()) {
      interrupted.set(true);
      abort();
      throw new StorageReadInterruptedException("blob read interrupted: " + objectKey);
    }
    if (deadline.isExpired()) {
      timedOut.set(true);
      abort();
      throw timeoutFailure(null);
    }
  }

  private StorageReadTimeoutException timeoutFailure(Throwable cause) {
    StorageReadTimeoutException failure =
        cause == null
            ? new StorageReadTimeoutException("blob read timed out: " + objectKey)
            : new StorageReadTimeoutException("blob read timed out: " + objectKey, cause);
    RuntimeException watchdogError = abortError.get();
    if (watchdogError != null) {
      failure.addSuppressed(watchdogError);
    }
    return failure;
  }

  private void onDeadline() {
    if (finished.get()) {
      // 已在 close 中注销，不能再用 abort 覆盖正常完成的读取。
      return;
    }
    timedOut.set(true);
    abort();
  }

  private void abort() {
    try {
      object.abort();
    } catch (RuntimeException error) {
      // abort 失败时不能静默：读取仍可能无界阻塞，把失败附到超时结论上。
      abortError.compareAndSet(null, error);
    }
  }
}
