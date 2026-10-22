package fun.fengwk.kkstudio.platform.catalog.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.schema.InputSchema;
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
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.catalog.mcp.runtime.McpToolCatalog;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRuntime;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;

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
 *   <li>租用当前快照；
 *   <li>严格 require 各 mismatch reason（NOT_FOUND, DEFINITION, CONTRIBUTOR, REQUIREMENTS）；
 *   <li>冻结 MCP 单次读取（无 live fallback，同名冲突 fail-closed）；
 *   <li>冻结失败关闭已取 runtime lease 且 cleanup suppressed；
 *   <li>close 幂等及关闭后操作拒绝。
 * </ul>
 */
class PluginRuntimeToolCatalogSourceTest {

  private static final String PLUGIN_ID = "plugin.demo";
  private static final long CONFIG_REVISION = 2L;

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
    when(currentSnapshot.harnessCatalog()).thenReturn(currentHarnessCatalog);

    PluginCatalogSnapshot.ConfiguredPlugin configuredPlugin =
        new PluginCatalogSnapshot.ConfiguredPlugin(
            new PluginDescriptor(PLUGIN_ID, "Demo Plugin", "1.0.0"),
            new PluginConfig(CONFIG_REVISION, "{}"),
            mock(PluginInstance.class));

    when(currentSnapshot.configuredPlugins()).thenReturn(List.of(configuredPlugin));

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

