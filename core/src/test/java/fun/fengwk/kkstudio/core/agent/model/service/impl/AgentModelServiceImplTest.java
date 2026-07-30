package fun.fengwk.kkstudio.core.agent.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.service.converter.AgentModelConverter;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.ai.error.AiDuplicateException;
import fun.fengwk.kkstudio.core.ai.error.AiInUseException;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.ai.error.AiVersionConflictException;
import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelUpdateDTO;

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
    model.setId(2L);
    model.setProviderId(1L);
    model.setName("model");
    model.setVersion(0L);
    AgentModelCreateDTO create = new AgentModelCreateDTO();
    create.setProviderId("1");
    when(factory.newModel(1L, create)).thenReturn(model);
    when(repository.create(model)).thenReturn(false);
    assertThrows(IllegalStateException.class, () -> service.createModel(create));

    when(repository.create(model)).thenThrow(new DuplicateKeyException("dup"));
    assertThrows(AiDuplicateException.class, () -> service.createModel(create));
    doThrow(new DataIntegrityViolationException("provider deleted")).when(repository).create(model);
    assertThrows(AiResourceNotFoundException.class, () -> service.createModel(create));

    AgentModelUpdateDTO update = new AgentModelUpdateDTO();
    update.setExpectedVersion("0");
    AgentModelUpdateDTO missing = new AgentModelUpdateDTO();
    assertThrows(AiValidationException.class, () -> service.updateModel(2L, missing));
    when(resolver.requireModel(2L)).thenReturn(model);
    when(repository.updateById(model, 0L)).thenReturn(false);
    when(repository.getById(2L)).thenReturn(null);
    assertThrows(AiResourceNotFoundException.class, () -> service.updateModel(2L, update));

    AgentModel reread = new AgentModel();
    reread.setId(2L);
    reread.setVersion(3L);
    when(repository.updateById(model, 0L)).thenReturn(false);
    when(repository.getById(2L)).thenReturn(reread);
    assertThrows(AiVersionConflictException.class, () -> service.updateModel(2L, update));

    when(repository.updateById(model, 0L)).thenThrow(new DuplicateKeyException("dup"));
    assertThrows(AiDuplicateException.class, () -> service.updateModel(2L, update));

    when(repository.deleteById(eq(2L), anyLong())).thenReturn(false);
    when(repository.getById(2L)).thenReturn(null);
    assertThrows(AiResourceNotFoundException.class, () -> service.deleteModel(2L, "0"));
    when(repository.getById(2L)).thenReturn(reread);
    assertThrows(AiVersionConflictException.class, () -> service.deleteModel(2L, "0"));

    model.setVersion(0L);
    when(repository.deleteById(eq(2L), anyLong()))
        .thenThrow(new DataIntegrityViolationException("model still referenced"));
    assertThrows(AiInUseException.class, () -> service.deleteModel(2L, "0"));
  }

  @Test
  public void shouldPrioritizeStaleVersionOverDuplicateAndInUseChecks() {
    AgentModelRepository repository = mock(AgentModelRepository.class);
    AgentModelConverter converter = mock(AgentModelConverter.class);
    AgentModelMutationFactory factory = mock(AgentModelMutationFactory.class);
    AgentModelReferenceResolver resolver = mock(AgentModelReferenceResolver.class);
    AgentModelServiceImpl service =
        new AgentModelServiceImpl(repository, converter, factory, resolver);
    AgentModel model = new AgentModel();
    model.setId(2L);
    model.setProviderId(1L);
    model.setName("model");
    model.setVersion(1L);
    when(resolver.requireModel(2L)).thenReturn(model);

    AgentModelUpdateDTO update = new AgentModelUpdateDTO();
    update.setExpectedVersion("0");
    doThrow(new AiDuplicateException("agent_model", "duplicate"))
        .when(resolver)
        .ensureNameAvailable(1L, "model", "model");
    assertThrows(AiVersionConflictException.class, () -> service.updateModel(2L, update));
    verify(factory, never()).update(model, update);

    doThrow(new AiInUseException("agent_model", "in use")).when(resolver).ensureDeletable(2L);
    assertThrows(AiVersionConflictException.class, () -> service.deleteModel(2L, "0"));
    verify(resolver, never()).ensureDeletable(2L);
  }
}
