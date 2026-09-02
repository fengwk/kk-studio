package fun.fengwk.kkstudio.platform.catalog.definition.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;

import java.util.List;
import java.util.UUID;

/** 缺失的全局 Agent / model / environment 引用在持久化变更前必须失败。 */
public class AgentDefinitionReferenceResolverTest {

  @Test
  public void shouldRejectMissingReferences() {
    AgentDefinitionRepository definitions = mock(AgentDefinitionRepository.class);
    AgentModelRepository models = mock(AgentModelRepository.class);
    EnvironmentRepository environments = mock(EnvironmentRepository.class);
    AgentDefinitionReferenceResolver resolver =
        new AgentDefinitionReferenceResolver(definitions, models, environments);

    assertThrows(AiResourceNotFoundException.class, () -> resolver.requireAgent("missing"));
    assertThrows(
        AiResourceNotFoundException.class, () -> resolver.requireModel("provider", "missing"));
    UUID envId = UUID.randomUUID();
    when(environments.lockForKeyShare(envId)).thenReturn(null);
    assertThrows(
        AiResourceNotFoundException.class, () -> resolver.requireEnvironmentForShare(envId));

    assertDoesNotThrow(() -> resolver.requireEnvironmentForShare(null));
    when(environments.lockForKeyShare(envId)).thenReturn(new Environment());
    assertDoesNotThrow(() -> resolver.requireEnvironmentForShare(envId));
  }

  @Test
  void locksUpdatedAgentAndSubagentsInCanonicalOrder() {
    AgentDefinitionRepository definitions = mock(AgentDefinitionRepository.class);
    AgentDefinition target = new AgentDefinition();
    AgentDefinition alpha = new AgentDefinition();
    AgentDefinition omega = new AgentDefinition();
    when(definitions.getByNameForUpdate("alpha")).thenReturn(alpha);
    when(definitions.getByNameForUpdate("middle")).thenReturn(target);
    when(definitions.getByNameForUpdate("omega")).thenReturn(omega);
    AgentDefinitionReferenceResolver resolver =
        new AgentDefinitionReferenceResolver(
            definitions, mock(AgentModelRepository.class), mock(EnvironmentRepository.class));

    assertSame(
        target,
        resolver.requireAgentAndSubagentsForUpdate("middle", List.of("omega", "alpha", "middle")));

    InOrder ordered = inOrder(definitions);
    ordered.verify(definitions).getByNameForUpdate("alpha");
    ordered.verify(definitions).getByNameForUpdate("middle");
    ordered.verify(definitions).getByNameForUpdate("omega");
  }
}
