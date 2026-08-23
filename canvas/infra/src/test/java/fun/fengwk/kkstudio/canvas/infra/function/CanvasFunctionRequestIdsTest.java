package fun.fengwk.kkstudio.canvas.infra.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** requestId 不做隐式 trim，且拒绝空值、控制字符和超长输入。 */
class CanvasFunctionRequestIdsTest {

  @Test
  void validatesExactRequestId() {
    assertEquals("request-1", CanvasFunctionRequestIds.validate("request-1"));
    assertThrows(IllegalArgumentException.class, () -> CanvasFunctionRequestIds.validate(null));
    assertThrows(IllegalArgumentException.class, () -> CanvasFunctionRequestIds.validate(""));
    assertThrows(IllegalArgumentException.class, () -> CanvasFunctionRequestIds.validate(" r"));
    assertThrows(IllegalArgumentException.class, () -> CanvasFunctionRequestIds.validate("r "));
    assertThrows(IllegalArgumentException.class, () -> CanvasFunctionRequestIds.validate("r\n"));
    assertThrows(
        IllegalArgumentException.class, () -> CanvasFunctionRequestIds.validate("r\u0000x"));
    assertThrows(
        IllegalArgumentException.class, () -> CanvasFunctionRequestIds.validate("r".repeat(129)));
  }
}
