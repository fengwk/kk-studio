package fun.fengwk.kkstudio.core.ai.catalog.definition.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.ai.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.ai.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;

/** 缺失的全局 Agent / model 引用在持久化变更前必须失败。 */
public class AgentDefinitionReferenceResolverTest {

  @Test
  public void shouldRejectMissingReferences() {
    AgentDefinitionRepository definitions = mock(AgentDefinitionRepository.class);
    AgentModelRepository models = mock(AgentModelRepository.class);
    AgentDefinitionReferenceResolver resolver =
        new AgentDefinitionReferenceResolver(definitions, models);

    assertThrows(AiResourceNotFoundException.class, () -> resolver.requireAgent("missing"));
    assertThrows(
        AiResourceNotFoundException.class, () -> resolver.requireModel("provider", "missing"));
  }
}
