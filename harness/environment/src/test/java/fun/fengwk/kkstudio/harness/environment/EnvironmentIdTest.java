package fun.fengwk.kkstudio.harness.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.UUID;

/** {@link EnvironmentId} canonical UUID 路由身份契约测试。 */
class EnvironmentIdTest {

  private static final String CANONICAL = "123e4567-e89b-12d3-a456-426614174000";

  /** canonical UUID 文本可解析，且大小写变体被拒绝以保持 durable 路由身份唯一。 */
  @Test
  void parsesCanonicalUuidText() {
    assertEquals(CANONICAL, EnvironmentId.parse(CANONICAL).toString());
    assertEquals(CANONICAL, EnvironmentId.of(UUID.fromString(CANONICAL)).toString());
  }

  /** null、空白与非 canonical 文本必须在边界拒绝。 */
  @Test
  void rejectsNonCanonicalOrBlankText() {
    assertThrows(NullPointerException.class, () -> EnvironmentId.parse(null));
    assertThrows(IllegalArgumentException.class, () -> EnvironmentId.parse(""));
    assertThrows(IllegalArgumentException.class, () -> EnvironmentId.parse(" "));
    assertThrows(IllegalArgumentException.class, () -> EnvironmentId.parse("not-a-uuid"));
    assertThrows(
        IllegalArgumentException.class, () -> EnvironmentId.parse(CANONICAL.toUpperCase()));
    assertThrows(
        IllegalArgumentException.class,
        () -> EnvironmentId.parse("{123e4567-e89b-12d3-a456-426614174000}"));
  }

  /** record 拒绝 null UUID 值。 */
  @Test
  void rejectsNullUuid() {
    assertThrows(NullPointerException.class, () -> new EnvironmentId(null));
  }

  /** toString 输出 canonical UUID 文本。 */
  @Test
  void toStringReturnsCanonicalValue() {
    assertEquals(CANONICAL, EnvironmentId.of(UUID.fromString(CANONICAL)).toString());
  }
}
