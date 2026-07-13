package fun.fengwk.kkstudio.harness.runtime.session;

/** 永久 Session Entry 的语义类型。 */
public enum SessionEntryType {
  MESSAGE("message"),
  AGENT_SNAPSHOT("agent_snapshot"),
  MODEL_CHANGE("model_change"),
  TOOLSET_CHANGE("toolset_change"),
  COMPACTION("compaction"),
  BRANCH_SUMMARY("branch_summary"),
  CUSTOM("custom"),
  CUSTOM_MESSAGE("custom_message"),
  LABEL("label");

  private final String value;

  SessionEntryType(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  public static SessionEntryType fromValue(String value) {
    for (SessionEntryType type : values()) {
      if (type.value.equals(value)) {
        return type;
      }
    }
    throw new IllegalArgumentException("unknown session entry type: " + value);
  }
}
