package fun.fengwk.kkstudio.platform.catalog.definition.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.harness.tool.RuntimeToolCatalog;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentSkillRefDTO;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 依据 {@link RuntimeToolCatalog} 校验 Agent 选择；包含对工具环境需求的强制围栏校验。 */
@Component
public final class AgentDefinitionConfigValidator {

  private final RuntimeToolCatalog toolCatalog;

  public AgentDefinitionConfigValidator(RuntimeToolCatalog toolCatalog) {
    this.toolCatalog = Objects.requireNonNull(toolCatalog, "toolCatalog");
  }

  public void validate(AgentDefinitionConfigDTO config) {
    validate(config, null);
  }

  public void validate(AgentDefinitionConfigDTO config, UUID environmentId) {
    Objects.requireNonNull(config, "config");
    validateToolIds(config.getToolIds(), environmentId);
    validateSkills(config.getSkills());
    validateSubagents(config.getSubagents());
  }

  private void validateToolIds(List<String> values, UUID environmentId) {
    for (String value : values) {
      AgentToolId id;
      try {
        id = new AgentToolId(value);
      } catch (RuntimeException error) {
        throw new IllegalArgumentException("invalid agent tool id: " + value, error);
      }
      ToolContribution contribution = toolCatalog.findTool(id).orElse(null);
      if (contribution == null) {
        throw new IllegalArgumentException("unknown agent tool id: " + id);
      }
      if (contribution.definition().visibility() != ToolVisibility.SELECTABLE) {
        throw new IllegalArgumentException("internal tool cannot be selected by an Agent: " + id);
      }
      ToolRequirements requirements = contribution.requirements();
      if (requirements != null && requirements.environmentRequired() && environmentId == null) {
        throw new IllegalArgumentException(
            "tool " + id + " requires an environment but agent has no environment");
      }
      if (requirements != null && requirements.requiredEnvironmentId() != null) {
        UUID requiredEnvironmentId = requirements.requiredEnvironmentId().value();
        if (!requiredEnvironmentId.equals(environmentId)) {
          throw new IllegalArgumentException(
              "tool "
                  + id
                  + " requires environment "
                  + requiredEnvironmentId
                  + " but agent has environment "
                  + environmentId);
        }
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
