package fun.fengwk.kkstudio.web.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

import fun.fengwk.kkstudio.harness.builtin.BuiltinHarnessContributor;
import fun.fengwk.kkstudio.harness.builtin.environment.ReadToolExecutor;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentRunner;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.catalog.OfflinePluginHost;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogBootstrap;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.project.tool.ProjectHarnessContributor;
import fun.fengwk.kkstudio.web.upgrade.OfflineHostProbes.CountingDataSource;
import fun.fengwk.kkstudio.web.upgrade.OfflineHostProbes.LocalHttpProbe;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ProxySelector;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 离线初始目录装配的定向回归：真实安装定义加真实内置贡献，全过程没有数据库连接、没有 HTTP 命中、没有宿主操作。
 *
 * <p>本测试只用真实发行依赖装配，不加载任何 Spring Boot 上下文、不启动 Web 或 worker。数据库用计数 DataSource 顶替，出网用真实 {@code
 * SystemNetworkConfiguration} 指向本地探针，离线端口则一律抛出固定安全异常，因此任何一次误调用都只会让候选构造失败。
 */
class OfflinePluginCatalogTest {

  private static final List<String> INSTALLED_PLUGIN_IDS =
      List.of("comfyui", "media", "minimax-mavis", "opencli");

  /** 真实四插件共存，并且冻结出的 epoch 1 候选同时覆盖普通值、两域指纹与真正内置的贡献。 */
  @Test
  void assemblesTheInstalledPluginsAndFreezesTheCompleteInitialCatalogOffline() throws IOException {
    ProxySelector previous = ProxySelector.getDefault();
    CountingDataSource dataSource = new CountingDataSource();
    try (LocalHttpProbe probe = LocalHttpProbe.start();
        OfflinePluginCatalog catalog =
            OfflinePluginCatalog.open(
                dataSource, OfflineHostProbes.settingsWithLocalProxy(probe.proxyUrl()))) {
      assertNotSame(
          previous,
          ProxySelector.getDefault(),
          "assembly must install the real global proxy selector");

      StudioPluginRegistry registry = catalog.registry();
      assertEquals(INSTALLED_PLUGIN_IDS, registry.pluginIds());

      assertEquals(
          2, catalog.builtins().size(), "builtin tools and Project handoff must be present");
      Set<ContributorId> builtinIds =
          catalog.builtins().stream()
              .map(contributor -> contributor.descriptor().id())
              .collect(Collectors.toSet());
      assertTrue(builtinIds.contains(BuiltinHarnessContributor.ID));
      assertTrue(builtinIds.contains(ProjectHarnessContributor.ID));

      PluginCatalogBootstrap.PreparedInitial prepared = prepare(catalog);
      try {
        PluginCatalogSnapshot snapshot = prepared.snapshot();
        assertEquals(1L, snapshot.epoch());
        assertEquals(Set.copyOf(INSTALLED_PLUGIN_IDS), prepared.manifest().plugins().keySet());
        assertEquals(4, prepared.configurationRows().size());
        assertNotNull(snapshot.catalogFingerprint());
        assertEquals(snapshot.catalogFingerprint(), prepared.manifest().catalogFingerprint());
        assertEquals(Set.copyOf(INSTALLED_PLUGIN_IDS), snapshot.capabilityFingerprints().keySet());

        // 两域指纹必须真的含内置贡献：内置 read/task 与 Project 交接工具都在冻结目录里，媒体插件提供 Canvas function。
        HarnessCatalog harness = snapshot.harnessCatalog();
        assertTrue(harness.findTool("read").isPresent());
        assertTrue(harness.findTool("task").isPresent());
        assertTrue(harness.findTool("issue_transition").isPresent());
        assertTrue(
            snapshot.canvasFunctionCatalog().list().stream()
                .anyMatch(function -> function.function().name().equals("image.crop")));

        for (PluginConfigurationRow row : prepared.configurationRows()) {
          assertEquals(1L, row.revision());
          assertTrue(row.configuredSecretFields().isEmpty());
          assertEquals(
              registry.find(row.pluginId()).orElseThrow().descriptor().version(),
              row.codeVersion());
        }

        assertOfflinePortsRejectEveryHostOperation(catalog, snapshot);
      } finally {
        prepared.snapshot().close();
      }

      assertEquals(
          0, dataSource.openedConnections(), "assembly must not open a database connection");
      assertEquals(0, probe.connections(), "assembly must not issue any HTTP request");
    }
    assertSame(
        previous,
        ProxySelector.getDefault(),
        "closing the assembly must restore the previous default proxy selector");
  }

