package fun.fengwk.kkstudio.harness.runtime.model.provider;

import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/** 按 Provider 类型从凭据和配置创建标准适配器。 */
public interface ProviderFactory {

  ProviderType providerType();

  /** Provider 暴露给资源解析器的可信提示缓存能力快照，用于构造模型请求策略。 */
  PromptCacheCapability promptCacheCapability();

  /**
   * 基于 Provider 配置 JSON 动态解析提示缓存能力，默认委托无参方法。
   *
   * @param configJson Provider 持久化配置 JSON
   * @return 对应的 PromptCacheCapability
   */
  default PromptCacheCapability promptCacheCapability(String configJson) {
    return promptCacheCapability();
  }

  /**
   * 创建标准 {@link ProviderAdapter}。
   *
   * @param credential 凭据字符串；{@code null} 或空白允许 adapter 走匿名请求路径
   * @param configJson Provider 持久化配置 JSON；允许空串
   */
  ProviderAdapter create(String credential, String configJson);

  /**
   * 构造一个直接的 {@link ProviderFactory}：基于固定的 {@link ProviderType}、缓存能力快照，以及一个适配器构造函数。
   *
   * <p>构造函数必须返回 {@code providerType} 一致的 adapter，否则抛 IllegalArgumentException。
   */
  static ProviderFactory of(
      ProviderType providerType,
      PromptCacheCapability promptCacheCapability,
      BiFunction<String, String, ProviderAdapter> adapterConstructor) {
    Objects.requireNonNull(providerType, "providerType");
    Objects.requireNonNull(promptCacheCapability, "promptCacheCapability");
    Objects.requireNonNull(adapterConstructor, "adapterConstructor");
    return new ProviderFactory() {
      @Override
      public ProviderType providerType() {
        return providerType;
      }

      @Override
      public PromptCacheCapability promptCacheCapability() {
        return promptCacheCapability;
      }

      @Override
      public ProviderAdapter create(String credential, String configJson) {
        ProviderAdapter adapter = adapterConstructor.apply(credential, configJson);
        Objects.requireNonNull(adapter, "adapter");
        if (adapter.providerType() != providerType) {
          throw new IllegalArgumentException(
              "adapter reports " + adapter.providerType() + " but factory is " + providerType);
        }
        return adapter;
      }
    };
  }

  /**
   * 构造一个基于 Provider 配置动态解析提示缓存能力的 {@link ProviderFactory}。
   *
   * <p>无参 capability 对动态 factory 等价于空配置默认能力。
   *
   * <p>构造函数必须返回 {@code providerType} 一致的 adapter，否则抛 IllegalArgumentException。
   */
  static ProviderFactory of(
      ProviderType providerType,
      Function<String, PromptCacheCapability> promptCacheCapabilityResolver,
      BiFunction<String, String, ProviderAdapter> adapterConstructor) {
    Objects.requireNonNull(providerType, "providerType");
    Objects.requireNonNull(promptCacheCapabilityResolver, "promptCacheCapabilityResolver");
    Objects.requireNonNull(adapterConstructor, "adapterConstructor");
    return new ProviderFactory() {
      @Override
      public ProviderType providerType() {
        return providerType;
      }

      @Override
      public PromptCacheCapability promptCacheCapability() {
        return promptCacheCapability(null);
      }

      @Override
      public PromptCacheCapability promptCacheCapability(String configJson) {
        PromptCacheCapability capability = promptCacheCapabilityResolver.apply(configJson);
        return Objects.requireNonNull(capability, "promptCacheCapability");
      }

      @Override
      public ProviderAdapter create(String credential, String configJson) {
        ProviderAdapter adapter = adapterConstructor.apply(credential, configJson);
        Objects.requireNonNull(adapter, "adapter");
        if (adapter.providerType() != providerType) {
          throw new IllegalArgumentException(
              "adapter reports " + adapter.providerType() + " but factory is " + providerType);
        }
        return adapter;
      }
    };
  }
}
