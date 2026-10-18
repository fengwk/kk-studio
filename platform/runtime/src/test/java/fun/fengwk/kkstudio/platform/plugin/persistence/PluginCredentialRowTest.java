package fun.fengwk.kkstudio.platform.plugin.persistence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;

import java.time.Instant;

/** {@link PluginCredentialRow} 的租约判定、持久状态校验与去敏输出。 */
class PluginCredentialRowTest {

  private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

  /** 只有尚未过期的租约才算刷新在途。 */
  @Test
  void refreshingRequiresAnUnexpiredLease() {
    assertFalse(row(null).refreshing(NOW));
    assertFalse(row(NOW).refreshing(NOW));
    assertTrue(row(NOW.plusSeconds(1)).refreshing(NOW));
  }

  /** 投影态 NOT_CONNECTED 不能作为持久状态。 */
  @Test
  void rejectsTheProjectionOnlyStatus() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PluginCredentialRow(
                "p",
                "{}",
                null,
                null,
                null,
                PluginCredentialStatus.NOT_CONNECTED,
                null,
                null,
                null,
                null,
                0L));
  }

  /** toString 绝不输出载荷。 */
  @Test
  void toStringRedactsThePayload() {
    assertFalse(row(null).toString().contains("secret"));
  }

  private static PluginCredentialRow row(Instant leaseUntil) {
    return new PluginCredentialRow(
        "p",
        "{\"token\":\"secret\"}",
        "CN",
        NOW.plusSeconds(60),
        NOW,
        PluginCredentialStatus.CONNECTED,
        null,
        null,
        leaseUntil == null ? null : "lease",
        leaseUntil,
        1L);
  }
}
