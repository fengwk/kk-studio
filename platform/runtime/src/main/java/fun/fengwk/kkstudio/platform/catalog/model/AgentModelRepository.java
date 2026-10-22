package fun.fengwk.kkstudio.platform.catalog.model;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.common.page.Pages;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelKind;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * {@code agent_model} 的 JdbcClient 仓库：写入以 (name, version) 做原子 CAS；改名在同一事务内同步 Agent 版本、模型组成员引用与相关冷却，
 * 组配置的任何写入都清除该组旧冷却。
 */
@Repository
public class AgentModelRepository {

  private static final String COLUMNS =
      "name, kind, provider_name, model_id, description, config, group_config, version, "
          + "created_at as create_time, updated_at as update_time";

  private static final RowMapper<AgentModel> ROW_MAPPER =
      (rs, rowNum) ->
          new AgentModel(
              rs.getString("name"),
              AgentModelKind.valueOf(rs.getString("kind")),
              rs.getString("provider_name"),
              rs.getString("model_id"),
              rs.getString("description"),
              rs.getString("config"),
              rs.getString("group_config"),
              rs.getLong("version"),
              PostgresJdbcTypes.toInstant(rs.getObject("create_time", OffsetDateTime.class)),
              PostgresJdbcTypes.toInstant(rs.getObject("update_time", OffsetDateTime.class)));

  /** 引用 {@code :oldName} 的组：成员 JSON 中逐项改名，保持成员顺序。 */
  private static final String RENAME_GROUP_MEMBERS_SQL =
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

  private static final String DELETE_COOLDOWNS_OF_GROUPS_REFERENCING_SQL =
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

  private final JdbcClient jdbcClient;

