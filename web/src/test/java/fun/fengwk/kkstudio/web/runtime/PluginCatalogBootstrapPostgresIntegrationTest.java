package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogBootstrap;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRuntime;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptResources;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptTasks;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginResourceGateway;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import javax.sql.DataSource;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 真实 Web 启动链路的 PostgreSQL 集成测试：V1 未初始化行上的首次初始化与同版本重启恢复。
 *
 * <p>直接复用容器中真实的 {@link StudioPluginRegistry}、{@link PluginConfigurationRepository} 与事务管理器驱动 {@link
 * PluginCatalogBootstrap}，因此覆盖真实发行物（Media、Mavis 与 OpenCLI）的安装定义、真实 jsonb 持久化与真实指纹计算。任一 Plugin
 * 未配置凭据也可安装与冻结贡献，启动绝不解析凭据或发起远端请求。
 */
class PluginCatalogBootstrapPostgresIntegrationTest extends WebPostgresTestSupport {

  private static final String MAVIS = "minimax-mavis";

  private static final String OPENCLI = "opencli";

  /** 真实 Web 测试 classpath 上注册的全部构建期可选 Plugin。 */
  private static final Set<String> INSTALLED_PLUGIN_IDS = Set.of("media", MAVIS, OPENCLI);

  @Autowired private StudioPluginRegistry registry;
  @Autowired private PluginConfigurationRepository repository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private PluginCredentialStore credentials;
  @Autowired private ObjectProvider<PluginResourceGateway> resourceGateways;
  @Autowired private ObjectProvider<PluginPromptTasks> promptTasks;
  @Autowired private ObjectProvider<PluginPromptResources> promptResources;
  @Autowired private ObjectProvider<HarnessContributor> contributors;
  @Autowired private ObjectProvider<CanvasFunctionAdapter> adapters;
  @Autowired private PluginCatalogRuntime liveRuntime;
  @Autowired private DataSource dataSource;

  /** 容器启动期缓存的冻结快照：从 V1 未初始化形态初始化到 epoch 1 并覆盖全部真实安装定义。 */
  @Test
  void contextStartupInitializesTheAuthoritativeCatalog() {
    assertEquals(INSTALLED_PLUGIN_IDS, Set.copyOf(registry.pluginIds()));
    try (PluginCatalogRuntime.Lease lease = liveRuntime.acquireCurrent()) {
      PluginCatalogSnapshot liveSnapshot = lease.snapshot();
      assertEquals(1, liveSnapshot.epoch());
      assertEquals(
          INSTALLED_PLUGIN_IDS,
          liveSnapshot.configuredPlugins().stream()
              .map(configured -> configured.descriptor().pluginId())
              .collect(Collectors.toSet()));
      assertTrue(
          liveSnapshot.harnessCatalog().findDescriptor(new ContributorId(MAVIS)).isPresent());
      assertEquals(
          Set.of("image.crop"), liveSnapshot.pluginOwnership().get("media").functionNames());
      assertNotNull(liveSnapshot.catalogFingerprint());
    }
  }

  /** 同版本重启必须按持久 revision 恢复：epoch 不推进、历史不新增、值来自已持久 config 而不是当前默认值。 */
  @Test
  void restartRestoresThePersistedRevisionWithoutRewriting() {
    PluginCatalogSnapshot first = bootstrap();
    try {
      assertEquals(1, first.epoch());
      assertEquals(1, epoch());
      assertEquals(INSTALLED_PLUGIN_IDS.size(), historyRows(), "每个安装定义恰好持久化一条历史 revision");
    } finally {
      first.close();
    }

    PluginCatalogSnapshot second = bootstrap();
    try {
      assertEquals(1, second.epoch(), "restart must restore, never re-initialize");
      assertEquals(1, epoch(), "restart must not advance the epoch");
      assertEquals(INSTALLED_PLUGIN_IDS.size(), historyRows(), "restart must not append history");
      assertEquals(first.catalogFingerprint(), second.catalogFingerprint());
      assertTrue(second.harnessCatalog().findDescriptor(new ContributorId(MAVIS)).isPresent());
      assertNotNull(second.capabilityFingerprints().get(MAVIS));
    } finally {
      second.close();
    }
  }

  /** 本 Context 真实的两域来源：内置贡献必须与该 Context 启动期冻结的目录同一来源，绝不写成 plugin-only 目录。 */
  private PluginCatalogSnapshot bootstrap() {
    return new PluginCatalogBootstrap(registry, repository, transactionManager)
        .bootstrap(
            PluginCatalogConfiguration.pluginContexts(
                credentials, resourceGateways, promptTasks, promptResources),
            contributors.orderedStream().toList(),
            adapters.orderedStream().toList());
  }

  private long epoch() {
    return jdbc()
        .sql("select epoch from plugin_catalog_state where id = 1")
        .query(Long.class)
        .single();
  }

  private int historyRows() {
    return jdbc()
        .sql("select count(*) from plugin_configuration_revision")
        .query(Integer.class)
        .single();
  }

  private JdbcClient jdbc() {
    return JdbcClient.create(dataSource);
  }
}
