package fun.fengwk.kkstudio.platform.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

/**
 * 启动期 classpath Plugin 安装注册表契约测试。
 *
 * <p>目录收集 Spring 容器内的 StudioPlugin bean，启动期一次性冻结，支持按 pluginId 排序存储、查找与唯一性校验。
 */
class StudioPluginRegistryTest {

  private StudioPlugin createMockPlugin(String pluginId, String name) {
    PluginDescriptor descriptor = new PluginDescriptor(pluginId, name, "1.0", List.of("CN"));
    return new StudioPlugin() {
      @Override
      public PluginDescriptor descriptor() {
        return descriptor;
      }
    };
  }

  /** 空列表可正常构造，size() 为 0，查询均返回 empty。 */
  @Test
  void constructsWithEmptyList() {
    StudioPluginRegistry registry = new StudioPluginRegistry(List.of());

    assertEquals(0, registry.size());
    assertTrue(registry.plugins().isEmpty());
    assertTrue(registry.pluginIds().isEmpty());
    assertTrue(registry.descriptors().isEmpty());
    assertTrue(registry.find("any-plugin").isEmpty());
    assertTrue(registry.find(null).isEmpty());
  }

  /**
   * 安装目录的遍历顺序是管理面与刷新 dispatcher 的稳定契约：三个集合都必须按 {@code pluginId} 升序，而不是哈希顺序。
   *
   * <p>用 10 个哈希顺序与字典序明显不同的 id：如果实现用 {@code Map.copyOf} 之类不保留插入顺序的包装，断言会立即失败。
   */
  @Test
  void freezesPluginsInAscendingPluginIdOrder() {
    List<String> declared =
        List.of(
            "plugin-h",
            "plugin-a",
            "plugin-z",
            "plugin-b",
            "plugin-m",
            "plugin-c",
            "plugin-y",
            "plugin-d",
            "plugin-k",
            "plugin-e");
    List<StudioPlugin> plugins =
        declared.stream().map(id -> createMockPlugin(id, "Plugin " + id)).toList();
    List<String> expected =
        List.of(
            "plugin-a",
            "plugin-b",
            "plugin-c",
            "plugin-d",
            "plugin-e",
            "plugin-h",
            "plugin-k",
            "plugin-m",
            "plugin-y",
            "plugin-z");

    StudioPluginRegistry registry = new StudioPluginRegistry(plugins);

    assertEquals(expected, registry.pluginIds(), "pluginIds() must be ascending by pluginId");
    assertEquals(
        expected,
        registry.descriptors().stream().map(PluginDescriptor::pluginId).toList(),
        "descriptors() must follow pluginIds() order");
    assertEquals(
        expected,
        registry.plugins().stream().map(plugin -> plugin.descriptor().pluginId()).toList(),
        "plugins() must follow pluginIds() order");
    assertEquals(declared.size(), registry.size());
  }

  /** find() 命中时返回对应 StudioPlugin，未命中或为 null 时返回 Optional.empty()。 */
  @Test
  void findHitsAndMisses() {
    StudioPlugin pluginA = createMockPlugin("plugin-a", "Plugin A");
    StudioPlugin pluginB = createMockPlugin("plugin-b", "Plugin B");

    StudioPluginRegistry registry = new StudioPluginRegistry(List.of(pluginA, pluginB));

    Optional<StudioPlugin> hit = registry.find("plugin-a");
    assertTrue(hit.isPresent());
    assertEquals("plugin-a", hit.get().descriptor().pluginId());

    Optional<StudioPlugin> miss = registry.find("plugin-unknown");
    assertFalse(miss.isPresent());

    Optional<StudioPlugin> nullFind = registry.find(null);
    assertFalse(nullFind.isPresent());
  }

  /** 重复 pluginId 必须在构造期抛出 IllegalStateException，且消息中包含重复 pluginId 与双方类名。 */
  @Test
  void rejectsDuplicatePluginIdDuringConstruction() {
    StudioPlugin first = createMockPlugin("dupe-plugin", "First Dupe");
    StudioPlugin second = createMockPlugin("dupe-plugin", "Second Dupe");

    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class, () -> new StudioPluginRegistry(List.of(first, second)));

    assertTrue(
        ex.getMessage().contains("duplicate StudioPlugin pluginId: dupe-plugin"),
        "error message must mention duplicate pluginId");
    assertTrue(
        ex.getMessage().contains(first.getClass().getName()),
        "error message must mention conflicting class name");
  }
}
