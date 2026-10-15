package fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.model.PluginCatalogRevisionDO;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.model.PluginCatalogStateDO;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.model.PluginConfigurationRowDO;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code plugin_catalog_state}、{@code plugin_catalog_revision} 与 {@code
 * plugin_configuration_revision} 的 JdbcClient 入口。
 */
@Repository
public class PluginConfigurationJdbcStore {

  static final String ADVANCE_EPOCH_WITH_REVISION_SQL =
      """
      with advanced as (
          update plugin_catalog_state
          set epoch = epoch + 1,
              updated_at = greatest(updated_at, current_timestamp)
          where id = 1 and epoch = :expectedEpoch
          returning epoch
      )
      insert into plugin_catalog_revision (epoch, manifest_json)
      select advanced.epoch, cast(:manifestJson as jsonb) from advanced
      """;

  static final String INSERT_REVISION_SQL =
      """
      insert into plugin_configuration_revision
          (plugin_id, revision, code_version, schema_fingerprint, capability_fingerprint,
           auth_generation, context_fingerprint, configured_secret_fields, values_json)
      values
          (:pluginId, :revision, :codeVersion, :schemaFingerprint, :capabilityFingerprint,
           :authGeneration, :contextFingerprint, cast(:configuredSecretFields as text[]),
           :valuesJson)
      """;

  static final String REFERENCE_COUNT_SQL =
      """
      select count(*) from plugin_configuration_revision
      where plugin_id = :pluginId and revision = :revision
          and code_version = :codeVersion
          and schema_fingerprint = :schemaFingerprint
          and capability_fingerprint = :capabilityFingerprint
          and auth_generation = :authGeneration
          and context_fingerprint = :contextFingerprint
      """;

  private static final String CATALOG_COLUMNS = "id, epoch";

  private static final String CATALOG_REVISION_COLUMNS = "epoch, manifest_json";

  private static final String REVISION_COLUMNS =
      "plugin_id, revision, code_version, schema_fingerprint, capability_fingerprint, "
          + "auth_generation, context_fingerprint, configured_secret_fields, values_json";

  private static final RowMapper<PluginCatalogStateDO> CATALOG_ROW_MAPPER =
      PluginConfigurationJdbcStore::mapCatalogRow;

  private static final RowMapper<PluginCatalogRevisionDO> CATALOG_REVISION_ROW_MAPPER =
      PluginConfigurationJdbcStore::mapCatalogRevisionRow;

  private static final RowMapper<PluginConfigurationRowDO> REVISION_ROW_MAPPER =
      PluginConfigurationJdbcStore::mapRevisionRow;

  private final JdbcClient jdbcClient;

