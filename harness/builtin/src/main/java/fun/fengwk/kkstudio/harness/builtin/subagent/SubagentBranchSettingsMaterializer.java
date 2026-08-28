package fun.fengwk.kkstudio.harness.builtin.subagent;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;

/** 按最新 Agent/Model catalog 物化子 Agent branch settings 的窄端口；由外层适配器实现，TaskTool 每次决策现读。 */
public interface SubagentBranchSettingsMaterializer {

  /** 物化 {@code agentName} 在 {@code environment} 上的完整 branch settings。 */
  BranchSettings materialize(String agentName, EnvironmentBinding environment);
}
