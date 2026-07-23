package fun.fengwk.kkstudio.core.agent.provider.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.convention4j.api.page.PageQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.persistence.id.PostgresqlSequenceIdGenerator;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.model.AgentProviderType;

/**
 * Global provider repository contract, exercised against the authoritative PostgreSQL schema via
 * {@link PostgresSpringTestSupport}. Verifies that the JSONB {@code config} column round-trips
 * losslessly and that {@code created_at} / {@code updated_at} timestamptz columns are populated.
 */
public class AgentProviderRepositoryTest extends PostgresSpringTestSupport {

  @Autowired private AgentProviderRepository agentProviderRepository;
  @Autowired private PostgresqlSequenceIdGenerator idGenerator;

  @Test
  public void shouldPersistQueryUpdateAndDeleteGlobalProvider() {
    String name = "repository-provider-" + System.nanoTime();
    AgentProvider provider = provider(name);
    assertTrue(agentProviderRepository.create(provider));

    AgentProvider stored = agentProviderRepository.getById(provider.getId());
    assertNotNull(stored);
    assertEquals(name, stored.getName());
    assertEquals(AgentProviderType.openai, stored.getProviderType());
    assertEquals("{}", stored.getConfigJson());
    assertNotNull(stored.getCreateTime(), "created_at must populate the createTime alias");
    assertNotNull(stored.getUpdateTime(), "updated_at must populate the updateTime alias");

    AgentProvider foundById = agentProviderRepository.getById(provider.getId());
    assertEquals(name, foundById.getName());
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
    provider.setId(idGenerator.next());
    provider.setName(name);
    provider.setProviderType(AgentProviderType.openai);
    provider.setConfigJson("{}");
    return provider;
  }
}
