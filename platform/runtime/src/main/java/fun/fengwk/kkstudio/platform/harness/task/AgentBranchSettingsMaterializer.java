package fun.fengwk.kkstudio.platform.harness.task;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.platform.harness.builtin.BuiltinAgentExecutionMaterializer;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;

import java.util.Objects;

/** 按最新 Agent 配置为新建或恢复的 Agent branch 物化路由 settings。 */
@Component
public final class AgentBranchSettingsMaterializer {

  private final AgentDefinitionRepository agentRepository;
  private final BuiltinAgentExecutionMaterializer builtinMaterializer;
  private final AgentDefinitionConfigCodec agentConfigCodec;

  public AgentBranchSettingsMaterializer(
      AgentDefinitionRepository agentRepository,
      BuiltinAgentExecutionMaterializer builtinMaterializer,
      AgentDefinitionConfigCodec agentConfigCodec) {
    this.agentRepository = Objects.requireNonNull(agentRepository, "agentRepository");
    this.builtinMaterializer = Objects.requireNonNull(builtinMaterializer, "builtinMaterializer");
    this.agentConfigCodec = Objects.requireNonNull(agentConfigCodec, "agentConfigCodec");
  }

  /**
   * 普通 root 调用物化：只物化自身的执行选择，{@code environmentName} 恒为 null——root branch 没有父调用， 因此不受任何 Environment
   * 继承语义影响。
   */
  public BranchSettings materialize(String agentName) {
    return materialize(agentName, requireAgent(agentName), null);
  }

  /**
   * {@code task} 委派的子 Agent 物化：{@code environmentName} 由被调用 Agent 的 {@code
   * inheritParentEnvironment} 决定。
   *
   * <p>开关为 true 时使用调用方传入的父 Model invocation 冻结的 Environment name，否则为 null。Agent 定义只加载一次。
   */
  public BranchSettings materializeSubagent(String agentName, String parentEnvironmentName) {
    AgentDefinition agent = requireAgent(agentName);
    String environmentName =
        inheritParentEnvironment(agentName, agent) ? parentEnvironmentName : null;
    return materialize(agentName, agent, environmentName);
  }

  private AgentDefinition requireAgent(String agentName) {
    AgentDefinition agent = agentRepository.getByName(agentName);
    if (agent == null) {
      throw new IllegalArgumentException("subagent not found: " + agentName);
    }
    return agent;
  }

  /** 读取被调用 Agent 的继承开关；损坏或非法的持久化 config 以带上下文的稳定异常失败。 */
  private boolean inheritParentEnvironment(String agentName, AgentDefinition agent) {
    AgentDefinitionConfigDTO config;
    try {
      config = agentConfigCodec.decode(agent.configJson());
    } catch (RuntimeException error) {
      throw new IllegalArgumentException("invalid subagent configuration: " + agentName, error);
    }
    return Boolean.TRUE.equals(config.getInheritParentEnvironment());
  }

  private BranchSettings materialize(
      String agentName, AgentDefinition agent, String environmentName) {
    ExecutionSelection execution = agent.execution();
    if (execution == null) {
      // 未配置的内置 Agent 不能被普通调用当作路由来源：明确拒绝，不猜测或回退。
      throw new IllegalArgumentException("agent execution is not configured: " + agentName);
    }
    if (BuiltinExecution.DRIVER.equals(execution.driver())) {
      execution = builtinMaterializer.materialize(agentName, execution);
    }
    return new BranchSettings(agent.name(), execution, environmentName);
  }
}
