package fun.fengwk.kkstudio.agent.provider;

/**
 * ProviderManager 负责按 provider 信息创建 provider 实例。
 *
 * <p>语义说明： - ProviderRegistry 负责按名称解析 ProviderInfo。 - ProviderManager 负责根据 ProviderInfo 创建实际
 * Provider 实例。
 *
 * @author fengwk
 */
public interface ProviderManager {

  /** 根据 ProviderInfo 创建对应的 Provider 实例。 */
  Provider getProvider(ProviderInfo providerInfo);
}
