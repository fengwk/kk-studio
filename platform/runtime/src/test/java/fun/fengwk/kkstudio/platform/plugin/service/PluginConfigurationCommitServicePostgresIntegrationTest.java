package fun.fengwk.kkstudio.platform.plugin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.Driver;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.StringSchema;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthHandler;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginConfiguration;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigurationState;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialIntegrityException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialMaterial;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableReason;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.PluginKeyUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginAuthContextFingerprint;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogFingerprint;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogManifest;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRecord;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRefreshTrigger;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;
import fun.fengwk.kkstudio.platform.plugin.credential.DatabasePluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialCodec;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialKeyLoader;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialReference;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialRefreshDispatcher;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.PostgresqlPluginCatalogChangeNotifier;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.PostgresqlPluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.PostgresqlPluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.store.PluginConfigurationJdbcStore;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.store.PluginCredentialJdbcStore;
import fun.fengwk.kkstudio.platform.plugin.testing.TestPluginFixtures;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link PluginConfigurationCommitService} 的真实 PostgreSQL 写入契约测试。
 *
 * <p>写入断言只在这里对真实数据库成立：唯一提交入口把普通 revision、秘密替换/清除与完整 manifest 放在同一 epoch CAS
 * 事务内，凭据行只以条件语句写入或删除。覆盖首连写入、纯 KEEP 不触碰凭据行、SET 轮换代际并重写密文、CLEAR/断连删除行且不需要主密钥、 缺主密钥时 SET fail closed
 * 且没有任何部分写入、陈旧坐标冲突，以及提交成功后调度唤醒在提交后触发。
 */
class PluginConfigurationCommitServicePostgresIntegrationTest {

  private static final String STATIC_PLUGIN_ID = "static.key.plugin";
  private static final String DEEP_LINK_PLUGIN_ID = "deep.link.plugin";
  private static final String REGION = "CN";
  private static final String STATIC_PAYLOAD_SECRET = "static-api-key-marker-13579";
  private static final String FRESH_PAYLOAD_SECRET = "fresh-api-key-marker-24680";

  /** 已补齐默认值的规范配置文本：与 schema.resolveValues 的输出恒等，可直接作为权威历史值。 */
  private static final String BOOTSTRAP_VALUES =
      "{\"endpoint\":\"https://api.example.com\",\"label\":\"default\"}";

  private static final String UPDATED_VALUES =
      "{\"endpoint\":\"https://api.example.com\",\"label\":\"east\"}";

  private static DataSource dataSource;
  private static JdbcClient jdbcClient;

  private final Instant now = Instant.parse("2026-10-01T12:00:00Z");
  private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
  private final PluginCredentialCodec codec = new PluginCredentialCodec();

  @TempDir Path tempDir;

  private StudioPluginRegistry registry;
  private PlatformTransactionManager transactionManager;
  private PluginConfigurationRepository configurationRepository;
  private PluginCredentialRepository credentialRepository;
  private DatabasePluginCredentialStore credentialStore;
  private PluginConfigurationCommitService commitService;
  private PluginConfigurationCommitService keylessCommitService;
  private final AtomicInteger dispatcherWakes = new AtomicInteger();
  private final AtomicInteger catalogWakes = new AtomicInteger();

  private StaticKeyPlugin staticPlugin;
  private DeepLinkPlugin deepLinkPlugin;
  private String staticSchemaFingerprint;
  private String staticContextFingerprint;
  private String deepLinkSchemaFingerprint;
  private String deepLinkContextFingerprint;
  private UUID staticGeneration;
  private UUID deepLinkGeneration;

  @BeforeAll
  static void beforeAll() throws SQLException {
    try (Connection conn = PostgresSchemaSupport.newConnection()) {
      PostgresSchemaSupport.resetDatabase(conn);
      PostgresSchemaSupport.applyBaseline(conn);
    }
    dataSource =
        new SimpleDriverDataSource(
            new Driver(),
            PostgresSchemaSupport.POSTGRES.getJdbcUrl(),
            PostgresSchemaSupport.POSTGRES.getUsername(),
            PostgresSchemaSupport.POSTGRES.getPassword());
    jdbcClient = JdbcClient.create(dataSource);
  }

