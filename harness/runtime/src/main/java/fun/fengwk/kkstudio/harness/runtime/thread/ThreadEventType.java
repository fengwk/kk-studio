package fun.fengwk.kkstudio.harness.runtime.thread;

/** Thread 事件 journal 类型；事件 id 即 SSE cursor。 */
public enum ThreadEventType {
  THREAD_STARTED("thread_started"),
  THREAD_RUNNING("thread_running"),
  THREAD_WAITING("thread_waiting"),
  THREAD_IDLE("thread_idle"),
  THREAD_FAILED("thread_failed"),
  THREAD_RETRYING("thread_retrying"),
  THREAD_STOPPED("thread_stopped"),
  TURN_STARTED("turn_started"),
  ASSISTANT_STARTED("assistant_started"),
  ASSISTANT_DELTA_BATCH("assistant_delta_batch"),
  ASSISTANT_COMPLETED("assistant_completed"),
  ASSISTANT_FAILED("assistant_failed"),
  COMPACTION_STARTED("compaction_started"),
  COMPACTION_COMPLETED("compaction_completed"),
  INPUT_APPLIED("input_applied"),
  INPUT_CANCELLED("input_cancelled"),
  AGENT_CHANGED("agent_changed"),
  MODEL_CHANGED("model_changed"),
  YOLO_CHANGED("yolo_changed"),
  TOOL_PREPARED("tool_prepared"),
  PERMISSION_REQUESTED("permission_requested"),
  PERMISSION_RESOLVED("permission_resolved"),
  TOOL_STARTED("tool_started"),
  TOOL_DELTA_BATCH("tool_delta_batch"),
  TOOL_COMPLETED("tool_completed"),
  TOOL_RESULTS_APPLIED("tool_results_applied"),
  SUBAGENT_STARTED("subagent_started"),
  SUBAGENT_COMPLETED("subagent_completed"),
  SUBAGENT_CANCEL_REQUESTED("subagent_cancel_requested");

  private final String value;

  ThreadEventType(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  public static ThreadEventType fromValue(String value) {
    for (ThreadEventType type : values()) {
      if (type.value.equals(value)) {
        return type;
      }
    }
    throw new IllegalArgumentException("unknown thread event type: " + value);
  }
}
