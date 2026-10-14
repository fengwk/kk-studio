package fun.fengwk.kkstudio.platform.plugin.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;

import java.util.UUID;

/** 冻结凭据引用的身份校验：任何非 canonical pluginId 或非 64 位小写指纹都必须被拒绝。 */
class PluginCredentialReferenceTest {

  private static final UUID GENERATION = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final String CONTEXT = "a1".repeat(32);

  @Test
  void acceptsCanonicalIdentityAndExposesExactCoordinates() {
    PluginCredentialReference reference =
        new PluginCredentialReference("minimax-mavis", GENERATION, CONTEXT);

    assertEquals("minimax-mavis", reference.pluginId());
    assertEquals(GENERATION, reference.authGeneration());
    assertEquals(CONTEXT, reference.contextFingerprint());
  }

  @Test
  void rejectsMissingBlankPaddedOrOversizedPluginId() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginCredentialReference(null, GENERATION, CONTEXT));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginCredentialReference("", GENERATION, CONTEXT));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginCredentialReference(" minimax-mavis", GENERATION, CONTEXT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PluginCredentialReference(
                "p".repeat(PluginDescriptor.MAX_PLUGIN_ID_LENGTH + 1), GENERATION, CONTEXT));
  }

  @Test
  void rejectsMissingGenerationAndInvalidFingerprint() {
    assertThrows(
        NullPointerException.class,
        () -> new PluginCredentialReference("minimax-mavis", null, CONTEXT));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginCredentialReference("minimax-mavis", GENERATION, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginCredentialReference("minimax-mavis", GENERATION, "A1".repeat(32)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginCredentialReference("minimax-mavis", GENERATION, "a1".repeat(31)));
  }
}
