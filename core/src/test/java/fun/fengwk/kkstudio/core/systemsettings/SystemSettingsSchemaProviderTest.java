package fun.fengwk.kkstudio.core.systemsettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsSchemaDTO;

import java.lang.reflect.RecordComponent;
import java.util.HashSet;
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
