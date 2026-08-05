package fun.fengwk.kkstudio.harness.tool;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** ResourceToolContent 是 ResourceRef 的薄包装：只做非空约束。 */
class ResourceToolContentTest {

  @Test
  void rejectsNullResource() {
    assertThrows(NullPointerException.class, () -> new ResourceToolContent(null));
  }

  @Test
  void keepsTheExactResourceRef() {
    ResourceRef ref = new ResourceRef("https://example.com/a", "text/plain", "a", null, null);
    assertSame(ref, new ResourceToolContent(ref).resource());
  }
}
