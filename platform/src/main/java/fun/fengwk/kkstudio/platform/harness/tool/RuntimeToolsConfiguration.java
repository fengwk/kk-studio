package fun.fengwk.kkstudio.platform.harness.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.plugin.api.PluginCatalog;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessThreadChangeSource;
import fun.fengwk.kkstudio.harness.runtime.skill.LoadSkillTool;
import fun.fengwk.kkstudio.harness.runtime.skill.SkillBodyLoader;
import fun.fengwk.kkstudio.harness.runtime.skill.ThreadSelectedSkillLookup;
import fun.fengwk.kkstudio.harness.runtime.subagent.SubagentConfig;
import fun.fengwk.kkstudio.harness.runtime.subagent.SubagentConfigProvider;
import fun.fengwk.kkstudio.harness.runtime.subagent.SubagentRunRegistry;
import fun.fengwk.kkstudio.harness.runtime.subagent.TaskTool;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactory;
import fun.fengwk.kkstudio.harness.tool.ToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.harness.configuration.HarnessExecutionAdmissionProperties;
import fun.fengwk.kkstudio.platform.harness.task.AgentBranchSettingsMaterializer;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 把普通 runtime 工具（当前为 {@code load_skill} 与 {@code task}）装配为 Spring {@code Tool} bean，并为 {@code
 * {@link ToolFactory} bean；普通 Tool 与插件 Tool 随后统一进入 {@link ToolContributionCatalog}。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HarnessExecutionAdmissionProperties.class)
public class RuntimeToolsConfiguration {

  @Bean
  @ConditionalOnBean({ThreadSelectedSkillLookup.class, SkillBodyLoader.class})
  @ConditionalOnMissingBean
  public LoadSkillTool loadSkillTool(
      ThreadSelectedSkillLookup skillLookup,
      SkillBodyLoader skillBodyLoader,
      SystemSettingsSnapshot systemSettingsSnapshot) {
    // skill 正文加载超时：每次 execute 从 SystemSettingsSnapshot 现读 tool.skillLoadTimeoutMillis。
    return new LoadSkillTool(
        skillLookup,
        skillBodyLoader,
        () -> Duration.ofMillis(systemSettingsSnapshot.get().tool().skillLoadTimeoutMillis()));
  }

  @Bean
  @ConditionalOnBean(LoadSkillTool.class)
  @ConditionalOnMissingBean(name = "loadSkillToolFactory")
  public ToolFactory loadSkillToolFactory(LoadSkillTool loadSkillTool) {
    return ToolFactory.singleton(
        LoadSkillTool.AGENT_TOOL_ID, loadSkillTool, ToolVisibility.INTERNAL, 0);
  }

  /**
   * task/subagent 的并发与预算现读通道：每次决策点从 {@link SystemSettingsSnapshot} 映射 {@code aiRuntime.subagent*}。
   */
  @Bean
  public SubagentConfigProvider subagentConfigProvider(
      SystemSettingsSnapshot systemSettingsSnapshot) {
    return () -> {
      SystemSettings.AiRuntime aiRuntime = systemSettingsSnapshot.get().aiRuntime();
      return new SubagentConfig(
          aiRuntime.subagentMaxDepth(),
          aiRuntime.subagentMaxConcurrency(),
          aiRuntime.subagentMaxTotalConcurrency(),
          Duration.ofMillis(aiRuntime.subagentIdleTimeoutMillis()),
          aiRuntime.subagentMaxTurns());
    };
  }

  @Bean
  public SubagentRunRegistry subagentRunRegistry() {
    return new SubagentRunRegistry();
  }

  @Bean(name = "subagentTaskExecutor", destroyMethod = "close")
  public ExecutorService subagentTaskExecutor(HarnessExecutionAdmissionProperties properties) {
    int concurrency = properties.getSubagent();
    return new ThreadPoolExecutor(
        concurrency,
        concurrency,
        0L,
        TimeUnit.MILLISECONDS,
        new SynchronousQueue<>(),
        Thread.ofVirtual().name("subagent-task-", 0L).factory(),
        new ThreadPoolExecutor.AbortPolicy());
  }

  @Bean
  public TaskTool taskTool(
      ObjectProvider<HarnessRuntime> runtimeProvider,
      AgentBranchSettingsMaterializer settingsMaterializer,
      SubagentConfigProvider subagentConfigProvider,
      SubagentRunRegistry runRegistry,
      HarnessThreadChangeSource changeSource,
      @Qualifier("subagentTaskExecutor") ExecutorService executor,
      ObjectMapper objectMapper) {
    return new TaskTool(
        () -> runtimeProvider.getIfAvailable(),
        settingsMaterializer,
        subagentConfigProvider,
        runRegistry,
        changeSource,
        executor,
        objectMapper);
  }

  @Bean
  public ToolFactory taskToolFactory(TaskTool taskTool) {
    return ToolFactory.singleton(TaskTool.AGENT_TOOL_ID, taskTool, ToolVisibility.INTERNAL, 0);
  }

  @Bean
  public ToolContributionCatalog toolContributionCatalog(
      ObjectProvider<ToolFactory> toolFactoryBeans, PluginCatalog pluginCatalog) {
    return new ToolContributionCatalog(toolFactoryBeans.orderedStream().toList(), pluginCatalog);
  }

  @Bean
  public ToolCatalog toolCatalog(ToolContributionCatalog toolContributionCatalog) {
    return toolContributionCatalog.toToolCatalog();
  }
}
