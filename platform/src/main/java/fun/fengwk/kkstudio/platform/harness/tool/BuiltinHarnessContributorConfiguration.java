package fun.fengwk.kkstudio.platform.harness.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.builtin.BuiltinHarnessContributor;
import fun.fengwk.kkstudio.harness.builtin.environment.ReadTool;
import fun.fengwk.kkstudio.harness.builtin.environment.ReadToolExecutor;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentConfig;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentConfigProvider;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentRunner;
import fun.fengwk.kkstudio.harness.builtin.subagent.TaskTool;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.harness.task.AgentBranchSettingsMaterializer;
import fun.fengwk.kkstudio.platform.harness.task.SubagentTaskRunner;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

/**
 * 装配第一方内置工具（{@code read} 与 {@code task}）并暴露唯一 {@link BuiltinHarnessContributor} bean。
 *
 * <p>核心内置装配为无条件装配（不使用 {@code @ConditionalOnBean}），确保缺失必要依赖时在启动期明确失败， 而不会静默降级并丢失最小功能集。
 *
 * <p>异步 task 的 {@link SubagentTaskRunner} 只负责校验并调用 Runtime 的原子接受；匹配与交付由 Runtime 负责。
 */
@Configuration(proxyBeanMethods = false)
public class BuiltinHarnessContributorConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public ReadTool readTool(ReadToolExecutor readToolExecutor) {
    return new ReadTool(readToolExecutor);
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
          aiRuntime.subagentMaxTurns());
    };
  }

  @Bean
  @ConditionalOnMissingBean
  public SubagentRunner subagentRunner(
      ObjectProvider<HarnessRuntime> runtimeProvider,
      AgentBranchSettingsMaterializer settingsMaterializer,
      SubagentConfigProvider subagentConfigProvider) {
    return new SubagentTaskRunner(
        runtimeProvider::getIfAvailable, settingsMaterializer, subagentConfigProvider);
  }

  @Bean
  @ConditionalOnMissingBean
  public TaskTool taskTool(SubagentRunner subagentRunner, ObjectMapper objectMapper) {
    return new TaskTool(subagentRunner, objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  public BuiltinHarnessContributor builtinHarnessContributor(ReadTool readTool, TaskTool taskTool) {
    return new BuiltinHarnessContributor(readTool, taskTool);
  }
}
