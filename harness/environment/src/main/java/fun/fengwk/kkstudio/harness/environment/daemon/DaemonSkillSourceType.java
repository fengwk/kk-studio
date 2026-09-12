package fun.fengwk.kkstudio.harness.environment.daemon;

/** Daemon Skill 来源的两种稳定类型：本机 PATH 发现与受管 GIT 安装。 */
public enum DaemonSkillSourceType {
  PATH("path"),
  GIT("git");

  private final String wireValue;

  DaemonSkillSourceType(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }

  public static DaemonSkillSourceType fromWireValue(String value) {
    for (DaemonSkillSourceType type : values()) {
      if (type.wireValue.equals(value)) {
        return type;
      }
    }
    throw new IllegalArgumentException("unsupported daemon skill source type: " + value);
  }
}
