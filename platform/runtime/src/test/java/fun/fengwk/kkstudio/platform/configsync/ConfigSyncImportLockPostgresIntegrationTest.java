package fun.fengwk.kkstudio.platform.configsync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderModelDiscovery;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentService;
import fun.fengwk.kkstudio.platform.catalog.dto.ProviderCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpServerService;
import fun.fengwk.kkstudio.platform.catalog.model.ModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.ModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.model.ModelService;
import fun.fengwk.kkstudio.platform.catalog.provider.Provider;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderConfigurationCodec;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderService;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillCatalogService;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.environment.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.EnvironmentService;
import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;
import fun.fengwk.kkstudio.platform.pluginhost.service.PluginAgentDependencies;
import fun.fengwk.kkstudio.platform.pluginhost.service.PluginConfigurationService;
import fun.fengwk.kkstudio.platform.secret.SecretUpdate;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsService;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 意图：两个独立 ConfigSyncApplier 共用同一把事务级导入锁。T1 已进入 provider upsert 后，T2 必须阻塞在同一双 int advisory key
 * 上且尚未写资源；T1 提交后 T2 才能继续，最终两行都保留各自最后一次写入。
 */
class ConfigSyncImportLockPostgresIntegrationTest {

  private static final String ALPHA = "import-lock-alpha";
  private static final String BETA = "import-lock-beta";

