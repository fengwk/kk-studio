package fun.fengwk.kkstudio.web.upgrade;

/**
 * CLI 参数、进程环境与显式 prepare 编排的固定失败：只携带一个固定阶段与一句固定安全原因，绝不附带底层 cause、原始消息或任何输入值。
 *
 * <p>{@code outcomeUncertain} 为 true 表示目标库可能已经被改动（例如 prepare 已真实应用 baseline
 * 后才失败），调用方必须人工核验目标状态而不是自动重试。
 */
final class LegacyUpgradeCliException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** 失败发生的位置；只用于分类与运维定位，不含任何数据。 */
  enum Stage {
    USAGE,
    INPUT,
    ENVIRONMENT,
    PREPARE
  }

  private final Stage stage;

  private final boolean outcomeUncertain;

  LegacyUpgradeCliException(Stage stage, String reason) {
    this(stage, reason, false);
  }

  LegacyUpgradeCliException(Stage stage, String reason, boolean outcomeUncertain) {
    super(reason);
    this.stage = stage;
    this.outcomeUncertain = outcomeUncertain;
  }

  Stage stage() {
    return stage;
  }

  boolean outcomeUncertain() {
    return outcomeUncertain;
  }
}
