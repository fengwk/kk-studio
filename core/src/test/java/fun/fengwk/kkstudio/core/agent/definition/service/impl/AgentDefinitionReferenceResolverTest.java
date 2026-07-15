package fun.fengwk.kkstudio.core.agent.definition.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fun.fengwk.kkstudio.core.agent.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import org.junit.jupiter.api.Test;

/** Missing and duplicate global Agent references fail before persistence changes. */
public class AgentDefinitionReferenceResolverTest {

  @Test
  public void shouldRejectMissingAndDuplicateReferences() {
    AgentDefinitionRepository definitions = mock(AgentDefinitionRepository.class);
    AgentModelRepository models = mock(AgentModelRepository.class);
    AgentDefinitionReferenceResolver resolver =
        new AgentDefinitionReferenceResolver(definitions, models);

    assertThrows(IllegalArgumentException.class, () -> resolver.requireAgent(1L));
    assertThrows(IllegalArgumentException.class, () -> resolver.requireModel(2L));

    when(definitions.getByName("duplicate")).thenReturn(new AgentDefinition());
    assertThrows(IllegalArgumentException.class, () -> resolver.ensureNameAvailable("duplicate"));
    resolver.ensureNameAvailable("same", "same");
  }
}
