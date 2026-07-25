package fun.fengwk.kkstudio.harness.runtime.extension;

import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtensionException.Phase;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtensionRegistry.DisposerRegistration;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtensionRegistry.ToolKey;
import fun.fengwk.kkstudio.harness.runtime.tool.AfterToolCallInterceptor;
import fun.fengwk.kkstudio.harness.runtime.tool.BeforeToolCallInterceptor;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** 校验、排序并冻结显式提供的 Harness 扩展贡献。 */
public final class HarnessExtensionHost implements AutoCloseable {

  private final List<BeforeToolCallInterceptor> beforeToolCallInterceptors;
  private final List<AfterToolCallInterceptor> afterToolCallInterceptors;
  private final List<HarnessLifecycleObserver> lifecycleObservers;
  private final List<ProviderFactory> providerFactories;
  private final List<ToolFactory> toolFactories;
  private final Map<ProviderType, ProviderFactory> providerFactoriesByType;
  private final Map<ToolKey, ToolFactory> toolFactoriesByKey;
  private final List<DisposerRegistration> disposers;
  private final AtomicBoolean closed = new AtomicBoolean();

  public static HarnessExtensionHost load(List<HarnessExtension> extensions) {
    return new HarnessExtensionHost(extensions);
  }

  public HarnessExtensionHost(List<HarnessExtension> extensions) {
    List<ExtensionRegistration> registrations = validateAndSort(extensions);
    HarnessExtensionRegistry registry = new HarnessExtensionRegistry();
    for (ExtensionRegistration registration : registrations) {
      registry.beginContribution(registration.id());
      try {
        registration.extension().contribute(registry);
      } catch (RuntimeException error) {
        registry.freeze();
        HarnessExtensionException failure =
            new HarnessExtensionException(registration.id(), Phase.CONTRIBUTE, error);
        HarnessExtensionException disposalFailure = dispose(registry.disposers());
        if (disposalFailure != null) {
          failure.addSuppressed(disposalFailure);
        }
        throw failure;
      } finally {
        registry.endContribution();
      }
    }
    registry.freeze();

    beforeToolCallInterceptors = registry.beforeToolCallInterceptors();
    afterToolCallInterceptors = registry.afterToolCallInterceptors();
    lifecycleObservers = registry.lifecycleObservers();
    providerFactories = registry.providerFactories();
    toolFactories = registry.toolFactories();
    providerFactoriesByType = registry.providerFactoriesByType();
    toolFactoriesByKey = registry.toolFactoriesByKey();
    disposers = registry.disposers();
  }

  public List<BeforeToolCallInterceptor> beforeToolCallInterceptors() {
    return beforeToolCallInterceptors;
  }

  public List<AfterToolCallInterceptor> afterToolCallInterceptors() {
    return afterToolCallInterceptors;
  }

  public List<HarnessLifecycleObserver> lifecycleObservers() {
    return lifecycleObservers;
  }

  public List<ProviderFactory> providerFactories() {
    return providerFactories;
  }

  public List<ToolFactory> toolFactories() {
    return toolFactories;
  }

  public Optional<ProviderFactory> providerFactory(ProviderType providerType) {
    return Optional.ofNullable(
        providerFactoriesByType.get(Objects.requireNonNull(providerType, "providerType")));
  }

  public Optional<ToolFactory> toolFactory(String name, String version) {
    return Optional.ofNullable(toolFactoryByKey(name, version));
  }

  public Optional<Tool> createTool(String name, String version) {
    ToolFactory factory = toolFactoryByKey(name, version);
    if (factory == null) {
      return Optional.empty();
    }
    return Optional.of(factory.create());
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    HarnessExtensionException failure = dispose(disposers);
    if (failure != null) {
      throw failure;
    }
  }

  private ToolFactory toolFactoryByKey(String name, String version) {
    return toolFactoriesByKey.get(
        new ToolKey(
            Objects.requireNonNull(name, "name"), Objects.requireNonNull(version, "version")));
  }

  private static List<ExtensionRegistration> validateAndSort(List<HarnessExtension> extensions) {
    Objects.requireNonNull(extensions, "extensions");
    List<ExtensionRegistration> registrations = new ArrayList<>(extensions.size());
    Set<String> ids = new HashSet<>();
    for (HarnessExtension extension : extensions) {
      Objects.requireNonNull(extension, "extension");
      String id = extension.id();
      if (id == null || id.isBlank()) {
        throw new IllegalArgumentException("extension id must not be blank");
      }
      if (!ids.add(id)) {
        throw new IllegalArgumentException("duplicate extension id: " + id);
      }
      registrations.add(new ExtensionRegistration(extension, id, extension.priority()));
    }
    registrations.sort(
        Comparator.comparingInt(ExtensionRegistration::priority)
            .thenComparing(ExtensionRegistration::id));
    return List.copyOf(registrations);
  }

  private static HarnessExtensionException dispose(List<DisposerRegistration> disposers) {
    HarnessExtensionException firstFailure = null;
    for (int index = disposers.size() - 1; index >= 0; index--) {
      DisposerRegistration registration = disposers.get(index);
      try {
        registration.disposer().run();
      } catch (RuntimeException error) {
        HarnessExtensionException failure =
            new HarnessExtensionException(registration.extensionId(), Phase.DISPOSE, error);
        if (firstFailure == null) {
          firstFailure = failure;
        } else {
          firstFailure.addSuppressed(failure);
        }
      }
    }
    return firstFailure;
  }

  private record ExtensionRegistration(HarnessExtension extension, String id, int priority) {}
}
