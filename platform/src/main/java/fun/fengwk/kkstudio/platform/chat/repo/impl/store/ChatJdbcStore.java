package fun.fengwk.kkstudio.platform.chat.repo.impl.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.chat.repo.impl.model.ChatDO;
import fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** {@code chat} 的 JdbcClient 入口：读取、两种行锁与 CAS 写。 */
@Repository
public class ChatJdbcStore {

  private static final String COLUMNS =
      "id, title, agent_name, environment_name, yolo_enabled, version, "
          + "created_at as create_time, updated_at as update_time";

  private static final String INSERT_SQL =
      """
      insert into chat (
          id, title, agent_name, environment_name, yolo_enabled,
          created_at, updated_at, version
      ) values (
          :id, :title, :agentName, :environmentName, :yoloEnabled,
          current_timestamp, current_timestamp, 0
      )
      """;

  private static final String UPDATE_BY_ID_SQL =
      """
      update chat
      set title = :title, agent_name = :agentName,
          environment_name = :environmentName,
          yolo_enabled = :yoloEnabled,
          updated_at = greatest(updated_at, current_timestamp), version = version + 1
      where id = :id and version = :expectedVersion
      """;

  private static final RowMapper<ChatDO> ROW_MAPPER = ChatJdbcStore::mapRow;

  private final JdbcClient jdbcClient;

  public ChatJdbcStore(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  public List<ChatDO> listNewestFirst() {
    return jdbcClient
        .sql(
            "select " + COLUMNS + " from chat order by updated_at desc, created_at desc, title asc")
        .query(ROW_MAPPER)
        .list();
  }

  public ChatDO getById(UUID id) {
    return findOne("select " + COLUMNS + " from chat where id = :id", id);
  }

  public ChatDO lockById(UUID id) {
    return findOne("select " + COLUMNS + " from chat where id = :id for update", id);
  }

  public ChatDO lockForKeyShare(UUID id) {
    return findOne("select " + COLUMNS + " from chat where id = :id for key share", id);
  }

  public int insert(ChatDO chat) {
    return jdbcClient
        .sql(INSERT_SQL)
        .param("id", chat.getId())
        .param("title", chat.getTitle())
        .param("agentName", chat.getAgentName())
        .param("environmentName", chat.getEnvironmentName())
        .param("yoloEnabled", chat.isYoloEnabled())
        .update();
  }

  public int updateById(ChatDO chat, long expectedVersion) {
    return jdbcClient
        .sql(UPDATE_BY_ID_SQL)
        .param("title", chat.getTitle())
        .param("agentName", chat.getAgentName())
        .param("environmentName", chat.getEnvironmentName())
        .param("yoloEnabled", chat.isYoloEnabled())
        .param("id", chat.getId())
        .param("expectedVersion", expectedVersion)
        .update();
  }

  public int deleteById(UUID id, long expectedVersion) {
    return jdbcClient
        .sql("delete from chat where id = :id and version = :expectedVersion")
        .param("id", id)
        .param("expectedVersion", expectedVersion)
        .update();
  }

  private ChatDO findOne(String sql, UUID id) {
    return jdbcClient.sql(sql).param("id", id).query(ROW_MAPPER).optional().orElse(null);
  }

  private static ChatDO mapRow(ResultSet rs, int rowNum) throws SQLException {
    ChatDO row = new ChatDO();
    row.setId(rs.getObject("id", UUID.class));
    row.setTitle(rs.getString("title"));
    row.setAgentName(rs.getString("agent_name"));
    row.setEnvironmentName(rs.getString("environment_name"));
    row.setYoloEnabled(rs.getBoolean("yolo_enabled"));
    row.setVersion(rs.getLong("version"));
    row.setCreateTime(
        PostgresJdbcTypes.toInstant(rs.getObject("create_time", OffsetDateTime.class)));
    row.setUpdateTime(
        PostgresJdbcTypes.toInstant(rs.getObject("update_time", OffsetDateTime.class)));
    return row;
  }
}
