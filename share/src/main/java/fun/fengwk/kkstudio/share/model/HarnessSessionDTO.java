package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.LocalDateTime;

/** T15 harness session query; ids are decimal strings of the underlying bigint values. */
@Data
public class HarnessSessionDTO {

  private String sessionId;
  private String agentDefinitionId;
  private String title;
  private String rootSessionId;
  private String parentSessionId;
  private Integer depth;
  private String leafEntryId;
  private String activeRunId;
  private Boolean yoloEnabled;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
