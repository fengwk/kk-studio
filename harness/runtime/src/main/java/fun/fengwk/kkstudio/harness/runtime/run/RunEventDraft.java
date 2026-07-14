package fun.fengwk.kkstudio.harness.runtime.run;

import java.util.Objects;

/** 与 Run 状态事务一起追加、尚未分配 sequence/id 的事件。 */
public record RunEventDraft(RunEventType type, String payloadJson) {

  public RunEventDraft {
    type = Objects.requireNonNull(type, "type");
    payloadJson = Objects.requireNonNull(payloadJson, "payloadJson");
  }
}
