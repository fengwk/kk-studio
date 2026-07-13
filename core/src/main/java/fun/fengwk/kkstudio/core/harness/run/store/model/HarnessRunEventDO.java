package fun.fengwk.kkstudio.core.harness.run.store.model;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class HarnessRunEventDO {
  private Long id;
  private Long runId;
  private Long sequence;
  private String eventType;
  private String payloadJson;
  private LocalDateTime createTime;
}
