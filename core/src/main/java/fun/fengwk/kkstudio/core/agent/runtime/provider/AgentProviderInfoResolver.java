package fun.fengwk.kkstudio.core.agent.runtime.provider;

import fun.fengwk.kkstudio.agent.provider.ProviderInfo;

import fun.fengwk.kkstudio.agent.provider.ProviderInfo;

/**
 * @author fengwk
 */
public interface AgentProviderInfoResolver {

  ProviderInfo resolve(String provider);
}
