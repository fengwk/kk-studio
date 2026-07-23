package fun.fengwk.kkstudio.core.agent.definition.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import fun.fengwk.kkstudio.core.agent.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.core.persistence.id.PostgresqlSequenceIdGenerator;
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
    config.setEnvironmentName(" local-dev ");
    config.setTools(Arrays.asList(" browser ", "", null));
    config.setSkills(Arrays.asList(" java ", "dev"));
    config.setAllowedSubagents(List.of(" reviewer "));
    config.setExecutionPolicy(policy);
    AgentDefinitionCreateDTO create = new AgentDefinitionCreateDTO();
    create.setName("agent");
    create.setVariant("default");
    create.setConfig(config);

    AgentDefinition definition = factory.newAgent(2L, create);
    AgentDefinitionConfigDTO stored =
        objectMapper.readValue(definition.getConfigJson(), AgentDefinitionConfigDTO.class);

    assertEquals("local-dev", stored.getEnvironmentName());
    assertEquals(List.of("browser"), stored.getTools());
    assertEquals(List.of("java", "dev"), stored.getSkills());
    assertEquals(List.of("reviewer"), stored.getAllowedSubagents());
    assertEquals(8, stored.getExecutionPolicy().getMaxTurns());
    assertEquals(3, stored.getExecutionPolicy().getMaxDepth());
    assertEquals(4, stored.getExecutionPolicy().getMaxDirectSubagents());
  }

  @Test
  public void shouldRejectDuplicateCapabilityAndSubagentNames() {
    AgentDefinitionMutationFactory factory = factory(new ObjectMapper());
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setSkills(Arrays.asList("java", " java "));
    AgentDefinitionCreateDTO create = new AgentDefinitionCreateDTO();
    create.setName("agent");
    create.setVariant("default");
    create.setConfig(config);
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> factory.newAgent(2L, create));
    assertEquals("agent skills must not contain duplicates: java", error.getMessage());

    config.setSkills(List.of());
    config.setTools(Arrays.asList("read", " read "));
    error = assertThrows(IllegalArgumentException.class, () -> factory.newAgent(2L, create));
    assertEquals("agent tools must not contain duplicates: read", error.getMessage());

    config.setTools(List.of());
    config.setAllowedSubagents(Arrays.asList("reviewer", " reviewer "));
    error = assertThrows(IllegalArgumentException.class, () -> factory.newAgent(2L, create));
    assertEquals(
        "agent allowedSubagents must not contain duplicates: reviewer", error.getMessage());
  }

  @Test
  public void shouldRequireAndReplaceCompleteDefinitionConfiguration() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    AgentDefinitionMutationFactory factory = factory(objectMapper);
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    AgentDefinitionCreateDTO create = new AgentDefinitionCreateDTO();
    create.setName("agent");
    create.setVariant("quality");
    create.setConfig(config);
    AgentDefinition definition = factory.newAgent(2L, create);
    assertEquals("quality", definition.getVariant());

    AgentDefinitionUpdateDTO update = new AgentDefinitionUpdateDTO();
    update.setName("agent");
    update.setDescription("updated");
    update.setVariant("quality");
    update.setConfig(config);
    factory.update(definition, update);
    assertEquals("agent", definition.getName());
    assertEquals("quality", definition.getVariant());
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

    AgentDefinitionCreateDTO incomplete = new AgentDefinitionCreateDTO();
    incomplete.setName("agent");
    assertThrows(IllegalArgumentException.class, () -> factory.newAgent(2L, incomplete));
    incomplete.setConfig(new AgentDefinitionConfigDTO());
    // Variant override is optional; null means use model.defaultVariant at apply/runtime.
    AgentDefinition allowedBlankVariant = factory.newAgent(2L, incomplete);
    assertNull(allowedBlankVariant.getVariant());

    AgentExecutionPolicyDTO policy = new AgentExecutionPolicyDTO();
    policy.setMaxTurns(0);
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setExecutionPolicy(policy);
    AgentDefinitionCreateDTO create = new AgentDefinitionCreateDTO();
    create.setName("agent");
    create.setVariant("default");
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
    create.setVariant("default");
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
    create.setVariant("default");
    create.setConfig(config);
    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> factory.newAgent(2L, create));
    assertEquals("executionPolicy.maxTurns must be positive", exception.getMessage());
  }

  private AgentDefinitionMutationFactory factory(ObjectMapper objectMapper) {
    PostgresqlSequenceIdGenerator idGenerator = Mockito.mock(PostgresqlSequenceIdGenerator.class);
    when(idGenerator.next()).thenReturn(303L);
    return new AgentDefinitionMutationFactory(
        new AgentEditableSupport(objectMapper),
        new AgentDefinitionConfigCodec(objectMapper),
        idGenerator);
  }
}
