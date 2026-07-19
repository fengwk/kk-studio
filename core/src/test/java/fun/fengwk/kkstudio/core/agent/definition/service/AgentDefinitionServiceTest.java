package fun.fengwk.kkstudio.core.agent.definition.service;

import static fun.fengwk.kkstudio.core.agent.model.AgentModelTestData.executable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.convention4j.api.page.PageQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.core.agent.model.service.AgentModelService;
import fun.fengwk.kkstudio.core.agent.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.share.model.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionUpdateDTO;
import fun.fengwk.kkstudio.share.model.AgentExecutionPolicyDTO;
import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderDTO;

import java.util.List;

/** Agent definitions and their model references are global. */
@SpringBootTest(classes = CoreTestApplication.class)
public class AgentDefinitionServiceTest {

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
    assertEquals(List.of("reviewer"), definition.getConfig().getAllowedSubagents());
    assertEquals(8, definition.getConfig().getExecutionPolicy().getMaxTurns());
    assertThrows(
        IllegalArgumentException.class,
        () -> agentDefinitionService.createAgent(agent(model.getId(), name)));
    assertThrows(
        IllegalStateException.class, () -> agentModelService.deleteModel(id(model.getId())));

    AgentDefinitionUpdateDTO update = new AgentDefinitionUpdateDTO();
    update.setDescription("updated");
    AgentDefinitionDTO updated = agentDefinitionService.updateAgent(id(definition.getId()), update);
    assertEquals("updated", updated.getDescription());
    assertTrue(
        agentDefinitionService.pageAgents(new PageQuery(1, 100)).getResults().stream()
            .anyMatch(candidate -> candidate.getId().equals(definition.getId())));

    agentDefinitionService.deleteAgent(id(definition.getId()));
    assertThrows(
        IllegalArgumentException.class,
        () -> agentDefinitionService.deleteAgent(id(definition.getId())));
    agentModelService.deleteModel(id(model.getId()));
    agentProviderService.deleteProvider(id(provider.getId()));
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
    config.setAllowedSubagents(List.of("reviewer"));
    AgentExecutionPolicyDTO policy = new AgentExecutionPolicyDTO();
    policy.setMaxTurns(8);
    config.setExecutionPolicy(policy);
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
