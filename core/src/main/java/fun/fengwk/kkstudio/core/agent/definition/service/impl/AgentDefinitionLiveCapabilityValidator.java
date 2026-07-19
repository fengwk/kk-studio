package fun.fengwk.kkstudio.core.agent.definition.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.environment.registry.LiveEnvironment;
import fun.fengwk.kkstudio.core.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtensionHost;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.share.model.AgentDefinitionConfigDTO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validates Agent live Environment / short-name tool / skill selections against the current live
 * registry and registered non-{@link ToolExecutionMode#ENVIRONMENT} platform tools.
 */
@Component
final class AgentDefinitionLiveCapabilityValidator {

  private final LiveEnvironmentRegistry environmentRegistry;
  private final HarnessExtensionHost extensionHost;

  AgentDefinitionLiveCapabilityValidator(
      LiveEnvironmentRegistry environmentRegistry, HarnessExtensionHost extensionHost) {
    this.environmentRegistry = Objects.requireNonNull(environmentRegistry, "environmentRegistry");
    this.extensionHost = Objects.requireNonNull(extensionHost, "extensionHost");
  }

  void validate(AgentDefinitionConfigDTO config) {
    Objects.requireNonNull(config, "config");
    String environmentName = blankToNull(config.getEnvironmentName());
    LiveEnvironment selectedEnvironment = null;
    if (environmentName != null) {
      selectedEnvironment = requireReadyEnvironment(environmentName);
    }
    validateShortNames(config.getTools(), "tools");
    validateShortNames(config.getSkills(), "skills");
    validateTools(listOrEmpty(config.getTools()), selectedEnvironment);
    validateSkills(listOrEmpty(config.getSkills()), selectedEnvironment);
  }

  private void validateTools(List<String> tools, LiveEnvironment selectedEnvironment) {
    Map<String, ToolDescriptor> platformTools = platformToolsByName();
    Map<String, ToolDescriptor> environmentTools =
        selectedEnvironment == null ? Map.of() : toolsByName(selectedEnvironment.tools());
    for (String name : tools) {
      if (platformTools.containsKey(name)) {
        continue;
      }
      if (environmentTools.containsKey(name)) {
        continue;
      }
      throw new IllegalArgumentException("unknown agent tool: " + name);
    }
  }

  private void validateSkills(List<String> skills, LiveEnvironment selectedEnvironment) {
    Map<String, DaemonSkillDescriptor> candidates = skillCandidates(selectedEnvironment);
    for (String name : skills) {
      if (!candidates.containsKey(name)) {
        throw new IllegalArgumentException("unknown agent skill: " + name);
      }
    }
  }

  /**
   * Platform-first skill catalog: READY {@code platform} Environment skills, then selected
   * Environment skills that do not collide with platform names.
   */
  private Map<String, DaemonSkillDescriptor> skillCandidates(LiveEnvironment selectedEnvironment) {
    Map<String, DaemonSkillDescriptor> result = new LinkedHashMap<>();
    environmentRegistry
        .find(LiveEnvironmentRegistry.PLATFORM_ENVIRONMENT_NAME)
        .filter(LiveEnvironment::isReady)
        .ifPresent(
            platform -> {
              for (DaemonSkillDescriptor skill : platform.skills()) {
                result.putIfAbsent(skill.name(), skill);
              }
            });
    if (selectedEnvironment != null
        && !LiveEnvironmentRegistry.PLATFORM_ENVIRONMENT_NAME.equals(
            selectedEnvironment.environmentName())) {
      for (DaemonSkillDescriptor skill : selectedEnvironment.skills()) {
        result.putIfAbsent(skill.name(), skill);
      }
    }
    return result;
  }

  private Map<String, ToolDescriptor> platformToolsByName() {
    Map<String, List<ToolDescriptor>> byName = new LinkedHashMap<>();
    extensionHost
        .toolFactories()
        .forEach(
            factory -> {
              ToolDescriptor descriptor = factory.descriptor();
              if (descriptor.executionMode() == ToolExecutionMode.ENVIRONMENT) {
                return;
              }
              byName
                  .computeIfAbsent(descriptor.name(), ignored -> new ArrayList<>())
                  .add(descriptor);
            });
    Map<String, ToolDescriptor> unique = new LinkedHashMap<>();
    for (Map.Entry<String, List<ToolDescriptor>> entry : byName.entrySet()) {
      if (entry.getValue().size() == 1) {
        unique.put(entry.getKey(), entry.getValue().get(0));
      }
    }
    return unique;
  }

  private static Map<String, ToolDescriptor> toolsByName(List<ToolDescriptor> tools) {
    Map<String, List<ToolDescriptor>> byName = new LinkedHashMap<>();
    for (ToolDescriptor tool : tools) {
      byName.computeIfAbsent(tool.name(), ignored -> new ArrayList<>()).add(tool);
    }
    Map<String, ToolDescriptor> unique = new LinkedHashMap<>();
    for (Map.Entry<String, List<ToolDescriptor>> entry : byName.entrySet()) {
      if (entry.getValue().size() == 1) {
        unique.put(entry.getKey(), entry.getValue().get(0));
      }
    }
    return unique;
  }

  private LiveEnvironment requireReadyEnvironment(String environmentName) {
    return environmentRegistry
        .find(environmentName)
        .filter(LiveEnvironment::isReady)
        .orElseThrow(
            () ->
                new IllegalArgumentException("agent environment is not READY: " + environmentName));
  }

  private static void validateShortNames(List<String> values, String field) {
    if (values == null) {
      return;
    }
    Set<String> seen = new LinkedHashSet<>();
    for (String value : values) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(
            "agent config " + field + " must contain non-blank short names");
      }
      if (!isShortName(value)) {
        throw new IllegalArgumentException(
            "agent config " + field + " must use short names only: " + value);
      }
      if (field.equals("skills") && !seen.add(value)) {
        // Skills are validated for duplicates by MutationFactory; keep defensive check.
        throw new IllegalArgumentException("agent skills must not contain duplicates: " + value);
      }
    }
  }

  private static boolean isShortName(String value) {
    return value.indexOf(':') < 0
        && value.indexOf('/') < 0
        && value.indexOf('@') < 0
        && value.indexOf('\\') < 0;
  }

  private static List<String> listOrEmpty(List<String> values) {
    return values == null ? List.of() : values;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