  @BeforeEach
  void setUp() throws IOException, SQLException {
    try (Connection conn = PostgresSchemaSupport.newConnection()) {
      PostgresSchemaSupport.resetDatabase(conn);
      PostgresSchemaSupport.applyBaseline(conn);
    }

    staticPlugin = new StaticKeyPlugin();
    deepLinkPlugin = new DeepLinkPlugin();
    registry = new StudioPluginRegistry(List.of(staticPlugin, deepLinkPlugin));
    transactionManager = new DataSourceTransactionManager(dataSource);
    configurationRepository =
        new PostgresqlPluginConfigurationRepository(
            new PluginConfigurationJdbcStore(jdbcClient),
            new PostgresqlPluginCatalogChangeNotifier(new JdbcTemplate(dataSource)));
    credentialRepository =
        new PostgresqlPluginCredentialRepository(new PluginCredentialJdbcStore(jdbcClient));

    Path keyFile = tempDir.resolve("commit-test.key");
    Files.write(keyFile, "12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8));
    Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
    PluginCredentialKeyLoader keyLoader =
        new PluginCredentialKeyLoader(keyFile.toAbsolutePath().toString());
    PluginCredentialKeyLoader missingKeyLoader =
        new PluginCredentialKeyLoader(tempDir.resolve("missing.key").toAbsolutePath().toString());

    credentialStore =
        new DatabasePluginCredentialStore(
            credentialRepository, configurationRepository, codec, keyLoader, clock);
    commitService = commitService(keyLoader, credentialStore);
    keylessCommitService = commitService(missingKeyLoader, credentialStore);

    seedCatalog();
    dispatcherWakes.set(0);
    catalogWakes.set(0);
  }

  private PluginConfigurationCommitService commitService(
      PluginCredentialKeyLoader keyLoader, DatabasePluginCredentialStore store) {
    return new PluginConfigurationCommitService(
        registry,
        configurationRepository,
        credentialRepository,
        codec,
        keyLoader,
        new PluginContextFactory(store, () -> null),
        List::of,
        List::of,
        transactionManager,
        clock,
        dispatcherWakes::incrementAndGet,
        catalogWakes::incrementAndGet);
  }

  /** 以真实快照指纹建立 epoch 1 的权威 catalog：两个已安装 Plugin 各有一条 revision 1 历史。 */
  private void seedCatalog() {
    assertEquals(
        BOOTSTRAP_VALUES,
        staticPlugin.configurationSchema().resolveValues(BOOTSTRAP_VALUES),
        "the seeded values must already be canonical");
    staticSchemaFingerprint =
        PluginCatalogFingerprint.schema(
            staticPlugin.configurationSchema(), staticPlugin.authSpec());
    deepLinkSchemaFingerprint =
        PluginCatalogFingerprint.schema(
            deepLinkPlugin.configurationSchema(), deepLinkPlugin.authSpec());
    staticContextFingerprint =
        PluginAuthContextFingerprint.derive(
            STATIC_PLUGIN_ID,
            StaticKeyPlugin.VERSION,
            staticSchemaFingerprint,
            staticPlugin.authSpec(),
            JsonValues.readTree(BOOTSTRAP_VALUES));
    deepLinkContextFingerprint =
        PluginAuthContextFingerprint.derive(
            DEEP_LINK_PLUGIN_ID,
            DeepLinkPlugin.VERSION,
            deepLinkSchemaFingerprint,
            deepLinkPlugin.authSpec(),
            JsonValues.readTree("{}"));
    staticGeneration = UUID.fromString("11111111-1111-1111-1111-111111111111");
    deepLinkGeneration = UUID.fromString("22222222-2222-2222-2222-222222222222");

    Map<String, PluginConfigurationState> states =
        Map.of(
            STATIC_PLUGIN_ID,
            new PluginConfigurationState(
                new PluginConfig(1L, BOOTSTRAP_VALUES),
                new PluginCredentialReference(
                    STATIC_PLUGIN_ID, staticGeneration, staticContextFingerprint)),
            DEEP_LINK_PLUGIN_ID,
            new PluginConfigurationState(
                new PluginConfig(1L, "{}"),
                new PluginCredentialReference(
                    DEEP_LINK_PLUGIN_ID, deepLinkGeneration, deepLinkContextFingerprint)));
    try (PluginCatalogSnapshot snapshot =
        PluginCatalogSnapshot.build(
            1L,
            registry,
            states,
            new PluginContextFactory(credentialStore, () -> null)::create,
            List.of(),
            List.of())) {
      PluginCatalogManifest manifest =
          new PluginCatalogManifest(
              Map.of(
                  STATIC_PLUGIN_ID,
                  new PluginCatalogManifest.Entry(
                      1L,
                      StaticKeyPlugin.VERSION,
                      staticSchemaFingerprint,
                      snapshot.capabilityFingerprints().get(STATIC_PLUGIN_ID),
                      staticGeneration,
                      staticContextFingerprint),
                  DEEP_LINK_PLUGIN_ID,
                  new PluginCatalogManifest.Entry(
                      1L,
                      DeepLinkPlugin.VERSION,
                      deepLinkSchemaFingerprint,
                      snapshot.capabilityFingerprints().get(DEEP_LINK_PLUGIN_ID),
                      deepLinkGeneration,
                      deepLinkContextFingerprint)),
              snapshot.catalogFingerprint());
      assertTrue(
          Boolean.TRUE.equals(
              new TransactionTemplate(transactionManager)
                  .execute(
                      status ->
                          configurationRepository.commit(
                              0L,
                              List.of(
                                  new PluginConfigurationRow(
                                      STATIC_PLUGIN_ID,
                                      1L,
                                      StaticKeyPlugin.VERSION,
                                      staticSchemaFingerprint,
                                      snapshot.capabilityFingerprints().get(STATIC_PLUGIN_ID),
                                      staticGeneration,
                                      staticContextFingerprint,
                                      List.of("apiKey"),
                                      BOOTSTRAP_VALUES),
                                  new PluginConfigurationRow(
                                      DEEP_LINK_PLUGIN_ID,
                                      1L,
                                      DeepLinkPlugin.VERSION,
                                      deepLinkSchemaFingerprint,
                                      snapshot.capabilityFingerprints().get(DEEP_LINK_PLUGIN_ID),
                                      deepLinkGeneration,
                                      deepLinkContextFingerprint,
                                      List.of(),
                                      "{}")),
                              manifest))),
          "the epoch 1 catalog seed must win the epoch CAS");
    }
    assertEquals(1L, configurationRepository.readCatalog().epoch());
  }

  /** 首次 SET 写入凭据行：密文只含密文，历史只记录字段名，投影可读回同一载荷。 */
  @Test
  void setWritesEncryptedCredentialRowAndRecordsOnlyFieldNames() {
    PluginCatalogRecord committed = setStaticSecret(STATIC_PAYLOAD_SECRET, 1L, 1L);

    assertEquals(2L, committed.epoch());
    PluginCredentialRow row = credentialRepository.find(STATIC_PLUGIN_ID).orElseThrow();
    assertEquals(PluginCredentialStatus.CONNECTED, row.status());
    assertNull(row.region());
    assertNull(row.expiresAt());
    assertEquals(0L, row.version());
    assertNotEquals(
        staticGeneration, row.authGeneration(), "a secret write must rotate the auth generation");
    assertFalse(
        indexOf(row.encryptedPayload(), STATIC_PAYLOAD_SECRET.getBytes(StandardCharsets.UTF_8))
            >= 0,
        "the credential row must not contain the plaintext secret");

    PluginCredentialSnapshot snapshot = credentialStore.resolve(currentReference(STATIC_PLUGIN_ID));
    assertEquals("{\"apiKey\":\"" + STATIC_PAYLOAD_SECRET + "\"}", snapshot.payloadJson());
    assertEquals(row.authGeneration(), snapshot.authGeneration());
    assertEquals(row.contextFingerprint(), snapshot.contextFingerprint());

    PluginConfigurationRow history =
        configurationRepository.readConfiguration(STATIC_PLUGIN_ID, 2L).orElseThrow();
    assertEquals(List.of("apiKey"), history.configuredSecretFields());
    assertEquals(BOOTSTRAP_VALUES, history.valuesJson());
    assertEquals(
        row.authGeneration(),
        configurationRepository
            .readCatalog()
            .manifest()
            .plugins()
            .get(STATIC_PLUGIN_ID)
            .authGeneration(),
        "the manifest entry must carry the committed generation");
    assertEquals(
        1, dispatcherWakes.get(), "a credential write must wake the refresh dispatcher once");
    assertEquals(
        1, catalogWakes.get(), "every successful commit must wake the local catalog refresh once");
    assertEquals(
        "payload={\"apiKey\":\"" + STATIC_PAYLOAD_SECRET + "\"}",
        staticPlugin.lastObservedCredential(),
        "the candidate instance must see this commit's private secret view");
  }

  /** 纯 KEEP 且认证上下文未变化：只推进普通 revision，凭据行逐字节不变、代际不变、已录入字段保留。 */
  @Test
  void keepOnlyCommitLeavesTheCredentialRowUntouched() {
    seedStaticCredential(STATIC_PAYLOAD_SECRET);
    PluginCredentialRow before = credentialRepository.find(STATIC_PLUGIN_ID).orElseThrow();

    commitService.configure(STATIC_PLUGIN_ID, 1L, 1L, UPDATED_VALUES, Map.of());

    PluginCredentialRow after = credentialRepository.find(STATIC_PLUGIN_ID).orElseThrow();
    assertEquals(before.authGeneration(), after.authGeneration());
    assertEquals(before.contextFingerprint(), after.contextFingerprint());
    assertEquals(before.version(), after.version());
    assertEquals(before.updateTime(), after.updateTime(), "the row must not be rewritten at all");
    assertTrue(
        Arrays.equals(before.encryptedPayload(), after.encryptedPayload()),
        "a keep-only commit must not re-encrypt the credential");
    assertEquals(0, dispatcherWakes.get(), "no credential change means no dispatcher wake");
    assertEquals(
        1,
        catalogWakes.get(),
        "a keep-only commit still advances the catalog and must wake the local refresh once");

    PluginConfigurationRow history =
        configurationRepository.readConfiguration(STATIC_PLUGIN_ID, 2L).orElseThrow();
    assertEquals(UPDATED_VALUES, history.valuesJson());
    assertEquals(
        List.of("apiKey"),
        history.configuredSecretFields(),
        "the configured field set must be preserved across a keep-only commit");
    assertEquals(
        staticContextFingerprint,
        history.contextFingerprint(),
        "the context fingerprint must be unchanged when only non-context fields move");
    assertEquals(
        "payload={\"apiKey\":\"" + STATIC_PAYLOAD_SECRET + "\"}",
        staticPlugin.lastObservedCredential(),
        "a keep-only commit reuses the authoritative scoped access for an unchanged row");
  }

  /** 认证目标变化时 KEEP 保留值，但必须绑定新代际与新上下文，旧引用不能取得新秘密。 */
  @Test
  void contextChangeReencryptsKeptSecretsForTheNewReference() {
    seedStaticCredential(STATIC_PAYLOAD_SECRET);
    PluginCredentialRow before = credentialRepository.find(STATIC_PLUGIN_ID).orElseThrow();
    PluginCredentialReference oldReference = currentReference(STATIC_PLUGIN_ID);
    PluginCatalogManifest.Entry unchanged =
        configurationRepository.readCatalog().manifest().plugins().get(DEEP_LINK_PLUGIN_ID);
    String values = "{\"endpoint\":\"https://other.example.com\",\"label\":\"default\"}";

    commitService.configure(
        STATIC_PLUGIN_ID, 1L, 1L, values, Map.of("apiKey", PluginSecretPatch.keep()));

    PluginCredentialRow after = credentialRepository.find(STATIC_PLUGIN_ID).orElseThrow();
    assertNotEquals(before.authGeneration(), after.authGeneration());
    assertNotEquals(before.contextFingerprint(), after.contextFingerprint());
    assertEquals(before.version() + 1, after.version());
    assertFalse(Arrays.equals(before.encryptedPayload(), after.encryptedPayload()));
    assertEquals(
        "{\"apiKey\":\"" + STATIC_PAYLOAD_SECRET + "\"}",
        credentialStore.resolve(currentReference(STATIC_PLUGIN_ID)).payloadJson());
    assertThrows(
        PluginCredentialUnavailableException.class, () -> credentialStore.resolve(oldReference));
    PluginConfigurationRow history =
        configurationRepository.readConfiguration(STATIC_PLUGIN_ID, 2L).orElseThrow();
    assertEquals(after.authGeneration(), history.authGeneration());
    assertEquals(after.contextFingerprint(), history.contextFingerprint());
    assertEquals(List.of("apiKey"), history.configuredSecretFields());
    assertEquals(values, history.valuesJson());
    assertEquals(
        unchanged,
        configurationRepository.readCatalog().manifest().plugins().get(DEEP_LINK_PLUGIN_ID));
    assertEquals(1, dispatcherWakes.get());
  }

  /** 部分 SET/CLEAR 只改变指定字段，显式 KEEP 与省略的已录入字段保留原值。 */
  @Test
  void partialSecretChangesPreserveTheKeptFields() {
    seedStaticCredential(STATIC_PAYLOAD_SECRET);

    commitService.configure(
        STATIC_PLUGIN_ID,
        1L,
        1L,
        BOOTSTRAP_VALUES,
        Map.of(
            "apiKey", PluginSecretPatch.keep(),
            "apiSecret", PluginSecretPatch.set(FRESH_PAYLOAD_SECRET)));
    JsonNode extended =
        JsonValues.readTree(
            credentialStore.resolve(currentReference(STATIC_PLUGIN_ID)).payloadJson());
    assertEquals(STATIC_PAYLOAD_SECRET, extended.get("apiKey").asString());
    assertEquals(FRESH_PAYLOAD_SECRET, extended.get("apiSecret").asString());
    assertEquals(2, extended.size());

    commitService.configure(
        STATIC_PLUGIN_ID, 2L, 2L, BOOTSTRAP_VALUES, Map.of("apiSecret", PluginSecretPatch.clear()));
    JsonNode retained =
        JsonValues.readTree(
            credentialStore.resolve(currentReference(STATIC_PLUGIN_ID)).payloadJson());
    assertEquals(STATIC_PAYLOAD_SECRET, retained.get("apiKey").asString());
    assertEquals(1, retained.size());
    assertEquals(
        List.of("apiKey"),
        configurationRepository
            .readConfiguration(STATIC_PLUGIN_ID, 3L)
            .orElseThrow()
            .configuredSecretFields());
  }

  @Test
  void secretReplacementConflictRollsBackCatalogAndHistory() {
    assertCredentialVersionConflict(PluginSecretPatch.set(FRESH_PAYLOAD_SECRET));
  }

  @Test
  void secretRemovalConflictRollsBackCatalogAndHistory() {
    assertCredentialVersionConflict(PluginSecretPatch.clear());
  }

  /** 候选构建期间另一请求作废凭据：真实行 CAS 冲突必须回滚本次目录与历史，但保留竞争者的写入。 */
  private void assertCredentialVersionConflict(PluginSecretPatch patch) {
    seedStaticCredential(STATIC_PAYLOAD_SECRET);
    PluginCredentialRow baseline = credentialRepository.find(STATIC_PLUGIN_ID).orElseThrow();
    PluginInstance candidate = mock(PluginInstance.class);
    when(candidate.harnessContributors()).thenReturn(List.of());
    when(candidate.canvasAdapters()).thenReturn(List.of());
    staticPlugin.candidateInstance = candidate;
    staticPlugin.beforeConfigure =
        () ->
            assertTrue(
                credentialRepository.rejectUsedCredential(
                    STATIC_PLUGIN_ID,
                    baseline.authGeneration(),
                    baseline.contextFingerprint(),
                    baseline.version(),
                    baseline.encryptedPayload(),
                    "provider rejected the stored static key",
                    now));

    assertThrows(
        AiVersionConflictException.class,
        () ->
            commitService.configure(
                STATIC_PLUGIN_ID, 1L, 1L, BOOTSTRAP_VALUES, Map.of("apiKey", patch)));

    PluginCredentialRow competitor = credentialRepository.find(STATIC_PLUGIN_ID).orElseThrow();
    assertEquals(PluginCredentialStatus.REAUTH_REQUIRED, competitor.status());
    assertEquals(baseline.version() + 1, competitor.version());
    assertEquals(baseline.authGeneration(), competitor.authGeneration());
    assertTrue(Arrays.equals(baseline.encryptedPayload(), competitor.encryptedPayload()));
    assertEquals(1L, configurationRepository.readCatalog().epoch());
    assertEquals(2, countHistoryRows());
    assertTrue(configurationRepository.readConfiguration(STATIC_PLUGIN_ID, 2L).isEmpty());
    assertEquals(0, dispatcherWakes.get());
    assertEquals(0, catalogWakes.get(), "a rolled-back commit must not wake the local refresh");
    verify(candidate, times(1)).close();
  }

  /** 第二次 SET 在既有行上整体替换：推进代际、改写密文，旧引用随即失效。 */
  @Test
  void setReplacesTheExistingRowAndInvalidatesTheOldReference() {
    setStaticSecret(STATIC_PAYLOAD_SECRET, 1L, 1L);
    PluginCredentialRow before = credentialRepository.find(STATIC_PLUGIN_ID).orElseThrow();
    PluginCredentialReference oldReference =
        new PluginCredentialReference(
            STATIC_PLUGIN_ID, before.authGeneration(), before.contextFingerprint());

    commitService.configure(
        STATIC_PLUGIN_ID,
        2L,
        2L,
        BOOTSTRAP_VALUES,
        Map.of("apiKey", PluginSecretPatch.set(FRESH_PAYLOAD_SECRET)));

    PluginCredentialRow after = credentialRepository.find(STATIC_PLUGIN_ID).orElseThrow();
    assertNotEquals(before.authGeneration(), after.authGeneration());
    assertFalse(
        Arrays.equals(before.encryptedPayload(), after.encryptedPayload()),
        "a new secret value must rewrite the ciphertext");
    assertEquals(
        "{\"apiKey\":\"" + FRESH_PAYLOAD_SECRET + "\"}",
        credentialStore.resolve(currentReference(STATIC_PLUGIN_ID)).payloadJson());
    assertThrows(
        PluginCredentialUnavailableException.class,
        () -> credentialStore.resolve(oldReference),
        "a rotated generation must reject the old reference");
    assertEquals(
        List.of("apiKey"),
        configurationRepository
            .readConfiguration(STATIC_PLUGIN_ID, 3L)
            .orElseThrow()
            .configuredSecretFields());
  }

  /** SET 后 CLEAR：删除凭据行，历史记录空字段集，且整个过程不需要主密钥。 */
  @Test
  void clearDeletesTheRowWithoutRequiringTheMasterKey() {
    setStaticSecret(STATIC_PAYLOAD_SECRET, 1L, 1L);
    int wakesBeforeClear = dispatcherWakes.get();

    keylessCommitService.configure(
        STATIC_PLUGIN_ID, 2L, 2L, BOOTSTRAP_VALUES, Map.of("apiKey", PluginSecretPatch.clear()));

    assertTrue(
        credentialRepository.find(STATIC_PLUGIN_ID).isEmpty(),
        "CLEAR must delete the credential row");
    assertEquals(3L, configurationRepository.readCatalog().epoch());
    assertEquals(
        List.of(),
        configurationRepository
            .readConfiguration(STATIC_PLUGIN_ID, 3L)
            .orElseThrow()
            .configuredSecretFields(),
        "the cleared revision must record no configured field");
    assertEquals(
        PluginCredentialStatus.NOT_CONNECTED,
        credentialStore.projection(STATIC_PLUGIN_ID).status());
    assertEquals(
        wakesBeforeClear + 1, dispatcherWakes.get(), "the removal must wake the dispatcher");
    assertEquals(
        PluginCredentialUnavailableReason.NOT_CONNECTED.name(),
        staticPlugin.lastObservedCredential(),
        "a CLEAR candidate must see this commit's private credential-less view, never the secret"
            + " it is about to revoke");
  }

  /** 缺主密钥且确实需要重写密文时必须 fail closed：不写凭据、不推进 epoch、不写历史。 */
  @Test
  void setWithoutTheMasterKeyFailsClosedWithoutPartialWrites() {
    long epochBefore = configurationRepository.readCatalog().epoch();

    assertThrows(
        PluginKeyUnavailableException.class,
        () ->
            keylessCommitService.configure(
                STATIC_PLUGIN_ID,
                1L,
                1L,
                BOOTSTRAP_VALUES,
                Map.of("apiKey", PluginSecretPatch.set(STATIC_PAYLOAD_SECRET))));

    assertTrue(
        credentialRepository.find(STATIC_PLUGIN_ID).isEmpty(), "no credential row may be written");
    assertEquals(epochBefore, configurationRepository.readCatalog().epoch());
    assertEquals(2, countHistoryRows(), "no revision history may be written");
    assertEquals(0, dispatcherWakes.get());
    assertEquals(0, catalogWakes.get(), "a failed commit must not wake the local refresh");
  }

  /** 陈旧坐标必须冲突失败，且失败提交不留任何痕迹。 */
  @Test
  void staleCoordinatesConflictWithoutWritingAnything() {
    assertThrows(
        AiVersionConflictException.class,
        () ->
            commitService.configure(
                STATIC_PLUGIN_ID,
                99L,
                1L,
                UPDATED_VALUES,
                Map.of("apiKey", PluginSecretPatch.set(STATIC_PAYLOAD_SECRET))));
    assertThrows(
        AiVersionConflictException.class,
        () ->
            commitService.configure(
                STATIC_PLUGIN_ID,
                1L,
                99L,
                UPDATED_VALUES,
                Map.of("apiKey", PluginSecretPatch.set(STATIC_PAYLOAD_SECRET))));

    assertEquals(1L, configurationRepository.readCatalog().epoch());
    assertEquals(
        BOOTSTRAP_VALUES,
        configurationRepository.readConfiguration(STATIC_PLUGIN_ID, 1L).orElseThrow().valuesJson());
    assertTrue(credentialRepository.find(STATIC_PLUGIN_ID).isEmpty());
    assertEquals(0, dispatcherWakes.get());
    assertEquals(
        0, catalogWakes.get(), "a stale-coordinate conflict must not wake the local refresh");
  }

  /** 登录完成走同一提交入口：写入交互凭据行并轮换代际，配置值保持不变。 */
  @Test
  void completeAuthWritesInteractiveCredentialRow() {
    loginDeepLinkPlugin();
    assertEquals(1, dispatcherWakes.get());
  }

  /** 生产装配的回调在外层事务提交后只唤醒一次，不能再次注册进已经遍历的提交回调列表。 */
  @Test
  void productionWiringWakesDispatcherOnceAfterOuterCommit() {
    PluginCredentialRefreshDispatcher dispatcher = mock(PluginCredentialRefreshDispatcher.class);
    PluginConfigurationCommitService wired = wiredCommitService(dispatcher);

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              wired.configure(
                  STATIC_PLUGIN_ID,
                  1L,
                  1L,
                  BOOTSTRAP_VALUES,
                  Map.of("apiKey", PluginSecretPatch.set(STATIC_PAYLOAD_SECRET)));
              verifyNoInteractions(dispatcher);
            });