    when(runtime.acquire()).thenReturn(currentRuntimeLease);
  }

  // =========================================================================
  // 1. Current / History acquire 测试
  // =========================================================================

  @Test
  void acquire_returnsLeaseOverTheCurrentSnapshot() {
    // 意图：验证 acquire 租用当前快照，暴露其 Harness 目录与冻结工具目录
    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);

    RuntimeToolCatalogSource.Lease lease = source.acquire();

    assertNotNull(lease);
    assertSame(currentHarnessCatalog, lease.harnessCatalog());
    assertNotNull(lease.catalog());
    verify(runtime, times(1)).acquire();
  }

  @Test
  void acquire_whenRuntimeFails_propagatesExceptionDirectly() {
    // 意图：验证当底层 runtime 抛出异常时直接原样向上传播
    when(runtime.acquire())
        .thenThrow(new IllegalStateException("plugin catalog runtime is closed"));

    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);

    IllegalStateException ex = assertThrows(IllegalStateException.class, source::acquire);
    assertEquals("plugin catalog runtime is closed", ex.getMessage());
  }

  // =========================================================================
  // 3. 冻结 MCP 单次读取 (无 live fallback)
  // =========================================================================

  @Test
  void freezeMcp_readsSnapshotExactlyOnceWithoutLiveFallback() {
    // 意图：验证租用构建时 MCP 仅执行单次 readSnapshot 冻结全部事实，后续无论调用多少次 catalog 方法都不二次读库
    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);

    RuntimeToolCatalogSource.Lease lease = source.acquire();

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
    RuntimeToolCatalogSource.Lease lease = source.acquire();
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

    IllegalStateException ex = assertThrows(IllegalStateException.class, source::acquire);
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

    assertThrows(IllegalStateException.class, source::acquire);
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

    IllegalStateException primary = assertThrows(IllegalStateException.class, source::acquire);

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

    AssertionError fatal = assertThrows(AssertionError.class, source::acquire);

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

    AssertionError thrown = assertThrows(AssertionError.class, source::acquire);

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
    RuntimeToolCatalogSource.Lease lease = source.acquire();

    ToolBinding binding =
        new ToolBinding(
            pluginTool.definition(),
            new ContributorBinding("plugin.contributor.demo", "echo-tool", List.of()),
            EnvironmentSupport.NONE,
            null,
            null);

    ToolContribution matched = lease.require(binding);
    assertSame(pluginTool, matched);
  }

  @Test
  void require_success_forBuiltinTool() {
    // 意图：验证内置工具同样按冻结定义通过 require 校验
    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);
    RuntimeToolCatalogSource.Lease lease = source.acquire();

    ToolBinding binding =
        new ToolBinding(
            builtinTool.definition(),
            new ContributorBinding("builtin.system", "calc-tool", List.of()),
            EnvironmentSupport.NONE,
            null,
            null);

    ToolContribution matched = lease.require(binding);
    assertSame(builtinTool, matched);
  }

  @Test
  void require_mismatch_toolNotFound_failsWithNotFoundReason() {
    // 意图：验证 binding 请求的工具在冻结目录中不存在时拒绝并报告 NOT_FOUND mismatch
    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);
    RuntimeToolCatalogSource.Lease lease = source.acquire();

    AgentToolDefinition missingDefinition =
        new AgentToolDefinition(
            createDescriptor("missing-tool", "Missing tool description"),
            ToolVisibility.SELECTABLE);

    ToolBinding binding =
        new ToolBinding(
            missingDefinition,
            new ContributorBinding("plugin.contributor.demo", "missing-tool", List.of()),
            EnvironmentSupport.NONE,
            null,
            null);

    ToolBindingMismatchException ex =
        assertThrows(ToolBindingMismatchException.class, () -> lease.require(binding));
    assertEquals(ToolBindingMismatchException.Reason.NOT_FOUND, ex.reason());
    assertTrue(ex.getMessage().contains("Frozen tool definition missing-tool is not registered"));
  }

  @Test
  void require_mismatch_definition_failsWithDefinitionReason() {
    // 意图：验证工具名存在但其 AgentToolDefinition 漂移时拒绝并报告 DEFINITION mismatch
    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);
    RuntimeToolCatalogSource.Lease lease = source.acquire();

    AgentToolDefinition driftedDefinition =
        new AgentToolDefinition(
            createDescriptor("echo-tool", "Drifted different description"),
            ToolVisibility.SELECTABLE);

    ToolBinding binding =
        new ToolBinding(
            driftedDefinition,
            new ContributorBinding("plugin.contributor.demo", "echo-tool", List.of()),
            EnvironmentSupport.NONE,
            null,
            null);

    ToolBindingMismatchException ex =
        assertThrows(ToolBindingMismatchException.class, () -> lease.require(binding));
    assertEquals(ToolBindingMismatchException.Reason.DEFINITION, ex.reason());
    assertTrue(ex.getMessage().contains("does not match its catalog definition"));
  }

  @Test
  void require_mismatch_contributorId_failsWithContributorReason() {
    // 意图：验证 contributorId 不一致时拒绝并报告 CONTRIBUTOR mismatch
    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);
    RuntimeToolCatalogSource.Lease lease = source.acquire();

    ToolBinding binding =
        new ToolBinding(
            pluginTool.definition(),
            new ContributorBinding("other.contributor", "echo-tool", List.of()),
            EnvironmentSupport.NONE,
            null,
            null);

    ToolBindingMismatchException ex =
        assertThrows(ToolBindingMismatchException.class, () -> lease.require(binding));
    assertEquals(ToolBindingMismatchException.Reason.CONTRIBUTOR, ex.reason());
    assertTrue(ex.getMessage().contains("does not match the catalog contribution"));
  }

  @Test
  void require_mismatch_localName_failsWithContributorReason() {
    // 意图：验证 contributor localName 不一致时拒绝并报告 CONTRIBUTOR mismatch
    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);
    RuntimeToolCatalogSource.Lease lease = source.acquire();

    ToolBinding binding =
        new ToolBinding(
            pluginTool.definition(),
            new ContributorBinding("plugin.contributor.demo", "other-local", List.of()),
            EnvironmentSupport.NONE,
            null,
            null);

    ToolBindingMismatchException ex =
        assertThrows(ToolBindingMismatchException.class, () -> lease.require(binding));
    assertEquals(ToolBindingMismatchException.Reason.CONTRIBUTOR, ex.reason());
    assertTrue(ex.getMessage().contains("does not match the catalog contribution"));
  }

  @Test
  void require_mismatch_environmentSupport_failsWithRequirementsReason() {
    // 意图：验证 binding environmentSupport 与目录 requirements 不一致时拒绝并报告 REQUIREMENTS mismatch
    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);
    RuntimeToolCatalogSource.Lease lease = source.acquire();

    // 目录中的 pluginTool 的 requirements 是 NONE，而 binding 声明 OPTIONAL
    ToolBinding binding =
        new ToolBinding(
            pluginTool.definition(),
            new ContributorBinding("plugin.contributor.demo", "echo-tool", List.of()),
            EnvironmentSupport.OPTIONAL,
            null,
            null);

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
    RuntimeToolCatalogSource.Lease lease = source.acquire();

    // binding 绑定了另一个环境 ENV_UUID_2
    ToolBinding binding =
        new ToolBinding(
            envBoundTool.definition(),
            new ContributorBinding("plugin.contributor.demo", "env-tool", List.of()),
            EnvironmentSupport.REQUIRED,
            new EnvironmentId(ENV_UUID_2),
            "different-env");

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
    RuntimeToolCatalogSource.Lease lease = source.acquire();

    // binding 的 contributor 声明了 WRITE 而不是 READ
    ContributorBinding mismatchedStateContributor =
        new ContributorBinding(
            "plugin.contributor.demo",
            "state-tool",
            List.of(new ContributorStateAccess("custom-state", ContributorStateAccessMode.WRITE)));

    ToolBinding binding =
        new ToolBinding(
            stateTool.definition(),
            mismatchedStateContributor,
            EnvironmentSupport.NONE,
            null,
            null);

    ToolBindingMismatchException ex =
        assertThrows(ToolBindingMismatchException.class, () -> lease.require(binding));
    assertEquals(ToolBindingMismatchException.Reason.REQUIREMENTS, ex.reason());
    assertTrue(ex.getMessage().contains("no longer matches its frozen state access declaration"));
  }

  @Test
  void require_rejectsNullBinding() {
    // 意图：验证 require 拒绝 null binding 并抛出 NullPointerException
    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);
    RuntimeToolCatalogSource.Lease lease = source.acquire();

    assertThrows(NullPointerException.class, () -> lease.require(null));
  }

  // =========================================================================
  // 6. Close 幂等及关闭后操作拒绝
  // =========================================================================

  @Test
  void close_isIdempotent() {
    // 意图：验证 Lease.close() 多次调用幂等，底层 runtime snapshotLease.close() 仅被调用恰好一次
    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);
    RuntimeToolCatalogSource.Lease lease = source.acquire();

    lease.close();
    lease.close();
    lease.close();

    verify(currentRuntimeLease, times(1)).close();
  }

  @Test
  void closedLease_rejectsAllOperations() {
    // 意图：验证租用关闭后，所有方法均确定性抛出 IllegalStateException("runtime tool catalog lease is closed")
    PluginRuntimeToolCatalogSource source = new PluginRuntimeToolCatalogSource(runtime, mcpCatalog);
    RuntimeToolCatalogSource.Lease lease = source.acquire();

    lease.close();

    IllegalStateException harnessEx =
        assertThrows(IllegalStateException.class, lease::harnessCatalog);
    assertEquals("runtime tool catalog lease is closed", harnessEx.getMessage());

    IllegalStateException catalogEx = assertThrows(IllegalStateException.class, lease::catalog);
    assertEquals("runtime tool catalog lease is closed", catalogEx.getMessage());

    ToolBinding binding =
        new ToolBinding(
            pluginTool.definition(),
            new ContributorBinding("plugin.contributor.demo", "echo-tool", List.of()),
            EnvironmentSupport.NONE,
            null,
            null);
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
