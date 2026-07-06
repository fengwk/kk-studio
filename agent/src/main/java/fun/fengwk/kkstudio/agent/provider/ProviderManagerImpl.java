package fun.fengwk.kkstudio.agent.provider;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * ProviderManager 的默认实现。
 *
 * @author fengwk
 */
public class ProviderManagerImpl implements ProviderManager {

  /** provider 类型到 provider 工厂的映射表。 */
  private final Map<ProviderType, Function<ProviderInfo, Provider>> providerFactoryMap;

  /** 初始化默认 provider 工厂映射。 */
  public ProviderManagerImpl() {
    Map<ProviderType, Function<ProviderInfo, Provider>> providerFactoryMap = new HashMap<>();
    providerFactoryMap.put(ProviderType.openai, OpenAiModelProvider::new);
    providerFactoryMap.put(ProviderType.openai_response, OpenAiResponseModelProvider::new);
    providerFactoryMap.put(ProviderType.anthropic, AnthropicModelProvider::new);
    providerFactoryMap.put(ProviderType.google, GoogleModelProvider::new);
    this.providerFactoryMap = providerFactoryMap;
  }

  /** 根据 ProviderInfo 解析并创建 Provider 实例。 */
  @Override
  public Provider getProvider(ProviderInfo providerInfo) {
    if (providerInfo == null) {
      throw new IllegalArgumentException("providerInfo must not be null");
    }
    Function<ProviderInfo, Provider> factory =
        providerFactoryMap.get(providerInfo.getProviderType());
    if (factory == null) {
      throw new IllegalArgumentException(
          "Unsupported model provider type: " + providerInfo.getProviderType());
    }

    return factory.apply(providerInfo);
  }
}
