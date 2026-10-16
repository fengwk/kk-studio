package fun.fengwk.kkstudio.web.upgrade;

/**
 * 离线数据库转换的固定失败分类。
 *
 * <p>失败只以「发生位置 + 固定安全原因」表达：绝不携带底层 cause、SQL、字段值、凭据片段或连接信息，因此任何 catch 点都无法用 {@link #getCause()}
 * 反推出源数据。唯一的附加事实是目标事务的提交/回滚结果是否无法确认。
 *
 * <p>包内可见（package-private）：它只是离线升级包内部的错误契约，不是平台 API。
 */
final class LegacyDatabaseUpgradeException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** 失败发生的阶段；只用于分类与运维定位，不含任何数据。 */
  enum Stage {
    CONNECTION,
    SOURCE_PROFILE,
    SOURCE_HISTORY,
    SOURCE_DRAIN,
    CONVERT,
    TARGET_TRANSACTION,
    TARGET_SCHEMA,
    TARGET_PRISTINE,
    TARGET_WRITE,
    CREDENTIAL,
    CATALOG,
    VERIFY,
    COMMIT
  }

  private final Stage stage;

  private final boolean outcomeUncertain;

  LegacyDatabaseUpgradeException(Stage stage, String reason) {
    this(stage, reason, false);
  }

  LegacyDatabaseUpgradeException(Stage stage, String reason, boolean outcomeUncertain) {
    super(reason);
    this.stage = stage;
    this.outcomeUncertain = outcomeUncertain;
  }

  Stage stage() {
    return stage;
  }

  /** 目标事务的结果是否无法确认：为 true 时不能声称「零写入」，调用方必须人工核验目标库的实际状态，且不得自动重试。 */
  boolean outcomeUncertain() {
    return outcomeUncertain;
  }
}
