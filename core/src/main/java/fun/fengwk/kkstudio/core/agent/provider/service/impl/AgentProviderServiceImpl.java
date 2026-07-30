package fun.fengwk.kkstudio.core.agent.provider.service.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import lombok.AllArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.core.agent.provider.service.converter.AgentProviderConverter;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.ai.error.AiDuplicateException;
import fun.fengwk.kkstudio.core.ai.error.AiInUseException;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.ai.error.AiVersionConflictException;
import fun.fengwk.kkstudio.core.ai.error.CatalogVersions;
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderUpdateDTO;

/** Global provider CRUD. */
@AllArgsConstructor
@Service
public class AgentProviderServiceImpl implements AgentProviderService {

  private static final String RESOURCE = "agent_provider";

  private final AgentProviderRepository agentProviderRepository;
  private final AgentProviderConverter agentProviderConverter;
  private final AgentProviderMutationFactory providerMutationFactory;
  private final AgentProviderGuard providerGuard;

  @Override
  public Page<AgentProviderDTO> pageProviders(PageQuery pageQuery) {
    return agentProviderRepository.page(pageQuery).map(agentProviderConverter::convert);
  }

  @Override
  @Transactional
  public AgentProviderDTO createProvider(AgentProviderCreateDTO createDTO) {
    AgentProvider provider = providerMutationFactory.newProvider(createDTO);
    providerGuard.ensureNameAvailable(provider.getName());
    try {
      if (!agentProviderRepository.create(provider)) {
        throw new IllegalStateException("create agent provider failed");
      }
    } catch (DuplicateKeyException error) {
      throw new AiDuplicateException(
          RESOURCE, "agent provider name already exists: " + provider.getName(), error);
    }
    AgentProvider loaded = agentProviderRepository.getById(provider.getId());
    return agentProviderConverter.convert(loaded);
  }

  @Override
  @Transactional
  public AgentProviderDTO updateProvider(long id, AgentProviderUpdateDTO updateDTO) {
    String rawExpected = updateDTO == null ? null : updateDTO.getExpectedVersion();
    if (rawExpected == null) {
      throw new AiValidationException(RESOURCE, "expectedVersion is required");
    }
    long expected = CatalogVersions.parse(rawExpected, "expectedVersion");
    AgentProvider provider = providerGuard.requireProvider(id);
    ensureExpectedVersion(provider, id, rawExpected, expected);
    String currentName = provider.getName();
    providerMutationFactory.update(provider, updateDTO);
    providerGuard.ensureNameAvailable(currentName, provider.getName());
    try {
      if (!agentProviderRepository.updateById(provider, expected)) {
        AgentProvider reread = agentProviderRepository.getById(id);
        if (reread == null) {
          throw new AiResourceNotFoundException(RESOURCE, RESOURCE + " not found: " + id);
        }
        throw new AiVersionConflictException(
            RESOURCE, Long.toString(id), rawExpected, CatalogVersions.format(reread.getVersion()));
      }
    } catch (DuplicateKeyException error) {
      throw new AiDuplicateException(
          RESOURCE, "agent provider name already exists: " + provider.getName(), error);
    }
    AgentProvider reloaded = agentProviderRepository.getById(id);
    return agentProviderConverter.convert(reloaded);
  }

  @Override
  @Transactional
  public void deleteProvider(long id, String expectedVersion) {
    long expected = CatalogVersions.parse(expectedVersion, "expectedVersion");
    AgentProvider provider = providerGuard.requireProvider(id);
    ensureExpectedVersion(provider, id, expectedVersion, expected);
    providerGuard.ensureDeletable(id);
    try {
      if (!agentProviderRepository.deleteById(id, expected)) {
        AgentProvider reread = agentProviderRepository.getById(id);
        if (reread == null) {
          throw new AiResourceNotFoundException(RESOURCE, RESOURCE + " not found: " + id);
        }
        throw new AiVersionConflictException(
            RESOURCE,
            Long.toString(id),
            expectedVersion,
            CatalogVersions.format(reread.getVersion()));
      }
    } catch (DuplicateKeyException error) {
      throw new AiInUseException(RESOURCE, RESOURCE + " in use by models: " + id);
    } catch (DataIntegrityViolationException error) {
      throw new AiInUseException(RESOURCE, RESOURCE + " in use by models: " + id);
    }
  }

  private static void ensureExpectedVersion(
      AgentProvider provider, long id, String expectedVersion, long expected) {
    if (provider.getVersion() != expected) {
      throw new AiVersionConflictException(
          RESOURCE,
          Long.toString(id),
          expectedVersion,
          CatalogVersions.format(provider.getVersion()));
    }
  }
}
