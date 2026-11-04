package fun.fengwk.kkstudio.platform.pluginhost.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.pluginhost.testing.TestPluginFixtures;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** {@link PluginCatalogRuntime} 的发布、退役与失败保留契约。 */
class PluginCatalogRuntimeTest {

  private static final String PLUGIN_ID = "alpha.plugin";

  /** 平台不认识的产品贡献：只有注入的产品目录投影会按该类型读取它。 */
  private record FakeAdapter(String functionName) {}

  /** 示例产品目录视图。 */
  private record FakeProductCatalog(List<String> functionNames) {

    private FakeProductCatalog {
      functionNames = List.copyOf(functionNames);
    }
  }

  private final PluginConfigurationRepository repository =
      mock(PluginConfigurationRepository.class);

  /** 产品目录投影的校验失败开关：模拟新快照发布前的产品目录校验。 */
  private final AtomicBoolean productCatalogRejects = new AtomicBoolean();

  private TestPlugin plugin;
  private PluginCatalogRuntime runtime;

  @BeforeEach
  void setUp() {
    plugin = new TestPlugin();
    stored(1L);
    runtime =
        new PluginCatalogRuntime(
            new StudioPluginRegistry(List.of(plugin)),
            repository,
            pluginId ->
                new PluginContext(
                    mock(PluginCredentialAccess.class),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()),
            List.of(),
            this::productCatalog);
  }

  /** 启动为已安装 Plugin 写入默认配置，并只按已安装 Plugin 的当前配置构建首个快照。 */
  @Test
  void constructorSeedsDefaultsAndBuildsFromInstalledPluginsOnly() {
    verify(repository).insertIfMissing(PLUGIN_ID, "{}");
    try (PluginCatalogRuntime.Lease lease = runtime.acquire()) {
      assertEquals(Map.of(PLUGIN_ID, 1L), lease.snapshot().revisions());
      assertEquals(
          List.of("product.run"),
          lease.snapshot().productCatalog(FakeProductCatalog.class).functionNames());
    }
    assertEquals(1, plugin.instances.size());
    assertNull(runtime.applicationError());
  }

  /** 修订未变时不重新 configure。 */
  @Test
  void refreshWithUnchangedRevisionsDoesNotConfigure() {
    runtime.refresh();

    assertEquals(1, plugin.instances.size());
  }

  /** 修订变化时发布新快照；被替换的快照在最后一个租用释放后才关闭，租用关闭幂等。 */
  @Test
  void refreshPublishesAndRetiresTheOldSnapshotAfterTheLastLease() {
    PluginCatalogRuntime.Lease held = runtime.acquire();
    PluginCatalogSnapshot old = held.snapshot();
    stored(2L);

    runtime.refresh();

    try (PluginCatalogRuntime.Lease fresh = runtime.acquire()) {
      assertNotSame(old, fresh.snapshot());
      assertEquals(Map.of(PLUGIN_ID, 2L), fresh.snapshot().revisions());
    }
    assertEquals(0, plugin.instances.get(0).closeCount);
    held.close();
    held.close();
    assertEquals(1, plugin.instances.get(0).closeCount);
  }

  /** 构建或回读失败时保留旧快照并暴露固定错误；随后成功同步清空错误。 */
  @Test
  void refreshFailureKeepsTheOldSnapshotUntilTheNextSuccessfulSync() {
    PluginCatalogSnapshot before;
    try (PluginCatalogRuntime.Lease lease = runtime.acquire()) {
      before = lease.snapshot();
    }
    stored(2L);
    plugin.failure = new IllegalStateException("boom");

    runtime.refresh();

    assertEquals(
        "plugin configuration could not be applied on this node", runtime.applicationError());
    try (PluginCatalogRuntime.Lease lease = runtime.acquire()) {
      assertSame(before, lease.snapshot());
    }

    doThrow(new IllegalStateException("database unavailable")).when(repository).findAll();
    runtime.refresh();
    assertEquals(
        "plugin configuration could not be applied on this node", runtime.applicationError());

    plugin.failure = null;
    stored(2L);
    runtime.refresh();
    assertNull(runtime.applicationError());
  }

