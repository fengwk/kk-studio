package fun.fengwk.kkstudio.platform.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Instant;

/** PluginCredentialSnapshot 只有两种形态，且输出时不泄露载荷。 */
class PluginCredentialSnapshotTest {

  private static final Instant EXPIRES_AT = Instant.parse("2030-01-01T00:00:00Z");

  @Test
  void interactiveSnapshotCarriesRegionAndExpiry() {
    assertTrue(new PluginCredentialSnapshot("p", "CN", EXPIRES_AT, 3, "{}").interactive());
    assertFalse(new PluginCredentialSnapshot("p", null, null, 0, "{}").interactive());
  }

  @Test
  void toStringRedactsPayload() {
    String text =
        new PluginCredentialSnapshot("p", "CN", EXPIRES_AT, 3, "{\"secret\":1}").toString();

    assertTrue(text.contains("<redacted>"));
    assertFalse(text.contains("secret"));
  }

  @Test
  void rejectsMissingFieldsNegativeVersionAndMixedRouteMetadata() {
    assertThrows(
        NullPointerException.class, () -> new PluginCredentialSnapshot(null, null, null, 0, "{}"));
    assertThrows(
        NullPointerException.class, () -> new PluginCredentialSnapshot("p", null, null, 0, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginCredentialSnapshot("p", null, null, -1, "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginCredentialSnapshot("p", "CN", null, 0, "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginCredentialSnapshot("p", null, EXPIRES_AT, 0, "{}"));
  }
}
