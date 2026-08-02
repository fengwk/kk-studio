package fun.fengwk.kkstudio.core.ai.catalog.provider.service.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import lombok.AllArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.ai.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.ai.catalog.provider.repo.AgentProviderRevisionRepository;
import fun.fengwk.kkstudio.core.ai.catalog.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.core.ai.catalog.provider.service.converter.AgentProviderConverter;
import fun.fengwk.kkstudio.core.ai.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.ai.catalog.provider.service.model.AgentProviderRevision;
import fun.fengwk.kkstudio.core.ai.error.AiDuplicateException;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.ai.error.AiVersionConflictException;
import fun.fengwk.kkstudio.core.ai.error.CatalogVersions;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderUpdateDTO;

/** Global provider CRUD. */
@AllArgsConstructor
@Service
public class AgentProviderServiceImpl implements AgentProviderService {

  private static final String RESOURCE = "agent_provider";

  private final AgentProviderRepository agentProviderRepository;
  private final AgentProviderRevisionRepository agentProviderRevisionRepository;
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
    String name = createDTO == null ? null : createDTO.getName();
    AgentProvider provider = providerMutationFactory.newProvider(name, createDTO);
    providerGuard.ensureNameAvailable(provider.getName());
    try {
      if (!agentProviderRepository.create(provider)) {
        throw new IllegalStateException("create agent provider failed");
      }
      if (!agentProviderRevisionRepository.create(newRevision(provider, 0L))) {
        throw new IllegalStateException("create agent provider revision failed");
      }
    } catch (DuplicateKeyException error) {
      throw new AiDuplicateException(
          RESOURCE, "agent provider name already exists: " + provider.getName(), error);
    }
    AgentProvider loaded = agentProviderRepository.getByName(provider.getName());
    return agentProviderConverter.convert(loaded);
  }

  @Override
  @Transactional
  public AgentProviderDTO updateProvider(String name, AgentProviderUpdateDTO updateDTO) {
    String rawExpected = updateDTO == null ? null : updateDTO.getExpectedVersion();
    if (rawExpected == null) {
      throw new AiValidationException(RESOURCE, "expectedVersion is required");
    }
    long expected = CatalogVersions.parse(rawExpected, "expectedVersion");
    AgentProvider provider = providerGuard.requireProvider(name);
    ensureExpectedVersion(provider, name, rawExpected, expected);
    providerMutationFactory.update(provider, updateDTO);
    if (!agentProviderRepository.updateByName(provider, expected)) {
      AgentProvider reread = agentProviderRepository.getByName(name);
      if (reread == null) {
        throw new AiResourceNotFoundException(RESOURCE, RESOURCE + " not found: " + name);
      }
      throw new AiVersionConflictException(
          RESOURCE, name, rawExpected, CatalogVersions.format(reread.getVersion()));
    }
    if (!agentProviderRevisionRepository.create(newRevision(provider, expected + 1))) {
      throw new IllegalStateException("create agent provider revision failed");
    }
    AgentProvider reloaded = agentProviderRepository.getByName(name);
    return agentProviderConverter.convert(reloaded);
  }

  @Override
  @Transactional
  public void deleteProvider(String name, String expectedVersion) {
    long expected = CatalogVersions.parse(expectedVersion, "expectedVersion");
    AgentProvider provider = providerGuard.requireProviderForUpdate(name);
    ensureExpectedVersion(provider, name, expectedVersion, expected);
    providerGuard.ensureDeletable(name);
    if (!agentProviderRepository.deleteByName(name, expected)) {
      AgentProvider reread = agentProviderRepository.getByName(name);
      if (reread == null) {
        throw new AiResourceNotFoundException(RESOURCE, RESOURCE + " not found: " + name);
      }
      throw new AiVersionConflictException(
          RESOURCE, name, expectedVersion, CatalogVersions.format(reread.getVersion()));
    }
  }

  private static void ensureExpectedVersion(
      AgentProvider provider, String name, String expectedVersion, long expected) {
    if (provider.getVersion() != expected) {
      throw new AiVersionConflictException(
          RESOURCE, name, expectedVersion, CatalogVersions.format(provider.getVersion()));
    }
  }

  private static AgentProviderRevision newRevision(AgentProvider provider, long providerVersion) {
    AgentProviderRevision revision = new AgentProviderRevision();
    revision.setProviderName(provider.getName());
    revision.setProviderVersion(providerVersion);
    revision.setProviderType(provider.getProviderType());
    revision.setBaseUrl(provider.getBaseUrl());
    revision.setCredential(provider.getCredential());
    revision.setConfigJson(provider.getConfigJson());
    return revision;
  }
}
