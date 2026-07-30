package fun.fengwk.kkstudio.core.agent.provider.service;

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
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderUpdateDTO;

import java.util.Arrays;

/** Provider names are global and credentials never cross the service boundary. */
public class AgentProviderServiceTest extends PostgresSpringTestSupport {

  @Autowired private AgentProviderService agentProviderService;

  @Test
  public void shouldEnforceGlobalNameAndRedactCredential() {
    String name = "provider-" + System.nanoTime();
    AgentProviderDTO provider = agentProviderService.createProvider(provider(name, "secret"));

    assertTrue(provider.isConfigured());
    assertFalse(hasProperty(provider, "credential"));
    assertEquals("0", provider.getVersion());
    assertThrows(
        AiDuplicateException.class,
        () -> agentProviderService.createProvider(provider(name, "another")));
    assertTrue(
        agentProviderService.pageProviders(new PageQuery(1, 100)).getResults().stream()
            .anyMatch(candidate -> candidate.getId().equals(provider.getId())));

    AgentProviderUpdateDTO update = new AgentProviderUpdateDTO();
    update.setProviderType("openai");
    update.setCredential("rotated-secret");
    update.setExpectedVersion(provider.getVersion());
    AgentProviderDTO updated = agentProviderService.updateProvider(id(provider.getId()), update);
    assertTrue(updated.isConfigured());
    assertEquals("1", updated.getVersion());
    assertThrows(
        AiVersionConflictException.class,
        () -> agentProviderService.updateProvider(id(provider.getId()), update));

    AgentProviderUpdateDTO badUpdate = new AgentProviderUpdateDTO();
    assertThrows(
        AiValidationException.class,
        () -> agentProviderService.updateProvider(id(provider.getId()), badUpdate));

    assertThrows(
        AiResourceNotFoundException.class,
        () -> agentProviderService.updateProvider(Long.MAX_VALUE, update));
    assertThrows(
        AiVersionConflictException.class,
        () ->
            agentProviderService.updateProvider(
                id(provider.getId()), staleUpdate(provider.getVersion(), "rotated-secret-2")));

    agentProviderService.deleteProvider(id(provider.getId()), updated.getVersion());
    assertThrows(
        AiResourceNotFoundException.class,
        () -> agentProviderService.deleteProvider(id(provider.getId()), "0"));
  }

  private AgentProviderUpdateDTO staleUpdate(String version, String credential) {
    AgentProviderUpdateDTO update = new AgentProviderUpdateDTO();
    update.setProviderType("openai");
    update.setCredential(credential);
    update.setExpectedVersion(version);
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

  private long id(String value) {
    return Long.parseLong(value);
  }
}
