package fun.fengwk.kkstudio.harness.api.entry;

import java.util.Objects;

/**
 * 一次 Entry branch 的完整不可变 settings 快照。
 *
 * <p>冻结 Agent、执行选择与 Environment。环境选择归执行根；子线程初始设置不含环境，回合快照中的 {@code environmentName}
 * 是该回合规划时执行根已生效的环境，不是子线程自己的选择。name 可为 null 表示无环境；非 null 时是 Environment 的全局唯一不可变 name， Platform 在每个
 * Turn 将其解析为内部 EnvironmentId。目录只存在于具体工具 arguments 中，因此 branch 历史不保存任何 workspace/cwd 状态。YOLO
 * 被刻意省略：它属于 Thread runtime policy，而不是 branch 历史。
 */
public record BranchSettings(
    String agentName, ExecutionSelection execution, String environmentName) {

  /** Environment name 上限（与 Environment 资源名约束一致）。 */
  public static final int MAX_ENVIRONMENT_NAME_CHARS = 64;

  private static final int MAX_AGENT_NAME_CHARS = 128;

  public BranchSettings {
    agentName = requireCanonicalAgentName(agentName, "agentName");
    execution = Objects.requireNonNull(execution, "execution");
    environmentName = requireCanonicalEnvironmentName(environmentName, "environmentName");
  }

  /** 返回仅替换 agent 引用后的快照。 */
  public BranchSettings withAgentName(String value) {
    return new BranchSettings(value, execution, environmentName);
  }

  /** 返回整体原子替换 execution selection 后的快照。 */
  public BranchSettings withExecution(ExecutionSelection value) {
    return new BranchSettings(agentName, value, environmentName);
  }

  /** 返回整体替换 Environment 选择后的快照；null 表示解除环境选择。 */
  public BranchSettings withEnvironmentName(String value) {
    return new BranchSettings(agentName, execution, value);
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
