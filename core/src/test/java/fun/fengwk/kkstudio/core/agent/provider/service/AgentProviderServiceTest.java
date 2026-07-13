package fun.fengwk.kkstudio.core.agent.provider.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.convention4j.api.page.PageQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import fun.fengwk.kkstudio.core.CoreTestApplication;

import java.util.Arrays;
import fun.fengwk.kkstudio.core.workspace.service.WorkspaceService;
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderUpdateDTO;
import fun.fengwk.kkstudio.share.model.WorkspaceCreateDTO;
import fun.fengwk.kkstudio.share.model.WorkspaceDTO;

/** Service tests prove workspace-local uniqueness and credential redaction. */
@SpringBootTest(classes = CoreTestApplication.class)
public class AgentProviderServiceTest {

  @Autowired private WorkspaceService workspaceService;
  @Autowired private AgentProviderService agentProviderService;

  @Test
  public void shouldScopeNamesAndRedactCredential() {
    String suffix = Long.toString(System.nanoTime());
    WorkspaceDTO first = createWorkspace("provider-first-" + suffix);
    WorkspaceDTO second = createWorkspace("provider-second-" + suffix);
    long firstId = id(first.getId());
    long secondId = id(second.getId());
    AgentProviderDTO provider = agentProviderService.createProvider(firstId, provider("shared", "secret"));

    // The query DTO exposes only configured state, never the credential itself.
    assertTrue(provider.isConfigured());
    assertFalse(hasProperty(provider, "credential"));
    assertThrows(
        IllegalArgumentException.class,
        () -> agentProviderService.createProvider(firstId, provider("shared", "another")));
    agentProviderService.createProvider(secondId, provider("shared", null));

    AgentProviderUpdateDTO update = new AgentProviderUpdateDTO();
    update.setProviderType("openai");
    update.setCredential("rotated-secret");
    AgentProviderDTO updated = agentProviderService.updateProvider(firstId, id(provider.getId()), update);
    assertTrue(updated.isConfigured());
    assertThrows(
        IllegalArgumentException.class,
        () -> agentProviderService.updateProvider(secondId, id(provider.getId()), update));

    agentProviderService.deleteProvider(firstId, id(provider.getId()));
    agentProviderService.deleteProvider(secondId, id(agentProviderService.pageProviders(secondId, new PageQuery(1, 10)).getResults().get(0).getId()));
    workspaceService.deleteWorkspace(firstId);
    workspaceService.deleteWorkspace(secondId);
  }

  private WorkspaceDTO createWorkspace(String name) {
    WorkspaceCreateDTO dto = new WorkspaceCreateDTO();
    dto.setName(name);
    return workspaceService.createWorkspace(dto);
  }

  private AgentProviderCreateDTO provider(String name, String credential) {
    AgentProviderCreateDTO dto = new AgentProviderCreateDTO();
    dto.setName(name);
    dto.setProviderType("openai");
    dto.setBaseUrl("https://example.invalid/v1");
    dto.setCredential(credential);
    return dto;
  }

  private boolean hasProperty(AgentProviderDTO provider, String property) {
    return Arrays.stream(provider.getClass().getDeclaredFields())
        .anyMatch(field -> field.getName().equals(property));
  }

  private long id(String value) {
    return Long.parseLong(value);
  }
}
