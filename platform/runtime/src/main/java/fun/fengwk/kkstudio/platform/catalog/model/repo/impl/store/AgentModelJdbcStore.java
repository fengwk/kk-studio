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

/** {@code agent_model} 的 JdbcClient 入口，含原子改名与组引用维护。 */
@Repository
public class AgentModelJdbcStore {

  private static final String COLUMNS =
      "name, kind, provider_name, model_id, description, config, group_config, version, "
          + "created_at as create_time, updated_at as update_time";

  private static final String INSERT_SQL =
      """
      insert into agent_model (
          name, kind, provider_name, model_id, description, config, group_config,
          created_at, updated_at, version
      ) values (
          :name, :kind, :providerName, :modelId, :description,
          cast(:configJson as jsonb), cast(:groupConfigJson as jsonb),
          current_timestamp, current_timestamp, 0
      )
      """;

  private static final String UPDATE_BY_NAME_SQL =
      """
      update agent_model
      set kind = :kind, provider_name = :providerName, model_id = :modelId,
          description = :description,
          config = cast(:configJson as jsonb), group_config = cast(:groupConfigJson as jsonb),
          updated_at = greatest(updated_at, current_timestamp), version = version + 1
      where name = :name and version = :expectedVersion
      """;

  private static final String RENAME_SQL =
      """
      update agent_model
      set name = :name, kind = :kind, provider_name = :providerName, model_id = :modelId,
          description = :description,
          config = cast(:configJson as jsonb), group_config = cast(:groupConfigJson as jsonb),
          updated_at = greatest(updated_at, current_timestamp), version = version + 1
      where name = :oldName and version = :expectedVersion
      """;

  private static final String UPDATE_GROUPS_REFERENCING_MODEL_SQL =
      """
      update agent_model g
      set group_config = jsonb_set(
              g.group_config,
              '{members}',
              (
                  select jsonb_agg(
                             case
                                 when member.value ->> 'modelName' = :oldName
                                     then jsonb_set(member.value, '{modelName}', to_jsonb(:name::text))
                                 else member.value
                             end
                             order by member.ordinality
                         )
                  from jsonb_array_elements(g.group_config -> 'members')
                       with ordinality as member(value, ordinality)
              )
          ),
          updated_at = greatest(g.updated_at, current_timestamp),
          version = g.version + 1
      where g.kind = 'GROUP'
        and exists (
            select 1
            from jsonb_array_elements(g.group_config -> 'members') as member(value)
            where member.value ->> 'modelName' = :oldName
        )
      """;

