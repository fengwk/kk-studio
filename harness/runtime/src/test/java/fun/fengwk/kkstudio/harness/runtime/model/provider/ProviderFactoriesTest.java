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
