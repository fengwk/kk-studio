package fun.fengwk.kkstudio.core.agent.provider.service.impl;

import static fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator.nextProviderId;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.agent.provider.ProviderType;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.core.agent.provider.service.converter.AgentProviderConverter;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderUpdateDTO;
import java.time.Duration;
import lombok.AllArgsConstructor;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.agent.provider.ProviderType;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.core.agent.provider.service.converter.AgentProviderConverter;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderUpdateDTO;
import java.time.Duration;
import lombok.AllArgsConstructor;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Service
public class AgentProviderServiceImpl implements AgentProviderService {

  private static final long DEFAULT_TIMEOUT_MILLIS = 60_000L;

  private final AgentProviderRepository agentProviderRepository;
  private final AgentProviderConverter agentProviderConverter;

  @Override
  public Page<AgentProviderDTO> pageProviders(PageQuery pageQuery) {
    return agentProviderRepository.page(pageQuery).map(agentProviderConverter::convert);
  }

  @Override
  public AgentProviderDTO createProvider(AgentProviderCreateDTO createDTO) {
    validateEditable(createDTO, true);
    if (agentProviderRepository.getByName(trimToNull(createDTO.getName())) != null) {
      throw new IllegalArgumentException(
          "agent provider name already exists: " + createDTO.getName());
    }
    AgentProvider provider = toProvider(createDTO);
    long nid = nextProviderId();
    LoggerFactory.getLogger(getClass()).info("nextProviderId={}", nid);
    provider.setId(nid);
    if (!agentProviderRepository.create(provider)) {
      throw new IllegalStateException("create agent provider failed");
    }
    return agentProviderConverter.convert(agentProviderRepository.getById(provider.getId()));
  }

  @Override
  public AgentProviderDTO updateProvider(long id, AgentProviderUpdateDTO updateDTO) {
    if (id <= 0) {
      throw new IllegalArgumentException("agent provider id must be positive");
    }
    validateEditable(updateDTO, false);
    AgentProvider existing = agentProviderRepository.getById(id);
    if (existing == null) {
      throw new IllegalArgumentException("agent provider not found: " + id);
    }
    String newName =
        updateDTO.getName() == null || updateDTO.getName().isBlank()
            ? existing.getName()
            : updateDTO.getName();
    if (!existing.getName().equals(newName) && agentProviderRepository.getByName(newName) != null) {
      throw new IllegalArgumentException("agent provider name already exists: " + newName);
    }
    existing.setName(newName);
    existing.setDescription(trimToNull(updateDTO.getDescription()));
    existing.setProviderType(toProviderType(updateDTO.getProviderType()));
    existing.setBaseUrl(trimToNull(updateDTO.getBaseUrl()));
    existing.setApiKey(trimToNull(updateDTO.getApiKey()));
    existing.setTimeout(toDuration(updateDTO.getTimeoutMillis()));
    existing.setStreamIdleTimeout(toDuration(updateDTO.getStreamIdleTimeoutMillis()));
    if (!agentProviderRepository.updateById(existing)) {
      throw new IllegalStateException("update agent provider failed: " + id);
    }
    return agentProviderConverter.convert(agentProviderRepository.getById(id));
  }

  @Override
  public void deleteProvider(long id) {
    if (id <= 0) {
      throw new IllegalArgumentException("agent provider id must be positive");
    }
    if (agentProviderRepository.getById(id) == null) {
      throw new IllegalArgumentException("agent provider not found: " + id);
    }
    if (agentProviderRepository.hasModels(id)) {
      throw new IllegalStateException("agent provider in use by models: " + id);
    }
    if (agentProviderRepository.hasAgents(id)) {
      throw new IllegalStateException("agent provider in use by agents: " + id);
    }
    if (!agentProviderRepository.deleteById(id)) {
      throw new IllegalStateException("delete agent provider failed: " + id);
    }
  }

  private void validateEditable(
      AgentProviderEditablePropertiesDTO properties, boolean requireName) {
    if (properties == null) {
      throw new IllegalArgumentException("agent provider body must not be null");
    }
    if (requireName && (properties.getName() == null || properties.getName().isBlank())) {
      throw new IllegalArgumentException("agent provider name must not be blank");
    }
    if (properties.getProviderType() == null || properties.getProviderType().isBlank()) {
      throw new IllegalArgumentException("agent provider providerType must not be null");
    }
    toProviderType(properties.getProviderType());
  }

  private AgentProvider toProvider(AgentProviderEditablePropertiesDTO properties) {
    AgentProvider provider = new AgentProvider();
    provider.setName(trimToNull(properties.getName()));
    provider.setDescription(trimToNull(properties.getDescription()));
    provider.setProviderType(toProviderType(properties.getProviderType()));
    provider.setBaseUrl(trimToNull(properties.getBaseUrl()));
    provider.setApiKey(trimToNull(properties.getApiKey()));
    provider.setTimeout(toDuration(properties.getTimeoutMillis()));
    provider.setStreamIdleTimeout(toDuration(properties.getStreamIdleTimeoutMillis()));
    return provider;
  }

  private Duration toDuration(Long millis) {
    long value = millis == null ? DEFAULT_TIMEOUT_MILLIS : millis;
    if (value <= 0) {
      throw new IllegalArgumentException("timeout millis must be positive");
    }
    return Duration.ofMillis(value);
  }

  private ProviderType toProviderType(String providerType) {
    try {
      return ProviderType.valueOf(providerType.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("unsupported providerType: " + providerType, e);
    }
  }

  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
