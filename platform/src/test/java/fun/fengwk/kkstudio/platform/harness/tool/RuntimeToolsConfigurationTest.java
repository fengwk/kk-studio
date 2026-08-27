package fun.fengwk.kkstudio.platform.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.plugin.api.HarnessPlugin;
import fun.fengwk.kkstudio.harness.plugin.api.PluginCatalog;
import fun.fengwk.kkstudio.harness.plugin.api.PluginDescriptor;
import fun.fengwk.kkstudio.harness.plugin.api.PluginId;
import fun.fengwk.kkstudio.harness.plugin.api.PluginTool;
import fun.fengwk.kkstudio.harness.plugin.api.PluginToolContext;
import fun.fengwk.kkstudio.harness.plugin.api.PluginToolResult;
import fun.fengwk.kkstudio.harness.runtime.subagent.SubagentConfig;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactory;
import fun.fengwk.kkstudio.harness.tool.AgentToolBackend;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
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
 * 验证 {@link RuntimeToolsConfiguration#subagentConfigProvider} 把 {@code SystemSettings.AiRuntime}
 * 的五个 subagent 字段完整映射为 {@link SubagentConfig}。纯 JUnit 单元测试：不启动 Spring/Postgres，直接构造 provider 现读。
 */
class RuntimeToolsConfigurationTest {

  private static final AgentToolId LOAD_SKILL_TOOL_ID = new AgentToolId("test.load-skill");
  private static final AgentToolId TASK_TOOL_ID = new AgentToolId("test.task");
  private static final AgentToolId PLUGIN_TOOL_ID = new AgentToolId("test.plugin-internal");

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
        new RuntimeToolsConfiguration()
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
    ExecutorService executor = new RuntimeToolsConfiguration().subagentTaskExecutor(properties);
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
  void agentToolRegistryKeepsInternalPluginContributionInternal() {
    ToolDescriptor loadSkill = descriptor("load_skill");
    ToolDescriptor task = descriptor("task");
    ToolFactory loadSkillFactory = mock(ToolFactory.class);
    when(loadSkillFactory.definition())
        .thenReturn(
            new AgentToolDefinition(
                LOAD_SKILL_TOOL_ID, loadSkill, ToolVisibility.INTERNAL, AgentToolBackend.HOST));
    when(loadSkillFactory.priority()).thenReturn(0);
    ToolFactory taskFactory = mock(ToolFactory.class);
    when(taskFactory.definition())
        .thenReturn(
            new AgentToolDefinition(
                TASK_TOOL_ID, task, ToolVisibility.INTERNAL, AgentToolBackend.HOST));
    when(taskFactory.priority()).thenReturn(0);

    ToolDescriptor pluginDescriptor = descriptor("plugin-internal");
    PluginTool pluginTool =
        new PluginTool() {
          @Override
          public ToolDescriptor descriptor() {
            return pluginDescriptor;
          }

          @Override
          public PluginToolResult execute(PluginToolContext context, ToolCall call) {
            return null;
          }
        };
    HarnessPlugin plugin =
        HarnessPlugin.of(
            new PluginDescriptor(new PluginId("admission"), "Admission", "1", Set.of()),
            registrar ->
                registrar.registerTool(
                    "plugin-internal", PLUGIN_TOOL_ID, pluginTool, ToolVisibility.INTERNAL));

    AgentToolRegistry catalog =
        new AgentToolRegistry(
            List.of(loadSkillFactory, taskFactory),
            PluginCatalog.from(List.of(plugin)),
            EnvironmentToolCatalog.entries());

    assertEquals(
        ToolVisibility.INTERNAL,
        catalog.find(PLUGIN_TOOL_ID).orElseThrow().definition().visibility());
  }

  private static ToolDescriptor descriptor(String name) {
    return new ToolDescriptor(
        name,
        "1",
        name + " description",
        name,
        new ToolParamsSchema("arguments", Map.of(), Set.of(), false),
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
