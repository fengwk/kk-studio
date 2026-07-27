package fun.fengwk.kkstudio.core.agent.definition.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.environment.gateway.EnvironmentDaemonConnection;
import fun.fengwk.kkstudio.core.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolExecutionLocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactories;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactory;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolCapabilitiesCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolCapabilitiesCodec.DaemonToolCapabilities;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.share.model.AgentDefinitionConfigDTO;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Agent create/update live capability validation: READY env, short names, platform priority. */
class AgentDefinitionLiveCapabilityValidatorTest {

  private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

  @Test
  void acceptsPlatformToolAndPlatformSkillWithOptionalEnvironment() {
    try (Fixture fixture = new Fixture(List.of(cloudTool("read", "1")))) {
      fixture.readyEnvironment(
          LiveEnvironmentRegistry.PLATFORM_ENVIRONMENT_NAME,
          List.of(),
          List.of(new DaemonSkillDescriptor("dev", "Developer rules")));
      fixture.readyEnvironment(
          "local-dev",
          List.of(environmentTool("bash", "1")),
          List.of(new DaemonSkillDescriptor("project", "Project skill")));

      AgentDefinitionConfigDTO config = config();
      config.setEnvironmentName("local-dev");
      config.setTools(List.of("read", "bash"));
      config.setSkills(List.of("dev", "project"));
      assertDoesNotThrow(() -> fixture.validator.validate(config));
    }
  }

  @Test
  void rejectsUnknownToolsSkillsOfflineEnvironmentAndLongNames() {
    try (Fixture fixture = new Fixture(List.of(cloudTool("read", "1")))) {
      fixture.readyEnvironment(
          LiveEnvironmentRegistry.PLATFORM_ENVIRONMENT_NAME,
          List.of(),
          List.of(new DaemonSkillDescriptor("dev", "Developer rules")));

      AgentDefinitionConfigDTO unknownTool = config();
      unknownTool.setTools(List.of("missing"));
      assertTrue(
          assertThrows(
                  IllegalArgumentException.class, () -> fixture.validator.validate(unknownTool))
              .getMessage()
              .contains("unknown agent tool"));

      AgentDefinitionConfigDTO unknownSkill = config();
      unknownSkill.setSkills(List.of("missing"));
      assertTrue(
          assertThrows(
                  IllegalArgumentException.class, () -> fixture.validator.validate(unknownSkill))
              .getMessage()
              .contains("unknown agent skill"));

      AgentDefinitionConfigDTO offline = config();
      offline.setEnvironmentName("gone");
      assertTrue(
          assertThrows(IllegalArgumentException.class, () -> fixture.validator.validate(offline))
              .getMessage()
              .contains("not READY"));

      AgentDefinitionConfigDTO longName = config();
      longName.setTools(List.of("environment:local/bash@1"));
      assertTrue(
          assertThrows(IllegalArgumentException.class, () -> fixture.validator.validate(longName))
              .getMessage()
              .contains("short names"));
    }
  }

  @Test
  void platformSkillNameShadowsEnvironmentSkillAndRejectsEnvironmentOnlyWhenUnknown() {
    try (Fixture fixture = new Fixture(List.of())) {
      fixture.readyEnvironment(
          LiveEnvironmentRegistry.PLATFORM_ENVIRONMENT_NAME,
          List.of(),
          List.of(new DaemonSkillDescriptor("shared", "from platform")));
      fixture.readyEnvironment(
          "local-dev",
          List.of(),
          List.of(
              new DaemonSkillDescriptor("shared", "from env"),
              new DaemonSkillDescriptor("only-env", "env only")));

      AgentDefinitionConfigDTO config = config();
      config.setEnvironmentName("local-dev");
      config.setSkills(List.of("shared", "only-env"));
      assertDoesNotThrow(() -> fixture.validator.validate(config));

      AgentDefinitionConfigDTO noEnv = config();
      noEnv.setSkills(List.of("only-env"));
      assertThrows(IllegalArgumentException.class, () -> fixture.validator.validate(noEnv));
    }
  }

  @Test
  void rejectsDuplicatePlatformToolRegistration() {
    // Two distinct factories claiming the same (name, version) is rejected at the ToolFactories
    // construction boundary — the validator never sees ambiguous tool names.
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new ToolFactories(
                    List.of(
                        ToolFactory.singleton(cloudTool("dup", "1")),
                        ToolFactory.singleton(cloudTool("dup", "1")))));
    assertTrue(error.getMessage().contains("duplicate ToolFactory"));
  }

  private static Tool cloudTool(String name, String version) {
    return tool(name, version, ToolExecutionLocation.PLATFORM);
  }

  private static AgentDefinitionConfigDTO config() {
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of());
    return config;
  }

  private static ToolDescriptor environmentTool(String name, String version) {
    return descriptor(name, version, ToolExecutionLocation.ENVIRONMENT);
  }

  private static Tool tool(String name, String version, ToolExecutionLocation mode) {
    ToolDescriptor descriptor = descriptor(name, version, mode);
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

  private static ToolDescriptor descriptor(
      String name, String version, ToolExecutionLocation mode) {
    return new ToolDescriptor(
        name,
        version,
        name + " tool",
        name,
        new ToolParamsSchema("", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(5));
  }

  private static final class Fixture implements AutoCloseable {
    private final LiveEnvironmentRegistry environments =
        new LiveEnvironmentRegistry(new DaemonToolCapabilitiesCodec());
    private final ToolFactories toolFactories;
    private final AgentDefinitionLiveCapabilityValidator validator;

    private Fixture(List<Tool> tools) {
      this.toolFactories = new ToolFactories(tools.stream().map(ToolFactory::singleton).toList());
      this.validator = new AgentDefinitionLiveCapabilityValidator(environments, toolFactories);
    }

    private void readyEnvironment(
        String name, List<ToolDescriptor> tools, List<DaemonSkillDescriptor> skills) {
      EnvironmentDaemonConnection connection =
          new EnvironmentDaemonConnection() {
            @Override
            public String connectionId() {
              return "conn-" + name;
            }

            @Override
            public boolean isOpen() {
              return true;
            }

            @Override
            public void sendText(String text) {}

            @Override
            public void close() {}
          };
      environments.tryBind(name, connection, NOW);
      environments.updateCapabilities(
          name, connection, new DaemonToolCapabilities(tools, skills), NOW);
      environments.markReady(name, connection, NOW);
    }

    @Override
    public void close() {}
  }
}
