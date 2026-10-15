package fun.fengwk.kkstudio.platform.canvas.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBinding;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalogSource;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionPluginSource;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableReason;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogBootstrap;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogManifest;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRecord;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRuntime;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialReference;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.plugin.testing.InMemoryPluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.testing.InMemoryTransactionManager;
import fun.fengwk.kkstudio.platform.plugin.testing.TestPluginFixtures;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 生产 Canvas 目录来源的契约测试：冻结来源、严格校验绑定、按持久历史重建旧 epoch 与租用生命周期。
 *
 * <p>装配走真实 {@link PluginCatalogBootstrap} 启动链路（真实 manifest
 * 与配置历史），因此来源字段取自权威行而不是测试默认值，历史重建与启动恢复同一条代码路径。
 */
class PluginCanvasFunctionCatalogSourceTest {

  private static final String PLUGIN_ID = "canvas-plugin";
  private static final String PLUGIN_FUNCTION = "plugin-function";
  private static final String BUILTIN_FUNCTION = "builtin-function";

  private Fixture fixture;
  private CanvasFunctionCatalogSource source;

  @BeforeEach
  void setUp() {
    fixture = new Fixture();
    source = new PluginCanvasFunctionCatalogSource(fixture.runtime);
  }

  @AfterEach
  void tearDown() {
    fixture.close();
  }

  /** 内置函数来源为 null；插件函数的每个来源字段都必须等于权威行冻结的身份，绝不手写默认值。 */
  @Test
  void freezesBuiltinAndPluginSourceFromTheAuthoritativeRow() {
    PluginConfigurationRow row = fixture.authorityRow();
    try (CanvasFunctionCatalogSource.Lease lease = source.acquireCurrent()) {
      assertEquals(1L, lease.epoch());
      assertEquals(2, lease.catalog().list().size());

      CanvasFunctionBinding builtin = lease.bind(BUILTIN_FUNCTION);
      assertNull(builtin.pluginSource(), "a builtin function must never be faked as a plugin");
      assertEquals(1L, builtin.catalogEpoch());
      assertSame(lease.catalog().require(BUILTIN_FUNCTION), lease.require(builtin));

      CanvasFunctionBinding plugin = lease.bind(PLUGIN_FUNCTION);
      assertEquals(PLUGIN_ID, plugin.pluginSource().pluginId());
      assertEquals("1.0", plugin.pluginSource().pluginCodeVersion());
      assertEquals(row.revision(), plugin.pluginSource().configRevision());
      assertEquals(row.capabilityFingerprint(), plugin.pluginSource().capabilityFingerprint());
      assertEquals(row.authGeneration(), plugin.pluginSource().authGeneration());
      assertEquals(row.contextFingerprint(), plugin.pluginSource().contextFingerprint());
      assertSame(lease.catalog().require(PLUGIN_FUNCTION), lease.require(plugin));
    }
  }

  /** 同一 epoch 的两次租用必须复用同一冻结视图，绝不重复 configure。 */
  @Test
  void reusesTheSameFrozenViewWithinOneEpoch() {
    try (CanvasFunctionCatalogSource.Lease current = source.acquireCurrent();
        CanvasFunctionCatalogSource.Lease byEpoch = source.acquire(1L)) {
      assertEquals(1L, byEpoch.epoch());
      assertSame(current.catalog(), byEpoch.catalog());
      assertEquals(1, fixture.instanceCount());
    }
  }

  /** epoch 漂移必须拒绝，且拒绝后租用立即释放。 */
  @Test
  void rejectsBindingWithAnotherEpoch() {
    CanvasFunctionCatalogSource.Lease lease = source.acquireCurrent();
    CanvasFunctionBinding binding = lease.bind(PLUGIN_FUNCTION);
    CanvasFunctionBinding drifted =
        new CanvasFunctionBinding(
            binding.catalogEpoch() + 1, binding.definition(), binding.pluginSource());
    assertThrows(IllegalArgumentException.class, () -> lease.require(drifted));
    assertThrows(IllegalStateException.class, lease::catalog);
  }

