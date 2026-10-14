package fun.fengwk.kkstudio.platform.plugin.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

/** attempt 行的字段门禁：摘要与上下文指纹必须是 64 位小写 SHA-256，修订为正，region 有界且无 padding。 */
class PluginAuthAttemptRowTest {

  private static final String HASH = "a1".repeat(32);
  private static final String CONTEXT = "b2".repeat(32);
  private static final UUID GENERATION = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final Instant EXPIRES_AT = Instant.parse("2026-10-01T12:10:00Z");

  @Test
  void acceptsAFullySpecifiedAttempt() {
    PluginAuthAttemptRow row = row(HASH, CONTEXT, 3L, "CN");

    assertEquals("plugin.alpha", row.pluginId());
    assertEquals(HASH, row.nonceHash());
    assertEquals(GENERATION, row.authGeneration());
    assertEquals(CONTEXT, row.contextFingerprint());
    assertEquals(3L, row.configRevision());
    assertEquals("CN", row.region());
    assertEquals(EXPIRES_AT, row.expiresAt());
  }

  @Test
  void rejectsMissingOrMalformedDigests() {
    assertThrows(NullPointerException.class, () -> row(null, CONTEXT, 1L, "CN"));
    assertThrows(NullPointerException.class, () -> row(HASH, null, 1L, "CN"));
    assertThrows(IllegalArgumentException.class, () -> row("XYZ", CONTEXT, 1L, "CN"));
    assertThrows(IllegalArgumentException.class, () -> row(HASH, "A1".repeat(32), 1L, "CN"));
  }

  @Test
  void rejectsNonPositiveRevisionAndUnusableRegion() {
    assertThrows(IllegalArgumentException.class, () -> row(HASH, CONTEXT, 0L, "CN"));
    assertThrows(IllegalArgumentException.class, () -> row(HASH, CONTEXT, -1L, "CN"));
    assertThrows(IllegalArgumentException.class, () -> row(HASH, CONTEXT, 1L, " CN "));
    assertThrows(IllegalArgumentException.class, () -> row(HASH, CONTEXT, 1L, ""));
    assertThrows(IllegalArgumentException.class, () -> row(HASH, CONTEXT, 1L, "C".repeat(65)));
    assertThrows(NullPointerException.class, () -> row(HASH, CONTEXT, 1L, null));
  }

  private static PluginAuthAttemptRow row(
      String nonceHash, String contextFingerprint, long configRevision, String region) {
    return new PluginAuthAttemptRow(
        "plugin.alpha",
        nonceHash,
        GENERATION,
        contextFingerprint,
        configRevision,
        region,
        EXPIRES_AT);
  }
}
