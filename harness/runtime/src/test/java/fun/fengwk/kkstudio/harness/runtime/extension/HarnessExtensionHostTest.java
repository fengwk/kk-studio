package fun.fengwk.kkstudio.harness.runtime.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.harness.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.model.provider.adapter.ProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.context.ContextTransform;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtensionException.Phase;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class HarnessExtensionHostTest {

  /** Host 先按 priority/id 排扩展，并保留同一扩展内的贡献顺序。 */
  @Test
  void ordersExtensionsAndContributionsDeterministically() {
    ContextTransform betaFirst = state -> state;
    ContextTransform betaSecond = state -> state;
    ContextTransform alpha = state -> state;
    ContextTransform early = state -> state;
    ProviderFactory betaProvider = providerFactory(ProviderType.OPENAI);
    ProviderFactory earlyProvider = providerFactory(ProviderType.GOOGLE);
    ToolFactory betaTool = toolFactory(tool("beta", "1"));
    ToolFactory earlyTool = toolFactory(tool("early", "1"));

    HarnessExtensionHost host =
        HarnessExtensionHost.load(
            List.of(
                extension(
                    "beta",
                    10,
                    registry -> {
                      registry.addContextTransform(betaFirst);
                      registry.addContextTransform(betaSecond);
                      registry.addProviderFactory(betaProvider);
                      registry.addToolFactory(betaTool);
                    }),
                extension("alpha", 10, registry -> registry.addContextTransform(alpha)),
                extension(
                    "early",
                    -1,
                    registry -> {
                      registry.addContextTransform(early);
                      registry.addProviderFactory(earlyProvider);
                      registry.addToolFactory(earlyTool);
                    })));

    assertEquals(List.of(early, alpha, betaFirst, betaSecond), host.contextTransforms());
    assertEquals(List.of(earlyProvider, betaProvider), host.providerFactories());
    assertEquals(
        List.of("early", "beta"),
        host.toolFactories().stream().map(factory -> factory.descriptor().name()).toList());
  }

  /** extension 列表在任何 contribute 前完成 null、blank 与 duplicate id 校验。 */
  @Test
  void validatesExtensionsBeforeContribution() {
    List<String> calls = new ArrayList<>();
    HarnessExtension duplicateFirst = extension("same", 0, registry -> calls.add("called"));
    HarnessExtension duplicateSecond = extension("same", 1, registry -> calls.add("called"));

    assertThrows(
        IllegalArgumentException.class,
        () -> new HarnessExtensionHost(List.of(duplicateFirst, duplicateSecond)));
    assertTrue(calls.isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () -> new HarnessExtensionHost(List.of(extension(" ", 0, registry -> {}))));
    assertThrows(NullPointerException.class, () -> new HarnessExtensionHost(null));
    assertThrows(
        NullPointerException.class,
        () -> new HarnessExtensionHost(Arrays.asList(duplicateFirst, null)));
  }

  /** ProviderType 和 tool name/version 是全 Host 唯一 factory key。 */
  @Test
  void rejectsDuplicateProviderAndToolFactories() {
    HarnessExtensionException providerFailure =
        assertThrows(
            HarnessExtensionException.class,
            () ->
                new HarnessExtensionHost(
                    List.of(
                        extension(
                            "provider",
                            0,
                            registry -> {
                              registry.addProviderFactory(providerFactory(ProviderType.OPENAI));
                              registry.addProviderFactory(providerFactory(ProviderType.OPENAI));
                            }))));
    assertEquals("provider", providerFailure.extensionId());
    assertEquals(Phase.CONTRIBUTE, providerFailure.phase());
    assertTrue(providerFailure.getCause().getMessage().contains("duplicate ProviderFactory"));

    HarnessExtensionException toolFailure =
        assertThrows(
            HarnessExtensionException.class,
            () ->
                new HarnessExtensionHost(
                    List.of(
                        extension(
                            "tool",
                            0,
                            registry -> {
                              registry.addToolFactory(toolFactory(tool("write", "1")));
                              registry.addToolFactory(toolFactory(tool("write", "1")));
                            }))));
    assertTrue(toolFailure.getCause().getMessage().contains("duplicate ToolFactory"));
  }

  /** 所有 registry 入口拒绝 null，contribute 返回后保留的 registry 已被冻结。 */
  @Test
  void rejectsNullAndLateContributions() {
    List<Consumer<HarnessExtensionRegistry>> nullRegistrations =
        List.of(
            registry -> registry.addContextTransform(null),
            registry -> registry.addBeforeProviderRequestInterceptor(null),
            registry -> registry.addBeforeToolCallInterceptor(null),
            registry -> registry.addAfterToolCallInterceptor(null),
            registry -> registry.addBeforeCompactionInterceptor(null),
            registry -> registry.addLifecycleObserver(null),
            registry -> registry.addProviderFactory(null),
            registry -> registry.addToolFactory(null),
            registry -> registry.onDispose(null));
    for (int index = 0; index < nullRegistrations.size(); index++) {
      int registrationIndex = index;
      Consumer<HarnessExtensionRegistry> registration = nullRegistrations.get(index);
      HarnessExtensionException failure =
          assertThrows(
              HarnessExtensionException.class,
              () ->
                  new HarnessExtensionHost(
                      List.of(extension("null-" + registrationIndex, 0, registration))));
      assertTrue(failure.getCause() instanceof NullPointerException);
    }

    AtomicReference<HarnessExtensionRegistry> retained = new AtomicReference<>();
    HarnessExtensionHost host =
        new HarnessExtensionHost(
            List.of(extension("retained", 0, registry -> retained.set(registry))));
    assertThrows(
        IllegalStateException.class, () -> retained.get().addContextTransform(state -> state));
    host.close();
  }

  /** Host 暴露全部 typed contribution 的不可变快照。 */
  @Test
  void exposesImmutableContributionViews() {
    HarnessExtensionHost host =
        new HarnessExtensionHost(
            List.of(
                extension(
                    "all",
                    0,
                    registry -> {
                      registry.addContextTransform(state -> state);
                      registry.addBeforeProviderRequestInterceptor(request -> request);
                      registry.addBeforeToolCallInterceptor(context -> null);
                      registry.addAfterToolCallInterceptor(context -> context.result());
                      registry.addBeforeCompactionInterceptor(context -> context.context());
                      registry.addLifecycleObserver(observation -> {});
                      registry.addProviderFactory(providerFactory(ProviderType.ANTHROPIC));
                      registry.addToolFactory(toolFactory(tool("all", "1")));
                    })));

    assertImmutable(host.contextTransforms());
    assertImmutable(host.beforeProviderRequestInterceptors());
    assertImmutable(host.beforeToolCallInterceptors());
    assertImmutable(host.afterToolCallInterceptors());
    assertImmutable(host.beforeCompactionInterceptors());
    assertImmutable(host.lifecycleObservers());
    assertImmutable(host.providerFactories());
    assertImmutable(host.toolFactories());
  }

  /** Factory lookup 返回 Optional，tool 创建会校验注册时冻结的 name/version。 */
  @Test
  void looksUpFactoriesAndValidatesCreatedToolDescriptor() {
    ProviderFactory providerFactory = providerFactory(ProviderType.GOOGLE);
    Tool expected = tool("read", "1");
    ToolFactory matchingFactory = toolFactory(expected);
    HarnessExtensionHost host =
        new HarnessExtensionHost(
            List.of(
                extension(
                    "factories",
                    0,
                    registry -> {
                      registry.addProviderFactory(providerFactory);
                      registry.addToolFactory(matchingFactory);
                    })));

    assertSame(providerFactory, host.providerFactory(ProviderType.GOOGLE).orElseThrow());
    assertFalse(host.providerFactory(ProviderType.OPENAI).isPresent());
    assertSame(expected, host.toolFactory("read", "1").orElseThrow().create());
    assertFalse(host.toolFactory("read", "2").isPresent());
    assertSame(expected, host.createTool("read", "1").orElseThrow());
    assertFalse(host.createTool("missing", "1").isPresent());

    ToolFactory mismatch =
        new ToolFactory() {
          @Override
          public ToolDescriptor descriptor() {
            return HarnessExtensionHostTest.descriptor("registered", "1");
          }

          @Override
          public Tool create() {
            return tool("different", "1");
          }
        };
    HarnessExtensionHost mismatchHost =
        new HarnessExtensionHost(
            List.of(extension("mismatch", 0, registry -> registry.addToolFactory(mismatch))));
    assertThrows(
        IllegalStateException.class,
        () -> mismatchHost.toolFactory("registered", "1").orElseThrow().create());
    assertThrows(IllegalStateException.class, () -> mismatchHost.createTool("registered", "1"));
  }

  /** contribute 中途失败时已注册 disposer 逆序清理，清理失败作为 suppressed 保留。 */
  @Test
  void cleansUpWhenContributionFails() {
    List<String> disposed = new ArrayList<>();
    HarnessExtensionException failure =
        assertThrows(
            HarnessExtensionException.class,
            () ->
                new HarnessExtensionHost(
                    List.of(
                        extension(
                            "first",
                            0,
                            registry -> registry.onDispose(() -> disposed.add("first"))),
                        extension(
                            "broken",
                            1,
                            registry -> {
                              registry.onDispose(
                                  () -> {
                                    disposed.add("broken-1");
                                    throw new IllegalStateException("dispose");
                                  });
                              registry.onDispose(() -> disposed.add("broken-2"));
                              throw new IllegalArgumentException("contribute");
                            }))));

    assertEquals(List.of("broken-2", "broken-1", "first"), disposed);
    assertEquals("broken", failure.extensionId());
    assertEquals(Phase.CONTRIBUTE, failure.phase());
    assertEquals("contribute", failure.getCause().getMessage());
    assertEquals(1, failure.getSuppressed().length);
    HarnessExtensionException cleanup = (HarnessExtensionException) failure.getSuppressed()[0];
    assertEquals("broken", cleanup.extensionId());
    assertEquals(Phase.DISPOSE, cleanup.phase());
  }

  /** close 只执行一次、严格逆注册顺序，并传播首错且抑制后续 disposer 错误。 */
  @Test
  void disposesInReverseOrderOnceAndSuppressesLaterFailures() {
    List<String> disposed = new ArrayList<>();
    HarnessExtensionHost host =
        new HarnessExtensionHost(
            List.of(
                extension(
                    "dispose",
                    0,
                    registry -> {
                      registry.onDispose(
                          () -> {
                            disposed.add("first");
                            throw new IllegalArgumentException("later failure");
                          });
                      registry.onDispose(() -> disposed.add("middle"));
                      registry.onDispose(
                          () -> {
                            disposed.add("last");
                            throw new IllegalStateException("first failure");
                          });
                    })));

    HarnessExtensionException failure = assertThrows(HarnessExtensionException.class, host::close);
    assertEquals(List.of("last", "middle", "first"), disposed);
    assertEquals(Phase.DISPOSE, failure.phase());
    assertEquals("first failure", failure.getCause().getMessage());
    assertEquals(1, failure.getSuppressed().length);
    assertEquals(
        "later failure",
        ((HarnessExtensionException) failure.getSuppressed()[0]).getCause().getMessage());

    host.close();
    assertEquals(List.of("last", "middle", "first"), disposed);
  }

  /** singleton factory 固定首次 descriptor，并在每次 create 时检测实现漂移。 */
  @Test
  void singletonFactoryRejectsDescriptorMismatch() {
    AtomicReference<ToolDescriptor> descriptor =
        new AtomicReference<>(descriptor("singleton", "1"));
    Tool tool = tool(descriptor);
    ToolFactory factory = ToolFactory.singleton(tool);

    assertSame(tool, factory.create());
    assertEquals("singleton", factory.descriptor().name());
    descriptor.set(descriptor("changed", "1"));
    assertThrows(IllegalStateException.class, factory::create);
  }

  private static HarnessExtension extension(
      String id, int priority, Consumer<HarnessExtensionRegistry> contribution) {
    return new HarnessExtension() {
      @Override
      public String id() {
        return id;
      }

      @Override
      public int priority() {
        return priority;
      }

      @Override
      public void contribute(HarnessExtensionRegistry registry) {
        contribution.accept(registry);
      }
    };
  }

  private static ProviderFactory providerFactory(ProviderType providerType) {
    PromptCacheCapability capability =
        PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT));
    return new ProviderFactory() {
      @Override
      public ProviderType providerType() {
        return providerType;
      }

      @Override
      public PromptCacheCapability promptCacheCapability() {
        return capability;
      }

      @Override
      public ProviderAdapter create(String credential, String configJson) {
        return null;
      }
    };
  }

  private static ToolFactory toolFactory(Tool tool) {
    return new ToolFactory() {
      @Override
      public ToolDescriptor descriptor() {
        return tool.descriptor();
      }

      @Override
      public Tool create() {
        return tool;
      }
    };
  }

  private static Tool tool(String name, String version) {
    return tool(new AtomicReference<>(descriptor(name, version)));
  }

  private static Tool tool(AtomicReference<ToolDescriptor> descriptor) {
    return new Tool() {
      @Override
      public ToolDescriptor descriptor() {
        return descriptor.get();
      }

      @Override
      public ToolExecutionHandle execute(
          ToolExecutionRequest request, ToolExecutionListener listener) {
        return null;
      }
    };
  }

  private static ToolDescriptor descriptor(String name, String version) {
    return new ToolDescriptor(
        name,
        version,
        name,
        null,
        new ToolParamsSchema("", Map.of(), Set.of(), false),
        ToolExecutionMode.CLOUD,
        ToolSideEffect.READ_ONLY,
        Duration.ZERO);
  }

  private static void assertImmutable(List<?> values) {
    assertThrows(UnsupportedOperationException.class, values::clear);
  }
}
