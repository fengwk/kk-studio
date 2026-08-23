package fun.fengwk.kkstudio.platform.catalog.provider.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.api.page.PageQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderUpdateDTO;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Provider 名称全局唯一；记录存续期间名称不可变，硬删除后可同名重建。凭据绝不越过 API 边界。 */
public class AgentProviderServiceTest extends PostgresSpringTestSupport {

  @Autowired private AgentProviderService agentProviderService;
  @Autowired private JdbcTemplate jdbc;

  @Test
  public void shouldEnforceGlobalNameAndRedactCredential() {
    String name = "provider-" + System.nanoTime();
    AgentProviderDTO provider = agentProviderService.createProvider(provider(name, "secret"));

    assertTrue(provider.isConfigured());
    assertFalse(hasProperty(provider, "credential"));
    assertEquals(name, provider.getName());
    assertEquals("0", provider.getVersion());
    assertThrows(
        AiDuplicateException.class,
        () -> agentProviderService.createProvider(provider(name, "another")));
    assertTrue(
        agentProviderService.pageProviders(new PageQuery(1, 100)).getResults().stream()
            .anyMatch(candidate -> candidate.getName().equals(name)));

    AgentProviderUpdateDTO update = new AgentProviderUpdateDTO();
    update.setProviderType("openai");
    update.setCredential("rotated-secret");
    update.setExpectedVersion(provider.getVersion());
    AgentProviderDTO updated = agentProviderService.updateProvider(name, update);
    assertEquals(name, updated.getName());
    assertTrue(updated.isConfigured());
    assertEquals("1", updated.getVersion());
    assertThrows(
        AiVersionConflictException.class, () -> agentProviderService.updateProvider(name, update));

    AgentProviderUpdateDTO badUpdate = new AgentProviderUpdateDTO();
    assertThrows(
        AiValidationException.class, () -> agentProviderService.updateProvider(name, badUpdate));
    assertThrows(
        AiResourceNotFoundException.class,
        () -> agentProviderService.updateProvider("missing-" + name, update));

    // 硬删除：行物理消失且同名立即可重建。
    agentProviderService.deleteProvider(name, updated.getVersion());
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from agent_provider where name = ?", Integer.class, name));
    AgentProviderDTO recreated = agentProviderService.createProvider(provider(name, "replacement"));
    assertEquals(name, recreated.getName());
    agentProviderService.deleteProvider(name, recreated.getVersion());
    assertThrows(
        AiResourceNotFoundException.class, () -> agentProviderService.deleteProvider(name, "0"));
  }

  @Test
  public void credentialIsWriteOnlyForGenericJsonSerialization() throws Exception {
    AgentProviderCreateDTO input = provider("provider-json", "secret");
    ObjectMapper objectMapper = new ObjectMapper();

    String json = objectMapper.writeValueAsString(input);

    assertFalse(json.contains("secret"));
    assertEquals(
        "secret",
        objectMapper
            .readValue(jsonWithCredential(json), AgentProviderCreateDTO.class)
            .getCredential());
  }

  @Test
  public void concurrentUpdatesAllowExactlyOneWinningCas() throws Exception {
    String name = "provider-concurrent-" + System.nanoTime();
    AgentProviderDTO created = agentProviderService.createProvider(provider(name, "secret"));
    AgentProviderUpdateDTO first = update("first-secret", created.getVersion());
    AgentProviderUpdateDTO second = update("second-secret", created.getVersion());
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<AgentProviderDTO> firstResult =
          executor.submit(() -> updateAfter(start, ready, name, first));
      Future<AgentProviderDTO> secondResult =
          executor.submit(() -> updateAfter(start, ready, name, second));
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();

      int successes = 0;
      String currentVersion = null;
      for (Future<AgentProviderDTO> result : Arrays.asList(firstResult, secondResult)) {
        try {
          AgentProviderDTO updated = result.get(5, TimeUnit.SECONDS);
          successes++;
          currentVersion = updated.getVersion();
        } catch (ExecutionException error) {
          assertTrue(error.getCause() instanceof AiVersionConflictException);
        }
      }
      assertEquals(1, successes);
      agentProviderService.deleteProvider(name, currentVersion);
    } finally {
      executor.shutdownNow();
    }
  }

  private String jsonWithCredential(String json) {
    return json.replaceFirst("\\}$", ",\"credential\":\"secret\"}");
  }

  private AgentProviderDTO updateAfter(
      CountDownLatch start, CountDownLatch ready, String name, AgentProviderUpdateDTO update)
      throws InterruptedException {
    ready.countDown();
    start.await();
    return agentProviderService.updateProvider(name, update);
  }

  private AgentProviderUpdateDTO update(String credential, String expectedVersion) {
    AgentProviderUpdateDTO update = new AgentProviderUpdateDTO();
    update.setProviderType("openai");
    update.setCredential(credential);
    update.setExpectedVersion(expectedVersion);
    return update;
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
}
