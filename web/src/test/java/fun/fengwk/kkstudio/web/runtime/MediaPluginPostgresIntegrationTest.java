package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.harness.common.network.HttpProxySelector;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogManifest;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRecord;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRuntime;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;
import fun.fengwk.kkstudio.platform.plugin.credential.DatabasePluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialCodec;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialKeyLoader;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.PostgresqlPluginCatalogChangeNotifier;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.PostgresqlPluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.PostgresqlPluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.store.PluginConfigurationJdbcStore;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.store.PluginCredentialJdbcStore;
import fun.fengwk.kkstudio.platform.plugin.service.PluginConfigurationCommitService;
import fun.fengwk.kkstudio.platform.plugin.service.PluginContextFactory;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import javax.sql.DataSource;

import java.io.IOException;
import java.net.ProxySelector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 真实隔离 PostgreSQL 上 Media 插件作为统一 StudioPlugin owner 的目录与生命周期集成测试。
 *
 * <p>验证： 1. 独占隔离数据库，runtime-only 插件通过反射加载配置，安装定义只注册唯一 owner； 2. 默认启用时由配置实例贡献 {@code
 * image.crop}，不是宿主内置 adapter； 3. 持久 manifest 与配置历史和 live 快照同身份，且没有第二个 owner； 4. 保存 disabled 并应用新
 * epoch 后新目录不再有 {@code image.crop}； 5. 历史 epoch 仍可租用并恢复原函数，租用结束后当前快照保持最新。
 *
 * <p>本测试不 compile import 任何 Plugin 实现，plugin 只在 web 的 runtime classpath 上。
 */
class MediaPluginPostgresIntegrationTest {

  private static final String PLUGIN_ID = "media";

  private static final String IMAGE_CROP_FUNCTION = "image.crop";

  private static final String PLUGIN_PACKAGE_PREFIX = "fun.fengwk.kkstudio.plugin.";

  private static final String MEDIA_AUTO_CONFIGURATION =
      PLUGIN_PACKAGE_PREFIX + "media.MediaAutoConfiguration";

  private static final String MEDIA_FUNCTION_ADAPTER =
      PLUGIN_PACKAGE_PREFIX + "media.MediaFunctionAdapter";

  @TempDir Path tempDir;

  private DataSource dataSource;
  private JdbcClient jdbcClient;
  private PluginConfigurationRepository configurationRepository;
  private PluginCredentialRepository credentialRepository;
  private PluginCredentialCodec codec;
  private PluginCredentialKeyLoader keyLoader;
  private PluginCredentialStore credentialStore;
  private PlatformTransactionManager transactionManager;

