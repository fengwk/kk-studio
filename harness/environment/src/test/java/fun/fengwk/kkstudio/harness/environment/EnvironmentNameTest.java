package fun.fengwk.kkstudio.harness.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** {@link EnvironmentName} canonical 有界小写路由名称契约测试。 */
class EnvironmentNameTest {

  @Test
  void acceptsCanonicalBoundedLowercaseNames() {
    assertEquals("env", new EnvironmentName("env").value());
    assertEquals("env-1", new EnvironmentName("env-1").value());
    assertEquals("a-b-c", new EnvironmentName("a-b-c").value());
    assertEquals("dev", new EnvironmentName("dev").value());
    assertEquals(
        "a".repeat(EnvironmentName.MAX_LENGTH),
        new EnvironmentName("a".repeat(EnvironmentName.MAX_LENGTH)).value());
  }

  @Test
  void rejectsBlankAndNull() {
    assertThrows(NullPointerException.class, () -> new EnvironmentName(null));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentName(""));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentName(" "));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentName("  "));
  }

  /** 大小写折叠歧义、路径分隔与空白/下划线等非路由字符全部拒绝。 */
  @Test
  void rejectsAmbiguousOrUnsafeCharacters() {
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentName("Env"));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentName("ENV"));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentName("myEnv"));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentName("my/env"));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentName("my env"));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentName("my\u00A0env"));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentName("my_env"));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentName("my.env"));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentName("env\t"));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentName("env\n"));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentName("我的环境"));
  }

  @Test
  void rejectsNonCanonicalSeparators() {
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentName("-env"));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentName("env-"));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentName("env--1"));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentName("--"));
  }

  @Test
  void rejectsNamesExceedingMaxLength() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new EnvironmentName("a".repeat(EnvironmentName.MAX_LENGTH + 1)));
  }

  @Test
  void toStringReturnsCanonicalValue() {
    assertEquals("env", new EnvironmentName("env").toString());
  }
}
