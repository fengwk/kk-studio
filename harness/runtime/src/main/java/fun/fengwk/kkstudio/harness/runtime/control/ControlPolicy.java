package fun.fengwk.kkstudio.harness.runtime.control;

import java.util.Objects;

/** 入队时定型的冻结 policy：每个 kind 各自决定消费模式。 */
public record ControlPolicy(ControlConsumptionMode steeringMode, ControlConsumptionMode followUpMode) {

  /** 默认 policy：所有 kind 走 ONE_AT_A_TIME。 */
  public static final ControlPolicy DEFAULT =
      new ControlPolicy(ControlConsumptionMode.ONE_AT_A_TIME, ControlConsumptionMode.ONE_AT_A_TIME);

  public ControlPolicy {
    steeringMode = Objects.requireNonNull(steeringMode, "steeringMode");
    followUpMode = Objects.requireNonNull(followUpMode, "followUpMode");
  }

  /** 根据 kind 取得其冻结消费模式。 */
  public ControlConsumptionMode modeFor(RunControlKind kind) {
    Objects.requireNonNull(kind, "kind");
    return kind == RunControlKind.STEER ? steeringMode : followUpMode;
  }
}
