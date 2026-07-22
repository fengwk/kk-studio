package fun.fengwk.kkstudio.harness.kernel.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Failure 字段约束测试。 */
class FailureTest {

  @Test
  void carriesCodeAndMessage() {
    Failure failure = new Failure("LEASE_LOST", "lease expired at T1");

    assertEquals("LEASE_LOST", failure.code());
    assertEquals("lease expired at T1", failure.message());
  }

  @Test
  void rejectsBlankCode() {
    assertThrows(IllegalArgumentException.class, () -> new Failure("", "msg"));
    assertThrows(IllegalArgumentException.class, () -> new Failure("   ", "msg"));
  }

  @Test
  void rejectsNullCode() {
    assertThrows(NullPointerException.class, () -> new Failure(null, "msg"));
  }

  @Test
  void rejectsNullMessage() {
    assertThrows(NullPointerException.class, () -> new Failure("CODE", null));
  }
}
