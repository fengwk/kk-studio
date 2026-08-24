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
import fun.fengwk.kkstudio.harness.plugin.api.ToolContribution;
import fun.fengwk.kkstudio.harness.plugin.api.ToolVisibility;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessThreadChangeSource;
import fun.fengwk.kkstudio.harness.runtime.skill.LoadSkillTool;
import fun.fengwk.kkstudio.harness.runtime.skill.SkillBodyLoader;
import fun.fengwk.kkstudio.harness.runtime.skill.ThreadSelectedSkillLookup;
import fun.fengwk.kkstudio.harness.runtime.subagent.SubagentConfig;
import fun.fengwk.kkstudio.harness.runtime.subagent.SubagentConfigProvider;
import fun.fengwk.kkstudio.harness.runtime.subagent.SubagentRunRegistry;
import fun.fengwk.kkstudio.harness.runtime.subagent.TaskTool;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactories;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactory;
import fun.fengwk.kkstudio.harness.tool.ToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.platform.harness.configuration.HarnessExecutionAdmissionProperties;
import fun.fengwk.kkstudio.platform.harness.task.AgentBranchSettingsMaterializer;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 把普通 runtime 工具（当前为 {@code load_skill} 与 {@code task}）装配为 Spring {@code Tool} bean，并为 {@code
 * ToolFactories} 暴露为 {@link ToolFactory} bean；插件 Tool 由冻结 {@link PluginCatalog} 独立贡献。
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
    return ToolFactory.singleton(loadSkillTool);
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
    return ToolFactory.singleton(taskTool);
  }

  @Bean
  public ToolFactories toolFactories(ObjectProvider<ToolFactory> toolFactoryBeans) {
    return new ToolFactories(toolFactoryBeans.orderedStream().toList());
  }

  @Bean
  public ToolCatalog toolCatalog(ToolFactories toolFactories, PluginCatalog pluginCatalog) {
    List<ToolDescriptor> descriptors = new ArrayList<>(toolFactories.descriptors());
    Set<String> internalNames = new HashSet<>(Set.of(LoadSkillTool.NAME, TaskTool.NAME));
    for (ToolContribution contribution : pluginCatalog.tools()) {
      descriptors.add(contribution.descriptor());
      if (contribution.visibility() == ToolVisibility.INTERNAL) {
        internalNames.add(contribution.descriptor().name());
      }
    }
    return new ToolCatalog(descriptors, internalNames);
  }
}
