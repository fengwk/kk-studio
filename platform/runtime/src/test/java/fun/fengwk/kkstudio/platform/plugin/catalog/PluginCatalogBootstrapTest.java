package fun.fengwk.kkstudio.platform.plugin.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.harness.common.schema.BooleanSchema;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
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
import java.util.function.BiFunction;

/**
 * {@link PluginCatalogBootstrap} 的初始化与恢复契约测试（内存仓储替身）。
 *
 * <p>覆盖权威行缺失、epoch 0 首次初始化写入默认值与历史、初始化失败释放候选实例、CAS 败方回读赢家恢复、无赢家的初始化竞争，以及恢复期安装集合、 历史缺失与
 * code/schema/capability/catalog 漂移的明确失败。
 */
class PluginCatalogBootstrapTest {

  private static final String PLUGIN_ALPHA = "alpha.plugin";
  private static final String PLUGIN_BETA = "beta.plugin";
  private static final String DEFAULT_SCHEMA_JSON = "{\"enabled\":true}";
  private static final String CONTEXT =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final UUID GENERATION = UUID.fromString("11111111-1111-1111-1111-111111111111");

  private static PluginConfigSchema enabledSchema() {
    return new PluginConfigSchema(
        new InputSchema(null, Map.of("enabled", new BooleanSchema(null)), Set.of("enabled"), false),
        DEFAULT_SCHEMA_JSON,
        Map.of());
  }

  @Test
  void failsWhenCatalogRowIsMissing() {
    FakeRepository repository = new FakeRepository();
    repository.catalog = null;

    TestPlugin plugin = new TestPlugin(PLUGIN_ALPHA);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> bootstrap(repository, plugin).bootstrap(contexts(), List.of(), List.of()));

