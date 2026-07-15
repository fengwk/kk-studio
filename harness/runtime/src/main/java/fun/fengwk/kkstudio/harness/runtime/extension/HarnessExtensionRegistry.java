package fun.fengwk.kkstudio.harness.runtime.extension;

import fun.fengwk.kkstudio.harness.agent.extension.BeforeProviderRequestInterceptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.context.ContextTransform;
import fun.fengwk.kkstudio.harness.runtime.tool.AfterToolCallInterceptor;
import fun.fengwk.kkstudio.harness.runtime.tool.BeforeToolCallInterceptor;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 仅允许扩展贡献受支持的 typed contract，并在 Host 初始化后冻结。 */
public final class HarnessExtensionRegistry {

  private final List<ContextTransform> contextTransforms = new ArrayList<>();
  private final List<BeforeProviderRequestInterceptor> beforeProviderRequestInterceptors =
      new ArrayList<>();
  private final List<BeforeToolCallInterceptor> beforeToolCallInterceptors = new ArrayList<>();
  private final List<AfterToolCallInterceptor> afterToolCallInterceptors = new ArrayList<>();
  private final List<BeforeCompactionInterceptor> beforeCompactionInterceptors = new ArrayList<>();
  private final List<HarnessLifecycleObserver> lifecycleObservers = new ArrayList<>();
  private final List<ProviderFactory> providerFactories = new ArrayList<>();
  private final List<ToolFactory> toolFactories = new ArrayList<>();
  private final Map<ProviderType, ProviderFactory> providerFactoriesByType = new LinkedHashMap<>();
  private final Map<ToolKey, ToolFactory> toolFactoriesByKey = new LinkedHashMap<>();
  private final List<DisposerRegistration> disposers = new ArrayList<>();

  private boolean frozen;
  private String contributingExtensionId;

  HarnessExtensionRegistry() {}

  public void addContextTransform(ContextTransform transform) {
    add(contextTransforms, transform, "transform");
  }

  public void addBeforeProviderRequestInterceptor(BeforeProviderRequestInterceptor interceptor) {
    add(beforeProviderRequestInterceptors, interceptor, "interceptor");
  }

  public void addBeforeToolCallInterceptor(BeforeToolCallInterceptor interceptor) {
    add(beforeToolCallInterceptors, interceptor, "interceptor");
  }

  public void addAfterToolCallInterceptor(AfterToolCallInterceptor interceptor) {
    add(afterToolCallInterceptors, interceptor, "interceptor");
  }

  public void addBeforeCompactionInterceptor(BeforeCompactionInterceptor interceptor) {
    add(beforeCompactionInterceptors, interceptor, "interceptor");
  }

  public void addLifecycleObserver(HarnessLifecycleObserver observer) {
    add(lifecycleObservers, observer, "observer");
  }

  public void addProviderFactory(ProviderFactory factory) {
    requireOpen();
    Objects.requireNonNull(factory, "factory");
    ProviderType providerType = Objects.requireNonNull(factory.providerType(), "providerType");
    if (providerFactoriesByType.putIfAbsent(providerType, factory) != null) {
      throw new IllegalArgumentException("duplicate ProviderFactory for " + providerType);
    }
    providerFactories.add(factory);
  }

  public void addToolFactory(ToolFactory factory) {
    requireOpen();
    Objects.requireNonNull(factory, "factory");
    ToolDescriptor descriptor = Objects.requireNonNull(factory.descriptor(), "descriptor");
    ToolKey key = new ToolKey(descriptor.name(), descriptor.version());
    ToolFactory frozenFactory = new FrozenToolFactory(factory, descriptor);
    if (toolFactoriesByKey.putIfAbsent(key, frozenFactory) != null) {
      throw new IllegalArgumentException(
          "duplicate ToolFactory for " + descriptor.name() + "@" + descriptor.version());
    }
    toolFactories.add(frozenFactory);
  }

  public void onDispose(Runnable disposer) {
    requireOpen();
    disposers.add(
        new DisposerRegistration(
            contributingExtensionId, Objects.requireNonNull(disposer, "disposer")));
  }

  void beginContribution(String extensionId) {
    contributingExtensionId = extensionId;
  }

  void endContribution() {
    contributingExtensionId = null;
  }

  void freeze() {
    frozen = true;
  }

  List<ContextTransform> contextTransforms() {
    return List.copyOf(contextTransforms);
  }

  List<BeforeProviderRequestInterceptor> beforeProviderRequestInterceptors() {
    return List.copyOf(beforeProviderRequestInterceptors);
  }

  List<BeforeToolCallInterceptor> beforeToolCallInterceptors() {
    return List.copyOf(beforeToolCallInterceptors);
  }

  List<AfterToolCallInterceptor> afterToolCallInterceptors() {
    return List.copyOf(afterToolCallInterceptors);
  }

  List<BeforeCompactionInterceptor> beforeCompactionInterceptors() {
    return List.copyOf(beforeCompactionInterceptors);
  }

  List<HarnessLifecycleObserver> lifecycleObservers() {
    return List.copyOf(lifecycleObservers);
  }

  List<ProviderFactory> providerFactories() {
    return List.copyOf(providerFactories);
  }

  List<ToolFactory> toolFactories() {
    return List.copyOf(toolFactories);
  }

  Map<ProviderType, ProviderFactory> providerFactoriesByType() {
    return Map.copyOf(providerFactoriesByType);
  }

  Map<ToolKey, ToolFactory> toolFactoriesByKey() {
    return Map.copyOf(toolFactoriesByKey);
  }

  List<DisposerRegistration> disposers() {
    return List.copyOf(disposers);
  }

  private <T> void add(List<T> contributions, T contribution, String name) {
    requireOpen();
    contributions.add(Objects.requireNonNull(contribution, name));
  }

  private void requireOpen() {
    if (frozen) {
      throw new IllegalStateException("HarnessExtensionRegistry is frozen");
    }
  }

  record ToolKey(String name, String version) {}

  record DisposerRegistration(String extensionId, Runnable disposer) {}

  private record FrozenToolFactory(ToolFactory delegate, ToolDescriptor descriptor)
      implements ToolFactory {

    @Override
    public Tool create() {
      Tool tool = Objects.requireNonNull(delegate.create(), "created tool");
      ToolDescriptor actual = Objects.requireNonNull(tool.descriptor(), "created tool descriptor");
      if (!descriptor.name().equals(actual.name())
          || !descriptor.version().equals(actual.version())) {
        throw new IllegalStateException(
            "created tool descriptor does not match registered name and version");
      }
      return tool;
    }
  }
}
