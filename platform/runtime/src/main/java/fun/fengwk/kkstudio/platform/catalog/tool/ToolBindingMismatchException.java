package fun.fengwk.kkstudio.platform.catalog.tool;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;

import java.util.Objects;

/**
 * 冻结 {@link ToolBinding} 与其目录 epoch 不一致时的确定性拒绝。
 *
 * <p>{@link Reason} 明确区分「该 epoch 下不存在」与「存在但定义/身份/requirements/来源已漂移」：两者都不出网，也都不按当前同名工具重放。
 */
public final class ToolBindingMismatchException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** 冻结绑定与租用目录不一致的具体维度。 */
  public enum Reason {
    /** 该 epoch 的目录中不存在该模型可见工具名。 */
    NOT_FOUND,
    /** 完整定义不一致。 */
    DEFINITION,
    /** 冻结贡献身份与目录贡献身份不一致。 */
    CONTRIBUTOR,
    /** environment 支持级别或必需环境不一致。 */
    REQUIREMENTS,
    /** 冻结插件来源与目录来源不一致。 */
    SOURCE
  }

  private final Reason reason;

  public ToolBindingMismatchException(Reason reason, String message) {
    super(Objects.requireNonNull(message, "message"));
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  public Reason reason() {
    return reason;
  }
}
