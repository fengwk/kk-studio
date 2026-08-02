package fun.fengwk.kkstudio.core.ai.catalog.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import fun.fengwk.kkstudio.core.ai.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.ai.catalog.model.service.converter.AgentModelConverter;
import fun.fengwk.kkstudio.core.ai.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.ai.error.AiDuplicateException;
import fun.fengwk.kkstudio.core.ai.error.AiInUseException;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.ai.error.AiVersionConflictException;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelUpdateDTO;

import java.sql.SQLException;

/** Atomic CAS failure must surface as the typed {@link AiVersionConflictException}. */
public class AgentModelServiceImplTest {

  @Test
  public void shouldRejectFailedRepositoryMutations() {
    AgentModelRepository repository = mock(AgentModelRepository.class);
    AgentModelConverter converter = mock(AgentModelConverter.class);
    AgentModelMutationFactory factory = mock(AgentModelMutationFactory.class);
    AgentModelReferenceResolver resolver = mock(AgentModelReferenceResolver.class);
    AgentModelServiceImpl service =
        new AgentModelServiceImpl(repository, converter, factory, resolver);

    AgentModel model = new AgentModel();
    model.setProviderName("provider");
    model.setName("model");
    model.setVersion(0L);
    when(resolver.requireModelForUpdate("provider", "model")).thenReturn(model);
    AgentModelCreateDTO create = new AgentModelCreateDTO();
    create.setProviderName("provider");
    create.setName("model");
    when(factory.newModel("provider", "model", create)).thenReturn(model);
    when(repository.create(model)).thenReturn(false);
    assertThrows(IllegalStateException.class, () -> service.createModel(create));

    when(repository.create(model)).thenThrow(new DuplicateKeyException("dup"));
    assertThrows(AiDuplicateException.class, () -> service.createModel(create));
    doThrow(integrityFailure("23503")).when(repository).create(model);
    assertThrows(AiResourceNotFoundException.class, () -> service.createModel(create));

    AgentModelUpdateDTO update = new AgentModelUpdateDTO();
    update.setExpectedVersion("0");
    AgentModelUpdateDTO missing = new AgentModelUpdateDTO();
    assertThrows(
        AiValidationException.class, () -> service.updateModel("provider", "model", missing));
    when(resolver.requireModel("provider", "model")).thenReturn(model);
    when(repository.updateByName(model, 0L)).thenReturn(false);
    when(repository.getByProviderNameAndName("provider", "model")).thenReturn(null);
    assertThrows(
        AiResourceNotFoundException.class, () -> service.updateModel("provider", "model", update));

    AgentModel reread = new AgentModel();
    reread.setProviderName("provider");
    reread.setName("model");
    reread.setVersion(3L);
    when(repository.getByProviderNameAndName("provider", "model")).thenReturn(reread);
    assertThrows(
        AiVersionConflictException.class, () -> service.updateModel("provider", "model", update));

    when(repository.deleteByName(eq("provider"), eq("model"), anyLong())).thenReturn(false);
    when(repository.getByProviderNameAndName("provider", "model")).thenReturn(null);
    assertThrows(
        AiResourceNotFoundException.class, () -> service.deleteModel("provider", "model", "0"));
    when(repository.getByProviderNameAndName("provider", "model")).thenReturn(reread);
    assertThrows(
        AiVersionConflictException.class, () -> service.deleteModel("provider", "model", "0"));
    verify(resolver, atLeastOnce()).requireModelForUpdate("provider", "model");

    DataIntegrityViolationException nonForeignKey = integrityFailure("22001");
    doThrow(nonForeignKey).when(repository).create(model);
    assertSame(
        nonForeignKey,
        assertThrows(DataIntegrityViolationException.class, () -> service.createModel(create)));
  }

  @Test
  public void shouldPrioritizeStaleVersionOverDeletionChecks() {
    AgentModelRepository repository = mock(AgentModelRepository.class);
    AgentModelConverter converter = mock(AgentModelConverter.class);
    AgentModelMutationFactory factory = mock(AgentModelMutationFactory.class);
    AgentModelReferenceResolver resolver = mock(AgentModelReferenceResolver.class);
    AgentModelServiceImpl service =
        new AgentModelServiceImpl(repository, converter, factory, resolver);
    AgentModel model = new AgentModel();
    model.setProviderName("provider");
    model.setName("model");
    model.setVersion(1L);
    when(resolver.requireModel("provider", "model")).thenReturn(model);
    when(resolver.requireModelForUpdate("provider", "model")).thenReturn(model);

    AgentModelUpdateDTO update = new AgentModelUpdateDTO();
    update.setExpectedVersion("0");
    assertThrows(
        AiVersionConflictException.class, () -> service.updateModel("provider", "model", update));
    verify(factory, never()).update(model, update);

    doThrow(new AiInUseException("agent_model", "in use"))
        .when(resolver)
        .ensureDeletable("provider", "model");
    assertThrows(
        AiVersionConflictException.class, () -> service.deleteModel("provider", "model", "0"));
    verify(resolver, never()).ensureDeletable("provider", "model");
  }

  private static DataIntegrityViolationException integrityFailure(String sqlState) {
    return new DataIntegrityViolationException(
        "database integrity failure", new SQLException("database failure", sqlState));
  }
}