  @BeforeEach
  void setUp() throws IOException {
    dataSource = WebPostgresTestSupport.createIsolatedDatabase();
    jdbcClient = JdbcClient.create(dataSource);
    configurationRepository =
        new PostgresqlPluginConfigurationRepository(
            new PluginConfigurationJdbcStore(jdbcClient),
            new PostgresqlPluginCatalogChangeNotifier(new JdbcTemplate(dataSource)));
    credentialRepository =
        new PostgresqlPluginCredentialRepository(new PluginCredentialJdbcStore(jdbcClient));
    codec = new PluginCredentialCodec();

    Path keyFile = tempDir.resolve("media-integration-master.key");
    Files.write(keyFile, "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));
    try {
      Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException ignored) {
      // 兼容不支持 POSIX 文件权限的运行环境
    }
    keyLoader = new PluginCredentialKeyLoader(keyFile.toAbsolutePath().toString());
    credentialStore =
        new DatabasePluginCredentialStore(
            credentialRepository, configurationRepository, codec, keyLoader, Clock.systemUTC());
    transactionManager = new DataSourceTransactionManager(dataSource);
  }

  /** 起始 epoch 由唯一 owner 初始化：目录里只有 owner 贡献的 image.crop，且没有内置 adapter 兜底。 */
  @Test
  void bootstrapInitializesEnabledMediaOwnerInIsolatedDatabase() {
    createContextRunner()
        .run(
            context -> {
              PluginCatalogRuntime runtime = context.getBean(PluginCatalogRuntime.class);
              StudioPluginRegistry registry = context.getBean(StudioPluginRegistry.class);

              Optional<StudioPlugin> plugin = registry.find(PLUGIN_ID);
              assertTrue(
                  plugin.isPresent(), "media plugin must be registered via auto-configuration");
              assertEquals("Media", plugin.get().descriptor().name());
              assertEquals("1.0.0", plugin.get().descriptor().version());
              assertEquals(1, registry.size(), "media must be the only installed StudioPlugin");
              assertTrue(
                  context.getBeansOfType(CanvasFunctionAdapter.class).isEmpty(),
                  "media must not also publish a builtin adapter bean");

              try (PluginCatalogRuntime.Lease lease = runtime.acquireCurrent()) {
                PluginCatalogSnapshot snapshot = lease.snapshot();
                assertEquals(1L, snapshot.epoch(), "first bootstrap must advance epoch to 1");

                CanvasFunctionCatalog catalog = snapshot.canvasFunctionCatalog();
                assertEquals(
                    List.of(IMAGE_CROP_FUNCTION),
                    catalog.list().stream()
                        .map(CanvasFunctionCatalog.RegisteredFunction::function)
                        .map(CanvasFunctionDefinition::name)
                        .toList(),
                    "the only canvas function must come from the media owner");
                CanvasFunctionCatalog.RegisteredFunction registered =
                    catalog.require(IMAGE_CROP_FUNCTION);
                assertTrue(registered.enabled(), "default configuration enables image.crop");
                assertNull(registered.unavailableReason());
                assertEquals(MEDIA_FUNCTION_ADAPTER, registered.adapter().getClass().getName());

                PluginCatalogSnapshot.PluginOwnership ownership =
                    snapshot.pluginOwnership().get(PLUGIN_ID);
                assertNotNull(ownership, "media must own its frozen contributions");
                assertEquals(Set.of(IMAGE_CROP_FUNCTION), ownership.functionNames());
                assertTrue(ownership.contributorIds().isEmpty(), "media contributes no harness");
              }

              assertEquals(1L, readEpoch(jdbcClient));
              assertEquals(1, countConfigurationRevisions(jdbcClient));
              PluginConfigurationRow row =
                  configurationRepository.readConfiguration(PLUGIN_ID, 1L).orElseThrow();
              assertEquals("{\"enabled\":true}", row.valuesJson());
              assertTrue(row.configuredSecretFields().isEmpty(), "media declares no secrets");
              assertTrue(
                  credentialRepository.find(PLUGIN_ID).isEmpty(),
                  "media must never read or store credentials");
            });
  }

  /** 持久 manifest 与配置历史必须和 live 快照同身份，且 manifest 里没有第二个 owner。 */
  @Test
  void persistedManifestAndHistoryMatchTheLiveSnapshotWithoutASecondOwner() {
    createContextRunner()
        .run(
            context -> {
              PluginCatalogRuntime runtime = context.getBean(PluginCatalogRuntime.class);

              PluginCatalogRecord catalog = configurationRepository.readCatalog();
              assertNotNull(catalog);

              try (PluginCatalogRuntime.Lease lease = runtime.acquireCurrent()) {
                PluginCatalogSnapshot snapshot = lease.snapshot();
                assertEquals(snapshot.epoch(), catalog.epoch());
                assertEquals(
                    Set.of(PLUGIN_ID),
                    catalog.manifest().plugins().keySet(),
                    "the manifest must list exactly the media owner");
                assertEquals(
                    snapshot.catalogFingerprint(), catalog.manifest().catalogFingerprint());

                PluginCatalogManifest.Entry entry = catalog.manifest().plugins().get(PLUGIN_ID);
                assertEquals(1L, entry.configRevision());
                assertEquals("1.0.0", entry.pluginCodeVersion());
                assertEquals(
                    snapshot.capabilityFingerprints().get(PLUGIN_ID),
                    entry.capabilityFingerprint());

                assertEquals(1, snapshot.configuredPlugins().size());
                PluginCatalogSnapshot.ConfiguredPlugin configured =
                    snapshot.configuredPlugins().get(0);
                assertEquals(PLUGIN_ID, configured.descriptor().pluginId());
                assertEquals(
                    configured.credentialReference().authGeneration(), entry.authGeneration());
                assertEquals(
                    configured.credentialReference().contextFingerprint(),
                    entry.contextFingerprint());
              }

              assertEquals(
                  2, countCatalogRevisions(jdbcClient), "epoch 0 plus the initialized epoch");
              assertEquals(1, countConfigurationRevisions(jdbcClient));
            });
  }

  /** 保存 disabled 并应用 epoch 后新目录不再有 image.crop，历史 epoch 仍可租用恢复原函数。 */
  @Test
  void disablingMediaRemovesImageCropFromTheNewEpochAndHistoryStillRestoresIt() {
    createContextRunner()
        .run(
            context -> {
              PluginCatalogRuntime runtime = context.getBean(PluginCatalogRuntime.class);
              StudioPluginRegistry registry = context.getBean(StudioPluginRegistry.class);
              PluginConfigurationCommitService commitService =
                  createCommitService(registry, runtime);

              commitService.configure(PLUGIN_ID, 1L, 1L, "{\"enabled\":false}", Map.of());
              runtime.refresh();

              assertEquals(2L, readEpoch(jdbcClient));
              assertTrue(
                  context.getBeansOfType(CanvasFunctionAdapter.class).isEmpty(),
                  "disabling media must not fall back to a builtin adapter");

              try (PluginCatalogRuntime.Lease lease = runtime.acquireCurrent()) {
                PluginCatalogSnapshot snapshot = lease.snapshot();
                assertEquals(2L, snapshot.epoch());
                assertTrue(
                    snapshot.canvasFunctionCatalog().list().isEmpty(),
                    "a disabled owner must contribute no canvas function");
                assertThrows(
                    IllegalArgumentException.class,
                    () -> snapshot.canvasFunctionCatalog().require(IMAGE_CROP_FUNCTION));
                assertTrue(
                    snapshot.pluginOwnership().get(PLUGIN_ID).functionNames().isEmpty(),
                    "the disabled owner must own no function");
              }

              PluginConfigurationRow disabledRow =
                  configurationRepository.readConfiguration(PLUGIN_ID, 2L).orElseThrow();
              assertEquals("{\"enabled\":false}", disabledRow.valuesJson());
              assertEquals(
                  2L,
                  configurationRepository
                      .readCatalog()
                      .manifest()
                      .plugins()
                      .get(PLUGIN_ID)
                      .configRevision(),
                  "the persisted manifest must point at the disabled revision");

              // 历史 epoch 1 仍可租用，并恢复原函数与其 owner
              try (PluginCatalogRuntime.Lease historical = runtime.acquire(1L)) {
                assertEquals(1L, historical.snapshot().epoch());
                CanvasFunctionCatalog.RegisteredFunction restored =
                    historical.snapshot().canvasFunctionCatalog().require(IMAGE_CROP_FUNCTION);
                assertTrue(restored.enabled(), "historical epoch 1 had image.crop enabled");
                assertEquals(MEDIA_FUNCTION_ADAPTER, restored.adapter().getClass().getName());
              }

              // 历史租用结束后当前快照保持最新，且仍没有 image.crop
              try (PluginCatalogRuntime.Lease current = runtime.acquireCurrent()) {
                assertEquals(2L, current.snapshot().epoch());
                assertThrows(
                    IllegalArgumentException.class,
                    () -> current.snapshot().canvasFunctionCatalog().require(IMAGE_CROP_FUNCTION));
              }
            });
  }

  /** 重新启用推进新 epoch 并再次由 owner 贡献原函数，证明启停切换是完整往返。 */
  @Test
  void reenablingMediaAdvancesEpochAndRestoresTheOwnedFunction() {
    createContextRunner()
        .run(
            context -> {
              PluginCatalogRuntime runtime = context.getBean(PluginCatalogRuntime.class);
              StudioPluginRegistry registry = context.getBean(StudioPluginRegistry.class);
              PluginConfigurationCommitService commitService =
                  createCommitService(registry, runtime);

              commitService.configure(PLUGIN_ID, 1L, 1L, "{\"enabled\":false}", Map.of());
              runtime.refresh();
              commitService.configure(PLUGIN_ID, 2L, 2L, "{\"enabled\":true}", Map.of());
              runtime.refresh();

              assertEquals(3L, readEpoch(jdbcClient));
              try (PluginCatalogRuntime.Lease lease = runtime.acquireCurrent()) {
                PluginCatalogSnapshot snapshot = lease.snapshot();
                assertEquals(3L, snapshot.epoch());
                CanvasFunctionCatalog.RegisteredFunction registered =
                    snapshot.canvasFunctionCatalog().require(IMAGE_CROP_FUNCTION);
                assertTrue(registered.enabled());
                assertEquals(MEDIA_FUNCTION_ADAPTER, registered.adapter().getClass().getName());
                assertEquals(
                    Set.of(IMAGE_CROP_FUNCTION),
                    snapshot.pluginOwnership().get(PLUGIN_ID).functionNames());
              }
              assertEquals(3, countConfigurationRevisions(jdbcClient));
            });
  }

  /** 隔离组合根：真实 Postgres 仓库与事务，runtime-only 插件经反射装配为容器里的安装定义。 */
  private ApplicationContextRunner createContextRunner() {
    Class<?> mediaAutoConfiguration;
    try {
      mediaAutoConfiguration = Class.forName(MEDIA_AUTO_CONFIGURATION);
    } catch (ClassNotFoundException error) {
      throw new AssertionError(
          "MediaAutoConfiguration must be available on the test runtime classpath", error);
    }

    return new ApplicationContextRunner()
        .withUserConfiguration(PluginCatalogConfiguration.class, RegistryConfiguration.class)
        .withConfiguration(AutoConfigurations.of(mediaAutoConfiguration))
        // PluginCatalogRuntime 显式依赖 systemProxySelector：隔离组合根提供直接连接的合法 selector 依赖，
        // 不安装 JVM 全局代理，保持与其他测试的全局代理隔离。
        .withBean(
            "systemProxySelector", ProxySelector.class, () -> HttpProxySelector.fixed(null, ""))
        .withBean(PluginCredentialStore.class, () -> credentialStore)
        .withBean(PluginConfigurationRepository.class, () -> configurationRepository)
        .withBean(PlatformTransactionManager.class, () -> transactionManager);
  }

  private PluginConfigurationCommitService createCommitService(
      StudioPluginRegistry registry, PluginCatalogRuntime runtime) {
    return new PluginConfigurationCommitService(
        registry,
        configurationRepository,
        credentialRepository,
        codec,
        keyLoader,
        new PluginContextFactory(credentialStore, () -> null, () -> null, () -> null),
        List::of,
        List::of,
        transactionManager,
        Clock.systemUTC(),
        () -> {},
        runtime::refresh);
  }

  private static long readEpoch(JdbcClient jdbc) {
    return jdbc.sql("select epoch from plugin_catalog_state where id = 1")
        .query(Long.class)
        .single();
  }

  private static int countConfigurationRevisions(JdbcClient jdbc) {
    return jdbc.sql("select count(*) from plugin_configuration_revision")
        .query(Integer.class)
        .single();
  }

  private static int countCatalogRevisions(JdbcClient jdbc) {
    return jdbc.sql("select count(*) from plugin_catalog_revision").query(Integer.class).single();
  }

  @Configuration(proxyBeanMethods = false)
  static class RegistryConfiguration {
    @Bean
    StudioPluginRegistry studioPluginRegistry(ObjectProvider<StudioPlugin> plugins) {
      return new StudioPluginRegistry(plugins.orderedStream().toList());
    }
  }
}
