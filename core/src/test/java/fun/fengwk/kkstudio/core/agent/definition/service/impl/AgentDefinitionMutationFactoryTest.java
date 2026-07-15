package fun.fengwk.kkstudio.core.agent.definition.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.share.model.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionUpdateDTO;
import fun.fengwk.kkstudio.share.model.AgentExecutionPolicyDTO;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Structured definition config is normalized before it is stored. */
public class AgentDefinitionMutationFactoryTest {

  @Test
  public void shouldNormalizeListsAndPersistTypedPolicy() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    AgentDefinitionMutationFactory factory = factory(objectMapper);
    AgentExecutionPolicyDTO policy = new AgentExecutionPolicyDTO();
    policy.setMaxTurns(8);
    policy.setMaxDepth(3);
    policy.setIdleTimeoutMillis(1_000L);
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(Arrays.asList(" browser ", "browser", "", null));
    config.setSkills(Arrays.asList(" java ", "java"));
    config.setAllowedSubagents(Arrays.asList(" reviewer ", "reviewer"));
    config.setExecutionPolicy(policy);
    AgentDefinitionCreateDTO create = new AgentDefinitionCreateDTO();
    create.setName("agent");
    create.setConfig(config);

    AgentDefinition definition = factory.newAgent(2L, create);
    AgentDefinitionConfigDTO stored =
        objectMapper.readValue(definition.getConfigJson(), AgentDefinitionConfigDTO.class);

    assertEquals(List.of("browser"), stored.getTools());
    assertEquals(List.of("java"), stored.getSkills());
    assertEquals(List.of("reviewer"), stored.getAllowedSubagents());
    assertEquals(8, stored.getExecutionPolicy().getMaxTurns());
    assertEquals(3, stored.getExecutionPolicy().getMaxDepth());
    assertEquals(1_000L, stored.getExecutionPolicy().getIdleTimeoutMillis());
  }

  @Test
  public void shouldDefaultAndPatchDefinitionConfiguration() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    AgentDefinitionMutationFactory factory = factory(objectMapper);
    AgentDefinitionCreateDTO create = new AgentDefinitionCreateDTO();
    create.setName("agent");
    AgentDefinition definition = factory.newAgent(2L, create);
    assertEquals("default", definition.getVariant());

    String originalConfig = definition.getConfigJson();
    AgentDefinitionUpdateDTO update = new AgentDefinitionUpdateDTO();
    update.setDescription("updated");
    factory.update(definition, update);
    assertEquals("agent", definition.getName());
    assertEquals(originalConfig, definition.getConfigJson());
    assertEquals(
        List.of(),
        objectMapper
            .readValue(definition.getConfigJson(), AgentDefinitionConfigDTO.class)
            .getAllowedSubagents());
  }

  @Test
  public void shouldRejectInvalidDefinitionConfiguration() {
    AgentDefinitionMutationFactory factory = factory(new ObjectMapper());
    assertThrows(
        IllegalArgumentException.class, () -> factory.newAgent(0L, new AgentDefinitionCreateDTO()));
    assertThrows(IllegalArgumentException.class, () -> factory.newAgent(2L, null));

    AgentDefinitionCreateDTO blank = new AgentDefinitionCreateDTO();
    blank.setName(" ");
    assertThrows(IllegalArgumentException.class, () -> factory.newAgent(2L, blank));

    AgentExecutionPolicyDTO policy = new AgentExecutionPolicyDTO();
    policy.setMaxTurns(0);
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setExecutionPolicy(policy);
    AgentDefinitionCreateDTO create = new AgentDefinitionCreateDTO();
    create.setName("agent");
    create.setConfig(config);
    assertThrows(IllegalArgumentException.class, () -> factory.newAgent(2L, create));
  }

  private AgentDefinitionMutationFactory factory(ObjectMapper objectMapper) {
    return new AgentDefinitionMutationFactory(new AgentEditableSupport(objectMapper), objectMapper);
  }
}
