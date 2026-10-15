package fun.fengwk.kkstudio.platform.catalog.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.ContributionId;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.StateDeclaration;
import fun.fengwk.kkstudio.harness.contributor.api.StateMode;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorStateAccess;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorStateAccessMode;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolPluginSource;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.catalog.mcp.runtime.McpToolCatalog;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigurationState;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRuntime;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialReference;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link PluginRuntimeToolCatalogSource} 的生产实现自动化测试。
 *
 * <p>测试覆盖：
 *
 * <ul>
 *   <li>current / history acquire，包括严格的 epoch 绑定且无假 epoch fallback；
 *   <li>严格 require 各 mismatch reason（NOT_FOUND, DEFINITION, CONTRIBUTOR, REQUIREMENTS, SOURCE）；
 *   <li>pluginSource 与 ownership 事实解析（包括内置/MCP 为 null、指纹缺失与所有权冲突拒绝）；
 *   <li>冻结 MCP 单次读取（无 live fallback，同名冲突 fail-closed）；
 *   <li>冻结失败关闭已取 runtime lease 且 cleanup suppressed；
 *   <li>close 幂等及关闭后操作拒绝。
 * </ul>
 */
class PluginRuntimeToolCatalogSourceTest {

  private static final long CURRENT_EPOCH = 10L;
  private static final long HISTORY_EPOCH = 5L;

  private static final String PLUGIN_ID = "plugin.demo";
  private static final String PLUGIN_VERSION = "1.0.0";
  private static final long CONFIG_REVISION = 2L;
  private static final String CAPABILITY_FINGERPRINT =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final String CONTEXT_FINGERPRINT =
      "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
  private static final UUID AUTH_GENERATION =
      UUID.fromString("11111111-1111-1111-1111-111111111111");

  private static final ContributorId PLUGIN_CONTRIBUTOR_ID =
      new ContributorId("plugin.contributor.demo");
  private static final ContributorId BUILTIN_CONTRIBUTOR_ID = new ContributorId("builtin.system");

  private static final UUID ENV_UUID_1 = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID ENV_UUID_2 = UUID.fromString("33333333-3333-3333-3333-333333333333");

  private PluginCatalogRuntime runtime;
  private McpToolCatalog mcpCatalog;
  private McpToolCatalog.Snapshot mcpSnapshot;

  private PluginCatalogRuntime.Lease currentRuntimeLease;
  private PluginCatalogSnapshot currentSnapshot;
  private HarnessCatalog currentHarnessCatalog;

  private ToolContribution pluginTool;
  private ToolContribution builtinTool;
  private ToolPluginSource expectedPluginSource;

  @BeforeEach
  void setUp() {
    runtime = mock(PluginCatalogRuntime.class);
    mcpCatalog = mock(McpToolCatalog.class);
    mcpSnapshot = mock(McpToolCatalog.Snapshot.class);
    when(mcpCatalog.readSnapshot()).thenReturn(mcpSnapshot);
    when(mcpSnapshot.plannableTools()).thenReturn(List.of());
    when(mcpSnapshot.selectableTools()).thenReturn(List.of());

    currentRuntimeLease = mock(PluginCatalogRuntime.Lease.class);
    currentSnapshot = mock(PluginCatalogSnapshot.class);
    currentHarnessCatalog = mock(HarnessCatalog.class);

    when(currentRuntimeLease.snapshot()).thenReturn(currentSnapshot);
    when(currentSnapshot.epoch()).thenReturn(CURRENT_EPOCH);
    when(currentSnapshot.harnessCatalog()).thenReturn(currentHarnessCatalog);

    expectedPluginSource =
        new ToolPluginSource(
            PLUGIN_ID,
            PLUGIN_VERSION,
            CONFIG_REVISION,
            CAPABILITY_FINGERPRINT,
            CONTEXT_FINGERPRINT,
            AUTH_GENERATION);

    PluginCatalogSnapshot.ConfiguredPlugin configuredPlugin =
        new PluginCatalogSnapshot.ConfiguredPlugin(
            new PluginDescriptor(PLUGIN_ID, "Demo Plugin", PLUGIN_VERSION),
            new PluginConfigurationState(
                new PluginConfig(CONFIG_REVISION, "{}"),
                new PluginCredentialReference(PLUGIN_ID, AUTH_GENERATION, CONTEXT_FINGERPRINT)),
            mock(PluginInstance.class));

    when(currentSnapshot.configuredPlugins()).thenReturn(List.of(configuredPlugin));
    when(currentSnapshot.pluginOwnership())
        .thenReturn(
            Map.of(
                PLUGIN_ID,
                new PluginCatalogSnapshot.PluginOwnership(
                    Set.of(PLUGIN_CONTRIBUTOR_ID), Set.of())));
    when(currentSnapshot.capabilityFingerprints())
        .thenReturn(Map.of(PLUGIN_ID, CAPABILITY_FINGERPRINT));

    pluginTool =
        createContribution(
            PLUGIN_CONTRIBUTOR_ID,
            "echo-tool",
            "echo-tool",
            "Echo description",
            ToolVisibility.SELECTABLE,
            ToolRequirements.none());

    builtinTool =
        createContribution(
            BUILTIN_CONTRIBUTOR_ID,
            "calc-tool",
            "calc-tool",
            "Calculator description",
            ToolVisibility.SELECTABLE,
            ToolRequirements.none());

    when(currentHarnessCatalog.tools()).thenReturn(List.of(pluginTool, builtinTool));
    when(currentHarnessCatalog.selectableTools()).thenReturn(List.of(pluginTool, builtinTool));

    when(runtime.acquireCurrent()).thenReturn(currentRuntimeLease);
  }

