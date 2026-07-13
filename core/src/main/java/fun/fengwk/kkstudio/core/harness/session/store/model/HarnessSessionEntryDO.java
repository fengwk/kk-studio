package fun.fengwk.kkstudio.core.harness.session.store.model;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class HarnessSessionEntryDO {
  private Long id;
  private Long sessionId;
  private Long parentEntryId;
  private Long runId;
  private String entryType;
  private String payloadJson;
  private LocalDateTime createTime;
}
