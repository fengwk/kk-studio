package fun.fengwk.kkstudio.platform.catalog.definition;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.codec.ExecutionSelectionJsonCodec;
import fun.fengwk.kkstudio.harness.model.HarnessJson;
import fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/** {@code agent} 的 JdbcClient 仓库；写入以 (name, version) 做原子 CAS，并提供删除前的引用查询。 */
@Repository
public class AgentRepository {

  private static final ObjectMapper MAPPER = HarnessJson.STRICT_DECIMAL;

  private static final String COLUMNS =
      "name, type, description, system_prompt, execution, config,"
          + " version, created_at, updated_at";

  private static final String INSERT_SQL =
      """
      insert into agent (
          name, type, description, system_prompt, execution, config,
          created_at, updated_at, version
      ) values (
          :name, :type, :description, :systemPrompt, cast(:executionJson as jsonb),
          cast(:configJson as jsonb), current_timestamp, current_timestamp, 0
      )
      """;

  private static final String UPDATE_BY_NAME_SQL =
      """
      update agent
      set description = :description, system_prompt = :systemPrompt,
          execution = cast(:executionJson as jsonb),
          config = cast(:configJson as jsonb),
          updated_at = greatest(updated_at, current_timestamp), version = version + 1
      where name = :name and version = :expectedVersion
      """;

  private static final RowMapper<Agent> ROW_MAPPER =
      (rs, rowNum) ->
          new Agent(
              rs.getString("name"),
              AgentType.valueOf(rs.getString("type")),
              rs.getString("description"),
              rs.getString("system_prompt"),
              decodeExecution(rs.getString("execution")),
              rs.getString("config"),
              rs.getLong("version"),
              PostgresJdbcTypes.toInstant(rs.getObject("created_at", OffsetDateTime.class)),
              PostgresJdbcTypes.toInstant(rs.getObject("updated_at", OffsetDateTime.class)));

  private final JdbcClient jdbcClient;

  public AgentRepository(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  /** 按 {@code name asc} 列出全部 Agent，用于列表读取与一次性导出快照。 */
  public List<Agent> listAll() {
    return jdbcClient
        .sql("select " + COLUMNS + " from agent order by name asc")
        .query(ROW_MAPPER)
        .list();
  }

  public Agent getByName(String name) {
    return findOne("select " + COLUMNS + " from agent where name = :name", name);
  }

  /** 返回 Agent 并持有其行锁，直到外层事务结束。 */
  public Agent getByNameForUpdate(String name) {
    return findOne("select " + COLUMNS + " from agent where name = :name for update", name);
  }

  /** 是否有其它 Agent 的 subagents allowlist 引用该 Agent。 */
  public boolean existsReferencingSubagent(String name) {
    return jdbcClient
        .sql(
            """
            select exists (
                select 1
                from agent
                where name <> :name
                  and jsonb_exists(config -> 'subagents', :name)
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
                from agent,
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

  public boolean create(Agent agent) {
    return jdbcClient
            .sql(INSERT_SQL)
            .param("name", agent.name())
            .param("type", agent.type().name())
            .param("description", agent.description())
            .param("systemPrompt", agent.systemPrompt())
            .param("executionJson", encodeExecution(agent.execution()))
            .param("configJson", agent.configJson())
            .update()
        == 1;
  }

  /** 基于 (name, expectedVersion) 的原子 CAS 更新。 */
  public boolean updateByName(Agent agent, long expectedVersion) {
    return jdbcClient
            .sql(UPDATE_BY_NAME_SQL)
            .param("description", agent.description())
            .param("systemPrompt", agent.systemPrompt())
            .param("executionJson", encodeExecution(agent.execution()))
            .param("configJson", agent.configJson())
            .param("name", agent.name())
            .param("expectedVersion", expectedVersion)
            .update()
        == 1;
  }

  /** 基于 (name, expectedVersion) 的硬删除 CAS；返回 false 表示 name 缺失或 version 不匹配。 */
  public boolean deleteByName(String name, long expectedVersion) {
    return jdbcClient
            .sql("delete from agent where name = :name and version = :expectedVersion")
            .param("name", name)
            .param("expectedVersion", expectedVersion)
            .update()
        == 1;
  }

  private Agent findOne(String sql, String name) {
    return jdbcClient.sql(sql).param("name", name).query(ROW_MAPPER).optional().orElse(null);
  }

  private static ExecutionSelection decodeExecution(String json) {
    return json == null
        ? null
        : ExecutionSelectionJsonCodec.decode(MAPPER.readTree(json), "agent.execution");
  }

  private static String encodeExecution(ExecutionSelection selection) {
    return selection == null
        ? null
        : MAPPER.writeValueAsString(ExecutionSelectionJsonCodec.encode(selection));
  }
}
