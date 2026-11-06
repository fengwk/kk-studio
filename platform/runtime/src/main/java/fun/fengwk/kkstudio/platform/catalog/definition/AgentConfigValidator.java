package fun.fengwk.kkstudio.platform.catalog.definition;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillRefDTO;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillNames;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalogSource;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 依据 {@link RuntimeToolCatalogSource} 的 current 租用校验 Agent 选择：工具必须存在且可被 Agent 选择，Skill 名必须
 * canonical，subagent 名遵守长度规则。
 *
 * <p>Agent 与 Environment 解耦：环境工具的可用性由每个 branch 的 Environment 选择在执行期决定，配置阶段不对环境工具做环境绑定的过滤；Skill 名指向
 * Platform 的全局目录，因此配置阶段只校验形状，存在性与锁定由引用解析器负责。
 */
@Component
public final class AgentConfigValidator {

  private final RuntimeToolCatalogSource toolCatalogSource;

  public AgentConfigValidator(RuntimeToolCatalogSource toolCatalogSource) {
    this.toolCatalogSource = Objects.requireNonNull(toolCatalogSource, "toolCatalogSource");
  }

  public void validate(AgentConfigDTO config) {
    Objects.requireNonNull(config, "config");
    validateToolNames(config.getTools());
    validateSkills(config.getSkills());
    validateSubagents(config.getSubagents());
  }

  private void validateToolNames(List<String> values) {
    // 一次租用包住完整校验：存在性与可见性来自同一份目录快照。
    try (RuntimeToolCatalogSource.Lease lease = toolCatalogSource.acquire()) {
      for (String toolName : values) {
        ToolContribution contribution = lease.catalog().findTool(toolName).orElse(null);
        if (contribution == null) {
          throw DomainException.validation("agent", "unknown agent tool: " + toolName);
        }
        if (contribution.definition().visibility() != ToolVisibility.SELECTABLE) {
          throw DomainException.validation(
              "agent", "internal tool cannot be selected by an Agent: " + toolName);
        }
      }
    }
  }

  private static void validateSkills(List<SkillRefDTO> skills) {
    if (skills == null) {
      throw DomainException.validation("agent", "agent config skills is required");
    }
    Set<SkillRefKey> seen = new HashSet<>();
    for (SkillRefDTO skill : skills) {
      if (skill == null) {
        throw DomainException.validation(
            "agent", "agent config skills must not contain null elements");
      }
      try {
        SkillNames.canonicalPackageName(skill.getPackageName());
        SkillNames.canonicalSkillName(skill.getName());
      } catch (IllegalArgumentException error) {
        // SkillNames 是与导入路径共享的纯校验器；这里为 Agent 配置补上字段上下文，错误分类仍在产生处完成。
        throw DomainException.validation(
            "agent",
            "agent config skills must contain canonical short names: "
                + skill.getPackageName()
                + "/"
                + skill.getName(),
            error);
      }
      if (!seen.add(new SkillRefKey(skill.getPackageName(), skill.getName()))) {
        throw DomainException.validation(
            "agent",
            "agent config skills must not contain duplicates: "
                + skill.getPackageName()
                + "/"
                + skill.getName());
      }
    }
  }

  private record SkillRefKey(String packageName, String name) {}

  private static void validateSubagents(List<String> names) {
    for (String name : names) {
      if (name.length() > 64) {
        throw DomainException.validation(
            "agent", "subagent name must be <= 64 characters: " + name);
      }
    }
  }
}
