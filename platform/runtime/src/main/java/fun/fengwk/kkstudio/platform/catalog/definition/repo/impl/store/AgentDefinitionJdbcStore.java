package fun.fengwk.kkstudio.platform.catalog.definition.repo.impl.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.catalog.definition.repo.impl.model.AgentDefinitionDO;
import fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/** {@code agent_definition} 的 JdbcClient 入口。 */
@Repository
public class AgentDefinitionJdbcStore {

  private static final String COLUMNS =
      "name, type, description, system_prompt, model_provider_name, model_name, variant, config,"
          + " version, created_at as create_time, updated_at as update_time";

  private static final String INSERT_SQL =
      """
      insert into agent_definition (
          name, type, description, system_prompt, model_provider_name, model_name, variant, config,
          created_at, updated_at, version
      ) values (
          :name, :type, :description, :systemPrompt, :modelProviderName, :modelName,
          :variant, cast(:configJson as jsonb), current_timestamp, current_timestamp, 0
      )
      """;

  private static final String UPDATE_BY_NAME_SQL =
      """
      update agent_definition
      set description = :description, system_prompt = :systemPrompt,
          model_provider_name = :modelProviderName, model_name = :modelName,
          variant = :variant,
          config = cast(:configJson as jsonb),
          updated_at = greatest(updated_at, current_timestamp), version = version + 1
      where name = :name and version = :expectedVersion
      """;

  private static final RowMapper<AgentDefinitionDO> ROW_MAPPER = AgentDefinitionJdbcStore::mapRow;

  private final JdbcClient jdbcClient;

  public AgentDefinitionJdbcStore(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  public long count() {
    return jdbcClient.sql("select count(*) from agent_definition").query(Long.class).single();
  }

  public List<AgentDefinitionDO> page(long offset, int limit) {
    return jdbcClient
        .sql(
            "select "
                + COLUMNS
                + " from agent_definition"
                + " order by name asc limit :limit offset :offset")
        .param("limit", limit)
        .param("offset", offset)
        .query(ROW_MAPPER)
        .list();
  }

  public List<AgentDefinitionDO> listAll() {
    return jdbcClient
        .sql("select " + COLUMNS + " from agent_definition order by name asc")
        .query(ROW_MAPPER)
        .list();
  }

  public AgentDefinitionDO getByName(String name) {
    return findOne("select " + COLUMNS + " from agent_definition where name = :name", name);
  }

  public AgentDefinitionDO getByNameForUpdate(String name) {
    return findOne(
        "select " + COLUMNS + " from agent_definition where name = :name for update", name);
  }

  public boolean existsReferencingSubagent(String name) {
    return jdbcClient
        .sql(
            """
            select exists (
                select 1
                from agent_definition
                where name <> :name
                  and jsonb_exists(config -> 'subagents', :name)
            )
            """)
        .param("name", name)
        .query(Boolean.class)
        .single();
  }

  public boolean existsReferencingSkill(String packageName, String skillName) {
    return jdbcClient
        .sql(
            """
            select exists (
                select 1
                from agent_definition,
                     lateral jsonb_array_elements(
                         case when jsonb_typeof(config -> 'skills') = 'array'
                              then config -> 'skills'
                              else '[]'::jsonb
                         end
                     ) as skill
                where skill ->> 'packageName' = :packageName
                  and skill ->> 'name' = :skillName
            )
            """)
        .param("packageName", packageName)
        .param("skillName", skillName)
        .query(Boolean.class)
        .single();
  }

  public int insert(AgentDefinitionDO agent) {
    return jdbcClient
        .sql(INSERT_SQL)
        .param("name", agent.getName())
        .param("type", agent.getType())
        .param("description", agent.getDescription())
        .param("systemPrompt", agent.getSystemPrompt())
        .param("modelProviderName", agent.getModelProviderName())
        .param("modelName", agent.getModelName())
        .param("variant", agent.getVariant())
        .param("configJson", agent.getConfigJson())
        .update();
  }

  public int updateByName(AgentDefinitionDO agent, long expectedVersion) {
    return jdbcClient
        .sql(UPDATE_BY_NAME_SQL)
        .param("description", agent.getDescription())
        .param("systemPrompt", agent.getSystemPrompt())
        .param("modelProviderName", agent.getModelProviderName())
        .param("modelName", agent.getModelName())
        .param("variant", agent.getVariant())
        .param("configJson", agent.getConfigJson())
        .param("name", agent.getName())
        .param("expectedVersion", expectedVersion)
        .update();
  }

  public int deleteByName(String name, long expectedVersion) {
    return jdbcClient
        .sql("delete from agent_definition where name = :name and version = :expectedVersion")
        .param("name", name)
        .param("expectedVersion", expectedVersion)
        .update();
  }

  private AgentDefinitionDO findOne(String sql, String name) {
    return jdbcClient.sql(sql).param("name", name).query(ROW_MAPPER).optional().orElse(null);
  }

  private static AgentDefinitionDO mapRow(ResultSet rs, int rowNum) throws SQLException {
    AgentDefinitionDO row = new AgentDefinitionDO();
    row.setName(rs.getString("name"));
    row.setType(rs.getString("type"));
    row.setDescription(rs.getString("description"));
    row.setSystemPrompt(rs.getString("system_prompt"));
    row.setModelProviderName(rs.getString("model_provider_name"));
    row.setModelName(rs.getString("model_name"));
    row.setVariant(rs.getString("variant"));
    row.setConfigJson(rs.getString("config"));
    row.setVersion(rs.getLong("version"));
    row.setCreateTime(
        PostgresJdbcTypes.toInstant(rs.getObject("create_time", OffsetDateTime.class)));
    row.setUpdateTime(
        PostgresJdbcTypes.toInstant(rs.getObject("update_time", OffsetDateTime.class)));
    return row;
  }
}
