package fun.fengwk.kkstudio.web.upgrade;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.harness.runtime.history.EntryType;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogBootstrap;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogManifest;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialCodec;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialReference;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.PostgresqlPluginCatalogChangeNotifier;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.PostgresqlPluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.store.PluginConfigurationJdbcStore;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;

import javax.crypto.SecretKey;
import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 已发行 Java 1.1.0 源的真实当前形态：六节 settings、完整 catalog epoch/history、format 2 凭据、当前 Harness 历史与 {@code
 * javaHome} 安装字段。
 *
 * <p>先复用旧 fixture 的跨表业务数据，再把其旧 wire/旧 payload/旧 format 1 凭据规范化成 1.1.0 真实会留下的当前形态；随后用真实 {@link
 * OfflinePluginCatalog} 与生产仓储初始化 epoch 1 catalog，并按真实认证身份写入两条 format 2 凭据。所有值都是测试常量，不读取 任何真实凭据。
 */
final class LegacyV110SourceFixture {

  static final String PROFILE = "java-v1.1.0";

  static final String INTERACTIVE_PLUGIN_ID = "minimax-mavis";

  static final String INTERACTIVE_REGION = "cn";

  static final String INTERACTIVE_PLAINTEXT =
      "{\"accessToken\":\"current-v110-refresh-token\","
          + "\"clientUuid\":\"a1f0c5b8-53c7-4cc3-a7a8-4f9d1b2c3d4e\","
          + "\"obtainedAt\":1760000000000}";

  static final String STATIC_PLUGIN_ID = "comfyui";

  static final String STATIC_PLAINTEXT = "{\"apiKey\":\"current-v110-comfy-api-key\"}";

  /** 第二次真实 CAS 的目标 epoch；源端因此带有 epoch 0、1、2 三段 manifest 历史与 revision 1、2 配置历史。 */
  private static final long ADVANCED_EPOCH = 2L;

  private static final PluginCredentialCodec CODEC = new PluginCredentialCodec();

  private static final Instant CREDENTIAL_TIME = Instant.parse("2026-01-02T03:04:05.678Z");

  private static final Instant CREDENTIAL_REFRESH = Instant.parse("2099-01-01T00:00:00Z");

  private LegacyV110SourceFixture() {}

