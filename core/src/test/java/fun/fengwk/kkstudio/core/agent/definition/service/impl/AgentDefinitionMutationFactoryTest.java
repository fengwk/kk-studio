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
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.persistence.id.PostgresqlSequenceIdGenerator;
import fun.fengwk.kkstudio.share.model.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionUpdateDTO;

import java.util.List;

/** Structured definition config must be canonical before it is persisted. */
public class AgentDefinitionMutationFactoryTest {

  @Test
  public void shouldPersistCanonicalCapabilityListsAndEnvironmentName() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    AgentDefinitionMutationFactory factory = factory(objectMapper);
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setEnvironmentName("local-dev");
    config.setTools(List.of("browser"));
    config.setSkills(List.of("java", "dev"));
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
  }

  @Test
  public void shouldRejectNonCanonicalCapabilityNames() {
    AgentDefinitionMutationFactory factory = factory(new ObjectMapper());
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of("java", "java"));
    AgentDefinitionCreateDTO create = new AgentDefinitionCreateDTO();
    create.setName("agent");
    create.setVariant("default");
    create.setConfig(config);
    AiValidationException error =
        assertThrows(AiValidationException.class, () -> factory.newAgent(2L, create));
    assertEquals(
        "agent definition config skills must not contain duplicates: java", error.getMessage());

    config.setSkills(List.of());
    config.setTools(List.of(" read "));
    error = assertThrows(AiValidationException.class, () -> factory.newAgent(2L, create));
    assertEquals(
        "agent definition config tools must not contain surrounding whitespace",
        error.getMessage());
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
    assertThrows(AiValidationException.class, () -> factory.newAgent(2L, create));
    config.setTools(List.of());
    config.setSkills(List.of());
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
    AgentDefinitionConfigDTO stored =
        objectMapper.readValue(definition.getConfigJson(), AgentDefinitionConfigDTO.class);
    assertEquals(List.of(), stored.getTools());
    assertEquals(List.of(), stored.getSkills());
    assertNull(stored.getEnvironmentName());
  }

  @Test
  public void shouldRejectInvalidDefinitionConfiguration() {
    AgentDefinitionMutationFactory factory = factory(new ObjectMapper());
    assertThrows(
        AiValidationException.class, () -> factory.newAgent(0L, new AgentDefinitionCreateDTO()));
    assertThrows(AiValidationException.class, () -> factory.newAgent(2L, null));

    AgentDefinitionCreateDTO blank = new AgentDefinitionCreateDTO();
    blank.setName(" ");
    assertThrows(AiValidationException.class, () -> factory.newAgent(2L, blank));

    AgentDefinitionCreateDTO incomplete = new AgentDefinitionCreateDTO();
    incomplete.setName("agent");
    assertThrows(AiValidationException.class, () -> factory.newAgent(2L, incomplete));
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of());
    incomplete.setConfig(config);
    // Variant override is optional; null means use model.defaultVariant at apply/runtime.
    AgentDefinition allowedBlankVariant = factory.newAgent(2L, incomplete);
    assertNull(allowedBlankVariant.getVariant());
  }

  @Test
  public void shouldRejectBlankOrWhitespaceEnvironmentName() {
    AgentDefinitionMutationFactory factory = factory(new ObjectMapper());
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of());
    AgentDefinitionCreateDTO create = new AgentDefinitionCreateDTO();
    create.setName("agent");
    create.setConfig(config);

    config.setEnvironmentName(" ");
    assertThrows(AiValidationException.class, () -> factory.newAgent(2L, create));

    config.setEnvironmentName(" local ");
    assertThrows(AiValidationException.class, () -> factory.newAgent(2L, create));
  }

  @Test
  public void shouldPersistNullEnvironmentNameWithProjectJacksonConvention() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    AgentDefinitionMutationFactory factory = factory(objectMapper);
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of());
    AgentDefinitionCreateDTO create = new AgentDefinitionCreateDTO();
    create.setName("agent");
    create.setVariant("default");
    create.setConfig(config);

    AgentDefinition definition = factory.newAgent(2L, create);

    AgentDefinitionConfigDTO stored =
        objectMapper.readValue(definition.getConfigJson(), AgentDefinitionConfigDTO.class);
    assertNull(stored.getEnvironmentName());
  }

  @Test
  public void shouldEnforceAgentSchemaStringLimitsAfterNormalization() {
    AgentDefinitionMutationFactory factory = factory(new ObjectMapper());
    AgentDefinitionCreateDTO accepted = create("n".repeat(64), "d".repeat(512), "v".repeat(64));
    AgentDefinition persisted = factory.newAgent(2L, accepted);
    assertEquals("n".repeat(64), persisted.getName());
    assertEquals("d".repeat(512), persisted.getDescription());
    assertEquals("v".repeat(64), persisted.getVariant());

    assertThrows(
        AiValidationException.class,
        () -> factory.newAgent(2L, create("n".repeat(65), null, null)));
    assertThrows(
        AiValidationException.class,
        () -> factory.newAgent(2L, create("agent", "d".repeat(513), null)));
    assertThrows(
        AiValidationException.class,
        () -> factory.newAgent(2L, create("agent", null, "v".repeat(65))));
  }

  private static AgentDefinitionCreateDTO create(String name, String description, String variant) {
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of());
    AgentDefinitionCreateDTO create = new AgentDefinitionCreateDTO();
    create.setName(name);
    create.setDescription(description);
    create.setVariant(variant);
    create.setConfig(config);
    return create;
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
