package fun.fengwk.kkstudio.platform.environment.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentInstallCodeDTO;

import java.time.Instant;
import java.util.UUID;

/** 安装 code 固定五分钟，到期、篡改、错环境和换 key 都拒绝，且不需要真实等待。 */
class EnvironmentInstallCodesTest {
  private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");

  @Test
  void expiresExactlyFiveMinutesAndRejectsBoundary() {
    EnvironmentInstallCodeDTO issued = EnvironmentInstallCodes.issue(ID, "token", NOW);
    assertEquals(NOW.plusSeconds(300), issued.getExpiresAt());
    assertDoesNotThrow(
        () -> EnvironmentInstallCodes.verify(ID, "token", issued.getCode(), NOW.plusSeconds(299)));
    assertThrows(
        AiValidationException.class,
        () -> EnvironmentInstallCodes.verify(ID, "token", issued.getCode(), NOW.plusSeconds(300)));
  }

  @Test
  void rejectsWrongEnvironmentTamperingAndRotatedToken() {
    String code = EnvironmentInstallCodes.issue(ID, "token", NOW).getCode();
    UUID other = UUID.fromString("22222222-2222-2222-2222-222222222222");
    assertThrows(
        AiValidationException.class,
        () -> EnvironmentInstallCodes.verify(other, "token", code, NOW));
    assertThrows(
        AiValidationException.class,
        () -> EnvironmentInstallCodes.verify(ID, "rotated", code, NOW));
    assertThrows(
        AiValidationException.class,
        () -> EnvironmentInstallCodes.verify(ID, "token", code + "x", NOW));
    assertThrows(
        AiValidationException.class, () -> EnvironmentInstallCodes.verify(ID, "token", null, NOW));
    assertThrows(
        AiValidationException.class,
        () -> EnvironmentInstallCodes.verify(ID, "token", "no-dot", NOW));
    assertThrows(
        AiValidationException.class,
        () -> EnvironmentInstallCodes.verify(ID, "token", "1.2.3", NOW));
    assertThrows(
        AiValidationException.class,
        () -> EnvironmentInstallCodes.verify(ID, "token", "abc.sig", NOW));
    assertThrows(
        AiValidationException.class,
        () -> EnvironmentInstallCodes.verify(ID, "token", "1.@@@", NOW));
    assertDoesNotThrow(() -> EnvironmentInstallCodes.verify(ID, "token", code, NOW));
  }
}
