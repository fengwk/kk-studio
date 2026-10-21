package fun.fengwk.kkstudio.platform.catalog.provider;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.common.page.Pages;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** {@code agent_provider} 的 JdbcClient 仓库；写入以 (name, version) 做原子 CAS。 */
@Repository
public class AgentProviderRepository {

  private static final String COLUMNS =
      "name, description, provider_type, base_url, credential, config, connection_generation_id, version, "
          + "created_at as create_time, updated_at as update_time";

  private static final RowMapper<AgentProvider> ROW_MAPPER =
      (rs, rowNum) ->
          new AgentProvider(
              rs.getString("name"),
              rs.getString("description"),
              ProviderType.fromWireValue(rs.getString("provider_type")),
              rs.getString("base_url"),
              rs.getString("credential"),
              rs.getString("config"),
              rs.getObject("connection_generation_id", UUID.class),
              rs.getLong("version"),
              PostgresJdbcTypes.toInstant(rs.getObject("create_time", OffsetDateTime.class)),
              PostgresJdbcTypes.toInstant(rs.getObject("update_time", OffsetDateTime.class)));

  private final JdbcClient jdbcClient;

  public AgentProviderRepository(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  public Page<AgentProvider> page(PageQuery pageQuery) {
    List<AgentProvider> results =
        jdbcClient
            .sql(
                "select "
                    + COLUMNS
                    + " from agent_provider order by name asc limit :limit offset :offset")
            .param("limit", Pages.queryLimit(pageQuery))
            .param("offset", Pages.queryOffset(pageQuery))
            .query(ROW_MAPPER)
            .list();
    long total = jdbcClient.sql("select count(*) from agent_provider").query(Long.class).single();
    return Pages.page(pageQuery, results, total);
  }

  /** 按 {@code name asc} 列出全部 provider（含凭据），用于一次性导出快照。 */
  public List<AgentProvider> listAll() {
    return jdbcClient
        .sql("select " + COLUMNS + " from agent_provider order by name asc")
        .query(ROW_MAPPER)
        .list();
  }

  public AgentProvider getByName(String name) {
    return findOne("select " + COLUMNS + " from agent_provider where name = :name", name);
  }

  /** 返回有效的 provider 并持有其行锁，直到外层事务结束。 */
  public AgentProvider getByNameForUpdate(String name) {
    return findOne(
        "select " + COLUMNS + " from agent_provider where name = :name for update", name);
  }

  public boolean create(AgentProvider provider) {
    return jdbcClient
            .sql(
                """
                insert into agent_provider (
                    name, description, provider_type, base_url, credential, config,
                    connection_generation_id, created_at, updated_at, version
                ) values (
                    :name, :description, :providerType, :baseUrl, :credential,
                    cast(:configJson as jsonb), :connectionGenerationId, current_timestamp, current_timestamp, 0
                )
                """)
            .param("name", provider.name())
            .param("description", provider.description())
            .param("providerType", provider.providerType().wireValue())
            .param("baseUrl", provider.baseUrl())
            .param("credential", provider.credential())
            .param("configJson", provider.configJson())
            .param("connectionGenerationId", provider.connectionGenerationId())
            .update()
        == 1;
  }

  /** 基于 (name, expectedVersion) 的原子 CAS 更新。行被更新且版本恰好 +1 时返回 true；name 不存在或版本不再匹配时返回 false。 */
  public boolean updateByName(AgentProvider provider, long expectedVersion) {
    return jdbcClient
            .sql(
                """
                update agent_provider
                set description = :description, provider_type = :providerType,
                    base_url = :baseUrl,
                    credential = :credential, config = cast(:configJson as jsonb),
                    connection_generation_id = :connectionGenerationId,
                    updated_at = greatest(updated_at, current_timestamp), version = version + 1
                where name = :name and version = :expectedVersion
                """)
            .param("description", provider.description())
            .param("providerType", provider.providerType().wireValue())
            .param("baseUrl", provider.baseUrl())
            .param("credential", provider.credential())
            .param("configJson", provider.configJson())
            .param("connectionGenerationId", provider.connectionGenerationId())
            .param("name", provider.name())
            .param("expectedVersion", expectedVersion)
            .update()
        == 1;
  }

  /** 基于 (name, expectedVersion) 的硬删除 CAS：行物理删除后同名立即可重建；返回 false 表示 name 缺失或 version 不匹配。 */
  public boolean deleteByName(String name, long expectedVersion) {
    return jdbcClient
            .sql("delete from agent_provider where name = :name and version = :expectedVersion")
            .param("name", name)
            .param("expectedVersion", expectedVersion)
            .update()
        == 1;
  }

  public boolean hasModels(String providerName) {
    return jdbcClient
            .sql("select count(*) from agent_model where provider_name = :providerName")
            .param("providerName", providerName)
            .query(Long.class)
            .single()
        > 0;
  }

  private AgentProvider findOne(String sql, String name) {
    return jdbcClient.sql(sql).param("name", name).query(ROW_MAPPER).optional().orElse(null);
  }
}
