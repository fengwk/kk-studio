package fun.fengwk.kkstudio.core.agent.provider.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderUpdateDTO;

/** Provider mutation validation and credential patch behavior. */
public class AgentProviderMutationFactoryTest {

  @Test
  public void shouldRetainCredentialWhenUpdateDoesNotProvideOne() {
    AgentProviderMutationFactory factory = factory();
    AgentProviderCreateDTO create = provider("provider", " initial-secret ");
    AgentProvider provider = factory.newProvider(create);

    AgentProviderUpdateDTO update = new AgentProviderUpdateDTO();
    update.setProviderType("openai");
    update.setCredential(" ");
    factory.update(provider, update);

    assertEquals("initial-secret", provider.getCredential());
    assertEquals("{}", provider.getConfigJson());
  }

  @Test
  public void shouldRejectInvalidProviderConfiguration() {
    AgentProviderMutationFactory factory = factory();
    assertThrows(IllegalArgumentException.class, () -> factory.newProvider(null));

    AgentProviderCreateDTO blank = provider(" ", null);
    assertThrows(IllegalArgumentException.class, () -> factory.newProvider(blank));

    AgentProviderCreateDTO unsupported = provider("provider", null);
    unsupported.setProviderType("missing");
    assertThrows(IllegalArgumentException.class, () -> factory.newProvider(unsupported));

    AgentProviderCreateDTO invalidJson = provider("provider", null);
    invalidJson.setConfigJson("[]");
    assertThrows(IllegalArgumentException.class, () -> factory.newProvider(invalidJson));
  }

  private AgentProviderCreateDTO provider(String name, String credential) {
    AgentProviderCreateDTO dto = new AgentProviderCreateDTO();
    dto.setName(name);
    dto.setProviderType("openai");
    dto.setCredential(credential);
    return dto;
  }

  private AgentProviderMutationFactory factory() {
    return new AgentProviderMutationFactory(new AgentEditableSupport(new ObjectMapper()));
  }
}
