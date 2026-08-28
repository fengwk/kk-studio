package fun.fengwk.kkstudio.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** {@link AgentToolDefinition} 与 {@link AgentToolBackend} 公共契约测试。 */
class AgentToolDefinitionTest {

  private static final AgentToolId ID = new AgentToolId("base.read");
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "read",
          "1.0.0",
          "Read a file",
          "read",
          new ToolParamsSchema("Read input", Map.of(), Set.of(), false),
          ToolSideEffect.READ_ONLY,
          Duration.ZERO);

  /** 验证统一定义可以明确表达三种模型工具执行后端。 */
  @Test
  void supportsAllExecutionBackends() {
    assertEquals(AgentToolBackend.HOST, definition(AgentToolBackend.HOST).backend());
    assertEquals(AgentToolBackend.DECLARATIVE, definition(AgentToolBackend.DECLARATIVE).backend());
    assertEquals(
        AgentToolBackend.ENVIRONMENT_CAPABILITY,
        definition(AgentToolBackend.ENVIRONMENT_CAPABILITY).backend());
  }

  /** 验证四个组成字段均必须存在，避免公共契约产生不完整定义。 */
  @Test
  void rejectsNullFields() {
    assertThrows(
        NullPointerException.class,
        () ->
            new AgentToolDefinition(
                null, DESCRIPTOR, ToolVisibility.SELECTABLE, AgentToolBackend.HOST));
    assertThrows(
        NullPointerException.class,
        () -> new AgentToolDefinition(ID, null, ToolVisibility.SELECTABLE, AgentToolBackend.HOST));
    assertThrows(
        NullPointerException.class,
        () -> new AgentToolDefinition(ID, DESCRIPTOR, null, AgentToolBackend.HOST));
    assertThrows(
        NullPointerException.class,
        () -> new AgentToolDefinition(ID, DESCRIPTOR, ToolVisibility.SELECTABLE, null));
  }

  /** 验证相同四字段的定义使用 record 的 value equality。 */
  @Test
  void comparesDefinitionsByValue() {
    AgentToolDefinition first =
        new AgentToolDefinition(
            ID, DESCRIPTOR, ToolVisibility.INTERNAL, AgentToolBackend.DECLARATIVE);
    AgentToolDefinition second =
        new AgentToolDefinition(
            ID, DESCRIPTOR, ToolVisibility.INTERNAL, AgentToolBackend.DECLARATIVE);

    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
  }

  private static AgentToolDefinition definition(AgentToolBackend backend) {
    return new AgentToolDefinition(ID, DESCRIPTOR, ToolVisibility.SELECTABLE, backend);
  }
}
