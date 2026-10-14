package fun.fengwk.kkstudio.platform.chat.repo.impl.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.chat.repo.impl.model.ChatSessionDO;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** {@code chat_session} 归属边的 JdbcClient 入口。 */
@Repository
public class ChatSessionJdbcStore {

  private static final RowMapper<ChatSessionDO> ROW_MAPPER = ChatSessionJdbcStore::mapRow;

  private final JdbcClient jdbcClient;

  public ChatSessionJdbcStore(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  public int insert(UUID sessionId, UUID chatId) {
    return jdbcClient
        .sql("insert into chat_session (session_id, chat_id) values (:sessionId, :chatId)")
        .param("sessionId", sessionId)
        .param("chatId", chatId)
        .update();
  }

  public ChatSessionDO findBySessionId(UUID sessionId) {
    return jdbcClient
        .sql("select session_id, chat_id from chat_session where session_id = :sessionId")
        .param("sessionId", sessionId)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public List<UUID> listSessionIds(UUID chatId) {
    return jdbcClient
        .sql(
            "select session_id from chat_session where chat_id = :chatId"
                + " order by created_at desc, session_id desc")
        .param("chatId", chatId)
        .query(UUID.class)
        .list();
  }

  public int deleteBySessionId(UUID sessionId) {
    return jdbcClient
        .sql("delete from chat_session where session_id = :sessionId")
        .param("sessionId", sessionId)
        .update();
  }

  private static ChatSessionDO mapRow(ResultSet rs, int rowNum) throws SQLException {
    ChatSessionDO row = new ChatSessionDO();
    row.setSessionId(rs.getObject("session_id", UUID.class));
    row.setChatId(rs.getObject("chat_id", UUID.class));
    return row;
  }
}
