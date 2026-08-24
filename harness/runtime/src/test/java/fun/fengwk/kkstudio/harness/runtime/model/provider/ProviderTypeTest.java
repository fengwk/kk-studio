package fun.fengwk.kkstudio.harness.runtime.model.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** ProviderType 的稳定 Catalog wire 值契约。 */
class ProviderTypeTest {

  @Test
  void everyProviderTypeRoundTripsItsCatalogWireValue() {
    // 四个值必须由同一个运行时 enum 双向提供，避免目录、持久化和解析层各自维护映射。
    for (ProviderType providerType : ProviderType.values()) {
      assertEquals(providerType, ProviderType.fromWireValue(providerType.wireValue()));
    }
    assertEquals("openai", ProviderType.OPENAI.wireValue());
    assertEquals("openai_response", ProviderType.OPENAI_RESPONSES.wireValue());
    assertEquals("anthropic", ProviderType.ANTHROPIC.wireValue());
    assertEquals("google", ProviderType.GOOGLE.wireValue());
  }

  @Test
  void rejectsNullBlankCaseChangedAndUnknownWireValues() {
    // wire 解析不 trim、不忽略大小写，也不接受未知值，防止非法值进入 Catalog。
    assertThrows(IllegalArgumentException.class, () -> ProviderType.fromWireValue(null));
    for (String invalid : new String[] {"", " ", "\t", "OPENAI", "openai_response ", "unknown"}) {
      assertThrows(
          IllegalArgumentException.class, () -> ProviderType.fromWireValue(invalid), invalid);
    }
  }
}
