package fun.fengwk.kkstudio.platform.configsync;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillRefDTO;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalog;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncParser.AgentSpec;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncKind;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncRef;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncSkipped;
import fun.fengwk.kkstudio.platform.harness.builtin.BuiltinConfigSyncAdapter;
import fun.fengwk.kkstudio.platform.harness.builtin.BuiltinConfigSyncAdapter.VariantIssue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 可用池的不动点判定：依赖模型池、Skill、MCP 工具与运行时工具目录，池内不满足者被移除，保留文件顺序作为写入顺序。
 *
 * <p>被移除的 Agent 不会进入写入计划，依赖它的条目（Subagent 或插件声明）随池判定被 skip，因此绝不会靠快照中的同名 Agent 蒙混过关。
 */
final class ConfigSyncAgentPlanning {

  private static final String RESOURCE = "config_sync";

  /** 池内逐轮移除不满足依赖者，直到不动点；池里仍有文件条目时，未满足的原因写入 skip。 */
  AgentPlan plan(
      List<AgentSpec> candidates,
      ConfigSyncSnapshot snapshot,
      Set<String> availableModelKeys,
      Map<String, Set<String>> availableSkills,
      Map<String, Set<String>> availableMcpTools,
      Set<String> knownMcpToolNames,
      RuntimeToolCatalog toolCatalog,
      List<ConfigSyncSkipped> skipped,
      List<ConfigSyncRef> imported) {
    Set<String> skippedAgents = ConfigSyncSkippedNames.of(skipped, ConfigSyncKind.AGENTS);
    Set<String> agentPool = new LinkedHashSet<>();
    for (var agent : snapshot.agents()) {
      if (!skippedAgents.contains(agent.name())) {
        agentPool.add(agent.name());
      }
    }
    candidates.forEach(spec -> agentPool.add(spec.name()));
    AgentDependencies dependencies =
        new AgentDependencies(
            availableModelKeys, availableSkills, availableMcpTools, knownMcpToolNames, toolCatalog);
    boolean agentPoolChanged = true;
    while (agentPoolChanged) {
      agentPoolChanged = false;
      for (AgentSpec spec : candidates) {
        if (!agentPool.contains(spec.name())) {
          continue;
        }
        if (dependencies.unsatisfiedReason(agentPool, spec) != null) {
          agentPool.remove(spec.name());
          agentPoolChanged = true;
        }
      }
    }
    List<AgentSpec> agents = new ArrayList<>();
    for (AgentSpec spec : candidates) {
      if (agentPool.contains(spec.name())) {
        agents.add(spec);
        imported.add(ConfigSyncRefs.ref(ConfigSyncKind.AGENTS, spec.name()));
      } else {
        skipped.add(
            new ConfigSyncSkipped(
                ConfigSyncKind.AGENTS,
                spec.name(),
                dependencies.unsatisfiedReason(agentPool, spec)));
      }
    }
    return new AgentPlan(agentPool, agents);
  }

  /**
   * Agent 引用的显式 variant 必须由导入后将生效的 Model config 声明。
   *
   * <p>仅校验引用可用 Model 且 variant 非空白的 Agent：缺 Model 仍按既有依赖 skip 政策处理；即使该 Agent 会因其它依赖被 skip， 已知非法
   * variant 仍必须先硬拒绝。错误消息只带安全条目名，不回显 raw variant、Model 配置或 cause。
   */
  static void rejectUnknownModelVariants(
      List<AgentSpec> agents,
      Map<String, ConfigSyncModelPlanning.ModelAvailability> availableModels) {
    for (AgentSpec spec : agents) {
      var selection = spec.builtinModel();
      if (selection == null) {
        continue;
      }
      ConfigSyncModelPlanning.ModelAvailability model = availableModels.get(selection.modelName());
      if (model == null) {
        continue;
      }
      VariantIssue issue =
          BuiltinConfigSyncAdapter.variantIssue(selection, model.kind(), model.config());
      if (issue == VariantIssue.GROUP_VARIANT) {
        throw DomainException.validation(
            RESOURCE, "agent references a model group with variant: " + spec.name());
      }
      if (issue == VariantIssue.UNKNOWN_VARIANT) {
        throw DomainException.validation(
            RESOURCE, "agent references unknown model variant: " + spec.name());
      }
    }
  }

  /** Agent 池内判定所需的外部可用性事实：模型键、Skill、MCP 工具与运行时工具目录。 */
  private record AgentDependencies(
      Set<String> availableModelKeys,
      Map<String, Set<String>> availableSkills,
      Map<String, Set<String>> availableMcpTools,
      Set<String> knownMcpToolNames,
      RuntimeToolCatalog toolCatalog) {

    /** 返回首个不满足依赖的原因；全部满足时返回 {@code null}。 */
    String unsatisfiedReason(Set<String> agentPool, AgentSpec spec) {
      var selection = spec.builtinModel();
      String modelKey = selection == null ? null : selection.modelName();
      if (modelKey != null && !availableModelKeys.contains(modelKey)) {
        return "missing model: " + modelKey;
      }
      AgentConfigDTO config = spec.properties().getConfig();
      if (config == null) {
        return "missing config";
      }
      String skillReason = skillReason(config);
      if (skillReason != null) {
        return skillReason;
      }
      if (config.getSubagents() != null) {
        for (String subagent : config.getSubagents()) {
          if (!agentPool.contains(subagent)) {
            return "missing subagent: " + subagent;
          }
        }
      }
      if (config.getTools() != null) {
        for (String tool : config.getTools()) {
          if (!toolAvailable(tool)) {
            return "unsupported or unknown agent tool: " + tool;
          }
        }
      }
      return null;
    }

    private String skillReason(AgentConfigDTO config) {
      if (config.getSkills() == null) {
        return null;
      }
      for (SkillRefDTO ref : config.getSkills()) {
        if (ref == null) {
          return "invalid skill reference";
        }
        if (!availableSkills.containsKey(ref.getPackageName())) {
          return "missing skill package: " + ref.getPackageName();
        }
        Set<String> packageSkills = availableSkills.get(ref.getPackageName());
        if (packageSkills != null && !packageSkills.contains(ref.getName())) {
          return "missing skill: " + ref.getPackageName() + "/" + ref.getName();
        }
      }
      return null;
    }

    /** MCP 工具按导入后将生效的工具池判定；其余工具按运行时目录的可选择性判定。 */
    private boolean toolAvailable(String toolName) {
      if (knownMcpToolNames.contains(toolName)) {
        return availableMcpTools.values().stream().anyMatch(names -> names.contains(toolName));
      }
      return toolCatalog
          .findTool(toolName)
          .map(contribution -> contribution.definition().visibility() == ToolVisibility.SELECTABLE)
          .orElse(false);
    }
  }

  /** 最终写入的 Agent 与导入后将生效的 Agent 池。 */
  record AgentPlan(Set<String> pool, List<AgentSpec> ordered) {}
}
