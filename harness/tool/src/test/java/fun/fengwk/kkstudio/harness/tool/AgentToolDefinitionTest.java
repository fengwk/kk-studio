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

  private static final AgentToolId ID = new AgentToolId("base.read");
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "read",
          "1.0.0",
          "Read a file",
          "read",
          new InputSchema("Read input", Map.of(), Set.of(), false),
          ToolSideEffect.READ_ONLY,
          Duration.ZERO);

  /** 验证三个组成字段均必须存在，避免公共契约产生不完整定义。 */
  @Test
  void rejectsNullFields() {
    assertThrows(
        NullPointerException.class,
        () -> new AgentToolDefinition(null, DESCRIPTOR, ToolVisibility.SELECTABLE));
    assertThrows(
        NullPointerException.class,
        () -> new AgentToolDefinition(ID, null, ToolVisibility.SELECTABLE));
    assertThrows(NullPointerException.class, () -> new AgentToolDefinition(ID, DESCRIPTOR, null));
  }

  /** 验证相同三字段的定义使用 record 的 value equality。 */
  @Test
  void comparesDefinitionsByValue() {
    AgentToolDefinition first = new AgentToolDefinition(ID, DESCRIPTOR, ToolVisibility.INTERNAL);
    AgentToolDefinition second = new AgentToolDefinition(ID, DESCRIPTOR, ToolVisibility.INTERNAL);

    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
  }

  /** 验证正常构造与访问器正确返回各字段。 */
  @Test
  void constructsAndExposesComponents() {
    AgentToolDefinition definition =
        new AgentToolDefinition(ID, DESCRIPTOR, ToolVisibility.SELECTABLE);
    assertEquals(ID, definition.id());
    assertEquals(DESCRIPTOR, definition.descriptor());
    assertEquals(ToolVisibility.SELECTABLE, definition.visibility());
  }
}