    verify(dispatcher, times(1)).wake();
    assertEquals(1, catalogWakes.get(), "the wired catalog trigger must be woken exactly once");
    assertEquals(2L, configurationRepository.readCatalog().epoch());
    assertTrue(credentialRepository.find(STATIC_PLUGIN_ID).isPresent());
  }

  /** 外层事务回滚时，秘密、目录与刷新唤醒都不能留下部分成功。 */
  @Test
  void productionWiringNeverWakesDispatcherAfterRollback() {
    PluginCredentialRefreshDispatcher dispatcher = mock(PluginCredentialRefreshDispatcher.class);
    PluginConfigurationCommitService wired = wiredCommitService(dispatcher);

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              wired.configure(
                  STATIC_PLUGIN_ID,
                  1L,
                  1L,
                  BOOTSTRAP_VALUES,
                  Map.of("apiKey", PluginSecretPatch.set(STATIC_PAYLOAD_SECRET)));
              verifyNoInteractions(dispatcher);
              status.setRollbackOnly();
            });

    verifyNoInteractions(dispatcher);
    assertEquals(0, catalogWakes.get(), "a rolled-back commit must not wake the local refresh");
    assertEquals(1L, configurationRepository.readCatalog().epoch());
    assertTrue(credentialRepository.find(STATIC_PLUGIN_ID).isEmpty());
    assertEquals(2, countHistoryRows());
  }

  /** 提交与候选回收同时出现致命错误时仍释放实例，原始致命错误不能被回收错误替代。 */
  @Test
  void candidateCleanupPreservesThePrimaryFatalError() {
    AssertionError primary = new AssertionError("transaction fatal error");
    AssertionError cleanup = new AssertionError("instance cleanup fatal error");
    PlatformTransactionManager failingTransactions = mock(PlatformTransactionManager.class);
    when(failingTransactions.getTransaction(any())).thenThrow(primary);
    PluginInstance instance = mock(PluginInstance.class);
    when(instance.harnessContributors()).thenReturn(List.of());
    when(instance.canvasAdapters()).thenReturn(List.of());
    doThrow(cleanup).when(instance).close();
    staticPlugin.candidateInstance = instance;
    PluginConfigurationCommitService failingCommit =
        new PluginConfigurationCommitService(
            registry,
            configurationRepository,
            credentialRepository,
            codec,
            new PluginCredentialKeyLoader(tempDir.resolve("commit-test.key").toString()),
            new PluginContextFactory(credentialStore, () -> null),
            List::of,
            List::of,
            failingTransactions,
            clock,
            dispatcherWakes::incrementAndGet,
            catalogWakes::incrementAndGet);

    assertSame(
        primary,
        assertThrows(
            AssertionError.class,
            () -> failingCommit.configure(STATIC_PLUGIN_ID, 1L, 1L, UPDATED_VALUES, Map.of())));
    verify(instance, times(1)).close();
    assertEquals(1L, configurationRepository.readCatalog().epoch());
    assertEquals(0, dispatcherWakes.get());
    assertEquals(0, catalogWakes.get(), "a rolled-back commit must not wake the local refresh");
  }

  private PluginConfigurationCommitService wiredCommitService(
      PluginCredentialRefreshDispatcher dispatcher) {
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("refreshDispatcher", dispatcher);
    // 生产装配的目录监听端口同样只排队：这里用计数器替身断言它在提交后恰好被唤醒一次。
    beans.addBean(
        "catalogRefreshTrigger", (PluginCatalogRefreshTrigger) catalogWakes::incrementAndGet);
    return new PluginConfiguration()
        .pluginConfigurationCommitService(
            registry,
            configurationRepository,
            credentialRepository,
            codec,
            new PluginCredentialKeyLoader(tempDir.resolve("commit-test.key").toString()),
            new PluginContextFactory(credentialStore, () -> null),
            beans.getBeanProvider(HarnessContributor.class),
            beans.getBeanProvider(CanvasFunctionAdapter.class),
            transactionManager,
            beans.getBeanProvider(PluginCredentialRefreshDispatcher.class),
            beans.getBeanProvider(PluginCatalogRefreshTrigger.class));
  }

  /** 登录完成写入交互凭据行的具体断言。 */
  private void loginDeepLinkPlugin() {
    String payload = "{\"token\":\"" + STATIC_PAYLOAD_SECRET + "\",\"refreshToken\":\"rt\"}";
    commitService.completeAuth(
        DEEP_LINK_PLUGIN_ID,
        1L,
        new PluginCredentialMaterial(
            REGION, now.plusSeconds(3600), now.plusSeconds(1800), payload));

    PluginCredentialRow row = credentialRepository.find(DEEP_LINK_PLUGIN_ID).orElseThrow();
    assertEquals(REGION, row.region());
    assertEquals(now.plusSeconds(3600), row.expiresAt());
    assertNotEquals(deepLinkGeneration, row.authGeneration());
    assertFalse(
        indexOf(row.encryptedPayload(), STATIC_PAYLOAD_SECRET.getBytes(StandardCharsets.UTF_8))
            >= 0);
    PluginCredentialSnapshot snapshot =
        credentialStore.resolve(currentReference(DEEP_LINK_PLUGIN_ID));
    assertEquals(
        payload,
        snapshot.payloadJson(),
        "interactive material is opaque and must be stored verbatim");
    assertTrue(snapshot.interactive());
    assertEquals(
        List.of("token"),
        configurationRepository
            .readConfiguration(DEEP_LINK_PLUGIN_ID, 2L)
            .orElseThrow()
            .configuredSecretFields(),
        "only declared secret fields are recorded as configured");
    assertEquals(
        "{}",
        configurationRepository
            .readConfiguration(DEEP_LINK_PLUGIN_ID, 2L)
            .orElseThrow()
            .valuesJson(),
        "login completion must not change the ordinary configuration");
  }

  /** 已过期的登录材料绝不落库：与其它写入门禁一致地固定拒绝。 */
  @Test
  void completeAuthRejectsAlreadyExpiredMaterial() {
    assertThrows(
        AiValidationException.class,
        () ->
            commitService.completeAuth(
                DEEP_LINK_PLUGIN_ID,
                1L,
                new PluginCredentialMaterial(
                    REGION, now, now.plusSeconds(1800), "{\"token\":\"x\"}")));

    assertTrue(credentialRepository.find(DEEP_LINK_PLUGIN_ID).isEmpty());
    assertEquals(1L, configurationRepository.readCatalog().epoch());
  }

  /** 断连幂等且不需要主密钥：删除凭据行、推进 epoch，重复调用不再触碰凭据。 */
  @Test
  void disconnectDeletesTheRowAndIsIdempotentWithoutTheMasterKey() {
    loginDeepLinkPlugin();
    int wakesAfterLogin = dispatcherWakes.get();
    int catalogWakesAfterLogin = catalogWakes.get();

    keylessCommitService.disconnect(DEEP_LINK_PLUGIN_ID, 2L);

    assertTrue(credentialRepository.find(DEEP_LINK_PLUGIN_ID).isEmpty());
    assertEquals(3L, configurationRepository.readCatalog().epoch());
    assertEquals(wakesAfterLogin + 1, dispatcherWakes.get());
    assertEquals(catalogWakesAfterLogin + 1, catalogWakes.get());

    keylessCommitService.disconnect(DEEP_LINK_PLUGIN_ID, 3L);

    assertTrue(credentialRepository.find(DEEP_LINK_PLUGIN_ID).isEmpty());
    assertEquals(4L, configurationRepository.readCatalog().epoch(), "disconnect is idempotent");
    assertEquals(wakesAfterLogin + 1, dispatcherWakes.get(), "a no-op disconnect must not wake");
    assertEquals(
        catalogWakesAfterLogin + 2,
        catalogWakes.get(),
        "even a no-op disconnect advances the catalog and must wake the local refresh");
  }

  /** 只有 registry 里已安装的 Plugin 才能提交；未知 id fail closed。 */
  @Test
  void configureRejectsUnknownPlugin() {
    assertThrows(
        AiResourceNotFoundException.class,
        () -> commitService.configure("unknown.plugin", 1L, 1L, "{}", Map.of()));
  }

  /**
   * 多个 SET 的组合会叠加成一份载荷：多字节与转义展开都在完整分配前受同一份 64 KiB 预算约束，超限整体拒绝。
   *
   * <p>失败必须发生在任何写入之前：epoch、历史行与凭据行逐项保持不变，且错误文本绝不回显任何秘密值。
   */
  @Test
  void oversizedSecretPatchIsRejectedWithoutAnyWrite() {
    long epochBefore = configurationRepository.readCatalog().epoch();
    int historyBefore = countHistoryRows();
    String marker = "oversized-marker-4242";
    // 每个值本身都满足单值预算（<=4096 字符且 <=4096 UTF-8 字节），但转义展开合计远超 64 KiB。
    String escaped = "\u0001".repeat(4070) + marker;
    String multibyte = "\u4e2d".repeat(1300);

    AiValidationException error =
        assertThrows(
            AiValidationException.class,
            () ->
                commitService.configure(
                    STATIC_PLUGIN_ID,
                    1L,
                    1L,
                    BOOTSTRAP_VALUES,
                    Map.of(
                        "apiKey", PluginSecretPatch.set(escaped),
                        "apiSecret", PluginSecretPatch.set(escaped),
                        "apiToken", PluginSecretPatch.set(multibyte),
                        "apiSalt", PluginSecretPatch.set("\u0001".repeat(4090)))));

    assertFalse(
        error.getMessage().contains(marker), "the rejection must not echo any secret value");
    assertFalse(error.toString().contains(marker));
    assertEquals(epochBefore, configurationRepository.readCatalog().epoch());
    assertEquals(historyBefore, countHistoryRows());
    assertTrue(credentialRepository.find(STATIC_PLUGIN_ID).isEmpty());
    assertEquals(0, dispatcherWakes.get());
    assertEquals(0, catalogWakes.get(), "a rejected patch must not wake the local refresh");
  }

  /**
   * 已被全部替换或清除的旧秘密绝不解密：旧密文不可解时，整体 SET 覆盖与缺主密钥的整体 CLEAR 仍必须成功。
   *
   * <p>反过来，只要还有字段靠 KEEP 保留，旧密文仍是必要输入，此时不可解的旧密文必须 fail closed 而不是被静默丢弃。
   */
  @Test
  void replacingOrClearingEveryConfiguredFieldNeverDecryptsTheOldSecret() throws SQLException {
    commitService.configure(
        STATIC_PLUGIN_ID,
        1L,
        1L,
        BOOTSTRAP_VALUES,
        Map.of(
            "apiKey", PluginSecretPatch.set(FRESH_PAYLOAD_SECRET),
            "apiSecret", PluginSecretPatch.set(STATIC_PAYLOAD_SECRET)));
    assertEquals(
        Set.of("apiKey", "apiSecret"),
        Set.copyOf(
            configurationRepository
                .readConfiguration(STATIC_PLUGIN_ID, 2L)
                .orElseThrow()
                .configuredSecretFields()),
        "both declared fields must be recorded as configured");

    // 旧密文改成不可解：任何解密尝试都会失败，因此成功提交即证明旧值从未被读取。
    corruptCredentialPayload();

    // 全部 SET 覆盖：两个已录入字段都被替换，旧（不可解）密文不参与候选载荷。
    commitService.configure(
        STATIC_PLUGIN_ID,
        2L,
        2L,
        BOOTSTRAP_VALUES,
        Map.of(
            "apiKey", PluginSecretPatch.set("rotated-key-1"),
            "apiSecret", PluginSecretPatch.set("rotated-key-2")));
    JsonNode rotated =
        JsonValues.readTree(
            credentialStore.resolve(currentReference(STATIC_PLUGIN_ID)).payloadJson());
    assertEquals(2, rotated.size(), "only this commit's fields may remain in the payload");
    assertEquals("rotated-key-1", rotated.get("apiKey").asString());
    assertEquals("rotated-key-2", rotated.get("apiSecret").asString());
    assertEquals(3L, configurationRepository.readCatalog().epoch());

    // 仍有字段靠 KEEP 保留：旧密文是必要输入，不可解即 fail closed，且不留痕迹。
    corruptCredentialPayload();
    long epochBeforeRefusal = configurationRepository.readCatalog().epoch();
    assertThrows(
        PluginCredentialIntegrityException.class,
        () ->
            commitService.configure(
                STATIC_PLUGIN_ID,
                3L,
                3L,
                BOOTSTRAP_VALUES,
                Map.of("apiKey", PluginSecretPatch.set("partial-rotation"))));
    assertEquals(epochBeforeRefusal, configurationRepository.readCatalog().epoch());

    // 全部 CLEAR：缺主密钥也必须能撤销全部已录入字段，绝不尝试解密旧密文。
    keylessCommitService.configure(
        STATIC_PLUGIN_ID,
        3L,
        3L,
        BOOTSTRAP_VALUES,
        Map.of(
            "apiKey", PluginSecretPatch.clear(),
            "apiSecret", PluginSecretPatch.clear()));
    assertTrue(credentialRepository.find(STATIC_PLUGIN_ID).isEmpty());
    assertEquals(4L, configurationRepository.readCatalog().epoch());
    assertEquals(
        List.of(),
        configurationRepository
            .readConfiguration(STATIC_PLUGIN_ID, 4L)
            .orElseThrow()
            .configuredSecretFields());
  }

  /** 把当前凭据行改成不可解密的密文：任何解密尝试都会失败，从而证明旧值从未被读取。 */
  private void corruptCredentialPayload() throws SQLException {
    try (Connection conn = PostgresSchemaSupport.newConnection();
        Statement statement = conn.createStatement()) {
      statement.execute(
          "update plugin_credential set encrypted_payload = decode('00ff', 'hex')"
              + " where plugin_id = '"
              + STATIC_PLUGIN_ID
              + "'");
    }
  }

  /** 在真实凭据行上植入一行已连接秘密，用于 KEEP/CLEAR 基线。 */
  private void seedStaticCredential(String secret) {
    String payload = "{\"apiKey\":\"" + secret + "\"}";
    assertTrue(
        credentialRepository.insert(
            new PluginCredentialRow(
                STATIC_PLUGIN_ID,
                staticGeneration,
                staticContextFingerprint,
                codec.encrypt(
                    key(),
                    STATIC_PLUGIN_ID,
                    staticGeneration,
                    staticContextFingerprint,
                    null,
                    payload),
                null,
                null,
                null,
                PluginCredentialStatus.CONNECTED,
                null,
                null,
                null,
                null,
                0L,
                now,
                now)));
  }

  /** 权威 manifest 中该 Plugin 当前的冻结引用。 */
  private PluginCredentialReference currentReference(String pluginId) {
    PluginCatalogManifest.Entry entry =
        configurationRepository.readCatalog().manifest().plugins().get(pluginId);
    return new PluginCredentialReference(
        pluginId, entry.authGeneration(), entry.contextFingerprint());
  }

  /** 首次 SET 的实际提交，返回提交后的权威记录。 */
  private PluginCatalogRecord setStaticSecret(
      String secret, long expectedEpoch, long expectedRevision) {
    return commitService.configure(
        STATIC_PLUGIN_ID,
        expectedEpoch,
        expectedRevision,
        BOOTSTRAP_VALUES,
        Map.of("apiKey", PluginSecretPatch.set(secret)));
  }

  private static SecretKey key() {
    return new SecretKeySpec(
        "12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8), "AES");
  }

  private static int countHistoryRows() {
    return jdbcClient
        .sql("select count(*) from plugin_configuration_revision")
        .query(Integer.class)
        .single();
  }

  private static int indexOf(byte[] haystack, byte[] needle) {
    outer:
    for (int i = 0; i + needle.length <= haystack.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  /** 静态密钥 Plugin：一个用于绑定认证上下文的普通字段、一个与上下文无关的普通字段，以及四个声明的秘密字段。 */
  private static final class StaticKeyPlugin implements StudioPlugin {

    static final String VERSION = "1.0";

    private final PluginDescriptor descriptor =
        new PluginDescriptor(STATIC_PLUGIN_ID, "Static Key Plugin", VERSION);

    /** 每次候选构建时该 Plugin 实际看到的秘密视图：用于断言候选从不进入活动凭据。 */
    private final List<String> observedCredentials = new ArrayList<>();

    private PluginInstance candidateInstance;
    private Runnable beforeConfigure;

    private final PluginConfigSchema schema =
        new PluginConfigSchema(
            new InputSchema(
                "config",
                Map.of(
                    "endpoint", new StringSchema("endpoint"),
                    "label", new StringSchema("label")),
                Set.of(),
                false),
            "{\"endpoint\":\"https://api.example.com\",\"label\":\"default\"}",
            Map.of(
                "apiKey", "static API key",
                "apiSecret", "static API secret",
                "apiToken", "static API token",
                "apiSalt", "static API salt"));

    PluginAuthSpec authSpec() {
      return new PluginAuthSpec.StaticKey("apiKey", Set.of("endpoint"));
    }

    @Override
    public PluginDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public PluginConfigSchema configurationSchema() {
      return schema;
    }

    @Override
    public PluginAuthSpec authenticationSpec() {
      return authSpec();
    }

    @Override
    public PluginInstance configure(PluginConfig config, PluginContext context) {
      if (beforeConfigure != null) {
        Runnable action = beforeConfigure;
        beforeConfigure = null;
        action.run();
      }
      try {
        observedCredentials.add("payload=" + context.credentialAccess().resolve().payloadJson());
      } catch (PluginCredentialUnavailableException unavailable) {
        observedCredentials.add(unavailable.reason().name());
      } catch (RuntimeException error) {
        observedCredentials.add(error.getClass().getSimpleName());
      }
      return candidateInstance == null ? TestPluginFixtures.emptyInstance() : candidateInstance;
    }

    String lastObservedCredential() {
      return observedCredentials.get(observedCredentials.size() - 1);
    }
  }

  /** 交互式 Plugin：用于覆盖登录完成与断连的写入路径。 */
  private static final class DeepLinkPlugin implements StudioPlugin {

    static final String VERSION = "1.0";

    private final PluginDescriptor descriptor =
        new PluginDescriptor(DEEP_LINK_PLUGIN_ID, "Deep Link Plugin", VERSION);

    PluginAuthSpec authSpec() {
      return new PluginAuthSpec.DeepLink(List.of(REGION));
    }

    @Override
    public PluginDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public PluginConfigSchema configurationSchema() {
      return TestPluginFixtures.emptySchemaWithSecrets(Map.of("token", "interactive token"));
    }

    @Override
    public PluginAuthSpec authenticationSpec() {
      return authSpec();
    }

    @Override
    public PluginInstance configure(PluginConfig config, PluginContext context) {
      return TestPluginFixtures.emptyInstance();
    }

    @Override
    public Optional<PluginAuthHandler> authHandler() {
      return Optional.of(
          new PluginAuthHandler() {
            @Override
            public String loginUrl(String region) {
              return "https://login.example.com/" + region;
            }

            @Override
            public PluginCredentialMaterial complete(String callbackUrl) {
              return null;
            }
          });
    }
  }
}
