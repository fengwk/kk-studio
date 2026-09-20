package fun.fengwk.kkstudio.platform.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.BuiltinHarnessContributor;
import fun.fengwk.kkstudio.harness.builtin.environment.ReadTool;
import fun.fengwk.kkstudio.harness.builtin.environment.ReadToolExecutor;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentConfig;
import fun.fengwk.kkstudio.harness.builtin.subagent.TaskTool;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.harness.configuration.HarnessExecutionAdmissionProperties;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 验证 {@link BuiltinHarnessContributorConfiguration#subagentConfigProvider} 把 {@code
 * SystemSettings.AiRuntime} 的五个 subagent 字段完整映射为 {@link SubagentConfig}，以及 executor 与 Contributor
 * 装配。
 */
class BuiltinHarnessContributorConfigurationTest {

  @Test
  void subagentConfigMapsAllFiveAiRuntimeFields() {
    // 使用互不相同的非默认值，确保 per-parent 与 total 上限不会在装配时丢失或互换。
    SystemSettings.AiRuntime aiRuntime =
        new SystemSettings.AiRuntime(
            SystemSettings.AiRuntime.DEFAULT.retryMaxRetries(),
            SystemSettings.AiRuntime.DEFAULT.retryBackoffStrategy(),
            SystemSettings.AiRuntime.DEFAULT.retryBaseDelayMillis(),
            SystemSettings.AiRuntime.DEFAULT.retryMaxDelayMillis(),
            SystemSettings.AiRuntime.DEFAULT.compactionKeepRecentTokens(),
            SystemSettings.AiRuntime.DEFAULT.compactionFallbackModel(),
            4,
            7,
            13,
            1234L,
            89);

    SubagentConfig config =
        new BuiltinHarnessContributorConfiguration()
            .subagentConfigProvider(new SystemSettingsSnapshot(customSettings(aiRuntime)))
            .subagentConfig();

    assertEquals(4, config.maxDepth());
    assertEquals(7, config.maxConcurrency());
    assertEquals(13, config.maxTotalConcurrency());
    assertEquals(Duration.ofMillis(1234L), config.idleTimeout());
    assertEquals(89, config.maxTurns());
  }

  @Test
  void subagentExecutorUsesFixedVirtualThreadsAndZeroQueue() throws Exception {
    HarnessExecutionAdmissionProperties properties = new HarnessExecutionAdmissionProperties();
    properties.setSubagent(2);
    ExecutorService executor =
        new BuiltinHarnessContributorConfiguration().subagentTaskExecutor(properties);
    ThreadPoolExecutor pool = assertInstanceOf(ThreadPoolExecutor.class, executor);
    CountDownLatch entered = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    try {
      assertEquals(2, pool.getCorePoolSize());
      assertEquals(2, pool.getMaximumPoolSize());
      assertEquals(0, pool.getQueue().remainingCapacity());

      for (int i = 0; i < 2; i++) {
        executor.execute(
            () -> {
              assertTrue(Thread.currentThread().isVirtual());
              entered.countDown();
              try {
                release.await();
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
            });
      }
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      // 固定 N + SynchronousQueue：第 N+1 个 Task 在创建 child 前确定性拒绝。
      assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
    } finally {
      release.countDown();
      executor.close();
    }
  }

  @Test
  void executionAdmissionPropertiesRejectNonPositiveValues() {
    HarnessExecutionAdmissionProperties properties = new HarnessExecutionAdmissionProperties();
    assertEquals(16, properties.getModel());
    assertEquals(64, properties.getTool());
    assertEquals(10, properties.getSubagent());
    properties.setModel(3);
    properties.setTool(4);
    properties.setSubagent(5);
    assertEquals(3, properties.getModel());
    assertEquals(4, properties.getTool());
    assertEquals(5, properties.getSubagent());
    assertThrows(IllegalArgumentException.class, () -> properties.setModel(0));
    assertThrows(IllegalArgumentException.class, () -> properties.setTool(-1));
    assertThrows(IllegalArgumentException.class, () -> properties.setSubagent(0));
  }

  @Test
  void builtinHarnessContributorRegistersInternalAndSelectableTools() {
    ReadTool readTool = new ReadTool(mock(ReadToolExecutor.class));
    TaskTool task = mock(TaskTool.class);
    when(task.descriptor()).thenReturn(descriptor("task"));
    when(task.requirements()).thenReturn(ToolRequirements.none());

    BuiltinHarnessContributor contributor =
        new BuiltinHarnessContributorConfiguration().builtinHarnessContributor(readTool, task);

    HarnessCatalog catalog = HarnessCatalog.from(List.of(contributor));
    assertEquals(
        ToolVisibility.INTERNAL,
        catalog.findTool(TaskTool.NAME).orElseThrow().definition().visibility());
    assertEquals(
        ToolVisibility.SELECTABLE,
        catalog.findTool(ReadTool.NAME).orElseThrow().definition().visibility());
  }

  @Test
  void readToolBeanInstantiatesWithExecutor() {
    ReadToolExecutor executor = mock(ReadToolExecutor.class);
    ReadTool tool = new BuiltinHarnessContributorConfiguration().readTool(executor);
    assertEquals(ReadTool.NAME, tool.descriptor().name());
  }

  private static ToolDescriptor descriptor(String name) {
    return new ToolDescriptor(
        name,
        name + " description",
        name,
        new InputSchema("arguments", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ZERO);
  }

  private static SystemSettings customSettings(SystemSettings.AiRuntime aiRuntime) {
    return new SystemSettings(
        SystemSettings.Tool.DEFAULT,
        aiRuntime,
        SystemSettings.Environment.DEFAULT,
        SystemSettings.Integrations.DEFAULT,
        SystemSettings.StorageMedia.DEFAULT,
        SystemSettings.Advanced.DEFAULT);
  }
}
