package fun.fengwk.kkstudio.core.agent.provider.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.converter.AgentProviderConverter;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.ai.error.AiDuplicateException;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.ai.error.AiVersionConflictException;
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderUpdateDTO;

/** Atomic CAS failure must surface as the typed {@link AiVersionConflictException}. */
public class AgentProviderServiceImplTest {

  @Test
  public void shouldRejectFailedRepositoryMutations() {
    AgentProviderRepository repository = mock(AgentProviderRepository.class);
    AgentProviderConverter converter = mock(AgentProviderConverter.class);
    AgentProviderMutationFactory factory = mock(AgentProviderMutationFactory.class);
    AgentProviderGuard guard = mock(AgentProviderGuard.class);
    AgentProviderServiceImpl service =
        new AgentProviderServiceImpl(repository, converter, factory, guard);

    AgentProvider provider = new AgentProvider();
    provider.setId(1L);
    provider.setName("provider");
    AgentProviderCreateDTO create = new AgentProviderCreateDTO();
    when(factory.newProvider(create)).thenReturn(provider);
    when(repository.create(provider)).thenReturn(false);
    assertThrows(IllegalStateException.class, () -> service.createProvider(create));

    when(repository.create(provider)).thenThrow(new DuplicateKeyException("dup"));
    assertThrows(AiDuplicateException.class, () -> service.createProvider(create));

    AgentProviderUpdateDTO update = new AgentProviderUpdateDTO();
    update.setExpectedVersion("0");
    AgentProviderUpdateDTO missing = new AgentProviderUpdateDTO();
    assertThrows(AiValidationException.class, () -> service.updateProvider(1L, missing));
    when(guard.requireProvider(1L)).thenReturn(provider);
    when(repository.updateById(provider, 0L)).thenReturn(false);
    when(repository.getById(1L)).thenReturn(null);
    assertThrows(AiResourceNotFoundException.class, () -> service.updateProvider(1L, update));

    AgentProvider reread = new AgentProvider();
    reread.setId(1L);
    reread.setVersion(5L);
    when(repository.updateById(provider, 0L)).thenReturn(false);
    when(repository.getById(1L)).thenReturn(reread);
    assertThrows(AiVersionConflictException.class, () -> service.updateProvider(1L, update));

    when(repository.updateById(provider, 0L)).thenThrow(new DuplicateKeyException("dup"));
    assertThrows(AiDuplicateException.class, () -> service.updateProvider(1L, update));

    when(repository.deleteById(eq(1L), anyLong())).thenReturn(false);
    when(repository.getById(1L)).thenReturn(null);
    assertThrows(AiResourceNotFoundException.class, () -> service.deleteProvider(1L, "0"));
    when(repository.getById(1L)).thenReturn(reread);
    assertThrows(AiVersionConflictException.class, () -> service.deleteProvider(1L, "0"));
  }
}
