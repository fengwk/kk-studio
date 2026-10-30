package fun.fengwk.kkstudio.harness.runtime.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 验证 admission 的容量、无等待与 lease 恰好释放语义。 */
class ConcurrencyAdmissionTest {

  @Test
  void requiresPositiveLimit() {
    assertThrows(IllegalArgumentException.class, () -> new ConcurrencyAdmission(0));
    assertThrows(IllegalArgumentException.class, () -> new ConcurrencyAdmission(-1));
  }

  @Test
  void rejectsAtCapacityAndIdempotentlyReleasesExactlyOnce() {
    ConcurrencyAdmission admission = new ConcurrencyAdmission(2);
    ConcurrencyAdmission.Lease first = admission.tryAcquire().orElseThrow();
    ConcurrencyAdmission.Lease second = admission.tryAcquire().orElseThrow();

    // 第 N+1 次必须立即拒绝，不能隐式等待或排队。
    assertTrue(admission.tryAcquire().isEmpty());

    first.close();
    first.close();
    ConcurrencyAdmission.Lease replacement = admission.tryAcquire().orElseThrow();
    assertNotNull(replacement);
    assertFalse(admission.tryAcquire().isPresent());

    replacement.close();
    second.close();
    second.close();
    assertEquals(2, countAvailable(admission, 2));
  }

  @Test
  void concurrentAcquireNeverExceedsLimit() throws Exception {
    int limit = 3;
    int callers = 12;
    ConcurrencyAdmission admission = new ConcurrencyAdmission(limit);
    ExecutorService executor = Executors.newFixedThreadPool(callers);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(callers);
    List<ConcurrencyAdmission.Lease> leases = new ArrayList<>();
    Object monitor = new Object();
    try {
      for (int i = 0; i < callers; i++) {
        executor.execute(
            () -> {
              try {
                start.await();
                Optional<ConcurrencyAdmission.Lease> acquired = admission.tryAcquire();
                acquired.ifPresent(
                    lease -> {
                      synchronized (monitor) {
                        leases.add(lease);
                      }
                    });
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              } finally {
                finished.countDown();
              }
            });
      }
      start.countDown();
      assertTrue(finished.await(5, TimeUnit.SECONDS));
      assertTrue(leases.size() <= limit);
    } finally {
      leases.forEach(ConcurrencyAdmission.Lease::close);
      executor.shutdownNow();
    }
  }

  private static int countAvailable(ConcurrencyAdmission admission, int expected) {
    List<ConcurrencyAdmission.Lease> leases = new ArrayList<>();
    try {
      for (int i = 0; i < expected; i++) {
        leases.add(admission.tryAcquire().orElseThrow());
      }
      return leases.size();
    } finally {
      leases.forEach(ConcurrencyAdmission.Lease::close);
    }
  }
}
