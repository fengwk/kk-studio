package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import fun.fengwk.kkstudio.agent.provider.ProviderInfo;
import fun.fengwk.kkstudio.agent.provider.ProviderRegistry;

final class SingleProviderRegistry implements ProviderRegistry {

  private final String providerName;
  private final ProviderInfo providerInfo;

  SingleProviderRegistry(String providerName, ProviderInfo providerInfo) {
    this.providerName = providerName;
    this.providerInfo = providerInfo;
  }

  @Override
  public void registerProvider(String provider, ProviderInfo providerInfo) {
    throw new UnsupportedOperationException("registerProvider is not supported");
  }

  @Override
  public ProviderInfo getProviderInfo(String provider) {
    return providerName != null && providerName.equals(provider) ? providerInfo : null;
  }
}