  /** 启用态（ComfyUI 默认 H3 + 追加 DIRECT、OpenCLI GPT Image 2 与 Seedance）在离线装配里同样只构造不执行。 */
  @Test
  void preparesEnabledCapabilitiesWithoutExecutingAnything() throws IOException {
    CountingDataSource dataSource = new CountingDataSource();
    try (LocalHttpProbe probe = LocalHttpProbe.start();
        OfflinePluginCatalog catalog =
            OfflinePluginCatalog.open(
                dataSource, OfflineHostProbes.settingsWithLocalProxy(probe.proxyUrl()))) {
      StudioPluginRegistry registry = catalog.registry();
      PluginCatalogBootstrap.PreparedInitial prepared =
          catalog.prepareInitial(
              OfflineInitialCatalogValues.valuesJsonWithEnabledCapabilities(registry),
              OfflineInitialCatalogValues.noConfiguredSecretFields(registry));
      try {
        PluginCatalogSnapshot snapshot = prepared.snapshot();
        assertEquals(1L, snapshot.epoch());
        assertEquals(4, prepared.configurationRows().size());
        assertNotNull(snapshot.catalogFingerprint());
        assertEquals(Set.copyOf(INSTALLED_PLUGIN_IDS), snapshot.capabilityFingerprints().keySet());

        Set<String> enabledFunctions = OfflineInitialCatalogValues.enabledCanvasFunctions(snapshot);
        assertEquals(
            OfflineInitialCatalogValues.EXPECTED_ENABLED_CANVAS_FUNCTIONS, enabledFunctions);

        // 启用态只新增 Canvas 能力：内置 Harness 贡献必须一个都不少。
        HarnessCatalog harness = snapshot.harnessCatalog();
        assertTrue(harness.findTool("read").isPresent());
        assertTrue(harness.findTool("task").isPresent());
        assertTrue(harness.findTool("issue_transition").isPresent());

        assertOfflinePortsRejectEveryHostOperation(catalog, snapshot);
      } finally {
        prepared.snapshot().close();
      }

      assertEquals(0, dataSource.openedConnections());
      assertEquals(0, probe.connections(), "assembly must not issue any HTTP request");
    }
  }

  /** 不完整输入以受控 stage 失败且不掩盖 primary；装配本身保持可用，随后仍能构造同一候选。 */
  @Test
  void rejectsIncompletePreparationWithTheControlledStageAndStaysUsable() throws IOException {
    CountingDataSource dataSource = new CountingDataSource();
    try (LocalHttpProbe probe = LocalHttpProbe.start();
        OfflinePluginCatalog catalog =
            OfflinePluginCatalog.open(
                dataSource, OfflineHostProbes.settingsWithLocalProxy(probe.proxyUrl()))) {
      IllegalStateException rejection =
          assertThrows(
              IllegalStateException.class, () -> catalog.prepareInitial(Map.of(), Map.of()));
      assertTrue(
          rejection.getMessage().contains("stage=prepare-values-set"), rejection.getMessage());
      assertNull(rejection.getCause());

      PluginCatalogBootstrap.PreparedInitial prepared = prepare(catalog);
      try {
        assertEquals(1L, prepared.snapshot().epoch());
        assertEquals(4, prepared.configurationRows().size());
      } finally {
        prepared.snapshot().close();
      }

      assertEquals(0, dataSource.openedConnections());
      assertEquals(0, probe.connections());
    }
  }

  /** 关闭只释放上下文资源：全局代理 selector 恢复、close 幂等，并且关闭后拒绝再构造候选。 */
  @Test
  void restoresTheGlobalProxySelectorAndRejectsPreparationAfterClose() throws IOException {
    ProxySelector previous = ProxySelector.getDefault();
    try (LocalHttpProbe probe = LocalHttpProbe.start()) {
      OfflinePluginCatalog catalog =
          OfflinePluginCatalog.open(
              new CountingDataSource(), OfflineHostProbes.settingsWithLocalProxy(probe.proxyUrl()));
      assertNotSame(previous, ProxySelector.getDefault());

      catalog.close();
      assertSame(previous, ProxySelector.getDefault(), "close must restore the global selector");
      catalog.close();
      assertSame(previous, ProxySelector.getDefault(), "close must be idempotent");

      assertPostCloseRejection(catalog);
    }
  }

