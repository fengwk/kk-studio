package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** SkillBinding 的平台包身份冻结契约。 */
class SkillBindingTest {

  @Test
  void acceptsCompleteFrozenFacts() {
    SkillBinding binding = new SkillBinding("web_search", "web-tools", "1.0.0", "Search the web");

    assertEquals("web_search", binding.name());
    assertEquals("web-tools", binding.packageName());
    assertEquals("1.0.0", binding.packageVersion());
    assertEquals("Search the web", binding.description());

    SkillBinding longDescription =
        new SkillBinding("web_search", "web-tools", "1.0.0", "d".repeat(1024));
    assertEquals(1024, longDescription.description().length());
  }

  @Test
  void rejectsInvalidNameAndPackageIdentity() {
    assertThrows(
        NullPointerException.class, () -> new SkillBinding(null, "package", "1.0.0", "desc"));
    assertThrows(
        IllegalArgumentException.class, () -> new SkillBinding(" ", "package", "1.0.0", "desc"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding(" name", "package", "1.0.0", "desc"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding("name ", "package", "1.0.0", "desc"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding("n".repeat(129), "package", "1.0.0", "desc"));
    assertThrows(NullPointerException.class, () -> new SkillBinding("name", null, "1.0.0", "desc"));
    assertThrows(
        NullPointerException.class, () -> new SkillBinding("name", "package", null, "desc"));
  }

  @Test
  void rejectsInvalidDescription() {
    assertThrows(
        NullPointerException.class, () -> new SkillBinding("name", "package", "1.0.0", null));
    assertThrows(
        IllegalArgumentException.class, () -> new SkillBinding("name", "package", "1.0.0", ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding("name", "package", "1.0.0", " desc"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding("name", "package", "1.0.0", "d".repeat(1025)));
  }

  /** Runtime 冻结事实必须保持 canonical 文本。 */
  @Test
  void rejectsValuesOutsideTheCanonicalContract() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding("bad\nname", "package", "1.0.0", "desc"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding("name", "package", "1.0.0", "bad\u0000desc"));
  }
}
