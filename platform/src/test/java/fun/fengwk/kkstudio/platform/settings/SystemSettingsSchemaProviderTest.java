package fun.fengwk.kkstudio.platform.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsSchemaDTO;

import java.lang.reflect.RecordComponent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Schema 与 SystemSettings record 的可编辑 leaf 契约测试。 */
class SystemSettingsSchemaProviderTest {

  private static final Set<String> CUSTOM_ATOMIC_LEAVES =
      Set.of("tool.permission", "aiRuntime.compactionFallbackModel");

  @Test
  void schemaPathsExactlyCoverEditableRecordLeavesAndMetadataKeysAreUnique() {
    SystemSettingsSchemaDTO schema = new SystemSettingsSchemaProvider().get();
    Set<String> expectedPaths = new HashSet<>();
    collectRecordLeaves(SystemSettings.class, "", expectedPaths);
    Set<String> actualPaths = new HashSet<>();
    Set<String> sectionKeys = new HashSet<>();
    Set<String> groupKeys = new HashSet<>();

    for (SystemSettingsSchemaDTO.SectionDTO section : schema.getSections()) {
      assertTrue(sectionKeys.add(section.getKey()), "duplicate section key: " + section.getKey());
      for (SystemSettingsSchemaDTO.GroupDTO group : section.getGroups()) {
        assertTrue(groupKeys.add(group.getKey()), "duplicate group key: " + group.getKey());
        for (SystemSettingsSchemaDTO.FieldDTO field : group.getFields()) {
          assertTrue(actualPaths.add(field.getPath()), "duplicate field path: " + field.getPath());
          assertTrue(field.getType() != null, "schema field type is required: " + field.getPath());
        }
      }
    }

    // 反射集合与 schema 集合必须逐项相等，防止新增 record leaf 后只更新 UI 数量而漏掉路径。
    assertEquals(expectedPaths, actualPaths);
  }

  @Test
  void sectionOrderMatchesTheBaselineSettingsTabs() {
    // 基线 settings-tabs 顺序（General 在前端本地）：aiRuntime -> tool -> environment
    // -> network -> integrations -> storageMedia -> advanced；精确顺序断言防止重构破坏 tabs 布局。
    assertEquals(
        List.of(
            "aiRuntime",
            "tool",
            "environment",
            "network",
            "integrations",
            "storageMedia",
            "advanced"),
        new SystemSettingsSchemaProvider()
            .get().getSections().stream().map(SystemSettingsSchemaDTO.SectionDTO::getKey).toList());
  }

  @Test
  void networkDescribesOnlyGlobalProxyWithRestartTimingAndTextBounds() {
    // 精确 schema 契约阻止运行期开关、模块覆盖或 nullable/长度边界漂移。
    SystemSettingsSchemaDTO.SectionDTO section =
        new SystemSettingsSchemaProvider().get().getSections().get(3);
    assertEquals("network", section.getKey());
    assertEquals("settings.tabs.network", section.getLabelKey());
    assertEquals("settings.section.network.description", section.getDescriptionKey());
    assertTrue(section.isRestartRequired());
    assertEquals(1, section.getGroups().size());
    SystemSettingsSchemaDTO.GroupDTO group = section.getGroups().get(0);
    assertEquals("network.proxy", group.getKey());
    assertEquals("settings.section.network.proxy.title", group.getLabelKey());
    assertEquals("settings.section.network.proxy.description", group.getDescriptionKey());
    assertTrue(group.isRestartRequired());
    assertEquals(SystemSettingsSchemaDTO.ApplyTiming.RESTART, group.getApplyTiming());
    assertEquals(2, group.getFields().size());
    for (SystemSettingsSchemaDTO.FieldDTO field : group.getFields()) {
      assertEquals(SystemSettingsSchemaDTO.FieldType.TEXT, field.getType());
      assertEquals("settings.field." + field.getPath(), field.getLabelKey());
      assertEquals(field.getLabelKey() + ".hint", field.getHintKey());
    }
    assertEquals("network.proxyUrl", group.getFields().get(0).getPath());
    assertEquals(true, group.getFields().get(0).isNullable());
    assertEquals(2048, group.getFields().get(0).getMax());
    assertEquals("network.noProxyHosts", group.getFields().get(1).getPath());
    assertEquals(false, group.getFields().get(1).isNullable());
    assertEquals(4096, group.getFields().get(1).getMax());
  }

  private static void collectRecordLeaves(Class<?> type, String prefix, Set<String> paths) {
    for (RecordComponent component : type.getRecordComponents()) {
      String path = prefix.isEmpty() ? component.getName() : prefix + "." + component.getName();
      if (CUSTOM_ATOMIC_LEAVES.contains(path) || !component.getType().isRecord()) {
        paths.add(path);
      } else {
        collectRecordLeaves(component.getType(), path, paths);
      }
    }
  }
}
