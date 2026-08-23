package fun.fengwk.kkstudio.platform.catalog.definition.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.tool.ToolCatalog;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;

import java.util.List;
import java.util.Objects;

/** 只依据静态 catalog 校验 Agent 选择；在线 Environment 状态与校验无关。 */
@Component
final class AgentDefinitionConfigValidator {

  private final ToolCatalog toolCatalog;

  AgentDefinitionConfigValidator(ToolCatalog toolCatalog) {
    this.toolCatalog = Objects.requireNonNull(toolCatalog, "toolCatalog");
  }

  void validate(AgentDefinitionConfigDTO config) {
    Objects.requireNonNull(config, "config");
    validateTools(config.getTools());
    validateSkills(config.getSkills());
    validateSubagents(config.getSubagents());
  }

  private void validateTools(List<String> names) {
    for (String name : names) {
      if (toolCatalog.findInternal(name).isPresent()) {
        throw new IllegalArgumentException(
            "internal platform tool cannot be selected by an Agent: " + name);
      }
      if (toolCatalog.findSelectable(name).isEmpty()) {
        throw new IllegalArgumentException("unknown agent tool: " + name);
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
