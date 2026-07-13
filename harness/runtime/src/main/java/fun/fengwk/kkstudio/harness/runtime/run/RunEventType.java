package fun.fengwk.kkstudio.harness.runtime.run;

/** T05 产生或消费的 Run Event 类型。 */
public enum RunEventType {
  RUN_STARTED("run_started"),
  TURN_STARTED("turn_started"),
  ASSISTANT_STARTED("assistant_started"),
  ASSISTANT_DELTA_BATCH("assistant_delta_batch"),
  ASSISTANT_COMPLETED("assistant_completed"),
  ASSISTANT_FAILED("assistant_failed"),
  RETRY_SCHEDULED("retry_scheduled"),
  COMPACTION_STARTED("compaction_started"),
  COMPACTION_COMPLETED("compaction_completed"),
  TOOL_PREPARED("tool_prepared"),
  RUN_WAITING("run_waiting"),
  RUN_COMPLETED("run_completed"),
  RUN_FAILED("run_failed"),
  RUN_CANCELLED("run_cancelled");

  private final String value;

  RunEventType(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  public static RunEventType fromValue(String value) {
    for (RunEventType type : values()) {
      if (type.value.equals(value)) {
        return type;
      }
    }
    throw new IllegalArgumentException("unknown run event type: " + value);
  }
}
