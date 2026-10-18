package fun.fengwk.kkstudio.platform.plugin.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.harness.common.schema.BooleanSchema;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
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
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;
import fun.fengwk.kkstudio.platform.plugin.testing.TestPluginFixtures;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * {@link PluginCatalogSnapshot} 的构建与生命周期契约测试。
 *
 * <p>覆盖真实成功聚合、配置覆盖校验、半途失败/目录冲突全部回收、构建期致命 {@link Error} 传播，以及 close 的幂等、逆序与故障隔离。
 */
class PluginCatalogSnapshotTest {

  private static final PluginConfigSchema SCHEMA = TestPluginFixtures.EMPTY_SCHEMA;
  private static final String PLUGIN_ALPHA = "alpha.plugin";
  private static final String PLUGIN_BETA = "beta.plugin";
  private static final String PLUGIN_GAMMA = "gamma.plugin";

  /** 真实两域聚合：按安装 id 顺序冻结实例，并合并插件贡献与容器内置贡献。 */
  @Test
  void buildFreezesInstancesInInstallOrderAndAggregatesBothDomains() {
    TestPlugin alpha = new TestPlugin(PLUGIN_ALPHA);
    alpha.configure =
        () ->
            new RecordingInstance(
                List.of(contributor("alpha.contrib")), List.of(adapter("alpha.run")));
    TestPlugin beta = new TestPlugin(PLUGIN_BETA);
    beta.configure =
        () ->
            new RecordingInstance(
                List.of(contributor("beta.contrib")), List.of(adapter("beta.run")));
    // registry 打乱输入顺序，验证快照仍按 pluginId 升序冻结。
    StudioPluginRegistry registry = new StudioPluginRegistry(List.of(beta, alpha));

    PluginCatalogSnapshot snapshot =
        PluginCatalogSnapshot.build(
            registry,
            initialConfigurations(registry),
            PluginCatalogSnapshotTest::contexts,
            List.of(contributor("builtin.contrib")),
            List.of(adapter("builtin.run")));

    assertEquals(
        List.of(PLUGIN_ALPHA, PLUGIN_BETA),
        snapshot.configuredPlugins().stream().map(c -> c.descriptor().pluginId()).toList());
    assertTrue(
        snapshot.harnessCatalog().findDescriptor(new ContributorId("alpha.contrib")).isPresent());
    assertTrue(
        snapshot.harnessCatalog().findDescriptor(new ContributorId("beta.contrib")).isPresent());
    assertTrue(
        snapshot.harnessCatalog().findDescriptor(new ContributorId("builtin.contrib")).isPresent());
    assertEquals(Set.of("alpha.run", "beta.run", "builtin.run"), canvasFunctionNames(snapshot));
    // 归属只记录插件贡献，内置贡献不归属任何插件。
    assertEquals(
        Set.of(new ContributorId("alpha.contrib")),
        snapshot.configuredPlugins().get(0).contributorIds());
    assertEquals(Set.of("beta.run"), snapshot.configuredPlugins().get(1).functionNames());
    assertEquals(Map.of(PLUGIN_ALPHA, 0L, PLUGIN_BETA, 0L), snapshot.revisions());
    snapshot.close();
  }

  /** configure 只收到 Schema 归一后的配置，修订号在归一化与冻结之间保持不变。 */
  @Test
  void configureReceivesNormalizedValuesWithTheOriginalRevision() {
    TestPlugin plugin = new TestPlugin(PLUGIN_ALPHA);
    plugin.schema =
        new PluginConfigSchema(
            new InputSchema(
                null, Map.of("enabled", new BooleanSchema(null)), Set.of("enabled"), false),
            "{\"enabled\":true}",
            Map.of());
    StudioPluginRegistry registry = new StudioPluginRegistry(List.of(plugin));
    try (PluginCatalogSnapshot snapshot =
        PluginCatalogSnapshot.build(
            registry,
            Map.of(PLUGIN_ALPHA, new PluginConfig(7, "{}")),
            PluginCatalogSnapshotTest::contexts,
            List.of(),
            List.of())) {
      assertEquals(new PluginConfig(7, "{\"enabled\":true}"), plugin.receivedConfig);
      assertEquals(plugin.receivedConfig, snapshot.configuredPlugins().get(0).config());
    }
  }

  /** 缺少已安装 Plugin 的配置时固定失败。 */
  @Test
  void buildRejectsConfigurationCoverageGaps() {
    TestPlugin alpha = new TestPlugin(PLUGIN_ALPHA);
    StudioPluginRegistry registry =
        new StudioPluginRegistry(List.of(alpha, new TestPlugin(PLUGIN_BETA)));

    IllegalStateException missing =
        assertThrows(
            IllegalStateException.class,
            () ->
                PluginCatalogSnapshot.build(
                    registry,
                    Map.of(PLUGIN_ALPHA, PluginConfig.initial(SCHEMA)),
                    PluginCatalogSnapshotTest::contexts,
                    List.of(),
                    List.of()));
    assertTrue(missing.getMessage().contains("stage=configure"), missing.getMessage());
  }

