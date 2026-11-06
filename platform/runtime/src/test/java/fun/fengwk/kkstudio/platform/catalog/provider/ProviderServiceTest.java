package fun.fengwk.kkstudio.platform.catalog.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.catalog.dto.ProviderCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ProviderDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ProviderUpdateDTO;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.platform.secret.SecretUpdate;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Provider 名称全局唯一；记录存续期间名称与协议类型不可变，硬删除后可同名重建。凭据绝不回读。 */
public class ProviderServiceTest extends PostgresSpringTestSupport {

  @Autowired private ProviderService providerService;
  @Autowired private JdbcTemplate jdbc;

  @Test
  public void shouldEnforceGlobalNameAndRedactCredential() {
    String name = "provider-" + System.nanoTime();
    ProviderDTO provider = providerService.createProvider(provider(name, "secret"));

    assertTrue(provider.getCredential().configured());
    assertEquals(name, provider.getName());
    assertEquals(ProviderType.OPENAI, provider.getProviderType());
    assertEquals(0L, provider.getVersion());
    assertThrows(
        DomainException.class, () -> providerService.createProvider(provider(name, "another")));
    assertTrue(
        providerService.listProviders().stream()
            .anyMatch(candidate -> candidate.getName().equals(name)));

    ProviderUpdateDTO update = update(SecretUpdate.set("rotated-secret"), provider.getVersion());
    ProviderDTO updated = providerService.updateProvider(name, update);
    assertEquals(name, updated.getName());
    assertEquals(ProviderType.OPENAI, updated.getProviderType());
    assertTrue(updated.getCredential().configured());
    assertEquals(1L, updated.getVersion());
    assertThrows(DomainException.class, () -> providerService.updateProvider(name, update));

    ProviderUpdateDTO badUpdate = new ProviderUpdateDTO();
    assertThrows(DomainException.class, () -> providerService.updateProvider(name, badUpdate));
    assertThrows(
        DomainException.class, () -> providerService.updateProvider("missing-" + name, update));

    // 硬删除：行物理消失且同名立即可重建。
    providerService.deleteProvider(name, updated.getVersion());
    assertEquals(
        0,
        jdbc.queryForObject("select count(*) from provider where name = ?", Integer.class, name));
    ProviderDTO recreated = providerService.createProvider(provider(name, "replacement"));
    assertEquals(name, recreated.getName());
    providerService.deleteProvider(name, recreated.getVersion());
    assertThrows(DomainException.class, () -> providerService.deleteProvider(name, 0L));
  }

  /** 更新用 SecretUpdate 三态：KEEP 保留、CLEAR 清除、SET 覆盖。 */
  @Test
  public void shouldApplyCredentialSecretUpdates() {
    String name = "provider-secret-" + System.nanoTime();
    ProviderDTO created = providerService.createProvider(provider(name, "initial"));
    assertTrue(created.getCredential().configured());

    ProviderDTO kept =
        providerService.updateProvider(name, update(SecretUpdate.keep(), created.getVersion()));
    assertTrue(kept.getCredential().configured());

    ProviderDTO cleared =
        providerService.updateProvider(name, update(SecretUpdate.clear(), kept.getVersion()));
    assertFalse(cleared.getCredential().configured());

    ProviderDTO set =
        providerService.updateProvider(
            name, update(SecretUpdate.set("next"), cleared.getVersion()));
    assertTrue(set.getCredential().configured());

    providerService.deleteProvider(name, set.getVersion());
  }

  @Test
  public void concurrentUpdatesAllowExactlyOneWinningCas() throws Exception {
    String name = "provider-concurrent-" + System.nanoTime();
    ProviderDTO created = providerService.createProvider(provider(name, "secret"));
    ProviderUpdateDTO first = update(SecretUpdate.set("first-secret"), created.getVersion());
    ProviderUpdateDTO second = update(SecretUpdate.set("second-secret"), created.getVersion());
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<ProviderDTO> firstResult =
          executor.submit(() -> updateAfter(start, ready, name, first));
      Future<ProviderDTO> secondResult =
          executor.submit(() -> updateAfter(start, ready, name, second));
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();

      int successes = 0;
      Long currentVersion = null;
      for (Future<ProviderDTO> result : Arrays.asList(firstResult, secondResult)) {
        try {
          ProviderDTO updated = result.get(5, TimeUnit.SECONDS);
          successes++;
          currentVersion = updated.getVersion();
        } catch (ExecutionException error) {
          assertTrue(
              error.getCause() instanceof DomainException conflict
                  && conflict.code() == DomainErrorCode.VERSION_CONFLICT);
        }
      }
      assertEquals(1, successes);
      providerService.deleteProvider(name, currentVersion);
    } finally {
      executor.shutdownNow();
    }
  }

  private ProviderDTO updateAfter(
      CountDownLatch start, CountDownLatch ready, String name, ProviderUpdateDTO update)
      throws InterruptedException {
    ready.countDown();
    start.await();
    return providerService.updateProvider(name, update);
  }

  private static ProviderUpdateDTO update(SecretUpdate credential, long expectedVersion) {
    ProviderUpdateDTO update = new ProviderUpdateDTO();
    update.setCredential(credential);
    update.setModelCallTimeoutMillis(1_800_000L);
    update.setModelCallIdleTimeoutMillis(120_000L);
    update.setExpectedVersion(expectedVersion);
    return update;
  }

  private static ProviderCreateDTO provider(String name, String credential) {
    ProviderCreateDTO dto = new ProviderCreateDTO();
    dto.setName(name);
    dto.setProviderType(ProviderType.OPENAI);
    dto.setBaseUrl("https://example.invalid/v1");
    dto.setCredential(credential);
    return dto;
  }

  private boolean hasProperty(ProviderDTO provider, String property) {
    return Arrays.stream(provider.getClass().getDeclaredFields())
        .anyMatch(field -> field.getName().equals(property));
  }
}
