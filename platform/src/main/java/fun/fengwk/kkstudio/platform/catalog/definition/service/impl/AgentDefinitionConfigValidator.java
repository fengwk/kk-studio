package fun.fengwk.kkstudio.platform.catalog.definition.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;

import java.util.List;
import java.util.Objects;

/** 只依据静态 catalog 校验 Agent 选择；在线 Environment 状态与校验无关。 */
@Component
final class AgentDefinitionConfigValidator {

  private final HarnessCatalog catalog;

  AgentDefinitionConfigValidator(HarnessCatalog catalog) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
  }

  void validate(AgentDefinitionConfigDTO config) {
    Objects.requireNonNull(config, "config");
    validateToolIds(config.getToolIds());
    validateSkills(config.getSkills());
    validateSubagents(config.getSubagents());
  }

  private void validateToolIds(List<String> values) {
    for (String value : values) {
      AgentToolId id;
      try {
        id = new AgentToolId(value);
      } catch (RuntimeException error) {
        throw new IllegalArgumentException("invalid agent tool id: " + value, error);
      }
      ToolContribution contribution = catalog.findTool(id).orElse(null);
      if (contribution == null) {
        throw new IllegalArgumentException("unknown agent tool id: " + id);
      }
      if (contribution.definition().visibility() != ToolVisibility.SELECTABLE) {
        throw new IllegalArgumentException("internal tool cannot be selected by an Agent: " + id);
      }
    }
  }

  private static void validateSkills(List<String> names) {
    for (String name : names) {
      if (name.length() > 128) {
        throw new IllegalArgumentException("agent skill name must be <= 128 characters: " + name);
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
