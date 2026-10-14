package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigurationState;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogManifest;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialReference;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.PostgresqlPluginCatalogChangeNotifier;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.PostgresqlPluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.store.PluginConfigurationJdbcStore;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginResourceGateway;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import javax.sql.DataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 插件 JAR 与宿主组合根的权威配置回归：首次启动落库、提交被停用的 revision 2、重启后按持久值恢复。
 *
 * <p>每个阶段都走真实组合根与真实发行依赖：真实 {@link PostgresqlPluginConfigurationRepository}（真实 PostgreSQL 的 text
 * 普通值存储）、真实事务管理器、真实 Mavis auto-configuration（离线 transport，启动与恢复都不发远端请求、不读凭据）、真实 {@code
 * PluginCatalogConfiguration} 的 context 工厂与内置两域来源。因此明确证明恢复用的是持久化的 {@code enabled=false}，而不是 schema
 * 默认值 {@code enabled=true}。
 *
 * <p>本测试只用 {@link ApplicationContextRunner} 装配插件目录，不需要真实 Web 上下文：它在共享物理容器上取自己的独立数据库，因此既不受 其它测试类的权威
 * catalog 影响，也不会把 plugin-only 目录写进共享 web 库。
 */
class PluginCatalogMavisRevisionPostgresIntegrationTest {

  private static final String MAVIS = "minimax-mavis";
  private static final String ENABLED_DEFAULTS = "{\"enabled\":true}";
  private static final String DISABLED_VALUES = "{\"enabled\":false}";
  private static final int ENABLED_TOOL_COUNT = 15;

  private DataSource dataSource;

  /** 每个测试一个全新独立库（baseline + dev seed），目录身份与共享 web 库完全隔离。 */
  @BeforeEach
  void createIsolatedDatabase() {
    dataSource = WebPostgresTestSupport.createIsolatedDatabase();
  }

