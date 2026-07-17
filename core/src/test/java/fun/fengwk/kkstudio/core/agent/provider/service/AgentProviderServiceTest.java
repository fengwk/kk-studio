package fun.fengwk.kkstudio.core.agent.provider.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.convention4j.api.page.PageQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderUpdateDTO;

import java.util.Arrays;

/** Provider names are global and credentials never cross the service boundary. */
@SpringBootTest(classes = CoreTestApplication.class)
public class AgentProviderServiceTest {

  @Autowired private AgentProviderService agentProviderService;

  @Test
  public void shouldEnforceGlobalNameAndRedactCredential() {
    String name = "provider-" + System.nanoTime();
    AgentProviderDTO provider = agentProviderService.createProvider(provider(name, "secret"));

    assertTrue(provider.isConfigured());
    assertFalse(hasProperty(provider, "credential"));
    assertThrows(
        IllegalArgumentException.class,
        () -> agentProviderService.createProvider(provider(name, "another")));
    assertTrue(
        agentProviderService.pageProviders(new PageQuery(1, 100)).getResults().stream()
            .anyMatch(candidate -> candidate.getId().equals(provider.getId())));

    AgentProviderUpdateDTO update = new AgentProviderUpdateDTO();
    update.setProviderType("openai");
    update.setCredential("rotated-secret");
    AgentProviderDTO updated = agentProviderService.updateProvider(id(provider.getId()), update);
    assertTrue(updated.isConfigured());
    assertThrows(
        IllegalArgumentException.class,
        () -> agentProviderService.updateProvider(Long.MAX_VALUE, update));

    agentProviderService.deleteProvider(id(provider.getId()));
    assertThrows(
        IllegalArgumentException.class,
        () -> agentProviderService.deleteProvider(id(provider.getId())));
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
