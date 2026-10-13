package fun.fengwk.kkstudio.platform.catalog.provider.repo.impl.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.catalog.provider.repo.impl.model.AgentProviderDO;
import fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** {@code agent_provider} 的 JdbcClient 入口。 */
@Repository
public class AgentProviderJdbcStore {

  private static final String COLUMNS =
      "name, description, provider_type, base_url, credential, config, connection_generation_id, version, "
          + "created_at as create_time, updated_at as update_time";

  private static final String INSERT_SQL =
      """
      insert into agent_provider (
          name, description, provider_type, base_url, credential, config,
          connection_generation_id, created_at, updated_at, version
      ) values (
          :name, :description, :providerType, :baseUrl, :credential,
          cast(:configJson as jsonb), :connectionGenerationId, current_timestamp, current_timestamp, 0
      )
      """;

  private static final String UPDATE_BY_NAME_SQL =
      """
      update agent_provider
      set description = :description, provider_type = :providerType,
          base_url = :baseUrl,
          credential = :credential, config = cast(:configJson as jsonb),
          connection_generation_id = :connectionGenerationId,
          updated_at = greatest(updated_at, current_timestamp), version = version + 1
      where name = :name and version = :expectedVersion
      """;

  private static final RowMapper<AgentProviderDO> ROW_MAPPER = AgentProviderJdbcStore::mapRow;

  private final JdbcClient jdbcClient;

  public AgentProviderJdbcStore(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  public long count() {
    return jdbcClient.sql("select count(*) from agent_provider").query(Long.class).single();
  }

  public List<AgentProviderDO> page(long offset, int limit) {
    return jdbcClient
        .sql(
            "select "
                + COLUMNS
                + " from agent_provider"
                + " order by name asc limit :limit offset :offset")
        .param("limit", limit)
        .param("offset", offset)
        .query(ROW_MAPPER)
        .list();
  }

  public List<AgentProviderDO> listAll() {
    return jdbcClient
        .sql("select " + COLUMNS + " from agent_provider order by name asc")
        .query(ROW_MAPPER)
        .list();
  }

  public AgentProviderDO getByName(String name) {
    return findOne("select " + COLUMNS + " from agent_provider where name = :name", name);
  }

  public AgentProviderDO getByNameForUpdate(String name) {
    return findOne(
        "select " + COLUMNS + " from agent_provider where name = :name for update", name);
  }

  public int insert(AgentProviderDO provider) {
    return jdbcClient
        .sql(INSERT_SQL)
        .param("name", provider.getName())
        .param("description", provider.getDescription())
        .param("providerType", provider.getProviderType())
        .param("baseUrl", provider.getBaseUrl())
        .param("credential", provider.getCredential())
        .param("configJson", provider.getConfigJson())
        .param("connectionGenerationId", provider.getConnectionGenerationId())
        .update();
  }

  public int updateByName(AgentProviderDO provider, long expectedVersion) {
    return jdbcClient
        .sql(UPDATE_BY_NAME_SQL)
        .param("description", provider.getDescription())
        .param("providerType", provider.getProviderType())
        .param("baseUrl", provider.getBaseUrl())
        .param("credential", provider.getCredential())
        .param("configJson", provider.getConfigJson())
        .param("connectionGenerationId", provider.getConnectionGenerationId())
        .param("name", provider.getName())
        .param("expectedVersion", expectedVersion)
        .update();
  }

  public int deleteByName(String name, long expectedVersion) {
    return jdbcClient
        .sql("delete from agent_provider where name = :name and version = :expectedVersion")
        .param("name", name)
        .param("expectedVersion", expectedVersion)
        .update();
  }

  public long countModelsByProviderName(String providerName) {
    return jdbcClient
        .sql("select count(*) from agent_model where provider_name = :providerName")
        .param("providerName", providerName)
        .query(Long.class)
        .single();
  }

  private AgentProviderDO findOne(String sql, String name) {
    return jdbcClient.sql(sql).param("name", name).query(ROW_MAPPER).optional().orElse(null);
  }

  private static AgentProviderDO mapRow(ResultSet rs, int rowNum) throws SQLException {
    AgentProviderDO row = new AgentProviderDO();
    row.setName(rs.getString("name"));
    row.setDescription(rs.getString("description"));
    row.setProviderType(rs.getString("provider_type"));
    row.setBaseUrl(rs.getString("base_url"));
    row.setCredential(rs.getString("credential"));
    row.setConfigJson(rs.getString("config"));
    row.setConnectionGenerationId(rs.getObject("connection_generation_id", UUID.class));
    row.setVersion(rs.getLong("version"));
    row.setCreateTime(
        PostgresJdbcTypes.toInstant(rs.getObject("create_time", OffsetDateTime.class)));
    row.setUpdateTime(
        PostgresJdbcTypes.toInstant(rs.getObject("update_time", OffsetDateTime.class)));
    return row;
  }
}
