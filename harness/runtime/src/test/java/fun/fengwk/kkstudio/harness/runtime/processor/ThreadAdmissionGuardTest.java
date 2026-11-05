package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;

import java.util.UUID;

/** ThreadAdmissionGuard 轻量级 claim 准入防重守卫测试。 */
class ThreadAdmissionGuardTest {

  private static final UUID TOKEN_1 = UUID.randomUUID();
  private static final UUID TOKEN_2 = UUID.randomUUID();

  @Test
  void rejectsDuplicateTokenUntilReleased() {
    ThreadAdmissionGuard guard = new ThreadAdmissionGuard();

    assertTrue(guard.tryAdmit(TestIds.id(1), TOKEN_1));
    assertFalse(guard.tryAdmit(TestIds.id(1), TOKEN_1));

    guard.release(TestIds.id(1), TOKEN_1);
    assertTrue(guard.tryAdmit(TestIds.id(1), TOKEN_1));
  }

  @Test
  void staleReleaseDoesNotRemoveActiveToken() {
    ThreadAdmissionGuard guard = new ThreadAdmissionGuard();

    assertTrue(guard.tryAdmit(TestIds.id(1), TOKEN_2));

    // 旧 token-1 的迟到 release 不影响 token-2
    guard.release(TestIds.id(1), TOKEN_1);
    assertFalse(guard.tryAdmit(TestIds.id(1), TOKEN_2));

    guard.release(TestIds.id(1), TOKEN_2);
    assertTrue(guard.tryAdmit(TestIds.id(1), TOKEN_2));
  }

  @Test
  void rejectsConcurrentAdmissionsForSameThread() {
    ThreadAdmissionGuard guard = new ThreadAdmissionGuard();

    assertTrue(guard.tryAdmit(TestIds.id(1), TOKEN_1));
    // 另一个 token 在当前 token 运行期间并发准入：拒绝
    assertFalse(guard.tryAdmit(TestIds.id(1), TOKEN_2));

    guard.release(TestIds.id(1), TOKEN_1);
    assertTrue(guard.tryAdmit(TestIds.id(1), TOKEN_2));
  }
}
