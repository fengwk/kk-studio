package fun.fengwk.kkstudio.platform.catalog.definition.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.common.skill.SkillNames;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.harness.tool.RuntimeToolCatalog;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;

import java.util.List;
import java.util.Objects;

/**
 * 依据 {@link RuntimeToolCatalog} 校验 Agent 选择：工具必须存在且可被 Agent 选择，Skill 名必须 canonical，subagent
 * 名遵守长度规则。
 *
 * <p>Agent 与 Environment 解耦：环境工具的可用性由每个 branch 的 Environment 选择在执行期决定，配置阶段不再按任何 Environment
 * 拒绝或过滤环境工具；Skill 名指向 Platform 的全局目录，因此配置阶段只校验形状，存在性与锁定由引用解析器负责。
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
    validateSkillNames(config.getSkills());
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

  private static void validateSkillNames(List<String> names) {
    if (names == null) {
      return;
    }
    for (String name : names) {
      if (name == null) {
        throw new IllegalArgumentException("agent skill name must not be null");
      }
      try {
        SkillNames.canonicalSkillName(name);
      } catch (IllegalArgumentException error) {
        throw new IllegalArgumentException("invalid agent skill name: " + name, error);
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
