package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRuntime;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.service.PluginContextFactory;
import fun.fengwk.kkstudio.platform.settings.HttpProxySelector;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import javax.sql.DataSource;

import java.net.ProxySelector;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 真实 Mavis 发行物、真实组合根与隔离 PostgreSQL 上的插件目录回归。
 *
 * <p>覆盖首次并发启动只写一份默认配置、运行中节点按回读应用新修订，以及重启后按持久值（而不是 schema 默认值）恢复。Mavis 的传输被替换为禁止出网的替身，
 * 启动、刷新与重启都不读凭据、不发远端请求。
 */
class PluginCatalogMavisPostgresIntegrationTest {

  private static final String MAVIS = "minimax-mavis";
  private static final String ENABLED_DEFAULTS = "{\"enabled\":true}";
  private static final String DISABLED_VALUES = "{\"enabled\":false}";
  private static final int ENABLED_TOOL_COUNT = 15;

  private final AtomicInteger remoteRequests = new AtomicInteger();
  private final PluginCredentialStore credentialStore = mock(PluginCredentialStore.class);
  private DataSource dataSource;

  @BeforeEach
  void createIsolatedDatabase() {
    dataSource = WebPostgresTestSupport.createIsolatedDatabase();
  }

  /** 两个节点首次并发启动：只写入一份默认配置，双方都按它构建完整目录。 */
  @Test
  void concurrentFirstStartupSeedsTheDefaultsOnce() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<Integer>> toolCounts = new ArrayList<>();
      for (int index = 0; index < 2; index++) {
        toolCounts.add(
            executor.submit(
                () -> {
                  start.await();
                  int[] count = new int[1];
                  contextRunner().run(context -> count[0] = toolCount(runtime(context)));
                  return count[0];
                }));
      }
      start.countDown();
      assertEquals(ENABLED_TOOL_COUNT, toolCounts.get(0).get(30, TimeUnit.SECONDS));
      assertEquals(ENABLED_TOOL_COUNT, toolCounts.get(1).get(30, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
    assertEquals(List.of("1:" + ENABLED_DEFAULTS), storedRows());
    assertEquals(0, remoteRequests.get());
    verifyNoInteractions(credentialStore);
  }

  /** 保存停用修订后运行中节点回读应用；全新上下文按持久值恢复，schema 默认值仍是启用。 */
  @Test
  void runningAndRestartedNodesApplyThePersistedRevision() {
    contextRunner()
        .run(
            running -> {
              PluginCatalogRuntime runtime = runtime(running);
              assertEquals(ENABLED_TOOL_COUNT, toolCount(runtime));

              PluginConfigurationRepository repository =
                  new PluginConfigurationRepository(JdbcClient.create(dataSource));
              new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                  .executeWithoutResult(
                      status -> {
                        assertTrue(repository.update(MAVIS, 1L, DISABLED_VALUES));
                        repository.notifyChanged(MAVIS);
                      });
              runtime.refresh();
              assertEquals(0, toolCount(runtime), "disabled Mavis must contribute no tool");
            });
    assertEquals(List.of("2:" + DISABLED_VALUES), storedRows());

    contextRunner()
        .run(
            restarted -> {
              assertEquals(0, toolCount(runtime(restarted)));
              assertEquals(
                  ENABLED_DEFAULTS,
                  restarted
                      .getBean(StudioPluginRegistry.class)
                      .find(MAVIS)
                      .orElseThrow()
                      .configurationSchema()
                      .defaultsJson());
            });
    assertEquals(List.of("2:" + DISABLED_VALUES), storedRows(), "restart must not rewrite values");
    assertEquals(0, remoteRequests.get());
    verifyNoInteractions(credentialStore);
  }

  private ApplicationContextRunner contextRunner() {
    return RuntimeMavisTestSupport.withMavis(
        new ApplicationContextRunner()
            .withUserConfiguration(PluginCatalogConfiguration.class, RegistryConfiguration.class)
            .withBean(
                PluginContextFactory.class,
                () -> new PluginContextFactory(credentialStore, () -> null, () -> null, () -> null))
            // 隔离组合根提供直连的 selector 依赖，不安装 JVM 全局代理。
            .withBean(
                "systemProxySelector", ProxySelector.class, () -> HttpProxySelector.fixed(null, ""))
            .withBean(
                PluginConfigurationRepository.class,
                () -> new PluginConfigurationRepository(JdbcClient.create(dataSource))),
        remoteRequests);
  }

  private static PluginCatalogRuntime runtime(ApplicationContext context) {
    return context.getBean(PluginCatalogRuntime.class);
  }

  private static int toolCount(PluginCatalogRuntime runtime) {
    try (PluginCatalogRuntime.Lease lease = runtime.acquire()) {
      return lease.snapshot().harnessCatalog().tools().size();
    }
  }

  private List<String> storedRows() {
    return JdbcClient.create(dataSource)
        .sql("select revision || ':' || values_json from plugin_configuration order by plugin_id")
        .query(String.class)
        .list();
  }

  @Configuration(proxyBeanMethods = false)
  static class RegistryConfiguration {

    @Bean
    StudioPluginRegistry studioPluginRegistry(ObjectProvider<StudioPlugin> plugins) {
      return new StudioPluginRegistry(plugins.orderedStream().toList());
    }
  }
}
