package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.harness.common.network.HttpProxySelector;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRuntime;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;
import fun.fengwk.kkstudio.platform.plugin.credential.DatabasePluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.service.PluginAgentDependencies;
import fun.fengwk.kkstudio.platform.plugin.service.PluginConfigurationService;
import fun.fengwk.kkstudio.platform.plugin.service.PluginContextFactory;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import javax.sql.DataSource;

import java.net.ProxySelector;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 真实隔离 PostgreSQL 上 Media 插件的归属与启停往返。
 *
 * <p>Media 是 {@code image.crop} 的唯一 owner：启动写入启用的默认配置，停用后目录不再提供该函数且不回退到内置 adapter，重新启用后由同一 owner
 * 恢复。 Media 不声明秘密，从不读写凭据。
 */
class MediaPluginPostgresIntegrationTest {

  private static final String PLUGIN_ID = "media";
  private static final String IMAGE_CROP_FUNCTION = "image.crop";
  private static final String PLUGIN_PACKAGE_PREFIX = "fun.fengwk.kkstudio.plugin.";
  private static final String MEDIA_AUTO_CONFIGURATION =
      PLUGIN_PACKAGE_PREFIX + "media.MediaAutoConfiguration";
  private static final String MEDIA_FUNCTION_ADAPTER =
      PLUGIN_PACKAGE_PREFIX + "media.MediaFunctionAdapter";

  private DataSource dataSource;
  private PluginConfigurationRepository configurationRepository;
  private PluginCredentialRepository credentialRepository;

  @BeforeEach
  void setUp() {
    dataSource = WebPostgresTestSupport.createIsolatedDatabase();
    JdbcClient jdbcClient = JdbcClient.create(dataSource);
    configurationRepository = new PluginConfigurationRepository(jdbcClient);
    credentialRepository = new PluginCredentialRepository(jdbcClient);
  }

  /** 启动写入启用的默认配置：目录只有 owner 贡献的 image.crop，没有内置 adapter 兜底。 */
  @Test
  void startupSeedsTheEnabledMediaOwner() {
    createContextRunner()
        .run(
            context -> {
              StudioPluginRegistry registry = context.getBean(StudioPluginRegistry.class);
              assertEquals(1, registry.size(), "media must be the only installed StudioPlugin");
              assertTrue(
                  context.getBeansOfType(CanvasFunctionAdapter.class).isEmpty(),
                  "media must not also publish a builtin adapter bean");

              try (PluginCatalogRuntime.Lease lease =
                  context.getBean(PluginCatalogRuntime.class).acquire()) {
                PluginCatalogSnapshot snapshot = lease.snapshot();
                assertEquals(List.of(IMAGE_CROP_FUNCTION), functionNames(snapshot));
                CanvasFunctionCatalog.RegisteredFunction registered =
                    snapshot.canvasFunctionCatalog().require(IMAGE_CROP_FUNCTION);
                assertTrue(registered.enabled(), "default configuration enables image.crop");
                assertNull(registered.unavailableReason());
                assertEquals(MEDIA_FUNCTION_ADAPTER, registered.adapter().getClass().getName());
                PluginCatalogSnapshot.ConfiguredPlugin owner = snapshot.configuredPlugins().get(0);
                assertEquals(Set.of(IMAGE_CROP_FUNCTION), owner.functionNames());
                assertTrue(owner.contributorIds().isEmpty(), "media contributes no harness");
              }
              assertEquals(
                  new PluginConfigurationRow(PLUGIN_ID, 1L, "{\"enabled\":true}"),
                  configurationRepository.find(PLUGIN_ID).orElseThrow());
              assertTrue(credentialRepository.find(PLUGIN_ID).isEmpty());
            });
  }

  /** 停用后目录不再提供 image.crop，重新启用后由同一 owner 恢复。 */
  @Test
  void disablingAndReenablingRoundTripsTheOwnedFunction() {
    createContextRunner()
        .run(
            context -> {
              PluginCatalogRuntime runtime = context.getBean(PluginCatalogRuntime.class);
              PluginConfigurationService configuration = configurationService(context, runtime);

              configuration.configure(PLUGIN_ID, 1L, "{\"enabled\":false}", Map.of());
              try (PluginCatalogRuntime.Lease lease = runtime.acquire()) {
                assertTrue(lease.snapshot().canvasFunctionCatalog().list().isEmpty());
                assertTrue(lease.snapshot().configuredPlugins().get(0).functionNames().isEmpty());
              }
              assertTrue(context.getBeansOfType(CanvasFunctionAdapter.class).isEmpty());

              configuration.configure(PLUGIN_ID, 2L, "{\"enabled\":true}", Map.of());
              try (PluginCatalogRuntime.Lease lease = runtime.acquire()) {
                CanvasFunctionCatalog.RegisteredFunction registered =
                    lease.snapshot().canvasFunctionCatalog().require(IMAGE_CROP_FUNCTION);
                assertTrue(registered.enabled());
                assertEquals(MEDIA_FUNCTION_ADAPTER, registered.adapter().getClass().getName());
              }
              assertEquals(3L, configurationRepository.find(PLUGIN_ID).orElseThrow().revision());
            });
  }

  private static List<String> functionNames(PluginCatalogSnapshot snapshot) {
    return snapshot.canvasFunctionCatalog().list().stream()
        .map(CanvasFunctionCatalog.RegisteredFunction::function)
        .map(CanvasFunctionDefinition::name)
        .toList();
  }

  /** 隔离组合根：真实 PostgreSQL 仓储，runtime-only 插件经反射装配为容器里的安装定义。 */
  private ApplicationContextRunner createContextRunner() {
    Class<?> mediaAutoConfiguration;
    try {
      mediaAutoConfiguration = Class.forName(MEDIA_AUTO_CONFIGURATION);
    } catch (ClassNotFoundException error) {
      throw new AssertionError(
          "MediaAutoConfiguration must be available on the test runtime classpath", error);
    }
    DatabasePluginCredentialStore credentialStore =
        new DatabasePluginCredentialStore(credentialRepository, Clock.systemUTC());
    return new ApplicationContextRunner()
        .withUserConfiguration(PluginCatalogConfiguration.class, RegistryConfiguration.class)
        .withConfiguration(AutoConfigurations.of(mediaAutoConfiguration))
        // 隔离组合根提供直连的 selector 依赖，不安装 JVM 全局代理。
        .withBean(
            "systemProxySelector", ProxySelector.class, () -> HttpProxySelector.fixed(null, ""))
        .withBean(
            PluginContextFactory.class,
            () -> new PluginContextFactory(credentialStore, () -> null, () -> null, () -> null))
        .withBean(PluginConfigurationRepository.class, () -> configurationRepository);
  }

  /** 与生产一致的写入口；提交后立即在本节点应用。 */
  private PluginConfigurationService configurationService(
      ApplicationContext context, PluginCatalogRuntime runtime) {
    StudioPluginRegistry registry = context.getBean(StudioPluginRegistry.class);
    return new PluginConfigurationService(
        registry,
        configurationRepository,
        credentialRepository,
        context.getBean(PluginContextFactory.class),
        new PluginAgentDependencies(
            registry, configurationRepository, mock(AgentDefinitionRepository.class)),
        new DataSourceTransactionManager(dataSource),
        runtime::refresh,
        () -> {});
  }

  @Configuration(proxyBeanMethods = false)
  static class RegistryConfiguration {
    @Bean
    StudioPluginRegistry studioPluginRegistry(ObjectProvider<StudioPlugin> plugins) {
      return new StudioPluginRegistry(plugins.orderedStream().toList());
    }
  }
}
