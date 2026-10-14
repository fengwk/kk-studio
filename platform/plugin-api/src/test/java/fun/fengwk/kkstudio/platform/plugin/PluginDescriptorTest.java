package fun.fengwk.kkstudio.platform.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** PluginDescriptor 只保存安装身份，测试锁定三字段与拒绝路径。 */
class PluginDescriptorTest {

  @Test
  void exposesIdentity() {
    PluginDescriptor descriptor = new PluginDescriptor("acme.plugin-1", "Display", "1.0.0");

    assertEquals("acme.plugin-1", descriptor.pluginId());
    assertEquals("Display", descriptor.name());
    assertEquals("1.0.0", descriptor.version());
  }

  @Test
  void rejectsNonCanonicalPluginId() {
    assertThrows(IllegalArgumentException.class, () -> new PluginDescriptor(null, "n", "1"));
    assertThrows(IllegalArgumentException.class, () -> new PluginDescriptor("", "n", "1"));
    assertThrows(IllegalArgumentException.class, () -> new PluginDescriptor("Bad.Id", "n", "1"));
    assertThrows(IllegalArgumentException.class, () -> new PluginDescriptor("a..b", "n", "1"));
    assertThrows(
        IllegalArgumentException.class, () -> new PluginDescriptor("a".repeat(65), "n", "1"));
  }

  @Test
  void rejectsInvalidNameAndVersion() {
    assertThrows(
        IllegalArgumentException.class, () -> new PluginDescriptor("acme.plugin", null, "1"));
    assertThrows(
        IllegalArgumentException.class, () -> new PluginDescriptor("acme.plugin", " ", "1"));
    assertThrows(
        IllegalArgumentException.class, () -> new PluginDescriptor("acme.plugin", " name", "1"));
    assertThrows(
        IllegalArgumentException.class, () -> new PluginDescriptor("acme.plugin", "name", "1 "));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginDescriptor("acme.plugin", "name", "v".repeat(65)));
  }
}
