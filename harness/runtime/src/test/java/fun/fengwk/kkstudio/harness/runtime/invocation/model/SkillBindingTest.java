package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** SkillBinding 的平台包版本冻结契约。 */
class SkillBindingTest {

  private static final String REVISION =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  @Test
  void acceptsCompleteFrozenFacts() {
    SkillBinding binding =
        new SkillBinding("web_search", "web-tools", "1.0.0", REVISION, "Search the web");

    assertEquals("web_search", binding.name());
    assertEquals("web-tools", binding.packageName());
    assertEquals("1.0.0", binding.packageVersion());
    assertEquals(REVISION, binding.contentRevision());
    assertEquals("Search the web", binding.description());

    SkillBinding longDescription =
        new SkillBinding("web_search", "web-tools", "1.0.0", REVISION, "d".repeat(1024));
    assertEquals(1024, longDescription.description().length());
  }

  @Test
  void rejectsInvalidNameAndPackageIdentity() {
    assertThrows(
        NullPointerException.class,
        () -> new SkillBinding(null, "package", "1.0.0", REVISION, "desc"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding(" ", "package", "1.0.0", REVISION, "desc"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding(" name", "package", "1.0.0", REVISION, "desc"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding("name ", "package", "1.0.0", REVISION, "desc"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding("n".repeat(129), "package", "1.0.0", REVISION, "desc"));
    assertThrows(
        NullPointerException.class,
        () -> new SkillBinding("name", null, "1.0.0", REVISION, "desc"));
    assertThrows(
        NullPointerException.class,
        () -> new SkillBinding("name", "package", null, REVISION, "desc"));
  }

  @Test
  void rejectsInvalidDescription() {
    assertThrows(
        NullPointerException.class,
        () -> new SkillBinding("name", "package", "1.0.0", REVISION, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding("name", "package", "1.0.0", REVISION, ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding("name", "package", "1.0.0", REVISION, " desc"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding("name", "package", "1.0.0", REVISION, "d".repeat(1025)));
  }

  /** contentRevision 是必须显式冻结的事实：缺失或空白不得被解释成“当前版本”。 */
  @Test
  void rejectsMissingContentRevision() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding("name", "package", "1.0.0", null, "desc"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding("name", "package", "1.0.0", " ", "desc"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding("name", "package", "1.0.0", " revision", "desc"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding("name", "package", "1.0.0", "a".repeat(40), "desc"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding("name", "package", "1.0.0", REVISION.toUpperCase(), "desc"));
  }

  /** Runtime 冻结事实必须保持 canonical 文本。 */
  @Test
  void rejectsValuesOutsideTheCanonicalContract() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding("bad\nname", "package", "1.0.0", REVISION, "desc"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding("name", "package", "1.0.0", REVISION, "bad\u0000desc"));
  }
}
