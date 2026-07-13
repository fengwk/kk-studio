package fun.fengwk.kkstudio.core.agent.runtime.provider;

import lombok.AllArgsConstructor;

import fun.fengwk.kkstudio.agent.provider.ProviderInfo;
import fun.fengwk.kkstudio.core.agent.runtime.configuration.AgentRuntimeProperties;

/**
 * @author fengwk
 */
@AllArgsConstructor
public class ConfiguredAgentProviderInfoResolver implements AgentProviderInfoResolver {

  private final AgentRuntimeProperties properties;

  @Override
  public ProviderInfo resolve(String provider) {
    if (provider == null || provider.isBlank()) {
      return null;
    }
    AgentRuntimeProperties.ProviderProperties providerProperties =
        properties.getProviders().get(provider);
    if (providerProperties == null || providerProperties.getProviderType() == null) {
      return null;
    }
    return ProviderInfo.builder()
        .providerType(providerProperties.getProviderType())
        .baseUrl(providerProperties.getBaseUrl())
        .apiKey(providerProperties.getApiKey())
        .timeout(providerProperties.getTimeout())
        .build();
  }
}
