package fun.fengwk.kkstudio.platform.catalog.definition.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.harness.tool.AgentToolRegistry;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;

import java.util.List;
import java.util.Objects;

/** 只依据静态 catalog 校验 Agent 选择；在线 Environment 状态与校验无关。 */
@Component
final class AgentDefinitionConfigValidator {

  private final AgentToolRegistry toolRegistry;

  AgentDefinitionConfigValidator(AgentToolRegistry toolRegistry) {
    this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
  }

  void validate(AgentDefinitionConfigDTO config) {
    Objects.requireNonNull(config, "config");
    validateTools(config.getTools());
    validateSkills(config.getSkills());
    validateSubagents(config.getSubagents());
  }

  private void validateTools(List<String> names) {
    for (String name : names) {
      if (toolRegistry.findInternal(name).isPresent()) {
        throw new IllegalArgumentException("internal tool cannot be selected by an Agent: " + name);
      }
      if (toolRegistry.findSelectable(name).isEmpty()) {
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
