package fun.fengwk.kkstudio.harness.runtime.configuration;

import fun.fengwk.kkstudio.harness.runtime.entry.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.EntryType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable Thread runtime configuration; live tool and skill capabilities are resolved per model
 * invocation.
 */
public record RuntimeConfigSnapshot(
    AgentSnapshot agent,
    ModelSnapshot model,
    String environmentName,
    List<String> toolNames,
    List<String> skillNames,
    boolean yoloEnabled)
    implements EntryPayload {

  public RuntimeConfigSnapshot {
    Objects.requireNonNull(agent, "agent");
    Objects.requireNonNull(model, "model");
    environmentName = canonicalEnvironmentName(environmentName);
    toolNames = canonicalNames(toolNames, "toolNames");
    skillNames = canonicalNames(skillNames, "skillNames");
    if ((!toolNames.isEmpty() || !skillNames.isEmpty()) && !model.descriptor().tools()) {
      throw new IllegalArgumentException(
          "model.descriptor().tools() must be true when tools or skills are configured");
    }
  }

  @Override
  public EntryType type() {
    return EntryType.RUNTIME_CONFIG;
  }

  public RuntimeConfigSnapshot withYoloEnabled(boolean yoloEnabled) {
    return new RuntimeConfigSnapshot(
        agent, model, environmentName, toolNames, skillNames, yoloEnabled);
  }

  public RuntimeConfigSnapshot withEnvironmentName(String environmentName) {
    return new RuntimeConfigSnapshot(
        agent, model, environmentName, toolNames, skillNames, yoloEnabled);
  }

  private static String canonicalEnvironmentName(String value) {
    if (value == null) {
      return null;
    }
    if (value.isBlank()) {
      throw new IllegalArgumentException("environmentName must not be blank");
    }
    if (!value.equals(value.trim())) {
      throw new IllegalArgumentException("environmentName must not contain surrounding whitespace");
    }
    if (value.length() > 128) {
      throw new IllegalArgumentException("environmentName must be <= 128 characters");
    }
    return value;
  }

  private static List<String> canonicalNames(List<String> source, String field) {
    Objects.requireNonNull(source, field);
    List<String> copy = new ArrayList<>(source.size());
    Set<String> seen = new HashSet<>();
    for (String value : source) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(field + " names must not be blank");
      }
      if (!value.equals(value.trim())) {
        throw new IllegalArgumentException(
            field + " names must not contain surrounding whitespace: " + value);
      }
      if (value.length() > 128
          || value.indexOf(':') >= 0
          || value.indexOf('/') >= 0
          || value.indexOf('@') >= 0
          || value.indexOf('\\') >= 0) {
        throw new IllegalArgumentException(field + " must contain short names only: " + value);
      }
      if (!seen.add(value)) {
        throw new IllegalArgumentException(field + " contains duplicate name: " + value);
      }
      copy.add(value);
    }
    copy.sort(String::compareTo);
    return List.copyOf(copy);
  }
}
