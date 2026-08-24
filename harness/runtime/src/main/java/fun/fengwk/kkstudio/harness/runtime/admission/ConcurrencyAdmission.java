package fun.fengwk.kkstudio.harness.runtime.admission;

import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 线程安全、无等待、无队列的进程内并发 admission。
 *
 * <p>成功取得的 {@link Lease} 必须在对应执行的完整生命周期结束时关闭；关闭幂等且恰好归还一个 permit。
 */
public final class ConcurrencyAdmission {

  private final Semaphore permits;

  public ConcurrencyAdmission(int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    this.permits = new Semaphore(limit);
  }

  /** 无等待尝试取得一个 permit；容量耗尽时立即返回空结果。 */
  public Optional<Lease> tryAcquire() {
    return permits.tryAcquire() ? Optional.of(new AdmissionLease(permits)) : Optional.empty();
  }

  /** 一次 admission 的幂等释放句柄。 */
  @FunctionalInterface
  public interface Lease extends AutoCloseable {

    @Override
    void close();
  }

  private static final class AdmissionLease implements Lease {

    private final Semaphore permits;
    private final AtomicBoolean closed = new AtomicBoolean();

    private AdmissionLease(Semaphore permits) {
      this.permits = permits;
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        permits.release();
      }
    }
  }
}
