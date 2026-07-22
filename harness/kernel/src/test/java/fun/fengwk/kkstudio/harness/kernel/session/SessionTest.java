package fun.fengwk.kkstudio.harness.kernel.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Instant;

/** Session 字段约束与父子关系不变量测试。 */
class SessionTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void rootSessionIsValid() {
    Session session = new Session(1L, 10L, "title", null, null, NOW, NOW);

    assertTrue(session.isRoot());
    assertEquals(1L, session.id());
    assertEquals(10L, session.mainThreadId());
    assertEquals("title", session.title());
    assertNull(session.parentSessionId());
    assertNull(session.parentInvocationId());
  }

  @Test
  void childSessionRequiresBothParentFields() {
    Session child = new Session(2L, 20L, "child", 1L, 100L, NOW, NOW);

    assertFalse(child.isRoot());
    assertEquals(1L, child.parentSessionId());
    assertEquals(100L, child.parentInvocationId());
  }

  @Test
  void titleMayBeNull() {
    Session session = new Session(1L, 10L, null, null, null, NOW, NOW);

    assertNull(session.title());
  }

  @Test
  void rejectsNonPositiveIds() {
    assertThrows(
        IllegalArgumentException.class, () -> new Session(0L, 1L, "title", null, null, NOW, NOW));
    assertThrows(
        IllegalArgumentException.class, () -> new Session(1L, 0L, "title", null, null, NOW, NOW));
  }

  @Test
  void rejectsNegativeParentIds() {
    assertThrows(
        IllegalArgumentException.class, () -> new Session(1L, 10L, "title", -1L, null, NOW, NOW));
    assertThrows(
        IllegalArgumentException.class, () -> new Session(1L, 10L, "title", null, -1L, NOW, NOW));
  }

  @Test
  void rejectsMismatchedParentFields() {
    assertThrows(
        IllegalArgumentException.class, () -> new Session(1L, 10L, "title", 1L, null, NOW, NOW));
    assertThrows(
        IllegalArgumentException.class, () -> new Session(1L, 10L, "title", null, 1L, NOW, NOW));
  }

  @Test
  void rejectsBlankTitleWhenPresent() {
    assertThrows(
        IllegalArgumentException.class, () -> new Session(1L, 10L, "", null, null, NOW, NOW));
    assertThrows(
        IllegalArgumentException.class, () -> new Session(1L, 10L, "  ", null, null, NOW, NOW));
  }

  @Test
  void rejectsInvertedTimestamps() {
    Instant earlier = NOW.minusSeconds(1);
    assertThrows(
        IllegalArgumentException.class,
        () -> new Session(1L, 10L, "title", null, null, NOW, earlier));
  }
}
