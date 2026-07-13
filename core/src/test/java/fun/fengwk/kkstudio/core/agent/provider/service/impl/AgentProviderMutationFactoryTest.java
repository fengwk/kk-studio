package fun.fengwk.kkstudio.core.agent.provider.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderUpdateDTO;

/** Provider credential patches preserve the stored secret unless a replacement is supplied. */
public class AgentProviderMutationFactoryTest {

  @Test
  public void shouldRetainCredentialWhenUpdateDoesNotProvideOne() {
    AgentProviderMutationFactory factory = factory();
    AgentProviderCreateDTO create = new AgentProviderCreateDTO();
    create.setName("provider");
    create.setProviderType("openai");
    create.setCredential(" initial-secret ");
    AgentProvider provider = factory.newProvider(1L, create);

    AgentProviderUpdateDTO update = new AgentProviderUpdateDTO();
    update.setProviderType("openai");
    update.setCredential(" ");
    factory.update(provider, update);

    assertEquals("initial-secret", provider.getCredential());
  }

  private AgentProviderMutationFactory factory() {
    return new AgentProviderMutationFactory(new AgentEditableSupport(new ObjectMapper()));
  }
}
