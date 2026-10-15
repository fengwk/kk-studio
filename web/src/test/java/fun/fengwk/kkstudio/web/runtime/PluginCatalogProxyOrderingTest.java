package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
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
import fun.fengwk.kkstudio.platform.settings.SystemNetworkConfiguration;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

import java.net.ProxySelector;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 宿主组合根的代理装配顺序回归：{@link PluginCatalogConfiguration#pluginCatalogRuntime} 必须在 {@code
 * systemProxySelector} 安装之后才构建插件实例。
 *
 * <p>不加载 ComfyUI/H3，仅使用真实目录组合根、真实全局代理和探测插件；内存仓储替代与装配顺序无关的持久化。
 */
@ResourceLock("jvm-proxy-selector")
class PluginCatalogProxyOrderingTest {

  @Test
  void pluginCatalogRuntimeIsBuiltAfterTheSystemProxySelectorIsInstalled() {
    ProxySelector original = ProxySelector.getDefault();
    AtomicReference<ProxySelector> observed = new AtomicReference<>();
    SystemSettingsSnapshot settings = mock(SystemSettingsSnapshot.class);
    when(settings.get()).thenReturn(SystemSettings.DEFAULT);
    new ApplicationContextRunner()
        .withUserConfiguration(PluginCatalogConfiguration.class)
        .withBean(
            StudioPluginRegistry.class,
            () -> new StudioPluginRegistry(List.of(new ProbePlugin(observed))))
        .withBean(PluginCredentialStore.class, () -> mock(PluginCredentialStore.class))
        .withBean(PluginConfigurationRepository.class, InMemoryPluginCatalogFakes::repository)
        .withBean(PlatformTransactionManager.class, InMemoryPluginCatalogFakes::transactionManager)
        // 后注册 selector，使顺序保证来自宿主声明的依赖，而非 Bean 注册顺序。
        .withBean(
            "systemProxySelector",
            ProxySelector.class,
            () -> new SystemNetworkConfiguration().systemProxySelector(settings))
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertArrayEquals(
                  new String[] {"systemProxySelector"},
                  context.getBeanFactory().getBeanDefinition("pluginCatalogRuntime").getDependsOn(),
                  "宿主组合根必须显式声明运行期 owner 依赖 systemProxySelector");

              ProxySelector installed = context.getBean("systemProxySelector", ProxySelector.class);
              assertNotNull(observed.get(), "插件实例必须在启动期完成 configure");
              assertSame(installed, observed.get(), "插件实例必须使用已安装的全局代理");

              PluginCatalogRuntime runtime = context.getBean(PluginCatalogRuntime.class);
              try (PluginCatalogRuntime.Lease lease = runtime.acquireCurrent()) {
                PluginCatalogSnapshot snapshot = lease.snapshot();
                assertEquals(1L, snapshot.epoch());
                assertTrue(
                    snapshot.configuredPlugins().stream()
                        .anyMatch(
                            configured ->
                                configured.descriptor().pluginId().equals(ProbePlugin.PLUGIN_ID)));
              }
            });

    assertEquals(original, ProxySelector.getDefault(), "组合根关闭后必须归还此前的默认代理");
  }

  /** 只记录 configure 期默认代理的安装定义，不贡献任何两域能力。 */
  static final class ProbePlugin implements StudioPlugin {

    static final String PLUGIN_ID = "proxy-probe";

    private static final PluginConfigSchema SCHEMA =
        new PluginConfigSchema(
            new InputSchema("proxy probe configuration", Map.of(), Set.of(), false),
            "{}",
            Map.of());

    private final AtomicReference<ProxySelector> observedProxy;

    private ProbePlugin(AtomicReference<ProxySelector> observedProxy) {
      this.observedProxy = observedProxy;
    }

    @Override
    public PluginDescriptor descriptor() {
      return new PluginDescriptor(PLUGIN_ID, "Proxy Probe", "1.0.0");
    }

    @Override
    public PluginConfigSchema configurationSchema() {
      return SCHEMA;
    }

    @Override
    public PluginAuthSpec authenticationSpec() {
      return new PluginAuthSpec.None();
    }

    @Override
    public PluginInstance configure(PluginConfig config, PluginContext context) {
      observedProxy.set(ProxySelector.getDefault());
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
        public void close() {}
      };
    }
  }
}
