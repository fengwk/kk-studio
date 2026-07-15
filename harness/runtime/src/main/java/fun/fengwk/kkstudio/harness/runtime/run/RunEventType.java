package fun.fengwk.kkstudio.harness.runtime.run;

/** Harness Runtime 持久 Run Event 类型。 */
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
  PERMISSION_REQUESTED("permission_requested"),
  PERMISSION_RESOLVED("permission_resolved"),
  TOOL_STARTED("tool_started"),
  TOOL_DELTA_BATCH("tool_delta_batch"),
  TOOL_COMPLETED("tool_completed"),
  TOOL_REQUEUED("tool_requeued"),
  RUNTIME_STATE_CHANGED("runtime_state_changed"),
  SUBAGENT_STARTED("subagent_started"),
  SUBAGENT_RESUMED("subagent_resumed"),
  SUBAGENT_COMPLETED("subagent_completed"),
  SUBAGENT_CANCEL_REQUESTED("subagent_cancel_requested"),
  RUN_WAITING("run_waiting"),
  RUN_COMPLETED("run_completed"),
  RUN_FAILED("run_failed"),
  RUN_CANCELLED("run_cancelled"),
  STEER_REQUESTED("steer_requested"),
  FOLLOW_UP_REQUESTED("follow_up_requested"),
  STEER_CONSUMED("steer_consumed"),
  FOLLOW_UP_CONSUMED("follow_up_consumed"),
  CONTROL_PROMOTED("control_promoted"),
  ABORT_REQUESTED("abort_requested"),
  RUN_REQUEUED("run_requeued");

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
