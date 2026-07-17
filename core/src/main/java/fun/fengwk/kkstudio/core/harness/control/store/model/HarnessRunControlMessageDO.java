package fun.fengwk.kkstudio.core.harness.control.store.model;

import lombok.Data;

import java.time.LocalDateTime;

/** harness_run_control_message 行映射；runId 可为 null（FOLLOW_UP 直接 promotion 时没有 active run）。 */
@Data
public class HarnessRunControlMessageDO {
  private Long id;
  private Long sessionId;
  private Long runId;
  private String controlKind;
  private String consumptionMode;
  private String messageJson;
  private String status;
  private Long consumedRunId;
  private Long consumedEntryId;
  private LocalDateTime createTime;
  private LocalDateTime consumedAt;
  private LocalDateTime updateTime;
}
