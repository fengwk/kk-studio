package fun.fengwk.kkstudio.harness.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PluginIdTest {

  @Test
  void acceptsCanonicalLowercaseDottedDashedIdentifiers() {
    assertEquals("core", new PluginId("core").value());
    assertEquals("com.example.goal", new PluginId("com.example.goal").value());
    assertEquals("pi-base", new PluginId("pi-base").value());
    assertEquals("a1.b2-c3", new PluginId("a1.b2-c3").value());
    assertEquals("0", new PluginId("0").value());
  }

  @Test
  void acceptsMaximumBoundaryLength() {
    String max = "a".repeat(PluginId.MAX_LENGTH);
    assertEquals(max, new PluginId(max).value());
    String tooLong = "a".repeat(PluginId.MAX_LENGTH + 1);
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> new PluginId(tooLong));
    assertTrue(error.getMessage().contains("at most " + PluginId.MAX_LENGTH + " chars"));
  }

  @Test
  void rejectsInvalidIdentifiers() {
    assertThrows(NullPointerException.class, () -> new PluginId(null));
    assertThrows(IllegalArgumentException.class, () -> new PluginId(""));
    assertThrows(IllegalArgumentException.class, () -> new PluginId(" "));
    assertThrows(IllegalArgumentException.class, () -> new PluginId("Core"));
    assertThrows(IllegalArgumentException.class, () -> new PluginId("core_plugin"));
    assertThrows(IllegalArgumentException.class, () -> new PluginId("-core"));
    assertThrows(IllegalArgumentException.class, () -> new PluginId("core-"));
    assertThrows(IllegalArgumentException.class, () -> new PluginId("com..example"));
    assertThrows(IllegalArgumentException.class, () -> new PluginId("com.-example"));
    assertThrows(IllegalArgumentException.class, () -> new PluginId("a b"));
    assertThrows(IllegalArgumentException.class, () -> new PluginId("a\u00e9"));
  }
}
