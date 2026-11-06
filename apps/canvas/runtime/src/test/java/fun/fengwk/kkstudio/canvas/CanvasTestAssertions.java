package fun.fengwk.kkstudio.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.function.Executable;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;

/** Canvas 测试共享的领域校验失败断言。 */
public final class CanvasTestAssertions {
  private CanvasTestAssertions() {}

  public static DomainException assertValidation(Executable executable) {
    DomainException error = assertThrows(DomainException.class, executable);
    assertEquals(DomainErrorCode.VALIDATION, error.code());
    return error;
  }
}
