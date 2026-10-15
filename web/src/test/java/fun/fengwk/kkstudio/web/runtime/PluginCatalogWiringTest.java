package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalogSource;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.canvas.function.PluginCanvasFunctionCatalogSource;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRuntime;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 组合根装配测试：唯一运行期所有者、绑在它上面的 Canvas 目录来源与对账器，以及 context 关闭时的资源收敛。
 *
 * <p>这里不依赖任何静态目录 bean：目录只从 {@link PluginCatalogRuntime} 的租用读取，关闭后租用与对账同时收敛。
 */
class PluginCatalogWiringTest {

  private static final String PLUGIN_ID = "wiring-plugin";

  @Test
  void collectsBuiltinContributorsIntoTheRuntimeSnapshot() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      registerHost(context);
      for (String id : List.of("first", "second")) {
        context.registerBean(
            id,
            HarnessContributor.class,
            () ->
                HarnessContributor.of(
                    new ContributorDescriptor(new ContributorId(id), id, "1", Set.of()),
                    registrar -> registrar.registerCustomEntryType(id, "custom." + id)));
      }
      context.refresh();
      try (PluginCatalogRuntime.Lease lease =
          context.getBean(PluginCatalogRuntime.class).acquireCurrent()) {
        HarnessCatalog catalog = lease.snapshot().harnessCatalog();
        assertEquals(2, catalog.descriptors().size());
        assertTrue(
            catalog.findCustomEntryType(new ContributorId("first"), "custom.first").isPresent());
        assertTrue(
            catalog.findCustomEntryType(new ContributorId("second"), "custom.second").isPresent());
      }
    }
  }

  @Test
  void allowsAnEmptyContributorSet() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      registerHost(context);
      context.refresh();
      try (PluginCatalogRuntime.Lease lease =
          context.getBean(PluginCatalogRuntime.class).acquireCurrent()) {
        HarnessCatalog catalog = lease.snapshot().harnessCatalog();
        assertTrue(catalog.descriptors().isEmpty());
        assertTrue(catalog.tools().isEmpty());
        assertTrue(catalog.selectableTools().isEmpty());
        assertTrue(catalog.customEntryTypes().isEmpty());
        assertTrue(catalog.contextProjectors().isEmpty());
      }
    }
  }

  @Test
  void rejectsDuplicateBuiltinContributorIds() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      registerHost(context);
      for (String name : List.of("first", "second")) {
        context.registerBean(
            name,
            HarnessContributor.class,
            () ->
                HarnessContributor.of(
                    new ContributorDescriptor(new ContributorId("duplicate"), name, "1", Set.of()),
                    registrar -> {}));
      }
      assertThrows(RuntimeException.class, context::refresh);
    }
  }

  /** 组合根只装配一个运行期所有者；Canvas 目录来源与对账器都绑在它上面，context 关闭即收敛。 */
  @Test
  void wiresOneRuntimeOwnedCatalogAndConvergesOnShutdown() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      registerHost(context);
      WiringPlugin plugin = new WiringPlugin();
      context.registerBean("wiringPlugin", StudioPlugin.class, () -> plugin);
      context.refresh();

      PluginCatalogRuntime runtime = context.getBean(PluginCatalogRuntime.class);
      PluginCanvasFunctionCatalogSource source =
          context.getBean(PluginCanvasFunctionCatalogSource.class);
      PluginCatalogRefresher refresher = context.getBean(PluginCatalogRefresher.class);
      assertEquals(1, context.getBeansOfType(PluginCatalogRuntime.class).size());
      assertEquals(1, context.getBeansOfType(PluginCanvasFunctionCatalogSource.class).size());
      assertEquals(1, context.getBeansOfType(PluginCatalogRefresher.class).size());
      assertTrue(context.getBeansOfType(PluginCatalogSnapshot.class).isEmpty());
      assertTrue(context.getBeansOfType(HarnessCatalog.class).isEmpty());
      assertTrue(context.getBeansOfType(CanvasFunctionCatalog.class).isEmpty());
      assertEquals(new PluginCatalogRuntime.RuntimeState(1, 1, null), runtime.state());
      assertTrue(refresher.isRunning(), "the refresher must be started with the context");

      try (CanvasFunctionCatalogSource.Lease lease = source.acquireCurrent()) {
        assertEquals(1L, lease.epoch());
        assertTrue(lease.catalog().list().isEmpty());
        context.close();
        assertEquals(0, plugin.closes.get(), "shutdown must not kill a leased instance");
        assertTrue(lease.catalog().list().isEmpty(), "the active lease survives context shutdown");
      }
      assertEquals(1, plugin.closes.get(), "the last lease must release the instance");

      assertFalse(refresher.isRunning(), "closing the context must stop the refresher");
      assertThrows(IllegalStateException.class, runtime::acquireCurrent);
      assertThrows(
          IllegalStateException.class,
          source::acquireCurrent,
          "the production canvas source must be bound to the closed runtime, not to a static catalog");
    }
  }

  /** 可选 JAR 只通过运行时 classpath 接入：真实 configure 冻结贡献，启动不解析凭据或发送请求。 */
  @Test
  void realMavisIsInstalledAndFrozenWithoutResolvingCredentials() {
    AtomicInteger requests = new AtomicInteger();
    PluginCredentialStore credentials = mock(PluginCredentialStore.class);
    RuntimeMavisTestSupport.withMavis(
            new ApplicationContextRunner()
                .withUserConfiguration(
                    PluginCatalogConfiguration.class, RegistryConfiguration.class)
                .withBean(PluginCredentialStore.class, () -> credentials)
                .withBean(
                    PluginConfigurationRepository.class, InMemoryPluginCatalogFakes::repository)
                .withBean(
                    PlatformTransactionManager.class,
                    InMemoryPluginCatalogFakes::transactionManager),
            requests)
        .run(
            context -> {
              assertTrue(context.getStartupFailure() == null);
              PluginCatalogRuntime runtime = context.getBean(PluginCatalogRuntime.class);
              PluginCanvasFunctionCatalogSource source =
                  context.getBean(PluginCanvasFunctionCatalogSource.class);
              assertTrue(context.getBean(PluginCatalogRefresher.class).isRunning());
              assertEquals(new PluginCatalogRuntime.RuntimeState(1, 1, null), runtime.state());

              try (PluginCatalogRuntime.Lease lease = runtime.acquireCurrent()) {
                assertEquals(1, lease.snapshot().configuredPlugins().size());
                assertEquals(
                    15, lease.snapshot().harnessCatalog().tools().size(), "Mavis tools are frozen");
                assertTrue(
                    lease
                        .snapshot()
                        .harnessCatalog()
                        .findDescriptor(new ContributorId("minimax-mavis"))
                        .isPresent());
                assertTrue(lease.snapshot().canvasFunctionCatalog().list().isEmpty());
              }
              try (CanvasFunctionCatalogSource.Lease lease = source.acquireCurrent()) {
                assertEquals(1L, lease.epoch());
              }
              assertTrue(context.getBeansOfType(HarnessContributor.class).isEmpty());
              assertEquals(0, requests.get());
              verifyNoInteractions(credentials);
            });
  }

  private static void registerHost(AnnotationConfigApplicationContext context) {
    context.register(PluginCatalogConfiguration.class, RegistryConfiguration.class);
    context.registerBean(PluginCredentialStore.class, () -> mock(PluginCredentialStore.class));
    context.registerBean(
        PluginConfigurationRepository.class, InMemoryPluginCatalogFakes::repository);
    context.registerBean(
        PlatformTransactionManager.class, InMemoryPluginCatalogFakes::transactionManager);
  }

  @Configuration(proxyBeanMethods = false)
  static class RegistryConfiguration {

    @Bean
    StudioPluginRegistry studioPluginRegistry(ObjectProvider<StudioPlugin> plugins) {
      return new StudioPluginRegistry(plugins.orderedStream().toList());
    }
  }

  /** 无普通配置、无秘密、两域贡献均为空的最小安装定义。 */
  private static final class WiringPlugin implements StudioPlugin {

    private final AtomicInteger closes = new AtomicInteger();

    private final PluginDescriptor descriptor =
        new PluginDescriptor(PLUGIN_ID, "Wiring Plugin", "1.0");

    private final PluginConfigSchema schema =
        new PluginConfigSchema(
            new InputSchema("config", Map.of(), Set.of(), false), "{}", Map.of());

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
      return new PluginAuthSpec.None();
    }

    @Override
    public PluginInstance configure(PluginConfig config, PluginContext context) {
      return new PluginInstance() {
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
          closes.incrementAndGet();
        }
      };
    }
  }
}
