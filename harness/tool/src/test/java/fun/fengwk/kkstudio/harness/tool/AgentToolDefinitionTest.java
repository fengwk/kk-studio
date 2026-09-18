package fun.fengwk.kkstudio.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** {@link AgentToolDefinition} 公共契约测试。 */
class AgentToolDefinitionTest {

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "read",
          "Read a file",
          "read",
          new InputSchema("Read input", Map.of(), Set.of(), false),
          ToolSideEffect.READ_ONLY,
          Duration.ZERO);

  /** 验证两个组成字段均必须存在，避免公共契约产生不完整定义。 */
  @Test
  void rejectsNullFields() {
    assertThrows(
        NullPointerException.class, () -> new AgentToolDefinition(null, ToolVisibility.SELECTABLE));
    assertThrows(NullPointerException.class, () -> new AgentToolDefinition(DESCRIPTOR, null));
  }

  /** 验证相同字段的定义使用 record 的 value equality。 */
  @Test
  void comparesDefinitionsByValue() {
    AgentToolDefinition first = new AgentToolDefinition(DESCRIPTOR, ToolVisibility.INTERNAL);
    AgentToolDefinition second = new AgentToolDefinition(DESCRIPTOR, ToolVisibility.INTERNAL);

    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
  }

  /** 验证正常构造与访问器正确返回各字段；身份即 descriptor name。 */
  @Test
  void constructsAndExposesComponents() {
    AgentToolDefinition definition = new AgentToolDefinition(DESCRIPTOR, ToolVisibility.SELECTABLE);
    assertEquals(DESCRIPTOR, definition.descriptor());
    assertEquals("read", definition.descriptor().name());
    assertEquals(ToolVisibility.SELECTABLE, definition.visibility());
  }
}
