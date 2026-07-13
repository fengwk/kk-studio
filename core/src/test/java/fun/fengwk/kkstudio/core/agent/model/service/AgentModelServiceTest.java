package fun.fengwk.kkstudio.core.agent.model.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.core.agent.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.core.workspace.service.WorkspaceService;
import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderDTO;
import fun.fengwk.kkstudio.share.model.WorkspaceCreateDTO;
import fun.fengwk.kkstudio.share.model.WorkspaceDTO;

/** Model references must remain inside their workspace. */
@SpringBootTest(classes = CoreTestApplication.class)
public class AgentModelServiceTest {

  @Autowired private WorkspaceService workspaceService;
  @Autowired private AgentProviderService agentProviderService;
  @Autowired private AgentModelService agentModelService;

  @Test
  public void shouldRejectCrossWorkspaceProviderAndKeepNameLocal() {
    String suffix = Long.toString(System.nanoTime());
    WorkspaceDTO first = workspace("model-first-" + suffix);
    WorkspaceDTO second = workspace("model-second-" + suffix);
    long firstId = id(first.getId());
    long secondId = id(second.getId());
    AgentProviderDTO firstProvider = provider(firstId, "provider");
    AgentProviderDTO secondProvider = provider(secondId, "provider");

    assertThrows(
        IllegalArgumentException.class,
        () -> agentModelService.createModel(secondId, model(firstProvider.getId(), "shared-model")));
    AgentModelDTO firstModel = agentModelService.createModel(firstId, model(firstProvider.getId(), "shared-model"));
    AgentModelDTO secondModel = agentModelService.createModel(secondId, model(secondProvider.getId(), "shared-model"));
    assertEquals(firstProvider.getId(), firstModel.getProviderId());

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

  private AgentModelCreateDTO model(String providerId, String name) {
    AgentModelCreateDTO dto = new AgentModelCreateDTO();
    dto.setProviderId(providerId);
    dto.setName(name);
    return dto;
  }

  private long id(String value) {
    return Long.parseLong(value);
  }
}
