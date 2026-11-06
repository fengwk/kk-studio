package fun.fengwk.kkstudio.harness.model.provider;

import java.util.Objects;
import java.util.function.Function;

/** 按 Provider 类型从凭据创建标准适配器。 */
public interface ProviderFactory {

  ProviderType providerType();

  /**
   * 创建标准 {@link ProviderAdapter}。
   *
   * @param credential 凭据字符串；{@code null} 或空白允许 adapter 走匿名请求路径
   */
  ProviderAdapter create(String credential);

  /**
   * 构造一个直接的 {@link ProviderFactory}：基于固定的 {@link ProviderType} 与适配器构造函数。
   *
   * <p>构造函数必须返回 {@code providerType} 一致的 adapter，否则抛 IllegalArgumentException。
   */
  static ProviderFactory of(
      ProviderType providerType, Function<String, ProviderAdapter> adapterConstructor) {
    Objects.requireNonNull(providerType, "providerType");
    Objects.requireNonNull(adapterConstructor, "adapterConstructor");
    return new ProviderFactory() {
      @Override
      public ProviderType providerType() {
        return providerType;
      }

      @Override
      public ProviderAdapter create(String credential) {
        ProviderAdapter adapter = adapterConstructor.apply(credential);
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
