package fun.fengwk.kkstudio.core.agent.provider.repo;

import static fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator.nextProviderId;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import fun.fengwk.convention4j.api.page.PageQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import fun.fengwk.kkstudio.agent.provider.ProviderType;
import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.workspace.service.WorkspaceService;
import fun.fengwk.kkstudio.share.model.WorkspaceCreateDTO;
import fun.fengwk.kkstudio.share.model.WorkspaceDTO;

/** Repository queries must never leak a provider into another workspace. */
@SpringBootTest(classes = CoreTestApplication.class)
public class AgentProviderRepositoryTest {

  @Autowired private WorkspaceService workspaceService;
  @Autowired private AgentProviderRepository agentProviderRepository;

  @Test
  public void shouldFilterProviderReadsByWorkspace() {
    String suffix = Long.toString(System.nanoTime());
    WorkspaceDTO first = workspace("repository-first-" + suffix);
    WorkspaceDTO second = workspace("repository-second-" + suffix);
    long firstId = Long.parseLong(first.getId());
    long secondId = Long.parseLong(second.getId());
    AgentProvider provider = provider(firstId, "provider-" + suffix);
    agentProviderRepository.create(provider);

    assertEquals(1, agentProviderRepository.page(firstId, new PageQuery(1, 10)).getResults().size());
    assertEquals(0, agentProviderRepository.page(secondId, new PageQuery(1, 10)).getResults().size());
    assertNull(agentProviderRepository.getByWorkspaceIdAndId(secondId, provider.getId()));

    agentProviderRepository.deleteByWorkspaceIdAndId(firstId, provider.getId());
    workspaceService.deleteWorkspace(firstId);
    workspaceService.deleteWorkspace(secondId);
  }

  private WorkspaceDTO workspace(String name) {
    WorkspaceCreateDTO dto = new WorkspaceCreateDTO();
    dto.setName(name);
    return workspaceService.createWorkspace(dto);
  }

  private AgentProvider provider(long workspaceId, String name) {
    AgentProvider provider = new AgentProvider();
    provider.setId(nextProviderId());
    provider.setWorkspaceId(workspaceId);
    provider.setName(name);
    provider.setProviderType(ProviderType.openai);
    provider.setConfigJson("{}");
    return provider;
  }
}
