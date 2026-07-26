package fun.fengwk.kkstudio.harness.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.time.Instant;

/** Session 字段约束测试。 */
class SessionTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void sessionIsValid() {
    Session session = new Session(1L, "title", NOW);

    assertEquals(1L, session.id());
    assertEquals("title", session.title());
    assertEquals(NOW, session.createdAt());
  }

  @Test
  void titleMayBeNull() {
    Session session = new Session(1L, null, NOW);

    assertNull(session.title());
  }

  @Test
  void rejectsNonPositiveIds() {
    assertThrows(IllegalArgumentException.class, () -> new Session(0L, "title", NOW));
  }

  @Test
  void rejectsBlankTitleWhenPresent() {
    assertThrows(IllegalArgumentException.class, () -> new Session(1L, "", NOW));
    assertThrows(IllegalArgumentException.class, () -> new Session(1L, "  ", NOW));
  }

  @Test
  void rejectsNullCreatedAt() {
    assertThrows(NullPointerException.class, () -> new Session(1L, "title", null));
  }
}
