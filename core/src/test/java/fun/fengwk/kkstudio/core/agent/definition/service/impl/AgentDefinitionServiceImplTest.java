package fun.fengwk.kkstudio.core.agent.definition.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.agent.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.core.agent.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.agent.definition.service.converter.AgentDefinitionConverter;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.model.runtime.AgentModelDefaultVariantResolver;
import fun.fengwk.kkstudio.share.model.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionUpdateDTO;

/** Persistence and identifier failures must not be reported as successful Agent mutations. */
public class AgentDefinitionServiceImplTest {

  @Test
  public void shouldRejectInvalidIdsAndFailedRepositoryMutations() {
    AgentDefinitionRepository repository = mock(AgentDefinitionRepository.class);
    AgentDefinitionConverter converter = mock(AgentDefinitionConverter.class);
    AgentDefinitionMutationFactory factory = mock(AgentDefinitionMutationFactory.class);
    AgentDefinitionReferenceResolver resolver = mock(AgentDefinitionReferenceResolver.class);
    AgentModelDefaultVariantResolver variantResolver = mock(AgentModelDefaultVariantResolver.class);
    AgentDefinitionLiveCapabilityValidator validator =
        mock(AgentDefinitionLiveCapabilityValidator.class);
    doNothing().when(validator).validate(any());
    AgentDefinitionServiceImpl service =
        new AgentDefinitionServiceImpl(
            repository,
            converter,
            factory,
            resolver,
            variantResolver,
            validator,
            new AgentDefinitionConfigCodec(new ObjectMapper()));

    assertThrows(IllegalArgumentException.class, () -> service.createAgent(null));

    AgentDefinition definition = new AgentDefinition();
    definition.setId(3L);
    definition.setModelId(2L);
    definition.setName("agent");
    definition.setConfigJson("{\"tools\":[],\"skills\":[]}");
    AgentDefinitionCreateDTO create = new AgentDefinitionCreateDTO();
    create.setModelId("2");
    when(factory.newAgent(2L, create)).thenReturn(definition);
    when(repository.create(definition)).thenReturn(false);
    assertThrows(IllegalStateException.class, () -> service.createAgent(create));

    AgentDefinitionUpdateDTO update = new AgentDefinitionUpdateDTO();
    update.setModelId("2");
    when(resolver.requireAgent(3L)).thenReturn(definition);
    when(repository.updateById(definition)).thenReturn(false);
    assertThrows(IllegalStateException.class, () -> service.updateAgent(3L, update));

    when(repository.deleteById(3L)).thenReturn(false);
    assertThrows(IllegalStateException.class, () -> service.deleteAgent(3L));
  }
}
