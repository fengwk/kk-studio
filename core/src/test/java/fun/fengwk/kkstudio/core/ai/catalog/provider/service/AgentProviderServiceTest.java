package fun.fengwk.kkstudio.core.ai.catalog.provider.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.convention4j.api.page.PageQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.core.ai.error.AiDuplicateException;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.ai.error.AiVersionConflictException;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderUpdateDTO;

import java.util.Arrays;

/** Provider names are immutable global identities and credentials never cross the API boundary. */
public class AgentProviderServiceTest extends PostgresSpringTestSupport {

  @Autowired private AgentProviderService agentProviderService;

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

    agentProviderService.deleteProvider(name, updated.getVersion());
    assertThrows(
        AiResourceNotFoundException.class, () -> agentProviderService.deleteProvider(name, "0"));
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