  /** 未配置底层目标的真实 Spring DataSource 代理必须使装配失败，而不是形成半可用宿主。 */
  @Test
  void rejectsAnUnconfiguredTargetDataSourceWithoutChangingTheGlobalProxy() {
    ProxySelector previous = ProxySelector.getDefault();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            OfflinePluginCatalog.open(
                new TransactionAwareDataSourceProxy(),
                OfflineHostProbes.settingsWithLocalProxy(null)));
    assertSame(previous, ProxySelector.getDefault());
  }

  /** 缺失配置类的加载诊断必须明确失败，且不携带底层 cause。 */
  @Test
  void rejectsAMissingDeclaredConfigurationClass() throws Exception {
    String missing = OfflinePluginCatalog.class.getName() + "Missing";
    Method load =
        OfflinePluginCatalog.class.getDeclaredMethod(
            "loadPluginConfiguration", String.class, ClassLoader.class);
    load.setAccessible(true);
    InvocationTargetException invocation =
        assertThrows(
            InvocationTargetException.class,
            () -> load.invoke(null, missing, OfflinePluginCatalog.class.getClassLoader()));
    IllegalStateException rejection =
        assertInstanceOf(IllegalStateException.class, invocation.getCause());
    assertEquals(
        "plugin auto-configuration declared by the classpath is missing: " + missing,
        rejection.getMessage());
    assertNull(rejection.getCause());
  }

  /** 三个可选宿主端口必须存在（否则启用中的能力会消失），且每一个操作都以固定安全异常终结。 */
  private static void assertOfflinePortsRejectEveryHostOperation(
      OfflinePluginCatalog catalog, PluginCatalogSnapshot snapshot) {
    PluginCatalogSnapshot.ConfiguredPlugin installed = snapshot.configuredPlugins().get(0);
    PluginContext context =
        catalog
            .contexts()
            .apply(installed.descriptor().pluginId(), installed.credentialReference());
    assertTrue(context.resourceGateway().isPresent(), "resource gateway port must stay present");
    assertTrue(context.promptTasks().isPresent(), "prompt task port must stay present");
    assertTrue(context.promptResources().isPresent(), "prompt resource port must stay present");

    OfflinePluginHost.HostOperationException credential =
        assertThrows(
            OfflinePluginHost.HostOperationException.class,
            () -> context.credentialAccess().resolve());
    assertNull(credential.getCause());
    Object host = context.resourceGateway().orElseThrow();
    assertThrows(
        OfflinePluginHost.HostOperationException.class,
        () -> ((ReadToolExecutor) host).read(null, null));
    assertThrows(
        OfflinePluginHost.HostOperationException.class, () -> ((SubagentRunner) host).accept(null));
    assertThrows(
        OfflinePluginHost.HostOperationException.class,
        () -> ((PluginCredentialStore) host).projection(installed.descriptor().pluginId()));
    assertThrows(
        OfflinePluginHost.HostOperationException.class,
        () -> ((PluginCredentialStore) host).rejectUsed(null, "synthetic rejection"));
    assertThrows(
        OfflinePluginHost.HostOperationException.class,
        () ->
            context
                .resourceGateway()
                .orElseThrow()
                .resolveSessionResource(null, "kkstudio:/resources/offline"));
    assertThrows(
        OfflinePluginHost.HostOperationException.class,
        () -> context.resourceGateway().orElseThrow().stageRemoteMedia(null, null, null, null));
    assertThrows(
        OfflinePluginHost.HostOperationException.class,
        () -> context.promptTasks().orElseThrow().submit("offline", "synthetic", List.of()));
    assertThrows(
        OfflinePluginHost.HostOperationException.class,
        () -> context.promptTasks().orElseThrow().await(null, null, null));
    assertThrows(
        OfflinePluginHost.HostOperationException.class,
        () -> context.promptTasks().orElseThrow().stop(null));
    assertThrows(
        OfflinePluginHost.HostOperationException.class,
        () -> context.promptResources().orElseThrow().stage("synthetic", "image/png", null, 0));
    assertThrows(
        OfflinePluginHost.HostOperationException.class,
        () -> context.promptResources().orElseThrow().discard(null));
  }

  private static void assertPostCloseRejection(OfflinePluginCatalog catalog) {
    StudioPluginRegistry registry = catalog.registry();
    IllegalStateException rejection =
        assertThrows(
            IllegalStateException.class,
            () ->
                catalog.prepareInitial(
                    OfflineInitialCatalogValues.valuesJsonByPluginId(registry),
                    OfflineInitialCatalogValues.noConfiguredSecretFields(registry)));
    assertTrue(rejection.getMessage().contains("closed"), rejection.getMessage());
  }

  private static PluginCatalogBootstrap.PreparedInitial prepare(OfflinePluginCatalog catalog) {
    StudioPluginRegistry registry = catalog.registry();
    return catalog.prepareInitial(
        OfflineInitialCatalogValues.valuesJsonByPluginId(registry),
        OfflineInitialCatalogValues.noConfiguredSecretFields(registry));
  }
}
