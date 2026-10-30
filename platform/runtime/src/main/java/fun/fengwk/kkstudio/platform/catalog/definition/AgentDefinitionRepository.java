package fun.fengwk.kkstudio.platform.catalog.definition;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.common.page.Page;
import fun.fengwk.kkstudio.common.page.PageQuery;
import fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/** {@code agent_definition} 的 JdbcClient 仓库；写入以 (name, version) 做原子 CAS，并提供删除前的引用查询。 */
@Repository
public class AgentDefinitionRepository {

  private static final String COLUMNS =
      "name, type, description, system_prompt, model_name, variant, config,"
          + " version, created_at as create_time, updated_at as update_time";

  private static final String INSERT_SQL =
      """
      insert into agent_definition (
          name, type, description, system_prompt, model_name, variant, config,
          created_at, updated_at, version
      ) values (
          :name, :type, :description, :systemPrompt, :modelName,
          :variant, cast(:configJson as jsonb), current_timestamp, current_timestamp, 0
      )
      """;

  private static final String UPDATE_BY_NAME_SQL =
      """
      update agent_definition
      set description = :description, system_prompt = :systemPrompt,
          model_name = :modelName,
          variant = :variant,
          config = cast(:configJson as jsonb),
          updated_at = greatest(updated_at, current_timestamp), version = version + 1
      where name = :name and version = :expectedVersion
      """;

  private static final RowMapper<AgentDefinition> ROW_MAPPER =
      (rs, rowNum) ->
          new AgentDefinition(
              rs.getString("name"),
              AgentDefinitionType.valueOf(rs.getString("type")),
              rs.getString("description"),
              rs.getString("system_prompt"),
              rs.getString("model_name"),
              rs.getString("variant"),
              rs.getString("config"),
              rs.getLong("version"),
              PostgresJdbcTypes.toInstant(rs.getObject("create_time", OffsetDateTime.class)),
              PostgresJdbcTypes.toInstant(rs.getObject("update_time", OffsetDateTime.class)));

  private final JdbcClient jdbcClient;

  public AgentDefinitionRepository(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  public Page<AgentDefinition> page(PageQuery pageQuery) {
    List<AgentDefinition> results =
        jdbcClient
            .sql(
                "select "
                    + COLUMNS
                    + " from agent_definition order by name asc limit :limit offset :offset")
            .param("limit", pageQuery.getPageSize())
            .param("offset", pageQuery.getOffset())
            .query(ROW_MAPPER)
            .list();
    long total = jdbcClient.sql("select count(*) from agent_definition").query(Long.class).single();
    return new Page<>(pageQuery.getPageNumber(), pageQuery.getPageSize(), results, total);
  }

  /** 按 {@code name asc} 列出全部 Agent definition，用于一次性导出快照。 */
  public List<AgentDefinition> listAll() {
    return jdbcClient
        .sql("select " + COLUMNS + " from agent_definition order by name asc")
        .query(ROW_MAPPER)
        .list();
  }

  public AgentDefinition getByName(String name) {
    return findOne("select " + COLUMNS + " from agent_definition where name = :name", name);
  }

  /** 返回 Agent 并持有其行锁，直到外层事务结束。 */
  public AgentDefinition getByNameForUpdate(String name) {
    return findOne(
        "select " + COLUMNS + " from agent_definition where name = :name for update", name);
  }

  /** 是否有其它 Agent 的 subagents allowlist 引用该 Agent。 */
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

  /** 是否有未归档 Project 的工作流阶段把该 Agent 作为执行者。 */
  public boolean existsReferencingProjectWorkflow(String name) {
    return jdbcClient
        .sql(
            """
            select exists (
                select 1
                from project p, jsonb_array_elements(p.workflow -> 'states') state
                where p.archived_at is null
                  and state ->> 'agent' = :name
            )
            """)
        .param("name", name)
        .query(Boolean.class)
        .single();
  }

  /** 是否有任何 Agent 的 {@code config.skills} 引用该全局 Skill。 */
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

  public boolean create(AgentDefinition agent) {
    return jdbcClient
            .sql(INSERT_SQL)
            .param("name", agent.name())
            .param("type", agent.type().name())
            .param("description", agent.description())
            .param("systemPrompt", agent.systemPrompt())
            .param("modelName", agent.modelName())
            .param("variant", agent.variant())
            .param("configJson", agent.configJson())
            .update()
        == 1;
  }

  /** 基于 (name, expectedVersion) 的原子 CAS 更新。 */
  public boolean updateByName(AgentDefinition agent, long expectedVersion) {
    return jdbcClient
            .sql(UPDATE_BY_NAME_SQL)
            .param("description", agent.description())
            .param("systemPrompt", agent.systemPrompt())
            .param("modelName", agent.modelName())
            .param("variant", agent.variant())
            .param("configJson", agent.configJson())
            .param("name", agent.name())
            .param("expectedVersion", expectedVersion)
            .update()
        == 1;
  }

  /** 基于 (name, expectedVersion) 的硬删除 CAS；返回 false 表示 name 缺失或 version 不匹配。 */
  public boolean deleteByName(String name, long expectedVersion) {
    return jdbcClient
            .sql("delete from agent_definition where name = :name and version = :expectedVersion")
            .param("name", name)
            .param("expectedVersion", expectedVersion)
            .update()
        == 1;
  }

  private AgentDefinition findOne(String sql, String name) {
    return jdbcClient.sql(sql).param("name", name).query(ROW_MAPPER).optional().orElse(null);
  }
}
