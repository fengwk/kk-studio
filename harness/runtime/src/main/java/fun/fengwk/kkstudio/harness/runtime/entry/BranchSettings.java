package fun.fengwk.kkstudio.harness.runtime.entry;

import fun.fengwk.kkstudio.harness.tool.EnvironmentId;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一次 Entry branch 的完整不可变 settings 快照。
 *
 * <p>Environment 通过其 canonical {@link EnvironmentId} 路由 identity 绑定；展示名称可重复使用， 不会进入该 durable
 * 快照。YOLO 被刻意省略：它属于 Thread runtime policy，而不是 branch 历史。
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

  /** 返回仅替换 agent 引用后的快照。 */
  public BranchSettings withAgentName(String value) {
    return new BranchSettings(environmentId, value, model, thinkingLevel, activeTools);
  }

  /** 返回整体原子替换 model selection 后的快照。 */
  public BranchSettings withModel(ModelSelection value) {
    return new BranchSettings(environmentId, agentName, value, thinkingLevel, activeTools);
  }

  /** 返回仅替换 thinking level 后的快照。 */
  public BranchSettings withThinkingLevel(String value) {
    return new BranchSettings(environmentId, agentName, model, value, activeTools);
  }

  /** 返回仅替换有序 active tool 名称后的快照。 */
  public BranchSettings withActiveTools(List<String> values) {
    return new BranchSettings(environmentId, agentName, model, thinkingLevel, values);
  }

  /** 返回仅替换 Environment 路由 identity 后的快照。 */
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
