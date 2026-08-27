package fun.fengwk.kkstudio.platform.catalog.definition.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.plugin.api.PluginCatalog;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactory;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.platform.harness.tool.AgentToolRegistry;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Agent 配置的静态校验：工具 ID 必须来自统一 AgentToolRegistry 且对应可选择条目，且 skill 名必须遵守有界长度规则。 */
class AgentDefinitionConfigValidatorTest {

  private static final AgentToolId CUSTOM_TOOL_ID = new AgentToolId("test.custom-tool");
  private static final AgentToolId LOAD_SKILL_TOOL_ID = new AgentToolId("test.load-skill");
  private static final AgentToolId DUPLICATE_FIRST_TOOL_ID =
      new AgentToolId("test.duplicate-first");
  private static final AgentToolId DUPLICATE_SECOND_TOOL_ID =
      new AgentToolId("test.duplicate-second");

  @Test
  void acceptsEnvironmentAndHostToolIds() {
    String environmentToolId =
        EnvironmentToolCatalog.entries().getFirst().definition().id().toString();
    try (Fixture fixture = new Fixture(List.of(hostTool("create_goal", "1")))) {
      AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
      config.setToolIds(List.of(environmentToolId, CUSTOM_TOOL_ID.toString()));
      config.setSkills(List.of("dev", "ops"));
      config.setSubagents(List.of("reviewer"));
      assertDoesNotThrow(() -> fixture.validator.validate(config));
    }
  }

  @Test
  void rejectsUnknownToolId() {
    try (Fixture fixture = new Fixture(List.of(hostTool("create_goal", "1")))) {
      AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
      config.setToolIds(List.of("test.missing"));
      config.setSkills(List.of());
      config.setSubagents(List.of());
      assertTrue(
          assertThrows(IllegalArgumentException.class, () -> fixture.validator.validate(config))
              .getMessage()
              .contains("unknown agent tool id"));
    }
  }

  @Test
  void rejectsSkillNameExceeding128Characters() {
    try (Fixture fixture = new Fixture(List.of())) {
      String tooLong = "x".repeat(129);
      AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
      config.setToolIds(List.of());
      config.setSkills(List.of(tooLong));
      config.setSubagents(List.of());
      assertTrue(
          assertThrows(IllegalArgumentException.class, () -> fixture.validator.validate(config))
              .getMessage()
              .contains("agent skill name must be <= 128 characters"));
    }
  }

  @Test
  void rejectsInternalToolSelection() {
    try (Fixture fixture = new Fixture(List.of(hostTool("create_goal", "1")))) {
      AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
      config.setToolIds(List.of(LOAD_SKILL_TOOL_ID.toString()));
      config.setSkills(List.of());
      config.setSubagents(List.of());

      IllegalArgumentException error =
          assertThrows(IllegalArgumentException.class, () -> fixture.validator.validate(config));
      assertTrue(error.getMessage().contains("internal tool"));
    }
  }

  @Test
  void rejectsSubagentNameExceeding64Characters() {
    try (Fixture fixture = new Fixture(List.of())) {
      AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
      config.setToolIds(List.of());
      config.setSkills(List.of());
      config.setSubagents(List.of("x".repeat(65)));

      assertTrue(
          assertThrows(IllegalArgumentException.class, () -> fixture.validator.validate(config))
              .getMessage()
              .contains("subagent name must be <= 64 characters"));
    }
  }

  @Test
  void rejectsDuplicateHostToolRegistration() {
    // 两个不同工厂声明相同 model-visible name 会在统一 registry 构造边界被拒绝。
    ToolDescriptor descriptor = hostDescriptor("dup", "1");
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new AgentToolRegistry(
                    List.of(
                        ToolFactory.singleton(DUPLICATE_FIRST_TOOL_ID, tool(descriptor)),
                        ToolFactory.singleton(DUPLICATE_SECOND_TOOL_ID, tool(descriptor))),
                    PluginCatalog.from(List.of()),
                    EnvironmentToolCatalog.entries()));
    assertTrue(error.getMessage().contains("duplicate Agent tool name"));
  }

  private static Tool hostTool(String name, String version) {
    return tool(hostDescriptor(name, version));
  }

  private static ToolDescriptor hostDescriptor(String name, String version) {
    return new ToolDescriptor(
        name,
        version,
        name + " tool",
        name,
        new ToolParamsSchema("", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(5));
  }

  private static Tool tool(ToolDescriptor descriptor) {
    return new Tool() {
      @Override
      public ToolDescriptor descriptor() {
        return descriptor;
      }

      @Override
      public ToolExecutionHandle execute(
          ToolExecutionRequest request, ToolExecutionListener listener) {
        throw new UnsupportedOperationException();
      }
    };
  }

  private static final class Fixture implements AutoCloseable {
    private final AgentToolRegistry toolRegistry;
    private final AgentDefinitionConfigValidator validator;

    private Fixture(List<Tool> tools) {
      List<Tool> registeredTools = new ArrayList<>(tools);
      Tool loadSkill = hostTool("load_skill", "1");
      List<ToolFactory> factories = new ArrayList<>(registeredTools.size() + 1);
      for (Tool tool : registeredTools) {
        factories.add(ToolFactory.singleton(CUSTOM_TOOL_ID, tool));
      }
      factories.add(ToolFactory.singleton(LOAD_SKILL_TOOL_ID, loadSkill, ToolVisibility.INTERNAL));
      this.toolRegistry =
          new AgentToolRegistry(
              factories, PluginCatalog.from(List.of()), EnvironmentToolCatalog.entries());
      this.validator = new AgentDefinitionConfigValidator(toolRegistry);
    }

    @Override
    public void close() {}
  }
}
