package fun.fengwk.kkstudio.core.harness.session.store.model;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class HarnessSessionEntryDO {
  private Long id;
  private String entryId;
  private String sessionId;
  private String parentEntryId;
  private String entryType;
  private String payloadJson;
  private LocalDateTime createTime;
}
