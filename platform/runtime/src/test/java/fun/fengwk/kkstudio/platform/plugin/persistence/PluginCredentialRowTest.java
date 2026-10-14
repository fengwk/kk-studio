package fun.fengwk.kkstudio.platform.plugin.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;

import java.time.Instant;
import java.util.UUID;

/** {@link PluginCredentialRow} 的防御性拷贝、认证身份（代际 + 上下文）相等语义、校验与去敏输出契约。 */
class PluginCredentialRowTest {

  private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");
  private static final UUID GENERATION = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID OTHER_GENERATION =
      UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String CONTEXT = "c".repeat(64);
  private static final String OTHER_CONTEXT = "d".repeat(64);

  @Test
  void copiesEncryptedPayloadDefensivelyAndReportsLease() {
    byte[] payload = {1, 2, 3};
    PluginCredentialRow row = row(GENERATION, payload, null);

    payload[0] = 9;
    assertArrayEquals(new byte[] {1, 2, 3}, row.encryptedPayload());
    byte[] exposed = row.encryptedPayload();
    exposed[0] = 9;
    assertArrayEquals(new byte[] {1, 2, 3}, row.encryptedPayload());
    assertFalse(row.leased());

    PluginCredentialRow leased = row(GENERATION, new byte[] {1}, "lease-token");
    assertTrue(leased.leased());
  }

  @Test
  void equalityIncludesAuthGenerationAndContentIdentity() {
    PluginCredentialRow first = row(GENERATION, new byte[] {1, 2, 3}, null);
    PluginCredentialRow same = row(GENERATION, new byte[] {1, 2, 3}, null);
    PluginCredentialRow otherGeneration = row(OTHER_GENERATION, new byte[] {1, 2, 3}, null);
    PluginCredentialRow otherPayload = row(GENERATION, new byte[] {4, 5, 6}, null);
    PluginCredentialRow otherContext =
        new PluginCredentialRow(
            "acme.plugin",
            GENERATION,
            OTHER_CONTEXT,
            new byte[] {1, 2, 3},
            "CN",
            NOW.plusSeconds(3600),
            NOW,
            PluginCredentialStatus.CONNECTED,
            null,
            null,
            null,
            null,
            0L,
            NOW,
            NOW);

    assertEquals(first, same);
    assertEquals(first.hashCode(), same.hashCode());
    assertNotEquals(first, otherGeneration);
    assertNotEquals(first, otherPayload);
    assertNotEquals(first, otherContext, "the auth context fingerprint is part of the identity");
    assertTrue(first.equals(first));
    assertFalse(first.equals(null));
    assertFalse(first.equals("not-a-row"));
  }

  @Test
  void toStringCarriesGenerationButRedactsCiphertext() {
    String text = row(GENERATION, new byte[] {9, 9, 9}, "lease-token").toString();

    assertTrue(text.contains(GENERATION.toString()));
    assertTrue(text.contains(CONTEXT), "the auth context fingerprint belongs to the row identity");
    assertTrue(text.contains("<redacted>"));
    assertFalse(text.contains("9, 9, 9"));
  }

  @Test
  void rejectsMissingFieldsNonPersistedStatusAndNegativeVersion() {
    assertThrows(NullPointerException.class, () -> row(null, new byte[] {1}, null));
    assertThrows(NullPointerException.class, () -> row(GENERATION, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PluginCredentialRow(
                "p",
                GENERATION,
                CONTEXT,
                new byte[] {1},
                "CN",
                NOW.plusSeconds(60),
                NOW,
                PluginCredentialStatus.NOT_CONNECTED,
                null,
                null,
                null,
                null,
                0L,
                NOW,
                NOW));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PluginCredentialRow(
                "p",
                GENERATION,
                CONTEXT,
                new byte[] {1},
                "CN",
                NOW.plusSeconds(60),
                NOW,
                PluginCredentialStatus.CONNECTED,
                null,
                null,
                null,
                null,
                -1L,
                NOW,
                NOW));
  }

  private static PluginCredentialRow row(UUID generation, byte[] payload, String leaseToken) {
    return new PluginCredentialRow(
        "acme.plugin",
        generation,
        CONTEXT,
        payload,
        "CN",
        NOW.plusSeconds(3600),
        NOW,
        PluginCredentialStatus.CONNECTED,
        null,
        null,
        leaseToken,
        leaseToken == null ? null : NOW.plusSeconds(60),
        0L,
        NOW,
        NOW);
  }
}
