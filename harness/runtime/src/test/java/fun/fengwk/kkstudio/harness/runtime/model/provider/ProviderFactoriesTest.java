package fun.fengwk.kkstudio.harness.runtime.model.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

class ProviderFactoriesTest {

  @Test
  void rejectsDuplicateProviderType() {
    ProviderFactory openai =
        ProviderFactory.of(ProviderType.OPENAI, PromptCacheRetention.NONE, (c, j) -> null);
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
        () -> ProviderFactory.of(ProviderType.OPENAI, PromptCacheRetention.NONE, null));
  }

  @Test
  void rejectsAdapterWhoseProviderTypeMismatchesFactory() {
    ProviderFactory factory =
        ProviderFactory.of(
            ProviderType.OPENAI,
            PromptCacheRetention.NONE,
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
            PromptCacheRetention.SHORT,
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
        ProviderFactory.of(ProviderType.OPENAI, PromptCacheRetention.SHORT, ctor);
    ProviderAdapter adapter = factory.create("cred", "cfg");
    assertEquals(ProviderType.OPENAI, adapter.providerType());
    assertEquals(1, calls.get());
  }

  /** 意图：固定档位工厂的 promptCacheRetention(configJson) 默认回退至无参方法，确保历史适配器无缝兼容。 */
  @Test
  void fixedFactoryDelegatesConfigAwareRetentionToParameterlessMethod() {
    ProviderFactory factory =
        ProviderFactory.of(
            ProviderType.OPENAI,
            PromptCacheRetention.LONG,
            (c, j) -> new AdapterStub(ProviderType.OPENAI));

    assertEquals(PromptCacheRetention.LONG, factory.promptCacheRetention());
    assertEquals(
        PromptCacheRetention.LONG, factory.promptCacheRetention("{\"mode\":\"anything\"}"));
    assertEquals(PromptCacheRetention.LONG, factory.promptCacheRetention(null));
  }

  /** 意图：动态工厂 of 重载必须严格校验参数非空。 */
  @Test
  void dynamicFactoryRejectsNullParameters() {
    Function<String, PromptCacheRetention> resolver = cfg -> PromptCacheRetention.NONE;
    BiFunction<String, String, ProviderAdapter> ctor =
        (c, j) -> new AdapterStub(ProviderType.OPENAI);

    assertThrows(NullPointerException.class, () -> ProviderFactory.of(null, resolver, ctor));
    assertThrows(
        NullPointerException.class,
        () ->
            ProviderFactory.of(
                ProviderType.OPENAI, (Function<String, PromptCacheRetention>) null, ctor));
    assertThrows(
        NullPointerException.class, () -> ProviderFactory.of(ProviderType.OPENAI, resolver, null));
  }

  /** 意图：动态工厂依据配置 JSON 解析留存档位，且无参调用必须等价于传 null 空配置的默认档位。 */
  @Test
  void dynamicFactoryResolvesRetentionFromConfigAndEquatesNoArgToNullConfig() {
    ProviderFactory factory =
        ProviderFactory.of(
            ProviderType.OPENAI,
            configJson ->
                "{\"useAffinity\":true}".equals(configJson)
                    ? PromptCacheRetention.SHORT
                    : PromptCacheRetention.NONE,
            (c, j) -> new AdapterStub(ProviderType.OPENAI));

    assertEquals(PromptCacheRetention.NONE, factory.promptCacheRetention());
    assertEquals(PromptCacheRetention.NONE, factory.promptCacheRetention(null));
    assertEquals(PromptCacheRetention.NONE, factory.promptCacheRetention(""));
    assertEquals(
        PromptCacheRetention.SHORT, factory.promptCacheRetention("{\"useAffinity\":true}"));
  }

  /** 意图：动态工厂严格校验留存解析器不得返回 null。 */
  @Test
  void dynamicFactoryRejectsNullResolvedRetention() {
    ProviderFactory factory =
        ProviderFactory.of(
            ProviderType.OPENAI,
            configJson -> null,
            (c, j) -> new AdapterStub(ProviderType.OPENAI));

    assertThrows(NullPointerException.class, factory::promptCacheRetention);
    assertThrows(
        NullPointerException.class, () -> factory.promptCacheRetention("{\"some\":\"config\"}"));
  }

  /** 意图：动态工厂创建 adapter 时严格校验 adapter 的 providerType 与 factory 声明一致。 */
  @Test
  void dynamicFactoryRejectsAdapterWhoseProviderTypeMismatchesFactory() {
    ProviderFactory factory =
        ProviderFactory.of(
            ProviderType.OPENAI,
            cfg -> PromptCacheRetention.NONE,
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
