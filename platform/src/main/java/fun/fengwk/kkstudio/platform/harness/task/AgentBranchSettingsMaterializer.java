package fun.fengwk.kkstudio.platform.harness.task;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser.ParsedAgentModelConfig;
import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;

import java.util.Objects;

/** 按最新 Agent/Model catalog 为新建或恢复的 Agent branch 物化路由 settings。 */
@Component
public final class AgentBranchSettingsMaterializer {

  private final AgentDefinitionRepository agentRepository;
  private final AgentModelRepository modelRepository;
  private final AgentModelRuntimeConfigParser modelConfigParser;
  private final AgentDefinitionConfigCodec agentConfigCodec;

  public AgentBranchSettingsMaterializer(
      AgentDefinitionRepository agentRepository,
      AgentModelRepository modelRepository,
      AgentModelRuntimeConfigParser modelConfigParser,
      AgentDefinitionConfigCodec agentConfigCodec) {
    this.agentRepository = Objects.requireNonNull(agentRepository, "agentRepository");
    this.modelRepository = Objects.requireNonNull(modelRepository, "modelRepository");
    this.modelConfigParser = Objects.requireNonNull(modelConfigParser, "modelConfigParser");
    this.agentConfigCodec = Objects.requireNonNull(agentConfigCodec, "agentConfigCodec");
  }

  /**
   * 普通 root 调用物化：只物化自身的 Agent/Model 选择，{@code environmentName} 恒为 null——root branch 没有父调用， 因此不受任何
   * Environment 继承语义影响。
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
      config = agentConfigCodec.decode(agent.getConfigJson());
    } catch (RuntimeException error) {
      throw new IllegalArgumentException("invalid subagent configuration: " + agentName, error);
    }
    return Boolean.TRUE.equals(config.getInheritParentEnvironment());
  }

  private BranchSettings materialize(
      String agentName, AgentDefinition agent, String environmentName) {
    AgentModel model =
        modelRepository.getByProviderNameAndName(
            agent.getModelProviderName(), agent.getModelName());
    if (model == null) {
      throw new IllegalArgumentException(
          "subagent model not found: " + agent.getModelProviderName() + "/" + agent.getModelName());
    }
    ParsedAgentModelConfig parsed;
    try {
      parsed = modelConfigParser.parse(model.getConfigJson());
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          "invalid subagent model configuration: " + agentName, error);
    }
    String variantName =
        agent.getVariant() == null || agent.getVariant().isBlank()
            ? parsed.defaultVariant()
            : agent.getVariant();
    // 校验所选 variant 存在于 catalog；ModelVariant 字段由 Resolver 在运行时按引用读取。
    parsed.variants().stream()
        .filter(candidate -> candidate.id().equals(variantName))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "subagent model variant not found: " + agentName + " variant=" + variantName));
    return new BranchSettings(
        agent.getName(),
        new ModelSelection(agent.getModelProviderName(), agent.getModelName(), variantName),
        environmentName);
  }
}