  /** 两个同发行物节点首次并发启动：只落一组历史，双方得到同一完整 epoch。 */
  @Test
  void concurrentFirstStartupRestoresTheSingleWinningEpoch() throws Exception {
    AtomicInteger remoteRequests = new AtomicInteger();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<String>> fingerprints = new ArrayList<>();
      for (int index = 0; index < 2; index++) {
        fingerprints.add(
            executor.submit(
                () -> {
                  start.await();
                  String[] fingerprint = new String[1];
                  contextRunner(remoteRequests)
                      .run(
                          context -> {
                            PluginCatalogSnapshot snapshot =
                                context.getBean(PluginCatalogSnapshot.class);
                            assertEquals(1, snapshot.epoch());
                            assertEquals(
                                ENABLED_TOOL_COUNT, snapshot.harnessCatalog().tools().size());
                            verifyNoInteractions(context.getBean(PluginCredentialStore.class));
                            fingerprint[0] = snapshot.catalogFingerprint();
                          });
                  return fingerprint[0];
                }));
      }
      start.countDown();
      assertEquals(
          fingerprints.get(0).get(30, TimeUnit.SECONDS),
          fingerprints.get(1).get(30, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
    JdbcClient jdbc = JdbcClient.create(dataSource);
    assertEquals(1, epoch(jdbc));
    assertEquals(1, historyRows(jdbc));
    assertEquals(0, remoteRequests.get());
  }

  @Test
  void startupPersistsThenRestoresADisabledRevisionAcrossFreshContexts() {
    JdbcClient jdbc = JdbcClient.create(dataSource);
    PluginConfigurationRepository repository = repository();
    TransactionTemplate transactionTemplate =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    AtomicInteger remoteRequests = new AtomicInteger();
    List<String> firstStartupTools = new ArrayList<>();
    String[] catalogFingerprint = new String[1];

    // 1. 真实首次启动：V1 epoch=0 单行上初始化，权威状态必须真的落库。
    contextRunner(remoteRequests)
        .run(
            context -> {
              PluginCatalogSnapshot snapshot = context.getBean(PluginCatalogSnapshot.class);
              assertEquals(1, snapshot.epoch());
              assertEquals(ENABLED_TOOL_COUNT, snapshot.harnessCatalog().tools().size());
              snapshot
                  .harnessCatalog()
                  .tools()
                  .forEach(tool -> firstStartupTools.add(tool.definition().descriptor().name()));
              catalogFingerprint[0] = snapshot.catalogFingerprint();
              verifyNoInteractions(context.getBean(PluginCredentialStore.class));
            });
    assertEquals(1, epoch(jdbc), "the first startup must persist epoch 1");
    assertEquals(1, historyRows(jdbc), "the first startup must persist exactly one revision");
    assertEquals(ENABLED_DEFAULTS, values(jdbc, MAVIS, 1));
    assertEquals(ENABLED_TOOL_COUNT, firstStartupTools.size());

    PluginConfigurationRow revisionOne = repository.readConfiguration(MAVIS, 1).orElseThrow();
    assertEquals(ENABLED_DEFAULTS, revisionOne.valuesJson());

    // 2. 在同一发行装配里提交完整的被停用 revision 2（manifest + history 一次事务落库）。
    String[] disabledCatalogFingerprint = new String[1];
    contextRunner(remoteRequests)
        .run(
            context -> {
              StudioPluginRegistry registry = context.getBean(StudioPluginRegistry.class);
              List<HarnessContributor> builtins =
                  List.copyOf(context.getBeansOfType(HarnessContributor.class).values());
              List<CanvasFunctionAdapter> adapters =
                  List.copyOf(context.getBeansOfType(CanvasFunctionAdapter.class).values());
              try (PluginCatalogSnapshot disabled =
                  PluginCatalogSnapshot.build(
                      2L,
                      registry,
                      Map.of(
                          MAVIS,
                          new PluginConfigurationState(
                              new PluginConfig(2, DISABLED_VALUES),
                              new PluginCredentialReference(
                                  MAVIS,
                                  revisionOne.authGeneration(),
                                  revisionOne.contextFingerprint()))),
                      PluginCatalogConfiguration.pluginContexts(
                          context.getBean(PluginCredentialStore.class),
                          context.getBeanProvider(PluginResourceGateway.class)),
                      builtins,
                      adapters)) {
                assertEquals(
                    0,
                    disabled.harnessCatalog().tools().size(),
                    "disabled Mavis must contribute no tool");
                disabledCatalogFingerprint[0] = disabled.catalogFingerprint();
                String capabilityFingerprint = disabled.capabilityFingerprints().get(MAVIS);
                PluginCatalogManifest manifest =
                    new PluginCatalogManifest(
                        Map.of(
                            MAVIS,
                            new PluginCatalogManifest.Entry(
                                2,
                                revisionOne.codeVersion(),
                                revisionOne.schemaFingerprint(),
                                capabilityFingerprint,
                                revisionOne.authGeneration(),
                                revisionOne.contextFingerprint())),
                        disabledCatalogFingerprint[0]);
                PluginConfigurationRow changed =
                    new PluginConfigurationRow(
                        MAVIS,
                        2,
                        revisionOne.codeVersion(),
                        revisionOne.schemaFingerprint(),
                        capabilityFingerprint,
                        revisionOne.authGeneration(),
                        revisionOne.contextFingerprint(),
                        List.of(),
                        DISABLED_VALUES);
                assertTrue(
                    Boolean.TRUE.equals(
                        transactionTemplate.execute(
                            status -> repository.commit(1, List.of(changed), manifest))),
                    "the revision 2 commit must win the epoch CAS");
              }
              verifyNoInteractions(context.getBean(PluginCredentialStore.class));
            });
    assertEquals(2, epoch(jdbc));
    assertEquals(2, historyRows(jdbc));
    assertEquals(DISABLED_VALUES, values(jdbc, MAVIS, 2));

    // 3. 全新上下文重启：必须按持久 revision 2 恢复，仍不读凭据、不发远端请求。
    contextRunner(remoteRequests)
        .run(
            context -> {
              PluginCatalogSnapshot snapshot = context.getBean(PluginCatalogSnapshot.class);
              assertEquals(2, snapshot.epoch(), "restart must restore the persisted epoch");
              assertTrue(
                  snapshot.harnessCatalog().tools().isEmpty(),
                  "the persisted disabled revision must not be replaced by the enabled default");
              assertEquals(disabledCatalogFingerprint[0], snapshot.catalogFingerprint());
              assertEquals(
                  DISABLED_VALUES, snapshot.configuredPlugins().get(0).config().valuesJson());
              assertEquals(
                  ENABLED_DEFAULTS,
                  context
                      .getBean(StudioPluginRegistry.class)
                      .find(MAVIS)
                      .orElseThrow()
                      .configurationSchema()
                      .defaultsJson(),
                  "the schema default stays enabled, so only the persisted value can explain disabled");
              verifyNoInteractions(context.getBean(PluginCredentialStore.class));
            });
    assertEquals(2, epoch(jdbc), "restart must not advance the epoch");
    assertEquals(2, historyRows(jdbc), "restart must not append history");
    assertEquals(0, remoteRequests.get(), "startup and restore must not issue remote requests");
    assertNotEquals(
        catalogFingerprint[0],
        disabledCatalogFingerprint[0],
        "the disabled revision must bind a different frozen catalog fingerprint");
  }

  private ApplicationContextRunner contextRunner(AtomicInteger remoteRequests) {
    return RuntimeMavisTestSupport.withMavis(
        new ApplicationContextRunner()
            .withUserConfiguration(PluginCatalogConfiguration.class, RegistryConfiguration.class)
            .withBean(PluginCredentialStore.class, () -> mock(PluginCredentialStore.class))
            .withBean(PluginConfigurationRepository.class, this::repository)
            .withBean(
                PlatformTransactionManager.class,
                () -> new DataSourceTransactionManager(dataSource)),
        remoteRequests);
  }

  private PluginConfigurationRepository repository() {
    return new PostgresqlPluginConfigurationRepository(
        new PluginConfigurationJdbcStore(JdbcClient.create(dataSource)),
        new PostgresqlPluginCatalogChangeNotifier(new JdbcTemplate(dataSource)));
  }

  private static long epoch(JdbcClient jdbc) {
    return jdbc.sql("select epoch from plugin_catalog_state where id = 1")
        .query(Long.class)
        .single();
  }

  private static int historyRows(JdbcClient jdbc) {
    return jdbc.sql("select count(*) from plugin_configuration_revision")
        .query(Integer.class)
        .single();
  }

  private static String values(JdbcClient jdbc, String pluginId, long revision) {
    return jdbc.sql(
            "select values_json from plugin_configuration_revision"
                + " where plugin_id = :pluginId and revision = :revision")
        .param("pluginId", pluginId)
        .param("revision", revision)
        .query(String.class)
        .single();
  }

  @Configuration(proxyBeanMethods = false)
  static class RegistryConfiguration {

    @Bean
    StudioPluginRegistry studioPluginRegistry(ObjectProvider<StudioPlugin> plugins) {
      return new StudioPluginRegistry(plugins.orderedStream().toList());
    }
  }
}