  private static DataSource dataSource;
  private static JdbcClient jdbcClient;

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
    insertProvider(ALPHA, "seed-alpha");
    insertProvider(BETA, "seed-beta");
  }

  @Test
  void sharedAdvisoryLockSerializesImportsFromSeparateAppliers() throws Exception {
    CountDownLatch firstUpsertEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    PlatformTransactionManager transactions = new DataSourceTransactionManager(dataSource);
    ConfigSyncApplier first =
        applier(new GatedProviderRepository(jdbcClient, ALPHA, firstUpsertEntered, releaseFirst));
    ConfigSyncApplier second = applier(new ProviderRepository(jdbcClient));
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Void> firstImport =
          executor.submit(
              () -> {
                apply(
                    transactions,
                    first,
                    List.of(providerSpec(ALPHA, "first-alpha"), providerSpec(BETA, "first-beta")));
                return null;
              });
      assertTrue(
          firstUpsertEntered.await(30, TimeUnit.SECONDS),
          "the first import must enter its provider upsert before the second starts");

      Future<Void> secondImport =
          executor.submit(
              () -> {
                apply(
                    transactions,
                    second,
                    List.of(
                        providerSpec(BETA, "second-beta"), providerSpec(ALPHA, "second-alpha")));
                return null;
              });
      awaitBlockedImportLock();
      // T2 正等待导入锁；独立连接只能看到已提交的种子，T1 首行更新也尚未提交。
      assertEquals("seed-alpha", description(ALPHA));
      assertEquals("seed-beta", description(BETA));
      assertEquals(0L, version(ALPHA));
      assertEquals(0L, version(BETA));

      releaseFirst.countDown();
      firstImport.get(30, TimeUnit.SECONDS);
      secondImport.get(30, TimeUnit.SECONDS);
    } finally {
      releaseFirst.countDown();
      shutdown(executor);
    }

    assertEquals("second-alpha", description(ALPHA));
    assertEquals("second-beta", description(BETA));
    assertEquals(2L, version(ALPHA));
    assertEquals(2L, version(BETA));
    assertEquals(
        0L, waitingImportLocks(), "the committed imports must release the transaction lock");
  }

  private static void apply(
      PlatformTransactionManager transactions,
      ConfigSyncApplier applier,
      List<ConfigSyncParser.ProviderSpec> providers) {
    new TransactionTemplate(transactions)
        .execute(
            status -> {
              applier.apply(plan(providers), List.of(), Set.of());
              return null;
            });
  }

  private static ConfigSyncApplier applier(ProviderRepository providers) {
    ProviderService providerService =
        new ProviderService(
            providers,
            new ProviderConfigurationCodec(JsonMapper.builder().build()),
            new AgentEditableSupport(),
            mock(ProviderModelDiscovery.class),
            mock(ModelRepository.class),
            new ModelRuntimeConfigParser(JsonMapper.builder().build()));
    return new ConfigSyncApplier(
        jdbcClient,
        providerService,
        mock(ModelRepository.class),
        mock(ModelService.class),
        mock(SkillCatalogService.class),
        mock(McpServerService.class),
        mock(AgentRepository.class),
        mock(AgentService.class),
        mock(EnvironmentRepository.class),
        mock(EnvironmentService.class),
        mock(SystemSettingsService.class),
        mock(PluginConfigurationService.class),
        mock(PluginAgentDependencies.class));
  }

  private static ConfigSyncPlan plan(List<ConfigSyncParser.ProviderSpec> providers) {
    return new ConfigSyncPlan(
        providers, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
        List.of(), List.of());
  }

  private static ConfigSyncParser.ProviderSpec providerSpec(String name, String description) {
    ProviderCreateDTO properties = new ProviderCreateDTO();
    properties.setName(name);
    properties.setProviderType(ProviderType.OPENAI);
    properties.setDescription(description);
    properties.setBaseUrl("https://api.example.com");
    properties.setCredential(SecretUpdate.set("synthetic"));
    return new ConfigSyncParser.ProviderSpec(name, properties);
  }

  private static void insertProvider(String name, String description) {
    jdbcClient
        .sql(
            """
            insert into provider (name, description, provider_type, base_url, config)
            values (:name, :description, 'openai', 'https://api.example.com', '{}'::jsonb)
            """)
        .param("name", name)
        .param("description", description)
        .update();
  }

  private static String description(String name) {
    return jdbcClient
        .sql("select description from provider where name = :name")
        .param("name", name)
        .query(String.class)
        .single();
  }

  private static long version(String name) {
    return jdbcClient
        .sql("select version from provider where name = :name")
        .param("name", name)
        .query(Long.class)
        .single();
  }

  /** 轮询真实锁表，确认第二个事务正在等待同一配置导入 key，而不是用固定睡眠判断阻塞。 */
  private static void awaitBlockedImportLock() {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      if (waitingImportLocks() > 0) {
        return;
      }
      Thread.onSpinWait();
    }
    throw new AssertionError("the second import did not wait on the shared config import lock");
  }

  private static long waitingImportLocks() {
    return jdbcClient
        .sql(
            """
                select count(*)
                from pg_locks
                where locktype = 'advisory'
                  and classid = :namespace
                  and objid = :key
                  and objsubid = 2
                  and not granted
                """)
        .param("namespace", ConfigSyncApplier.IMPORT_LOCK_NAMESPACE)
        .param("key", ConfigSyncApplier.IMPORT_LOCK_KEY)
        .query(Long.class)
        .single();
  }

  private static void shutdown(ExecutorService executor) {
    executor.shutdown();
    try {
      assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "concurrent imports must exit");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while waiting for the concurrent imports");
    } finally {
      executor.shutdownNow();
    }
  }

  /** 只闸住第一笔事务的首个 provider 更新：真实 UPDATE 完成后通知测试，提交前等待放行。 */
  private static final class GatedProviderRepository extends ProviderRepository {

    private final String gatedName;
    private final CountDownLatch entered;
    private final CountDownLatch release;
    private boolean gated;

    GatedProviderRepository(
        JdbcClient jdbcClient, String gatedName, CountDownLatch entered, CountDownLatch release) {
      super(jdbcClient);
      this.gatedName = gatedName;
      this.entered = entered;
      this.release = release;
    }

    @Override
    public boolean updateByName(Provider provider, long expectedVersion) {
      boolean updated = super.updateByName(provider, expectedVersion);
      if (!gated && gatedName.equals(provider.name())) {
        gated = true;
        entered.countDown();
        awaitRelease();
      }
      return updated;
    }

    private void awaitRelease() {
      try {
        if (!release.await(30, TimeUnit.SECONDS)) {
          throw new AssertionError("the first import was not released");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while holding the first import");
      }
    }
  }
}
