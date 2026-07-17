package fun.fengwk.kkstudio.core.harness.session.store.model;

import lombok.Data;

import java.time.LocalDateTime;

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
