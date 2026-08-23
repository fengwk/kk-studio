package fun.fengwk.kkstudio.canvas.infra.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import fun.fengwk.kkstudio.canvas.CanvasSession;
import fun.fengwk.kkstudio.canvas.CanvasSessionRepository;

import java.util.List;
import java.util.UUID;

/** {@link PostgresqlCanvasSessionRepository} 的 Harness Session 单归属 PostgreSQL 契约。 */
class PostgresqlCanvasSessionRepositoryIntegrationTest extends PostgresCanvasInfraTestSupport {

  @Autowired private CanvasSessionRepository sessions;

  /** 插入后可双向读取，session 主键阻止同一 Session 被同 Canvas 或另一 Canvas 重复持有。 */
  @Test
  void canvasSessionHasOneCanvasOwner() {
    UUID firstCanvas = addDocument();
    UUID secondCanvas = addDocument();
    UUID firstSession = addHarnessSession();
    UUID secondSession = addHarnessSession();

    assertTrue(sessions.insert(firstSession, firstCanvas));
    assertTrue(sessions.insert(secondSession, firstCanvas));
    assertEquals(
        new CanvasSession(firstSession, firstCanvas), sessions.findBySessionId(firstSession));
    assertNull(sessions.findBySessionId(UUID.randomUUID()));
    List<UUID> listed = sessions.listSessionIds(firstCanvas);
    assertEquals(2, listed.size());
    assertTrue(listed.contains(firstSession));
    assertTrue(listed.contains(secondSession));

    assertThrows(
        DataIntegrityViolationException.class, () -> sessions.insert(firstSession, firstCanvas));
    assertThrows(
        DataIntegrityViolationException.class, () -> sessions.insert(firstSession, secondCanvas));
    assertEquals(
        1L, count("select count(*) from canvas_session where session_id = ?", firstSession));

    assertEquals(1, sessions.deleteBySessionId(firstSession));
    assertEquals(0, sessions.deleteBySessionId(firstSession));
    assertNull(sessions.findBySessionId(firstSession));
    assertEquals(List.of(secondSession), sessions.listSessionIds(firstCanvas));
  }

  /** 条件插入允许无 owner 的 Session，但必须以 0 行拒绝已由 Chat 持有的 Session。 */
  @Test
  void conditionalInsertRejectsChatOwnedSession() {
    UUID canvasId = addDocument();
    UUID freeSession = addHarnessSession();
    assertEquals(1, sessions.insertIfNotOwnedByOther(freeSession, canvasId));

    UUID chatOwnedSession = addHarnessSession();
    UUID chatId = UUID.randomUUID();
    jdbc.update(
        "insert into chat (id, title, agent_name, version) values (?, ?, ?, 0)",
        chatId,
        "chat-owner",
        "test-agent");
    jdbc.update(
        "insert into chat_session (session_id, chat_id) values (?, ?)", chatOwnedSession, chatId);

    assertEquals(0, sessions.insertIfNotOwnedByOther(chatOwnedSession, canvasId));
    assertNull(sessions.findBySessionId(chatOwnedSession));
  }

  private UUID addHarnessSession() {
    UUID sessionId = UUID.randomUUID();
    jdbc.update(
        "insert into harness_session (id, created_at) values (?, current_timestamp)", sessionId);
    return sessionId;
  }

  private long count(String sql, Object... args) {
    Long result = jdbc.queryForObject(sql, Long.class, args);
    return result == null ? 0L : result;
  }
}
