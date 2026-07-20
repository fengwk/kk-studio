package fun.fengwk.kkstudio.core.harness.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.agent.definition.repo.impl.mapper.AgentDefinitionMapper;
import fun.fengwk.kkstudio.core.agent.definition.repo.impl.model.AgentDefinitionDO;
import fun.fengwk.kkstudio.core.environment.gateway.EnvironmentDaemonConnection;
import fun.fengwk.kkstudio.core.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.harness.runtime.context.AgentRuntimeConfig;
import fun.fengwk.kkstudio.harness.runtime.context.SelectedSkillMetadata;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolCapabilitiesCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolCapabilitiesCodec.DaemonToolCapabilities;

import java.time.Instant;
import java.util.List;

/**
 * Dynamic Agent runtime resolution: prompt updates and platform-first skill metadata without Entry
 * Tree persistence.
 */
class HarnessAgentDefinitionSupportTest {

  private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

  @Test
  void resolvesDynamicPromptToolsSkillsAndPlatformPriority() {
    AgentDefinitionMapper mapper = mock(AgentDefinitionMapper.class);
    LiveEnvironmentRegistry environments =
        new LiveEnvironmentRegistry(new DaemonToolCapabilitiesCodec());
    ready(
        environments,
        LiveEnvironmentRegistry.PLATFORM_ENVIRONMENT_NAME,
        List.of(
            new DaemonSkillDescriptor("shared", "platform shared"),
            new DaemonSkillDescriptor("dev", "Developer rules")));
    ready(
        environments,
        "local-dev",
        List.of(
            new DaemonSkillDescriptor("shared", "env shared"),
            new DaemonSkillDescriptor("project", "Project skill")));

    AgentDefinitionDO definition =
        definition(
            9L, "first prompt", "local-dev", List.of("read"), List.of("shared", "project", "dev"));
    when(mapper.getById(9L)).thenReturn(definition);

    HarnessAgentDefinitionSupport support =
        new HarnessAgentDefinitionSupport(mapper, new ObjectMapper(), environments);

    AgentRuntimeConfig first = support.runtimeConfig(definition, "11", "quality", true);
    assertEquals("first prompt", first.systemPrompt());
    assertEquals("local-dev", first.environmentName());
    assertEquals(List.of("read"), first.tools());
    assertEquals(List.of("shared", "project", "dev"), first.skills());
    assertEquals(
        List.of(
            new SelectedSkillMetadata("shared", "platform shared", "platform"),
            new SelectedSkillMetadata("project", "Project skill", "local-dev"),
            new SelectedSkillMetadata("dev", "Developer rules", "platform")),
        first.selectedSkills());
    assertTrue(first.yoloEnabled());

    definition.setSystemPrompt("updated prompt");
    definition.setConfigJson(
        """
        {"environmentName":"local-dev","tools":["bash"],"skills":["dev"],"allowedSubagents":[],"executionPolicy":{}}
        """);
    AgentRuntimeConfig second = support.runtimeConfig(definition, "11", "quality", false);
    assertEquals("updated prompt", second.systemPrompt());
    assertEquals(List.of("bash"), second.tools());
    assertEquals(
        List.of(new SelectedSkillMetadata("dev", "Developer rules", "platform")),
        second.selectedSkills());
  }

  @Test
  void failsWhenSelectedEnvironmentIsOffline() {
    AgentDefinitionMapper mapper = mock(AgentDefinitionMapper.class);
    LiveEnvironmentRegistry environments =
        new LiveEnvironmentRegistry(new DaemonToolCapabilitiesCodec());
    AgentDefinitionDO definition = definition(3L, "prompt", "missing-env", List.of(), List.of());
    HarnessAgentDefinitionSupport support =
        new HarnessAgentDefinitionSupport(mapper, new ObjectMapper(), environments);
    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () -> support.runtimeConfig(definition, "11", "default", false))
            .getMessage()
            .contains("offline or missing"));
  }

  private static AgentDefinitionDO definition(
      long id, String prompt, String environmentName, List<String> tools, List<String> skills) {
    AgentDefinitionDO definition = new AgentDefinitionDO();
    definition.setId(id);
    definition.setModelId(11L);
    definition.setVariant("default");
    definition.setSystemPrompt(prompt);
    definition.setConfigJson(
        String.format(
            "{\"environmentName\":%s,\"tools\":%s,\"skills\":%s,\"allowedSubagents\":[],\"executionPolicy\":{}}",
            environmentName == null ? "null" : "\"" + environmentName + "\"",
            toJsonArray(tools),
            toJsonArray(skills)));
    return definition;
  }

  private static String toJsonArray(List<String> values) {
    return values.stream()
        .map(value -> "\"" + value + "\"")
        .reduce((a, b) -> a + "," + b)
        .map(body -> "[" + body + "]")
        .orElse("[]");
  }

  private static void ready(
      LiveEnvironmentRegistry environments, String name, List<DaemonSkillDescriptor> skills) {
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
        name, connection, new DaemonToolCapabilities(List.of(), skills), NOW);
    environments.markReady(name, connection, NOW);
  }
}
