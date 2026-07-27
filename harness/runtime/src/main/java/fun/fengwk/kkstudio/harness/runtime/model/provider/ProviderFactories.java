package fun.fengwk.kkstudio.harness.runtime.model.provider;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 不可变 collection of {@link ProviderFactory}，按 {@link ProviderType} 索引。
 *
 * <p>同一个 {@link ProviderType} 上有两条注册时立刻以 IllegalArgumentException 失败，调用方可以信赖"未知 type、重复 type、null
 * factory、null providerType"在构造阶段就被拒绝。adapter 的 providerType 一致性只能在 {@link
 * ProviderFactory#create(String, String)} 时验证。
 */
public final class ProviderFactories {

  private final Map<ProviderType, ProviderFactory> byType;

  public ProviderFactories(Collection<? extends ProviderFactory> factories) {
    Objects.requireNonNull(factories, "factories");
    Map<ProviderType, ProviderFactory> index = new LinkedHashMap<>();
    for (ProviderFactory factory : factories) {
      Objects.requireNonNull(factory, "factory");
      ProviderType type = Objects.requireNonNull(factory.providerType(), "providerType");
      if (index.putIfAbsent(type, factory) != null) {
        throw new IllegalArgumentException("duplicate ProviderFactory for " + type);
      }
    }
    this.byType = Map.copyOf(index);
  }

  /** 缺失即返回 empty，绝不返回 null。 */
  public Optional<ProviderFactory> lookup(ProviderType type) {
    Objects.requireNonNull(type, "type");
    return Optional.ofNullable(byType.get(type));
  }
}
