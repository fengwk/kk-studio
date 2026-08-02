package fun.fengwk.kkstudio.core.ai.catalog.provider.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import fun.fengwk.kkstudio.core.ai.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.ai.catalog.provider.repo.AgentProviderRevisionRepository;
import fun.fengwk.kkstudio.core.ai.catalog.provider.service.converter.AgentProviderConverter;
import fun.fengwk.kkstudio.core.ai.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.ai.catalog.provider.service.model.AgentProviderRevision;
import fun.fengwk.kkstudio.core.ai.error.AiDuplicateException;
import fun.fengwk.kkstudio.core.ai.error.AiInUseException;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.ai.error.AiVersionConflictException;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderType;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderUpdateDTO;

/** Atomic CAS failure must surface as the typed {@link AiVersionConflictException}. */
public class AgentProviderServiceImplTest {

  @Test
  public void shouldRejectFailedRepositoryMutations() {
    AgentProviderRepository repository = mock(AgentProviderRepository.class);
    AgentProviderRevisionRepository revisions = mock(AgentProviderRevisionRepository.class);
    AgentProviderConverter converter = mock(AgentProviderConverter.class);
    AgentProviderMutationFactory factory = mock(AgentProviderMutationFactory.class);
    AgentProviderGuard guard = mock(AgentProviderGuard.class);
    AgentProviderServiceImpl service =
        new AgentProviderServiceImpl(repository, revisions, converter, factory, guard);

    AgentProvider provider = new AgentProvider();
    provider.setName("provider");
    provider.setVersion(0L);
    when(guard.requireProviderForUpdate("provider")).thenReturn(provider);
    AgentProviderCreateDTO create = new AgentProviderCreateDTO();
    create.setName("provider");
    when(factory.newProvider("provider", create)).thenReturn(provider);
    when(repository.create(provider)).thenReturn(false);
    assertThrows(IllegalStateException.class, () -> service.createProvider(create));

    when(repository.create(provider)).thenThrow(new DuplicateKeyException("dup"));
    assertThrows(AiDuplicateException.class, () -> service.createProvider(create));

    AgentProviderUpdateDTO update = new AgentProviderUpdateDTO();
    update.setExpectedVersion("0");
    AgentProviderUpdateDTO missing = new AgentProviderUpdateDTO();
    assertThrows(AiValidationException.class, () -> service.updateProvider("provider", missing));
    when(guard.requireProvider("provider")).thenReturn(provider);
    when(repository.updateByName(provider, 0L)).thenReturn(false);
    when(repository.getByName("provider")).thenReturn(null);
    assertThrows(
        AiResourceNotFoundException.class, () -> service.updateProvider("provider", update));
    verify(revisions, never()).create(any());

    AgentProvider reread = new AgentProvider();
    reread.setName("provider");
    reread.setVersion(5L);
    when(repository.getByName("provider")).thenReturn(reread);
    assertThrows(
        AiVersionConflictException.class, () -> service.updateProvider("provider", update));

    when(repository.deleteByName(eq("provider"), anyLong())).thenReturn(false);
    when(repository.getByName("provider")).thenReturn(null);
    assertThrows(AiResourceNotFoundException.class, () -> service.deleteProvider("provider", "0"));
    when(repository.getByName("provider")).thenReturn(reread);
    assertThrows(AiVersionConflictException.class, () -> service.deleteProvider("provider", "0"));
    verify(guard, atLeastOnce()).requireProviderForUpdate("provider");
  }

  @Test
  public void shouldPrioritizeStaleVersionOverDeletionChecks() {
    AgentProviderRepository repository = mock(AgentProviderRepository.class);
    AgentProviderRevisionRepository revisions = mock(AgentProviderRevisionRepository.class);
    AgentProviderConverter converter = mock(AgentProviderConverter.class);
    AgentProviderMutationFactory factory = mock(AgentProviderMutationFactory.class);
    AgentProviderGuard guard = mock(AgentProviderGuard.class);
    AgentProviderServiceImpl service =
        new AgentProviderServiceImpl(repository, revisions, converter, factory, guard);
    AgentProvider provider = new AgentProvider();
    provider.setName("provider");
    provider.setVersion(1L);
    when(guard.requireProvider("provider")).thenReturn(provider);
    when(guard.requireProviderForUpdate("provider")).thenReturn(provider);

    AgentProviderUpdateDTO update = new AgentProviderUpdateDTO();
    update.setExpectedVersion("0");
    assertThrows(
        AiVersionConflictException.class, () -> service.updateProvider("provider", update));
    verify(factory, never()).update(provider, update);

    doThrow(new AiInUseException("agent_provider", "in use"))
        .when(guard)
        .ensureDeletable("provider");
    assertThrows(AiVersionConflictException.class, () -> service.deleteProvider("provider", "0"));
    verify(guard, never()).ensureDeletable("provider");
  }

  @Test
  public void shouldWriteTheNextProviderRevisionOnlyAfterSuccessfulCas() {
    AgentProviderRepository repository = mock(AgentProviderRepository.class);
    AgentProviderRevisionRepository revisions = mock(AgentProviderRevisionRepository.class);
    AgentProviderConverter converter = mock(AgentProviderConverter.class);
    AgentProviderMutationFactory factory = mock(AgentProviderMutationFactory.class);
    AgentProviderGuard guard = mock(AgentProviderGuard.class);
    AgentProviderServiceImpl service =
        new AgentProviderServiceImpl(repository, revisions, converter, factory, guard);

    AgentProvider provider = new AgentProvider();
    provider.setName("provider");
    provider.setVersion(4L);
    provider.setProviderType(AgentProviderType.openai);
    provider.setBaseUrl("https://old.example");
    provider.setCredential("old-secret");
    provider.setConfigJson("{\"old\":true}");
    when(guard.requireProvider("provider")).thenReturn(provider);
    when(repository.updateByName(provider, 4L)).thenReturn(true);
    when(revisions.create(any())).thenReturn(true);
    when(repository.getByName("provider")).thenReturn(provider);

    AgentProviderUpdateDTO update = new AgentProviderUpdateDTO();
    update.setExpectedVersion("4");
    service.updateProvider("provider", update);

    ArgumentCaptor<AgentProviderRevision> captor =
        ArgumentCaptor.forClass(AgentProviderRevision.class);
    verify(revisions).create(captor.capture());
    AgentProviderRevision revision = captor.getValue();
    assertEquals("provider", revision.getProviderName());
    assertEquals(Long.valueOf(5L), revision.getProviderVersion());
    assertEquals("https://old.example", revision.getBaseUrl());
    assertEquals("old-secret", revision.getCredential());
    assertEquals("{\"old\":true}", revision.getConfigJson());
  }
}
