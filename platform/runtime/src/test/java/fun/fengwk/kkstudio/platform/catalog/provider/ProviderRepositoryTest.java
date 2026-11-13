package fun.fengwk.kkstudio.platform.catalog.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Provider 仓库契约仅以不可变的全局名称为键。 */
public class ProviderRepositoryTest extends PostgresSpringTestSupport {

  @Autowired private ProviderRepository agentProviderRepository;
  @Autowired private JdbcTemplate jdbc;

  @Test
  public void shouldPersistQueryUpdateAndDeleteGlobalProvider() {
    String name = "repository-provider-" + System.nanoTime();
    Provider provider = provider(name);
    assertTrue(agentProviderRepository.create(provider));

    Provider stored = agentProviderRepository.getByName(name);
    assertNotNull(stored);
    assertEquals(name, stored.name());
    assertEquals(ProviderType.OPENAI, stored.providerType());
    // Repository 必须显式写入稳定 wire 值，不做隐式 enum 名称转换。
    assertEquals(
        "openai",
        jdbc.queryForObject(
            "select provider_type from provider where name = ?", String.class, name));
    assertEquals("{}", stored.configJson());
    assertNotNull(stored.createdAt());
    assertNotNull(stored.updatedAt());
    assertTrue(
        agentProviderRepository.listAll().stream()
            .anyMatch(candidate -> candidate.name().equals(name)));

    Provider edited = withDescription(stored, "updated");
    assertTrue(agentProviderRepository.updateByName(edited, 0L));
    Provider updated = agentProviderRepository.getByName(name);
    assertEquals("updated", updated.description());
    assertEquals(Long.valueOf(1L), updated.version());

    assertFalse(agentProviderRepository.updateByName(edited, 0L));
    assertTrue(agentProviderRepository.updateByName(edited, 1L));
    Provider reread = agentProviderRepository.getByName(name);
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

    Provider current = agentProviderRepository.getByName(name);
    Provider first = withDescription(current, "first");
    Provider second = withDescription(current, "second");

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
      Provider reread = agentProviderRepository.getByName(name);
      assertEquals(Long.valueOf(1L), reread.version());
      assertTrue("first".equals(reread.description()) || "second".equals(reread.description()));
    } finally {
      executor.shutdownNow();
    }
  }

  private Provider provider(String name) {
    return new Provider(name, null, ProviderType.OPENAI, null, null, "{}", null, null, null);
  }

  private static Provider withDescription(Provider source, String description) {
    return new Provider(
        source.name(),
        description,
        source.providerType(),
        source.baseUrl(),
        source.credential(),
        source.configJson(),
        source.version(),
        source.createdAt(),
        source.updatedAt());
  }
}
