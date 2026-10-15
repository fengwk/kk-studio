package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigurationState;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginAuthContextFingerprint;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogBootstrap;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogFingerprint;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogManifest;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRecord;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRuntime;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * 运行期 epoch 对账的真实隔离 PostgreSQL 与真实 Mavis JAR 回归。
 *
 * <p>两个运行期节点共用同一个隔离库（全新 UUID 库 + baseline/dev seed，绝不与共享 web 库共享权威 catalog），但不共享任何运行期状态：每个节点各自
 * {@link PluginCatalogBootstrap} 启动并在 {@link PluginCatalogRuntime#refresh()}
 * 时按权威回读整体应用或整体保留。写入方始终经真实 {@code PluginConfigurationRepository} 的唯一 CAS 提交入口落库。
 *
 * <p>插件来源是真实发行物：Mavis 从运行期 JAR 经 {@link RuntimeMavisTestSupport} 装配，传输被替换为禁止出网的替身，因此启动、对账与重启都绝不发起远端
 * 或收费调用。
 */
class PluginCatalogRuntimePostgresIntegrationTest {

  private static final String MAVIS = "minimax-mavis";
  private static final String ENABLED_DEFAULTS = "{\"enabled\":true}";
  private static final String DISABLED_VALUES = "{\"enabled\":false}";
  private static final int ENABLED_TOOL_COUNT = 15;

  private DataSource dataSource;

  /** 每个测试一个全新独立库：权威 catalog 与共享 web 库完全隔离。 */
  @BeforeEach
  void createIsolatedDatabase() {
    dataSource = WebPostgresTestSupport.createIsolatedDatabase();
  }

  /** 两个运行期节点对同一权威推进各自对账：都发布同一 epoch、同一指纹与同一两域事实，且都不发远端请求。 */
  @Test
  void dualRuntimesReconcileToTheSameAuthoritativeEpoch() {
    AtomicInteger remoteRequests = new AtomicInteger();
    contextRunner(remoteRequests)
        .run(
            context -> {
              PluginConfigurationRepository repository = repository();
              PluginCatalogRuntime first = node(context, repository);
              PluginCatalogRuntime second = node(context, repository);
              try {
                assertEquals(new PluginCatalogRuntime.RuntimeState(1, 1, null), first.state());
                assertEquals(new PluginCatalogRuntime.RuntimeState(1, 1, null), second.state());

                // 写入方在权威单行上提交停用的 revision 2。
                commitDisabledRevision(context, repository);

                first.refresh();
                // 第二个节点在权威推进后才对账：同一 epoch 必须发布同一目录。
                second.refresh();

                assertEquals(new PluginCatalogRuntime.RuntimeState(2, 2, null), first.state());
                assertEquals(new PluginCatalogRuntime.RuntimeState(2, 2, null), second.state());
                try (PluginCatalogRuntime.Lease firstLease = first.acquireCurrent();
                    PluginCatalogRuntime.Lease secondLease = second.acquireCurrent()) {
                  assertEquals(2, firstLease.snapshot().epoch());
                  assertEquals(2, secondLease.snapshot().epoch());
                  assertTrue(
                      firstLease.snapshot().harnessCatalog().tools().isEmpty(),
                      "停用的持久 revision 不得被 schema 默认值替换");
                  assertEquals(
                      firstLease.snapshot().catalogFingerprint(),
                      secondLease.snapshot().catalogFingerprint(),
                      "两个节点必须对账到同一完整目录指纹");
                  assertEquals(
                      DISABLED_VALUES,
                      firstLease.snapshot().configuredPlugins().get(0).config().valuesJson());
                }

                // 对账不再推进 epoch，也不重复发布。
                first.refresh();
                second.refresh();
                assertEquals(new PluginCatalogRuntime.RuntimeState(2, 2, null), first.state());
                assertEquals(2, epoch(), "对账绝不推进权威 epoch");
                assertEquals(2, historyRows(), "对账绝不追加历史");
                verifyNoInteractions(context.getBean(PluginCredentialStore.class));
              } finally {
                first.close();
                second.close();
              }
            });
    assertEquals(0, remoteRequests.get(), "对账与启动都不得发起远端请求");
  }

  /** 真实 Mavis JAR 的启用 → 停用 → 重启：重启必须按持久 revision 恢复停用，绝不回落到默认启用。 */
  @Test
  void mavisRevisionStaysDisabledAcrossRuntimeRestarts() {
    AtomicInteger remoteRequests = new AtomicInteger();
    String[] enabledFingerprint = new String[1];
    contextRunner(remoteRequests)
        .run(
            context -> {
              PluginConfigurationRepository repository = repository();
              PluginCatalogRuntime enabled = node(context, repository);
              try {
                assertEquals(new PluginCatalogRuntime.RuntimeState(1, 1, null), enabled.state());
                try (PluginCatalogRuntime.Lease lease = enabled.acquireCurrent()) {
                  assertEquals(
                      ENABLED_TOOL_COUNT,
                      lease.snapshot().harnessCatalog().tools().size(),
                      "首次初始化必须冻结 schema 默认值下的完整 Mavis 目录");
                  enabledFingerprint[0] = lease.snapshot().catalogFingerprint();
                }
                commitDisabledRevision(context, repository);
                enabled.refresh();
                assertEquals(new PluginCatalogRuntime.RuntimeState(2, 2, null), enabled.state());
                try (PluginCatalogRuntime.Lease lease = enabled.acquireCurrent()) {
                  assertTrue(
                      lease.snapshot().harnessCatalog().tools().isEmpty(),
                      "运行期对账必须应用持久化的停用 revision");
                  assertNotEquals(
                      enabledFingerprint[0],
                      lease.snapshot().catalogFingerprint(),
                      "停用 revision 必须绑定不同的完整目录指纹");
                }
              } finally {
                enabled.close();
              }

              // 重启：全新运行期按持久 epoch 2 恢复，绝不采用当前 schema 默认值。
              PluginCatalogRuntime restarted = node(context, repository);
              try {
                assertEquals(new PluginCatalogRuntime.RuntimeState(2, 2, null), restarted.state());
                try (PluginCatalogRuntime.Lease lease = restarted.acquireCurrent()) {
                  assertEquals(2, lease.snapshot().epoch());
                  assertTrue(
                      lease.snapshot().harnessCatalog().tools().isEmpty(),
                      "重启必须遵守持久 revision 而不是默认启用");
                  assertEquals(
                      DISABLED_VALUES,
                      lease.snapshot().configuredPlugins().get(0).config().valuesJson());
                }
                assertEquals(
                    ENABLED_DEFAULTS,
                    context
                        .getBean(StudioPluginRegistry.class)
                        .find(MAVIS)
                        .orElseThrow()
                        .configurationSchema()
                        .defaultsJson(),
                    "只有持久值能解释停用");
                assertEquals(2, epoch(), "重启不得推进 epoch");
                assertEquals(2, historyRows(), "重启不得追加历史");
                verifyNoInteractions(context.getBean(PluginCredentialStore.class));
              } finally {
                restarted.close();
              }
            });
    assertEquals(0, remoteRequests.get(), "启用、停用与重启都不得发起远端请求");
  }

  /** 旧 epoch 的任务在运行中与重启后都能按持久历史严格重建：真实 Mavis JAR、离线 transport、绝不发远端请求。 */
  @Test
  void olderMavisEpochIsRebuiltFromPersistedHistoryAcrossRestarts() {
    AtomicInteger remoteRequests = new AtomicInteger();
    String[] enabledFingerprint = new String[1];
    contextRunner(remoteRequests)
        .run(
            context -> {
              PluginConfigurationRepository repository = repository();
              PluginCatalogRuntime original = node(context, repository);
              try {
                try (PluginCatalogRuntime.Lease lease = original.acquireCurrent()) {
                  enabledFingerprint[0] = lease.snapshot().catalogFingerprint();
                  assertEquals(
                      ENABLED_TOOL_COUNT, lease.snapshot().harnessCatalog().tools().size());
                }
                commitDisabledRevision(context, repository);
                original.refresh();
                assertEquals(new PluginCatalogRuntime.RuntimeState(2, 2, null), original.state());

                // 运行中的旧 epoch 任务仍按 epoch 1 的完整目录运行，且不倒退当前发布。
                try (PluginCatalogRuntime.Lease history = original.acquire(1)) {
                  assertEquals(1, history.snapshot().epoch());
                  assertEquals(
                      ENABLED_TOOL_COUNT,
                      history.snapshot().harnessCatalog().tools().size(),
                      "旧 epoch 必须重建完整 Mavis 目录，而不是拼接当前其它插件");
                  assertEquals(enabledFingerprint[0], history.snapshot().catalogFingerprint());
                }
                assertEquals(new PluginCatalogRuntime.RuntimeState(2, 2, null), original.state());
              } finally {
                original.close();
              }

              // 重启：全新运行期先按 epoch 2 恢复当前视图，再按持久历史重建 epoch 1。
              PluginCatalogRuntime restarted = node(context, repository);
              try {
                assertEquals(new PluginCatalogRuntime.RuntimeState(2, 2, null), restarted.state());
                try (PluginCatalogRuntime.Lease current = restarted.acquireCurrent()) {
                  assertTrue(current.snapshot().harnessCatalog().tools().isEmpty());
                }
                try (PluginCatalogRuntime.Lease first = restarted.acquire(1);
                    PluginCatalogRuntime.Lease reused = restarted.acquire(1)) {
                  assertEquals(1, first.snapshot().epoch());
                  assertEquals(
                      ENABLED_TOOL_COUNT,
                      first.snapshot().harnessCatalog().tools().size(),
                      "重启后必须按持久历史严格重建旧 epoch");
                  assertEquals(
                      enabledFingerprint[0],
                      first.snapshot().catalogFingerprint(),
                      "旧 epoch 的完整目录指纹必须与首次发布一致");
                  assertSame(first.snapshot(), reused.snapshot(), "再取同 epoch 必须复用现有视图");
                }
                assertEquals(new PluginCatalogRuntime.RuntimeState(2, 2, null), restarted.state());
                assertEquals(2, epoch(), "历史租用绝不推进 epoch");
                assertEquals(2, historyRows(), "历史租用绝不追加历史");
              } finally {
                restarted.close();
              }
            });
    assertEquals(0, remoteRequests.get(), "历史重建与复用都不得发起远端请求");
  }

  /** 一个运行期节点：真实 Mavis 安装定义、真实两域来源、真实仓储与它自己的初始快照。 */
  private PluginCatalogRuntime node(
      ConfigurableApplicationContext context, PluginConfigurationRepository repository) {
    List<HarnessContributor> builtins =
        List.copyOf(context.getBeansOfType(HarnessContributor.class).values());
    List<CanvasFunctionAdapter> adapters =
        List.copyOf(context.getBeansOfType(CanvasFunctionAdapter.class).values());
    BiFunction<String, PluginCredentialReference, PluginContext> contexts =
        PluginCatalogConfiguration.pluginContexts(
            context.getBean(PluginCredentialStore.class),
            context.getBeanProvider(PluginResourceGateway.class));
    PluginCatalogBootstrap bootstrap = context.getBean(PluginCatalogBootstrap.class);
    return new PluginCatalogRuntime(
        bootstrap,
        repository,
        contexts,
        builtins,
        adapters,
        bootstrap.bootstrap(contexts, builtins, adapters));
  }

  /**
   * 以真实仓储的唯一 CAS 入口提交一次停用 revision：先在 JAR 上构建完整候选取得能力指纹，再把完整 manifest 与 history 一次事务落库。
   *
   * <p>写入方候选由 try-with-resources 独立关闭，绝不与任何运行期节点共享实例。
   */
  private void commitDisabledRevision(
      ConfigurableApplicationContext context, PluginConfigurationRepository repository) {
    StudioPluginRegistry registry = context.getBean(StudioPluginRegistry.class);
    List<HarnessContributor> builtins =
        List.copyOf(context.getBeansOfType(HarnessContributor.class).values());
    List<CanvasFunctionAdapter> adapters =
        List.copyOf(context.getBeansOfType(CanvasFunctionAdapter.class).values());
    BiFunction<String, PluginCredentialReference, PluginContext> contexts =
        PluginCatalogConfiguration.pluginContexts(
            context.getBean(PluginCredentialStore.class),
            context.getBeanProvider(PluginResourceGateway.class));
    StudioPlugin plugin = registry.find(MAVIS).orElseThrow();
    PluginCatalogRecord authority = repository.readCatalog();
    long epoch = authority.epoch() + 1;
    PluginCatalogManifest.Entry baseline = authority.manifest().plugins().get(MAVIS);
    String schemaFingerprint =
        PluginCatalogFingerprint.schema(plugin.configurationSchema(), plugin.authenticationSpec());
    String contextFingerprint =
        PluginAuthContextFingerprint.derive(
            MAVIS,
            plugin.descriptor().version(),
            schemaFingerprint,
            plugin.authenticationSpec(),
            JsonValues.readTree(plugin.configurationSchema().resolveValues(DISABLED_VALUES)));
    try (PluginCatalogSnapshot candidate =
        PluginCatalogSnapshot.build(
            epoch,
            registry,
            Map.of(
                MAVIS,
                new PluginConfigurationState(
                    new PluginConfig(epoch, DISABLED_VALUES),
                    new PluginCredentialReference(
                        MAVIS, baseline.authGeneration(), contextFingerprint))),
            contexts,
            builtins,
            adapters)) {
      String capabilityFingerprint = candidate.capabilityFingerprints().get(MAVIS);
      PluginCatalogManifest manifest =
          new PluginCatalogManifest(
              Map.of(
                  MAVIS,
                  new PluginCatalogManifest.Entry(
                      epoch,
                      plugin.descriptor().version(),
                      schemaFingerprint,
                      capabilityFingerprint,
                      baseline.authGeneration(),
                      contextFingerprint)),
              candidate.catalogFingerprint());
      PluginConfigurationRow changed =
          new PluginConfigurationRow(
              MAVIS,
              epoch,
              plugin.descriptor().version(),
              schemaFingerprint,
              capabilityFingerprint,
              baseline.authGeneration(),
              contextFingerprint,
              List.of(),
              DISABLED_VALUES);
      assertTrue(
          Boolean.TRUE.equals(
              transactionTemplate()
                  .execute(status -> repository.commit(epoch - 1, List.of(changed), manifest))),
          "the disabled revision must win the epoch CAS");
    }
    assertEquals(epoch, repository.readCatalog().epoch());
    assertEquals(
        DISABLED_VALUES, repository.readConfiguration(MAVIS, epoch).orElseThrow().valuesJson());
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

  private TransactionTemplate transactionTemplate() {
    return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
  }

  private long epoch() {
    return JdbcClient.create(dataSource)
        .sql("select epoch from plugin_catalog_state where id = 1")
        .query(Long.class)
        .single();
  }

  private int historyRows() {
    return JdbcClient.create(dataSource)
        .sql("select count(*) from plugin_configuration_revision")
        .query(Integer.class)
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
