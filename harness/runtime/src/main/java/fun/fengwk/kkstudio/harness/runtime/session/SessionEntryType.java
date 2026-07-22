package fun.fengwk.kkstudio.harness.runtime.session;

/** 永久 Session Entry 的语义类型。 */
public enum SessionEntryType {
  /** Session 语义根；不承载运行时配置。 */
  ROOT("root"),
  MESSAGE("message"),
  /** 路径上的 Agent 配置变更；仅 id/name，不含完整 Agent 配置。 */
  AGENT_CHANGE("agent_change"),
  /** 路径上的 model/variant 配置变更；Thread 的 model/variant 为该 Thread 当前生效配置。 */
  MODEL_CHANGE("model_change"),
  COMPACTION("compaction"),
  BRANCH_SUMMARY("branch_summary"),
  CUSTOM("custom"),
  CUSTOM_MESSAGE("custom_message"),
  LABEL("label"),
  /** Assistant-side failure audit entry. UI-only; never projected into Provider Context. */
  ASSISTANT_ERROR("assistant_error");

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
