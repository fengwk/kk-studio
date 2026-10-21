package fun.fengwk.kkstudio.platform.catalog.model;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.common.page.Pages;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * {@code agent_model} 的 JdbcClient 仓库；写入以 (providerName, name, version) 做原子 CAS，改名在同一事务内改写 Agent
 * 引用。
 */
@Repository
public class AgentModelRepository {

  private static final String COLUMNS =
      "provider_name, name, model_id, description, config, version, "
          + "created_at as create_time, updated_at as update_time";

  private static final RowMapper<AgentModel> ROW_MAPPER =
      (rs, rowNum) ->
          new AgentModel(
              rs.getString("provider_name"),
              rs.getString("name"),
              rs.getString("model_id"),
              rs.getString("description"),
              rs.getString("config"),
              rs.getLong("version"),
              PostgresJdbcTypes.toInstant(rs.getObject("create_time", OffsetDateTime.class)),
              PostgresJdbcTypes.toInstant(rs.getObject("update_time", OffsetDateTime.class)));

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
                    + " from agent_model order by provider_name asc, name asc"
                    + " limit :limit offset :offset")
            .param("limit", Pages.queryLimit(pageQuery))
            .param("offset", Pages.queryOffset(pageQuery))
            .query(ROW_MAPPER)
            .list();
    long total = jdbcClient.sql("select count(*) from agent_model").query(Long.class).single();
    return Pages.page(pageQuery, results, total);
  }

  /** 按 {@code provider_name asc, name asc} 列出全部 model，用于一次性导出快照。 */
  public List<AgentModel> listAll() {
    return jdbcClient
        .sql("select " + COLUMNS + " from agent_model order by provider_name asc, name asc")
        .query(ROW_MAPPER)
        .list();
  }

  /** Model 名在同一个 provider 内唯一，而非全局唯一。 */
  public AgentModel getByProviderNameAndName(String providerName, String name) {
    return findOne(
        "select "
            + COLUMNS
            + " from agent_model where provider_name = :providerName and name = :name",
        providerName,
        name);
  }

  /** 返回有效的 model 并持有其行锁，直到外层事务结束。 */
  public AgentModel getByProviderNameAndNameForUpdate(String providerName, String name) {
    return findOne(
        "select "
            + COLUMNS
            + " from agent_model where provider_name = :providerName and name = :name"
            + " for update",
        providerName,
        name);
  }

  public boolean create(AgentModel model) {
    return jdbcClient
            .sql(
                """
                insert into agent_model (
                    provider_name, name, model_id, description, config,
                    created_at, updated_at, version
                ) values (
                    :providerName, :name, :modelId, :description, cast(:configJson as jsonb),
                    current_timestamp, current_timestamp, 0
                )
                """)
            .param("providerName", model.providerName())
            .param("name", model.name())
            .param("modelId", model.modelId())
            .param("description", model.description())
            .param("configJson", model.configJson())
            .update()
        == 1;
  }

  /** 基于 (providerName, name, expectedVersion) 的原子 CAS 更新。 */
  public boolean updateByName(AgentModel model, long expectedVersion) {
    return jdbcClient
            .sql(
                """
                update agent_model
                set model_id = :modelId, description = :description,
                    config = cast(:configJson as jsonb),
                    updated_at = greatest(updated_at, current_timestamp), version = version + 1
                where provider_name = :providerName and name = :name
                  and version = :expectedVersion
                """)
            .param("modelId", model.modelId())
            .param("description", model.description())
            .param("configJson", model.configJson())
            .param("providerName", model.providerName())
            .param("name", model.name())
            .param("expectedVersion", expectedVersion)
            .update()
        == 1;
  }

  /**
   * 将模型由 oldName 重命名为 model.name：插入新行、把引用它的 agent_definition 改到新名称、删除旧行。任一步骤影响行数不符合预期均抛出运行时异常以
   * 触发外层事务回滚。
   */
  public void rename(String oldName, AgentModel model, long expectedVersion) {
    int inserted =
        jdbcClient
            .sql(
                """
                insert into agent_model (
                    provider_name, name, model_id, description, config,
                    created_at, updated_at, version
                )
                select
                    provider_name, :name, :modelId, :description, cast(:configJson as jsonb),
                    created_at, greatest(updated_at, current_timestamp), version + 1
                from agent_model
                where provider_name = :providerName and name = :oldName
                  and version = :expectedVersion
                """)
            .param("name", model.name())
            .param("modelId", model.modelId())
            .param("description", model.description())
            .param("configJson", model.configJson())
            .param("providerName", model.providerName())
            .param("oldName", oldName)
            .param("expectedVersion", expectedVersion)
            .update();
    if (inserted != 1) {
      throw new IllegalStateException(
          "insert target model failed during rename: "
              + oldName
              + " -> "
              + model.name()
              + ", expectedVersion="
              + expectedVersion);
    }
    jdbcClient
        .sql(
            """
            update agent_definition
            set model_name = :newName,
                updated_at = greatest(updated_at, current_timestamp),
                version = version + 1
            where model_provider_name = :providerName and model_name = :oldName
            """)
        .param("newName", model.name())
        .param("providerName", model.providerName())
        .param("oldName", oldName)
        .update();
    if (!deleteByName(model.providerName(), oldName, expectedVersion)) {
      throw new IllegalStateException(
          "delete old model failed during rename: "
              + oldName
              + ", expectedVersion="
              + expectedVersion);
    }
  }

  /**
   * 基于 (providerName, name, expectedVersion) 的硬删除 CAS：行物理删除后同名立即可重建；返回 false 表示 identity 缺失或
   * version 不匹配。
   */
  public boolean deleteByName(String providerName, String name, long expectedVersion) {
    return jdbcClient
            .sql(
                "delete from agent_model where provider_name = :providerName and name = :name"
                    + " and version = :expectedVersion")
            .param("providerName", providerName)
            .param("name", name)
            .param("expectedVersion", expectedVersion)
            .update()
        == 1;
  }

  public boolean hasAgents(String providerName, String name) {
    return jdbcClient
            .sql(
                "select count(*) from agent_definition where model_provider_name = :providerName"
                    + " and model_name = :name")
            .param("providerName", providerName)
            .param("name", name)
            .query(Long.class)
            .single()
        > 0;
  }

  private AgentModel findOne(String sql, String providerName, String name) {
    return jdbcClient
        .sql(sql)
        .param("providerName", providerName)
        .param("name", name)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }
}
