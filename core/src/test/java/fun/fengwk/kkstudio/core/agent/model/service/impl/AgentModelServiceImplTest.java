package fun.fengwk.kkstudio.core.agent.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.service.converter.AgentModelConverter;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelUpdateDTO;

/** Persistence failures must not be reported as successful model mutations. */
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
    AgentModelCreateDTO create = new AgentModelCreateDTO();
    create.setProviderId("1");
    when(factory.newModel(1L, create)).thenReturn(model);
    when(repository.create(model)).thenReturn(false);
    assertThrows(IllegalStateException.class, () -> service.createModel(create));

    AgentModelUpdateDTO update = new AgentModelUpdateDTO();
    when(resolver.requireModel(2L)).thenReturn(model);
    when(repository.updateById(model)).thenReturn(false);
    assertThrows(IllegalStateException.class, () -> service.updateModel(2L, update));

    when(repository.deleteById(2L)).thenReturn(false);
    assertThrows(IllegalStateException.class, () -> service.deleteModel(2L));
  }
}
