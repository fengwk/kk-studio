package fun.fengwk.kkstudio.canvas.infra.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;

/** requestId 不做隐式 trim，且拒绝空值、控制字符和超长输入。 */
class CanvasFunctionRequestIdsTest {

  @Test
  void validatesExactRequestId() {
    assertEquals("request-1", CanvasFunctionRequestIds.validate("request-1"));
    for (String invalid : new String[] {null, "", " r", "r ", "r\n", "r\u0000x", "r".repeat(129)}) {
      DomainException rejected =
          assertThrows(
              DomainException.class, () -> CanvasFunctionRequestIds.validate(invalid), invalid);
      assertEquals(DomainErrorCode.VALIDATION, rejected.code());
    }
  }
}
