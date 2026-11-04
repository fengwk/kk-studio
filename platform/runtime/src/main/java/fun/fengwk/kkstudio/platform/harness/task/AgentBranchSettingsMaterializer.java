package fun.fengwk.kkstudio.platform.harness.task;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ExecutionSelection;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.harness.builtin.BuiltinAgentExecutionMaterializer;

import java.util.Objects;

/** 按最新 Agent 配置为新建或恢复的 Agent branch 物化路由 settings。 */
@Component
public final class AgentBranchSettingsMaterializer {

  private final AgentDefinitionRepository agentRepository;
  private final BuiltinAgentExecutionMaterializer builtinMaterializer;

  public AgentBranchSettingsMaterializer(
      AgentDefinitionRepository agentRepository,
      BuiltinAgentExecutionMaterializer builtinMaterializer) {
    this.agentRepository = Objects.requireNonNull(agentRepository, "agentRepository");
    this.builtinMaterializer = Objects.requireNonNull(builtinMaterializer, "builtinMaterializer");
  }

  /** 只物化执行选择；环境由执行根持有，子 Agent 不持有自己的选择。 */
  public BranchSettings materialize(String agentName) {
    return materialize(agentName, requireAgent(agentName));
  }

  private AgentDefinition requireAgent(String agentName) {
    AgentDefinition agent = agentRepository.getByName(agentName);
    if (agent == null) {
      throw new IllegalArgumentException("subagent not found: " + agentName);
    }
    return agent;
  }

  private BranchSettings materialize(String agentName, AgentDefinition agent) {
    ExecutionSelection execution = agent.execution();
    if (execution == null) {
      // 未配置的内置 Agent 不能被普通调用当作路由来源：明确拒绝，不猜测或回退。
      throw new IllegalArgumentException("agent execution is not configured: " + agentName);
    }
    if (BuiltinExecution.DRIVER.equals(execution.driver())) {
      execution = builtinMaterializer.materialize(agentName, execution);
    }
    return new BranchSettings(agent.name(), execution, null);
  }
}
