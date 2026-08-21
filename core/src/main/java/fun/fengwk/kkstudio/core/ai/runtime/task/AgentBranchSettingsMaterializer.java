package fun.fengwk.kkstudio.core.ai.runtime.task;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.core.ai.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.ai.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.ai.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.ai.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.core.ai.catalog.model.runtime.AgentModelRuntimeConfigParser.ParsedAgentModelConfig;
import fun.fengwk.kkstudio.core.ai.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.skill.LoadSkillTool;
import fun.fengwk.kkstudio.harness.runtime.subagent.SubagentBranchSettingsMaterializer;
import fun.fengwk.kkstudio.harness.runtime.subagent.SubagentConfigProvider;
import fun.fengwk.kkstudio.harness.runtime.subagent.TaskTool;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** 按最新 Agent/Model catalog 为新建或恢复的子 Agent 物化完整 branch settings。 */
@Component
public final class AgentBranchSettingsMaterializer implements SubagentBranchSettingsMaterializer {

  private final AgentDefinitionRepository agentRepository;
  private final AgentModelRepository modelRepository;
  private final AgentDefinitionConfigCodec configCodec;
  private final AgentModelRuntimeConfigParser modelConfigParser;
  private final SubagentConfigProvider configProvider;

  public AgentBranchSettingsMaterializer(
      AgentDefinitionRepository agentRepository,
      AgentModelRepository modelRepository,
      AgentDefinitionConfigCodec configCodec,
      AgentModelRuntimeConfigParser modelConfigParser,
      SubagentConfigProvider configProvider) {
    this.agentRepository = Objects.requireNonNull(agentRepository, "agentRepository");
    this.modelRepository = Objects.requireNonNull(modelRepository, "modelRepository");
    this.configCodec = Objects.requireNonNull(configCodec, "configCodec");
    this.modelConfigParser = Objects.requireNonNull(modelConfigParser, "modelConfigParser");
    this.configProvider = Objects.requireNonNull(configProvider, "configProvider");
  }

  public BranchSettings materialize(String agentName, EnvironmentBinding environment, int depth) {
    AgentDefinition agent = agentRepository.getByName(agentName);
    if (agent == null) {
      throw new IllegalArgumentException("subagent not found: " + agentName);
    }
    AgentDefinitionConfigDTO config;
    try {
      config = configCodec.decode(agent.getConfigJson());
    } catch (IllegalStateException error) {
      throw new IllegalArgumentException("invalid subagent configuration: " + agentName, error);
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
    LinkedHashSet<String> activeTools = new LinkedHashSet<>(config.getTools());
    if (!config.getSkills().isEmpty()) {
      activeTools.add(LoadSkillTool.NAME);
    }
    if (!config.getSubagents().isEmpty() && depth < configProvider.subagentConfig().maxDepth()) {
      activeTools.add(TaskTool.NAME);
    }
    return new BranchSettings(
        environment,
        agent.getName(),
        new ModelSelection(agent.getModelProviderName(), agent.getModelName(), variantName),
        List.copyOf(activeTools));
  }
}
