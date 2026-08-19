package fun.fengwk.kkstudio.core.ai.runtime.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.core.ai.runtime.HarnessThreadChangeSource;
import fun.fengwk.kkstudio.core.ai.runtime.task.AgentBranchSettingsMaterializer;
import fun.fengwk.kkstudio.core.ai.runtime.task.SubagentConfig;
import fun.fengwk.kkstudio.core.ai.runtime.task.SubagentRunRegistry;
import fun.fengwk.kkstudio.core.ai.runtime.task.TaskTool;
import fun.fengwk.kkstudio.core.systemsettings.SystemSettings;
import fun.fengwk.kkstudio.core.systemsettings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.harness.plugin.PluginCatalog;
import fun.fengwk.kkstudio.harness.plugin.ToolContribution;
import fun.fengwk.kkstudio.harness.plugin.ToolVisibility;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.skill.LoadSkillTool;
import fun.fengwk.kkstudio.harness.runtime.skill.SkillBodyLoader;
import fun.fengwk.kkstudio.harness.runtime.skill.ThreadSelectedSkillLookup;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactories;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactory;
import fun.fengwk.kkstudio.harness.tool.ToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 把普通 runtime 工具（当前为 {@code load_skill}）装配为 Spring {@code Tool} bean，并为 {@code ToolFactories} 暴露为
 * {@link ToolFactory} bean；插件 Tool 由冻结 {@link PluginCatalog} 独立贡献。
 */
@Configuration(proxyBeanMethods = false)
public class RuntimeToolsConfiguration {

  @Bean
  @ConditionalOnBean({ThreadSelectedSkillLookup.class, SkillBodyLoader.class})
  @ConditionalOnMissingBean
  public LoadSkillTool loadSkillTool(
      ThreadSelectedSkillLookup skillLookup,
      SkillBodyLoader skillBodyLoader,
      SystemSettingsSnapshot systemSettingsSnapshot) {
    // skill 正文加载超时：读取共享启动快照的 tool.skillLoadTimeoutMillis（装配期一次 DB 读取，DB 变更需重启生效）；再无默认值。
    Duration loadTimeout =
        Duration.ofMillis(systemSettingsSnapshot.get().tool().skillLoadTimeoutMillis());
    return new LoadSkillTool(skillLookup, skillBodyLoader, loadTimeout);
  }

  @Bean
  @ConditionalOnBean(LoadSkillTool.class)
  @ConditionalOnMissingBean(name = "loadSkillToolFactory")
  public ToolFactory loadSkillToolFactory(LoadSkillTool loadSkillTool) {
    return ToolFactory.singleton(loadSkillTool);
  }

  /** task/subagent 的并发与预算快照：读取共享启动快照的 {@code aiRuntime.subagent*}（装配期一次 DB 读取，DB 变更需重启生效）。 */
  @Bean
  public SubagentConfig subagentConfig(SystemSettingsSnapshot systemSettingsSnapshot) {
    SystemSettings.AiRuntime aiRuntime = systemSettingsSnapshot.get().aiRuntime();
    return new SubagentConfig(
        aiRuntime.subagentMaxDepth(),
        aiRuntime.subagentMaxConcurrency(),
        aiRuntime.subagentMaxTotalConcurrency(),
        Duration.ofMillis(aiRuntime.subagentIdleTimeoutMillis()),
        aiRuntime.subagentMaxTurns());
  }

  @Bean
  public SubagentRunRegistry subagentRunRegistry() {
    return new SubagentRunRegistry();
  }

  @Bean(name = "subagentTaskExecutor", destroyMethod = "close")
  public ExecutorService subagentTaskExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  @Bean
  public TaskTool taskTool(
      ObjectProvider<HarnessRuntime> runtimeProvider,
      AgentBranchSettingsMaterializer settingsMaterializer,
      SubagentConfig subagentConfig,
      SubagentRunRegistry runRegistry,
      HarnessThreadChangeSource changeSource,
      @Qualifier("subagentTaskExecutor") ExecutorService executor,
      ObjectMapper objectMapper) {
    return new TaskTool(
        runtimeProvider,
        settingsMaterializer,
        subagentConfig,
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
