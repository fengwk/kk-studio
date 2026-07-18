package fun.fengwk.kkstudio.core.agent.definition.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.share.model.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionUpdateDTO;
import fun.fengwk.kkstudio.share.model.AgentExecutionPolicyDTO;

import java.util.Arrays;
import java.util.List;

/** Structured definition config is normalized before it is stored. */
public class AgentDefinitionMutationFactoryTest {

  @Test
  public void shouldNormalizeListsAndPersistTypedPolicy() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    AgentDefinitionMutationFactory factory = factory(objectMapper);
    AgentExecutionPolicyDTO policy = new AgentExecutionPolicyDTO();
    policy.setMaxTurns(8);
    policy.setMaxDepth(3);
    policy.setMaxDirectSubagents(4);
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
    assertEquals(4, stored.getExecutionPolicy().getMaxDirectSubagents());
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

  /** 执行策略边界字段可原样持久化。 */
  @Test
  public void shouldPersistBoundedExecutionPolicy() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    AgentDefinitionMutationFactory factory = factory(objectMapper);
    AgentExecutionPolicyDTO policy = new AgentExecutionPolicyDTO();
    policy.setMaxTurns(8);
    policy.setMaxDepth(2);
    policy.setMaxDirectSubagents(3);
    policy.setMaxTotalSubagents(9);
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setExecutionPolicy(policy);
    AgentDefinitionCreateDTO create = new AgentDefinitionCreateDTO();
    create.setName("agent");
    create.setConfig(config);
    AgentDefinition definition = factory.newAgent(2L, create);

    AgentDefinitionConfigDTO stored =
        objectMapper.readValue(definition.getConfigJson(), AgentDefinitionConfigDTO.class);
    assertEquals(8, stored.getExecutionPolicy().getMaxTurns());
    assertEquals(2, stored.getExecutionPolicy().getMaxDepth());
    assertEquals(3, stored.getExecutionPolicy().getMaxDirectSubagents());
    assertEquals(9, stored.getExecutionPolicy().getMaxTotalSubagents());
  }

  @Test
  public void shouldRejectNonPositiveExecutionPolicyBounds() {
    AgentDefinitionMutationFactory factory = factory(new ObjectMapper());
    AgentExecutionPolicyDTO policy = new AgentExecutionPolicyDTO();
    policy.setMaxTurns(0);
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setExecutionPolicy(policy);
    AgentDefinitionCreateDTO create = new AgentDefinitionCreateDTO();
    create.setName("agent");
    create.setConfig(config);
    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> factory.newAgent(2L, create));
    assertEquals("executionPolicy.maxTurns must be positive", exception.getMessage());
  }

  private AgentDefinitionMutationFactory factory(ObjectMapper objectMapper) {
    return new AgentDefinitionMutationFactory(new AgentEditableSupport(objectMapper), objectMapper);
  }
}
