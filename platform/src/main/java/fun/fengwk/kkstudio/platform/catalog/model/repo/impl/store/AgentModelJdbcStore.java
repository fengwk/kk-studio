package fun.fengwk.kkstudio.platform.catalog.model.repo.impl.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.catalog.model.repo.impl.model.AgentModelDO;
import fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/** {@code agent_model} 的 JdbcClient 入口，含原子改名序列（插入新行、改写引用、删除旧行）。 */
@Repository
public class AgentModelJdbcStore {

  private static final String COLUMNS =
      "provider_name, name, model_id, description, config, version, "
          + "created_at as create_time, updated_at as update_time";

  private static final String INSERT_SQL =
      """
      insert into agent_model (
          provider_name, name, model_id, description, config,
          created_at, updated_at, version
      ) values (
          :providerName, :name, :modelId, :description, cast(:configJson as jsonb),
          current_timestamp, current_timestamp, 0
      )
      """;

  private static final String UPDATE_BY_NAME_SQL =
      """
      update agent_model
      set model_id = :modelId, description = :description,
          config = cast(:configJson as jsonb),
          updated_at = greatest(updated_at, current_timestamp), version = version + 1
      where provider_name = :providerName and name = :name
        and version = :expectedVersion
      """;

  private static final String INSERT_RENAMED_SQL =
      """
      insert into agent_model (
          provider_name, name, model_id, description, config,
          created_at, updated_at, version
      )
      select
          provider_name,
          :name,
          :modelId,
          :description,
          cast(:configJson as jsonb),
          created_at,
          greatest(updated_at, current_timestamp),
          version + 1
      from agent_model
      where provider_name = :providerName and name = :oldName
        and version = :expectedVersion
      """;

  private static final String UPDATE_REFERENCING_AGENTS_SQL =
      """
      update agent_definition
      set model_name = :newName,
          updated_at = greatest(updated_at, current_timestamp),
          version = version + 1
      where model_provider_name = :providerName and model_name = :oldName
      """;

  private static final RowMapper<AgentModelDO> ROW_MAPPER = AgentModelJdbcStore::mapRow;

  private final JdbcClient jdbcClient;

  public AgentModelJdbcStore(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  public long count() {
    return jdbcClient.sql("select count(*) from agent_model").query(Long.class).single();
  }

  public List<AgentModelDO> page(long offset, int limit) {
    return jdbcClient
        .sql(
            "select "
                + COLUMNS
                + " from agent_model"
                + " order by provider_name asc, name asc limit :limit offset :offset")
        .param("limit", limit)
        .param("offset", offset)
        .query(ROW_MAPPER)
        .list();
  }

  public List<AgentModelDO> listAll() {
    return jdbcClient
        .sql("select " + COLUMNS + " from agent_model order by provider_name asc, name asc")
        .query(ROW_MAPPER)
        .list();
  }

  public AgentModelDO getByProviderNameAndName(String providerName, String name) {
    return findOne(
        "select "
            + COLUMNS
            + " from agent_model where provider_name = :providerName and name = :name",
        providerName,
        name);
  }

  public AgentModelDO getByProviderNameAndNameForUpdate(String providerName, String name) {
    return findOne(
        "select "
            + COLUMNS
            + " from agent_model where provider_name = :providerName and name = :name"
            + " for update",
        providerName,
        name);
  }

  public int insert(AgentModelDO model) {
    return jdbcClient
        .sql(INSERT_SQL)
        .param("providerName", model.getProviderName())
        .param("name", model.getName())
        .param("modelId", model.getModelId())
        .param("description", model.getDescription())
        .param("configJson", model.getConfigJson())
        .update();
  }

  public int updateByName(AgentModelDO model, long expectedVersion) {
    return jdbcClient
        .sql(UPDATE_BY_NAME_SQL)
        .param("modelId", model.getModelId())
        .param("description", model.getDescription())
        .param("configJson", model.getConfigJson())
        .param("providerName", model.getProviderName())
        .param("name", model.getName())
        .param("expectedVersion", expectedVersion)
        .update();
  }

  public int insertRenamed(String oldName, AgentModelDO model, long expectedVersion) {
    return jdbcClient
        .sql(INSERT_RENAMED_SQL)
        .param("name", model.getName())
        .param("modelId", model.getModelId())
        .param("description", model.getDescription())
        .param("configJson", model.getConfigJson())
        .param("providerName", model.getProviderName())
        .param("oldName", oldName)
        .param("expectedVersion", expectedVersion)
        .update();
  }

  public int updateReferencingAgents(String providerName, String oldName, String newName) {
    return jdbcClient
        .sql(UPDATE_REFERENCING_AGENTS_SQL)
        .param("newName", newName)
        .param("providerName", providerName)
        .param("oldName", oldName)
        .update();
  }

  public int deleteByName(String providerName, String name, long expectedVersion) {
    return jdbcClient
        .sql(
            "delete from agent_model where provider_name = :providerName and name = :name"
                + " and version = :expectedVersion")
        .param("providerName", providerName)
        .param("name", name)
        .param("expectedVersion", expectedVersion)
        .update();
  }

  public long countAgentsByModelName(String providerName, String name) {
    return jdbcClient
        .sql(
            "select count(*) from agent_definition where model_provider_name = :providerName "
                + "and model_name = :name")
        .param("providerName", providerName)
        .param("name", name)
        .query(Long.class)
        .single();
  }

  private AgentModelDO findOne(String sql, String providerName, String name) {
    return jdbcClient
        .sql(sql)
        .param("providerName", providerName)
        .param("name", name)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  private static AgentModelDO mapRow(ResultSet rs, int rowNum) throws SQLException {
    AgentModelDO row = new AgentModelDO();
    row.setProviderName(rs.getString("provider_name"));
    row.setName(rs.getString("name"));
    row.setModelId(rs.getString("model_id"));
    row.setDescription(rs.getString("description"));
    row.setConfigJson(rs.getString("config"));
    row.setVersion(rs.getLong("version"));
    row.setCreateTime(
        PostgresJdbcTypes.toInstant(rs.getObject("create_time", OffsetDateTime.class)));
    row.setUpdateTime(
        PostgresJdbcTypes.toInstant(rs.getObject("update_time", OffsetDateTime.class)));
    return row;
  }
}
