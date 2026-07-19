package fun.fengwk.kkstudio.harness.runtime.thread;

/** 有序 Thread 输入类型。 */
public enum ThreadInputType {
  USER_MESSAGE("user_message"),
  CUSTOM_MESSAGE("custom_message"),
  SET_AGENT("set_agent"),
  SET_MODEL("set_model"),
  SET_TOOLSET("set_toolset"),
  SET_YOLO("set_yolo");

  private final String value;

  ThreadInputType(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  public boolean isMessage() {
    return this == USER_MESSAGE || this == CUSTOM_MESSAGE;
  }

  public boolean isConfig() {
    return this == SET_AGENT || this == SET_MODEL || this == SET_TOOLSET || this == SET_YOLO;
  }

  public static ThreadInputType fromValue(String value) {
    for (ThreadInputType type : values()) {
      if (type.value.equals(value)) {
        return type;
      }
    }
    throw new IllegalArgumentException("unknown thread input type: " + value);
  }
}
