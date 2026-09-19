package fun.fengwk.kkstudio.platform.catalog.definition.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.harness.tool.RuntimeToolCatalog;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentSkillRefDTO;

import java.util.List;
import java.util.Objects;

/**
 * 依据 {@link RuntimeToolCatalog} 校验 Agent 选择：工具必须存在且可被 Agent 选择，skill 与 subagent 名遵守长度规则。
 *
 * <p>Agent 与 Environment 解耦：环境工具的可用性由每个 branch 的 Environment 选择在执行期决定，配置阶段不再按任何 Environment
 * 拒绝或过滤环境工具。
 */
@Component
public final class AgentDefinitionConfigValidator {

  private final RuntimeToolCatalog toolCatalog;

  public AgentDefinitionConfigValidator(RuntimeToolCatalog toolCatalog) {
    this.toolCatalog = Objects.requireNonNull(toolCatalog, "toolCatalog");
  }

  public void validate(AgentDefinitionConfigDTO config) {
    Objects.requireNonNull(config, "config");
    validateToolNames(config.getTools());
    validateSkills(config.getSkills());
    validateSubagents(config.getSubagents());
  }

  private void validateToolNames(List<String> values) {
    for (String toolName : values) {
      ToolContribution contribution = toolCatalog.findTool(toolName).orElse(null);
      if (contribution == null) {
        throw new IllegalArgumentException("unknown agent tool: " + toolName);
      }
      if (contribution.definition().visibility() != ToolVisibility.SELECTABLE) {
        throw new IllegalArgumentException(
            "internal tool cannot be selected by an Agent: " + toolName);
      }
    }
  }

  private static void validateSkills(List<AgentSkillRefDTO> refs) {
    if (refs == null) {
      return;
    }
    for (AgentSkillRefDTO ref : refs) {
      if (ref != null && ref.getName() != null && ref.getName().length() > 128) {
        throw new IllegalArgumentException(
            "agent skill name must be <= 128 characters: " + ref.getName());
      }
    }
  }

  private static void validateSubagents(List<String> names) {
    for (String name : names) {
      if (name.length() > 64) {
        throw new IllegalArgumentException("subagent name must be <= 64 characters: " + name);
      }
    }
  }
}
