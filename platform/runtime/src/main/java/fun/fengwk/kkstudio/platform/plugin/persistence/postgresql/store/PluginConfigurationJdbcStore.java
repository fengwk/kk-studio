package fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.model.PluginCatalogStateDO;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.model.PluginConfigurationRowDO;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/** {@code plugin_catalog_state} 与 {@code plugin_configuration_revision} 的 JdbcClient 入口。 */
@Repository
public class PluginConfigurationJdbcStore {

  static final String CAS_EPOCH_SQL =
      """
      update plugin_catalog_state
      set manifest_json = cast(:manifestJson as jsonb),
          epoch = epoch + 1,
          updated_at = greatest(updated_at, current_timestamp)
      where id = 1 and epoch = :expectedEpoch
      """;

  static final String INSERT_REVISION_SQL =
      """
      insert into plugin_configuration_revision
          (plugin_id, revision, code_version, schema_fingerprint, capability_fingerprint, values_json)
      values
          (:pluginId, :revision, :codeVersion, :schemaFingerprint, :capabilityFingerprint,
           :valuesJson)
      """;

  static final String REFERENCE_COUNT_SQL =
      """
      select count(*) from plugin_configuration_revision
      where plugin_id = :pluginId and revision = :revision
          and code_version = :codeVersion
          and schema_fingerprint = :schemaFingerprint
          and capability_fingerprint = :capabilityFingerprint
      """;

  private static final String CATALOG_COLUMNS = "id, epoch, manifest_json";

  private static final String REVISION_COLUMNS =
      "plugin_id, revision, code_version, schema_fingerprint, capability_fingerprint, values_json";

  private static final RowMapper<PluginCatalogStateDO> CATALOG_ROW_MAPPER =
      PluginConfigurationJdbcStore::mapCatalogRow;

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

  public int compareAndSetEpoch(long expectedEpoch, String manifestJson) {
    return jdbcClient
        .sql(CAS_EPOCH_SQL)
        .param("manifestJson", manifestJson)
        .param("expectedEpoch", expectedEpoch)
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
      String capabilityFingerprint) {
    return jdbcClient
        .sql(REFERENCE_COUNT_SQL)
        .param("pluginId", pluginId)
        .param("revision", revision)
        .param("codeVersion", codeVersion)
        .param("schemaFingerprint", schemaFingerprint)
        .param("capabilityFingerprint", capabilityFingerprint)
        .query(Integer.class)
        .single();
  }

  private static PluginCatalogStateDO mapCatalogRow(ResultSet rs, int rowNum) throws SQLException {
    PluginCatalogStateDO row = new PluginCatalogStateDO();
    row.setId(rs.getLong("id"));
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
    row.setValuesJson(rs.getString("values_json"));
    return row;
  }
}
