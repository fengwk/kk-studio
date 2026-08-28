package fun.fengwk.kkstudio.harness.contributor.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** {@link ContributorId} 的 canonical 校验、长度限制与 value equality 测试。 */
class ContributorIdTest {

  /** 常用合法 canonical id。 */
  @Test
  void acceptsCanonicalIdentifiers() {
    assertEquals("core", new ContributorId("core").value());
    assertEquals("goal", new ContributorId("goal").value());
    assertEquals("com.example.goal", new ContributorId("com.example.goal").value());
    assertEquals("pi-base", new ContributorId("pi-base").value());
    assertEquals("a-1.b-2", new ContributorId("a-1.b-2").value());
    assertEquals("core", new ContributorId("core").toString());
  }

  /** 非法字符、大写、连续标点、前缀/后缀标点与超长值必须拒绝。 */
  @Test
  void rejectsInvalidIdentifiers() {
    assertThrows(NullPointerException.class, () -> new ContributorId(null));
    assertThrows(IllegalArgumentException.class, () -> new ContributorId(""));
    assertThrows(IllegalArgumentException.class, () -> new ContributorId("   "));
    assertThrows(IllegalArgumentException.class, () -> new ContributorId("Core"));
    assertThrows(IllegalArgumentException.class, () -> new ContributorId("core_plugin"));
    assertThrows(IllegalArgumentException.class, () -> new ContributorId("core..plugin"));
    assertThrows(IllegalArgumentException.class, () -> new ContributorId("core--plugin"));
    assertThrows(IllegalArgumentException.class, () -> new ContributorId(".core"));
    assertThrows(IllegalArgumentException.class, () -> new ContributorId("core."));
    assertThrows(IllegalArgumentException.class, () -> new ContributorId("-core"));
    assertThrows(IllegalArgumentException.class, () -> new ContributorId("core-"));
    assertThrows(IllegalArgumentException.class, () -> new ContributorId("a".repeat(65)));
  }

  /** 64 字符为上限，正好 64 字符的 canonical id 必须被接受。 */
  @Test
  void acceptsMaxLengthIdentifier() {
    String max = "a".repeat(64);
    assertEquals(max, new ContributorId(max).value());
  }

  /** 相同 value 的 ContributorId 在 value equality 与 hashCode 上等价。 */
  @Test
  void comparesByValue() {
    ContributorId first = new ContributorId("com.example.goal");
    ContributorId second = new ContributorId("com.example.goal");
    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
  }
}
