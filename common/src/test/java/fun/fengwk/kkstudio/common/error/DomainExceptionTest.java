package fun.fengwk.kkstudio.common.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * 通用领域异常与错误码契约测试。
 *
 * <p>验证既有保留工厂、新增工厂（自定义 notFound、conflict 系列、unavailable 系列、validationReason）、 reason
 * 校验（必须为非空大写下划线字符串）以及各属性 getter 的行为。
 */
class DomainExceptionTest {

  @Test
  void errorCodeEnumValuesAndLowercaseCodes() {
    // 验证所有枚举定义及其对外契约的 lowercase code 字符串。
    assertEquals("validation", DomainErrorCode.VALIDATION.code());
    assertEquals("not_found", DomainErrorCode.NOT_FOUND.code());
    assertEquals("version_conflict", DomainErrorCode.VERSION_CONFLICT.code());
    assertEquals("conflict", DomainErrorCode.CONFLICT.code());
    assertEquals("duplicate", DomainErrorCode.DUPLICATE.code());
    assertEquals("in_use", DomainErrorCode.IN_USE.code());
    assertEquals("unavailable", DomainErrorCode.UNAVAILABLE.code());
    assertEquals("upstream_failed", DomainErrorCode.UPSTREAM_FAILED.code());
    assertEquals("unauthorized", DomainErrorCode.UNAUTHORIZED.code());
    assertEquals("internal", DomainErrorCode.INTERNAL.code());
  }

  @Test
  void preservedValidationAndReferenceApis() {
    // 验证保留的既有校验、唯一性冲突、资源在用与上游异常工厂保持正确的状态码、resource 与 cause。
    IllegalStateException cause = new IllegalStateException("cause");
    DomainException validation = DomainException.validation("provider", "invalid", cause);
    assertEquals(DomainErrorCode.VALIDATION, validation.code());
    assertEquals("provider", validation.resource());
    assertEquals("invalid", validation.getMessage());
    assertSame(cause, validation.getCause());
    assertNull(validation.reason());
    assertNull(validation.errors());

    DomainException validationNoCause = DomainException.validation("provider", "invalid");
    assertEquals(DomainErrorCode.VALIDATION, validationNoCause.code());
    assertNull(validationNoCause.getCause());

    DomainException duplicate = DomainException.duplicate("model", "duplicate");
    assertEquals(DomainErrorCode.DUPLICATE, duplicate.code());
    assertEquals("model", duplicate.resource());
    assertEquals("duplicate", duplicate.getMessage());

    DomainException duplicateWithCause = DomainException.duplicate("model", "duplicate", cause);
    assertEquals(DomainErrorCode.DUPLICATE, duplicateWithCause.code());
    assertSame(cause, duplicateWithCause.getCause());

    DomainException inUse = DomainException.inUse("provider", "in use");
    assertEquals(DomainErrorCode.IN_USE, inUse.code());
    assertEquals("provider", inUse.resource());

    DomainException inUseWithCause = DomainException.inUse("provider", "in use", cause);
    assertEquals(DomainErrorCode.IN_USE, inUseWithCause.code());
    assertSame(cause, inUseWithCause.getCause());

    DomainException upstream = DomainException.upstream("provider", "HTTP 502");
    assertEquals(DomainErrorCode.UPSTREAM_FAILED, upstream.code());
    assertEquals("provider", upstream.resource());
    assertEquals("HTTP 502", upstream.getMessage());
  }

  @Test
  void preservedNotFoundAndVersionConflictApis() {
    // 验证保留的 notFound(resource) 与 versionConflict 的稳定默认文案与坐标。
    DomainException notFound = DomainException.notFound("agent");
    assertEquals(DomainErrorCode.NOT_FOUND, notFound.code());
    assertEquals("agent", notFound.resource());
    assertEquals("agent not found", notFound.getMessage());
    assertNull(notFound.getCause());
    assertNull(notFound.reason());

    DomainException conflict = DomainException.versionConflict("model", 3L, 4L);
    assertEquals(DomainErrorCode.VERSION_CONFLICT, conflict.code());
    assertEquals("model", conflict.resource());
    assertEquals("model version conflict: expected=3 actual=4", conflict.getMessage());
    assertEquals(3L, conflict.expectedVersion());
    assertEquals(4L, conflict.actualVersion());
    assertNull(conflict.reason());
  }

  @Test
  void notFoundWithCustomMessage() {
    // 验证新增的 notFound(resource, message) 支持业务定制消息同时保留 resource 与 code。
    DomainException notFound = DomainException.notFound("session", "session 123 expired");
    assertEquals(DomainErrorCode.NOT_FOUND, notFound.code());
    assertEquals("session", notFound.resource());
    assertEquals("session 123 expired", notFound.getMessage());
    assertNull(notFound.getCause());
    assertNull(notFound.reason());
  }

