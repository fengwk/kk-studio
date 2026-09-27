package fun.fengwk.kkstudio.project.port;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;

/**
 * 跨宿主 Agent/环境能力的分支设置物化，由宿主（platform）适配实现。
 *
 * <p>Issue+Agent 首次接受执行时必须按 Agent 在当前宿主环境下的权威配置物化分支 settings；该能力属于宿主环境，不属于 Project 领域。
 */
public interface AgentBranchSettingsPort {

  /** 按 Agent 自然名称物化其当前环境下可用的 Branch 设置。 */
  BranchSettings materializeBranchSettings(String agentName);
}