  /** 同名但完整定义不同的绑定必须拒绝：绝不退回当前同名函数。 */
  @Test
  void rejectsBindingWithAnotherDefinition() {
    CanvasFunctionCatalogSource.Lease lease = source.acquireCurrent();
    CanvasFunctionBinding binding = lease.bind(PLUGIN_FUNCTION);
    CanvasFunctionBinding drifted =
        new CanvasFunctionBinding(
            binding.catalogEpoch(),
            definition(PLUGIN_FUNCTION, "other description"),
            binding.pluginSource());
    assertThrows(IllegalArgumentException.class, () -> lease.require(drifted));
    assertThrows(IllegalStateException.class, lease::epoch);
  }

  /** 来源不同的绑定必须拒绝，包括把内置函数伪造成插件函数。 */
  @Test
  void rejectsBindingWithAnotherPluginSource() {
    CanvasFunctionCatalogSource.Lease pluginLease = source.acquireCurrent();
    CanvasFunctionBinding bound = pluginLease.bind(PLUGIN_FUNCTION);
    CanvasFunctionPluginSource other =
        new CanvasFunctionPluginSource(
            PLUGIN_ID,
            "1.0",
            1L,
            "f".repeat(64),
            bound.pluginSource().authGeneration(),
            "e".repeat(64));
    assertThrows(
        IllegalArgumentException.class,
        () -> pluginLease.require(new CanvasFunctionBinding(1L, bound.definition(), other)));
    assertThrows(IllegalStateException.class, pluginLease::catalog);

    CanvasFunctionCatalogSource.Lease builtinLease = source.acquireCurrent();
    CanvasFunctionBinding builtin = builtinLease.bind(BUILTIN_FUNCTION);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            builtinLease.require(
                new CanvasFunctionBinding(1L, builtin.definition(), bound.pluginSource())));
  }

  /** 未安装函数、失败后的租用与幂等关闭都必须 fail closed，绝不留悬空租用。 */
  @Test
  void releasesTheLeaseOnFailureAndRefusesUseAfterClose() {
    CanvasFunctionCatalogSource.Lease unknown = source.acquireCurrent();
    assertThrows(IllegalArgumentException.class, () -> unknown.bind("missing-function"));
    assertThrows(IllegalStateException.class, unknown::epoch);

    CanvasFunctionCatalogSource.Lease closed = source.acquireCurrent();
    closed.close();
    closed.close();
    assertThrows(IllegalStateException.class, () -> closed.bind(BUILTIN_FUNCTION));
  }

  /** 派生来源失败时必须释放租用，清理期的次要 Error 只能 suppressed，不能替换原始错误。 */
  @Test
  void preservesPrimaryErrorWhenConstructionAndCleanupBothFail() {
    PluginCatalogRuntime mockedRuntime = mock(PluginCatalogRuntime.class);
    PluginCatalogRuntime.Lease lease = mock(PluginCatalogRuntime.Lease.class);
    PluginCatalogSnapshot snapshot = mock(PluginCatalogSnapshot.class);
    AssertionError primary = new AssertionError("source derivation failed");
    LinkageError cleanup = new LinkageError("cleanup failed");
    when(mockedRuntime.acquireCurrent()).thenReturn(lease);
    when(lease.snapshot()).thenReturn(snapshot);
    when(snapshot.configuredPlugins()).thenThrow(primary);
    doThrow(cleanup).when(lease).close();

    assertSame(
        primary,
        assertThrows(
            AssertionError.class,
            () -> new PluginCanvasFunctionCatalogSource(mockedRuntime).acquireCurrent()));
    assertEquals(List.of(cleanup), List.of(primary.getSuppressed()));
    verify(lease).close();
  }

  /** 底层重复抛出同一个 Error 时不能 self-suppress，否则原始原因会被 IllegalArgumentException 遮蔽。 */
  @Test
  void neverSuppressesThePrimaryOntoItself() {
    PluginCatalogRuntime mockedRuntime = mock(PluginCatalogRuntime.class);
    PluginCatalogRuntime.Lease lease = mock(PluginCatalogRuntime.Lease.class);
    PluginCatalogSnapshot snapshot = mock(PluginCatalogSnapshot.class);
    AssertionError primary = new AssertionError("same failure");
    when(mockedRuntime.acquireCurrent()).thenReturn(lease);
    when(lease.snapshot()).thenReturn(snapshot);
    when(snapshot.configuredPlugins()).thenThrow(primary);
    doThrow(primary).when(lease).close();
    assertSame(
        primary,
        assertThrows(
            AssertionError.class,
            () -> new PluginCanvasFunctionCatalogSource(mockedRuntime).acquireCurrent()));
    assertEquals(0, primary.getSuppressed().length);
  }

  /** current(1) 退役后按持久历史重建 epoch 1：走同一条 restore 路径，且绝不重新发布旧 epoch。 */
  @Test
  void rebuildsHistoricalEpochThroughTheSameRestorePath() {
    assertEquals(1, fixture.instanceCount());

    fixture.publishNextEpoch();
    assertEquals(2L, fixture.runtime.state().appliedEpoch());
    assertEquals(2, fixture.instanceCount(), "a new epoch must freeze its own instance batch");

    try (CanvasFunctionCatalogSource.Lease history = source.acquire(1L)) {
      assertEquals(1L, history.epoch());
      CanvasFunctionBinding binding = history.bind(PLUGIN_FUNCTION);
      assertEquals(1L, binding.catalogEpoch());
      PluginConfigurationRow row = fixture.authorityRow();
      assertEquals(row.revision(), binding.pluginSource().configRevision());
      assertEquals(row.capabilityFingerprint(), binding.pluginSource().capabilityFingerprint());
      assertSame(history.catalog().require(PLUGIN_FUNCTION), history.require(binding));
      assertEquals(
          3,
          fixture.instanceCount(),
          "history rebuild must configure its own frozen batch through bootstrap.restore");
    }

    assertEquals(
        2L, fixture.runtime.state().appliedEpoch(), "leasing history must not republish it");
    assertNull(fixture.runtime.state().applicationError());
  }

  /** 退役快照被租用钉住期间实例必须存活，最后一个租用释放时才关闭；新 epoch 的实例不受影响。 */
  @Test
  void keepsRetiredInstancesAliveUntilTheLastLeaseCloses() {
    CanvasFunctionCatalogSource.Lease pinning = source.acquireCurrent();
    fixture.publishNextEpoch();
    assertEquals(2L, fixture.runtime.state().appliedEpoch());
    assertFalse(
        fixture.instance(0).isClosed(),
        "a retired snapshot must stay alive while a lease still pins it");

    try (CanvasFunctionCatalogSource.Lease reused = source.acquire(1L)) {
      assertSame(pinning.catalog(), reused.catalog(), "a pinned epoch must not be rebuilt");
      assertEquals(2, fixture.instanceCount());
    }
    assertFalse(
        fixture.instance(0).isClosed(), "releasing one of two leases must not retire the batch");

    pinning.close();
    pinning.close();
    assertTrue(
        fixture.instance(0).isClosed(), "the last lease release must close the retired batch");
    assertFalse(fixture.instance(1).isClosed(), "the current epoch must stay alive");
  }

  /** 真实启动装配：epoch 1 由 bootstrap 写入权威行与配置历史，运行期所有者持有该快照。 */
  private static final class Fixture {

    private final CanvasPlugin plugin = new CanvasPlugin();

    private final InMemoryPluginConfigurationRepository repository =
        new InMemoryPluginConfigurationRepository();

    private final PluginCatalogRuntime runtime;

    private Fixture() {
      StudioPluginRegistry registry = new StudioPluginRegistry(List.of(plugin));
      PluginCatalogBootstrap bootstrap =
          new PluginCatalogBootstrap(registry, repository, new InMemoryTransactionManager());
      List<CanvasFunctionAdapter> builtinAdapters = List.of(adapter(BUILTIN_FUNCTION));
      this.runtime =
          new PluginCatalogRuntime(
              bootstrap,
              repository,
              PluginCanvasFunctionCatalogSourceTest::context,
              List.of(),
              builtinAdapters,
              bootstrap.bootstrap(
                  PluginCanvasFunctionCatalogSourceTest::context, List.of(), builtinAdapters));
    }

    /** 权威推进到下一个 epoch（内容不变）并让运行期发布：新 epoch 必须重新 configure 一整批实例。 */
    void publishNextEpoch() {
      PluginCatalogRecord current = repository.readCatalog();
      PluginCatalogManifest manifest = current.manifest();
      boolean committed =
          repository.commit(
              current.epoch(),
              List.of(),
              new PluginCatalogManifest(manifest.plugins(), manifest.catalogFingerprint()));
      assertTrue(committed, "the authority epoch CAS must win");
      runtime.refresh();
    }

    PluginConfigurationRow authorityRow() {
      long revision = repository.readCatalog().manifest().plugins().get(PLUGIN_ID).configRevision();
      return repository.readConfiguration(PLUGIN_ID, revision).orElseThrow();
    }

    Instance instance(int index) {
      return plugin.instance(index);
    }

    int instanceCount() {
      return plugin.instanceCount();
    }

    void close() {
      runtime.close();
    }
  }

  private static PluginContext context(String pluginId, PluginCredentialReference reference) {
    return new PluginContext(
        new PluginCredentialAccess() {
          @Override
          public PluginCredentialSnapshot resolve() {
            throw new PluginCredentialUnavailableException(
                PluginCredentialUnavailableReason.NOT_CONNECTED, "test context");
          }

          @Override
          public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
            return false;
          }
        },
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  private static CanvasFunctionDefinition definition(String name, String description) {
    return CanvasFunctionDefinition.of(
        name,
        description,
        CanvasJson.parseObject(
            "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"),
        CanvasResourceKind.IMAGE,
        new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()));
  }

  private static CanvasFunctionAdapter adapter(String functionName) {
    CanvasFunctionDefinition definition = definition(functionName, "function " + functionName);
    return new CanvasFunctionAdapter() {
      @Override
      public List<CanvasFunctionDefinition> functions() {
        return List.of(definition);
      }

      @Override
      public boolean enabled() {
        return true;
      }

      @Override
      public String unavailableReason() {
        return null;
      }

      @Override
      public void preflight(CanvasFunctionFrozenRun run) {}

      @Override
      public void submit(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {}

      @Override
      public List<UUID> execute(
          CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
        return List.of();
      }
    };
  }

  /** 每次 configure 产出一个可观测关闭状态的实例，用于断言退役时机。 */
  private static final class CanvasPlugin implements StudioPlugin {

    private final PluginDescriptor descriptor =
        new PluginDescriptor(PLUGIN_ID, "Canvas Plugin", "1.0");

    private final List<Instance> instances = Collections.synchronizedList(new ArrayList<>());

    @Override
    public PluginDescriptor descriptor() {
      return descriptor;
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
      Instance instance = new Instance();
      instances.add(instance);
      return instance;
    }

    Instance instance(int index) {
      return instances.get(index);
    }

    int instanceCount() {
      return instances.size();
    }
  }

  private static final class Instance implements PluginInstance {

    private final CanvasFunctionAdapter adapter = adapter(PLUGIN_FUNCTION);

    private final AtomicBoolean closed = new AtomicBoolean();

    @Override
    public List<HarnessContributor> harnessContributors() {
      return List.of();
    }

    @Override
    public List<CanvasFunctionAdapter> canvasAdapters() {
      return List.of(adapter);
    }

    @Override
    public void close() {
      closed.set(true);
    }

    boolean isClosed() {
      return closed.get();
    }
  }
}
