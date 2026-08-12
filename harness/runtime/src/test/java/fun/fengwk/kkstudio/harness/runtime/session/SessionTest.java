package fun.fengwk.kkstudio.harness.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

/** Session 字段约束测试。 */
class SessionTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void sessionIsValid() {
    Session session = new Session(UUID.fromString("00000000-0000-0000-0000-000000000001"), NOW);

    assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000001"), session.id());
    assertEquals(NOW, session.createdAt());
  }

  @Test
  void rejectsNullCreatedAt() {
    assertThrows(
        NullPointerException.class,
        () -> new Session(UUID.fromString("00000000-0000-0000-0000-000000000001"), null));
  }
}
