package fun.fengwk.kkstudio.harness.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

/** Session 字段约束与 rename 迁移测试。 */
class SessionTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void sessionIsValid() {
    Session session =
        new Session(UUID.fromString("00000000-0000-0000-0000-000000000001"), "hello", NOW);

    assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000001"), session.id());
    assertEquals("hello", session.name());
    assertEquals(NOW, session.createdAt());
  }

  @Test
  void rejectsNullIdAndCreatedAt() {
    assertThrows(NullPointerException.class, () -> new Session(null, "hello", NOW));
    assertThrows(
        NullPointerException.class,
        () -> new Session(UUID.fromString("00000000-0000-0000-0000-000000000001"), "hello", null));
  }

  @Test
  void normalizesNameToSingleLineNonBlank() {
    // 名称统一折叠任意 Unicode 空白为单空格并去除首尾空白（与 Names 工具一致）。
    Session session =
        new Session(
            UUID.fromString("00000000-0000-0000-0000-000000000001"), "  hello\n\t  world  ", NOW);
    assertEquals("hello world", session.name());
  }

  @Test
  void rejectsBlankAndOverlongNames() {
    // 名称必须非空、至多 256 个 Unicode 码点（超过抛异常，绝不截断）。
    assertThrows(
        IllegalArgumentException.class,
        () -> new Session(UUID.fromString("00000000-0000-0000-0000-000000000001"), "   ", NOW));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Session(
                UUID.fromString("00000000-0000-0000-0000-000000000001"), "x".repeat(257), NOW));
  }

  @Test
  void renamePreservesIdentityAndReplacesName() {
    Session session =
        new Session(UUID.fromString("00000000-0000-0000-0000-000000000001"), "old", NOW);
    Session renamed = session.rename("  new name  ");
    assertEquals(session.id(), renamed.id());
    assertEquals(session.createdAt(), renamed.createdAt());
    assertEquals("new name", renamed.name());
    assertEquals("old", session.name());
  }
}
