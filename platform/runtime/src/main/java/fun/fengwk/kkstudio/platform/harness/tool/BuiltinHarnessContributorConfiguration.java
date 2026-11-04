package fun.fengwk.kkstudio.platform.harness.tool;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.builtin.BuiltinHarnessContributor;
import fun.fengwk.kkstudio.harness.contributor.builtin.environment.FileTool;
import fun.fengwk.kkstudio.harness.contributor.builtin.subagent.SubagentConfig;
import fun.fengwk.kkstudio.harness.contributor.builtin.subagent.SubagentConfigProvider;
import fun.fengwk.kkstudio.harness.contributor.builtin.subagent.SubagentRunner;
import fun.fengwk.kkstudio.harness.contributor.builtin.subagent.TaskTool;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.spi.VfsGateway;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.platform.agenttask.AgentTaskService;
import fun.fengwk.kkstudio.platform.harness.task.AgentBranchSettingsMaterializer;
import fun.fengwk.kkstudio.platform.harness.task.SubagentTaskRunner;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

import java.util.List;

/**
 * 装配第一方内置工具（5 个逐调用路由的文件工具与 {@code task}）并暴露唯一 {@link BuiltinHarnessContributor} bean。
 *
 * <p>核心内置装配为无条件装配（不使用 {@code @ConditionalOnBean}），确保缺失必要依赖时在启动期明确失败， 而不会静默降级并丢失最小功能集。
 *
 * <p>异步 task 的 {@link SubagentTaskRunner} 只负责校验并调用 Runtime 的原子接受；匹配与交付由 Runtime 负责。
 */
@Configuration(proxyBeanMethods = false)
public class BuiltinHarnessContributorConfiguration {

  /** 五个文件工具：{@code vfs:} 路径走 Platform VFS，绝对宿主路径走分支绑定的 Environment。 */
  @Bean
  @ConditionalOnMissingBean(name = "builtinFileTools")
  public List<Tool> builtinFileTools(VfsGateway vfsGateway) {
    return List.of(
        new FileTool(
            "read",
            FileTool.Operation.READ,
            "read.md",
            EnvironmentCapabilityIds.FS_READ,
            ToolSideEffect.READ_ONLY,
            vfsGateway),
        new FileTool(
            "write",
            FileTool.Operation.WRITE,
            "write.md",
            EnvironmentCapabilityIds.FS_WRITE,
            ToolSideEffect.IDEMPOTENT,
            vfsGateway),
        new FileTool(
            "edit",
            FileTool.Operation.EDIT,
            "edit.md",
            EnvironmentCapabilityIds.FS_EDIT,
            ToolSideEffect.NON_IDEMPOTENT,
            vfsGateway),
        new FileTool(
            "grep",
            FileTool.Operation.GREP,
            "grep.md",
            EnvironmentCapabilityIds.FS_GREP,
            ToolSideEffect.READ_ONLY,
            vfsGateway),
        new FileTool(
            "find",
            FileTool.Operation.FIND,
            "find.md",
            EnvironmentCapabilityIds.FS_FIND,
            ToolSideEffect.READ_ONLY,
            vfsGateway));
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
      AgentTaskService agentTaskService,
      AgentBranchSettingsMaterializer settingsMaterializer,
      SubagentConfigProvider subagentConfigProvider) {
    return new SubagentTaskRunner(
        runtimeProvider::getIfAvailable,
        agentTaskService,
        settingsMaterializer,
        subagentConfigProvider);
  }

  @Bean
  @ConditionalOnMissingBean
  public TaskTool taskTool(SubagentRunner subagentRunner, ObjectMapper objectMapper) {
    return new TaskTool(subagentRunner, objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  public BuiltinHarnessContributor builtinHarnessContributor(
      List<Tool> builtinFileTools, TaskTool taskTool) {
    return new BuiltinHarnessContributor(builtinFileTools, taskTool);
  }
}
