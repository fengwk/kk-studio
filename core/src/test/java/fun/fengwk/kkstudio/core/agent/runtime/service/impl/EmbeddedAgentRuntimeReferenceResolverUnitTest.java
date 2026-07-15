package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fun.fengwk.kkstudio.core.agent.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionRepository;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSession;
import org.junit.jupiter.api.Test;

/** Missing nodes in the legacy runtime configuration graph fail deterministically. */
public class EmbeddedAgentRuntimeReferenceResolverUnitTest {

  @Test
  public void shouldRejectMissingDefinitionModelAndProvider() {
    AgentDefinitionRepository definitions = mock(AgentDefinitionRepository.class);
    AgentModelRepository models = mock(AgentModelRepository.class);
    AgentProviderRepository providers = mock(AgentProviderRepository.class);
    AgentSessionRepository sessions = mock(AgentSessionRepository.class);
    EmbeddedAgentRuntimeReferenceResolver resolver =
        new EmbeddedAgentRuntimeReferenceResolver(definitions, models, providers, sessions);

    AgentSession session = new AgentSession();
    session.setAgentId(1L);
    when(sessions.getBySessionId("session")).thenReturn(session);
    assertThrows(IllegalArgumentException.class, () -> resolver.resolve("session"));

    AgentDefinition definition = new AgentDefinition();
    definition.setModelId(2L);
    when(definitions.getById(1L)).thenReturn(definition);
    assertThrows(IllegalArgumentException.class, () -> resolver.resolve("session"));

    AgentModel model = new AgentModel();
    model.setProviderId(3L);
    when(models.getById(2L)).thenReturn(model);
    assertThrows(IllegalArgumentException.class, () -> resolver.resolve("session"));
  }
}
