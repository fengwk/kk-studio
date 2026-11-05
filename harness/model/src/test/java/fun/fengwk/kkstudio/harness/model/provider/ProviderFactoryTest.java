package fun.fengwk.kkstudio.harness.model.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

class ProviderFactoryTest {
  // 类型和构造器为工厂必要输入；工厂不得返回 null adapter。
  @Test
  void rejectsNullParametersAndNullAdapter() {
    assertThrows(NullPointerException.class, () -> ProviderFactory.of(null, credential -> null));
    assertThrows(NullPointerException.class, () -> ProviderFactory.of(ProviderType.OPENAI, null));
    ProviderFactory factory = ProviderFactory.of(ProviderType.OPENAI, credential -> null);
    assertThrows(NullPointerException.class, () -> factory.create("credential"));
  }

  // 创建时只将凭据传入一次，类型由工厂固定。
  @Test
  void createsOnceWithCredential() {
    AtomicInteger calls = new AtomicInteger();
    ProviderFactory factory =
        ProviderFactory.of(
            ProviderType.OPENAI,
            credential -> {
              calls.incrementAndGet();
              assertEquals("cred", credential);
              return new AdapterStub(ProviderType.OPENAI);
            });
    assertEquals(ProviderType.OPENAI, factory.providerType());
    assertEquals(ProviderType.OPENAI, factory.create("cred").providerType());
    assertEquals(1, calls.get());
  }

  // 不允许工厂与 adapter 宣告不同的协议。
  @Test
  void rejectsAdapterWithMismatchedType() {
    ProviderFactory factory =
        ProviderFactory.of(
            ProviderType.OPENAI, credential -> new AdapterStub(ProviderType.ANTHROPIC));
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> factory.create(""));
    assertTrue(error.getMessage().contains("ANTHROPIC"));
    assertTrue(error.getMessage().contains("OPENAI"));
  }

  private record AdapterStub(ProviderType providerType) implements ProviderAdapter {
    @Override
    public ModelProvider create(ProviderDescriptor descriptor) {
      throw new UnsupportedOperationException();
    }
  }
}
