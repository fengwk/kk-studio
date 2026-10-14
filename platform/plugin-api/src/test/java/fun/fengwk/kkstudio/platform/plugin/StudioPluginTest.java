package fun.fengwk.kkstudio.platform.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

/** StudioPlugin 的可选认证与刷新能力默认缺席，测试锁定未实现时管理面不开放额外动作。 */
class StudioPluginTest {

  @Test
  void optionalCapabilitiesDefaultToEmpty() {
    StudioPlugin plugin = () -> new PluginDescriptor("acme.plugin", "Display", "1.0.0", List.of());

    assertEquals("acme.plugin", plugin.descriptor().pluginId());
    assertTrue(plugin.authHandler().isEmpty());
    assertTrue(plugin.refresher().isEmpty());
  }
}
