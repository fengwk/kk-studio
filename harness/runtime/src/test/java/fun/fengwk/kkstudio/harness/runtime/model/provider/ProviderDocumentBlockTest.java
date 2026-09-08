package fun.fengwk.kkstudio.harness.runtime.model.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** 验证 ProviderDocumentBlock 的构造校验与不可变记录属性。 */
class ProviderDocumentBlockTest {

  @Test
  void constructsValidDocumentBlock() {
    ProviderDocumentBlock block =
        new ProviderDocumentBlock("application/pdf", "https://example.test/doc.pdf");
    assertEquals("application/pdf", block.mediaType());
    assertEquals("https://example.test/doc.pdf", block.source());
  }

  @Test
  void rejectsNullOrBlankFields() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderDocumentBlock(null, "https://example.test/doc.pdf"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderDocumentBlock("   ", "https://example.test/doc.pdf"));
    assertThrows(
        IllegalArgumentException.class, () -> new ProviderDocumentBlock("application/pdf", null));
    assertThrows(
        IllegalArgumentException.class, () -> new ProviderDocumentBlock("application/pdf", "   "));
  }
}
