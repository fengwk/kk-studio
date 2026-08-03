package fun.fengwk.kkstudio.harness.runtime.entry;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Complete immutable settings snapshot for one Entry branch.
 *
 * <p>YOLO is intentionally absent: it is Thread runtime policy rather than branch history.
 */
public record BranchSettings(
    String environmentName,
    String agentName,
    ModelSelection model,
    String thinkingLevel,
    List<String> activeTools) {

  public BranchSettings {
    environmentName = nullableCanonicalName(environmentName, "environmentName");
    agentName = requireCanonicalName(agentName, "agentName");
    model = Objects.requireNonNull(model, "model");
    thinkingLevel = requireCanonicalName(thinkingLevel, "thinkingLevel");

    Objects.requireNonNull(activeTools, "activeTools");
    Set<String> uniqueTools = new LinkedHashSet<>();
    for (String activeTool : activeTools) {
      uniqueTools.add(requireCanonicalName(activeTool, "activeTools element"));
    }
    activeTools = List.copyOf(uniqueTools);
  }

  /** Returns a snapshot with only the agent reference replaced. */
  public BranchSettings withAgentName(String value) {
    return new BranchSettings(environmentName, value, model, thinkingLevel, activeTools);
  }

  /** Returns a snapshot with the complete model selection replaced atomically. */
  public BranchSettings withModel(ModelSelection value) {
    return new BranchSettings(environmentName, agentName, value, thinkingLevel, activeTools);
  }

  /** Returns a snapshot with only the thinking level replaced. */
  public BranchSettings withThinkingLevel(String value) {
    return new BranchSettings(environmentName, agentName, model, value, activeTools);
  }

  /** Returns a snapshot with only the ordered active tool names replaced. */
  public BranchSettings withActiveTools(List<String> values) {
    return new BranchSettings(environmentName, agentName, model, thinkingLevel, values);
  }

  /** Returns a snapshot with only the environment binding replaced. */
  public BranchSettings withEnvironmentName(String value) {
    return new BranchSettings(value, agentName, model, thinkingLevel, activeTools);
  }

  private static String nullableCanonicalName(String value, String field) {
    return value == null ? null : requireCanonicalName(value, field);
  }

  private static String requireCanonicalName(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must not contain surrounding whitespace");
    }
    if (value.length() > 128) {
      throw new IllegalArgumentException(field + " must be <= 128 characters");
    }
    return value;
  }
}
