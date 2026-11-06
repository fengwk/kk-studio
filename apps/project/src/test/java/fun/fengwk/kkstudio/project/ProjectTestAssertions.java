package fun.fengwk.kkstudio.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.function.Executable;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;

/** Project 领域失败测试断言：同时检查类型和稳定 code。 */
public final class ProjectTestAssertions {
  private ProjectTestAssertions() {}

  public static DomainException assertValidation(Executable action) {
    return assertDomain(DomainErrorCode.VALIDATION, action);
  }

  public static DomainException assertNotFound(Executable action) {
    return assertDomain(DomainErrorCode.NOT_FOUND, action);
  }

  public static DomainException assertVersionConflict(Executable action) {
    return assertDomain(DomainErrorCode.VERSION_CONFLICT, action);
  }

  public static DomainException assertDuplicate(Executable action) {
    return assertDomain(DomainErrorCode.DUPLICATE, action);
  }

  private static DomainException assertDomain(DomainErrorCode code, Executable action) {
    DomainException error = assertThrows(DomainException.class, action);
    assertEquals(code, error.code());
    return error;
  }
}
