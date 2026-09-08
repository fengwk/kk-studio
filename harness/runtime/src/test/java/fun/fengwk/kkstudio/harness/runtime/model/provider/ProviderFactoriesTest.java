package fun.fengwk.kkstudio.harness.runtime.model.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

class ProviderFactoriesTest {

  @Test
  void rejectsDuplicateProviderType() {
    ProviderFactory openai =
        ProviderFactory.of(
            ProviderType.OPENAI, PromptCacheCapability.unsupported(), (c, j) -> null);
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> new ProviderFactories(List.of(openai, openai)));
    assertTrue(error.getMessage().contains("duplicate ProviderFactory"));
    assertTrue(error.getMessage().contains("OPENAI"));
  }

  @Test
  void rejectsNullAdapterConstructor() {
    assertThrows(
        NullPointerException.class,
        () -> ProviderFactory.of(ProviderType.OPENAI, PromptCacheCapability.unsupported(), null));
  }

  @Test
  void rejectsAdapterWhoseProviderTypeMismatchesFactory() {
    ProviderFactory factory =
        ProviderFactory.of(
            ProviderType.OPENAI,
            PromptCacheCapability.unsupported(),
            (c, j) -> new AdapterStub(ProviderType.ANTHROPIC));
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> factory.create("", ""));
    assertTrue(error.getMessage().contains("ANTHROPIC"));
    assertTrue(error.getMessage().contains("OPENAI"));
  }

  @Test
  void lookupIsEmptyForUnknownTypeAndPresentForKnownType() {
    ProviderFactory openai =
        ProviderFactory.of(
            ProviderType.OPENAI,
            PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)),
            (c, j) -> new AdapterStub(ProviderType.OPENAI));
    ProviderFactories factories = new ProviderFactories(List.of(openai));
    assertSame(openai, factories.lookup(ProviderType.OPENAI).orElseThrow());
    assertTrue(factories.lookup(ProviderType.GOOGLE).isEmpty());
  }

  @Test
  void createAdapterIsInvokedExactlyOnceWithCredentialAndConfigJson() {
    AtomicInteger calls = new AtomicInteger();
    BiFunction<String, String, ProviderAdapter> ctor =
        (credential, configJson) -> {
          calls.incrementAndGet();
          assertEquals("cred", credential);
          assertEquals("cfg", configJson);
          return new AdapterStub(ProviderType.OPENAI);
        };
    ProviderFactory factory =
        ProviderFactory.of(
            ProviderType.OPENAI,
            PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)),
            ctor);
    ProviderAdapter adapter = factory.create("cred", "cfg");
    assertEquals(ProviderType.OPENAI, adapter.providerType());
    assertEquals(1, calls.get());
  }

  /** 意图：固定能力工厂的 promptCacheCapability(configJson) 默认回退至无参方法，确保历史适配器无缝兼容。 */
  @Test
  void fixedFactoryDelegatesConfigAwareCapabilityToParameterlessMethod() {
    PromptCacheCapability fixedCapability =
        PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT));
    ProviderFactory factory =
        ProviderFactory.of(
            ProviderType.OPENAI, fixedCapability, (c, j) -> new AdapterStub(ProviderType.OPENAI));

    assertSame(fixedCapability, factory.promptCacheCapability());
    assertSame(fixedCapability, factory.promptCacheCapability("{\"mode\":\"anything\"}"));
    assertSame(fixedCapability, factory.promptCacheCapability(null));
  }

  /** 意图：动态工厂 of 重载必须严格校验参数非空。 */
  @Test
  void dynamicFactoryRejectsNullParameters() {
    Function<String, PromptCacheCapability> resolver = cfg -> PromptCacheCapability.automatic();
    BiFunction<String, String, ProviderAdapter> ctor =
        (c, j) -> new AdapterStub(ProviderType.OPENAI);

    assertThrows(NullPointerException.class, () -> ProviderFactory.of(null, resolver, ctor));
    assertThrows(
        NullPointerException.class,
        () ->
            ProviderFactory.of(
                ProviderType.OPENAI, (Function<String, PromptCacheCapability>) null, ctor));
    assertThrows(
        NullPointerException.class, () -> ProviderFactory.of(ProviderType.OPENAI, resolver, null));
  }

  /** 意图：动态工厂依据配置 JSON 解析提示缓存能力，且无参调用必须等价于传 null 空配置的默认能力。 */
  @Test
  void dynamicFactoryResolvesCapabilityFromConfigAndEquatesNoArgToNullConfig() {
    PromptCacheCapability defaultConfig = PromptCacheCapability.automatic();
    PromptCacheCapability explicitConfig =
        PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT));

    ProviderFactory factory =
        ProviderFactory.of(
            ProviderType.OPENAI,
            configJson ->
                "{\"useAffinity\":true}".equals(configJson) ? explicitConfig : defaultConfig,
            (c, j) -> new AdapterStub(ProviderType.OPENAI));

    assertSame(defaultConfig, factory.promptCacheCapability());
    assertSame(defaultConfig, factory.promptCacheCapability(null));
    assertSame(defaultConfig, factory.promptCacheCapability(""));
    assertSame(explicitConfig, factory.promptCacheCapability("{\"useAffinity\":true}"));
  }

  /** 意图：动态工厂严格校验能力解析器不得返回 null。 */
  @Test
  void dynamicFactoryRejectsNullResolvedCapability() {
    ProviderFactory factory =
        ProviderFactory.of(
            ProviderType.OPENAI,
            configJson -> null,
            (c, j) -> new AdapterStub(ProviderType.OPENAI));

    assertThrows(NullPointerException.class, factory::promptCacheCapability);
    assertThrows(
        NullPointerException.class, () -> factory.promptCacheCapability("{\"some\":\"config\"}"));
  }

  /** 意图：动态工厂创建 adapter 时严格校验 adapter 的 providerType 与 factory 声明一致。 */
  @Test
  void dynamicFactoryRejectsAdapterWhoseProviderTypeMismatchesFactory() {
    ProviderFactory factory =
        ProviderFactory.of(
            ProviderType.OPENAI,
            cfg -> PromptCacheCapability.automatic(),
            (c, j) -> new AdapterStub(ProviderType.ANTHROPIC));

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> factory.create("", ""));
    assertTrue(error.getMessage().contains("ANTHROPIC"));
    assertTrue(error.getMessage().contains("OPENAI"));
  }

  private static final class AdapterStub implements ProviderAdapter {
    private final ProviderType type;

    private AdapterStub(ProviderType type) {
      this.type = type;
    }

    @Override
    public ProviderType providerType() {
      return type;
    }

    @Override
    public ModelProvider create(ProviderDescriptor descriptor) {
      throw new UnsupportedOperationException();
    }
  }
}
