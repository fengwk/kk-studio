package fun.fengwk.kkstudio.platform.environment.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/** heartbeat 超时在每次判定现读快照。 */
class EnvironmentDaemonGatewaySettingsLiveTest {

  @Test
  void heartbeatTimeoutReadsCurrentSnapshot() {
    SystemSettingsSnapshot snapshot = new SystemSettingsSnapshot(SystemSettings.DEFAULT);
    EnvironmentGatewayProperties properties = new EnvironmentGatewayProperties();
    properties.setDaemonToken("test-token");
    EnvironmentDaemonGateway gateway =
        new EnvironmentDaemonGateway(
            mock(LiveEnvironmentRegistry.class),
            properties,
            snapshot,
            Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC),
            environmentName -> {});

    assertEquals(Duration.ofMillis(60_000L), gateway.heartbeatTimeout());
    snapshot.replace(withHeartbeatTimeout(15_000L));
    assertEquals(Duration.ofMillis(15_000L), gateway.heartbeatTimeout());
  }

  private static SystemSettings withHeartbeatTimeout(long heartbeatTimeoutMillis) {
    SystemSettings.Environment base = SystemSettings.DEFAULT.environment();
    return new SystemSettings(
        SystemSettings.Tool.DEFAULT,
        SystemSettings.AiRuntime.DEFAULT,
        new SystemSettings.Environment(
            base.maxResourceBytes(), heartbeatTimeoutMillis, base.directoryListTimeoutMillis()),
        SystemSettings.Integrations.DEFAULT,
        SystemSettings.StorageMedia.DEFAULT,
        SystemSettings.Advanced.DEFAULT);
  }
}
