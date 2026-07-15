package fun.fengwk.kkstudio.core.agent.definition.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Agent definitions remain scoped temporarily while their model references are global. */
@SpringBootTest(classes = CoreTestApplication.class)
public class AgentDefinitionServiceTest {

  @Autowired private WorkspaceService workspaceService;
  @Autowired private AgentProviderService agentProviderService;
  @Autowired private AgentModelService agentModelService;
  @Autowired private AgentDefinitionService agentDefinitionService;

  @Test
  public void shouldPersistStructuredConfigWithGlobalModelReference() {
    String suffix = Long.toString(System.nanoTime());
    WorkspaceDTO workspace = workspace("agent-workspace-" + suffix);
    long workspaceId = id(workspace.getId());
    AgentProviderDTO provider = provider("agent-provider-" + suffix);
    AgentModelDTO model = model(provider.getId(), "agent-model-" + suffix);

    AgentDefinitionDTO definition =
        agentDefinitionService.createAgent(
            workspaceId, agent(model.getId(), "agent-definition-" + suffix));
    assertEquals(List.of("browser"), definition.getConfig().getTools());
    assertEquals(List.of("java"), definition.getConfig().getSkills());
    assertEquals(List.of("reviewer"), definition.getConfig().getAllowedSubagents());
    assertEquals(8, definition.getConfig().getExecutionPolicy().getMaxTurns());
    assertThrows(
        IllegalStateException.class, () -> agentModelService.deleteModel(id(model.getId())));

    agentDefinitionService.deleteAgent(workspaceId, id(definition.getId()));
    agentModelService.deleteModel(id(model.getId()));
    agentProviderService.deleteProvider(id(provider.getId()));
    workspaceService.deleteWorkspace(workspaceId);
  }

  private WorkspaceDTO workspace(String name) {
    WorkspaceCreateDTO dto = new WorkspaceCreateDTO();
    dto.setName(name);
    return workspaceService.createWorkspace(dto);
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
    return agentModelService.createModel(dto);
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
