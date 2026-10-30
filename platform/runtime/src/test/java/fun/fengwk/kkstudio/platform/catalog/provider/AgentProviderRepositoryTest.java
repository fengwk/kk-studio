package fun.fengwk.kkstudio.platform.catalog.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.common.page.PageQuery;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
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
    assertEquals(name, stored.name());
    assertEquals(ProviderType.OPENAI, stored.providerType());
    // Repository 必须显式写入稳定 wire 值，不做隐式 enum 名称转换。
    assertEquals(
        "openai",
        jdbc.queryForObject(
            "select provider_type from agent_provider where name = ?", String.class, name));
    assertEquals("{}", stored.configJson());
    assertNotNull(stored.createTime());
    assertNotNull(stored.updateTime());
    assertTrue(
        agentProviderRepository.page(new PageQuery(1, 100)).getResults().stream()
            .anyMatch(candidate -> candidate.name().equals(name)));

    AgentProvider edited = withDescription(stored, "updated");
    assertTrue(agentProviderRepository.updateByName(edited, 0L));
    AgentProvider updated = agentProviderRepository.getByName(name);
    assertEquals("updated", updated.description());
    assertEquals(Long.valueOf(1L), updated.version());

    assertFalse(agentProviderRepository.updateByName(edited, 0L));
    assertTrue(agentProviderRepository.updateByName(edited, 1L));
    AgentProvider reread = agentProviderRepository.getByName(name);
    assertEquals(Long.valueOf(2L), reread.version());

    assertFalse(agentProviderRepository.deleteByName(name, 0L));
    assertTrue(agentProviderRepository.deleteByName(name, 2L));
    assertNull(agentProviderRepository.getByName(name));
    assertFalse(agentProviderRepository.deleteByName(name, 3L));
  }

  @Test
  public void shouldAllowExactlyOneConcurrentCompareAndSetUpdate() throws Exception {
    String name = "concurrent-provider-" + System.nanoTime();
    assertTrue(agentProviderRepository.create(provider(name)));

    AgentProvider current = agentProviderRepository.getByName(name);
    AgentProvider first = withDescription(current, "first");
    AgentProvider second = withDescription(current, "second");

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
      assertEquals(Long.valueOf(1L), reread.version());
      assertTrue("first".equals(reread.description()) || "second".equals(reread.description()));
    } finally {
      executor.shutdownNow();
    }
  }

  private AgentProvider provider(String name) {
    return new AgentProvider(
        name, null, ProviderType.OPENAI, null, null, "{}", UUID.randomUUID(), null, null, null);
  }

  private static AgentProvider withDescription(AgentProvider source, String description) {
    return new AgentProvider(
        source.name(),
        description,
        source.providerType(),
        source.baseUrl(),
        source.credential(),
        source.configJson(),
        source.connectionGenerationId(),
        source.version(),
        source.createTime(),
        source.updateTime());
  }
}
