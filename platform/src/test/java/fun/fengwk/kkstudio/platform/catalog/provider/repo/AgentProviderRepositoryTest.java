package fun.fengwk.kkstudio.platform.catalog.provider.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.convention4j.api.page.PageQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Provider 仓库契约仅以不可变的全局名称为键。 */
public class AgentProviderRepositoryTest extends PostgresSpringTestSupport {

  @Autowired private AgentProviderRepository agentProviderRepository;
  @Autowired private JdbcTemplate jdbc;

  @Test
  public void shouldPersistQueryUpdateAndDeleteGlobalProvider() {
    String name = "repository-provider-" + System.nanoTime();
    AgentProvider provider = provider(name);
    assertTrue(agentProviderRepository.create(provider));

    AgentProvider stored = agentProviderRepository.getByName(name);
    assertNotNull(stored);
    assertEquals(name, stored.getName());
    assertEquals(ProviderType.OPENAI, stored.getProviderType());
    // Repository 必须显式写入稳定 wire 值，不能依赖 MyBatis enum name 转换。
    assertEquals(
        "openai",
        jdbc.queryForObject(
            "select provider_type from agent_provider where name = ?", String.class, name));
    assertEquals("{}", stored.getConfigJson());
    assertNotNull(stored.getCreateTime());
    assertNotNull(stored.getUpdateTime());
    assertTrue(
        agentProviderRepository.page(new PageQuery(1, 100)).getResults().stream()
            .anyMatch(candidate -> candidate.getName().equals(name)));

    stored.setDescription("updated");
    assertTrue(agentProviderRepository.updateByName(stored, 0L));
    AgentProvider updated = agentProviderRepository.getByName(name);
    assertEquals("updated", updated.getDescription());
    assertEquals(Long.valueOf(1L), updated.getVersion());

    assertFalse(agentProviderRepository.updateByName(stored, 0L));
    assertTrue(agentProviderRepository.updateByName(stored, 1L));
    AgentProvider reread = agentProviderRepository.getByName(name);
    assertEquals(Long.valueOf(2L), reread.getVersion());

    assertFalse(agentProviderRepository.deleteByName(name, 0L));
    assertTrue(agentProviderRepository.deleteByName(name, 2L));
    assertNull(agentProviderRepository.getByName(name));
    assertFalse(agentProviderRepository.deleteByName(name, 3L));
  }

  @Test
  public void shouldAllowExactlyOneConcurrentCompareAndSetUpdate() throws Exception {
    String name = "concurrent-provider-" + System.nanoTime();
    assertTrue(agentProviderRepository.create(provider(name)));

    AgentProvider first = agentProviderRepository.getByName(name);
    AgentProvider second = agentProviderRepository.getByName(name);
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
                return agentProviderRepository.updateByName(first, 0L);
              });
      Future<Boolean> secondResult =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                return agentProviderRepository.updateByName(second, 0L);
              });
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();

      int successfulUpdates = (firstResult.get() ? 1 : 0) + (secondResult.get() ? 1 : 0);
      assertEquals(1, successfulUpdates);
      AgentProvider reread = agentProviderRepository.getByName(name);
      assertEquals(Long.valueOf(1L), reread.getVersion());
      assertTrue(
          "first".equals(reread.getDescription()) || "second".equals(reread.getDescription()));
    } finally {
      executor.shutdownNow();
    }
  }

  private AgentProvider provider(String name) {
    AgentProvider provider = new AgentProvider();
    provider.setName(name);
    provider.setProviderType(ProviderType.OPENAI);
    provider.setConfigJson("{}");
    provider.setConnectionGenerationId(UUID.randomUUID());
    return provider;
  }
}