  public AgentModelRepository(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  public Page<AgentModel> page(PageQuery pageQuery) {
    List<AgentModel> results =
        jdbcClient
            .sql(
                "select "
                    + COLUMNS
                    + " from agent_model order by name asc limit :limit offset :offset")
            .param("limit", Pages.queryLimit(pageQuery))
            .param("offset", Pages.queryOffset(pageQuery))
            .query(ROW_MAPPER)
            .list();
    long total = jdbcClient.sql("select count(*) from agent_model").query(Long.class).single();
    return Pages.page(pageQuery, results, total);
  }

  /** 按 {@code name asc} 列出全部 model，用于一次性导出快照。 */
  public List<AgentModel> listAll() {
    return jdbcClient
        .sql("select " + COLUMNS + " from agent_model order by name asc")
        .query(ROW_MAPPER)
        .list();
  }

  public AgentModel getByName(String name) {
    return findOne("select " + COLUMNS + " from agent_model where name = :name", name);
  }

  /** 返回有效的 model 并持有其行锁，直到外层事务结束。 */
  public AgentModel getByNameForUpdate(String name) {
    return findOne("select " + COLUMNS + " from agent_model where name = :name for update", name);
  }

  public boolean create(AgentModel model) {
    return jdbcClient
            .sql(
                """
                insert into agent_model (
                    name, kind, provider_name, model_id, description, config, group_config,
                    created_at, updated_at, version
                ) values (
                    :name, :kind, :providerName, :modelId, :description,
                    cast(:configJson as jsonb), cast(:groupConfigJson as jsonb),
                    current_timestamp, current_timestamp, 0
                )
                """)
            .param("name", model.name())
            .param("kind", model.kind().name())
            .param("providerName", model.providerName())
            .param("modelId", model.modelId())
            .param("description", model.description())
            .param("configJson", model.configJson())
            .param("groupConfigJson", model.groupConfigJson())
            .update()
        == 1;
  }

  /** 基于 (name, expectedVersion) 的原子 CAS 更新；GROUP 更新同时清除本组旧冷却。 */
  public boolean updateByName(AgentModel model, long expectedVersion) {
    boolean updated =
        jdbcClient
                .sql(
                    """
                    update agent_model
                    set provider_name = :providerName, model_id = :modelId,
                        description = :description,
                        config = cast(:configJson as jsonb),
                        group_config = cast(:groupConfigJson as jsonb),
                        updated_at = greatest(updated_at, current_timestamp),
                        version = version + 1
                    where name = :name and version = :expectedVersion
                    """)
                .param("providerName", model.providerName())
                .param("modelId", model.modelId())
                .param("description", model.description())
                .param("configJson", model.configJson())
                .param("groupConfigJson", model.groupConfigJson())
                .param("name", model.name())
                .param("expectedVersion", expectedVersion)
                .update()
            == 1;
    if (updated && model.kind() == AgentModelKind.GROUP) {
      deleteGroupCooldowns(model.name());
    }
    return updated;
  }

  /**
   * 将模型由 oldName 重命名为 model.name：外键级联改写 Agent 引用，并显式推进这些 Agent 的版本；同步模型组成员引用并清除受影响组的冷却。
   * 目标行缺失或版本不匹配抛出运行时异常以触发外层事务回滚。
   */
  public void rename(String oldName, AgentModel model, long expectedVersion) {
    // 外键级联会改写引用行但不推进版本：先锁定这些 Agent 行，改名后显式同步版本事实。
    List<String> referencingAgents =
        jdbcClient
            .sql(
                "select name from agent_definition"
                    + " where model_name = :oldName order by name for update")
            .param("oldName", oldName)
            .query(String.class)
            .list();
    int renamed =
        jdbcClient
            .sql(
                """
                update agent_model
                set name = :name, provider_name = :providerName, model_id = :modelId,
                    description = :description,
                    config = cast(:configJson as jsonb),
                    group_config = cast(:groupConfigJson as jsonb),
                    updated_at = greatest(updated_at, current_timestamp),
                    version = version + 1
                where name = :oldName and version = :expectedVersion
                """)
            .param("name", model.name())
            .param("providerName", model.providerName())
            .param("modelId", model.modelId())
            .param("description", model.description())
            .param("configJson", model.configJson())
            .param("groupConfigJson", model.groupConfigJson())
            .param("oldName", oldName)
            .param("expectedVersion", expectedVersion)
            .update();
    if (renamed != 1) {
      throw new IllegalStateException(
          "rename model failed: "
              + oldName
              + " -> "
              + model.name()
              + ", expectedVersion="
              + expectedVersion);
    }
    // 成员引用存放在 group_config JSON：被引用组的配置因此改变，旧冷却必须失效。
    jdbcClient.sql(DELETE_COOLDOWNS_OF_GROUPS_REFERENCING_SQL).param("oldName", oldName).update();
    jdbcClient
        .sql(RENAME_GROUP_MEMBERS_SQL)
        .param("oldName", oldName)
        .param("name", model.name())
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
    if (model.kind() == AgentModelKind.GROUP) {
      // 组改名同样是一次配置写：旧名字下的冷却不再属于当前组。
      deleteGroupCooldowns(model.name());
    }
  }

  /** 基于 (name, expectedVersion) 的硬删除 CAS；返回 false 表示 identity 缺失或 version 不匹配。 */
  public boolean deleteByName(String name, long expectedVersion) {
    return jdbcClient
            .sql("delete from agent_model where name = :name and version = :expectedVersion")
            .param("name", name)
            .param("expectedVersion", expectedVersion)
            .update()
        == 1;
  }

  public boolean hasAgents(String name) {
    return jdbcClient
            .sql("select count(*) from agent_definition where model_name = :name")
            .param("name", name)
            .query(Long.class)
            .single()
        > 0;
  }

  /** 是否有模型组把该名称作为成员引用。 */
  public boolean hasGroupsReferencing(String name) {
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
            .single()
        > 0;
  }

  /** Agent 或模型组是否引用了该单 Model 的指定 Variant。 */
  public boolean hasVariantReferences(String name, String variant) {
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
            .single()
        > 0;
  }

  private void deleteGroupCooldowns(String groupName) {
    jdbcClient
        .sql("delete from agent_model_group_cooldown where group_name = :groupName")
        .param("groupName", groupName)
        .update();
  }

  private AgentModel findOne(String sql, String name) {
    return jdbcClient.sql(sql).param("name", name).query(ROW_MAPPER).optional().orElse(null);
  }
}
