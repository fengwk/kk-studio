package fun.fengwk.kkstudio.core.agent.provider.service.converter;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.provider.configuration.AgentProviderConfigurationCodec;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.ai.error.CatalogVersions;
import fun.fengwk.kkstudio.share.model.AgentProviderDTO;

/** Converts providers to credential-free public DTOs. */
@Component
public class AgentProviderConverter {

  private final AgentProviderConfigurationCodec configurationCodec;

  public AgentProviderConverter(AgentProviderConfigurationCodec configurationCodec) {
    this.configurationCodec = configurationCodec;
  }

  public AgentProviderDTO convert(AgentProvider provider) {
    if (provider == null) {
      return null;
    }
    AgentProviderDTO dto = new AgentProviderDTO();
    dto.setId(Long.toString(provider.getId()));
    dto.setName(provider.getName());
    dto.setDescription(provider.getDescription());
    dto.setProviderType(provider.getProviderType().name());
    dto.setBaseUrl(provider.getBaseUrl());
    dto.setConfigured(provider.getCredential() != null && !provider.getCredential().isBlank());
    var timeoutPolicy = configurationCodec.readTimeoutPolicy(provider.getConfigJson());
    dto.setModelCallTimeoutMillis(timeoutPolicy.modelCallTimeout().toMillis());
    dto.setModelCallIdleTimeoutMillis(timeoutPolicy.modelCallIdleTimeout().toMillis());
    dto.setVersion(CatalogVersions.format(provider.getVersion()));
    dto.setCreateTime(provider.getCreateTime());
    dto.setUpdateTime(provider.getUpdateTime());
    return dto;
  }
}
