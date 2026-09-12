package fun.fengwk.kkstudio.harness.runtime.entry;

import java.util.Objects;

/**
 * 一次 Entry branch 的完整不可变 settings 快照。
 *
 * <p>只冻结用户可见的 branch 选择：Agent 引用与 Model selection。环境由 Agent definition 自身决定，目录只存在于具体工具 arguments 中，
 * 因此 branch 历史不保存任何 workspace/cwd 状态。YOLO 被刻意省略：它属于 Thread runtime policy，而不是 branch 历史。
 */
public record BranchSettings(String agentName, ModelSelection model) {

  public BranchSettings {
    agentName = requireCanonicalName(agentName, "agentName");
    model = Objects.requireNonNull(model, "model");
  }

  /** 返回仅替换 agent 引用后的快照。 */
  public BranchSettings withAgentName(String value) {
    return new BranchSettings(value, model);
  }

  /** 返回整体原子替换 model selection 后的快照。 */
  public BranchSettings withModel(ModelSelection value) {
    return new BranchSettings(agentName, value);
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
