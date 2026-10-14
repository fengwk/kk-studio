package fun.fengwk.kkstudio.platform.plugin.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;

import java.time.Instant;

/** PluginCredentialProjection 是管理面安全投影，测试锁定缺席态不透出任何行内元数据。 */
class PluginCredentialProjectionTest {

  private static final Instant EXPIRES_AT = Instant.parse("2030-01-01T00:00:00Z");

  @Test
  void notConnectedProjectionExposesNoMetadata() {
    PluginCredentialProjection projection = PluginCredentialProjection.notConnected();

    assertEquals(PluginCredentialStatus.NOT_CONNECTED, projection.status());
    assertNull(projection.region());
    assertNull(projection.expiresAt());
    assertNull(projection.nextRefreshAt());
    assertNull(projection.lastRefreshedAt());
    assertNull(projection.lastRefreshError());
    assertFalse(projection.connected());
  }

  @Test
  void keyUnavailableProjectionIsOpaque() {
    PluginCredentialProjection projection = PluginCredentialProjection.keyUnavailable();

    assertEquals(PluginCredentialStatus.KEY_UNAVAILABLE, projection.status());
    assertNull(projection.region());
    assertNull(projection.lastRefreshError());
    assertFalse(projection.connected());
  }

  @Test
  void connectedOnlyForConnectedStatus() {
    PluginCredentialProjection connected =
        new PluginCredentialProjection(
            PluginCredentialStatus.CONNECTED, "CN", EXPIRES_AT, EXPIRES_AT, null, null);
    PluginCredentialProjection rejected =
        new PluginCredentialProjection(
            PluginCredentialStatus.REAUTH_REQUIRED, "CN", EXPIRES_AT, EXPIRES_AT, null, "rejected");

    assertTrue(connected.connected());
    assertFalse(rejected.connected());
  }
}
