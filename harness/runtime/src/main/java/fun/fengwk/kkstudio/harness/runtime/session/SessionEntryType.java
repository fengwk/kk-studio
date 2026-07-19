package fun.fengwk.kkstudio.harness.runtime.session;

/** 永久 Session Entry 的语义类型。 */
public enum SessionEntryType {
  /** Session 语义根；不承载运行时配置。 */
  ROOT("root"),
  MESSAGE("message"),
  /** Thread 级 Agent 切换审计；仅 id/name，不含完整 Agent 配置。 */
  AGENT_CHANGE("agent_change"),
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
