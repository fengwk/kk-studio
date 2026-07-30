package fun.fengwk.kkstudio.core.agent.definition.service;

import static fun.fengwk.kkstudio.core.agent.model.AgentModelTestData.executable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.convention4j.api.page.PageQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.core.agent.model.service.AgentModelService;
import fun.fengwk.kkstudio.core.agent.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.core.ai.error.AiDuplicateException;
import fun.fengwk.kkstudio.core.ai.error.AiInUseException;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.ai.error.AiVersionConflictException;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.model.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionUpdateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderDTO;

import java.util.List;

/** Agent definitions and their model references are global. */
public class AgentDefinitionServiceTest extends PostgresSpringTestSupport {

  @Autowired private AgentProviderService agentProviderService;
  @Autowired private AgentModelService agentModelService;
  @Autowired private AgentDefinitionService agentDefinitionService;

  @Test
  public void shouldPersistGlobalStructuredDefinition() {
    String suffix = Long.toString(System.nanoTime());
    AgentProviderDTO provider = provider("agent-provider-" + suffix);
    AgentModelDTO model = model(provider.getId(), "agent-model-" + suffix);
    String name = "agent-definition-" + suffix;

    AgentDefinitionDTO definition = agentDefinitionService.createAgent(agent(model.getId(), name));
    assertEquals(List.of(), definition.getConfig().getTools());
    assertEquals(List.of(), definition.getConfig().getSkills());
    assertEquals("0", definition.getVersion());
    assertThrows(
        AiDuplicateException.class,
        () -> agentDefinitionService.createAgent(agent(model.getId(), name)));
    assertThrows(
        AiInUseException.class, () -> agentModelService.deleteModel(id(model.getId()), "0"));

    AgentDefinitionUpdateDTO update = new AgentDefinitionUpdateDTO();
    update.setName(definition.getName());
    update.setDescription("updated");
    update.setSystemPrompt(definition.getSystemPrompt());
    update.setModelId(definition.getModelId());
    update.setVariant(definition.getVariant());
    update.setConfig(definition.getConfig());
    update.setExpectedVersion(definition.getVersion());
    AgentDefinitionDTO updated = agentDefinitionService.updateAgent(id(definition.getId()), update);
    assertEquals("updated", updated.getDescription());
    assertEquals("1", updated.getVersion());
    assertTrue(
        agentDefinitionService.pageAgents(new PageQuery(1, 100)).getResults().stream()
            .anyMatch(candidate -> candidate.getId().equals(definition.getId())));

    AgentDefinitionUpdateDTO stale = new AgentDefinitionUpdateDTO();
    stale.setName(definition.getName());
    stale.setDescription("stale");
    stale.setModelId(definition.getModelId());
    stale.setConfig(definition.getConfig());
    stale.setExpectedVersion(definition.getVersion());
    assertThrows(
        AiVersionConflictException.class,
        () -> agentDefinitionService.updateAgent(id(definition.getId()), stale));

    agentDefinitionService.deleteAgent(id(definition.getId()), updated.getVersion());
    assertThrows(
        AiResourceNotFoundException.class,
        () -> agentDefinitionService.deleteAgent(id(definition.getId()), "0"));
    agentModelService.deleteModel(id(model.getId()), model.getVersion());
    agentProviderService.deleteProvider(id(provider.getId()), provider.getVersion());
  }

  @Test
  public void rejectsVariantOutsideTheSelectedModelConfiguration() {
    String suffix = Long.toString(System.nanoTime());
    AgentProviderDTO provider = provider("agent-variant-provider-" + suffix);
    AgentModelDTO model = model(provider.getId(), "agent-variant-model-" + suffix);
    AgentDefinitionCreateDTO invalid = agent(model.getId(), "agent-invalid-variant-" + suffix);
    invalid.setVariant("missing");

    try {
      assertThrows(AiValidationException.class, () -> agentDefinitionService.createAgent(invalid));

      AgentDefinitionCreateDTO defaultVariant =
          agent(model.getId(), "agent-valid-variant-" + suffix);
      defaultVariant.setVariant(null);
      AgentDefinitionDTO created = agentDefinitionService.createAgent(defaultVariant);
      try {
        assertNull(created.getVariant());
        AgentDefinitionUpdateDTO invalidUpdate = new AgentDefinitionUpdateDTO();
        invalidUpdate.setName(created.getName());
        invalidUpdate.setDescription(created.getDescription());
        invalidUpdate.setSystemPrompt(created.getSystemPrompt());
        invalidUpdate.setModelId(model.getId());
        invalidUpdate.setVariant("missing");
        invalidUpdate.setConfig(created.getConfig());
        invalidUpdate.setExpectedVersion(created.getVersion());
        assertThrows(
            AiValidationException.class,
            () -> agentDefinitionService.updateAgent(id(created.getId()), invalidUpdate));
      } finally {
        agentDefinitionService.deleteAgent(id(created.getId()), created.getVersion());
      }
    } finally {
      agentModelService.deleteModel(id(model.getId()), model.getVersion());
      agentProviderService.deleteProvider(id(provider.getId()), provider.getVersion());
    }
  }

  private AgentProviderDTO provider(String name) {
    AgentProviderCreateDTO dto = new AgentProviderCreateDTO();
    dto.setName(name);
    dto.setProviderType("openai");
    return agentProviderService.createProvider(dto);
  }

  private AgentModelDTO model(String providerId, String name) {
    AgentModelCreateDTO dto = new AgentModelCreateDTO();
    dto.setProviderId(providerId);
    dto.setName(name);
    executable(dto);
    return agentModelService.createModel(dto);
  }

  private AgentDefinitionCreateDTO agent(String modelId, String name) {
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    // tools/skills require live capability validation; keep empty for basic CRUD coverage
    config.setTools(List.of());
    config.setSkills(List.of());
    AgentDefinitionCreateDTO dto = new AgentDefinitionCreateDTO();
    dto.setName(name);
    dto.setModelId(modelId);
    dto.setVariant("default");
    dto.setConfig(config);
    return dto;
  }

  private long id(String value) {
    return Long.parseLong(value);
  }
}
