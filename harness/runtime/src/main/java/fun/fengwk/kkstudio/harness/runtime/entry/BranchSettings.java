package fun.fengwk.kkstudio.harness.runtime.entry;

import java.util.Objects;

/**
 * 一次 Entry branch 的完整不可变 settings 快照。
 *
 * <p>只冻结用户可见的 branch 选择：Agent 引用、Model selection、Environment 引用与用户维护的 Goal。{@code environmentName}
 * 可为 null，表示该 branch 未选择 Environment；非 null 时是 Environment 的全局唯一不可变 name，Platform 在每个 Turn 将其解析为内部
 * EnvironmentId。{@code goal} 可为 null，表示该 branch 当前没有用户设定的 Goal；非 null 时是 用户经 typed GOAL
 * 输入命令设置的目标正文快照，Agent 无权创改。目录只存在于具体工具 arguments 中，因此 branch 历史不保存任何 workspace/cwd 状态。YOLO 被刻意省略：它属于
 * Thread runtime policy，而不是 branch 历史。
 */
public record BranchSettings(
    String agentName, ModelSelection model, String environmentName, GoalSetting goal) {

  /** Environment name 上限（与 Environment 资源名约束一致）。 */
  public static final int MAX_ENVIRONMENT_NAME_CHARS = 64;

  private static final int MAX_AGENT_NAME_CHARS = 128;

  public BranchSettings {
    agentName = requireCanonicalAgentName(agentName, "agentName");
    model = Objects.requireNonNull(model, "model");
    environmentName = requireCanonicalEnvironmentName(environmentName, "environmentName");
  }

  /** 无 Goal 的快捷构造：新建 branch 的 ROOT settings 从不携带 Goal。 */
  public BranchSettings(String agentName, ModelSelection model, String environmentName) {
    this(agentName, model, environmentName, null);
  }

  /** 返回仅替换 agent 引用后的快照。 */
  public BranchSettings withAgentName(String value) {
    return new BranchSettings(value, model, environmentName, goal);
  }

  /** 返回整体原子替换 model selection 后的快照。 */
  public BranchSettings withModel(ModelSelection value) {
    return new BranchSettings(agentName, value, environmentName, goal);
  }

  /** 返回整体替换 Environment 选择后的快照；null 表示解除环境选择。 */
  public BranchSettings withEnvironmentName(String value) {
    return new BranchSettings(agentName, model, value, goal);
  }

  /** 返回整体替换用户 Goal 后的快照；null 表示用户清除 Goal。 */
  public BranchSettings withGoal(GoalSetting value) {
    return new BranchSettings(agentName, model, environmentName, value);
  }

  private static String requireCanonicalAgentName(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must not contain surrounding whitespace");
    }
    if (value.length() > MAX_AGENT_NAME_CHARS) {
      throw new IllegalArgumentException(
          field + " must be <= " + MAX_AGENT_NAME_CHARS + " characters");
    }
    return value;
  }

  /**
   * 校验 nullable Environment 名：null 保留（表示未选择 Environment）；非 null 必须非 blank、无首尾空白、不含 {@code '/'} 且不超过
   * {@value #MAX_ENVIRONMENT_NAME_CHARS} 字符。command payload 与本类型共用同一规则。
   */
  public static String requireCanonicalEnvironmentName(String value, String field) {
    if (value == null) {
      return null;
    }
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must not contain surrounding whitespace");
    }
    if (value.indexOf('/') >= 0) {
      throw new IllegalArgumentException(field + " must not contain '/'");
    }
    if (value.length() > MAX_ENVIRONMENT_NAME_CHARS) {
      throw new IllegalArgumentException(
          field + " must be <= " + MAX_ENVIRONMENT_NAME_CHARS + " characters");
    }
    return value;
  }
}
