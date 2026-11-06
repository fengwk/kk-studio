package fun.fengwk.kkstudio.chat;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** {@code chat_session} 归属边的 JdbcClient 仓库。 */
@Repository
public class ChatSessionRepository {

  private static final RowMapper<ChatSession> ROW_MAPPER = ChatSessionRepository::mapRow;

  private final JdbcClient jdbcClient;

  public ChatSessionRepository(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  /**
   * 插入归属边（{@code created_at} 由数据库默认填充）；Session 已被任何一方持有时抛 {@code DataIntegrityViolationException}。
   */
  public boolean insert(UUID sessionId, UUID chatId) {
    return jdbcClient
            .sql("insert into chat_session (session_id, chat_id) values (:sessionId, :chatId)")
            .param("sessionId", sessionId)
            .param("chatId", chatId)
            .update()
        == 1;
  }

  /** 枚举某 Chat 的全部 Session id（owner listing），按归属时间倒序。 */
  public List<UUID> listSessionIds(UUID chatId) {
    return jdbcClient
        .sql(
            "select session_id from chat_session where chat_id = :chatId"
                + " order by created_at desc, session_id desc")
        .param("chatId", chatId)
        .query(UUID.class)
        .list();
  }

  /**
   * 一页归属边，按 {@code (created_at desc, session_id desc)} 稳定推进；游标为 null 时返回首屏。
   *
   * <p>调用方多取一行判断是否还有下一页。
   */
  public List<ChatSessionRow> listPage(UUID chatId, ChatSessionCursor cursor, int limit) {
    if (cursor == null) {
      return jdbcClient
          .sql(
              "select session_id, created_at from chat_session where chat_id = :chatId"
                  + " order by created_at desc, session_id desc limit :limit")
          .param("chatId", chatId)
          .param("limit", limit)
          .query(ChatSessionRepository::mapPageRow)
          .list();
    }
    return jdbcClient
        .sql(
            "select session_id, created_at from chat_session where chat_id = :chatId"
                + " and (created_at < :createdAt"
                + " or (created_at = :createdAt and session_id < :sessionId))"
                + " order by created_at desc, session_id desc limit :limit")
        .param("chatId", chatId)
        .param("createdAt", Timestamp.from(cursor.createdAt()))
        .param("sessionId", cursor.sessionId())
        .param("limit", limit)
        .query(ChatSessionRepository::mapPageRow)
        .list();
  }

  /** 按 Session id 读取归属边；不存在返回 {@code null}。 */
  public ChatSession findBySessionId(UUID sessionId) {
    return jdbcClient
        .sql("select session_id, chat_id from chat_session where session_id = :sessionId")
        .param("sessionId", sessionId)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  /** 删除指定 Session 的归属边并返回删除行数（深删除时先于 Session 行删除）。 */
  public int deleteBySessionId(UUID sessionId) {
    return jdbcClient
        .sql("delete from chat_session where session_id = :sessionId")
        .param("sessionId", sessionId)
        .update();
  }

  private static ChatSession mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new ChatSession(
        rs.getObject("session_id", UUID.class), rs.getObject("chat_id", UUID.class));
  }

  private static ChatSessionRow mapPageRow(ResultSet rs, int rowNum) throws SQLException {
    return new ChatSessionRow(
        rs.getObject("session_id", UUID.class),
        PostgresJdbcTypes.toInstant(rs.getObject("created_at", OffsetDateTime.class)));
  }
}