  /** 产品目录校验失败在发布前拒绝：候选实例回收，旧租用和后续租用仍指向旧快照。 */
  @Test
  void productCatalogValidationFailureDiscardsCandidateWithoutRetiringCurrentSnapshot() {
    PluginCatalogRuntime.Lease held = runtime.acquire();
    PluginCatalogSnapshot old = held.snapshot();
    productCatalogRejects.set(true);
    stored(2L);

    runtime.refresh();

    assertEquals(
        "plugin configuration could not be applied on this node", runtime.applicationError());
    assertEquals(1, plugin.instances.get(1).closeCount);
    assertEquals(0, plugin.instances.get(0).closeCount);
    try (PluginCatalogRuntime.Lease fresh = runtime.acquire()) {
      assertSame(old, fresh.snapshot());
      assertEquals(Map.of(PLUGIN_ID, 1L), fresh.snapshot().revisions());
      assertEquals(
          List.of("product.run"),
          fresh.snapshot().productCatalog(FakeProductCatalog.class).functionNames());
    }
    held.close();
    assertEquals(0, plugin.instances.get(0).closeCount);
    runtime.close();
    assertEquals(1, plugin.instances.get(0).closeCount);
  }

  /** 关闭停止授予新租用，关闭幂等；关闭后的刷新只回收候选，不发布。 */
  @Test
  void closeStopsNewLeasesAndDiscardsLaterCandidates() {
    PluginCatalogRuntime.Lease held = runtime.acquire();
    runtime.close();
    runtime.close();

    assertThrows(IllegalStateException.class, runtime::acquire);
    assertEquals(0, plugin.instances.get(0).closeCount);
    held.close();
    assertEquals(1, plugin.instances.get(0).closeCount);

    stored(2L);
    runtime.refresh();
    assertEquals(1, plugin.instances.get(1).closeCount);
  }

  /** 注入的产品目录投影：只读插件贡献，返回固定视图；校验开关打开时拒绝本次候选。 */
  private FakeProductCatalog productCatalog(PluginCatalogSnapshot snapshot) {
    if (productCatalogRejects.get()) {
      throw new IllegalArgumentException("duplicate product function name");
    }
    List<String> names = new ArrayList<>();
    for (PluginCatalogSnapshot.ConfiguredPlugin configured : snapshot.configuredPlugins()) {
      for (FakeAdapter adapter : configured.instance().contributions(FakeAdapter.class)) {
        names.add(adapter.functionName());
      }
    }
    return new FakeProductCatalog(names);
  }

  private void stored(long revision) {
    doReturn(
            List.of(
                new PluginConfigurationRow(PLUGIN_ID, revision, "{}"),
                new PluginConfigurationRow("removed.plugin", 9L, "{}")))
        .when(repository)
        .findAll();
  }

  /** 每次 configure 产出一个可计数关闭的实例，可注入 configure 失败。 */
  private static final class TestPlugin implements StudioPlugin {

    private final List<CountingInstance> instances = new ArrayList<>();
    private RuntimeException failure;

    @Override
    public PluginDescriptor descriptor() {
      return new PluginDescriptor(PLUGIN_ID, "Alpha", "1.0");
    }

    @Override
    public PluginConfigSchema configurationSchema() {
      return TestPluginFixtures.EMPTY_SCHEMA;
    }

    @Override
    public PluginAuthSpec authenticationSpec() {
      return new PluginAuthSpec.None();
    }

    @Override
    public PluginInstance configure(PluginConfig config, PluginContext context) {
      if (failure != null) {
        throw failure;
      }
      CountingInstance instance = new CountingInstance();
      instances.add(instance);
      return instance;
    }
  }

  /** 贡献一个产品 adapter 并记录 close 次数的实例。 */
  private static final class CountingInstance implements PluginInstance {

    private static final List<Object> CONTRIBUTIONS = List.of(new FakeAdapter("product.run"));

    private int closeCount;

    @Override
    public List<HarnessContributor> harnessContributors() {
      return List.of();
    }

    @Override
    public <T> List<T> contributions(Class<T> type) {
      return PluginInstance.contributionsOf(type, CONTRIBUTIONS);
    }

    @Override
    public void close() {
      closeCount++;
    }
  }
}
