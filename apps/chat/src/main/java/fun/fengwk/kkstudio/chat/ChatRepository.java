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

/** {@code chat} 的 JdbcClient 仓库：读取、keyset 分页、两种行锁与 CAS 写。 */
@Repository
public class ChatRepository {

  private static final String COLUMNS =
      "id, title, agent_name, environment_name, yolo_enabled, version, created_at, updated_at";

  private static final String ORDER_BY =
      " order by updated_at desc, created_at desc, title asc, id asc";

  /**
   * 严格位于游标之后的稳定行判定：主键按 {@code (updated_at, created_at)} 倒序推进，同一时刻再按 {@code title} 升序与 {@code id}
   * 升序推进；{@code title} 为 null 时按 PostgreSQL ASC 的 NULLS LAST 语义只接受同为 null 的更大 id。
   */
  private static final String AFTER_CURSOR =
      """
      where updated_at < :updatedAt
         or (updated_at = :updatedAt and created_at < :createdAt)
         or (updated_at = :updatedAt and created_at = :createdAt and (
              (cast(:title as text) is null and title is null and id > :id)
              or (cast(:title as text) is not null
                  and (title is null
                       or title > cast(:title as text)
                       or (title = cast(:title as text) and id > :id)))
         ))
      """;

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

  private static final RowMapper<Chat> ROW_MAPPER = ChatRepository::mapRow;

  private final JdbcClient jdbcClient;

  public ChatRepository(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  /**
   * 一页对话，按 {@code (updated_at desc, created_at desc, title asc, id asc)} 稳定推进。
   *
   * <p>游标为 null 时返回首屏；否则只返回严格位于游标之后的稳定行。调用方多取一行判断是否还有下一页。
   */
  public List<Chat> listPage(ChatCursor cursor, int limit) {
    if (cursor == null) {
      return jdbcClient
          .sql("select " + COLUMNS + " from chat" + ORDER_BY + " limit :limit")
          .param("limit", limit)
          .query(ROW_MAPPER)
          .list();
    }
    return jdbcClient
        .sql("select " + COLUMNS + " from chat " + AFTER_CURSOR + ORDER_BY + " limit :limit")
        .param("updatedAt", Timestamp.from(cursor.updatedAt()))
        .param("createdAt", Timestamp.from(cursor.createdAt()))
        .param("title", cursor.title())
        .param("id", cursor.id())
        .param("limit", limit)
        .query(ROW_MAPPER)
        .list();
  }

  public Chat getById(UUID id) {
    return findOne("select " + COLUMNS + " from chat where id = :id", id);
  }

  /** 行级锁定读取（{@code for update}），用于深删除时固定 Chat 版本与关联枚举。 */
  public Chat lockById(UUID id) {
    return findOne("select " + COLUMNS + " from chat where id = :id for update", id);
  }

  /** KEY SHARE 锁定读取：归属授权路径用于阻止 Chat 删除但不串行化同 Chat 的并发接受。 */
  public Chat lockForKeyShare(UUID id) {
    return findOne("select " + COLUMNS + " from chat where id = :id for key share", id);
  }

  public boolean create(Chat chat) {
    return insert(chat) == 1;
  }

  /** 基于 (id, expectedVersion) 的原子 CAS 更新。 */
  public boolean updateById(Chat chat, long expectedVersion) {
    return jdbcClient
            .sql(UPDATE_BY_ID_SQL)
            .param("title", chat.getTitle())
            .param("agentName", chat.getAgentName())
            .param("environmentName", chat.getEnvironmentName())
            .param("yoloEnabled", chat.isYoloEnabled())
            .param("id", chat.getId())
            .param("expectedVersion", expectedVersion)
            .update()
        == 1;
  }

  /** 基于 (id, expectedVersion) 的原子 CAS 删除。 */
  public boolean deleteById(UUID id, long expectedVersion) {
    return jdbcClient
            .sql("delete from chat where id = :id and version = :expectedVersion")
            .param("id", id)
            .param("expectedVersion", expectedVersion)
            .update()
        == 1;
  }

  private int insert(Chat chat) {
    return jdbcClient
        .sql(INSERT_SQL)
        .param("id", chat.getId())
        .param("title", chat.getTitle())
        .param("agentName", chat.getAgentName())
        .param("environmentName", chat.getEnvironmentName())
        .param("yoloEnabled", chat.isYoloEnabled())
        .update();
  }

  private Chat findOne(String sql, UUID id) {
    return jdbcClient.sql(sql).param("id", id).query(ROW_MAPPER).optional().orElse(null);
  }

  private static Chat mapRow(ResultSet rs, int rowNum) throws SQLException {
    Chat chat = new Chat();
    chat.setId(rs.getObject("id", UUID.class));
    chat.setTitle(rs.getString("title"));
    chat.setAgentName(rs.getString("agent_name"));
    chat.setEnvironmentName(rs.getString("environment_name"));
    chat.setYoloEnabled(rs.getBoolean("yolo_enabled"));
    chat.setVersion(rs.getLong("version"));
    chat.setCreatedAt(
        PostgresJdbcTypes.toInstant(rs.getObject("created_at", OffsetDateTime.class)));
    chat.setUpdatedAt(
        PostgresJdbcTypes.toInstant(rs.getObject("updated_at", OffsetDateTime.class)));
    return chat;
  }
}
