package fun.fengwk.kkstudio.platform.plugin.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.common.schema.BooleanSchema;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigurationState;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableReason;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialReference;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.plugin.testing.TestPluginFixtures;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * {@link PluginCatalogRuntime} 的权威对账、原子发布与租用退役契约测试（内存权威仓储替身）。
 *
 * <p>覆盖完整两域发布、相同与乱序 epoch、候选构建期权威推进、某个目标配置无法应用后完整 manifest 仍不得部分发布、权威不可用、并发刷新、
 * 租用与退役竞争、关闭语义、候选与前代实例只关一次，以及致命 {@link Error} 不被降级吞掉。
 */
class PluginCatalogRuntimeTest {

  private static final String PLUGIN_ALPHA = "alpha.plugin";
  private static final String PLUGIN_BETA = "beta.plugin";
  private static final String ENABLED_VALUES = "{\"enabled\":true}";
  private static final String DISABLED_VALUES = "{\"enabled\":false}";

  private static final UUID GENERATION = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final String CONTEXT =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  private static PluginConfigSchema enabledSchema() {
    return new PluginConfigSchema(
        new InputSchema(null, Map.of("enabled", new BooleanSchema(null)), Set.of("enabled"), false),
        ENABLED_VALUES,
        Map.of());
  }

