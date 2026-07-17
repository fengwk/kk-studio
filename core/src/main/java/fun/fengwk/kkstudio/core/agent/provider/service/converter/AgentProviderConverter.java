package fun.fengwk.kkstudio.core.agent.provider.service.converter;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.share.model.AgentProviderDTO;

/** Converts providers to credential-free public DTOs. */
@Component
public class AgentProviderConverter {

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
    dto.setConfigJson(provider.getConfigJson());
    dto.setVersion(provider.getVersion());
    dto.setCreateTime(provider.getCreateTime());
    dto.setUpdateTime(provider.getUpdateTime());
    return dto;
  }
}
