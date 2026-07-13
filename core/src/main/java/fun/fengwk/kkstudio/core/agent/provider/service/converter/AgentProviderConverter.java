package fun.fengwk.kkstudio.core.agent.provider.service.converter;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.share.model.AgentProviderDTO;

import java.time.Duration;

/**
 * @author fengwk
 */
@Component
public class AgentProviderConverter {

  public AgentProviderDTO convert(AgentProvider provider) {
    if (provider == null) {
      return null;
    }
    AgentProviderDTO providerDTO = new AgentProviderDTO();
    providerDTO.setId(provider.getId());
    providerDTO.setName(provider.getName());
    providerDTO.setDescription(provider.getDescription());
    providerDTO.setProviderType(
        provider.getProviderType() == null ? null : provider.getProviderType().name());
    providerDTO.setBaseUrl(provider.getBaseUrl());
    providerDTO.setApiKey(provider.getApiKey());
    providerDTO.setTimeoutMillis(toMillis(provider.getTimeout()));
    providerDTO.setCreateTime(provider.getCreateTime());
    providerDTO.setUpdateTime(provider.getUpdateTime());
    return providerDTO;
  }

  private Long toMillis(Duration duration) {
    return duration == null ? null : duration.toMillis();
  }
}
