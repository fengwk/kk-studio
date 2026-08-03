package fun.fengwk.kkstudio.harness.runtime.entry;

import fun.fengwk.kkstudio.harness.tool.EnvironmentId;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Complete immutable settings snapshot for one Entry branch.
 *
 * <p>Environment is bound by its canonical {@link EnvironmentId} route identity; display names are
 * reusable and never enter this durable snapshot. YOLO is intentionally absent: it is Thread
 * runtime policy rather than branch history.
 */
public record BranchSettings(
    EnvironmentId environmentId,
    String agentName,
    ModelSelection model,
    String thinkingLevel,
    List<String> activeTools) {

  public BranchSettings {
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
    return new BranchSettings(environmentId, value, model, thinkingLevel, activeTools);
  }

  /** Returns a snapshot with the complete model selection replaced atomically. */
  public BranchSettings withModel(ModelSelection value) {
    return new BranchSettings(environmentId, agentName, value, thinkingLevel, activeTools);
  }

  /** Returns a snapshot with only the thinking level replaced. */
  public BranchSettings withThinkingLevel(String value) {
    return new BranchSettings(environmentId, agentName, model, value, activeTools);
  }

  /** Returns a snapshot with only the ordered active tool names replaced. */
  public BranchSettings withActiveTools(List<String> values) {
    return new BranchSettings(environmentId, agentName, model, thinkingLevel, values);
  }

  /** Returns a snapshot with only the Environment route identity replaced. */
  public BranchSettings withEnvironmentId(EnvironmentId value) {
    return new BranchSettings(value, agentName, model, thinkingLevel, activeTools);
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
