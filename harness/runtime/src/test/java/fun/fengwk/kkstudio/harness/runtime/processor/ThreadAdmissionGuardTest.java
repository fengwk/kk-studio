package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;

/** ThreadAdmissionGuard 轻量级 claim 准入防重守卫测试。 */
class ThreadAdmissionGuardTest {

  @Test
  void rejectsDuplicateTokenUntilReleased() {
    ThreadAdmissionGuard guard = new ThreadAdmissionGuard();

    assertTrue(guard.tryAdmit(TestIds.id(1), "token-1"));
    assertFalse(guard.tryAdmit(TestIds.id(1), "token-1"));

    guard.release(TestIds.id(1), "token-1");
    assertTrue(guard.tryAdmit(TestIds.id(1), "token-1"));
  }

  @Test
  void staleReleaseDoesNotRemoveActiveToken() {
    ThreadAdmissionGuard guard = new ThreadAdmissionGuard();

    assertTrue(guard.tryAdmit(TestIds.id(1), "token-2"));

    // 旧 token-1 的迟到 release 不影响 token-2
    guard.release(TestIds.id(1), "token-1");
    assertFalse(guard.tryAdmit(TestIds.id(1), "token-2"));

    guard.release(TestIds.id(1), "token-2");
    assertTrue(guard.tryAdmit(TestIds.id(1), "token-2"));
  }

  @Test
  void rejectsConcurrentAdmissionsForSameThread() {
    ThreadAdmissionGuard guard = new ThreadAdmissionGuard();

    assertTrue(guard.tryAdmit(TestIds.id(1), "token-1"));
    // 另一个 token 在当前 token 运行期间并发准入：拒绝
    assertFalse(guard.tryAdmit(TestIds.id(1), "token-2"));

    guard.release(TestIds.id(1), "token-1");
    assertTrue(guard.tryAdmit(TestIds.id(1), "token-2"));
  }
}
