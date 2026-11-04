package fun.fengwk.kkstudio.harness.runtime.entry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;

/** {@link ExecutionSelection} 的 driver 格式、JSON 校验与规范化排序契约。 */
class ExecutionSelectionTest {

  @Test
  void acceptsCanonicalDriverAndSortsSettingsJsonKeys() {
    ExecutionSelection selection =
        new ExecutionSelection("builtin", "{\"modelName\":\"claude\",\"variant\":\"v1\"}");
    assertEquals("builtin", selection.driver());
    assertEquals("{\"modelName\":\"claude\",\"variant\":\"v1\"}", selection.settings());
    assertEquals(selection.settingsJson(), selection.settings());

    // 键乱序及空白会被规范化为字典序且无空白
    ExecutionSelection unsorted =
        new ExecutionSelection(
            "builtin", "{\n  \"variant\" : \"v1\" ,\n  \"modelName\" : \"claude\"\n}");
    assertEquals(selection, unsorted);
    assertEquals(selection.hashCode(), unsorted.hashCode());
    assertEquals("{\"modelName\":\"claude\",\"variant\":\"v1\"}", unsorted.settings());
  }

  @Test
  void acceptsEmptyObject() {
    ExecutionSelection empty = new ExecutionSelection("fake", "{}");
    assertEquals("fake", empty.driver());
    assertEquals("{}", empty.settings());
  }

  @Test
  void rejectsInvalidDriver() {
    assertThrows(NullPointerException.class, () -> new ExecutionSelection(null, "{}"));
    assertThrows(IllegalArgumentException.class, () -> new ExecutionSelection("", "{}"));
    assertThrows(IllegalArgumentException.class, () -> new ExecutionSelection("123", "{}"));
    assertThrows(IllegalArgumentException.class, () -> new ExecutionSelection("Builtin", "{}"));
    assertThrows(
        IllegalArgumentException.class, () -> new ExecutionSelection("builtin_driver", "{}"));
    assertThrows(
        IllegalArgumentException.class, () -> new ExecutionSelection("a".repeat(33), "{}"));
  }

  @Test
  void rejectsInvalidSettingsJson() {
    assertThrows(NullPointerException.class, () -> new ExecutionSelection("builtin", null));
    assertThrows(IllegalArgumentException.class, () -> new ExecutionSelection("builtin", ""));
    assertThrows(
        IllegalArgumentException.class, () -> new ExecutionSelection("builtin", "not json"));
    assertThrows(IllegalArgumentException.class, () -> new ExecutionSelection("builtin", "[]"));
    assertThrows(
        IllegalArgumentException.class, () -> new ExecutionSelection("builtin", "\"string\""));
    assertThrows(IllegalArgumentException.class, () -> new ExecutionSelection("builtin", "123"));
  }
}
