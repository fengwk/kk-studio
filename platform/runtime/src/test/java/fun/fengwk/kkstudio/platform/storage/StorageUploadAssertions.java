package fun.fengwk.kkstudio.platform.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.function.Executable;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;

import java.util.concurrent.ExecutionException;

/** 存储上传完整性/可消费性失败的统一断言：409 冲突 + 稳定原因 STORAGE_VERIFICATION。 */
final class StorageUploadAssertions {

  private StorageUploadAssertions() {}

  static DomainException assertStorageVerification(Executable operation, String messagePart) {
    DomainException error = assertThrows(DomainException.class, operation);
    assertStorageVerification(error, messagePart);
    return error;
  }

  static DomainException assertValidation(Executable operation, String messagePart) {
    DomainException error = assertThrows(DomainException.class, operation);
    assertEquals(DomainErrorCode.VALIDATION, error.code());
    assertTrue(error.getMessage().contains(messagePart), "actual: " + error.getMessage());
    return error;
  }

  static DomainException assertStorageVerificationCause(ExecutionException failure) {
    Throwable cause = failure.getCause();
    DomainException error = assertInstanceOf(DomainException.class, cause);
    assertStorageVerification(error, "");
    return error;
  }

  private static void assertStorageVerification(DomainException error, String messagePart) {
    assertEquals(DomainErrorCode.CONFLICT, error.code());
    assertEquals("STORAGE_VERIFICATION", error.reason());
    assertTrue(error.getMessage().contains(messagePart), "actual: " + error.getMessage());
  }
}