  public PluginConfigurationJdbcStore(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  public PluginCatalogStateDO getCatalog() {
    return jdbcClient
        .sql("select " + CATALOG_COLUMNS + " from plugin_catalog_state where id = 1")
        .query(CATALOG_ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  /** 在同一事务内取权威基线的排他读：并发提交只能有一个先读到旧 epoch。 */
  public PluginCatalogStateDO lockCatalog() {
    return jdbcClient
        .sql("select " + CATALOG_COLUMNS + " from plugin_catalog_state where id = 1 for update")
        .query(CATALOG_ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  /** 按 epoch 读取不可变完整 manifest 历史行；不存在时返回 null。 */
  public PluginCatalogRevisionDO getCatalogRevision(long epoch) {
    return jdbcClient
        .sql(
            "select "
                + CATALOG_REVISION_COLUMNS
                + " from plugin_catalog_revision where epoch = :epoch")
        .param("epoch", epoch)
        .query(CATALOG_REVISION_ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  /**
   * 单条语句完成 state 指针 CAS 与该 epoch 不可变 manifest 历史的新建。
   *
   * <p>两条写入处在同一条语句中，因此 state.epoch 对 revision.epoch 的即时外键在语句末校验时同时成立； CAS 未命中时 {@code advanced}
   * 为空、插入 0 行，败方不会留下任何历史。
   *
   * @return 新建的历史行数：1 表示 CAS 命中并已写入该 epoch 的 manifest，0 表示 expectedEpoch 已过期
   */
  public int advanceEpochAndInsertRevision(long expectedEpoch, String manifestJson) {
    return jdbcClient
        .sql(ADVANCE_EPOCH_WITH_REVISION_SQL)
        .param("expectedEpoch", expectedEpoch)
        .param("manifestJson", manifestJson)
        .update();
  }

  public int insertRevision(PluginConfigurationRowDO row) {
    return jdbcClient
        .sql(INSERT_REVISION_SQL)
        .param("pluginId", row.getPluginId())
        .param("revision", row.getRevision())
        .param("codeVersion", row.getCodeVersion())
        .param("schemaFingerprint", row.getSchemaFingerprint())
        .param("capabilityFingerprint", row.getCapabilityFingerprint())
        .param("authGeneration", row.getAuthGeneration())
        .param("contextFingerprint", row.getContextFingerprint())
        .param("configuredSecretFields", toTextArrayLiteral(row.getConfiguredSecretFields()))
        .param("valuesJson", row.getValuesJson())
        .update();
  }

  public PluginConfigurationRowDO getRevision(String pluginId, long revision) {
    return jdbcClient
        .sql(
            "select "
                + REVISION_COLUMNS
                + " from plugin_configuration_revision"
                + " where plugin_id = :pluginId and revision = :revision")
        .param("pluginId", pluginId)
        .param("revision", revision)
        .query(REVISION_ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public int countMatchingReference(
      String pluginId,
      long revision,
      String codeVersion,
      String schemaFingerprint,
      String capabilityFingerprint,
      UUID authGeneration,
      String contextFingerprint) {
    return jdbcClient
        .sql(REFERENCE_COUNT_SQL)
        .param("pluginId", pluginId)
        .param("revision", revision)
        .param("codeVersion", codeVersion)
        .param("schemaFingerprint", schemaFingerprint)
        .param("capabilityFingerprint", capabilityFingerprint)
        .param("authGeneration", authGeneration)
        .param("contextFingerprint", contextFingerprint)
        .query(Integer.class)
        .single();
  }

  /**
   * 把字段名列表编码为 PostgreSQL {@code text[]} 字面量（元素加双引号并转义 {@code \} 与 {@code "}）。
   *
   * <p>显式字面量加 {@code cast(... as text[])} 不依赖驱动对 Java 数组的隐式支持，空列表编码为 {@code {}}。
   */
  static String toTextArrayLiteral(List<String> fields) {
    StringBuilder literal = new StringBuilder("{");
    for (int index = 0; index < fields.size(); index++) {
      if (index > 0) {
        literal.append(',');
      }
      literal.append('"');
      for (int i = 0; i < fields.get(index).length(); i++) {
        char character = fields.get(index).charAt(i);
        if (character == '\\' || character == '"') {
          literal.append('\\');
        }
        literal.append(character);
      }
      literal.append('"');
    }
    return literal.append('}').toString();
  }

  private static PluginCatalogStateDO mapCatalogRow(ResultSet rs, int rowNum) throws SQLException {
    PluginCatalogStateDO row = new PluginCatalogStateDO();
    row.setId(rs.getLong("id"));
    row.setEpoch(rs.getLong("epoch"));
    return row;
  }

  private static PluginCatalogRevisionDO mapCatalogRevisionRow(ResultSet rs, int rowNum)
      throws SQLException {
    PluginCatalogRevisionDO row = new PluginCatalogRevisionDO();
    row.setEpoch(rs.getLong("epoch"));
    row.setManifestJson(rs.getString("manifest_json"));
    return row;
  }

  private static PluginConfigurationRowDO mapRevisionRow(ResultSet rs, int rowNum)
      throws SQLException {
    PluginConfigurationRowDO row = new PluginConfigurationRowDO();
    row.setPluginId(rs.getString("plugin_id"));
    row.setRevision(rs.getLong("revision"));
    row.setCodeVersion(rs.getString("code_version"));
    row.setSchemaFingerprint(rs.getString("schema_fingerprint"));
    row.setCapabilityFingerprint(rs.getString("capability_fingerprint"));
    row.setAuthGeneration(rs.getObject("auth_generation", UUID.class));
    row.setContextFingerprint(rs.getString("context_fingerprint"));
    row.setConfiguredSecretFields(readTextArray(rs, "configured_secret_fields"));
    row.setValuesJson(rs.getString("values_json"));
    return row;
  }

  /** 读取 {@code text[]} 列；空数组与 NULL 都归一为空列表。 */
  private static List<String> readTextArray(ResultSet rs, String column) throws SQLException {
    Array array = rs.getArray(column);
    if (array == null) {
      return List.of();
    }
    try {
      String[] elements = (String[]) array.getArray();
      return List.of(elements);
    } finally {
      array.free();
    }
  }
}
