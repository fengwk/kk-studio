package fun.fengwk.kkstudio.platform.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

/** PluginDescriptor 校验安装身份与固定 region 候选，测试锁定其拒绝路径与无副作用的查询方法。 */
class PluginDescriptorTest {

  private static PluginDescriptor descriptor(String pluginId, List<String> regions) {
    return new PluginDescriptor(pluginId, "Display", "1.0.0", regions);
  }

  @Test
  void exposesIdentityAndRegionCandidates() {
    PluginDescriptor descriptor = descriptor("acme.plugin-1", List.of("CN", "en"));

    assertEquals("acme.plugin-1", descriptor.pluginId());
    assertEquals("Display", descriptor.name());
    assertEquals("1.0.0", descriptor.version());
    assertEquals(List.of("CN", "en"), descriptor.regions());
    assertTrue(descriptor.supportsAuthentication());
  }

  @Test
  void regionMatchingIsCaseSensitiveAndNullSafe() {
    PluginDescriptor descriptor = descriptor("acme.plugin", List.of("CN"));

    assertTrue(descriptor.acceptsRegion("CN"));
    assertFalse(descriptor.acceptsRegion("cn"));
    assertFalse(descriptor.acceptsRegion(null));
    assertFalse(descriptor.acceptsRegion("other"));
  }

  @Test
  void emptyRegionsMeansNoAuthenticationInteraction() {
    PluginDescriptor descriptor = descriptor("acme.plugin", List.of());

    assertFalse(descriptor.supportsAuthentication());
    assertFalse(descriptor.acceptsRegion("CN"));
  }

  @Test
  void rejectsNonCanonicalPluginId() {
    assertThrows(IllegalArgumentException.class, () -> descriptor(null, List.of()));
    assertThrows(IllegalArgumentException.class, () -> descriptor("", List.of()));
    assertThrows(IllegalArgumentException.class, () -> descriptor("Bad.Id", List.of()));
    assertThrows(IllegalArgumentException.class, () -> descriptor("a..b", List.of()));
    assertThrows(IllegalArgumentException.class, () -> descriptor("a".repeat(65), List.of()));
  }

  @Test
  void rejectsInvalidNameAndVersion() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginDescriptor("acme.plugin", null, "1.0.0", List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginDescriptor("acme.plugin", " ", "1.0.0", List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginDescriptor("acme.plugin", " name", "1.0.0", List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginDescriptor("acme.plugin", "name", "1.0.0 ", List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginDescriptor("acme.plugin", "name", "v".repeat(65), List.of()));
  }

  @Test
  void rejectsInvalidRegionCandidates() {
    assertThrows(
        NullPointerException.class,
        () -> new PluginDescriptor("acme.plugin", "name", "1.0.0", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginDescriptor("acme.plugin", "name", "1.0.0", List.of("CN CN")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginDescriptor("acme.plugin", "name", "1.0.0", List.of("CN", "CN")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PluginDescriptor(
                "acme.plugin",
                "name",
                "1.0.0",
                List.of("r1", "r2", "r3", "r4", "r5", "r6", "r7", "r8", "r9")));
  }
}
