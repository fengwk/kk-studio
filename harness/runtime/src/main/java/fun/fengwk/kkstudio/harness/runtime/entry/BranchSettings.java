package fun.fengwk.kkstudio.harness.runtime.entry;

import fun.fengwk.kkstudio.harness.environment.EnvironmentWorkspacePath;

import java.util.Objects;

/**
 * 一次 Entry branch 的完整不可变 settings 快照。
 *
 * <p>Environment 只持久化 nullable canonical {@code workspacePath}（规则见 {@link
 * EnvironmentWorkspacePath}， {@code '.'} 表示 root）：Environment 的 durable 路由身份（{@code
 * environmentId}）由 Agent definition 每 turn 解析后与 本 workspace path 组合成 frozen {@code
 * EnvironmentBinding}。null 表示未选择 workspace。YOLO 被刻意省略：它属于 Thread runtime policy，而不是 branch 历史。
 */
public record BranchSettings(String workspacePath, String agentName, ModelSelection model) {

  public BranchSettings {
    if (workspacePath != null) {
      workspacePath = EnvironmentWorkspacePath.requireCanonicalRelativePath(workspacePath);
    }
    agentName = requireCanonicalName(agentName, "agentName");
    model = Objects.requireNonNull(model, "model");
  }

  /** 返回仅替换 agent 引用后的快照。 */
  public BranchSettings withAgentName(String value) {
    return new BranchSettings(workspacePath, value, model);
  }

  /** 返回整体原子替换 model selection 后的快照。 */
  public BranchSettings withModel(ModelSelection value) {
    return new BranchSettings(workspacePath, agentName, value);
  }

  /** 返回仅替换 workspace path 后的快照（null 表示解绑 workspace）。 */
  public BranchSettings withWorkspacePath(String value) {
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
