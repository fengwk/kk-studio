package fun.fengwk.kkstudio.platform.environment.skill;

import java.util.Objects;

/** 来源应用状态的稳定 wire 值，与 {@code environment_skill_source.status} 的取值域一致。 */
public enum SkillSourceStatus {
  UNAPPLIED("UNAPPLIED"),
  READY("READY"),
  FAILED("FAILED");

  private final String wireValue;

  SkillSourceStatus(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }

  public static SkillSourceStatus fromWireValue(String value) {
    for (SkillSourceStatus status : values()) {
      if (status.wireValue.equals(value)) {
        return status;
      }
    }
    throw new IllegalArgumentException("unsupported skill source status: " + value);
  }

  /** 从数据库列值恢复状态；null 表示列缺失，直接失败而不是猜默认值。 */
  public static SkillSourceStatus require(String value) {
    return fromWireValue(Objects.requireNonNull(value, "status"));
  }
}