    assertTrue(failure.getMessage().contains("stage=catalog-missing"), failure.getMessage());
    assertTrue(plugin.createdInstances.isEmpty(), "no instance may be created without an epoch");
  }

  /** 公开 restore 复用同一条严格恢复路径：只接受已初始化 record，绝不写库、也绝不偷偷初始化。 */
  @Test
  void publicRestoreReusesTheStrictPathAndRejectsUninitializedRecords() {
    TestPlugin plugin = new TestPlugin(PLUGIN_ALPHA);
    plugin.schema = enabledSchema();
    FakeRepository repository = new FakeRepository();
    PluginCatalogBootstrap bootstrap = bootstrap(repository, plugin);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> bootstrap.restore(epochZero(), contexts(), List.of(), List.of()));

    assertTrue(failure.getMessage().contains("stage=restore-uninitialized"), failure.getMessage());
    assertTrue(plugin.createdInstances.isEmpty(), "未初始化 record 不得 configure");
    assertEquals(0, repository.commitCalls, "restore 绝不写库");

    // 真实现场：公开 restore 与 bootstrap 恢复路径给出同一 epoch、同一指纹与同一 owner 能力。
    try (PluginCatalogSnapshot initialized =
        bootstrap.bootstrap(contexts(), List.of(), List.of())) {
      int commits = repository.commitCalls;
      try (PluginCatalogSnapshot restored =
          bootstrap.restore(repository.catalog, contexts(), List.of(), List.of())) {
        assertEquals(initialized.epoch(), restored.epoch());
        assertEquals(initialized.catalogFingerprint(), restored.catalogFingerprint());
        assertEquals(initialized.capabilityFingerprints(), restored.capabilityFingerprints());
      }
      assertEquals(commits, repository.commitCalls, "restore 绝不写库");
    }
  }

  @Test
  void initializesFromDefaultsAndPersistsTheFirstEpoch() {
    TestPlugin plugin = new TestPlugin(PLUGIN_ALPHA);
    plugin.schema = enabledSchema();
    FakeRepository repository = new FakeRepository();

    try (PluginCatalogSnapshot snapshot =
        bootstrap(repository, plugin).bootstrap(contexts(), List.of(), List.of())) {
      assertEquals(PluginCatalogBootstrap.INITIAL_EPOCH, snapshot.epoch());
      assertEquals(1, repository.commitCalls);
      assertEquals(PluginCatalogBootstrap.UNINITIALIZED_EPOCH, repository.committedAgainstEpoch);
      assertEquals(
          new PluginConfig(PluginCatalogBootstrap.INITIAL_EPOCH, DEFAULT_SCHEMA_JSON),
          plugin.receivedConfig);
      assertEquals(
          snapshot.catalogFingerprint(),
          repository.lastManifest.catalogFingerprint(),
          "persisted manifest must bind the frozen catalog fingerprint");
      assertEquals(
          snapshot.capabilityFingerprints().get(PLUGIN_ALPHA),
          repository.lastManifest.plugins().get(PLUGIN_ALPHA).capabilityFingerprint());
      assertEquals(PluginCatalogBootstrap.INITIAL_EPOCH, repository.catalog.epoch());
      assertEquals(1, repository.catalog.manifest().plugins().size());
      assertEquals(1, repository.history.size());
      PluginConfigurationRow row =
          repository.history.get(PLUGIN_ALPHA + "#" + PluginCatalogBootstrap.INITIAL_EPOCH);
      assertNotNull(row, "the changed configuration history must be written");
      assertEquals(plugin.descriptor().version(), row.codeVersion());
      assertEquals(DEFAULT_SCHEMA_JSON, row.valuesJson());
      assertEquals(1, repository.lastChanged.size());
    }
  }

  @Test
  void initializationFailureReleasesTheCandidateInstances() {
    TestPlugin plugin = new TestPlugin(PLUGIN_ALPHA);
    FakeRepository repository = new FakeRepository();
    repository.commitFailure = new IllegalStateException("commit exploded");

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> bootstrap(repository, plugin).bootstrap(contexts(), List.of(), List.of()));

    assertTrue(failure.getMessage().contains("stage=initialize-commit"), failure.getMessage());
    assertEquals(1, plugin.createdInstances.size());
    assertEquals(1, plugin.createdInstances.get(0).closeCount, "candidate must be reaped");
    assertTrue(repository.history.isEmpty(), "no history may be written on commit failure");
  }

  /** CAS 败方关闭自己的候选，一次回读赢家并按 manifest 恢复，不重复初始化。 */
  @Test
  void initializationLosingCasRestoresTheWinner() {
    TestPlugin first = new TestPlugin(PLUGIN_ALPHA);
    first.schema = enabledSchema();
    FakeRepository repository = new FakeRepository();
    PluginCatalogSnapshot winnerSnapshot =
        bootstrap(repository, first).bootstrap(contexts(), List.of(), List.of());
    winnerSnapshot.close();
    PluginCatalogRecord winner = repository.catalog;
    Map<String, PluginConfigurationRow> winnerHistory = new LinkedHashMap<>(repository.history);

    // 回退到未初始化，并让下一次 CAS 直接失败、只暴露赢家状态。
    Map<String, PluginConfigurationRow> restoredHistory = new LinkedHashMap<>(winnerHistory);
    repository.catalog = epochZero();
    repository.history.clear();
    repository.history.putAll(restoredHistory);
    repository.casFails = true;
    repository.casWinner = winner;

    TestPlugin second = new TestPlugin(PLUGIN_ALPHA);
    second.schema = enabledSchema();
    int readsBefore = repository.readCatalogCalls;
    try (PluginCatalogSnapshot snapshot =
        bootstrap(repository, second).bootstrap(contexts(), List.of(), List.of())) {
      assertEquals(winner.epoch(), snapshot.epoch());
      assertEquals(winner.manifest().catalogFingerprint(), snapshot.catalogFingerprint());
      assertEquals(2, second.createdInstances.size(), "candidate plus winner instance");
      assertEquals(
          1, second.createdInstances.get(0).closeCount, "the losing candidate must be reaped");
      assertEquals(0, second.createdInstances.get(1).closeCount, "the winner instance stays live");
      assertSame(second.createdInstances.get(1), snapshot.configuredPlugins().get(0).instance());
      assertEquals(
          readsBefore + 2,
          repository.readCatalogCalls,
          "one initial read plus one winner re-read, no retry loop");
    }
  }

  /** CAS 失败且回读仍为未初始化：绝不循环重试，候选实例全部回收。 */
  @Test
  void initializationRaceWithoutCommittedWinnerFails() {
    TestPlugin plugin = new TestPlugin(PLUGIN_ALPHA);
    FakeRepository repository = new FakeRepository();
    repository.casFails = true;

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> bootstrap(repository, plugin).bootstrap(contexts(), List.of(), List.of()));

    assertTrue(failure.getMessage().contains("stage=initialize-race"), failure.getMessage());
    assertEquals(1, plugin.createdInstances.get(0).closeCount);
    assertEquals(2, repository.readCatalogCalls);
  }

  /** 重启恢复必须使用持久化的普通值（而非当前默认值），并沿用持久 epoch。 */
  @Test
  void restoreUsesPersistedValuesNotDefaults() {
    Fixture fixture = initialize(PLUGIN_ALPHA);
    int commitsBefore = fixture.repository.commitCalls;
    fixture.repository.history.put(
        PLUGIN_ALPHA + "#" + PluginCatalogBootstrap.INITIAL_EPOCH,
        new PluginConfigurationRow(
            PLUGIN_ALPHA,
            PluginCatalogBootstrap.INITIAL_EPOCH,
            "1.0",
            fixture.schemaFingerprint(PLUGIN_ALPHA),
            fixture.capabilityFingerprint(PLUGIN_ALPHA),
            fixture.entry(PLUGIN_ALPHA).authGeneration(),
            fixture.entry(PLUGIN_ALPHA).contextFingerprint(),
            List.of(),
            "{\"enabled\":false}"));

    TestPlugin restarted = new TestPlugin(PLUGIN_ALPHA);
    restarted.schema = enabledSchema();
    try (PluginCatalogSnapshot snapshot =
        bootstrap(fixture.repository, restarted).bootstrap(contexts(), List.of(), List.of())) {
      assertEquals(PluginCatalogBootstrap.INITIAL_EPOCH, snapshot.epoch());
      assertEquals(
          new PluginConfig(PluginCatalogBootstrap.INITIAL_EPOCH, "{\"enabled\":false}"),
          restarted.receivedConfig);
      assertEquals(commitsBefore, fixture.repository.commitCalls, "restore must not write again");
    }
    fixture.close();
  }

  @Test
  void restoreRejectsInstallSetMismatch() {
    Fixture fixture = initialize(PLUGIN_ALPHA, PLUGIN_BETA);
    fixture.repository.catalog =
        new PluginCatalogRecord(
            PluginCatalogBootstrap.INITIAL_EPOCH,
            new PluginCatalogManifest(
                Map.of(PLUGIN_ALPHA, fixture.entry(PLUGIN_ALPHA)),
                fixture.repository.catalog.manifest().catalogFingerprint()));

    assertRestoreStage(fixture, "stage=restore-install-set");
  }

  @Test
  void restoreRejectsMissingHistory() {
    Fixture fixture = initialize(PLUGIN_ALPHA);
    fixture.repository.history.clear();

    assertRestoreStage(fixture, "stage=restore-missing-history");
  }

  /** 持久 revision 记载的 code version 与当前 JAR 不符（history 与 manifest 一致）必须拒绝。 */
  @Test
  void restoreRejectsCodeVersionDrift() {
    Fixture fixture = initialize(PLUGIN_ALPHA);
    fixture.rewritePersistedIdentity(
        PLUGIN_ALPHA,
        "9.9",
        fixture.schemaFingerprint(PLUGIN_ALPHA),
        fixture.capabilityFingerprint(PLUGIN_ALPHA));

    assertRestoreStage(fixture, "stage=restore-code-version");
  }

  /** 持久 revision 记载的 schema 指纹与当前 JAR 不符（history 与 manifest 一致）必须拒绝。 */
  @Test
  void restoreRejectsSchemaFingerprintDrift() {
    Fixture fixture = initialize(PLUGIN_ALPHA);
    fixture.rewritePersistedIdentity(
        PLUGIN_ALPHA, "1.0", "f".repeat(64), fixture.capabilityFingerprint(PLUGIN_ALPHA));

    assertRestoreStage(fixture, "stage=restore-schema-fingerprint");
  }

  @Test
  void restoreRejectsCatalogFingerprintDrift() {
    Fixture fixture = initialize(PLUGIN_ALPHA);
    PluginCatalogManifest drifted =
        new PluginCatalogManifest(fixture.repository.catalog.manifest().plugins(), "e".repeat(64));
    fixture.repository.catalog =
        new PluginCatalogRecord(PluginCatalogBootstrap.INITIAL_EPOCH, drifted);

    assertRestoreStage(fixture, "stage=restore-catalog-fingerprint");
  }

  /** 持久 revision 记载的 capability 指纹与当前 JAR 冻结贡献不符（history 与 manifest 一致）必须拒绝。 */
  @Test
  void restoreRejectsCapabilityFingerprintDrift() {
    Fixture fixture = initialize(PLUGIN_ALPHA);
    PluginCatalogManifest.Entry original = fixture.entry(PLUGIN_ALPHA);
    fixture.rewritePersistedIdentity(
        PLUGIN_ALPHA, original.pluginCodeVersion(), original.schemaFingerprint(), "d".repeat(64));

    assertRestoreStage(fixture, "stage=restore-capability-fingerprint");
  }

  /** manifest entry 的 fingerprint 与 history row 不符（row 仍等于当前 JAR）也必须拒绝。 */
  @Test
  void restoreRejectsManifestEntrySchemaDriftAgainstHistory() {
    Fixture fixture = initialize(PLUGIN_ALPHA);
    PluginCatalogManifest.Entry original = fixture.entry(PLUGIN_ALPHA);
    fixture.repository.catalog =
        new PluginCatalogRecord(
            PluginCatalogBootstrap.INITIAL_EPOCH,
            new PluginCatalogManifest(
                Map.of(
                    PLUGIN_ALPHA,
                    new PluginCatalogManifest.Entry(
                        original.configRevision(),
                        original.pluginCodeVersion(),
                        "f".repeat(64),
                        original.capabilityFingerprint(),
                        original.authGeneration(),
                        original.contextFingerprint())),
                fixture.repository.catalog.manifest().catalogFingerprint()));

    assertRestoreStage(fixture, "stage=restore-history-identity");
  }

  /** manifest entry 的 code version 与 history row 不符也必须拒绝。 */
  @Test
  void restoreRejectsManifestEntryCodeDriftAgainstHistory() {
    Fixture fixture = initialize(PLUGIN_ALPHA);
    PluginCatalogManifest.Entry original = fixture.entry(PLUGIN_ALPHA);
    fixture.repository.catalog =
        new PluginCatalogRecord(
            PluginCatalogBootstrap.INITIAL_EPOCH,
            new PluginCatalogManifest(
                Map.of(
                    PLUGIN_ALPHA,
                    new PluginCatalogManifest.Entry(
                        original.configRevision(),
                        "9.9",
                        original.schemaFingerprint(),
                        original.capabilityFingerprint(),
                        original.authGeneration(),
                        original.contextFingerprint())),
                fixture.repository.catalog.manifest().catalogFingerprint()));

    assertRestoreStage(fixture, "stage=restore-history-identity");
  }

  /** 恢复期读取失败收敛为只带 stage 与类名的受控诊断。 */
  @Test
  void restoreReadFailureConverges() {
    Fixture fixture = initialize(PLUGIN_ALPHA);
    fixture.repository.readFailure = new IllegalStateException("db down");

    TestPlugin restarted = new TestPlugin(PLUGIN_ALPHA);
    restarted.schema = enabledSchema();
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                bootstrap(fixture.repository, restarted)
                    .bootstrap(contexts(), List.of(), List.of()));

    assertTrue(failure.getMessage().contains("stage=restore-read"), failure.getMessage());
    assertTrue(failure.getMessage().contains("cause=IllegalStateException"), failure.getMessage());
    assertNull(failure.getCause());
    assertEquals(0, restarted.createdInstances.size(), "no instance may be built before the read");
    fixture.close();
  }

  /** 候选建成后的提交阶段抛 Error：候选仍被独立释放，原始 Error 原样传播。 */
  @Test
  void initializationCommitErrorReleasesCandidateAndPropagates() {
    TestPlugin plugin = new TestPlugin(PLUGIN_ALPHA);
    FakeRepository repository = new FakeRepository();
    Error fatal = new AssertionError("commit fatal");
    repository.commitError = fatal;

    Error propagated =
        assertThrows(
            Error.class,
            () -> bootstrap(repository, plugin).bootstrap(contexts(), List.of(), List.of()));

    assertSame(fatal, propagated, "original Error must be propagated unchanged");
    assertEquals(1, plugin.createdInstances.get(0).closeCount, "candidate must be released");
  }

  /** 原始 Error 与回收期 Error 同时出现：原样保留 primary Error，实例仍被释放。 */
  @Test
  void primaryErrorIsNotReplacedByCleanupError() {
    TestPlugin plugin = new TestPlugin(PLUGIN_ALPHA);
    plugin.closeError = new StackOverflowError("cleanup fatal");
    FakeRepository repository = new FakeRepository();
    Error primary = new AssertionError("commit fatal");
    repository.commitError = primary;

    Error propagated =
        assertThrows(
            Error.class,
            () -> bootstrap(repository, plugin).bootstrap(contexts(), List.of(), List.of()));

    assertSame(primary, propagated, "cleanup Error must not replace the primary Error");
    assertEquals(1, plugin.createdInstances.get(0).closeCount);
  }

  /** 原始失败为 RuntimeException 时，回收期致命 Error 绝不被降级为受控诊断。 */
  @Test
  void cleanupErrorIsNotDowngradedForRuntimePrimaryFailure() {
    TestPlugin plugin = new TestPlugin(PLUGIN_ALPHA);
    Error cleanup = new StackOverflowError("cleanup fatal");
    plugin.closeError = cleanup;
    FakeRepository repository = new FakeRepository();
    repository.commitFailure = new IllegalStateException("commit failed");

    Error propagated =
        assertThrows(
            Error.class,
            () -> bootstrap(repository, plugin).bootstrap(contexts(), List.of(), List.of()));

    assertSame(cleanup, propagated);
    assertEquals(1, plugin.createdInstances.get(0).closeCount);
  }

  private static void assertRestoreStage(Fixture fixture, String expectedStage) {
    TestPlugin[] restartedPlugins = new TestPlugin[fixture.pluginIds.size()];
    for (int index = 0; index < fixture.pluginIds.size(); index++) {
      TestPlugin plugin = new TestPlugin(fixture.pluginIds.get(index));
      plugin.schema = enabledSchema();
      restartedPlugins[index] = plugin;
    }

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                bootstrap(fixture.repository, restartedPlugins)
                    .bootstrap(contexts(), List.of(), List.of()));

    assertTrue(failure.getMessage().contains(expectedStage), failure.getMessage());
    for (TestPlugin plugin : restartedPlugins) {
      for (RecordingInstance instance : plugin.createdInstances) {
        assertEquals(1, instance.closeCount, "failed restore must not leak instances");
      }
    }
    fixture.close();
  }

  private static Fixture initialize(String... pluginIds) {
    List<TestPlugin> plugins = new ArrayList<>();
    for (String pluginId : pluginIds) {
      TestPlugin plugin = new TestPlugin(pluginId);
      plugin.schema = enabledSchema();
      plugins.add(plugin);
    }
    StudioPluginRegistry registry = new StudioPluginRegistry(new ArrayList<>(plugins));
    FakeRepository repository = new FakeRepository();
    PluginCatalogSnapshot snapshot =
        bootstrap(repository, registry).bootstrap(contexts(), List.of(), List.of());
    return new Fixture(repository, registry, List.of(pluginIds), snapshot);
  }

  private static PluginCatalogBootstrap bootstrap(
      FakeRepository repository, TestPlugin... plugins) {
    return bootstrap(repository, new StudioPluginRegistry(new ArrayList<>(List.of(plugins))));
  }

  private static PluginCatalogBootstrap bootstrap(
      FakeRepository repository, StudioPluginRegistry registry) {
    return new PluginCatalogBootstrap(registry, repository, new InMemoryTransactionManager());
  }

  private static BiFunction<String, PluginCredentialReference, PluginContext> contexts() {
    return (pluginId, reference) ->
        new PluginContext(
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
            Optional.empty());
  }

  private static PluginCatalogRecord epochZero() {
    return new PluginCatalogRecord(
        PluginCatalogBootstrap.UNINITIALIZED_EPOCH, PluginCatalogManifest.uninitialized());
  }

  /** 一次真实初始化后的现场，供恢复期漂移用例改写。 */
  private static final class Fixture {

    private final FakeRepository repository;
    private final StudioPluginRegistry registry;
    private final List<String> pluginIds;
    private final PluginCatalogSnapshot snapshot;

    Fixture(
        FakeRepository repository,
        StudioPluginRegistry registry,
        List<String> pluginIds,
        PluginCatalogSnapshot snapshot) {
      this.repository = repository;
      this.registry = registry;
      this.pluginIds = pluginIds;
      this.snapshot = snapshot;
    }

    PluginCatalogManifest.Entry entry(String pluginId) {
      return repository.catalog.manifest().plugins().get(pluginId);
    }

    String schemaFingerprint(String pluginId) {
      return entry(pluginId).schemaFingerprint();
    }

    String capabilityFingerprint(String pluginId) {
      return entry(pluginId).capabilityFingerprint();
    }

    /** 同步改写 history row 与 manifest entry：模拟由旧 JAR 撰写、当前 JAR 已漂移的持久 revision。 */
    void rewritePersistedIdentity(
        String pluginId,
        String codeVersion,
        String schemaFingerprint,
        String capabilityFingerprint) {
      PluginCatalogManifest.Entry identity = entry(pluginId);
      PluginConfigurationRow previous =
          repository.history.get(pluginId + "#" + identity.configRevision());
      repository.history.put(
          pluginId + "#" + identity.configRevision(),
          new PluginConfigurationRow(
              pluginId,
              identity.configRevision(),
              codeVersion,
              schemaFingerprint,
              capabilityFingerprint,
              identity.authGeneration(),
              identity.contextFingerprint(),
              previous.configuredSecretFields(),
              previous.valuesJson()));
      repository.catalog =
          new PluginCatalogRecord(
              repository.catalog.epoch(),
              new PluginCatalogManifest(
                  Map.of(
                      pluginId,
                      new PluginCatalogManifest.Entry(
                          identity.configRevision(),
                          codeVersion,
                          schemaFingerprint,
                          capabilityFingerprint,
                          identity.authGeneration(),
                          identity.contextFingerprint())),
                  repository.catalog.manifest().catalogFingerprint()));
    }

    void close() {
      snapshot.close();
    }
  }

  /** 内存权威仓储替身：模拟单行 CAS、append-only manifest/配置历史与可注入的读/写失败。 */
  private static final class FakeRepository implements PluginConfigurationRepository {

    private PluginCatalogRecord catalog = epochZero();
    private final Map<Long, PluginCatalogRecord> catalogRevisions =
        new LinkedHashMap<>(Map.of(0L, catalog));
    private final Map<String, PluginConfigurationRow> history = new LinkedHashMap<>();
    private RuntimeException commitFailure;
    private Error commitError;
    private RuntimeException readFailure;
    private boolean casFails;
    private PluginCatalogRecord casWinner;
    private int commitCalls;
    private int readCatalogCalls;
    private long committedAgainstEpoch = -1L;
    private PluginCatalogManifest lastManifest;
    private List<PluginConfigurationRow> lastChanged = List.of();

    @Override
    public PluginCatalogRecord readCatalog() {
      readCatalogCalls++;
      return catalog;
    }

    @Override
    public Optional<PluginCatalogRecord> readCatalog(long epoch) {
      return Optional.ofNullable(catalogRevisions.get(epoch));
    }

    @Override
    public Optional<PluginConfigurationRow> readConfiguration(String pluginId, long revision) {
      if (readFailure != null) {
        throw readFailure;
      }
      return Optional.ofNullable(history.get(pluginId + "#" + revision));
    }

    @Override
    public boolean commit(
        long expectedEpoch,
        List<PluginConfigurationRow> changedConfigurations,
        PluginCatalogManifest manifest) {
      commitCalls++;
      committedAgainstEpoch = expectedEpoch;
      lastChanged = List.copyOf(changedConfigurations);
      lastManifest = manifest;
      if (commitError != null) {
        throw commitError;
      }
      if (commitFailure != null) {
        throw commitFailure;
      }
      if (casFails) {
        if (casWinner != null) {
          catalog = casWinner;
        }
        return false;
      }
      if (catalog.epoch() != expectedEpoch) {
        return false;
      }
      for (PluginConfigurationRow row : changedConfigurations) {
        history.put(row.pluginId() + "#" + row.revision(), row);
      }
      catalog = new PluginCatalogRecord(expectedEpoch + 1, manifest);
      catalogRevisions.put(catalog.epoch(), catalog);
      return true;
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

  /** 可配置定义与实例行为的 Plugin 定义。 */
  private static final class TestPlugin implements StudioPlugin {

    private final PluginDescriptor descriptor;
    private final List<RecordingInstance> createdInstances = new ArrayList<>();
    private PluginConfigSchema schema = TestPluginFixtures.EMPTY_SCHEMA;
    private PluginConfig receivedConfig;
    private Error closeError;

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
      receivedConfig = config;
      RecordingInstance instance = new RecordingInstance(closeError);
      createdInstances.add(instance);
      return instance;
    }
  }

  private static final class RecordingInstance implements PluginInstance {

    private final Error closeError;
    private int closeCount;

    RecordingInstance(Error closeError) {
      this.closeError = closeError;
    }

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
      closeCount++;
      if (closeError != null) {
        throw closeError;
      }
    }
  }
}