  /** 读取贡献失败时，先前与当前实例都必须回收，半成品绝不外泄。 */
  @Test
  void buildReapsAllInstancesWhenContributionReadingFails() {
    TestPlugin alpha = new TestPlugin(PLUGIN_ALPHA);
    RecordingInstance alphaInstance =
        new RecordingInstance(List.of(contributor("alpha.contrib")), List.of());
    alpha.configure = () -> alphaInstance;
    TestPlugin beta = new TestPlugin(PLUGIN_BETA);
    RecordingInstance betaInstance =
        new RecordingInstance(List.of(contributor("beta.contrib")), List.of());
    betaInstance.nullContributors = true;
    beta.configure = () -> betaInstance;
    StudioPluginRegistry registry = new StudioPluginRegistry(List.of(alpha, beta));

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                PluginCatalogSnapshot.build(
                    registry,
                    initialConfigurations(registry),
                    PluginCatalogSnapshotTest::contexts,
                    List.of(),
                    List.of()));

    assertTrue(failure.getMessage().contains("pluginId=" + PLUGIN_BETA), failure.getMessage());
    assertEquals(1, alphaInstance.closeCount, "already built instance must be reaped");
    assertEquals(1, betaInstance.closeCount, "instance entering the reap list must be reaped");
  }

  /** 两域目录冲突（重复 contributor id 或重复函数名）必须先回收全部实例再固定失败。 */
  @Test
  void buildReapsAllInstancesOnCatalogConflict() {
    // Harness 目录冲突
    TestPlugin alpha = new TestPlugin(PLUGIN_ALPHA);
    RecordingInstance alphaInstance =
        new RecordingInstance(List.of(contributor("dup.contrib")), List.of());
    alpha.configure = () -> alphaInstance;
    TestPlugin beta = new TestPlugin(PLUGIN_BETA);
    RecordingInstance betaInstance =
        new RecordingInstance(List.of(contributor("dup.contrib")), List.of());
    beta.configure = () -> betaInstance;
    StudioPluginRegistry registry = new StudioPluginRegistry(List.of(alpha, beta));

    IllegalStateException harnessConflict =
        assertThrows(
            IllegalStateException.class,
            () ->
                PluginCatalogSnapshot.build(
                    registry,
                    initialConfigurations(registry),
                    PluginCatalogSnapshotTest::contexts,
                    List.of(),
                    List.of()));
    assertTrue(
        harnessConflict.getMessage().contains("stage=catalog"), harnessConflict.getMessage());
    assertNull(harnessConflict.getCause(), "build failure must not leak a raw cause");
    assertEquals(1, alphaInstance.closeCount);
    assertEquals(1, betaInstance.closeCount);

    // Canvas 目录冲突
    TestPlugin gamma = new TestPlugin(PLUGIN_GAMMA);
    RecordingInstance gammaInstance =
        new RecordingInstance(List.of(), List.of(adapter("same.run")));
    gamma.configure = () -> gammaInstance;
    TestPlugin delta = new TestPlugin("delta.plugin");
    RecordingInstance deltaInstance =
        new RecordingInstance(List.of(), List.of(adapter("same.run")));
    delta.configure = () -> deltaInstance;
    StudioPluginRegistry canvasRegistry = new StudioPluginRegistry(List.of(gamma, delta));

    assertThrows(
        IllegalStateException.class,
        () ->
            PluginCatalogSnapshot.build(
                canvasRegistry,
                initialConfigurations(canvasRegistry),
                PluginCatalogSnapshotTest::contexts,
                List.of(),
                List.of()));
    assertEquals(1, gammaInstance.closeCount);
    assertEquals(1, deltaInstance.closeCount);
  }

  /** configure 抛普通异常：收敛为只带 stage/id 的固定错误，不带 raw cause，并回收已建实例。 */
  @Test
  void buildConvergesRuntimeExceptionWithoutLeakingCause() {
    TestPlugin alpha = new TestPlugin(PLUGIN_ALPHA);
    RecordingInstance alphaInstance =
        new RecordingInstance(List.of(contributor("alpha.contrib")), List.of());
    alpha.configure = () -> alphaInstance;
    TestPlugin beta = new TestPlugin(PLUGIN_BETA);
    beta.configureRuntime = new RuntimeException("raw provider token abc123");
    StudioPluginRegistry registry = new StudioPluginRegistry(List.of(alpha, beta));

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                PluginCatalogSnapshot.build(
                    registry,
                    initialConfigurations(registry),
                    PluginCatalogSnapshotTest::contexts,
                    List.of(),
                    List.of()));

    assertTrue(failure.getMessage().contains("stage=configure"), failure.getMessage());
    assertTrue(failure.getMessage().contains("pluginId=" + PLUGIN_BETA), failure.getMessage());
    assertNull(failure.getCause(), "raw failure must not be attached as a cause");
    assertFalse(failure.getMessage().contains("abc123"), "raw detail must not leak");
    assertEquals(1, alphaInstance.closeCount, "already configured instance must be reaped");
  }

  /** configure 抛致命 Error：所有已建实例仍被回收，原始 Error 必须原样传播且不被回收错误替代。 */
  @Test
  void buildReapsCreatedInstancesAndPropagatesFatalError() {
    TestPlugin alpha = new TestPlugin(PLUGIN_ALPHA);
    RecordingInstance alphaInstance = new RecordingInstance(List.of(), List.of());
    alpha.configure = () -> alphaInstance;
    TestPlugin beta = new TestPlugin(PLUGIN_BETA);
    Error fatal = new AssertionError("fatal configure error");
    beta.configureError = fatal;
    StudioPluginRegistry registry = new StudioPluginRegistry(List.of(alpha, beta));

    Error propagated =
        assertThrows(
            Error.class,
            () ->
                PluginCatalogSnapshot.build(
                    registry,
                    initialConfigurations(registry),
                    PluginCatalogSnapshotTest::contexts,
                    List.of(),
                    List.of()));
    assertSame(fatal, propagated, "original fatal Error must be propagated unchanged");
    assertEquals(1, alphaInstance.closeCount, "created instances must still be reaped on Error");

    // 回收阶段再抛致命 Error 也不得替代原始 Error。
    TestPlugin gamma = new TestPlugin(PLUGIN_GAMMA);
    RecordingInstance gammaInstance = new RecordingInstance(List.of(), List.of());
    gammaInstance.closeError = new StackOverflowError("cleanup fatal");
    gamma.configure = () -> gammaInstance;
    TestPlugin delta = new TestPlugin("zeta.plugin");
    Error original = new AssertionError("original fatal");
    delta.configureError = original;
    StudioPluginRegistry secondRegistry = new StudioPluginRegistry(List.of(gamma, delta));

    Error second =
        assertThrows(
            Error.class,
            () ->
                PluginCatalogSnapshot.build(
                    secondRegistry,
                    initialConfigurations(secondRegistry),
                    PluginCatalogSnapshotTest::contexts,
                    List.of(),
                    List.of()));
    assertSame(original, second, "cleanup Error must not replace the original Error");
    assertEquals(
        1, gammaInstance.closeCount, "cleanup must run after the earlier instance was built");
    assertEquals(0, second.getSuppressed().length, "secondary details must not be attached");
  }

  /** 普通构建失败不能把清理时的致命错误降级为 RuntimeException。 */
  @Test
  void cleanupFatalErrorIsNotDowngradedAfterRuntimeFailure() {
    RecordingInstance instance = new RecordingInstance(List.of(), List.of());
    instance.closeError = new AssertionError("fatal cleanup");
    TestPlugin first = new TestPlugin(PLUGIN_ALPHA, () -> instance);
    TestPlugin failing = new TestPlugin(PLUGIN_BETA);
    failing.configureRuntime = new IllegalStateException("configuration failed");
    StudioPluginRegistry registry = new StudioPluginRegistry(List.of(first, failing));
    Error fatal =
        assertThrows(
            Error.class,
            () ->
                PluginCatalogSnapshot.build(
                    registry,
                    initialConfigurations(registry),
                    PluginCatalogSnapshotTest::contexts,
                    List.of(),
                    List.of()));
    assertSame(instance.closeError, fatal);
    assertEquals(1, instance.closeCount);
  }

  /** close 幂等、逆序释放、并隔离 RuntimeException；致命 Error 仍回收全部后传播，二次 close 空操作。 */
  @Test
  void closeIsIdempotentReleasesInReverseAndIsolatesFailures() {
    List<String> closeOrder = new ArrayList<>();
    RecordingInstance alpha = new RecordingInstance(List.of(), List.of(), closeOrder, PLUGIN_ALPHA);
    RecordingInstance beta = new RecordingInstance(List.of(), List.of(), closeOrder, PLUGIN_BETA);
    beta.closeRuntime = new RuntimeException("secondary close failure");
    RecordingInstance gamma = new RecordingInstance(List.of(), List.of(), closeOrder, PLUGIN_GAMMA);
    gamma.closeError = new AssertionError("fatal close");
    Map<String, TestPlugin> plugins = new LinkedHashMap<>();
    plugins.put(PLUGIN_ALPHA, new TestPlugin(PLUGIN_ALPHA, () -> alpha));
    plugins.put(PLUGIN_BETA, new TestPlugin(PLUGIN_BETA, () -> beta));
    plugins.put(PLUGIN_GAMMA, new TestPlugin(PLUGIN_GAMMA, () -> gamma));
    StudioPluginRegistry registry = new StudioPluginRegistry(new ArrayList<>(plugins.values()));

    PluginCatalogSnapshot snapshot =
        PluginCatalogSnapshot.build(
            registry,
            initialConfigurations(registry),
            PluginCatalogSnapshotTest::contexts,
            List.of(),
            List.of());

    Error fatal = assertThrows(Error.class, snapshot::close);
    assertSame(gamma.closeError, fatal, "fatal close Error must be propagated");
    // 逆序释放且 RuntimeException 不阻断其它回收。
    assertEquals(List.of(PLUGIN_GAMMA, PLUGIN_BETA, PLUGIN_ALPHA), closeOrder);

    // 幂等：第二次 close 不再触碰任何实例，也不再抛错。
    snapshot.close();
    assertEquals(1, alpha.closeCount);
    assertEquals(1, beta.closeCount);
    assertEquals(1, gamma.closeCount);
  }

  private static Set<String> canvasFunctionNames(PluginCatalogSnapshot snapshot) {
    return snapshot.canvasFunctionCatalog().list().stream()
        .map(registered -> registered.function().name())
        .collect(Collectors.toSet());
  }

  private static HarnessContributor contributor(String id) {
    return HarnessContributor.of(
        new ContributorDescriptor(new ContributorId(id), "contributor " + id, "1.0", Set.of()),
        registrar -> {});
  }

  private static CanvasFunctionAdapter adapter(String functionName) {
    CanvasFunctionDefinition definition =
        CanvasFunctionDefinition.of(
            functionName,
            "function " + functionName,
            CanvasJson.parseObject(
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"),
            CanvasResourceKind.IMAGE,
            new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()));
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

  private static Map<String, PluginConfig> initialConfigurations(StudioPluginRegistry registry) {
    Map<String, PluginConfig> configurations = new LinkedHashMap<>();
    for (StudioPlugin plugin : registry.plugins()) {
      String pluginId = plugin.descriptor().pluginId();
      configurations.put(pluginId, PluginConfig.initial(plugin.configurationSchema()));
    }
    return configurations;
  }

  private static PluginContext contexts(String pluginId) {
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

  /** 可配置失败模式的 Plugin 定义；默认无认证、空 schema、返回空实例。 */
  private static final class TestPlugin implements StudioPlugin {

    private final PluginDescriptor descriptor;
    private PluginConfigSchema schema = SCHEMA;
    private PluginConfig receivedConfig;
    private Supplier<PluginInstance> configure = TestPluginFixtures::emptyInstance;
    private RuntimeException configureRuntime;
    private Error configureError;

    TestPlugin(String pluginId) {
      this(pluginId, TestPluginFixtures::emptyInstance);
    }

    TestPlugin(String pluginId, Supplier<PluginInstance> configure) {
      this.descriptor = new PluginDescriptor(pluginId, "Plugin " + pluginId, "1.0");
      this.configure = configure;
    }

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
      receivedConfig = config;
      if (configureError != null) {
        throw configureError;
      }
      if (configureRuntime != null) {
        throw configureRuntime;
      }
      return configure.get();
    }
  }

  /** 记录 close 次数与顺序，并可注入 RuntimeException / Error 的实例替身。 */
  private static final class RecordingInstance implements PluginInstance {

    private final List<HarnessContributor> contributors;
    private final List<CanvasFunctionAdapter> adapters;
    private final List<String> closeOrder;
    private final String label;

    private boolean nullContributors;
    private RuntimeException closeRuntime;
    private Error closeError;
    private int closeCount;

    RecordingInstance(List<HarnessContributor> contributors, List<CanvasFunctionAdapter> adapters) {
      this(contributors, adapters, null, null);
    }

    RecordingInstance(
        List<HarnessContributor> contributors,
        List<CanvasFunctionAdapter> adapters,
        List<String> closeOrder,
        String label) {
      this.contributors = contributors;
      this.adapters = adapters;
      this.closeOrder = closeOrder;
      this.label = label;
    }

    @Override
    public List<HarnessContributor> harnessContributors() {
      return nullContributors ? null : contributors;
    }

    @Override
    public List<CanvasFunctionAdapter> canvasAdapters() {
      return adapters;
    }

    @Override
    public void close() {
      closeCount++;
      if (closeOrder != null) {
        closeOrder.add(label);
      }
      if (closeError != null) {
        throw closeError;
      }
      if (closeRuntime != null) {
        throw closeRuntime;
      }
    }
  }
}