  private static final String DELETE_COOLDOWNS_FOR_GROUPS_REFERENCING_MODEL_SQL =
      """
      delete from agent_model_group_cooldown cooldown
      using agent_model g
      where cooldown.group_name = g.name
        and g.kind = 'GROUP'
        and exists (
            select 1
            from jsonb_array_elements(g.group_config -> 'members') as member(value)
            where member.value ->> 'modelName' = :oldName
        )
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
                + " order by name asc limit :limit offset :offset")
        .param("limit", limit)
        .param("offset", offset)
        .query(ROW_MAPPER)
        .list();
  }

  public List<AgentModelDO> listAll() {
    return jdbcClient
        .sql("select " + COLUMNS + " from agent_model order by name asc")
        .query(ROW_MAPPER)
        .list();
  }

  public AgentModelDO getByName(String name) {
    return findOne("select " + COLUMNS + " from agent_model where name = :name", name);
  }

  public AgentModelDO getByNameForUpdate(String name) {
    return findOne("select " + COLUMNS + " from agent_model where name = :name for update", name);
  }

  public int insert(AgentModelDO model) {
    return jdbcClient
        .sql(INSERT_SQL)
        .param("name", model.getName())
        .param("kind", model.getKind())
        .param("providerName", model.getProviderName())
        .param("modelId", model.getModelId())
        .param("description", model.getDescription())
        .param("configJson", model.getConfigJson())
        .param("groupConfigJson", model.getGroupConfigJson())
        .update();
  }

  public int updateByName(AgentModelDO model, long expectedVersion) {
    int updated =
        jdbcClient
            .sql(UPDATE_BY_NAME_SQL)
            .param("kind", model.getKind())
            .param("providerName", model.getProviderName())
            .param("modelId", model.getModelId())
            .param("description", model.getDescription())
            .param("configJson", model.getConfigJson())
            .param("groupConfigJson", model.getGroupConfigJson())
            .param("name", model.getName())
            .param("expectedVersion", expectedVersion)
            .update();
    if (updated == 1 && "GROUP".equals(model.getKind())) {
      // 组配置的任何写操作都使旧冷却失效：删除行比依赖版本比较更直接，也不会留下无界历史。
      deleteGroupCooldowns(model.getName());
    }
    return updated;
  }

  public int rename(String oldName, AgentModelDO model, long expectedVersion) {
    // FK cascade 会改写引用行，但不会推进版本；先锁定这些 Agent 行，改名后显式同步版本事实。
    List<String> referencingAgents =
        jdbcClient
            .sql(
                "select name from agent_definition"
                    + " where model_name = :oldName order by name for update")
            .param("oldName", oldName)
            .query(String.class)
            .list();
    int updated =
        jdbcClient
            .sql(RENAME_SQL)
            .param("name", model.getName())
            .param("kind", model.getKind())
            .param("providerName", model.getProviderName())
            .param("modelId", model.getModelId())
            .param("description", model.getDescription())
            .param("configJson", model.getConfigJson())
            .param("groupConfigJson", model.getGroupConfigJson())
            .param("oldName", oldName)
            .param("expectedVersion", expectedVersion)
            .update();
    if (updated != 1) {
      return updated;
    }
    // 成员引用维护在 group_config JSON；被引用组的配置因此变化，旧冷却必须失效。
    jdbcClient
        .sql(DELETE_COOLDOWNS_FOR_GROUPS_REFERENCING_MODEL_SQL)
        .param("oldName", oldName)
        .update();
    jdbcClient
        .sql(UPDATE_GROUPS_REFERENCING_MODEL_SQL)
        .param("oldName", oldName)
        .param("name", model.getName())
        .update();
    for (String agentName : referencingAgents) {
      jdbcClient
          .sql(
              "update agent_definition"
                  + " set version = version + 1,"
                  + " updated_at = greatest(updated_at, current_timestamp)"
                  + " where name = :name")
          .param("name", agentName)
          .update();
    }
    if ("GROUP".equals(model.getKind())) {
      // 组改名同样是一次配置写：旧名字下的冷却不再属于当前组。
      deleteGroupCooldowns(model.getName());
    }
    return updated;
  }

  public int deleteByName(String name, long expectedVersion) {
    return jdbcClient
        .sql("delete from agent_model where name = :name and version = :expectedVersion")
        .param("name", name)
        .param("expectedVersion", expectedVersion)
        .update();
  }

  public long countAgentsByModelName(String name) {
    return jdbcClient
        .sql("select count(*) from agent_definition where model_name = :name")
        .param("name", name)
        .query(Long.class)
        .single();
  }

  public long countGroupsReferencingModel(String name) {
    return jdbcClient
        .sql(
            """
            select count(*)
            from agent_model g
            where g.kind = 'GROUP'
              and exists (
                  select 1
                  from jsonb_array_elements(g.group_config -> 'members') as member(value)
                  where member.value ->> 'modelName' = :name
              )
            """)
        .param("name", name)
        .query(Long.class)
        .single();
  }

  public long countVariantReferences(String name, String variant) {
    return jdbcClient
        .sql(
            """
            select
                (select count(*) from agent_definition
                 where model_name = :name and variant = :variant)
              + (select count(*)
                 from agent_model g,
                      lateral jsonb_array_elements(g.group_config -> 'members') as member(value)
                 where g.kind = 'GROUP'
                   and member.value ->> 'modelName' = :name
                   and member.value ->> 'variant' = :variant)
            """)
        .param("name", name)
        .param("variant", variant)
        .query(Long.class)
        .single();
  }

  public void deleteGroupCooldowns(String groupName) {
    jdbcClient
        .sql("delete from agent_model_group_cooldown where group_name = :groupName")
        .param("groupName", groupName)
        .update();
  }

  private AgentModelDO findOne(String sql, String name) {
    return jdbcClient.sql(sql).param("name", name).query(ROW_MAPPER).optional().orElse(null);
  }

  private static AgentModelDO mapRow(ResultSet rs, int rowNum) throws SQLException {
    AgentModelDO row = new AgentModelDO();
    row.setName(rs.getString("name"));
    row.setKind(rs.getString("kind"));
    row.setProviderName(rs.getString("provider_name"));
    row.setModelId(rs.getString("model_id"));
    row.setDescription(rs.getString("description"));
    row.setConfigJson(rs.getString("config"));
    row.setGroupConfigJson(rs.getString("group_config"));
    row.setVersion(rs.getLong("version"));
    row.setCreateTime(
        PostgresJdbcTypes.toInstant(rs.getObject("create_time", OffsetDateTime.class)));
    row.setUpdateTime(
        PostgresJdbcTypes.toInstant(rs.getObject("update_time", OffsetDateTime.class)));
    return row;
  }
}
