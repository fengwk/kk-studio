package fun.fengwk.kkstudio.core.harness.run.store.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class HarnessRunEventDO {
  private Long id;
  private Long runId;
  private Long sessionId;
  private Long sequence;
  private String eventType;
  private String payloadJson;
  private LocalDateTime createTime;
}
