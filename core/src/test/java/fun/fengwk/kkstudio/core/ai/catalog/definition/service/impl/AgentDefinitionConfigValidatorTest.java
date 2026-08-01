package fun.fengwk.kkstudio.core.ai.catalog.definition.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactories;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactory;
import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static Agent config validation: tool names must come from local ToolFactories or the fixed {@link
 * EnvironmentToolCatalog}, and skill names must respect the bounded length rule.
 */
class AgentDefinitionConfigValidatorTest {

  @Test
  void acceptsStaticEnvironmentToolAndLocalToolNames() {
    String envTool = EnvironmentToolCatalog.descriptors().get(0).name();
    try (Fixture fixture = new Fixture(List.of(platformTool("create_goal", "1")))) {
      AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
      config.setTools(List.of(envTool, "create_goal"));
      config.setSkills(List.of("dev", "ops"));
      assertDoesNotThrow(() -> fixture.validator.validate(config));
    }
  }

  @Test
  void rejectsUnknownToolName() {
    try (Fixture fixture = new Fixture(List.of(platformTool("create_goal", "1")))) {
      AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
      config.setTools(List.of("missing"));
      config.setSkills(List.of());
      assertTrue(
          assertThrows(IllegalArgumentException.class, () -> fixture.validator.validate(config))
              .getMessage()
              .contains("unknown agent tool"));
    }
  }

  @Test
  void rejectsSkillNameExceeding128Characters() {
    try (Fixture fixture = new Fixture(List.of())) {
      String tooLong = "x".repeat(129);
      AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
      config.setTools(List.of());
      config.setSkills(List.of(tooLong));
      assertTrue(
          assertThrows(IllegalArgumentException.class, () -> fixture.validator.validate(config))
              .getMessage()
              .contains("agent skill name must be <= 128 characters"));
    }
  }

  @Test
  void rejectsRuntimeManagedLoadSkillSelection() {
    try (Fixture fixture = new Fixture(List.of(platformTool("create_goal", "1")))) {
      AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
      config.setTools(List.of("load_skill"));
      config.setSkills(List.of());

      assertThrows(IllegalArgumentException.class, () -> fixture.validator.validate(config));
    }
  }

  @Test
  void rejectsDuplicatePlatformToolRegistration() {
    // Two distinct factories claiming the same (name, version) is rejected at the ToolFactories
    // construction boundary — the validator never sees ambiguous tool names.
    ToolDescriptor descriptor = platformDescriptor("dup", "1");
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new ToolFactories(
                    List.of(
                        ToolFactory.singleton(tool(descriptor)),
                        ToolFactory.singleton(tool(descriptor)))));
    assertTrue(error.getMessage().contains("duplicate ToolFactory"));
  }

  private static Tool platformTool(String name, String version) {
    return tool(platformDescriptor(name, version));
  }

  private static ToolDescriptor platformDescriptor(String name, String version) {
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
    private final ToolFactories toolFactories;
    private final AgentDefinitionConfigValidator validator;

    private Fixture(List<Tool> tools) {
      this.toolFactories = new ToolFactories(tools.stream().map(ToolFactory::singleton).toList());
      this.validator =
          new AgentDefinitionConfigValidator(
              new ToolCatalog(toolFactories.descriptors(), Set.of("load_skill")));
    }

    @Override
    public void close() {}
  }
}
