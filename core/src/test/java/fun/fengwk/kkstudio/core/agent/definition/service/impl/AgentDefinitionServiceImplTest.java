package fun.fengwk.kkstudio.core.agent.definition.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import fun.fengwk.kkstudio.core.agent.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.core.agent.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.agent.definition.service.converter.AgentDefinitionConverter;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.model.runtime.AgentModelDefaultVariantResolver;
import fun.fengwk.kkstudio.core.ai.error.AiDuplicateException;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.ai.error.AiVersionConflictException;
import fun.fengwk.kkstudio.share.model.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionUpdateDTO;

/** Atomic CAS failure must surface as the typed {@link AiVersionConflictException}. */
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

    assertThrows(AiValidationException.class, () -> service.createAgent(null));

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

    when(repository.create(definition)).thenThrow(new DuplicateKeyException("dup"));
    assertThrows(AiDuplicateException.class, () -> service.createAgent(create));

    AgentDefinitionUpdateDTO update = new AgentDefinitionUpdateDTO();
    update.setExpectedVersion("0");
    update.setModelId("2");
    AgentDefinitionUpdateDTO missing = new AgentDefinitionUpdateDTO();
    assertThrows(AiValidationException.class, () -> service.updateAgent(3L, missing));
    when(resolver.requireAgent(3L)).thenReturn(definition);
    when(repository.updateById(definition, 0L)).thenReturn(false);
    when(repository.getById(3L)).thenReturn(null);
    assertThrows(AiResourceNotFoundException.class, () -> service.updateAgent(3L, update));

    AgentDefinition reread = new AgentDefinition();
    reread.setId(3L);
    reread.setVersion(7L);
    when(repository.updateById(definition, 0L)).thenReturn(false);
    when(repository.getById(3L)).thenReturn(reread);
    assertThrows(AiVersionConflictException.class, () -> service.updateAgent(3L, update));

    when(repository.updateById(definition, 0L)).thenThrow(new DuplicateKeyException("dup"));
    assertThrows(AiDuplicateException.class, () -> service.updateAgent(3L, update));

    when(repository.deleteById(eq(3L), anyLong())).thenReturn(false);
    when(repository.getById(3L)).thenReturn(null);
    assertThrows(AiResourceNotFoundException.class, () -> service.deleteAgent(3L, "0"));
    when(repository.getById(3L)).thenReturn(reread);
    assertThrows(AiVersionConflictException.class, () -> service.deleteAgent(3L, "0"));
  }
}
