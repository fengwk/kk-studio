package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClaimAdmissionGuardTest {

  /** 同一 token 在 active admission 期间只能进入一次；对称 release 后可再次进入。 */
  @Test
  void rejectsDuplicateTokenUntilReleased() {
    ClaimAdmissionGuard guard = new ClaimAdmissionGuard();

    assertTrue(guard.tryAdmit(1, "token-1"));
    assertFalse(guard.tryAdmit(1, "token-1"));

    guard.release(1, "token-1");
    assertTrue(guard.tryAdmit(1, "token-1"));
  }

  /** 新 lease token 可 supersede 旧 token；旧调用方的迟到 release 不能删除当前 admission。 */
  @Test
  void staleReleaseDoesNotRemoveSupersedingToken() {
    ClaimAdmissionGuard guard = new ClaimAdmissionGuard();

    assertTrue(guard.tryAdmit(1, "token-1"));
    assertTrue(guard.tryAdmit(1, "token-2"));

    guard.release(1, "token-1");
    assertFalse(guard.tryAdmit(1, "token-2"));

    guard.release(1, "token-2");
    assertTrue(guard.tryAdmit(1, "token-2"));
  }
}
