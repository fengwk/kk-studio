package fun.fengwk.kkstudio.core.ai.catalog.definition.service;

import static fun.fengwk.kkstudio.core.ai.catalog.model.AgentModelTestData.executable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.convention4j.api.page.PageQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.core.ai.catalog.model.service.AgentModelService;
import fun.fengwk.kkstudio.core.ai.catalog.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.core.ai.error.AiDuplicateException;
import fun.fengwk.kkstudio.core.ai.error.AiInUseException;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.ai.error.AiVersionConflictException;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionUpdateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderDTO;

import java.util.List;

/** Agent definitions are global and reference immutable model names. */
public class AgentDefinitionServiceTest extends PostgresSpringTestSupport {

  @Autowired private AgentProviderService agentProviderService;
  @Autowired private AgentModelService agentModelService;
  @Autowired private AgentDefinitionService agentDefinitionService;

  @Test
  public void shouldPersistGlobalStructuredDefinition() {
    String suffix = Long.toString(System.nanoTime());
    AgentProviderDTO provider = provider("agent-provider-" + suffix);
    AgentModelDTO model = model(provider.getName(), "agent-model-" + suffix);
    String name = "agent-definition-" + suffix;
    String modelRef = provider.getName() + "/" + model.getName();

    AgentDefinitionDTO definition = agentDefinitionService.createAgent(agent(modelRef, name));
    assertEquals(modelRef, definition.getModel());
    assertEquals(List.of(), definition.getConfig().getTools());
    assertEquals(List.of(), definition.getConfig().getSkills());
    assertEquals("0", definition.getVersion());
    assertThrows(
        AiDuplicateException.class,
        () -> agentDefinitionService.createAgent(agent(modelRef, name)));
    assertThrows(
        AiInUseException.class,
        () -> agentModelService.deleteModel(provider.getName(), model.getName(), "0"));

    AgentDefinitionUpdateDTO update = new AgentDefinitionUpdateDTO();
    update.setDescription("updated");
    update.setSystemPrompt(definition.getSystemPrompt());
    update.setVariant(definition.getVariant());
    update.setConfig(definition.getConfig());
    update.setExpectedVersion(definition.getVersion());
    AgentDefinitionDTO updated = agentDefinitionService.updateAgent(name, update);
    assertEquals(name, updated.getName());
    assertEquals(modelRef, updated.getModel());
    assertEquals("updated", updated.getDescription());
    assertEquals("1", updated.getVersion());
    assertTrue(
        agentDefinitionService.pageAgents(new PageQuery(1, 100)).getResults().stream()
            .anyMatch(candidate -> candidate.getName().equals(name)));

    AgentDefinitionUpdateDTO stale = new AgentDefinitionUpdateDTO();
    stale.setDescription("stale");
    stale.setConfig(definition.getConfig());
    stale.setExpectedVersion(definition.getVersion());
    assertThrows(
        AiVersionConflictException.class, () -> agentDefinitionService.updateAgent(name, stale));

    agentDefinitionService.deleteAgent(name, updated.getVersion());
    assertThrows(
        AiResourceNotFoundException.class, () -> agentDefinitionService.deleteAgent(name, "0"));
    agentModelService.deleteModel(provider.getName(), model.getName(), model.getVersion());
    agentProviderService.deleteProvider(provider.getName(), provider.getVersion());
  }

  @Test
  public void rejectsVariantOutsideTheSelectedModelConfiguration() {
    String suffix = Long.toString(System.nanoTime());
    AgentProviderDTO provider = provider("agent-variant-provider-" + suffix);
    AgentModelDTO model = model(provider.getName(), "agent-variant-model-" + suffix);
    String modelRef = provider.getName() + "/" + model.getName();
    AgentDefinitionCreateDTO invalid = agent(modelRef, "agent-invalid-variant-" + suffix);
    invalid.setVariant("missing");

    assertThrows(AiValidationException.class, () -> agentDefinitionService.createAgent(invalid));

    AgentDefinitionCreateDTO valid = agent(modelRef, "agent-valid-variant-" + suffix);
    valid.setVariant(null);
    AgentDefinitionDTO created = agentDefinitionService.createAgent(valid);
    try {
      assertNull(created.getVariant());
      AgentDefinitionUpdateDTO invalidUpdate = new AgentDefinitionUpdateDTO();
      invalidUpdate.setDescription(created.getDescription());
      invalidUpdate.setVariant("missing");
      invalidUpdate.setConfig(created.getConfig());
      invalidUpdate.setExpectedVersion(created.getVersion());
      assertThrows(
          AiValidationException.class,
          () -> agentDefinitionService.updateAgent(created.getName(), invalidUpdate));
    } finally {
      agentDefinitionService.deleteAgent(created.getName(), created.getVersion());
      agentModelService.deleteModel(provider.getName(), model.getName(), model.getVersion());
      agentProviderService.deleteProvider(provider.getName(), provider.getVersion());
    }
  }

  private AgentProviderDTO provider(String name) {
    AgentProviderCreateDTO dto = new AgentProviderCreateDTO();
    dto.setName(name);
    dto.setProviderType("openai");
    return agentProviderService.createProvider(dto);
  }

  private AgentModelDTO model(String providerName, String name) {
    AgentModelCreateDTO dto = new AgentModelCreateDTO();
    dto.setProviderName(providerName);
    dto.setName(name);
    executable(dto);
    return agentModelService.createModel(dto);
  }

  private AgentDefinitionCreateDTO agent(String model, String name) {
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of());
    AgentDefinitionCreateDTO dto = new AgentDefinitionCreateDTO();
    dto.setName(name);
    dto.setModel(model);
    dto.setVariant("default");
    dto.setConfig(config);
    return dto;
  }
}
