package fun.fengwk.kkstudio.core.agent.provider.repo;

import static fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator.nextProviderId;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.share.model.AgentProviderType;
import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Global provider repository contract. */
@SpringBootTest(classes = CoreTestApplication.class)
public class AgentProviderRepositoryTest {

  @Autowired private AgentProviderRepository agentProviderRepository;

  @Test
  public void shouldPersistQueryUpdateAndDeleteGlobalProvider() {
    String name = "repository-provider-" + System.nanoTime();
    AgentProvider provider = provider(name);
    assertTrue(agentProviderRepository.create(provider));

    assertEquals(name, agentProviderRepository.getById(provider.getId()).getName());
    assertEquals(provider.getId(), agentProviderRepository.getByName(name).getId());
    assertTrue(
        agentProviderRepository.page(new PageQuery(1, 100)).getResults().stream()
            .anyMatch(candidate -> candidate.getId().equals(provider.getId())));

    provider.setDescription("updated");
    assertTrue(agentProviderRepository.updateById(provider));
    AgentProvider updated = agentProviderRepository.getById(provider.getId());
    assertEquals("updated", updated.getDescription());
    assertNotNull(updated.getUpdateTime());

    assertTrue(agentProviderRepository.deleteById(provider.getId()));
    assertNull(agentProviderRepository.getById(provider.getId()));
  }

  private AgentProvider provider(String name) {
    AgentProvider provider = new AgentProvider();
    provider.setId(nextProviderId());
    provider.setName(name);
    provider.setProviderType(AgentProviderType.openai);
    provider.setConfigJson("{}");
    return provider;
  }
}
