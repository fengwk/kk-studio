package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.LocalDateTime;

/** 持久控制消息投影；所有 bigint ID 以字符串传输。 */
@Data
public class RunControlDTO {

  private String id;
  private String sessionId;
  private String runId;
  private String consumedRunId;
  private String consumedEntryId;
  private String kind;
  private String consumptionMode;
  private String status;
  private String content;
  private LocalDateTime createdAt;
  private LocalDateTime consumedAt;
}
