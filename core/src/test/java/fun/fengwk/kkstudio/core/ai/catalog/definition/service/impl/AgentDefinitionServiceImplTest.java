package fun.fengwk.kkstudio.core.ai.catalog.definition.service.impl;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import fun.fengwk.kkstudio.core.ai.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.core.ai.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.ai.catalog.definition.service.converter.AgentDefinitionConverter;
import fun.fengwk.kkstudio.core.ai.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.ai.catalog.model.runtime.AgentModelDefaultVariantResolver;
import fun.fengwk.kkstudio.core.ai.error.AiDuplicateException;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.ai.error.AiVersionConflictException;
import fun.fengwk.kkstudio.harness.tool.ToolCatalog;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionUpdateDTO;

import java.sql.SQLException;

/** Atomic CAS failure must surface as the typed {@link AiVersionConflictException}. */
public class AgentDefinitionServiceImplTest {

  @Test
  public void shouldRejectInvalidIdsAndFailedRepositoryMutations() {
    AgentDefinitionRepository repository = mock(AgentDefinitionRepository.class);
    AgentDefinitionConverter converter = mock(AgentDefinitionConverter.class);
    AgentDefinitionMutationFactory factory = mock(AgentDefinitionMutationFactory.class);
    AgentDefinitionReferenceResolver resolver = mock(AgentDefinitionReferenceResolver.class);
    AgentModelDefaultVariantResolver variantResolver = mock(AgentModelDefaultVariantResolver.class);
    AgentDefinitionConfigValidator validator =
        new AgentDefinitionConfigValidator(mock(ToolCatalog.class));
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
    definition.setVersion(0L);
    AgentDefinitionCreateDTO create = new AgentDefinitionCreateDTO();
    create.setModelId("2");
    when(factory.newAgent(2L, create)).thenReturn(definition);
    when(repository.create(definition)).thenReturn(false);
    assertThrows(IllegalStateException.class, () -> service.createAgent(create));

    when(repository.create(definition)).thenThrow(new DuplicateKeyException("dup"));
    assertThrows(AiDuplicateException.class, () -> service.createAgent(create));
    doThrow(integrityFailure("23503")).when(repository).create(definition);
    assertThrows(AiResourceNotFoundException.class, () -> service.createAgent(create));

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
    doThrow(integrityFailure("23503")).when(repository).updateById(definition, 0L);
    assertThrows(AiResourceNotFoundException.class, () -> service.updateAgent(3L, update));

    DataIntegrityViolationException nonForeignKey = integrityFailure("22001");
    doThrow(nonForeignKey).when(repository).updateById(definition, 0L);
    assertSame(
        nonForeignKey,
        assertThrows(DataIntegrityViolationException.class, () -> service.updateAgent(3L, update)));

    when(repository.deleteById(eq(3L), anyLong())).thenReturn(false);
    when(repository.getById(3L)).thenReturn(null);
    assertThrows(AiResourceNotFoundException.class, () -> service.deleteAgent(3L, "0"));
    when(repository.getById(3L)).thenReturn(reread);
    assertThrows(AiVersionConflictException.class, () -> service.deleteAgent(3L, "0"));
  }

  @Test
  public void shouldPrioritizeStaleVersionOverInvalidModelReference() {
    AgentDefinitionRepository repository = mock(AgentDefinitionRepository.class);
    AgentDefinitionConverter converter = mock(AgentDefinitionConverter.class);
    AgentDefinitionMutationFactory factory = mock(AgentDefinitionMutationFactory.class);
    AgentDefinitionReferenceResolver resolver = mock(AgentDefinitionReferenceResolver.class);
    AgentModelDefaultVariantResolver variantResolver = mock(AgentModelDefaultVariantResolver.class);
    AgentDefinitionConfigValidator validator =
        new AgentDefinitionConfigValidator(mock(ToolCatalog.class));
    AgentDefinitionServiceImpl service =
        new AgentDefinitionServiceImpl(
            repository,
            converter,
            factory,
            resolver,
            variantResolver,
            validator,
            new AgentDefinitionConfigCodec(new ObjectMapper()));
    AgentDefinition definition = new AgentDefinition();
    definition.setId(3L);
    definition.setVersion(1L);
    when(resolver.requireAgent(3L)).thenReturn(definition);

    AgentDefinitionUpdateDTO update = new AgentDefinitionUpdateDTO();
    update.setExpectedVersion("0");
    update.setModelId("2");
    doThrow(new AiResourceNotFoundException("agent_model", "missing"))
        .when(resolver)
        .requireModel(2L);

    assertThrows(AiVersionConflictException.class, () -> service.updateAgent(3L, update));
    verify(resolver, never()).requireModel(2L);
  }

  private static DataIntegrityViolationException integrityFailure(String sqlState) {
    return new DataIntegrityViolationException(
        "database integrity failure", new SQLException("database failure", sqlState));
  }
}
