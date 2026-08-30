package fun.fengwk.kkstudio.harness.runtime.entry;

import fun.fengwk.kkstudio.harness.environment.EnvironmentBinding;

import java.util.Objects;

/**
 * 一次 Entry branch 的完整不可变 settings 快照。
 *
 * <p>Environment 通过完整 {@link EnvironmentBinding}（canonical 路由名称 + workspace path）原子绑定；null 表示未选择
 * Environment。YOLO 被刻意省略：它属于 Thread runtime policy，而不是 branch 历史。
 */
public record BranchSettings(
    EnvironmentBinding environment, String agentName, ModelSelection model) {

  public BranchSettings {
    agentName = requireCanonicalName(agentName, "agentName");
    model = Objects.requireNonNull(model, "model");
  }

  /** 返回仅替换 agent 引用后的快照。 */
  public BranchSettings withAgentName(String value) {
    return new BranchSettings(environment, value, model);
  }

  /** 返回整体原子替换 model selection 后的快照。 */
  public BranchSettings withModel(ModelSelection value) {
    return new BranchSettings(environment, agentName, value);
  }

  /** 返回仅替换完整 Environment binding 后的快照（null 表示解绑）。 */
  public BranchSettings withEnvironment(EnvironmentBinding value) {
    return new BranchSettings(value, agentName, model);
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
