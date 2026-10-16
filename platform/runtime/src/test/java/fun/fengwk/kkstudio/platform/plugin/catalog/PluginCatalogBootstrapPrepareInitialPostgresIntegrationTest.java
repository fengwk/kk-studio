package fun.fengwk.kkstudio.platform.plugin.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.harness.common.schema.BooleanSchema;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableReason;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialCodec;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialReference;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.PostgresqlPluginCatalogChangeNotifier;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.PostgresqlPluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.PostgresqlPluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.store.PluginConfigurationJdbcStore;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.store.PluginCredentialJdbcStore;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * {@link PluginCatalogBootstrap#prepareInitial} 的真实隔离 PostgreSQL 集成测试：单外层事务内的 catalog commit 与
 * format 2 凭据插入 必须整体成功或整体回滚，且成功后可被同一版本真实恢复。
 *
 * <p>使用真实生产仓储（{@link PostgresqlPluginConfigurationRepository} / {@link
 * PostgresqlPluginCredentialRepository}）与真实事务管理器， 依赖 V1 的外键与 CHECK，不引入 {@code
 * DEFERRABLE}、触发器绕过或任何额外写入口。dry preparation 只构造候选，不触碰 epoch 0。
 */
class PluginCatalogBootstrapPrepareInitialPostgresIntegrationTest {

  private static final String ALPHA = "alpha.plugin";

  /** 不透明旧载荷：真实升级会把这段原文字节重封装到 format 2。 */
  private static final String OPAQUE_PAYLOAD =
      "{\"token\":\"legacy-opaque\",\"count\":1234567890123456789}";

  private static final PluginConfigSchema SCHEMA =
      new PluginConfigSchema(
          new InputSchema(
              null, Map.of("enabled", new BooleanSchema(null)), Set.of("enabled"), false),
          "{\"enabled\":true}",
          Map.of("apiKey", "API key"));

  private static DataSource dataSource;
  private static JdbcClient jdbcClient;

  private TransactionTemplate transactionTemplate;
  private PostgresqlPluginConfigurationRepository catalogRepository;
  private PostgresqlPluginCredentialRepository credentialRepository;
  private PluginCatalogBootstrap bootstrap;
  private TestPlugin plugin;

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
  void setUp() throws SQLException {
    try (Connection conn = PostgresSchemaSupport.newConnection()) {
      PostgresSchemaSupport.resetDatabase(conn);
      PostgresSchemaSupport.applyBaseline(conn);
    }
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    transactionTemplate = new TransactionTemplate(transactionManager);
    catalogRepository =
        new PostgresqlPluginConfigurationRepository(
            new PluginConfigurationJdbcStore(jdbcClient),
            new PostgresqlPluginCatalogChangeNotifier(new JdbcTemplate(dataSource)));
    credentialRepository =
        new PostgresqlPluginCredentialRepository(new PluginCredentialJdbcStore(jdbcClient));
    plugin = new TestPlugin(ALPHA);
    bootstrap =
        new PluginCatalogBootstrap(
            new StudioPluginRegistry(List.of(plugin)), catalogRepository, transactionManager);
  }

  /** 离线准备只产出候选，权威 catalog 仍停在 V1 未初始化的 epoch 0，且没有任何历史或凭据行。 */
  @Test
  void dryPreparationLeavesTheUninitializedEpochUntouched() {
    PluginCatalogBootstrap.PreparedInitial prepared = prepare();
    try {
      assertEquals(0, epoch());
      assertEquals(1, catalogRevisionRows());
      assertEquals(0, historyRows());
      assertEquals(0, credentialRows());
      assertEquals(PluginCatalogBootstrap.INITIAL_EPOCH, prepared.snapshot().epoch());
      assertEquals(1, prepared.manifest().plugins().size());
      assertEquals(1, prepared.configurationRows().size());
    } finally {
      prepared.snapshot().close();
    }
    assertEquals(1, plugin.createdInstances.get(0).closeCount, "the caller owns the snapshot");
  }

  /** 单个外层事务里的 catalog commit 与 format 2 凭据插入整体成功：epoch/历史/凭据一致，并能被同版本真实恢复。 */
  @Test
  void catalogCommitAndFormatTwoCredentialInsertCommitAtomicallyAndRestore() {
    PluginCatalogBootstrap.PreparedInitial prepared = prepare();
    try {
      PluginCatalogManifest.Entry entry = prepared.manifest().plugins().get(ALPHA);
      PluginCredentialCodec codec = new PluginCredentialCodec();
      SecretKey key = key();
      byte[] payload =
          codec.encrypt(
              key, ALPHA, entry.authGeneration(), entry.contextFingerprint(), null, OPAQUE_PAYLOAD);

      transactionTemplate.executeWithoutResult(
          status -> {
            assertTrue(
                catalogRepository.commit(0, prepared.configurationRows(), prepared.manifest()));
            assertTrue(credentialRepository.insert(credentialRow(entry, payload)));
          });

      assertEquals(1, epoch());
      assertEquals(1, historyRows());
      assertEquals(2, catalogRevisionRows());
      assertEquals(1, credentialRows());

      PluginCredentialRow stored = credentialRepository.find(ALPHA).orElseThrow();
      assertEquals(entry.authGeneration(), stored.authGeneration());
      assertEquals(entry.contextFingerprint(), stored.contextFingerprint());
      assertEquals(
          OPAQUE_PAYLOAD,
          codec.decrypt(
              key,
              ALPHA,
              entry.authGeneration(),
              entry.contextFingerprint(),
              null,
              stored.encryptedPayload()),
          "the fmt2 credential must decrypt back to the opaque payload");

      try (PluginCatalogSnapshot restored =
          bootstrap.restore(catalogRepository.readCatalog(), contexts(), List.of(), List.of())) {
        assertEquals(1, restored.epoch());
        assertEquals(prepared.snapshot().catalogFingerprint(), restored.catalogFingerprint());
      }
    } finally {
      prepared.snapshot().close();
    }
  }

  /** catalog commit 之后的凭据插入失败必须让 catalog、历史与凭据全部回滚到 epoch 0。 */
  @Test
  void failureAfterCatalogCommitRollsBackCatalogHistoryAndCredential() {
    PluginCatalogBootstrap.PreparedInitial prepared = prepare();
    try {
      PluginCatalogManifest.Entry entry = prepared.manifest().plugins().get(ALPHA);
      Instant now = Instant.now();
      // 三路 route metadata 齐备（合法领域形态），但 region 带多余空格：真实 V1 ck_plugin_credential_region 会在凭据插入时拒绝它，
      // 从而在 catalog commit 之后制造一次真实数据库约束失败。
      PluginCredentialRow invalid =
          new PluginCredentialRow(
              ALPHA,
              entry.authGeneration(),
              entry.contextFingerprint(),
              new byte[] {1, 2, 3},
              " CN",
              now.plusSeconds(3600),
              now.plusSeconds(1800),
              PluginCredentialStatus.CONNECTED,
              null,
              null,
              null,
              null,
              0L,
              now,
              now);

      DataAccessException failure =
          assertThrows(
              DataAccessException.class,
              () ->
                  transactionTemplate.executeWithoutResult(
                      status -> {
                        assertTrue(
                            catalogRepository.commit(
                                0, prepared.configurationRows(), prepared.manifest()));
                        credentialRepository.insert(invalid);
                      }));
      assertCheckConstraintFailure(failure, "ck_plugin_credential_region");

      assertEquals(0, epoch(), "the catalog must roll back to the uninitialized epoch");
      assertEquals(1, catalogRevisionRows());
      assertEquals(0, historyRows());
      assertEquals(0, credentialRows());
    } finally {
      prepared.snapshot().close();
    }
  }

  /** 证明失败确实来自 V1 的 region CHECK，而不是任意数据库错误。 */
  private static void assertCheckConstraintFailure(
      DataAccessException thrown, String expectedConstraint) {
    PSQLException pg = null;
    Throwable current = thrown;
    while (current != null && pg == null) {
      if (current instanceof PSQLException psql) {
        pg = psql;
      }
      current = current.getCause();
    }
    assertNotNull(pg, "expected a PSQLException in the cause chain of " + thrown);
    assertEquals("23514", pg.getSQLState(), "must be a CHECK constraint violation");
    assertNotNull(pg.getServerErrorMessage(), "the PostgreSQL server error must be available");
    assertEquals(expectedConstraint, pg.getServerErrorMessage().getConstraint());
  }

  private PluginCatalogBootstrap.PreparedInitial prepare() {
    return bootstrap.prepareInitial(
        Map.of(ALPHA, "{}"), Map.of(ALPHA, List.of("apiKey")), contexts(), List.of(), List.of());
  }

  private static PluginCredentialRow credentialRow(
      PluginCatalogManifest.Entry entry, byte[] payload) {
    Instant now = Instant.now();
    return new PluginCredentialRow(
        ALPHA,
        entry.authGeneration(),
        entry.contextFingerprint(),
        payload,
        null,
        null,
        null,
        PluginCredentialStatus.CONNECTED,
        now,
        null,
        null,
        null,
        0L,
        now,
        now);
  }

  private static SecretKey key() {
    byte[] raw = new byte[32];
    Arrays.fill(raw, (byte) 7);
    return new SecretKeySpec(raw, "AES");
  }

  private static BiFunction<String, PluginCredentialReference, PluginContext> contexts() {
    return (pluginId, reference) ->
        new PluginContext(
            new PluginCredentialAccess() {
              @Override
              public PluginCredentialSnapshot resolve() {
                throw new PluginCredentialUnavailableException(
                    PluginCredentialUnavailableReason.NOT_CONNECTED, "test context");
              }

              @Override
              public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
                return false;
              }
            },
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
  }

  private long epoch() {
    return jdbcClient
        .sql("select epoch from plugin_catalog_state where id = 1")
        .query(Long.class)
        .single();
  }

  private int catalogRevisionRows() {
    return jdbcClient
        .sql("select count(*) from plugin_catalog_revision")
        .query(Integer.class)
        .single();
  }

  private int historyRows() {
    return jdbcClient
        .sql("select count(*) from plugin_configuration_revision")
        .query(Integer.class)
        .single();
  }

  private int credentialRows() {
    return jdbcClient.sql("select count(*) from plugin_credential").query(Integer.class).single();
  }

  /** 忠实最小 Plugin：声明一个普通字段与一个秘密字段，静态密钥认证，两域贡献为空。 */
  private static final class TestPlugin implements StudioPlugin {

    private final PluginDescriptor descriptor;
    private final List<RecordingInstance> createdInstances = new ArrayList<>();

    TestPlugin(String pluginId) {
      this.descriptor = new PluginDescriptor(pluginId, "Plugin " + pluginId, "1.0");
    }

    @Override
    public PluginDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public PluginConfigSchema configurationSchema() {
      return SCHEMA;
    }

    @Override
    public PluginAuthSpec authenticationSpec() {
      return new PluginAuthSpec.StaticKey("apiKey", Set.of());
    }

    @Override
    public PluginInstance configure(PluginConfig config, PluginContext context) {
      RecordingInstance instance = new RecordingInstance();
      createdInstances.add(instance);
      return instance;
    }
  }

  private static final class RecordingInstance implements PluginInstance {

    private int closeCount;

    @Override
    public List<HarnessContributor> harnessContributors() {
      return List.of();
    }

    @Override
    public List<CanvasFunctionAdapter> canvasAdapters() {
      return List.of();
    }

    @Override
    public void close() {
      closeCount++;
    }
  }
}
