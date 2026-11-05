package fun.fengwk.kkstudio.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code chat_session} 归属仓库在真实 PostgreSQL 上的契约：插入/查找/枚举/keyset 分页/删除、FK 防悬空与 session 单归属互斥。 */
class ChatSessionRepositoryIntegrationTest extends PostgresSpringTestSupport {

  @Autowired private ChatSessionRepository chatSessionRepository;
  @Autowired private ChatRepository chatRepository;
  @Autowired private JdbcTemplate jdbc;

  /** 插入后可按 session/chat 两个方向读取，owner 枚举按归属时间倒序，删除返回受影响行数与幂等。 */
  @Test
  void insertFindEnumerateAndDelete() {
    UUID chatId = chatRow();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    sessionRow(first);
    sessionRow(second);

    assertTrue(chatSessionRepository.insert(first, chatId));
    assertTrue(chatSessionRepository.insert(second, chatId));

    assertEquals(new ChatSession(first, chatId), chatSessionRepository.findBySessionId(first));
    assertEquals(new ChatSession(second, chatId), chatSessionRepository.findBySessionId(second));
    assertNull(chatSessionRepository.findBySessionId(UUID.randomUUID()));

    // owner listing 按 created_at 倒序：本容器时钟下后插入的更晚。
    List<UUID> listed = chatSessionRepository.listSessionIds(chatId);
    assertEquals(2, listed.size());
    assertTrue(listed.contains(first) && listed.contains(second));

    assertEquals(1, chatSessionRepository.deleteBySessionId(first));
    assertEquals(0, chatSessionRepository.deleteBySessionId(first), "重复删除必须幂等返回 0 行");
    assertNull(chatSessionRepository.findBySessionId(first));
    assertEquals(List.of(second), chatSessionRepository.listSessionIds(chatId));
  }

  /** session_id 是主键：同一 Session 不能被第二次持有（无论归属本 owner 还是另一 owner）。 */
  @Test
  void sessionIdIsSingleOwnerPrimaryKey() {
    UUID chatA = chatRow();
    UUID chatB = chatRow();
    UUID sessionId = UUID.randomUUID();
    sessionRow(sessionId);

    assertTrue(chatSessionRepository.insert(sessionId, chatA));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> chatSessionRepository.insert(sessionId, chatA));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> chatSessionRepository.insert(sessionId, chatB));
    assertEquals(1L, count("select count(*) from chat_session where session_id = ?", sessionId));
  }

  /** 一页归属边按 created_at 倒序、再按 session_id 倒序推进，游标后无重复无遗漏。 */
  @Test
  void listPageAdvancesByCreatedAtThenSessionId() {
    UUID chatId = chatRow();
    Instant base = Instant.parse("2026-01-01T00:00:00Z");
    UUID newest = ownedSession(chatId, base.plusSeconds(3));
    UUID sameTimeLow = ownedSessionAt(chatId, base.plusSeconds(1), new UUID(0L, 1L));
    UUID sameTimeHigh = ownedSessionAt(chatId, base.plusSeconds(1), new UUID(0L, 2L));
    UUID middle = ownedSession(chatId, base.plusSeconds(2));

    List<ChatSessionRow> page1 = chatSessionRepository.listPage(chatId, null, 2);
    assertEquals(List.of(newest, middle), pageIds(page1));
    ChatSessionCursor afterPage1 = ChatSessionCursor.of(page1.getLast());

    List<ChatSessionRow> page2 = chatSessionRepository.listPage(chatId, afterPage1, 2);
    assertEquals(List.of(sameTimeHigh, sameTimeLow), pageIds(page2));
    assertNull(
        chatSessionRepository.listPage(chatId, ChatSessionCursor.of(page2.getLast()), 2).stream()
            .findFirst()
            .orElse(null));
  }

  /** FK：chat 与 harness_session 都必须存在，防止出现悬空归属。 */
  @Test
  void foreignKeysRejectOrphanedRows() {
    UUID chatId = chatRow();
    UUID sessionId = UUID.randomUUID();
    sessionRow(sessionId);

    assertThrows(
        DataIntegrityViolationException.class,
        () -> chatSessionRepository.insert(sessionId, UUID.randomUUID()),
        "missing chat owner must fail the chat_id FK");
    assertThrows(
        DataIntegrityViolationException.class,
        () -> chatSessionRepository.insert(UUID.randomUUID(), chatId),
        "missing harness_session must fail the session_id FK");
  }

  private UUID chatRow() {
    Chat chat = new Chat();
    UUID id = UUID.randomUUID();
    chat.setId(id);
    chat.setTitle("chat-" + id);
    chat.setAgentName("schema-agent");
    chat.setVersion(0L);
    assertTrue(chatRepository.create(chat));
    return id;
  }

  private void sessionRow(UUID sessionId) {
    jdbc.update(
        "insert into harness_session (id, name, created_at) values (?, ?, current_timestamp)",
        sessionId,
        "test-session");
  }

  private UUID ownedSession(UUID chatId, Instant createTime) {
    return ownedSessionAt(chatId, createTime, UUID.randomUUID());
  }

  private UUID ownedSessionAt(UUID chatId, Instant createTime, UUID sessionId) {
    jdbc.update(
        "insert into harness_session (id, name, created_at) values (?, ?, ?)",
        sessionId,
        "test-session",
        Timestamp.from(createTime));
    jdbc.update(
        "insert into chat_session (session_id, chat_id, created_at) values (?, ?, ?)",
        sessionId,
        chatId,
        Timestamp.from(createTime));
    return sessionId;
  }

  private static List<UUID> pageIds(List<ChatSessionRow> rows) {
    return rows.stream().map(ChatSessionRow::sessionId).toList();
  }

  private long count(String sql, UUID id) {
    Long value = jdbc.queryForObject(sql, Long.class, id);
    return value == null ? 0L : value;
  }
}
