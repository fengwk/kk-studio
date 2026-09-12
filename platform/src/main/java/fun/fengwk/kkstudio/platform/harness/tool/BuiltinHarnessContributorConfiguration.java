package fun.fengwk.kkstudio.platform.harness.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.builtin.BuiltinHarnessContributor;
import fun.fengwk.kkstudio.harness.builtin.skill.LoadSkillTool;
import fun.fengwk.kkstudio.harness.builtin.skill.ThreadSelectedSkillLookup;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentConfig;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentConfigProvider;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentRunner;
import fun.fengwk.kkstudio.harness.builtin.subagent.TaskTool;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessThreadChangeSource;
import fun.fengwk.kkstudio.platform.harness.configuration.HarnessExecutionAdmissionProperties;
import fun.fengwk.kkstudio.platform.harness.subagent.SubagentRunRegistry;
import fun.fengwk.kkstudio.platform.harness.task.AgentBranchSettingsMaterializer;
import fun.fengwk.kkstudio.platform.harness.task.DatabaseSubagentRunner;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 装配第一方内置工具（{@code load_skill} 与 {@code task}）并暴露唯一 {@link BuiltinHarnessContributor} bean。
 *
 * <p>核心内置装配为无条件装配（不使用 {@code @ConditionalOnBean}），确保缺失必要依赖时在启动期明确失败， 而不会静默降级并丢失最小功能集。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HarnessExecutionAdmissionProperties.class)
public class BuiltinHarnessContributorConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public LoadSkillTool loadSkillTool(
      ThreadSelectedSkillLookup skillLookup, SystemSettingsSnapshot systemSettingsSnapshot) {
    // skill 正文加载超时：每次 execute 从 SystemSettingsSnapshot 现读 tool.skillLoadTimeoutMillis。
    return new LoadSkillTool(
        skillLookup,
        () -> Duration.ofMillis(systemSettingsSnapshot.get().tool().skillLoadTimeoutMillis()));
  }

  /**
   * task/subagent 的并发与预算现读通道：每次决策点从 {@link SystemSettingsSnapshot} 映射 {@code aiRuntime.subagent*}。
   */
  @Bean
  @ConditionalOnMissingBean
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
  @ConditionalOnMissingBean
  public SubagentRunRegistry subagentRunRegistry() {
    return new SubagentRunRegistry();
  }

  @Bean(name = "subagentTaskExecutor", destroyMethod = "close")
  @ConditionalOnMissingBean(name = "subagentTaskExecutor")
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
  @ConditionalOnMissingBean
  public SubagentRunner subagentRunner(
      ObjectProvider<HarnessRuntime> runtimeProvider,
      AgentBranchSettingsMaterializer settingsMaterializer,
      SubagentConfigProvider subagentConfigProvider,
      SubagentRunRegistry runRegistry,
      HarnessThreadChangeSource changeSource,
      @Qualifier("subagentTaskExecutor") ExecutorService executor,
      ObjectMapper objectMapper) {
    return new DatabaseSubagentRunner(
        runtimeProvider::getIfAvailable,
        settingsMaterializer,
        subagentConfigProvider,
        runRegistry,
        changeSource,
        executor,
        objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  public TaskTool taskTool(SubagentRunner subagentRunner, ObjectMapper objectMapper) {
    return new TaskTool(subagentRunner, objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  public BuiltinHarnessContributor builtinHarnessContributor(
      LoadSkillTool loadSkillTool, TaskTool taskTool) {
    return new BuiltinHarnessContributor(loadSkillTool, taskTool);
  }
}
