package fun.fengwk.kkstudio.agent.provider;

/**
 * ProviderRegistry 负责 provider 信息的注册与查询。
 *
 * <p>语义说明： - set_model_info 事件中只保存 provider 名称。 - registry 根据名称返回对应的 ProviderInfo。 -
 * ProviderManager 再根据 ProviderInfo 创建实际 Provider 实例。
 *
 * @author fengwk
 */
public interface ProviderRegistry {

  /**
   * 注册 provider 信息。
   *
   * @param provider provider 名称
   * @param providerInfo provider 信息
   */
  void registerProvider(String provider, ProviderInfo providerInfo);

  /**
   * 按名称解析 provider 信息。
   *
   * @param provider provider 名称
   * @return 对应的 provider 信息
   */
  ProviderInfo getProviderInfo(String provider);
}
