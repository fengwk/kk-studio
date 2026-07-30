package fun.fengwk.kkstudio.core.ai.catalog.provider.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.convention4j.api.page.PageQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.core.ai.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.persistence.id.PostgresqlSequenceIdGenerator;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderType;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Global provider repository contract, exercised against the authoritative PostgreSQL schema via
 * {@link PostgresSpringTestSupport}. Verifies that the JSONB {@code config} column round-trips
 * losslessly, that {@code created_at} / {@code updated_at} timestamptz columns populate, and that
 * the atomic CAS update/delete on (id, expectedVersion) behaves correctly.
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
    assertTrue(agentProviderRepository.updateById(provider, 0L));
    AgentProvider updated = agentProviderRepository.getById(provider.getId());
    assertEquals("updated", updated.getDescription());
    assertNotNull(updated.getUpdateTime());
    assertEquals(Long.valueOf(1L), updated.getVersion());

    // Stale CAS must miss; correct CAS succeeds.
    assertFalse(agentProviderRepository.updateById(provider, 0L));
    assertTrue(agentProviderRepository.updateById(provider, 1L));
    AgentProvider reread = agentProviderRepository.getById(provider.getId());
    assertEquals(Long.valueOf(2L), reread.getVersion());

    // Stale delete CAS misses; current delete succeeds.
    assertFalse(agentProviderRepository.deleteById(provider.getId(), 0L));
    assertTrue(agentProviderRepository.deleteById(provider.getId(), 2L));
    assertNull(agentProviderRepository.getById(provider.getId()));
    assertFalse(agentProviderRepository.deleteById(provider.getId(), 3L));
  }

  @Test
  public void shouldAllowExactlyOneConcurrentCompareAndSetUpdate() throws Exception {
    AgentProvider created = provider("concurrent-provider-" + System.nanoTime());
    assertTrue(agentProviderRepository.create(created));

    AgentProvider first = agentProviderRepository.getById(created.getId());
    AgentProvider second = agentProviderRepository.getById(created.getId());
    first.setDescription("first");
    second.setDescription("second");

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> firstResult =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                return agentProviderRepository.updateById(first, 0L);
              });
      Future<Boolean> secondResult =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                return agentProviderRepository.updateById(second, 0L);
              });

      assertTrue(ready.await(5, TimeUnit.SECONDS), "both CAS contenders must be ready");
      start.countDown();

      int successfulUpdates = (firstResult.get() ? 1 : 0) + (secondResult.get() ? 1 : 0);
      assertEquals(1, successfulUpdates, "the database must accept exactly one matching CAS");
      AgentProvider reread = agentProviderRepository.getById(created.getId());
      assertEquals(Long.valueOf(1L), reread.getVersion());
      assertTrue(
          "first".equals(reread.getDescription()) || "second".equals(reread.getDescription()),
          "the stored value must belong to the sole successful contender");
    } finally {
      executor.shutdownNow();
    }
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
