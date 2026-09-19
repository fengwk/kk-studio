package fun.fengwk.kkstudio.platform.harness.task;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser.ParsedAgentModelConfig;
import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;

import java.util.Objects;

/** 按最新 Agent/Model catalog 为新建或恢复的子 Agent 物化路由 branch settings。 */
@Component
public final class AgentBranchSettingsMaterializer {

  private final AgentDefinitionRepository agentRepository;
  private final AgentModelRepository modelRepository;
  private final AgentModelRuntimeConfigParser modelConfigParser;

  public AgentBranchSettingsMaterializer(
      AgentDefinitionRepository agentRepository,
      AgentModelRepository modelRepository,
      AgentModelRuntimeConfigParser modelConfigParser) {
    this.agentRepository = Objects.requireNonNull(agentRepository, "agentRepository");
    this.modelRepository = Objects.requireNonNull(modelRepository, "modelRepository");
    this.modelConfigParser = Objects.requireNonNull(modelConfigParser, "modelConfigParser");
  }

  /** 子 Agent 只物化自身的 Agent/Model 选择；不继承任何目录状态。 */
  public BranchSettings materialize(String agentName) {
    AgentDefinition agent = agentRepository.getByName(agentName);
    if (agent == null) {
      throw new IllegalArgumentException("subagent not found: " + agentName);
    }
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
        // 子 Agent 只物化自身的 Agent/Model 选择；Environment 由调用方按 inheritParentEnvironment 单独决定。
        null);
  }
}
