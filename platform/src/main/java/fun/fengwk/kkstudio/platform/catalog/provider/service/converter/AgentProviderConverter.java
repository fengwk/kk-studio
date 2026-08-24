package fun.fengwk.kkstudio.platform.catalog.provider.service.converter;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.catalog.provider.configuration.AgentProviderConfigurationCodec;
import fun.fengwk.kkstudio.platform.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderDTO;

/** 将 provider 转换为不含凭据的公开 DTO。 */
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
    dto.setName(provider.getName());
    dto.setDescription(provider.getDescription());
    dto.setProviderType(provider.getProviderType().wireValue());
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