  /** 初始快照必须对应已初始化 epoch：未初始化形态绝不允许进入运行期。 */
  @Test
  void constructorRejectsAnUninitializedInitialSnapshot() {
    FakeRepository repository = new FakeRepository();
    TestPlugin alpha = new TestPlugin(PLUGIN_ALPHA);
    alpha.schema = TestPluginFixtures.EMPTY_SCHEMA;
    StudioPluginRegistry registry = new StudioPluginRegistry(List.of(alpha));
    PluginCatalogBootstrap bootstrap = bootstrap(repository, registry);
    try (PluginCatalogSnapshot uninitialized =
        PluginCatalogSnapshot.build(
            PluginCatalogBootstrap.UNINITIALIZED_EPOCH,
            registry,
            Map.of(
                PLUGIN_ALPHA,
                new PluginConfigurationState(
                    PluginConfig.initial(TestPluginFixtures.EMPTY_SCHEMA),
                    new PluginCredentialReference(PLUGIN_ALPHA, GENERATION, CONTEXT))),
            contexts(),
            List.of(),
            List.of())) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new PluginCatalogRuntime(
                  bootstrap, repository, contexts(), List.of(), List.of(), uninitialized));
    }
  }

  /** 权威 epoch 前进时一次发布完整两域目录，前代快照退役关闭恰好一次。 */
  @Test
  void refreshPublishesTheWholeTwoDomainEpoch() {
    Fixture fixture = fixture(PLUGIN_ALPHA);
    TestPlugin alpha = fixture.plugin(PLUGIN_ALPHA);
    try (PluginCatalogRuntime runtime = fixture.runtime()) {
      try (PluginCatalogRuntime.Lease initial = runtime.acquireCurrent()) {
        assertEquals(new PluginCatalogRuntime.RuntimeState(1, 1, null), runtime.state());
        assertEquals(1, initial.snapshot().epoch());
        assertTrue(hasContributor(initial.snapshot(), PLUGIN_ALPHA + ".contrib"));
        assertEquals(Set.of(PLUGIN_ALPHA + ".run"), canvasFunctionNames(initial.snapshot()));
      }

      // 权威推进到 epoch 2，目标配置停用该 Plugin：发布后两个域都不再有它的贡献。
      commitEpoch(fixture, Map.of(PLUGIN_ALPHA, DISABLED_VALUES));
      runtime.refresh();

      assertEquals(new PluginCatalogRuntime.RuntimeState(2, 2, null), runtime.state());
      assertEquals(2, alpha.createdInstances.size(), "只跟踪运行期对账创建的实例");
      assertEquals(1, alpha.createdInstances.get(0).closeCount, "无租用的前代必须退役关闭");
      try (PluginCatalogRuntime.Lease current = runtime.acquireCurrent()) {
        assertEquals(2, current.snapshot().epoch());
        assertFalse(hasContributor(current.snapshot(), PLUGIN_ALPHA + ".contrib"));
        assertEquals(Set.of(), canvasFunctionNames(current.snapshot()));
      }
      assertEquals(0, alpha.createdInstances.get(1).closeCount, "当前快照保持所有者引用");
    }
    assertEquals(1, alpha.createdInstances.get(1).closeCount, "close 释放所有者引用");
  }

  /** 相同 epoch 不再 configure；乱序回读绝不降级已发布视图。 */
  @Test
  void refreshWithAnUnchangedOrOlderAuthorityNeverConfigures() {
    Fixture fixture = fixture(PLUGIN_ALPHA);
    TestPlugin alpha = fixture.plugin(PLUGIN_ALPHA);
    commitEpoch(fixture, Map.of(PLUGIN_ALPHA, DISABLED_VALUES));
    try (PluginCatalogRuntime runtime = fixture.runtime()) {
      runtime.refresh();
      assertEquals(new PluginCatalogRuntime.RuntimeState(2, 2, null), runtime.state());
      int configured = alpha.createdInstances.size();

      runtime.refresh();

      assertEquals(configured, alpha.createdInstances.size(), "相同 epoch 不得 configure");
      assertEquals(new PluginCatalogRuntime.RuntimeState(2, 2, null), runtime.state());

      // 乱序回读（权威暂时只暴露更旧的 epoch）：绝不降级到更旧的视图。
      fixture.repository().store(fixture.initialRecord(), List.of());
      runtime.refresh();

      assertEquals(new PluginCatalogRuntime.RuntimeState(2, 2, null), runtime.state());
      assertEquals(configured, alpha.createdInstances.size(), "乱序权威不得 configure");
      try (PluginCatalogRuntime.Lease lease = runtime.acquireCurrent()) {
        assertEquals(2, lease.snapshot().epoch(), "乱序权威绝不降级已发布视图");
      }
    }
  }

  /** 候选构建期权威推进：候选过期，独立关闭且不发布，等待下一次对账。 */
  @Test
  void refreshDiscardsTheCandidateWhenTheAuthorityAdvancesDuringTheBuild() {
    Fixture fixture = fixture(PLUGIN_ALPHA);
    TestPlugin alpha = fixture.plugin(PLUGIN_ALPHA);
    commitEpoch(fixture, Map.of(PLUGIN_ALPHA, DISABLED_VALUES));
    FakeRepository repository = fixture.repository();
    int commits = repository.commitCalls;
    // 第二次回读（发布前复核）时权威推进到 epoch 3：本次构建的 epoch 2 候选已经过期。
    AtomicInteger reads = new AtomicInteger();
    repository.readHook =
        () -> {
          if (reads.incrementAndGet() == 2) {
            repository.store(new PluginCatalogRecord(3, repository.catalog.manifest()), List.of());
          }
        };

    try (PluginCatalogRuntime runtime = fixture.runtime()) {
      runtime.refresh();

      assertEquals(
          new PluginCatalogRuntime.RuntimeState(3, 1, null),
          runtime.state(),
          "过期候选不发布也不报错，等待下一次对账应用更新的 epoch");
      assertEquals(2, alpha.createdInstances.size(), "epoch 2 候选只构建一次");
      assertEquals(1, alpha.createdInstances.get(1).closeCount, "过期候选必须独立回收");
      assertEquals(0, alpha.createdInstances.get(0).closeCount, "旧视图必须保持发布");
      try (PluginCatalogRuntime.Lease lease = runtime.acquireCurrent()) {
        assertEquals(1, lease.snapshot().epoch());
      }
    }
    assertEquals(commits, repository.commitCalls, "对账绝不写库");
  }

  /** 某个目标配置无法应用时整个新 epoch 保留旧视图；随后提交仍包含该配置的完整 manifest 也不能部分发布。 */
  @Test
  void refreshKeepsTheWholeOldViewWhenTheTargetConfigurationCannotBeApplied() {
    Fixture fixture = fixture(PLUGIN_ALPHA, PLUGIN_BETA);
    TestPlugin alpha = fixture.plugin(PLUGIN_ALPHA);
    TestPlugin beta = fixture.plugin(PLUGIN_BETA);
    try (PluginCatalogRuntime runtime = fixture.runtime()) {
      // epoch 2：beta 的新目标配置在本节点无法应用；alpha 已先建实例，必须随候选整体回收。
      commitEpoch(fixture, Map.of(PLUGIN_ALPHA, DISABLED_VALUES, PLUGIN_BETA, DISABLED_VALUES));
      beta.applicationUnavailable = true;
      runtime.refresh();

      assertEquals(
          new PluginCatalogRuntime.RuntimeState(2, 1, PluginCatalogRuntime.ERROR_APPLY_FAILED),
          runtime.state(),
          "失败只暴露固定受控错误码，旧 epoch 保持发布");
      assertEquals(2, alpha.createdInstances.size());
      assertEquals(1, alpha.createdInstances.get(1).closeCount, "候选必须整体回收");
      assertEquals(0, alpha.createdInstances.get(0).closeCount);
      try (PluginCatalogRuntime.Lease lease = runtime.acquireCurrent()) {
        assertEquals(1, lease.snapshot().epoch());
        assertTrue(hasContributor(lease.snapshot(), PLUGIN_ALPHA + ".contrib"));
      }
      assertEquals(1, beta.createdInstances.size(), "失败目标只留下已发布的 epoch 1 实例");

      // epoch 3：后续提交的新完整 manifest 仍包含失败的目标配置（beta revision 2 未变）。
      commitEpoch(fixture, Map.of(PLUGIN_ALPHA, ENABLED_VALUES));
      runtime.refresh();

      assertEquals(
          new PluginCatalogRuntime.RuntimeState(3, 1, PluginCatalogRuntime.ERROR_APPLY_FAILED),
          runtime.state(),
          "仍包含失败配置的完整 manifest 不能部分发布");
      assertEquals(3, alpha.createdInstances.size());
      assertEquals(1, alpha.createdInstances.get(2).closeCount, "epoch 3 候选同样整体回收");
      try (PluginCatalogRuntime.Lease lease = runtime.acquireCurrent()) {
        assertEquals(1, lease.snapshot().epoch(), "旧视图必须整体保留");
        assertTrue(hasContributor(lease.snapshot(), PLUGIN_ALPHA + ".contrib"));
        assertTrue(hasContributor(lease.snapshot(), PLUGIN_BETA + ".contrib"));
      }

      // 本节点恢复应用能力后，下一次对账把整个 epoch 一次应用：alpha 的新值与 beta 的改动一起生效。
      beta.applicationUnavailable = false;
      commitEpoch(fixture, Map.of(PLUGIN_BETA, ENABLED_VALUES));
      runtime.refresh();

      assertEquals(new PluginCatalogRuntime.RuntimeState(4, 4, null), runtime.state());
      try (PluginCatalogRuntime.Lease lease = runtime.acquireCurrent()) {
        assertEquals(4, lease.snapshot().epoch());
        assertTrue(hasContributor(lease.snapshot(), PLUGIN_ALPHA + ".contrib"));
        assertTrue(hasContributor(lease.snapshot(), PLUGIN_BETA + ".contrib"));
      }
    }
  }

  /** 权威回读失败（SQL 不可用）：旧视图保留，只暴露固定受控错误码。 */
  @Test
  void refreshKeepsTheOldViewWhenTheAuthorityIsUnavailable() {
    Fixture fixture = fixture(PLUGIN_ALPHA);
    TestPlugin alpha = fixture.plugin(PLUGIN_ALPHA);
    commitEpoch(fixture, Map.of(PLUGIN_ALPHA, DISABLED_VALUES));
    fixture.repository().readFailure = new IllegalStateException("the database is unavailable");

    try (PluginCatalogRuntime runtime = fixture.runtime()) {
      runtime.refresh();

      assertEquals(
          new PluginCatalogRuntime.RuntimeState(
              1, 1, PluginCatalogRuntime.ERROR_AUTHORITY_UNAVAILABLE),
          runtime.state(),
          "不可回读时无法得知新的期望 epoch，保留最近一次已知状态");
      assertEquals(1, alpha.createdInstances.size(), "不可回读的权威不得 configure");
      try (PluginCatalogRuntime.Lease lease = runtime.acquireCurrent()) {
        assertEquals(1, lease.snapshot().epoch());
      }
    }
  }

  /** 权威仍为未初始化形态：不采用 defaults、不写库，保留旧视图并暴露固定受控错误码。 */
  @Test
  void refreshRejectsAnUninitializedAuthority() {
    Fixture fixture = fixture(PLUGIN_ALPHA);
    TestPlugin alpha = fixture.plugin(PLUGIN_ALPHA);
    fixture
        .repository()
        .store(new PluginCatalogRecord(0, PluginCatalogManifest.uninitialized()), List.of());
    int commits = fixture.repository().commitCalls;

    try (PluginCatalogRuntime runtime = fixture.runtime()) {
      runtime.refresh();

      assertEquals(
          new PluginCatalogRuntime.RuntimeState(
              1, 1, PluginCatalogRuntime.ERROR_AUTHORITY_UNAVAILABLE),
          runtime.state(),
          "restore 绝不把未初始化权威偷偷初始化");
      assertEquals(1, alpha.createdInstances.size(), "未初始化权威不得 configure");
      assertEquals(commits, fixture.repository().commitCalls, "对账绝不写库");
    }
  }

  /** 并发刷新被串行化：只构建一次候选，后到的刷新看到同一权威 epoch 便不再 configure。 */
  @Test
  void concurrentRefreshesSerializeToOneBuildAndOnePublish() throws Exception {
    Fixture fixture = fixture(PLUGIN_ALPHA, PLUGIN_BETA);
    TestPlugin alpha = fixture.plugin(PLUGIN_ALPHA);
    TestPlugin beta = fixture.plugin(PLUGIN_BETA);
    commitEpoch(fixture, Map.of(PLUGIN_ALPHA, DISABLED_VALUES, PLUGIN_BETA, DISABLED_VALUES));

    // 第一个刷新在构建中途被钉住，第二个刷新只能在它释放后串行进入。
    CountDownLatch buildStarted = new CountDownLatch(1);
    CountDownLatch releaseBuild = new CountDownLatch(1);
    alpha.beforeConfigure =
        () -> {
          buildStarted.countDown();
          awaitLatch(releaseBuild);
        };

    try (PluginCatalogRuntime runtime = fixture.runtime()) {
      ExecutorService executor = Executors.newFixedThreadPool(2);
      try {
        Future<?> first = executor.submit(runtime::refresh);
        assertTrue(buildStarted.await(30, TimeUnit.SECONDS), "第一个刷新必须开始构建");
        Future<?> second = executor.submit(runtime::refresh);
        releaseBuild.countDown();
        first.get(30, TimeUnit.SECONDS);
        second.get(30, TimeUnit.SECONDS);
      } finally {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      }

      assertEquals(new PluginCatalogRuntime.RuntimeState(2, 2, null), runtime.state());
      assertEquals(2, alpha.createdInstances.size(), "两次并发刷新只构建一次候选");
      assertEquals(2, beta.createdInstances.size(), "两次并发刷新只构建一次候选");
      assertEquals(1, alpha.createdInstances.get(0).closeCount, "epoch 1 前代退役只关一次");
      assertEquals(0, alpha.createdInstances.get(1).closeCount, "唯一候选保持发布");
      try (PluginCatalogRuntime.Lease lease = runtime.acquireCurrent()) {
        assertEquals(2, lease.snapshot().epoch());
      }
    }
  }

  /** 构建期的租用、状态读取与关闭只走生命周期锁，绝不等待串行中的对账。 */
  @Test
  void leasesAndCloseAreNotBlockedByAnInFlightRefresh() throws Exception {
    Fixture fixture = fixture(PLUGIN_ALPHA);
    TestPlugin alpha = fixture.plugin(PLUGIN_ALPHA);
    commitEpoch(fixture, Map.of(PLUGIN_ALPHA, DISABLED_VALUES));
    CountDownLatch buildStarted = new CountDownLatch(1);
    CountDownLatch releaseBuild = new CountDownLatch(1);
    alpha.beforeConfigure =
        () -> {
          buildStarted.countDown();
          awaitLatch(releaseBuild);
        };

    PluginCatalogRuntime runtime = fixture.runtime();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> refreshing = executor.submit(runtime::refresh);
      assertTrue(buildStarted.await(30, TimeUnit.SECONDS), "刷新必须开始构建");

      Future<Long> probed =
          executor.submit(
              () -> {
                try (PluginCatalogRuntime.Lease lease = runtime.acquireCurrent()) {
                  assertEquals(new PluginCatalogRuntime.RuntimeState(2, 1, null), runtime.state());
                  return lease.snapshot().epoch();
                }
              });
      assertEquals(1L, probed.get(5, TimeUnit.SECONDS), "构建期的租用与状态读取不得被阻塞");

      executor.submit(runtime::close).get(5, TimeUnit.SECONDS);
      releaseBuild.countDown();
      refreshing.get(30, TimeUnit.SECONDS);

      assertEquals(
          new PluginCatalogRuntime.RuntimeState(2, 1, null), runtime.state(), "关闭后到达的候选不得发布");
      assertEquals(2, alpha.createdInstances.size());
      assertEquals(1, alpha.createdInstances.get(1).closeCount, "关闭后到达的候选必须回收");
      assertThrows(IllegalStateException.class, runtime::acquireCurrent);
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  /** 租用把退役快照钉到最后一个引用释放；Lease 关闭幂等。 */
  @Test
  void leasesPinTheRetiredSnapshotUntilTheLastRelease() {
    Fixture fixture = fixture(PLUGIN_ALPHA);
    TestPlugin alpha = fixture.plugin(PLUGIN_ALPHA);
    try (PluginCatalogRuntime runtime = fixture.runtime()) {
      PluginCatalogRuntime.Lease retiredLease = runtime.acquireCurrent();
      commitEpoch(fixture, Map.of(PLUGIN_ALPHA, DISABLED_VALUES));

      runtime.refresh();

      assertEquals(new PluginCatalogRuntime.RuntimeState(2, 2, null), runtime.state());
      assertEquals(2, alpha.createdInstances.size());
      assertEquals(0, alpha.createdInstances.get(0).closeCount, "活动租用必须钉住退役快照");
      retiredLease.close();
      retiredLease.close();
      assertEquals(1, alpha.createdInstances.get(0).closeCount, "最后一个引用释放时恰好关一次");
      assertEquals(0, alpha.createdInstances.get(1).closeCount, "当前快照保持所有者引用");
      try (PluginCatalogRuntime.Lease current = runtime.acquireCurrent()) {
        assertEquals(2, current.snapshot().epoch());
      }
      assertEquals(0, alpha.createdInstances.get(1).closeCount);
    }
    assertEquals(1, alpha.createdInstances.get(1).closeCount, "close 释放所有者引用");
  }

  /** 关闭停止授予新租用，但不强杀活动租用；最后释放的租用负责回收。 */
  @Test
  void closeStopsNewLeasesAndLetsTheLastLeaseReleaseTheSnapshot() {
    Fixture fixture = fixture(PLUGIN_ALPHA);
    TestPlugin alpha = fixture.plugin(PLUGIN_ALPHA);
    PluginCatalogRuntime runtime = fixture.runtime();
    PluginCatalogRuntime.Lease lease = runtime.acquireCurrent();

    runtime.close();
    runtime.close();

    assertThrows(IllegalStateException.class, runtime::acquireCurrent);
    assertEquals(0, alpha.createdInstances.get(0).closeCount, "使用中的快照绝不被强杀");
    lease.close();
    assertEquals(1, alpha.createdInstances.get(0).closeCount, "最后释放的租用负责回收");
    lease.close();
    assertEquals(1, alpha.createdInstances.get(0).closeCount, "Lease 关闭幂等");
  }

  /** 未初始化形态不是合法租用坐标：绝不按 epoch 0 重建。 */
  @Test
  void acquireRejectsUninitializedEpochs() {
    Fixture fixture = fixture(PLUGIN_ALPHA);
    try (PluginCatalogRuntime runtime = fixture.runtime()) {
      assertThrows(IllegalArgumentException.class, () -> runtime.acquire(0));
      assertThrows(IllegalArgumentException.class, () -> runtime.acquire(-1));
      assertEquals(1, fixture.plugin(PLUGIN_ALPHA).createdInstances.size(), "非法坐标不得 configure");
    }
  }

  /** 已发布的 epoch 直接复用当前视图，不重复构建。 */
  @Test
  void acquireReusesTheCurrentViewForThePublishedEpoch() {
    Fixture fixture = fixture(PLUGIN_ALPHA);
    TestPlugin alpha = fixture.plugin(PLUGIN_ALPHA);
    try (PluginCatalogRuntime runtime = fixture.runtime();
        PluginCatalogRuntime.Lease current = runtime.acquireCurrent();
        PluginCatalogRuntime.Lease byEpoch = runtime.acquire(1)) {
      assertSame(current.snapshot(), byEpoch.snapshot(), "同 epoch 必须返回同一已发布视图");
      assertEquals(new PluginCatalogRuntime.RuntimeState(1, 1, null), runtime.state());
      assertEquals(1, alpha.createdInstances.size(), "复用不得重新 configure");
    }
  }

  /** 旧 epoch 按持久完整 manifest 严格重建，绝不倒退当前发布、也不把当前插件拼进旧任务。 */
  @Test
  void acquireRebuildsAnOlderEpochWithoutRegressingTheCurrentView() {
    Fixture fixture = fixture(PLUGIN_ALPHA);
    TestPlugin alpha = fixture.plugin(PLUGIN_ALPHA);
    commitEpoch(fixture, Map.of(PLUGIN_ALPHA, DISABLED_VALUES));
    try (PluginCatalogRuntime runtime = fixture.runtime()) {
      runtime.refresh();
      int commits = fixture.repository().commitCalls;
      assertEquals(new PluginCatalogRuntime.RuntimeState(2, 2, null), runtime.state());

      try (PluginCatalogRuntime.Lease history = runtime.acquire(1)) {
        assertEquals(1, history.snapshot().epoch());
        assertTrue(
            hasContributor(history.snapshot(), PLUGIN_ALPHA + ".contrib"),
            "旧 epoch 必须按持久 revision 重建完整两域目录");
        assertEquals(Set.of(PLUGIN_ALPHA + ".run"), canvasFunctionNames(history.snapshot()));
        assertEquals(0, alpha.createdInstances.get(2).closeCount, "活动租用不得关闭历史视图");
      }

      assertEquals(
          new PluginCatalogRuntime.RuntimeState(2, 2, null),
          runtime.state(),
          "历史租用绝不修改 desired/applied");
      assertEquals(commits, fixture.repository().commitCalls, "历史租用绝不写库");
      try (PluginCatalogRuntime.Lease current = runtime.acquireCurrent()) {
        assertEquals(2, current.snapshot().epoch(), "历史租用绝不倒退当前发布");
        assertFalse(hasContributor(current.snapshot(), PLUGIN_ALPHA + ".contrib"));
      }
      assertEquals(3, alpha.createdInstances.size(), "epoch 1 历史视图只构建一次");
      assertEquals(1, alpha.createdInstances.get(2).closeCount, "最后一个租用释放恰好关一次");
    }
  }

  /** 当前视图退役但仍有活动租用时必须按 epoch 保留：同 epoch 再取复用同一视图，不构建第二套实例。 */
  @Test
  void retiredButStillLeasedViewIsReusedForTheSameEpoch() {
    Fixture fixture = fixture(PLUGIN_ALPHA);
    TestPlugin alpha = fixture.plugin(PLUGIN_ALPHA);
    try (PluginCatalogRuntime runtime = fixture.runtime()) {
      try (PluginCatalogRuntime.Lease pinned = runtime.acquireCurrent()) {
        commitEpoch(fixture, Map.of(PLUGIN_ALPHA, DISABLED_VALUES));
        runtime.refresh();
        assertEquals(new PluginCatalogRuntime.RuntimeState(2, 2, null), runtime.state());
        assertEquals(2, alpha.createdInstances.size(), "epoch 2 只构建一次");

        try (PluginCatalogRuntime.Lease reused = runtime.acquire(1)) {
          assertSame(pinned.snapshot(), reused.snapshot(), "仍在租用中的退役视图必须被复用，不得为同一 epoch 构建第二套实例");
        }
        assertEquals(2, alpha.createdInstances.size(), "复用退役视图不得重新 configure");
        assertEquals(0, alpha.createdInstances.get(0).closeCount, "活动租用仍在时不得关闭");
      }

      assertEquals(1, alpha.createdInstances.get(0).closeCount, "最后一个租用释放恰好关一次");
    }
  }

  /** 再取同一 epoch 返回现有历史视图；最后一个租用释放即关闭并移除。 */
  @Test
  void acquireReusesAndThenClosesTheRetainedHistoryView() {
    Fixture fixture = fixture(PLUGIN_ALPHA);
    TestPlugin alpha = fixture.plugin(PLUGIN_ALPHA);
    commitEpoch(fixture, Map.of(PLUGIN_ALPHA, DISABLED_VALUES));
    try (PluginCatalogRuntime runtime = fixture.runtime()) {
      runtime.refresh();

      PluginCatalogRuntime.Lease first = runtime.acquire(1);
      try (PluginCatalogRuntime.Lease second = runtime.acquire(1)) {
        assertSame(first.snapshot(), second.snapshot(), "再取同 epoch 必须复用现有视图");
        assertEquals(3, alpha.createdInstances.size(), "复用不得重新 configure");
      }
      assertEquals(0, alpha.createdInstances.get(2).closeCount, "仍有活动租用时不得关闭");

      first.close();
      first.close();
      assertEquals(1, alpha.createdInstances.get(2).closeCount, "最后一个租用释放恰好关一次");

      // 视图已移除：再次租用该 epoch 必须重新严格构建。
      try (PluginCatalogRuntime.Lease rebuilt = runtime.acquire(1)) {
        assertEquals(1, rebuilt.snapshot().epoch());
        assertTrue(hasContributor(rebuilt.snapshot(), PLUGIN_ALPHA + ".contrib"));
      }
      assertEquals(4, alpha.createdInstances.size(), "已闭合的历史视图不得再被复用");
    }
    assertEquals(1, alpha.createdInstances.get(3).closeCount, "重建视图由最后租用回收");
  }

  /** 缺历史明确拒绝：不采用当前配置、不写库、不改变状态。 */
  @Test
  void acquireFailsWhenTheEpochHasNoPersistedHistory() {
    Fixture fixture = fixture(PLUGIN_ALPHA);
    TestPlugin alpha = fixture.plugin(PLUGIN_ALPHA);
    try (PluginCatalogRuntime runtime = fixture.runtime()) {
      IllegalStateException failure =
          assertThrows(IllegalStateException.class, () -> runtime.acquire(7));

      assertTrue(failure.getMessage().contains("epoch=7"), failure.getMessage());
      assertEquals(new PluginCatalogRuntime.RuntimeState(1, 1, null), runtime.state());
      assertEquals(1, alpha.createdInstances.size(), "缺历史不得 configure");
    }
  }

  /** 历史回读失败收敛为受控失败，不泄漏底层细节。 */
  @Test
  void acquireFailsControlledWhenTheHistoryReadIsUnavailable() {
    Fixture fixture = fixture(PLUGIN_ALPHA);
    TestPlugin alpha = fixture.plugin(PLUGIN_ALPHA);
    fixture.repository().readFailure = new IllegalStateException("the database is unavailable");
    try (PluginCatalogRuntime runtime = fixture.runtime()) {
      IllegalStateException failure =
          assertThrows(IllegalStateException.class, () -> runtime.acquire(7));

      assertTrue(failure.getMessage().contains("stage=history"), failure.getMessage());
      assertEquals(1, alpha.createdInstances.size());
    }
  }

  /** 持久历史与当前代码/身份漂移时明确拒绝，并独立回收已建候选。 */
  @Test
  void acquireRejectsDriftedHistoryAndReapsTheCandidate() {
    Fixture fixture = fixture(PLUGIN_ALPHA);
    TestPlugin alpha = fixture.plugin(PLUGIN_ALPHA);
    FakeRepository repository = fixture.repository();
    // epoch 3 的 manifest 条目与持久行一致，但全局目录指纹不同：目录构建完成后必须整体拒绝。
    PluginCatalogManifest original = fixture.initialRecord().manifest();
    repository.store(
        new PluginCatalogRecord(3, new PluginCatalogManifest(original.plugins(), "e".repeat(64))),
        List.of());

    try (PluginCatalogRuntime runtime = fixture.runtime()) {
      assertThrows(IllegalStateException.class, () -> runtime.acquire(3));

      assertEquals(
          new PluginCatalogRuntime.RuntimeState(1, 1, null), runtime.state(), "漂移拒绝不得改变状态");
      assertEquals(2, alpha.createdInstances.size(), "候选已建即必须整体回收");
      assertEquals(1, alpha.createdInstances.get(1).closeCount, "漂移候选恰好回收一次");
    }
  }

  /** 构建期关闭：候选独立回收，且抛出已关闭错误，不让租用访问已关闭实例。 */
  @Test
  void acquireReapsTheCandidateWhenClosedDuringTheBuild() throws Exception {
    Fixture fixture = fixture(PLUGIN_ALPHA);
    TestPlugin alpha = fixture.plugin(PLUGIN_ALPHA);
    commitEpoch(fixture, Map.of(PLUGIN_ALPHA, DISABLED_VALUES));
    CountDownLatch buildStarted = new CountDownLatch(1);
    CountDownLatch releaseBuild = new CountDownLatch(1);
    alpha.beforeConfigure =
        () -> {
          buildStarted.countDown();
          awaitLatch(releaseBuild);
        };

    PluginCatalogRuntime runtime = fixture.runtime();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<PluginCatalogRuntime.Lease> acquiring = executor.submit(() -> runtime.acquire(2));
      assertTrue(buildStarted.await(30, TimeUnit.SECONDS), "历史构建必须开始");

      runtime.close();
      releaseBuild.countDown();

      ExecutionException failure =
          assertThrows(ExecutionException.class, () -> acquiring.get(30, TimeUnit.SECONDS));
      assertInstanceOf(IllegalStateException.class, failure.getCause());
      assertEquals(2, alpha.createdInstances.size());
      assertEquals(1, alpha.createdInstances.get(1).closeCount, "关闭后的候选必须独立回收");
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  /** 并发 acquire 同一 epoch 被串行化：只构建一次，双方拿到同一历史视图。 */
  @Test
  void concurrentAcquiresOfTheSameEpochBuildOnceAndShareTheView() throws Exception {
    Fixture fixture = fixture(PLUGIN_ALPHA);
    TestPlugin alpha = fixture.plugin(PLUGIN_ALPHA);
    commitEpoch(fixture, Map.of(PLUGIN_ALPHA, DISABLED_VALUES));
    CountDownLatch buildStarted = new CountDownLatch(1);
    CountDownLatch releaseBuild = new CountDownLatch(1);
    alpha.beforeConfigure =
        () -> {
          buildStarted.countDown();
          awaitLatch(releaseBuild);
        };

    PluginCatalogRuntime runtime = fixture.runtime();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<PluginCatalogRuntime.Lease> first = executor.submit(() -> runtime.acquire(2));
      assertTrue(buildStarted.await(30, TimeUnit.SECONDS), "第一个 acquire 必须开始构建");

      // 第二个 acquire 在首个安装前进入：它阻塞在运行期锁上，安装后必须复用而不是重复构建。
      PluginCatalogRuntime.Lease[] secondLease = new PluginCatalogRuntime.Lease[1];
      Thread second = new Thread(() -> secondLease[0] = runtime.acquire(2));
      second.start();
      awaitBlockedOnRuntimeLock(second);
      releaseBuild.countDown();

      try (PluginCatalogRuntime.Lease firstLease = first.get(30, TimeUnit.SECONDS)) {
        second.join(TimeUnit.SECONDS.toMillis(30));
        assertFalse(second.isAlive(), "第二个 acquire 必须在窗口内完成");
        assertNotNull(secondLease[0], "第二个 acquire 必须取得租用");
        assertSame(firstLease.snapshot(), secondLease[0].snapshot(), "同 epoch 只能有一个视图");
      }
      secondLease[0].close();

      assertEquals(
          new PluginCatalogRuntime.RuntimeState(1, 1, null), runtime.state(), "acquire 绝不写坐标");
      assertEquals(2, alpha.createdInstances.size(), "同一 epoch 只允许构建一次候选");
      assertEquals(1, alpha.createdInstances.get(1).closeCount, "释放后历史视图恰好回收一次");
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  /** 对账发布已在租用中的历史 epoch 时提升该视图为当前视图，不再保留第二套活动实例。 */
  @Test
  void refreshPromotesAnAlreadyLoadedHistoryViewInsteadOfKeepingTwoLiveViews() {
    Fixture fixture = fixture(PLUGIN_ALPHA);
    TestPlugin alpha = fixture.plugin(PLUGIN_ALPHA);
    commitEpoch(fixture, Map.of(PLUGIN_ALPHA, DISABLED_VALUES));
    try (PluginCatalogRuntime runtime = fixture.runtime()) {
      PluginCatalogRuntime.Lease history = runtime.acquire(2);
      assertEquals(2, alpha.createdInstances.size(), "历史构建只新增一套实例");

      runtime.refresh();

      assertEquals(new PluginCatalogRuntime.RuntimeState(2, 2, null), runtime.state());
      // refresh 仍会构建候选（构建期不占生命周期锁），但发布时提升已加载的历史视图并回收重复候选。
      assertEquals(3, alpha.createdInstances.size());
      assertEquals(1, alpha.createdInstances.get(2).closeCount, "重复候选必须回收");
      assertEquals(0, alpha.createdInstances.get(1).closeCount, "被提升的历史视图保持活跃");
      assertEquals(
          DISABLED_VALUES, history.snapshot().configuredPlugins().get(0).config().valuesJson());
      try (PluginCatalogRuntime.Lease current = runtime.acquireCurrent()) {
        assertSame(history.snapshot(), current.snapshot(), "提升后的视图成为当前视图");
      }

      history.close();
      assertEquals(0, alpha.createdInstances.get(1).closeCount, "所有者引用仍在时租用不得关闭");
      runtime.close();
      assertEquals(1, alpha.createdInstances.get(1).closeCount, "close 释放所有者引用后恰好关一次");
    }
  }

  /** 关闭后 acquire 不再授予新租用。 */
  @Test
  void acquireRejectsAfterClose() {
    Fixture fixture = fixture(PLUGIN_ALPHA);
    PluginCatalogRuntime runtime = fixture.runtime();
    runtime.close();

    assertThrows(IllegalStateException.class, () -> runtime.acquire(1));
  }

  /** 提升历史视图后，重复候选回收的致命错误不能阻断前代回收，也不能被次要错误替代。 */
  @Test
  void promotedHistoryStillRetiresThePreviousViewWhenDuplicateCleanupFailsFatally() {
    Fixture fixture = fixture(PLUGIN_ALPHA);
    TestPlugin alpha = fixture.plugin(PLUGIN_ALPHA);
    commitEpoch(fixture, Map.of(PLUGIN_ALPHA, DISABLED_VALUES));
    Error primary = new AssertionError("duplicate candidate cleanup failed");
    alpha.createdInstances.get(0).closeError = new LinkageError("retired view cleanup failed");
    try (PluginCatalogRuntime runtime = fixture.runtime();
        PluginCatalogRuntime.Lease history = runtime.acquire(2)) {
      alpha.instanceCloseError = primary;

      assertSame(primary, assertThrows(Error.class, runtime::refresh));

      assertEquals(new PluginCatalogRuntime.RuntimeState(2, 2, null), runtime.state());
      assertEquals(1, alpha.createdInstances.get(0).closeCount, "重复候选回收失败后仍须关闭前代");
      assertEquals(1, alpha.createdInstances.get(2).closeCount, "重复候选只能关闭一次");
      try (PluginCatalogRuntime.Lease current = runtime.acquireCurrent()) {
        assertSame(history.snapshot(), current.snapshot(), "已提升视图保持发布");
      }
    }
  }

  /** 关闭后对账不发布任何内容。 */
  @Test
  void refreshAfterCloseNeverPublishes() {
    Fixture fixture = fixture(PLUGIN_ALPHA);
    TestPlugin alpha = fixture.plugin(PLUGIN_ALPHA);
    commitEpoch(fixture, Map.of(PLUGIN_ALPHA, DISABLED_VALUES));
    PluginCatalogRuntime runtime = fixture.runtime();
    runtime.close();

    runtime.refresh();

    assertEquals(1, alpha.createdInstances.size(), "已关闭的运行期不得 configure");
    assertEquals(new PluginCatalogRuntime.RuntimeState(1, 1, null), runtime.state());
  }

  /** 退役回收期的致命错误不被降级吞掉：新 epoch 已发布，前代只关一次，Error 原样上抛。 */
  @Test
  void retiringAFailedSnapshotPropagatesTheFatalErrorAfterPublishing() {
    Fixture fixture = fixture(PLUGIN_ALPHA);
    TestPlugin alpha = fixture.plugin(PLUGIN_ALPHA);
    commitEpoch(fixture, Map.of(PLUGIN_ALPHA, DISABLED_VALUES));
    Error fatal = new AssertionError("alpha instance close exploded");
    alpha.createdInstances.get(0).closeError = fatal;

    try (PluginCatalogRuntime runtime = fixture.runtime()) {
      Error thrown = assertThrows(Error.class, runtime::refresh);

      assertSame(fatal, thrown, "退役期致命错误不得被替代或吞掉");
      assertEquals(1, alpha.createdInstances.get(0).closeCount);
      assertEquals(new PluginCatalogRuntime.RuntimeState(2, 2, null), runtime.state());
      try (PluginCatalogRuntime.Lease lease = runtime.acquireCurrent()) {
        assertEquals(2, lease.snapshot().epoch(), "新 epoch 在退役前已经发布");
      }
    }
  }

  /** 候选构建期的致命错误原样传播，回收错误不得替代它，且候选实例仍被回收。 */
  @Test
  void candidateBuildFatalErrorIsPropagatedWithThePrimary() {
    Fixture fixture = fixture(PLUGIN_ALPHA, PLUGIN_BETA);
    TestPlugin alpha = fixture.plugin(PLUGIN_ALPHA);
    TestPlugin beta = fixture.plugin(PLUGIN_BETA);
    commitEpoch(fixture, Map.of(PLUGIN_ALPHA, DISABLED_VALUES, PLUGIN_BETA, DISABLED_VALUES));
    Error primary = new AssertionError("beta configure exploded");
    beta.configureError = primary;
    alpha.instanceCloseError = new LinkageError("alpha instance close exploded");

    try (PluginCatalogRuntime runtime = fixture.runtime()) {
      Error thrown = assertThrows(Error.class, runtime::refresh);

      assertSame(primary, thrown, "原始致命错误必须保留");
      assertEquals(2, alpha.createdInstances.size(), "beta 失败前 alpha 已经建好实例");
      assertEquals(1, alpha.createdInstances.get(1).closeCount, "候选仍然整体回收");
      assertEquals(0, alpha.createdInstances.get(0).closeCount, "旧视图不受影响");
      assertEquals(new PluginCatalogRuntime.RuntimeState(2, 1, null), runtime.state());
      try (PluginCatalogRuntime.Lease lease = runtime.acquireCurrent()) {
        assertEquals(1, lease.snapshot().epoch());
      }
    }
  }

  /** 发布前复核回读失败：候选独立回收，旧视图保留并暴露固定受控错误码。 */
  @Test
  void refreshReleasesTheCandidateWhenThePrePublishRecheckIsUnavailable() {
    Fixture fixture = fixture(PLUGIN_ALPHA);
    TestPlugin alpha = fixture.plugin(PLUGIN_ALPHA);
    commitEpoch(fixture, Map.of(PLUGIN_ALPHA, DISABLED_VALUES));
    FakeRepository repository = fixture.repository();
    AtomicInteger reads = new AtomicInteger();
    repository.readHook =
        () -> {
          if (reads.incrementAndGet() == 2) {
            throw new IllegalStateException("the database became unavailable");
          }
        };

    try (PluginCatalogRuntime runtime = fixture.runtime()) {
      runtime.refresh();

      assertEquals(
          new PluginCatalogRuntime.RuntimeState(
              2, 1, PluginCatalogRuntime.ERROR_AUTHORITY_UNAVAILABLE),
          runtime.state());
      assertEquals(2, alpha.createdInstances.size(), "epoch 2 候选只构建一次");
      assertEquals(1, alpha.createdInstances.get(1).closeCount, "复核失败必须回收候选");
      assertEquals(0, alpha.createdInstances.get(0).closeCount, "旧视图不受影响");
      try (PluginCatalogRuntime.Lease lease = runtime.acquireCurrent()) {
        assertEquals(1, lease.snapshot().epoch());
      }
    }
  }

  /** 发布前复核期致命错误：候选绝不泄漏，回收期的次要 Error 不得遮蔽原始 Error。 */
  @Test
  void refreshReleasesTheCandidateWhenThePrePublishRecheckFailsFatally() {
    Fixture fixture = fixture(PLUGIN_ALPHA);
    TestPlugin alpha = fixture.plugin(PLUGIN_ALPHA);
    commitEpoch(fixture, Map.of(PLUGIN_ALPHA, DISABLED_VALUES));
    FakeRepository repository = fixture.repository();
    Error fatal = new AssertionError("the recheck read exploded");
    alpha.instanceCloseError = new LinkageError("alpha instance close exploded");
    AtomicInteger reads = new AtomicInteger();
    repository.readHook =
        () -> {
          if (reads.incrementAndGet() == 2) {
            throw fatal;
          }
        };

    try (PluginCatalogRuntime runtime = fixture.runtime()) {
      Error thrown = assertThrows(Error.class, runtime::refresh);

      assertSame(fatal, thrown, "复核期原始致命错误必须原样传播");
      assertEquals(2, alpha.createdInstances.size(), "epoch 2 候选只构建一次");
      assertEquals(1, alpha.createdInstances.get(1).closeCount, "候选必须独立回收且只关一次");
      assertEquals(0, alpha.createdInstances.get(0).closeCount, "旧视图不受影响");
      assertEquals(
          new PluginCatalogRuntime.RuntimeState(
              2, 1, PluginCatalogRuntime.ERROR_AUTHORITY_UNAVAILABLE),
          runtime.state());
      try (PluginCatalogRuntime.Lease lease = runtime.acquireCurrent()) {
        assertEquals(1, lease.snapshot().epoch());
      }
    }
  }

  /** 相同/更旧权威回读只在确实已同步时清空旧错误，绝不擦掉已知更高 desired 上的应用失败。 */
  @Test
  void refreshKeepsTheRecordedFailureWhileTheAuthorityIsBehindTheDesiredEpoch() {
    Fixture fixture = fixture(PLUGIN_ALPHA);
    TestPlugin alpha = fixture.plugin(PLUGIN_ALPHA);
    commitEpoch(fixture, Map.of(PLUGIN_ALPHA, DISABLED_VALUES));
    alpha.applicationUnavailable = true;

    try (PluginCatalogRuntime runtime = fixture.runtime()) {
      runtime.refresh();
      assertEquals(
          new PluginCatalogRuntime.RuntimeState(2, 1, PluginCatalogRuntime.ERROR_APPLY_FAILED),
          runtime.state());

      // 复读停在更旧的 epoch：desired 已高于 applied，旧失败必须保留。
      fixture.repository().store(fixture.initialRecord(), List.of());
      runtime.refresh();

      assertEquals(
          new PluginCatalogRuntime.RuntimeState(2, 1, PluginCatalogRuntime.ERROR_APPLY_FAILED),
          runtime.state(),
          "未同步状态下的旧失败不得被擦掉");
      assertEquals(1, alpha.createdInstances.size(), "更旧权威不得 configure");
    }
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      assertTrue(latch.await(30, TimeUnit.SECONDS), "the build latch must be released");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(error);
    }
  }

  /** 等待给定线程阻塞在运行期串行锁上：确定性证明并发 acquire 已越过无锁快速路径。 */
  private static void awaitBlockedOnRuntimeLock(Thread thread) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      if (thread.getState() == Thread.State.BLOCKED) {
        return;
      }
      try {
        Thread.sleep(1);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(error);
      }
    }
    throw new AssertionError("the concurrent acquire must block on the runtime lock");
  }

  private static boolean hasContributor(PluginCatalogSnapshot snapshot, String contributorId) {
    return snapshot.harnessCatalog().findDescriptor(new ContributorId(contributorId)).isPresent();
  }

  private static Set<String> canvasFunctionNames(PluginCatalogSnapshot snapshot) {
    return snapshot.canvasFunctionCatalog().list().stream()
        .map(registered -> registered.function().name())
        .collect(Collectors.toSet());
  }

  private static BiFunction<String, PluginCredentialReference, PluginContext> contexts() {
    return (pluginId, reference) -> {
      assertEquals(pluginId, reference.pluginId(), "contexts must be keyed by the plugin id");
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
    };
  }

  private static PluginCatalogBootstrap bootstrap(
      FakeRepository repository, TestPlugin... plugins) {
    return bootstrap(repository, new StudioPluginRegistry(List.of(plugins)));
  }

  private static PluginCatalogBootstrap bootstrap(
      FakeRepository repository, StudioPluginRegistry registry) {
    return new PluginCatalogBootstrap(registry, repository, new InMemoryTransactionManager());
  }

  /** 启动期真实初始化到 epoch 1 的现场：仓储里已有 epoch 1 的权威 manifest 与历史。 */
  private static Fixture fixture(String... pluginIds) {
    List<TestPlugin> plugins = new ArrayList<>();
    for (String pluginId : pluginIds) {
      TestPlugin plugin = new TestPlugin(pluginId);
      plugin.schema = enabledSchema();
      plugins.add(plugin);
    }
    StudioPluginRegistry registry = new StudioPluginRegistry(new ArrayList<>(plugins));
    FakeRepository repository = new FakeRepository();
    PluginCatalogBootstrap bootstrap = bootstrap(repository, registry);
    PluginCatalogSnapshot initial = bootstrap.bootstrap(contexts(), List.of(), List.of());
    Map<String, TestPlugin> pluginsById = new LinkedHashMap<>();
    for (TestPlugin plugin : plugins) {
      pluginsById.put(plugin.descriptor().pluginId(), plugin);
    }
    return new Fixture(
        repository,
        repository.catalog,
        new PluginCatalogRuntime(bootstrap, repository, contexts(), List.of(), List.of(), initial),
        registry,
        pluginsById);
  }

  /**
   * 模拟权威写入方的一次完整提交：changed 插件落新 revision 与目标普通值，其余插件沿用已持久身份，随后原子落库完整 manifest。
   *
   * <p>身份用与启动初始化同源的派生（schema 指纹与上下文指纹），能力指纹取自同来源构建的候选，因此运行期对账必须能整体恢复或整体拒绝。
   * 写入方候选的实例不计入测试断言，只有运行期对账创建的实例被跟踪。
   */
  private static void commitEpoch(Fixture fixture, Map<String, String> changedValues) {
    FakeRepository repository = fixture.repository();
    long epoch = repository.catalog.epoch() + 1;
    Map<String, PluginCatalogManifest.Entry> persisted = repository.catalog.manifest().plugins();
    Map<String, PluginConfigurationState> configurations = new LinkedHashMap<>();
    Map<String, String> schemaFingerprints = new LinkedHashMap<>();
    for (StudioPlugin plugin : fixture.registry().plugins()) {
      String pluginId = plugin.descriptor().pluginId();
      PluginCatalogManifest.Entry entry = persisted.get(pluginId);
      String schemaFingerprint =
          PluginCatalogFingerprint.schema(
              plugin.configurationSchema(), plugin.authenticationSpec());
      schemaFingerprints.put(pluginId, schemaFingerprint);
      boolean changed = changedValues.containsKey(pluginId);
      String values =
          changed
              ? changedValues.get(pluginId)
              : repository.history.get(pluginId + "#" + entry.configRevision()).valuesJson();
      long revision = changed ? epoch : entry.configRevision();
      String contextFingerprint =
          PluginAuthContextFingerprint.derive(
              pluginId,
              plugin.descriptor().version(),
              schemaFingerprint,
              plugin.authenticationSpec(),
              JsonValues.readTree(plugin.configurationSchema().resolveValues(values)));
      configurations.put(
          pluginId,
          new PluginConfigurationState(
              new PluginConfig(revision, values),
              new PluginCredentialReference(pluginId, entry.authGeneration(), contextFingerprint)));
    }

    List<TestPlugin> plugins = fixture.plugins();
    List<Boolean> applicationUnavailable = new ArrayList<>();
    for (TestPlugin plugin : plugins) {
      // 写入方节点可以应用这份目标配置；运行期节点的应用能力由各用例自行注入。
      applicationUnavailable.add(plugin.applicationUnavailable);
      plugin.trackInstances = false;
      plugin.applicationUnavailable = false;
    }
    try {
      try (PluginCatalogSnapshot candidate =
          PluginCatalogSnapshot.build(
              epoch, fixture.registry(), configurations, contexts(), List.of(), List.of())) {
        Map<String, PluginCatalogManifest.Entry> entries = new LinkedHashMap<>();
        List<PluginConfigurationRow> changedRows = new ArrayList<>();
        for (StudioPlugin plugin : fixture.registry().plugins()) {
          String pluginId = plugin.descriptor().pluginId();
          PluginCatalogManifest.Entry entry = persisted.get(pluginId);
          PluginConfigurationState state = configurations.get(pluginId);
          boolean changed = state.config().revision() == epoch;
          String capabilityFingerprint =
              changed
                  ? candidate.capabilityFingerprints().get(pluginId)
                  : entry.capabilityFingerprint();
          entries.put(
              pluginId,
              new PluginCatalogManifest.Entry(
                  state.config().revision(),
                  plugin.descriptor().version(),
                  schemaFingerprints.get(pluginId),
                  capabilityFingerprint,
                  entry.authGeneration(),
                  state.credentialReference().contextFingerprint()));
          if (changed) {
            changedRows.add(
                new PluginConfigurationRow(
                    pluginId,
                    epoch,
                    plugin.descriptor().version(),
                    schemaFingerprints.get(pluginId),
                    capabilityFingerprint,
                    entry.authGeneration(),
                    state.credentialReference().contextFingerprint(),
                    List.of(),
                    state.config().valuesJson()));
          }
        }
        repository.store(
            new PluginCatalogRecord(
                epoch, new PluginCatalogManifest(entries, candidate.catalogFingerprint())),
            changedRows);
      }
    } finally {
      for (int index = 0; index < plugins.size(); index++) {
        plugins.get(index).trackInstances = true;
        plugins.get(index).applicationUnavailable = applicationUnavailable.get(index);
      }
    }
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

  /** 启动期真实现场：权威仓储、epoch 1 记录、运行期与按 id 索引的 Plugin 定义。 */
  private record Fixture(
      FakeRepository repository,
      PluginCatalogRecord initialRecord,
      PluginCatalogRuntime runtime,
      StudioPluginRegistry registry,
      Map<String, TestPlugin> pluginsById) {

    List<TestPlugin> plugins() {
      return List.copyOf(pluginsById.values());
    }

    TestPlugin plugin(String pluginId) {
      TestPlugin plugin = pluginsById.get(pluginId);
      if (plugin == null) {
        throw new IllegalStateException("plugin is not installed: " + pluginId);
      }
      return plugin;
    }
  }

  /** 内存权威仓储替身：模拟单行 CAS、append-only manifest/配置历史与可注入的读失败、读钩子。 */
  private static final class FakeRepository implements PluginConfigurationRepository {

    private PluginCatalogRecord catalog =
        new PluginCatalogRecord(
            PluginCatalogBootstrap.UNINITIALIZED_EPOCH, PluginCatalogManifest.uninitialized());
    private final Map<Long, PluginCatalogRecord> catalogRevisions =
        new LinkedHashMap<>(Map.of(0L, catalog));
    private final Map<String, PluginConfigurationRow> history = new LinkedHashMap<>();
    private RuntimeException readFailure;
    private Runnable readHook;
    private int commitCalls;

    @Override
    public PluginCatalogRecord readCatalog() {
      if (readHook != null) {
        readHook.run();
      }
      if (readFailure != null) {
        throw readFailure;
      }
      return catalog;
    }

    @Override
    public Optional<PluginCatalogRecord> readCatalog(long epoch) {
      if (readFailure != null) {
        throw readFailure;
      }
      return Optional.ofNullable(catalogRevisions.get(epoch));
    }

    @Override
    public Optional<PluginConfigurationRow> readConfiguration(String pluginId, long revision) {
      return Optional.ofNullable(history.get(pluginId + "#" + revision));
    }

    @Override
    public boolean commit(
        long expectedEpoch,
        List<PluginConfigurationRow> changedConfigurations,
        PluginCatalogManifest manifest) {
      commitCalls++;
      if (catalog.epoch() != expectedEpoch) {
        return false;
      }
      store(new PluginCatalogRecord(expectedEpoch + 1, manifest), changedConfigurations);
      return true;
    }

    /** 原子落库一位模拟写入方提交的完整 epoch：manifest 历史与新增配置历史同时可见。 */
    void store(PluginCatalogRecord next, List<PluginConfigurationRow> changedConfigurations) {
      for (PluginConfigurationRow row : changedConfigurations) {
        history.put(row.pluginId() + "#" + row.revision(), row);
      }
      catalog = next;
      catalogRevisions.put(next.epoch(), next);
    }
  }

  /** 无资源的最小真实事务管理器：仅让 TransactionTemplate 的边界生效。 */
  private static final class InMemoryTransactionManager extends AbstractPlatformTransactionManager {

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }

  /** 可配置失败模式的 Plugin 定义：停用时不贡献任何一域能力。 */
  private static final class TestPlugin implements StudioPlugin {

    private final PluginDescriptor descriptor;
    private final List<RecordingInstance> createdInstances = new ArrayList<>();
    private PluginConfigSchema schema = enabledSchema();

    /** 本节点缺少应用目标配置所需的运行期条件（例如依赖不可用）；写入方节点不设该条件。 */
    private boolean applicationUnavailable;

    private Error configureError;
    private Error instanceCloseError;
    private Runnable beforeConfigure;
    private boolean trackInstances = true;

    TestPlugin(String pluginId) {
      this.descriptor = new PluginDescriptor(pluginId, "Plugin " + pluginId, "1.0");
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
      if (beforeConfigure != null) {
        beforeConfigure.run();
      }
      if (configureError != null) {
        throw configureError;
      }
      JsonNode values = JsonValues.readTree(config.valuesJson());
      // 本节点缺少应用目标配置所需的运行期条件时，该 epoch 在本节点整体不可应用。
      if (applicationUnavailable) {
        throw new IllegalStateException("this node cannot apply the target configuration");
      }
      boolean enabled = values.path("enabled").asBoolean(true);
      String pluginId = descriptor.pluginId();
      RecordingInstance instance =
          new RecordingInstance(
              enabled ? List.of(contributor(pluginId + ".contrib")) : List.of(),
              enabled ? List.of(adapter(pluginId + ".run")) : List.of(),
              instanceCloseError);
      if (trackInstances) {
        createdInstances.add(instance);
      }
      return instance;
    }
  }

  /** 记录 close 次数并可注入致命 close 错误的实例替身。 */
  private static final class RecordingInstance implements PluginInstance {

    private final List<HarnessContributor> contributors;
    private final List<CanvasFunctionAdapter> adapters;
    private Error closeError;
    private int closeCount;

    RecordingInstance(
        List<HarnessContributor> contributors,
        List<CanvasFunctionAdapter> adapters,
        Error closeError) {
      this.contributors = contributors;
      this.adapters = adapters;
      this.closeError = closeError;
    }

    @Override
    public List<HarnessContributor> harnessContributors() {
      return contributors;
    }

    @Override
    public List<CanvasFunctionAdapter> canvasAdapters() {
      return adapters;
    }

    @Override
    public void close() {
      closeCount++;
      if (closeError != null) {
        throw closeError;
      }
    }
  }
}
