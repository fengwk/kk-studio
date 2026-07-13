package fun.fengwk.kkstudio.core.agent.definition.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.core.agent.model.service.AgentModelService;
import fun.fengwk.kkstudio.core.agent.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.core.workspace.service.WorkspaceService;
import fun.fengwk.kkstudio.share.model.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.model.AgentExecutionPolicyDTO;
import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderDTO;
import fun.fengwk.kkstudio.share.model.WorkspaceCreateDTO;
import fun.fengwk.kkstudio.share.model.WorkspaceDTO;

import java.util.List;

/** Agent definition configuration is structured and model references are workspace-scoped. */
@SpringBootTest(classes = CoreTestApplication.class)
public class AgentDefinitionServiceTest {

  @Autowired private WorkspaceService workspaceService;
  @Autowired private AgentProviderService agentProviderService;
  @Autowired private AgentModelService agentModelService;
  @Autowired private AgentDefinitionService agentDefinitionService;

  @Test
  public void shouldPersistStructuredConfigAndRejectCrossWorkspaceModel() {
    String suffix = Long.toString(System.nanoTime());
    WorkspaceDTO first = workspace("agent-first-" + suffix);
    WorkspaceDTO second = workspace("agent-second-" + suffix);
    long firstId = id(first.getId());
    long secondId = id(second.getId());
    AgentProviderDTO firstProvider = provider(firstId, "provider");
    AgentProviderDTO secondProvider = provider(secondId, "provider");
    AgentModelDTO firstModel = model(firstId, firstProvider.getId(), "model");
    AgentModelDTO secondModel = model(secondId, secondProvider.getId(), "model");

    assertThrows(
        IllegalArgumentException.class,
        () -> agentDefinitionService.createAgent(secondId, agent(firstModel.getId(), "cross")));
    AgentDefinitionDTO definition = agentDefinitionService.createAgent(firstId, agent(firstModel.getId(), "agent"));
    assertEquals(List.of("browser"), definition.getConfig().getTools());
    assertEquals(List.of("java"), definition.getConfig().getSkills());
    assertEquals(List.of("reviewer"), definition.getConfig().getAllowedSubagents());
    assertEquals(8, definition.getConfig().getExecutionPolicy().getMaxTurns());

    agentDefinitionService.deleteAgent(firstId, id(definition.getId()));
    agentModelService.deleteModel(firstId, id(firstModel.getId()));
    agentModelService.deleteModel(secondId, id(secondModel.getId()));
    agentProviderService.deleteProvider(firstId, id(firstProvider.getId()));
    agentProviderService.deleteProvider(secondId, id(secondProvider.getId()));
    workspaceService.deleteWorkspace(firstId);
    workspaceService.deleteWorkspace(secondId);
  }

  private WorkspaceDTO workspace(String name) {
    WorkspaceCreateDTO dto = new WorkspaceCreateDTO();
    dto.setName(name);
    return workspaceService.createWorkspace(dto);
  }

  private AgentProviderDTO provider(long workspaceId, String name) {
    AgentProviderCreateDTO dto = new AgentProviderCreateDTO();
    dto.setName(name);
    dto.setProviderType("openai");
    return agentProviderService.createProvider(workspaceId, dto);
  }

  private AgentModelDTO model(long workspaceId, String providerId, String name) {
    AgentModelCreateDTO dto = new AgentModelCreateDTO();
    dto.setProviderId(providerId);
    dto.setName(name);
    return agentModelService.createModel(workspaceId, dto);
  }

  private AgentDefinitionCreateDTO agent(String modelId, String name) {
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(List.of("browser"));
    config.setSkills(List.of("java"));
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