  @Test
  void conflictApisRequireValidReasonAndPreserveErrors() {
    // 验证 conflict 系列工厂必须提供符合大写下划线格式的 reason，并正确携带 message、cause 与只读 errors 详情。
    DomainException simple =
        DomainException.conflict("STALE_COMMAND_CURSOR", "command cursor expired");
    assertEquals(DomainErrorCode.CONFLICT, simple.code());
    assertEquals("STALE_COMMAND_CURSOR", simple.reason());
    assertEquals("command cursor expired", simple.getMessage());
    assertNull(simple.resource());
    assertNull(simple.errors());
    assertNull(simple.getCause());

    IllegalStateException cause = new IllegalStateException("db lock");
    DomainException withCause =
        DomainException.conflict("THREAD_ID_REUSED", "thread id already assigned", cause);
    assertEquals(DomainErrorCode.CONFLICT, withCause.code());
    assertEquals("THREAD_ID_REUSED", withCause.reason());
    assertSame(cause, withCause.getCause());

    Map<String, Object> details = new HashMap<>();
    details.put("cursor", 42L);
    details.put("action", "REPLAY");
    DomainException withErrors =
        DomainException.conflict("PARTIAL_COMMAND_REPLAY", "replay incomplete", details);
    assertEquals(DomainErrorCode.CONFLICT, withErrors.code());
    assertEquals("PARTIAL_COMMAND_REPLAY", withErrors.reason());
    assertEquals(42L, withErrors.errors().get("cursor"));
    assertEquals("REPLAY", withErrors.errors().get("action"));

    // 验证 errors 不受外部修改影响且本身只读
    details.put("cursor", 99L);
    assertEquals(42L, withErrors.errors().get("cursor"));
    assertThrows(
        UnsupportedOperationException.class, () -> withErrors.errors().put("new_key", "value"));

    DomainException nullErrors =
        DomainException.conflict(
            "EXIT_UNCONFIRMED", "exit unconfirmed", (Map<String, Object>) null);
    assertNull(nullErrors.errors());
  }

  @Test
  void unavailableAndValidationReasonApis() {
    // 验证 unavailable 与 validationReason 工厂正确设置 code 与 reason，并支持可选 cause。
    DomainException envUnavailable =
        DomainException.unavailable("ENVIRONMENT_UNAVAILABLE", "environment offline");
    assertEquals(DomainErrorCode.UNAVAILABLE, envUnavailable.code());
    assertEquals("ENVIRONMENT_UNAVAILABLE", envUnavailable.reason());
    assertEquals("environment offline", envUnavailable.getMessage());
    assertNull(envUnavailable.resource());
    assertNull(envUnavailable.getCause());

    IllegalStateException cause = new IllegalStateException("timeout");
    DomainException ideUnavailable =
        DomainException.unavailable("IDE_UNAVAILABLE", "ide connection timeout", cause);
    assertEquals(DomainErrorCode.UNAVAILABLE, ideUnavailable.code());
    assertEquals("IDE_UNAVAILABLE", ideUnavailable.reason());
    assertSame(cause, ideUnavailable.getCause());

    DomainException closeCancelled =
        DomainException.validationReason("IDE_CLOSE_CANCELLED", "close cancelled by user");
    assertEquals(DomainErrorCode.VALIDATION, closeCancelled.code());
    assertEquals("IDE_CLOSE_CANCELLED", closeCancelled.reason());
    assertEquals("close cancelled by user", closeCancelled.getMessage());
    assertNull(closeCancelled.resource());
  }

  @Test
  void reasonValidationRejectsInvalidFormats() {
    // 验证所有接受 reason 的工厂对 null、空串、小写、空格、中划线等非大写下划线字符串统一抛出 IllegalArgumentException。
    String[] invalidReasons = {
      null,
      "",
      "   ",
      "stale_command",
      "StaleCommand",
      "STALE-COMMAND",
      "STALE COMMAND",
      "_STALE",
      "STALE_",
      "STALE__COMMAND"
    };

    for (String invalid : invalidReasons) {
      assertThrows(
          IllegalArgumentException.class,
          () -> DomainException.conflict(invalid, "conflict message"));
      assertThrows(
          IllegalArgumentException.class,
          () -> DomainException.conflict(invalid, "conflict message", new RuntimeException()));
      assertThrows(
          IllegalArgumentException.class,
          () -> DomainException.conflict(invalid, "conflict message", Map.of()));
      assertThrows(
          IllegalArgumentException.class,
          () -> DomainException.unavailable(invalid, "unavailable message"));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              DomainException.unavailable(invalid, "unavailable message", new RuntimeException()));
      assertThrows(
          IllegalArgumentException.class,
          () -> DomainException.validationReason(invalid, "validation message"));
    }
  }
}
