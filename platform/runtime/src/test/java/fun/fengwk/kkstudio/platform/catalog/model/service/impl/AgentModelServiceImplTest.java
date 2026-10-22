package fun.fengwk.kkstudio.platform.catalog.model.service.impl;

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

import fun.fengwk.kkstudio.platform.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.service.converter.AgentModelConverter;
import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelKind;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelUpdateDTO;

import java.sql.SQLException;

/** 原子 CAS 失败必须以类型化 {@link AiVersionConflictException} 抛出。 */
public class AgentModelServiceImplTest {

  @Test
  public void shouldRejectFailedRepositoryMutations() {
    AgentModelRepository repository = mock(AgentModelRepository.class);
    AgentModelConverter converter = mock(AgentModelConverter.class);
    AgentModelMutationFactory factory = mock(AgentModelMutationFactory.class);
    AgentModelReferenceResolver resolver = mock(AgentModelReferenceResolver.class);
    AgentModelServiceImpl service =
        new AgentModelServiceImpl(repository, converter, factory, resolver);

    AgentModel model = model();
    when(resolver.requireModelForUpdate("model")).thenReturn(model);
    AgentModelCreateDTO create = new AgentModelCreateDTO();
    create.setName("model");
    create.setKind(AgentModelKind.MODEL);
    create.setProviderName("provider");
    when(factory.newModel("model", create)).thenReturn(model);
    when(repository.create(model)).thenReturn(false);
    assertThrows(IllegalStateException.class, () -> service.createModel(create));

    when(repository.create(model)).thenThrow(new DuplicateKeyException("dup"));
    assertThrows(AiDuplicateException.class, () -> service.createModel(create));
    doThrow(integrityFailure("23503")).when(repository).create(model);
    assertThrows(AiResourceNotFoundException.class, () -> service.createModel(create));

    AgentModelUpdateDTO update = new AgentModelUpdateDTO();
    update.setName("model");
    update.setExpectedVersion("0");
    AgentModelUpdateDTO missing = new AgentModelUpdateDTO();
    assertThrows(AiValidationException.class, () -> service.updateModel("model", missing));
    when(resolver.requireModelForUpdate("model")).thenReturn(model);
    when(repository.updateByName(model, 0L)).thenReturn(false);
    when(repository.getByName("model")).thenReturn(null);
    assertThrows(AiResourceNotFoundException.class, () -> service.updateModel("model", update));

    AgentModel reread = model();
    reread.setVersion(3L);
    when(repository.getByName("model")).thenReturn(reread);
    assertThrows(AiVersionConflictException.class, () -> service.updateModel("model", update));

    when(repository.updateByName(model, 0L)).thenThrow(new DuplicateKeyException("dup"));
    assertThrows(AiDuplicateException.class, () -> service.updateModel("model", update));

    AgentModelUpdateDTO rename = new AgentModelUpdateDTO();
    rename.setName("renamed-model");
    rename.setExpectedVersion("0");
    doThrow(new DuplicateKeyException("dup")).when(repository).rename("model", model, 0L);
    assertThrows(AiDuplicateException.class, () -> service.updateModel("model", rename));

    when(repository.deleteByName(eq("model"), anyLong())).thenReturn(false);
    when(repository.getByName("model")).thenReturn(null);
    assertThrows(AiResourceNotFoundException.class, () -> service.deleteModel("model", "0"));
    when(repository.getByName("model")).thenReturn(reread);
    assertThrows(AiVersionConflictException.class, () -> service.deleteModel("model", "0"));
    verify(resolver, atLeastOnce()).requireModelForUpdate("model");

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
    AgentModel model = model();
    model.setVersion(1L);
    when(resolver.requireModel("model")).thenReturn(model);
    when(resolver.requireModelForUpdate("model")).thenReturn(model);

    AgentModelUpdateDTO update = new AgentModelUpdateDTO();
    update.setExpectedVersion("0");
    assertThrows(AiVersionConflictException.class, () -> service.updateModel("model", update));
    verify(factory, never()).update(model, update);

    doThrow(new AiInUseException("agent_model", "in use")).when(resolver).ensureDeletable("model");
    assertThrows(AiVersionConflictException.class, () -> service.deleteModel("model", "0"));
    verify(resolver, never()).ensureDeletable("model");
  }

  private static AgentModel model() {
    AgentModel model = new AgentModel();
    model.setProviderName("provider");
    model.setName("model");
    model.setKind(AgentModelKind.MODEL);
    model.setVersion(0L);
    return model;
  }

  private static DataIntegrityViolationException integrityFailure(String sqlState) {
    return new DataIntegrityViolationException(
        "database integrity failure", new SQLException("database failure", sqlState));
  }
}