  /** 写入一份已排空、可直接被 1.1.0 正常启动读取的源库。 */
  static void load(DataSource source, SecretKey sourceKey) throws SQLException {
    LegacyUpgradeSourceFixture.load(source, sourceKey, "openai_responses", false);
    LegacyHarnessHistoryFixture.insert(source);
    LegacyReferenceDataFixture.load(source, "main", sourceKey, "openai_responses");
    normalizeToCurrentShape(source);
    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "update environment set install_config = jsonb_set("
              + "install_config, '{javaHome}', '\"/opt/jdk-21\"'::jsonb)"
              + " where name = 'legacy-environment'");
    }
    initializeCurrentCatalog(source, sourceKey);
  }

  /** 当前 1.1.0 源应有的 provider wire、Harness payload 与 replay state。 */
  private static void normalizeToCurrentShape(DataSource source) throws SQLException {
    try (Connection connection = source.getConnection();
        PreparedStatement selectProviders =
            connection.prepareStatement("select name, provider_type from agent_provider");
        PreparedStatement updateProvider =
            connection.prepareStatement(
                "update agent_provider set provider_type = ? where name = ?");
        PreparedStatement selectEntries =
            connection.prepareStatement(
                "select id, entry_type, payload::text, provider_replay_state::text"
                    + " from harness_entry");
        PreparedStatement updateEntry =
            connection.prepareStatement(
                "update harness_entry set payload = cast(? as jsonb),"
                    + " provider_replay_state = cast(? as jsonb) where id = cast(? as uuid)")) {
      try (ResultSet rows = selectProviders.executeQuery()) {
        while (rows.next()) {
          updateProvider.setString(1, LegacyHarnessHistoryJson.providerType(rows.getString(2)));
          updateProvider.setString(2, rows.getString(1));
          updateProvider.executeUpdate();
        }
      }
      try (ResultSet rows = selectEntries.executeQuery()) {
        while (rows.next()) {
          String payload =
              LegacyHarnessHistoryJson.entryPayload(
                  EntryType.valueOf(rows.getString(2)), rows.getString(3));
          String replay = LegacyHarnessHistoryJson.providerReplayState(rows.getString(4));
          updateEntry.setString(1, payload);
          updateEntry.setString(2, replay);
          updateEntry.setString(3, rows.getString(1));
          updateEntry.executeUpdate();
        }
      }
    }
  }

  /** 用当前真实装配提交源端 epoch 1→2 两次真实 CAS，并按最后 epoch 的真实认证身份写入 format 2 凭据。 */
  private static void initializeCurrentCatalog(DataSource source, SecretKey sourceKey)
      throws SQLException {
    PluginConfigurationRepository repository =
        new PostgresqlPluginConfigurationRepository(
            new PluginConfigurationJdbcStore(JdbcClient.create(source)),
            new PostgresqlPluginCatalogChangeNotifier(new JdbcTemplate(source)));
    try (OfflinePluginCatalog catalog = OfflinePluginCatalog.open(source, SystemSettings.DEFAULT)) {
      StudioPluginRegistry registry = catalog.registry();
      Map<String, String> defaults = defaultValues(registry);
      Map<String, List<String>> secretFields = secretFields(registry);
      PluginCatalogBootstrap.PreparedInitial initial =
          catalog.prepareInitial(defaults, secretFields);
      // 第二次真实 CAS 推进到 epoch 2：身份重新生成、revision 前进，但值仍按同一份 schema 归一化。
      PluginCatalogBootstrap.PreparedInitial advanced =
          catalog.prepareInitial(defaults, secretFields);
      List<PluginConfigurationRow> advancedRows = advancedRows(advanced.configurationRows());
      PluginCatalogManifest advancedManifest = advancedManifest(advanced.manifest());
      try (PluginCatalogSnapshot initialSnapshot = initial.snapshot();
          PluginCatalogSnapshot advancedSnapshot = advanced.snapshot()) {
        Boolean committed =
            new TransactionTemplate(new DataSourceTransactionManager(source))
                .execute(
                    status ->
                        repository.commit(0, initial.configurationRows(), initial.manifest())
                            && repository.commit(1, advancedRows, advancedManifest));
        if (!Boolean.TRUE.equals(committed)) {
          throw new IllegalStateException("the 1.1.0 source catalog epoch CAS did not win");
        }
        insertCredentials(source, sourceKey, referencesOf(advancedRows));
      }
    }
  }

  private static Map<String, String> defaultValues(StudioPluginRegistry registry) {
    Map<String, String> values = new LinkedHashMap<>();
    for (StudioPlugin plugin : registry.plugins()) {
      values.put(plugin.descriptor().pluginId(), plugin.configurationSchema().defaultsJson());
    }
    return values;
  }

  private static Map<String, List<String>> secretFields(StudioPluginRegistry registry) {
    Map<String, List<String>> secretFields = new LinkedHashMap<>();
    for (StudioPlugin plugin : registry.plugins()) {
      secretFields.put(plugin.descriptor().pluginId(), List.of());
    }
    return secretFields;
  }

  private static List<PluginConfigurationRow> advancedRows(List<PluginConfigurationRow> rows) {
    List<PluginConfigurationRow> advanced = new ArrayList<>();
    for (PluginConfigurationRow row : rows) {
      advanced.add(
          new PluginConfigurationRow(
              row.pluginId(),
              ADVANCED_EPOCH,
              row.codeVersion(),
              row.schemaFingerprint(),
              row.capabilityFingerprint(),
              row.authGeneration(),
              row.contextFingerprint(),
              row.configuredSecretFields(),
              row.valuesJson()));
    }
    return advanced;
  }

  private static PluginCatalogManifest advancedManifest(PluginCatalogManifest manifest) {
    Map<String, PluginCatalogManifest.Entry> entries = new LinkedHashMap<>();
    for (Map.Entry<String, PluginCatalogManifest.Entry> entry : manifest.plugins().entrySet()) {
      PluginCatalogManifest.Entry value = entry.getValue();
      entries.put(
          entry.getKey(),
          new PluginCatalogManifest.Entry(
              ADVANCED_EPOCH,
              value.pluginCodeVersion(),
              value.schemaFingerprint(),
              value.capabilityFingerprint(),
              value.authGeneration(),
              value.contextFingerprint()));
    }
    return new PluginCatalogManifest(entries, manifest.catalogFingerprint());
  }

  private static Map<String, PluginCredentialReference> referencesOf(
      List<PluginConfigurationRow> rows) {
    Map<String, PluginCredentialReference> references = new LinkedHashMap<>();
    for (PluginConfigurationRow row : rows) {
      references.put(
          row.pluginId(),
          new PluginCredentialReference(
              row.pluginId(), row.authGeneration(), row.contextFingerprint()));
    }
    return references;
  }

  private static void insertCredentials(
      DataSource source, SecretKey sourceKey, Map<String, PluginCredentialReference> references)
      throws SQLException {
    try (Connection connection = source.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                "insert into plugin_credential (plugin_id, auth_generation,"
                    + " context_fingerprint, encrypted_payload, region, expires_at,"
                    + " next_refresh_at, status, last_refreshed_at, last_refresh_error,"
                    + " refresh_lease_token, refresh_lease_until, version, create_time,"
                    + " update_time) values (?, cast(? as uuid), ?, ?, ?, cast(? as timestamptz),"
                    + " cast(? as timestamptz), ?, cast(? as timestamptz), null, null, null, ?,"
                    + " cast(? as timestamptz), cast(? as timestamptz))")) {
      insertInteractive(insert, sourceKey, references.get(INTERACTIVE_PLUGIN_ID));
      insertStatic(insert, sourceKey, references.get(STATIC_PLUGIN_ID));
    }
  }

  private static void insertInteractive(
      PreparedStatement insert, SecretKey sourceKey, PluginCredentialReference reference)
      throws SQLException {
    if (reference == null) {
      throw new IllegalStateException("the 1.1.0 source catalog lacks the interactive plugin");
    }
    insert.setString(1, reference.pluginId());
    insert.setString(2, reference.authGeneration().toString());
    insert.setString(3, reference.contextFingerprint());
    insert.setBytes(
        4,
        CODEC.encrypt(
            sourceKey,
            reference.pluginId(),
            reference.authGeneration(),
            reference.contextFingerprint(),
            INTERACTIVE_REGION,
            INTERACTIVE_PLAINTEXT));
    insert.setString(5, INTERACTIVE_REGION);
    insert.setString(6, CREDENTIAL_REFRESH.toString());
    insert.setString(7, CREDENTIAL_REFRESH.toString());
    insert.setString(8, PluginCredentialStatus.CONNECTED.name());
    insert.setString(9, CREDENTIAL_TIME.toString());
    insert.setLong(10, 3L);
    insert.setString(11, CREDENTIAL_TIME.toString());
    insert.setString(12, CREDENTIAL_TIME.toString());
    if (insert.executeUpdate() != 1) {
      throw new IllegalStateException("the interactive credential row was not inserted");
    }
  }

  private static void insertStatic(
      PreparedStatement insert, SecretKey sourceKey, PluginCredentialReference reference)
      throws SQLException {
    if (reference == null) {
      throw new IllegalStateException("the 1.1.0 source catalog lacks the static plugin");
    }
    insert.setString(1, reference.pluginId());
    insert.setString(2, reference.authGeneration().toString());
    insert.setString(3, reference.contextFingerprint());
    insert.setBytes(
        4,
        CODEC.encrypt(
            sourceKey,
            reference.pluginId(),
            reference.authGeneration(),
            reference.contextFingerprint(),
            null,
            STATIC_PLAINTEXT));
    insert.setString(5, null);
    insert.setString(6, null);
    insert.setString(7, null);
    insert.setString(8, PluginCredentialStatus.CONNECTED.name());
    insert.setString(9, CREDENTIAL_TIME.toString());
    insert.setLong(10, 7L);
    insert.setString(11, CREDENTIAL_TIME.toString());
    insert.setString(12, CREDENTIAL_TIME.toString());
    if (insert.executeUpdate() != 1) {
      throw new IllegalStateException("the static credential row was not inserted");
    }
  }
}
