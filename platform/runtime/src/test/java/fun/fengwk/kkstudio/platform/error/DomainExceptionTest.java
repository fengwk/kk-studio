package fun.fengwk.kkstudio.platform.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/** 单一领域异常包含各类错误码、resource、固定消息与版本坐标。 */
class DomainExceptionTest {

  @Test
  void validationAndReferenceFailuresKeepTheirCodeAndResource() {
    IllegalStateException cause = new IllegalStateException("cause");
    DomainException validation = DomainException.validation("agent_provider", "invalid", cause);
    assertEquals(DomainErrorCode.VALIDATION, validation.code());
    assertEquals("agent_provider", validation.resource());
    assertEquals("invalid", validation.getMessage());
    assertSame(cause, validation.getCause());

    DomainException duplicate = DomainException.duplicate("model", "duplicate");
    assertEquals(DomainErrorCode.DUPLICATE, duplicate.code());
    DomainException inUse = DomainException.inUse("provider", "in use");
    assertEquals(DomainErrorCode.IN_USE, inUse.code());
  }

  @Test
  void notFoundUsesTheStableMessageWithoutACause() {
    DomainException notFound = DomainException.notFound("agent");

    assertEquals(DomainErrorCode.RESOURCE_NOT_FOUND, notFound.code());
    assertEquals("agent", notFound.resource());
    assertEquals("agent not found", notFound.getMessage());
    assertNull(notFound.getCause());
  }

  @Test
  void versionConflictKeepsExpectedAndActualCoordinates() {
    DomainException conflict = DomainException.versionConflict("agent_model", "3", "4");

    assertEquals(DomainErrorCode.VERSION_CONFLICT, conflict.code());
    assertEquals("agent_model version conflict: expected=3 actual=4", conflict.getMessage());
    assertEquals("3", conflict.expectedVersion());
    assertEquals("4", conflict.actualVersion());
  }

  @Test
  void environmentFailuresUseTheEnvironmentResource() {
    DomainException ide = DomainException.ideUnavailable("ide down");
    assertEquals(DomainErrorCode.IDE_UNAVAILABLE, ide.code());
    assertEquals("environment", ide.resource());

    DomainException runtime = DomainException.environmentUnavailable("runtime down");
    assertEquals(DomainErrorCode.ENVIRONMENT_UNAVAILABLE, runtime.code());
    assertEquals("environment", runtime.resource());
  }

  @Test
  void upstreamKeepsItsResourceAndCode() {
    DomainException upstream = DomainException.upstream("agent_provider", "HTTP 401");

    assertEquals(DomainErrorCode.UPSTREAM_FAILED, upstream.code());
    assertEquals("agent_provider", upstream.resource());
    assertEquals("HTTP 401", upstream.getMessage());
  }
}
