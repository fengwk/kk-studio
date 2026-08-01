package fun.fengwk.kkstudio.core.ai.catalog.definition.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.tool.ToolCatalog;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;

import java.util.List;
import java.util.Objects;

/**
 * Validates Agent selections against static catalogs only; live Environment state is irrelevant.
 */
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
  }

  private void validateTools(List<String> names) {
    for (String name : names) {
      if ("load_skill".equals(name)) {
        throw new IllegalArgumentException("load_skill is runtime-managed and cannot be selected");
      }
      if (toolCatalog.find(name).isEmpty()) {
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
}