  // =========================================================================
  // 1. Current / History acquire 测试
  // =========================================================================

  @Test
  void acquireCurrent_returnsLeaseWithCurrentEpochAndCatalog() {
    // 意图：验证 acquireCurrent 获取当前最新权威快照的租用，包含正确的 epoch、Harness 目录与工具目录
    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);

    RuntimeToolCatalogSource.Lease lease = source.acquireCurrent();

    assertNotNull(lease);
    assertEquals(CURRENT_EPOCH, lease.epoch());
    assertSame(currentHarnessCatalog, lease.harnessCatalog());
    assertNotNull(lease.catalog());
    verify(runtime, times(1)).acquireCurrent();
    verify(runtime, never()).acquire(anyLong());
  }

  @Test
  void acquire_withHistoryEpoch_returnsLeaseForRequestedEpochWithoutFallback() {
    // 意图：验证 acquire(historyEpoch) 严格为指定历史 epoch 获取租用，不发生到当前最新 epoch 的静默回退
    PluginCatalogRuntime.Lease historyRuntimeLease = mock(PluginCatalogRuntime.Lease.class);
    PluginCatalogSnapshot historySnapshot = mock(PluginCatalogSnapshot.class);
    HarnessCatalog historyHarnessCatalog = mock(HarnessCatalog.class);

    when(historyRuntimeLease.snapshot()).thenReturn(historySnapshot);
    when(historySnapshot.epoch()).thenReturn(HISTORY_EPOCH);
    when(historySnapshot.harnessCatalog()).thenReturn(historyHarnessCatalog);
    when(historySnapshot.configuredPlugins()).thenReturn(List.of());
    when(historySnapshot.pluginOwnership()).thenReturn(Map.of());
    when(historySnapshot.capabilityFingerprints()).thenReturn(Map.of());
    when(historyHarnessCatalog.tools()).thenReturn(List.of());
    when(historyHarnessCatalog.selectableTools()).thenReturn(List.of());

    when(runtime.acquire(HISTORY_EPOCH)).thenReturn(historyRuntimeLease);

    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);

    RuntimeToolCatalogSource.Lease lease = source.acquire(HISTORY_EPOCH);

    assertNotNull(lease);
    assertEquals(HISTORY_EPOCH, lease.epoch());
    assertSame(historyHarnessCatalog, lease.harnessCatalog());
    verify(runtime, times(1)).acquire(HISTORY_EPOCH);
    verify(runtime, never()).acquireCurrent();
  }

  @Test
  void acquire_whenRuntimeFails_propagatesExceptionDirectly() {
    // 意图：验证当底层 runtime 抛出异常时直接原样向上传播
    when(runtime.acquire(999L))
        .thenThrow(new IllegalArgumentException("catalog epoch is not available: 999"));

    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);

    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> source.acquire(999L));
    assertEquals("catalog epoch is not available: 999", ex.getMessage());
  }

  // =========================================================================
  // 2. pluginSource 与 ownership 事实
  // =========================================================================

  @Test
  void pluginSource_resolvesFromSnapshotOwnership() {
    // 意图：验证插件贡献的 ToolPluginSource 严格从 snapshot 的 configuredPlugins 与 pluginOwnership 推导
    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);
    RuntimeToolCatalogSource.Lease lease = source.acquireCurrent();

    ContributionId pluginContributionId = new ContributionId(PLUGIN_CONTRIBUTOR_ID, "echo-tool");
    ToolPluginSource sourceInfo = lease.pluginSource(pluginContributionId);

    assertNotNull(sourceInfo);
    assertEquals(PLUGIN_ID, sourceInfo.pluginId());
    assertEquals(PLUGIN_VERSION, sourceInfo.pluginVersion());
    assertEquals(CONFIG_REVISION, sourceInfo.configRevision());
    assertEquals(CAPABILITY_FINGERPRINT, sourceInfo.capabilityFingerprint());
    assertEquals(CONTEXT_FINGERPRINT, sourceInfo.contextFingerprint());
    assertEquals(AUTH_GENERATION, sourceInfo.authGeneration());
  }

  @Test
  void pluginSource_returnsNullForBuiltinAndMcpContributions() {
    // 意图：验证内置工具和 MCP 工具不属于任何插件实例，pluginSource 恒为 null
    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);
    RuntimeToolCatalogSource.Lease lease = source.acquireCurrent();

    ContributionId builtinId = new ContributionId(BUILTIN_CONTRIBUTOR_ID, "calc-tool");
    assertNull(lease.pluginSource(builtinId));

    ContributionId mcpId = new ContributionId(McpToolCatalog.CONTRIBUTOR_ID, "mcp-tool");
    assertNull(lease.pluginSource(mcpId));
  }

  @Test
  void pluginSource_rejectsNullContributionId() {
    // 意图：验证 pluginSource 查询参数为 null 时抛出 NullPointerException
    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);
    RuntimeToolCatalogSource.Lease lease = source.acquireCurrent();

    assertThrows(NullPointerException.class, () -> lease.pluginSource(null));
  }

  @Test
  void pluginSource_missingCapabilityFingerprint_throwsIllegalStateException() {
    // 意图：验证若 snapshot 声明了插件 ownership 但缺失 capabilityFingerprint，创建租用直接失败
    when(currentSnapshot.capabilityFingerprints()).thenReturn(Map.of());

    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);

    IllegalStateException ex = assertThrows(IllegalStateException.class, source::acquireCurrent);
    assertTrue(
        ex.getMessage().contains("plugin capability fingerprint is missing for " + PLUGIN_ID));
  }

  @Test
  void pluginSource_duplicateContributorAcrossPlugins_throwsIllegalStateException() {
    // 意图：验证若多个插件声明拥有同一 ContributorId，破坏唯一归属，创建租用直接 fail-closed
    PluginDescriptor secondDescriptor =
        new PluginDescriptor("plugin.second", "Second Plugin", "2.0.0");
    PluginCatalogSnapshot.ConfiguredPlugin secondConfigured =
        new PluginCatalogSnapshot.ConfiguredPlugin(
            secondDescriptor,
            new PluginConfigurationState(
                new PluginConfig(1L, "{}"),
                new PluginCredentialReference(
                    "plugin.second", AUTH_GENERATION, CONTEXT_FINGERPRINT)),
            mock(PluginInstance.class));

    PluginCatalogSnapshot.ConfiguredPlugin first = currentSnapshot.configuredPlugins().get(0);
    when(currentSnapshot.configuredPlugins()).thenReturn(List.of(first, secondConfigured));
    when(currentSnapshot.pluginOwnership())
        .thenReturn(
            Map.of(
                PLUGIN_ID,
                new PluginCatalogSnapshot.PluginOwnership(Set.of(PLUGIN_CONTRIBUTOR_ID), Set.of()),
                "plugin.second",
                new PluginCatalogSnapshot.PluginOwnership(
                    Set.of(PLUGIN_CONTRIBUTOR_ID), Set.of())));
    when(currentSnapshot.capabilityFingerprints())
        .thenReturn(
            Map.of(PLUGIN_ID, CAPABILITY_FINGERPRINT, "plugin.second", CAPABILITY_FINGERPRINT));

    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);

    IllegalStateException ex = assertThrows(IllegalStateException.class, source::acquireCurrent);
    assertTrue(ex.getMessage().contains("is owned by more than one plugin"));
  }

  @Test
  void pluginSource_pluginWithoutOwnership_ignoredGracefully() {
    // 意图：验证没有声明任何 contributor 的插件配置会被跳过，不影响其它插件来源解析
    PluginDescriptor emptyDescriptor =
        new PluginDescriptor("plugin.empty", "Empty Plugin", "1.0.0");
    PluginCatalogSnapshot.ConfiguredPlugin emptyConfigured =
        new PluginCatalogSnapshot.ConfiguredPlugin(
            emptyDescriptor,
            new PluginConfigurationState(
                new PluginConfig(1L, "{}"),
                new PluginCredentialReference(
                    "plugin.empty", AUTH_GENERATION, CONTEXT_FINGERPRINT)),
            mock(PluginInstance.class));

    PluginCatalogSnapshot.ConfiguredPlugin first = currentSnapshot.configuredPlugins().get(0);
    when(currentSnapshot.configuredPlugins()).thenReturn(List.of(emptyConfigured, first));

    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);
    RuntimeToolCatalogSource.Lease lease = source.acquireCurrent();

    ContributionId pluginContributionId = new ContributionId(PLUGIN_CONTRIBUTOR_ID, "echo-tool");
    assertNotNull(lease.pluginSource(pluginContributionId));
  }

  // =========================================================================
  // 3. 冻结 MCP 单次读取 (无 live fallback)
  // =========================================================================

  @Test
  void freezeMcp_readsSnapshotExactlyOnceWithoutLiveFallback() {
    // 意图：验证租用构建时 MCP 仅执行单次 readSnapshot 冻结全部事实，后续无论调用多少次 catalog 方法都不二次读库
    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);

    RuntimeToolCatalogSource.Lease lease = source.acquireCurrent();

    verify(mcpCatalog, times(1)).readSnapshot();

    RuntimeToolCatalog catalog = lease.catalog();
    catalog.selectableTools();
    catalog.selectableTools();
    catalog.findTool("echo-tool");
    catalog.findTool("calc-tool");
    catalog.findTool("non-existent");

    verify(mcpCatalog, times(1)).readSnapshot();
    verify(mcpCatalog, never()).selectableTools();
    verify(mcpCatalog, never()).findTool(any());
  }

  @Test
  void freezeMcp_aggregatesToolsAndSelectableToolsAcrossBothCatalogs() {
    // 意图：验证冻结目录正确合并 Harness 与 MCP 的全部 plannable/internal 工具到 findTool，且 selectableTools 仅包含双方的
    // selectable 工具
    ToolContribution internalHarness =
        createContribution(
            BUILTIN_CONTRIBUTOR_ID,
            "harness-internal",
            "harness-internal",
            "Internal harness tool",
            ToolVisibility.INTERNAL,
            ToolRequirements.none());

    when(currentHarnessCatalog.tools()).thenReturn(List.of(pluginTool, internalHarness));
    when(currentHarnessCatalog.selectableTools()).thenReturn(List.of(pluginTool));

    ToolContribution mcpSelectable =
        createContribution(
            McpToolCatalog.CONTRIBUTOR_ID,
            "mcp-selectable",
            "mcp-selectable",
            "MCP selectable tool",
            ToolVisibility.SELECTABLE,
            ToolRequirements.none());

    ToolContribution mcpPlannableOnly =
        createContribution(
            McpToolCatalog.CONTRIBUTOR_ID,
            "mcp-plannable",
            "mcp-plannable",
            "MCP plannable only tool",
            ToolVisibility.INTERNAL,
            ToolRequirements.none());

    when(mcpSnapshot.plannableTools()).thenReturn(List.of(mcpSelectable, mcpPlannableOnly));
    when(mcpSnapshot.selectableTools()).thenReturn(List.of(mcpSelectable));

    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);
    RuntimeToolCatalogSource.Lease lease = source.acquireCurrent();
    RuntimeToolCatalog catalog = lease.catalog();

    List<ToolContribution> selectables = catalog.selectableTools();
    assertEquals(2, selectables.size());
    assertTrue(selectables.contains(pluginTool));
    assertTrue(selectables.contains(mcpSelectable));
    assertFalse(selectables.contains(internalHarness));
    assertFalse(selectables.contains(mcpPlannableOnly));

    Optional<ToolContribution> foundPlugin = catalog.findTool("echo-tool");
    assertTrue(foundPlugin.isPresent());
    assertSame(pluginTool, foundPlugin.get());

    Optional<ToolContribution> foundInternal = catalog.findTool("harness-internal");
    assertTrue(foundInternal.isPresent());
    assertSame(internalHarness, foundInternal.get());

    Optional<ToolContribution> foundMcpSelectable = catalog.findTool("mcp-selectable");
    assertTrue(foundMcpSelectable.isPresent());
    assertSame(mcpSelectable, foundMcpSelectable.get());

    Optional<ToolContribution> foundMcpPlannable = catalog.findTool("mcp-plannable");
    assertTrue(foundMcpPlannable.isPresent());
    assertSame(mcpPlannableOnly, foundMcpPlannable.get());

    Optional<ToolContribution> notFound = catalog.findTool("non-existent");
    assertTrue(notFound.isEmpty());
  }

  @Test
  void freezeMcp_duplicateToolNameAcrossCatalogs_failsClosed() {
    // 意图：验证当 Harness 工具与 MCP 工具同名时，冻结过程 fail-closed 抛出 IllegalStateException
    ToolContribution duplicateMcpTool =
        createContribution(
            McpToolCatalog.CONTRIBUTOR_ID,
            "echo-tool",
            "echo-tool",
            "Duplicate echo tool from MCP",
            ToolVisibility.SELECTABLE,
            ToolRequirements.none());

    when(mcpSnapshot.plannableTools()).thenReturn(List.of(duplicateMcpTool));

    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);

    IllegalStateException ex = assertThrows(IllegalStateException.class, source::acquireCurrent);
    assertTrue(ex.getMessage().contains("duplicate tool name across catalogs: echo-tool"));
  }

  // =========================================================================
  // 4. 冻结失败关闭已取 runtime lease 且 cleanup suppressed
  // =========================================================================

  @Test
  void openFailure_closesAcquiredSnapshotLease() {
    // 意图：验证构建冻结目录失败时，已从 runtime 取得的 snapshotLease 被安全关闭，不泄漏快照引用
    ToolContribution duplicateMcpTool =
        createContribution(
            McpToolCatalog.CONTRIBUTOR_ID,
            "echo-tool",
            "echo-tool",
            "Duplicate tool",
            ToolVisibility.SELECTABLE,
            ToolRequirements.none());
    when(mcpSnapshot.plannableTools()).thenReturn(List.of(duplicateMcpTool));

    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);

    assertThrows(IllegalStateException.class, source::acquireCurrent);
    verify(currentRuntimeLease, times(1)).close();
  }

  @Test
  void openFailure_whenLeaseCloseThrows_suppressesCleanupExceptionAndPreservesPrimary() {
    // 意图：验证构建冻结目录失败且 snapshotLease.close 也抛出异常时，清理异常以 suppressed 附加，主异常原样传播
    ToolContribution duplicateMcpTool =
        createContribution(
            McpToolCatalog.CONTRIBUTOR_ID,
            "echo-tool",
            "echo-tool",
            "Duplicate tool",
            ToolVisibility.SELECTABLE,
            ToolRequirements.none());
    when(mcpSnapshot.plannableTools()).thenReturn(List.of(duplicateMcpTool));

    RuntimeException cleanupException = new RuntimeException("lease close cleanup failed");
    doThrow(cleanupException).when(currentRuntimeLease).close();

    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);

    IllegalStateException primary =
        assertThrows(IllegalStateException.class, source::acquireCurrent);

    assertTrue(primary.getMessage().contains("duplicate tool name across catalogs: echo-tool"));
    Throwable[] suppressed = primary.getSuppressed();
    assertEquals(1, suppressed.length);
    assertSame(cleanupException, suppressed[0]);
  }

  @Test
  void openFailure_whenFatalErrorOccurs_suppressesCleanupException() {
    // 意图：验证在抛出 Error 等致命错误时，snapshotLease 同样被关闭，且 cleanup 异常被正确附加
    when(mcpCatalog.readSnapshot()).thenThrow(new AssertionError("fatal catalog read error"));

    RuntimeException cleanupException = new RuntimeException("cleanup failure");
    doThrow(cleanupException).when(currentRuntimeLease).close();

    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);

    AssertionError fatal = assertThrows(AssertionError.class, source::acquireCurrent);

    assertEquals("fatal catalog read error", fatal.getMessage());
    Throwable[] suppressed = fatal.getSuppressed();
    assertEquals(1, suppressed.length);
    assertSame(cleanupException, suppressed[0]);
  }

  @Test
  void openFailure_whenFatalErrorOccursAndLeaseCloseThrowsSameError_doesNotSelfSuppress() {
    // 冻结与关闭共享同一 Error：仍传播原实例，不附加自身。
    AssertionError fatal = new AssertionError("fatal catalog read error");
    when(mcpCatalog.readSnapshot()).thenThrow(fatal);
    doThrow(fatal).when(currentRuntimeLease).close();

    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);

    AssertionError thrown = assertThrows(AssertionError.class, source::acquireCurrent);

    assertSame(fatal, thrown);
    assertEquals(0, thrown.getSuppressed().length);
    verify(currentRuntimeLease, times(1)).close();
  }

  // =========================================================================
  // 5. 严格 require 各 mismatch reason
  // =========================================================================

  @Test
  void require_success_forPluginTool() {
    // 意图：验证当 binding 的各项要素与插件工具完全一致时成功返回对应 ToolContribution
    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);
    RuntimeToolCatalogSource.Lease lease = source.acquireCurrent();

    ToolBinding binding =
        new ToolBinding(
            CURRENT_EPOCH,
            pluginTool.definition(),
            new ContributorBinding("plugin.contributor.demo", "echo-tool", List.of()),
            EnvironmentSupport.NONE,
            null,
            null,
            expectedPluginSource);

    ToolContribution matched = lease.require(binding);
    assertSame(pluginTool, matched);
  }

  @Test
  void require_success_forBuiltinToolWithoutPluginSource() {
    // 意图：验证非插件（内置/MCP）工具在 binding.pluginSource 为 null 时成功通过 require 校验
    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);
    RuntimeToolCatalogSource.Lease lease = source.acquireCurrent();

    ToolBinding binding =
        new ToolBinding(
            CURRENT_EPOCH,
            builtinTool.definition(),
            new ContributorBinding("builtin.system", "calc-tool", List.of()),
            EnvironmentSupport.NONE,
            null,
            null,
            null);

    ToolContribution matched = lease.require(binding);
    assertSame(builtinTool, matched);
  }

  @Test
  void require_mismatch_epoch_failsWithDefinitionReason() {
    // 意图：验证 binding 的 catalogEpoch 与租用 epoch 不一致时拒绝并报告 DEFINITION mismatch
    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);
    RuntimeToolCatalogSource.Lease lease = source.acquireCurrent();

    long wrongEpoch = CURRENT_EPOCH + 1;
    ToolBinding binding =
        new ToolBinding(
            wrongEpoch,
            pluginTool.definition(),
            new ContributorBinding("plugin.contributor.demo", "echo-tool", List.of()),
            EnvironmentSupport.NONE,
            null,
            null,
            expectedPluginSource);

    ToolBindingMismatchException ex =
        assertThrows(ToolBindingMismatchException.class, () -> lease.require(binding));
    assertEquals(ToolBindingMismatchException.Reason.DEFINITION, ex.reason());
    assertTrue(
        ex.getMessage().contains("binding catalogEpoch does not match the acquired catalog epoch"));
  }

  @Test
  void require_mismatch_toolNotFound_failsWithNotFoundReason() {
    // 意图：验证 binding 请求的工具在冻结目录中不存在时拒绝并报告 NOT_FOUND mismatch
    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);
    RuntimeToolCatalogSource.Lease lease = source.acquireCurrent();

    AgentToolDefinition missingDefinition =
        new AgentToolDefinition(
            createDescriptor("missing-tool", "Missing tool description"),
            ToolVisibility.SELECTABLE);

    ToolBinding binding =
        new ToolBinding(
            CURRENT_EPOCH,
            missingDefinition,
            new ContributorBinding("plugin.contributor.demo", "missing-tool", List.of()),
            EnvironmentSupport.NONE,
            null,
            null,
            expectedPluginSource);

    ToolBindingMismatchException ex =
        assertThrows(ToolBindingMismatchException.class, () -> lease.require(binding));
    assertEquals(ToolBindingMismatchException.Reason.NOT_FOUND, ex.reason());
    assertTrue(ex.getMessage().contains("Frozen tool definition missing-tool is not registered"));
  }

  @Test
  void require_mismatch_definition_failsWithDefinitionReason() {
    // 意图：验证工具名存在但其 AgentToolDefinition 漂移时拒绝并报告 DEFINITION mismatch
    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);
    RuntimeToolCatalogSource.Lease lease = source.acquireCurrent();

    AgentToolDefinition driftedDefinition =
        new AgentToolDefinition(
            createDescriptor("echo-tool", "Drifted different description"),
            ToolVisibility.SELECTABLE);

    ToolBinding binding =
        new ToolBinding(
            CURRENT_EPOCH,
            driftedDefinition,
            new ContributorBinding("plugin.contributor.demo", "echo-tool", List.of()),
            EnvironmentSupport.NONE,
            null,
            null,
            expectedPluginSource);

    ToolBindingMismatchException ex =
        assertThrows(ToolBindingMismatchException.class, () -> lease.require(binding));
    assertEquals(ToolBindingMismatchException.Reason.DEFINITION, ex.reason());
    assertTrue(ex.getMessage().contains("does not match its catalog definition"));
  }

  @Test
  void require_mismatch_contributorId_failsWithContributorReason() {
    // 意图：验证 contributorId 不一致时拒绝并报告 CONTRIBUTOR mismatch
    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);
    RuntimeToolCatalogSource.Lease lease = source.acquireCurrent();

    ToolBinding binding =
        new ToolBinding(
            CURRENT_EPOCH,
            pluginTool.definition(),
            new ContributorBinding("other.contributor", "echo-tool", List.of()),
            EnvironmentSupport.NONE,
            null,
            null,
            expectedPluginSource);

    ToolBindingMismatchException ex =
        assertThrows(ToolBindingMismatchException.class, () -> lease.require(binding));
    assertEquals(ToolBindingMismatchException.Reason.CONTRIBUTOR, ex.reason());
    assertTrue(ex.getMessage().contains("does not match the catalog contribution"));
  }

  @Test
  void require_mismatch_localName_failsWithContributorReason() {
    // 意图：验证 contributor localName 不一致时拒绝并报告 CONTRIBUTOR mismatch
    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);
    RuntimeToolCatalogSource.Lease lease = source.acquireCurrent();

    ToolBinding binding =
        new ToolBinding(
            CURRENT_EPOCH,
            pluginTool.definition(),
            new ContributorBinding("plugin.contributor.demo", "other-local", List.of()),
            EnvironmentSupport.NONE,
            null,
            null,
            expectedPluginSource);

    ToolBindingMismatchException ex =
        assertThrows(ToolBindingMismatchException.class, () -> lease.require(binding));
    assertEquals(ToolBindingMismatchException.Reason.CONTRIBUTOR, ex.reason());
    assertTrue(ex.getMessage().contains("does not match the catalog contribution"));
  }

  @Test
  void require_mismatch_environmentSupport_failsWithRequirementsReason() {
    // 意图：验证 binding environmentSupport 与目录 requirements 不一致时拒绝并报告 REQUIREMENTS mismatch
    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);
    RuntimeToolCatalogSource.Lease lease = source.acquireCurrent();

    // 目录中的 pluginTool 的 requirements 是 NONE，而 binding 声明 OPTIONAL
    ToolBinding binding =
        new ToolBinding(
            CURRENT_EPOCH,
            pluginTool.definition(),
            new ContributorBinding("plugin.contributor.demo", "echo-tool", List.of()),
            EnvironmentSupport.OPTIONAL,
            null,
            null,
            expectedPluginSource);

    ToolBindingMismatchException ex =
        assertThrows(ToolBindingMismatchException.class, () -> lease.require(binding));
    assertEquals(ToolBindingMismatchException.Reason.REQUIREMENTS, ex.reason());
    assertTrue(
        ex.getMessage()
            .contains("Frozen tool environment requirement does not match catalog requirements"));
  }

  @Test
  void require_mismatch_requiredEnvironmentId_failsWithRequirementsReason() {
    // 意图：验证 requiredEnvironmentId 与 binding environmentId 不匹配时拒绝并报告 REQUIREMENTS mismatch
    ToolContribution envBoundTool =
        createContribution(
            PLUGIN_CONTRIBUTOR_ID,
            "env-tool",
            "env-tool",
            "Env bound tool",
            ToolVisibility.SELECTABLE,
            new ToolRequirements(
                EnvironmentSupport.REQUIRED, new EnvironmentId(ENV_UUID_1), List.of()));

    when(currentHarnessCatalog.tools()).thenReturn(List.of(envBoundTool));
    when(currentHarnessCatalog.selectableTools()).thenReturn(List.of(envBoundTool));

    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);
    RuntimeToolCatalogSource.Lease lease = source.acquireCurrent();

    // binding 绑定了另一个环境 ENV_UUID_2
    ToolBinding binding =
        new ToolBinding(
            CURRENT_EPOCH,
            envBoundTool.definition(),
            new ContributorBinding("plugin.contributor.demo", "env-tool", List.of()),
            EnvironmentSupport.REQUIRED,
            new EnvironmentId(ENV_UUID_2),
            "different-env",
            expectedPluginSource);

    ToolBindingMismatchException ex =
        assertThrows(ToolBindingMismatchException.class, () -> lease.require(binding));
    assertEquals(ToolBindingMismatchException.Reason.REQUIREMENTS, ex.reason());
    assertTrue(ex.getMessage().contains("does not match catalog required environment"));
  }

  @Test
  void require_mismatch_stateAccesses_failsWithRequirementsReason() {
    // 意图：验证 contributor stateAccesses 声明（类型或模式）与目录不匹配时拒绝并报告 REQUIREMENTS mismatch
    ToolContribution stateTool =
        createContribution(
            PLUGIN_CONTRIBUTOR_ID,
            "state-tool",
            "state-tool",
            "Stateful tool",
            ToolVisibility.SELECTABLE,
            new ToolRequirements(
                EnvironmentSupport.NONE,
                null,
                List.of(new StateDeclaration("custom-state", StateMode.READ))));

    when(currentHarnessCatalog.tools()).thenReturn(List.of(stateTool));
    when(currentHarnessCatalog.selectableTools()).thenReturn(List.of(stateTool));

    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);
    RuntimeToolCatalogSource.Lease lease = source.acquireCurrent();

    // binding 的 contributor 声明了 WRITE 而不是 READ
    ContributorBinding mismatchedStateContributor =
        new ContributorBinding(
            "plugin.contributor.demo",
            "state-tool",
            List.of(new ContributorStateAccess("custom-state", ContributorStateAccessMode.WRITE)));

    ToolBinding binding =
        new ToolBinding(
            CURRENT_EPOCH,
            stateTool.definition(),
            mismatchedStateContributor,
            EnvironmentSupport.NONE,
            null,
            null,
            expectedPluginSource);

    ToolBindingMismatchException ex =
        assertThrows(ToolBindingMismatchException.class, () -> lease.require(binding));
    assertEquals(ToolBindingMismatchException.Reason.REQUIREMENTS, ex.reason());
    assertTrue(ex.getMessage().contains("no longer matches its frozen state access declaration"));
  }

  @Test
  void require_mismatch_pluginSource_failsWithSourceReason() {
    // 意图：验证 binding 的 ToolPluginSource 与租用目录解析的插件来源（如配置修订或版本）不匹配时报告 SOURCE mismatch
    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);
    RuntimeToolCatalogSource.Lease lease = source.acquireCurrent();

    // 来源的 configRevision 发生漂移（从 2 漂移为 3）
    ToolPluginSource driftedSource =
        new ToolPluginSource(
            PLUGIN_ID,
            PLUGIN_VERSION,
            CONFIG_REVISION + 1,
            CAPABILITY_FINGERPRINT,
            CONTEXT_FINGERPRINT,
            AUTH_GENERATION);

    ToolBinding binding =
        new ToolBinding(
            CURRENT_EPOCH,
            pluginTool.definition(),
            new ContributorBinding("plugin.contributor.demo", "echo-tool", List.of()),
            EnvironmentSupport.NONE,
            null,
            null,
            driftedSource);

    ToolBindingMismatchException ex =
        assertThrows(ToolBindingMismatchException.class, () -> lease.require(binding));
    assertEquals(ToolBindingMismatchException.Reason.SOURCE, ex.reason());
    assertTrue(
        ex.getMessage().contains("Frozen plugin source does not match the acquired catalog epoch"));
  }

  @Test
  void require_whenBuiltinToolHasUnexpectedPluginSource_failsWithSourceReason() {
    // 意图：验证非插件工具若携带非空 pluginSource，与目录的 null 来源不符时报告 SOURCE mismatch
    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);
    RuntimeToolCatalogSource.Lease lease = source.acquireCurrent();

    ToolBinding binding =
        new ToolBinding(
            CURRENT_EPOCH,
            builtinTool.definition(),
            new ContributorBinding("builtin.system", "calc-tool", List.of()),
            EnvironmentSupport.NONE,
            null,
            null,
            expectedPluginSource);

    ToolBindingMismatchException ex =
        assertThrows(ToolBindingMismatchException.class, () -> lease.require(binding));
    assertEquals(ToolBindingMismatchException.Reason.SOURCE, ex.reason());
    assertTrue(
        ex.getMessage().contains("Frozen plugin source does not match the acquired catalog epoch"));
  }

  @Test
  void require_rejectsNullBinding() {
    // 意图：验证 require 拒绝 null binding 并抛出 NullPointerException
    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);
    RuntimeToolCatalogSource.Lease lease = source.acquireCurrent();

    assertThrows(NullPointerException.class, () -> lease.require(null));
  }

  // =========================================================================
  // 6. Close 幂等及关闭后操作拒绝
  // =========================================================================

  @Test
  void close_isIdempotent() {
    // 意图：验证 Lease.close() 多次调用幂等，底层 runtime snapshotLease.close() 仅被调用恰好一次
    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);
    RuntimeToolCatalogSource.Lease lease = source.acquireCurrent();

    lease.close();
    lease.close();
    lease.close();

    verify(currentRuntimeLease, times(1)).close();
  }

  @Test
  void closedLease_rejectsAllOperations() {
    // 意图：验证租用关闭后，所有方法均确定性抛出 IllegalStateException("runtime tool catalog lease is closed")
    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);
    RuntimeToolCatalogSource.Lease lease = source.acquireCurrent();

    lease.close();

    IllegalStateException epochEx = assertThrows(IllegalStateException.class, lease::epoch);
    assertEquals("runtime tool catalog lease is closed", epochEx.getMessage());

    IllegalStateException harnessEx =
        assertThrows(IllegalStateException.class, lease::harnessCatalog);
    assertEquals("runtime tool catalog lease is closed", harnessEx.getMessage());

    IllegalStateException catalogEx = assertThrows(IllegalStateException.class, lease::catalog);
    assertEquals("runtime tool catalog lease is closed", catalogEx.getMessage());

    ContributionId contributionId = new ContributionId(PLUGIN_CONTRIBUTOR_ID, "echo-tool");
    IllegalStateException sourceEx =
        assertThrows(IllegalStateException.class, () -> lease.pluginSource(contributionId));
    assertEquals("runtime tool catalog lease is closed", sourceEx.getMessage());

    ToolBinding binding =
        new ToolBinding(
            CURRENT_EPOCH,
            pluginTool.definition(),
            new ContributorBinding("plugin.contributor.demo", "echo-tool", List.of()),
            EnvironmentSupport.NONE,
            null,
            null,
            expectedPluginSource);
    IllegalStateException requireEx =
        assertThrows(IllegalStateException.class, () -> lease.require(binding));
    assertEquals("runtime tool catalog lease is closed", requireEx.getMessage());
  }

  // =========================================================================
  // 构造器前置校验
  // =========================================================================

  @Test
  void constructor_rejectsNullArguments() {
    // 意图：验证 PluginRuntimeToolCatalogSource 构造器拒绝 null 依赖
    assertThrows(
        NullPointerException.class, () -> new PluginRuntimeToolCatalogSource(null, mcpCatalog));
    assertThrows(
        NullPointerException.class, () -> new PluginRuntimeToolCatalogSource(runtime, null));
  }

  // =========================================================================
  // 辅助方法
  // =========================================================================

  private static ToolDescriptor createDescriptor(String name, String description) {
    return new ToolDescriptor(
        name,
        description,
        name,
        new InputSchema("{}", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(30));
  }

  private static ToolContribution createContribution(
      ContributorId contributorId,
      String localName,
      String toolName,
      String description,
      ToolVisibility visibility,
      ToolRequirements requirements) {
    ContributionId id = new ContributionId(contributorId, localName);
    ToolDescriptor descriptor = createDescriptor(toolName, description);
    AgentToolDefinition definition = new AgentToolDefinition(descriptor, visibility);
    Tool tool = mock(Tool.class);
    when(tool.descriptor()).thenReturn(descriptor);
    when(tool.requirements()).thenReturn(requirements);
    return new ToolContribution(id, definition, tool, requirements, 0);
  }
}
