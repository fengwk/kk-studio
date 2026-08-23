package fun.fengwk.kkstudio.platform.catalog.provider.service.impl;

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
import org.springframework.dao.DuplicateKeyException;

import fun.fengwk.kkstudio.platform.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.provider.service.converter.AgentProviderConverter;
import fun.fengwk.kkstudio.platform.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderType;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderUpdateDTO;

/** 原子 CAS 失败必须以类型化 {@link AiVersionConflictException} 抛出。 */
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
    provider.setName("provider");
    provider.setVersion(0L);
    when(guard.requireProviderForUpdate("provider")).thenReturn(provider);
    AgentProviderCreateDTO create = new AgentProviderCreateDTO();
    create.setName("provider");
    when(factory.newProvider("provider", create)).thenReturn(provider);
    when(repository.create(provider)).thenReturn(false);
    assertThrows(IllegalStateException.class, () -> service.createProvider(create));

    // 创建直接依赖主键唯一约束：数据库重复键映射为 AiDuplicateException。
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
    AgentProviderConverter converter = mock(AgentProviderConverter.class);
    AgentProviderMutationFactory factory = mock(AgentProviderMutationFactory.class);
    AgentProviderGuard guard = mock(AgentProviderGuard.class);
    AgentProviderServiceImpl service =
        new AgentProviderServiceImpl(repository, converter, factory, guard);
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
  public void updateMutatesCurrentRowWithCas() {
    AgentProviderRepository repository = mock(AgentProviderRepository.class);
    AgentProviderConverter converter = mock(AgentProviderConverter.class);
    AgentProviderMutationFactory factory = mock(AgentProviderMutationFactory.class);
    AgentProviderGuard guard = mock(AgentProviderGuard.class);
    AgentProviderServiceImpl service =
        new AgentProviderServiceImpl(repository, converter, factory, guard);

    AgentProvider provider = new AgentProvider();
    provider.setName("provider");
    provider.setVersion(4L);
    provider.setProviderType(AgentProviderType.openai);
    provider.setBaseUrl("https://initial.example");
    provider.setCredential("initial-secret");
    provider.setConfigJson("{\"initial\":true}");
    when(guard.requireProvider("provider")).thenReturn(provider);
    when(repository.updateByName(provider, 4L)).thenReturn(true);
    when(repository.getByName("provider")).thenReturn(provider);
    AgentProviderDTO dto = new AgentProviderDTO();
    dto.setName("provider");
    when(converter.convert(provider)).thenReturn(dto);

    AgentProviderUpdateDTO update = new AgentProviderUpdateDTO();
    update.setExpectedVersion("4");
    AgentProviderDTO updated = service.updateProvider("provider", update);

    // 更新当前行并重新读取转换后的结果；不会走创建路径。
    assertEquals("provider", updated.getName());
    verify(factory).update(provider, update);
    verify(repository).updateByName(provider, 4L);
    verify(repository, never()).create(any());
  }
}
