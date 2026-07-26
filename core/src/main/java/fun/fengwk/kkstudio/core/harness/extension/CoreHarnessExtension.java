package fun.fengwk.kkstudio.core.harness.extension;

import fun.fengwk.kkstudio.core.harness.model.provider.AnthropicProviderAdapter;
import fun.fengwk.kkstudio.core.harness.model.provider.GoogleProviderAdapter;
import fun.fengwk.kkstudio.core.harness.model.provider.OpenAiProviderAdapter;
import fun.fengwk.kkstudio.core.harness.model.provider.OpenAiResponsesProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtension;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtensionRegistry;
import fun.fengwk.kkstudio.harness.runtime.extension.ProviderFactory;
import fun.fengwk.kkstudio.harness.runtime.extension.ToolFactory;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionEvaluator;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Core 内置的可信 Harness 扩展，汇总权限边界、Provider adapter 与 Spring 工具。 */
public final class CoreHarnessExtension implements HarnessExtension {
  private static final String ID = "core.builtin";

  private final PermissionEvaluator permissionEvaluator;
  private final List<Tool> tools;

  public CoreHarnessExtension(PermissionEvaluator permissionEvaluator, List<Tool> tools) {
    this.permissionEvaluator = Objects.requireNonNull(permissionEvaluator, "permissionEvaluator");
    this.tools = snapshotTools(tools);
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public int priority() {
    return 0;
  }

  @Override
  public void contribute(HarnessExtensionRegistry registry) {
    Objects.requireNonNull(registry, "registry");
    // The registry keeps this first; ToolInterceptorChain moves the sole boundary to the end.
    registry.addBeforeToolCallInterceptor(permissionEvaluator);
    for (ProviderFactoryDefinition definition : providerFactoryDefinitions()) {
      registry.addProviderFactory(definition.factory());
    }
    for (Tool tool : tools) {
      registry.addToolFactory(ToolFactory.singleton(tool));
    }
  }

  private static List<Tool> snapshotTools(List<Tool> tools) {
    List<Tool> snapshot = new ArrayList<>(Objects.requireNonNull(tools, "tools"));
    snapshot.sort(
        Comparator.comparing(
                CoreHarnessExtension::toolName, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(
                CoreHarnessExtension::toolVersion,
                Comparator.nullsFirst(Comparator.naturalOrder())));
    return Collections.unmodifiableList(snapshot);
  }

  private static String toolName(Tool tool) {
    return tool == null || tool.descriptor() == null ? null : tool.descriptor().name();
  }

  private static String toolVersion(Tool tool) {
    return tool == null || tool.descriptor() == null ? null : tool.descriptor().version();
  }

  private static List<ProviderFactoryDefinition> providerFactoryDefinitions() {
    return List.of(
        new ProviderFactoryDefinition(
            ProviderType.OPENAI,
            OpenAiProviderAdapter::new,
            PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT))),
        new ProviderFactoryDefinition(
            ProviderType.OPENAI_RESPONSES,
            OpenAiResponsesProviderAdapter::new,
            PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT))),
        new ProviderFactoryDefinition(
            ProviderType.ANTHROPIC,
            AnthropicProviderAdapter::new,
            PromptCacheCapability.breakpoints(
                Set.of(PromptCacheRetention.SHORT),
                EnumSet.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS))),
        new ProviderFactoryDefinition(
            ProviderType.GOOGLE, GoogleProviderAdapter::new, PromptCacheCapability.automatic()));
  }

  static ProviderFactory providerFactory(
      ProviderType providerType,
      Function<String, ProviderAdapter> constructor,
      PromptCacheCapability promptCacheCapability) {
    Objects.requireNonNull(providerType, "providerType");
    Objects.requireNonNull(constructor, "constructor");
    Objects.requireNonNull(promptCacheCapability, "promptCacheCapability");
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
        ProviderAdapter adapter =
            Objects.requireNonNull(constructor.apply(credential), "provider adapter");
        if (adapter.providerType() != providerType) {
          throw new IllegalStateException(
              "ProviderFactory result does not match registered provider type " + providerType);
        }
        return adapter;
      }
    };
  }

  private record ProviderFactoryDefinition(
      ProviderType providerType,
      Function<String, ProviderAdapter> constructor,
      PromptCacheCapability promptCacheCapability) {

    private ProviderFactoryDefinition {
      Objects.requireNonNull(providerType, "providerType");
      Objects.requireNonNull(constructor, "constructor");
      Objects.requireNonNull(promptCacheCapability, "promptCacheCapability");
    }

    private ProviderFactory factory() {
      return providerFactory(providerType, constructor, promptCacheCapability);
    }
  }
}
