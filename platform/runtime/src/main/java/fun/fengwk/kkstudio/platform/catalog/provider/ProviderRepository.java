package fun.fengwk.kkstudio.platform.catalog.provider;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/** {@code provider} 的 JdbcClient 仓库；写入以 (name, version) 做原子 CAS。 */
@Repository
public class ProviderRepository {

  private static final String COLUMNS =
      "name, description, provider_type, base_url, credential, config, version, created_at, updated_at";

  private static final RowMapper<Provider> ROW_MAPPER =
      (rs, rowNum) ->
          new Provider(
              rs.getString("name"),
              rs.getString("description"),
              ProviderType.fromWireValue(rs.getString("provider_type")),
              rs.getString("base_url"),
              rs.getString("credential"),
              rs.getString("config"),
              rs.getLong("version"),
              PostgresJdbcTypes.toInstant(rs.getObject("created_at", OffsetDateTime.class)),
              PostgresJdbcTypes.toInstant(rs.getObject("updated_at", OffsetDateTime.class)));

  private final JdbcClient jdbcClient;

  public ProviderRepository(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  /** 按 {@code name asc} 列出全部 provider（含凭据），用于一次性导出快照。 */
  public List<Provider> listAll() {
    return jdbcClient
        .sql("select " + COLUMNS + " from provider order by name asc")
        .query(ROW_MAPPER)
        .list();
  }

  public Provider getByName(String name) {
    return findOne("select " + COLUMNS + " from provider where name = :name", name);
  }

  /** 返回有效的 provider 并持有其行锁，直到外层事务结束。 */
  public Provider getByNameForUpdate(String name) {
    return findOne("select " + COLUMNS + " from provider where name = :name for update", name);
  }

  public boolean create(Provider provider) {
    return jdbcClient
            .sql(
                """
                insert into provider (
                    name, description, provider_type, base_url, credential, config,
                    created_at, updated_at, version
                ) values (
                    :name, :description, :providerType, :baseUrl, :credential,
                    cast(:configJson as jsonb), current_timestamp, current_timestamp, 0
                )
                """)
            .param("name", provider.name())
            .param("description", provider.description())
            .param("providerType", provider.providerType().wireValue())
            .param("baseUrl", provider.baseUrl())
            .param("credential", provider.credential())
            .param("configJson", provider.configJson())
            .update()
        == 1;
  }

  /** 基于 (name, expectedVersion) 的原子 CAS 更新。行被更新且版本恰好 +1 时返回 true；name 不存在或版本不再匹配时返回 false。 */
  public boolean updateByName(Provider provider, long expectedVersion) {
    return jdbcClient
            .sql(
                """
                update provider
                set description = :description, provider_type = :providerType,
                    base_url = :baseUrl,
                    credential = :credential, config = cast(:configJson as jsonb),
                    updated_at = greatest(updated_at, current_timestamp), version = version + 1
                where name = :name and version = :expectedVersion
                """)
            .param("description", provider.description())
            .param("providerType", provider.providerType().wireValue())
            .param("baseUrl", provider.baseUrl())
            .param("credential", provider.credential())
            .param("configJson", provider.configJson())
            .param("name", provider.name())
            .param("expectedVersion", expectedVersion)
            .update()
        == 1;
  }

  /** 基于 (name, expectedVersion) 的硬删除 CAS：行物理删除后同名立即可重建；返回 false 表示 name 缺失或 version 不匹配。 */
  public boolean deleteByName(String name, long expectedVersion) {
    return jdbcClient
            .sql("delete from provider where name = :name and version = :expectedVersion")
            .param("name", name)
            .param("expectedVersion", expectedVersion)
            .update()
        == 1;
  }

  public boolean hasModels(String providerName) {
    return jdbcClient
        .sql("select exists (select 1 from model where provider_name = :providerName)")
        .param("providerName", providerName)
        .query(Boolean.class)
        .single();
  }

  private Provider findOne(String sql, String name) {
    return jdbcClient.sql(sql).param("name", name).query(ROW_MAPPER).optional().orElse(null);
  }
}
